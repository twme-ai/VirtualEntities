package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import io.github.twme.virtualentities.metadata.EntityMetadataRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Owns virtual entity identity, canonical viewer transports, outbound ordering, and lookup for one library instance.
 * Closing a manager is terminal. Transport callbacks may re-enter ordinary entity APIs and are never invoked while an
 * entity state monitor or the manager lifecycle lock is held. Exclusive manager operations are rejected from a
 * transport callback that is already delivering an entity operation.
 */
public final class VirtualEntityManager implements AutoCloseable {
    private static final int MAX_PACKETS_PER_BUNDLE = 4_096;

    private final EntityIdProvider entityIdProvider;
    private final EntityMetadataRegistry metadataRegistry;
    private final Object relationshipLock = new Object();
    private final Object lifecycleLock = new Object();
    private final ReentrantReadWriteLock coordinationLock = new ReentrantReadWriteLock(true);
    private final Map<Integer, VirtualEntity> byId = new ConcurrentHashMap<>();
    private final Map<UUID, VirtualEntity> byUuid = new ConcurrentHashMap<>();
    private final Map<UUID, ViewerState> viewerStates = new ConcurrentHashMap<>();
    private final ThreadLocal<BundleContext> activeBundle = new ThreadLocal<>();
    private volatile VirtualInteractionValidator interactionValidator = interaction -> false;
    private volatile boolean closed;

    VirtualEntityManager(EntityIdProvider entityIdProvider, EntityMetadataRegistry metadataRegistry) {
        this.entityIdProvider = Objects.requireNonNull(entityIdProvider, "entityIdProvider");
        this.metadataRegistry = Objects.requireNonNull(metadataRegistry, "metadataRegistry");
    }

    public VirtualEntity.Builder entity(EntityType type) {
        ensureOpen();
        return VirtualEntity.builder(this, Objects.requireNonNull(type, "type"));
    }

    /** Creates a player entity builder using the profile UUID as the entity UUID. */
    public VirtualEntity.Builder player(UserProfile profile) {
        ensureOpen();
        return VirtualEntity.builder(this, EntityTypes.PLAYER)
                .playerProfile(Objects.requireNonNull(profile, "profile"));
    }

    public Optional<VirtualEntity> find(int entityId) {
        return Optional.ofNullable(byId.get(entityId));
    }

    public Optional<VirtualEntity> find(UUID uuid) {
        return Optional.ofNullable(byUuid.get(uuid));
    }

    public Collection<VirtualEntity> entities() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public EntityMetadataRegistry metadataRegistry() {
        return metadataRegistry;
    }

    /** Returns whether this manager has been permanently closed. */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Replaces the current transport for a UUID and replays every visible managed entity to it.
     * Platforms should call this after a reconnect even when their player context object is reused.
     */
    public void replaceViewer(VirtualViewer viewer) {
        try (Operation ignored = exclusiveOperation()) {
            ensureOpen();
            if (activeBundle.get() != null) {
                throw new IllegalStateException("Viewer transports cannot be replaced inside a bundle scope");
            }
            VirtualViewer replacement = Objects.requireNonNull(viewer, "viewer");
            ViewerState state = viewerStates.get(replacement.id());
            if (state == null) {
                throw new IllegalArgumentException("Viewer is not registered: " + replacement.id());
            }
            state.lock.lock();
            try {
                state.viewer = replacement;
                state.generation++;
                replayViewerReplacement(replacement, null);
            } finally {
                state.lock.unlock();
            }
        }
    }

    /**
     * Installs platform authorization for inbound interactions. The default rejects every interaction.
     * Core filtering only proves entity ownership, spawn state, and viewer membership.
     */
    public void interactionValidator(VirtualInteractionValidator validator) {
        ensureOpen();
        interactionValidator = Objects.requireNonNull(validator, "validator");
    }

    /**
     * Runs entity updates in one client bundle per affected viewer when their protocol supports it.
     * Only packets emitted on the calling thread are captured, and nested calls on that thread join the
     * outer scope. If {@code updates} throws, already queued packets are still flushed to match the updated
     * entity state, then the original exception is rethrown. A flush failure is attached as a suppressed
     * exception when the callback also failed.
     */
    public void bundle(Runnable updates) {
        try (Operation ignored = exclusiveOperation()) {
            bundleLocked(updates);
        }
    }

    private void bundleLocked(Runnable updates) {
        ensureOpen();
        Objects.requireNonNull(updates, "updates");
        if (activeBundle.get() != null) {
            updates.run();
            return;
        }

        BundleContext context = new BundleContext();
        Throwable updateFailure = null;
        activeBundle.set(context);
        try {
            updates.run();
        } catch (RuntimeException | Error failure) {
            updateFailure = failure;
        } finally {
            activeBundle.remove();
        }

        try {
            flush(context);
        } catch (RuntimeException | Error flushFailure) {
            if (updateFailure == null) {
                throw flushFailure;
            }
            updateFailure.addSuppressed(flushFailure);
        }
        rethrow(updateFailure);
    }

    /** Filters and dispatches a pre-26.1 interact-entity packet when it targets a visible entity. */
    public Optional<VirtualEntityInteraction> handleInteraction(
            User actor,
            WrapperPlayClientInteractEntity packet
    ) {
        Objects.requireNonNull(packet, "packet");
        VirtualEntityInteraction.Action action = switch (packet.getAction()) {
            case INTERACT -> VirtualEntityInteraction.Action.INTERACT;
            case INTERACT_AT -> VirtualEntityInteraction.Action.INTERACT_AT;
            case ATTACK -> VirtualEntityInteraction.Action.ATTACK;
        };
        return dispatchInteraction(
                actor,
                packet.getEntityId(),
                action,
                action == VirtualEntityInteraction.Action.ATTACK
                        ? Optional.empty()
                        : Optional.ofNullable(packet.getHand()),
                action == VirtualEntityInteraction.Action.INTERACT_AT
                        ? Optional.ofNullable(packet.getLocation())
                        : Optional.empty(),
                packet.isSneaking().orElse(false)
        );
    }

    /** Filters and dispatches a 26.1+ attack packet when it targets a visible entity. */
    public Optional<VirtualEntityInteraction> handleInteraction(User actor, WrapperPlayClientAttack packet) {
        Objects.requireNonNull(packet, "packet");
        return dispatchInteraction(
                actor,
                packet.getEntityId(),
                VirtualEntityInteraction.Action.ATTACK,
                Optional.empty(),
                Optional.empty(),
                false
        );
    }

    int nextEntityId() {
        ensureOpen();
        int id = entityIdProvider.nextEntityId();
        if (byId.containsKey(id)) {
            throw new IllegalStateException("EntityIdProvider returned duplicate ID " + id);
        }
        return id;
    }

    void register(VirtualEntity entity) {
        synchronized (lifecycleLock) {
            ensureOpen();
            if (byId.putIfAbsent(entity.entityId(), entity) != null) {
                throw new IllegalArgumentException("Duplicate virtual entity ID " + entity.entityId());
            }
            if (byUuid.putIfAbsent(entity.uuid(), entity) != null) {
                byId.remove(entity.entityId(), entity);
                throw new IllegalArgumentException("Duplicate virtual entity UUID " + entity.uuid());
            }
        }
    }

    void unregister(VirtualEntity entity) {
        byId.remove(entity.entityId(), entity);
        byUuid.remove(entity.uuid(), entity);
    }

    Object relationshipLock() {
        return relationshipLock;
    }

    void registerViewerTransport(VirtualEntity source, VirtualViewer viewer, boolean newMembership) {
        ensureOpen();
        Holder<VirtualViewer> previousHolder = new Holder<>();
        ViewerState state = viewerStates.compute(viewer.id(), (viewerId, current) -> {
            ViewerState resolved = current == null ? new ViewerState() : current;
            resolved.lock.lock();
            boolean success = false;
            try {
                previousHolder.value = resolved.viewer;
                if (activeBundle.get() != null && resolved.viewer != null && resolved.viewer != viewer) {
                    throw new IllegalStateException("Viewer transports cannot be replaced inside a bundle scope");
                }
                if (newMembership) {
                    resolved.memberships++;
                }
                if (resolved.viewer != viewer) {
                    resolved.viewer = viewer;
                    resolved.generation++;
                }
                success = true;
                return resolved;
            } finally {
                if (!success) {
                    resolved.lock.unlock();
                }
            }
        });
        try {
            VirtualViewer previous = previousHolder.value;
            if (previous != null && previous != viewer) {
                replayViewerReplacement(viewer, source);
            }
        } finally {
            state.lock.unlock();
        }
    }

    void unregisterViewerTransport(UUID viewerId) {
        viewerStates.computeIfPresent(viewerId, (ignored, state) -> {
            state.lock.lock();
            try {
                if (state.memberships <= 0) {
                    throw new IllegalStateException("Viewer membership count underflow for " + viewerId);
                }
                state.memberships--;
                if (state.memberships == 0) {
                    state.viewer = null;
                    state.generation++;
                    return null;
                }
                return state;
            } finally {
                state.lock.unlock();
            }
        });
    }

    private void replayViewerReplacement(VirtualViewer viewer, VirtualEntity source) {
        Throwable failure = null;
        for (VirtualEntity entity : List.copyOf(byId.values())) {
            if (entity != source) {
                try {
                    entity.replaceViewerTransport(viewer);
                } catch (RuntimeException | Error entityFailure) {
                    if (failure == null) {
                        failure = entityFailure;
                    } else {
                        failure.addSuppressed(entityFailure);
                    }
                }
            }
        }
        rethrow(failure);
    }

    void send(VirtualViewer viewer, PacketWrapper<?> packet) {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(packet, "packet");
        ViewerState state = viewerStates.get(viewer.id());
        if (state == null) {
            return;
        }
        BundleContext context = activeBundle.get();
        if (context != null) {
            context.enqueue(viewer.id(), state.generation, packet);
            return;
        }
        sendNow(viewer.id(), packet);
    }

    void sendDirect(VirtualViewer viewer, PacketWrapper<?> packet) {
        ViewerState state = viewerStates.get(viewer.id());
        if (state == null) {
            viewer.send(packet);
            return;
        }
        state.lock.lock();
        try {
            viewer.send(packet);
        } finally {
            state.lock.unlock();
        }
    }

    private Optional<VirtualEntityInteraction> dispatchInteraction(
            User actor,
            int entityId,
            VirtualEntityInteraction.Action action,
            Optional<com.github.retrooper.packetevents.protocol.player.InteractionHand> hand,
            Optional<com.github.retrooper.packetevents.util.Vector3d> target,
            boolean sneaking
    ) {
        Objects.requireNonNull(actor, "actor");
        UUID actorId = actor.getUUID();
        if (actorId == null) {
            return Optional.empty();
        }
        VirtualEntity entity = byId.get(entityId);
        if (entity == null || !entity.isSpawned() || !entity.hasViewer(actorId)) {
            return Optional.empty();
        }
        VirtualEntityInteraction interaction = new VirtualEntityInteraction(
                entity,
                actor,
                action,
                hand,
                target,
                sneaking
        );
        if (target.isPresent() && !finite(target.get())) {
            return Optional.empty();
        }
        if (!interactionValidator.test(interaction)) {
            return Optional.empty();
        }
        entity.dispatchInteraction(interaction);
        return Optional.of(interaction);
    }

    @Override
    public void close() {
        try (Operation ignored = exclusiveOperation()) {
            if (activeBundle.get() != null) {
                throw new IllegalStateException("VirtualEntityManager cannot be closed inside a bundle scope");
            }
            List<VirtualEntity> entities;
            synchronized (lifecycleLock) {
                if (closed) {
                    return;
                }
                closed = true;
                entities = List.copyOf(byId.values());
            }
            Throwable failure = null;
            for (VirtualEntity entity : entities) {
                try {
                    entity.remove();
                } catch (RuntimeException | Error entityFailure) {
                    failure = appendFailure(failure, entityFailure);
                }
            }
            viewerStates.clear();
            rethrow(failure);
        }
    }

    private void flush(BundleContext context) {
        Throwable failure = null;
        for (PendingViewer pending : context.pendingViewers()) {
            try {
                flush(pending);
            } catch (RuntimeException | Error viewerFailure) {
                if (failure == null) {
                    failure = viewerFailure;
                } else {
                    failure.addSuppressed(viewerFailure);
                }
            }
        }
        rethrow(failure);
    }

    private void flush(PendingViewer pending) {
        ViewerState state = viewerStates.get(pending.viewerId());
        if (state == null) {
            return;
        }
        state.lock.lock();
        try {
            VirtualViewer viewer = state.viewer;
            if (viewer == null || state.generation != pending.generation()) {
                return;
            }
            flushLocked(viewer, pending.packets());
        } finally {
            state.lock.unlock();
        }
    }

    private static void flushLocked(VirtualViewer viewer, List<PacketWrapper<?>> packets) {
        boolean bundled = viewer.clientVersion().isNewerThanOrEquals(ClientVersion.V_1_19_4);
        if (!bundled) {
            sendPacketsCollectingFailures(viewer, packets);
            return;
        }

        Throwable failure = null;
        for (int offset = 0; offset < packets.size(); offset += MAX_PACKETS_PER_BUNDLE) {
            int end = Math.min(offset + MAX_PACKETS_PER_BUNDLE, packets.size());
            try {
                flushBundleChunk(viewer, packets.subList(offset, end));
            } catch (RuntimeException | Error chunkFailure) {
                failure = appendFailure(failure, chunkFailure);
            }
        }
        rethrow(failure);
    }

    private static void flushBundleChunk(VirtualViewer viewer, List<PacketWrapper<?>> packets) {
        boolean opened = false;
        Throwable failure = null;
        try {
            viewer.send(new WrapperPlayServerBundle());
            opened = true;
            for (PacketWrapper<?> packet : packets) {
                try {
                    viewer.send(packet);
                } catch (RuntimeException | Error packetFailure) {
                    failure = appendFailure(failure, packetFailure);
                }
            }
        } finally {
            if (opened) {
                try {
                    viewer.send(new WrapperPlayServerBundle());
                } catch (RuntimeException | Error closeFailure) {
                    failure = appendFailure(failure, closeFailure);
                }
            }
        }
        rethrow(failure);
    }

    private static void sendPacketsCollectingFailures(
            VirtualViewer viewer,
            List<PacketWrapper<?>> packets
    ) {
        Throwable failure = null;
        for (PacketWrapper<?> packet : packets) {
            try {
                viewer.send(packet);
            } catch (RuntimeException | Error packetFailure) {
                failure = appendFailure(failure, packetFailure);
            }
        }
        rethrow(failure);
    }

    private static Throwable appendFailure(Throwable current, Throwable added) {
        if (current == null) {
            return added;
        }
        current.addSuppressed(added);
        return current;
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static final class BundleContext {
        private final Map<UUID, PendingViewer> viewers = new LinkedHashMap<>();

        private void enqueue(UUID viewerId, long generation, PacketWrapper<?> packet) {
            PendingViewer pending = viewers.computeIfAbsent(
                    viewerId,
                    ignored -> new PendingViewer(viewerId, generation)
            );
            if (pending.generation() != generation) {
                throw new IllegalStateException("Viewer transport changed inside a bundle scope");
            }
            pending.packets().add(packet);
        }

        private Collection<PendingViewer> pendingViewers() {
            return viewers.values();
        }
    }

    private record PendingViewer(UUID viewerId, long generation, List<PacketWrapper<?>> packets) {
        private PendingViewer(UUID viewerId, long generation) {
            this(viewerId, generation, new ArrayList<>());
        }
    }

    private void sendNow(UUID viewerId, PacketWrapper<?> packet) {
        ViewerState state = viewerStates.get(viewerId);
        if (state == null) {
            return;
        }
        state.lock.lock();
        try {
            VirtualViewer viewer = state.viewer;
            if (viewer == null) {
                return;
            }
            viewer.send(packet);
        } finally {
            state.lock.unlock();
        }
    }

    private static final class ViewerState {
        private final ReentrantLock lock = new ReentrantLock();
        private VirtualViewer viewer;
        private int memberships;
        private long generation;
    }

    private static final class Holder<T> {
        private T value;
    }

    Operation operation() {
        Lock lock = coordinationLock.readLock();
        lock.lock();
        return new Operation(lock);
    }

    private Operation exclusiveOperation() {
        if (coordinationLock.getReadHoldCount() > 0 && !coordinationLock.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException(
                    "Exclusive manager operations cannot start from an active entity transport callback"
            );
        }
        Lock lock = coordinationLock.writeLock();
        lock.lock();
        return new Operation(lock);
    }

    static final class Operation implements AutoCloseable {
        private final Lock lock;

        private Operation(Lock lock) {
            this.lock = lock;
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("VirtualEntityManager is closed");
        }
    }

    private static boolean finite(com.github.retrooper.packetevents.util.Vector3d vector) {
        return Double.isFinite(vector.getX())
                && Double.isFinite(vector.getY())
                && Double.isFinite(vector.getZ());
    }
}
