package io.github.twme.virtualentities;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Reconciles platform-specific audience candidates with one entity's viewers.
 * The tracker owns viewer membership for UUIDs that it adds.
 *
 * @param <C> a platform player, connection, or tracking context
 */
public final class VirtualAudienceTracker<C> implements AutoCloseable {
    private final VirtualEntity entity;
    private final Function<C, VirtualViewer> viewerFactory;
    private final Predicate<C> visibilityRule;
    private final Map<UUID, Tracked<C>> tracked = new LinkedHashMap<>();
    private boolean closed;

    private VirtualAudienceTracker(
            VirtualEntity entity,
            Function<C, VirtualViewer> viewerFactory,
            Predicate<C> visibilityRule
    ) {
        this.entity = Objects.requireNonNull(entity, "entity");
        this.viewerFactory = Objects.requireNonNull(viewerFactory, "viewerFactory");
        this.visibilityRule = Objects.requireNonNull(visibilityRule, "visibilityRule");
    }

    public static <C> VirtualAudienceTracker<C> of(
            VirtualEntity entity,
            Function<C, VirtualViewer> viewerFactory,
            Predicate<C> visibilityRule
    ) {
        return new VirtualAudienceTracker<>(entity, viewerFactory, visibilityRule);
    }

    /** Re-evaluates one candidate and returns whether it is now tracked. */
    public synchronized boolean update(C candidate) {
        ensureOpen();
        Objects.requireNonNull(candidate, "candidate");
        VirtualViewer viewer = Objects.requireNonNull(viewerFactory.apply(candidate), "viewerFactory result");
        Tracked<C> previous = tracked.get(viewer.id());
        if (!visibilityRule.test(candidate)) {
            if (previous != null) {
                tracked.remove(viewer.id());
                removeOwnedViewer(viewer.id(), previous);
            }
            return false;
        }

        if (previous == null || !Objects.equals(previous.candidate(), candidate)) {
            if (previous != null) {
                removeOwnedViewer(viewer.id(), previous);
            }
            boolean owned = !entity.hasViewer(viewer.id());
            tracked.put(viewer.id(), new Tracked<>(candidate, owned));
            if (owned) {
                entity.addViewer(viewer);
            }
        }
        return true;
    }

    /** Reconciles the complete current candidate set, including disconnected candidates. */
    public synchronized void reconcile(Collection<? extends C> candidates) {
        ensureOpen();
        Objects.requireNonNull(candidates, "candidates");
        Set<UUID> present = new LinkedHashSet<>();
        for (C candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            VirtualViewer viewer = Objects.requireNonNull(viewerFactory.apply(candidate), "viewerFactory result");
            if (!present.add(viewer.id())) {
                throw new IllegalArgumentException("Duplicate audience candidate UUID " + viewer.id());
            }
            updateResolved(candidate, viewer);
        }

        for (UUID viewerId : Set.copyOf(tracked.keySet())) {
            if (!present.contains(viewerId)) {
                removeOwnedViewer(viewerId, tracked.remove(viewerId));
            }
        }
    }

    public synchronized Set<UUID> trackedViewerIds() {
        return Set.copyOf(tracked.keySet());
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        for (UUID viewerId : Set.copyOf(tracked.keySet())) {
            removeOwnedViewer(viewerId, tracked.get(viewerId));
        }
        tracked.clear();
        closed = true;
    }

    private void updateResolved(C candidate, VirtualViewer viewer) {
        Tracked<C> previous = tracked.get(viewer.id());
        if (!visibilityRule.test(candidate)) {
            if (previous != null) {
                tracked.remove(viewer.id());
                removeOwnedViewer(viewer.id(), previous);
            }
            return;
        }
        if (previous == null || !Objects.equals(previous.candidate(), candidate)) {
            if (previous != null) {
                removeOwnedViewer(viewer.id(), previous);
            }
            boolean owned = !entity.hasViewer(viewer.id());
            tracked.put(viewer.id(), new Tracked<>(candidate, owned));
            if (owned) {
                entity.addViewer(viewer);
            }
        }
    }

    private void removeOwnedViewer(UUID viewerId, Tracked<C> trackedViewer) {
        if (trackedViewer != null && trackedViewer.owned()) {
            entity.removeViewer(viewerId);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Audience tracker is closed");
        }
    }

    private record Tracked<C>(C candidate, boolean owned) {
    }
}
