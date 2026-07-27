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
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns virtual entity identity, canonical viewer transports, outbound ordering, and lookup for one library instance.
 * Closing a manager is terminal. Transport callbacks may re-enter library APIs and are never invoked while an entity
 * state monitor or the manager lifecycle lock is held.
 */
public final class VirtualEntityManager implements AutoCloseable {
    private final EntityIdProvider entityIdProvider;
    private final EntityMetadataRegistry metadataRegistry;
    private final Object relationshipLock = new Object();
    private final Object lifecycleLock = new Object();
    private final Map<Integer, VirtualEntity> byId = new ConcurrentHashMap<>();
    private final Map<UUID, VirtualEntity> byUuid = new ConcurrentHashMap<>();
    private final Map<UUID, VirtualViewer> viewerTransports = new ConcurrentHashMap<>();
    private final Map<UUID, ReentrantLock> packetSendLocks = new ConcurrentHashMap<>();
    private final ThreadLocal<BundleContext> activeBundle = new ThreadLocal<>();
    private volatile VirtualInteractionValidator interactionValidator = interaction -> true;
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
        ensureOpen();
        if (activeBundle.get() != null) {
            throw new IllegalStateException("Viewer transports cannot be replaced inside a bundle scope");
        }
        VirtualViewer replacement = Objects.requireNonNull(viewer, "viewer");
        ReentrantLock lock = packetSendLocks.computeIfAbsent(replacement.id(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            viewerTransports.put(replacement.id(), replacement);
            replayViewerReplacement(replacement, null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Installs additional platform authorization for inbound interactions.
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

    void registerViewerTransport(VirtualEntity source, VirtualViewer viewer) {
        ensureOpen();
        ReentrantLock lock = packetSendLocks.computeIfAbsent(viewer.id(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            VirtualViewer previous = viewerTransports.get(viewer.id());
            if (activeBundle.get() != null && previous != null && previous != viewer) {
                throw new IllegalStateException("Viewer transports cannot be replaced inside a bundle scope");
            }
            viewerTransports.put(viewer.id(), viewer);
            if (previous != null && previous != viewer) {
                replayViewerReplacement(viewer, source);
            }
        } finally {
            lock.unlock();
        }
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
        viewerTransports.putIfAbsent(viewer.id(), viewer);
        BundleContext context = activeBundle.get();
        if (context != null) {
            context.enqueue(viewer.id(), packet);
            return;
        }
        sendNow(viewer.id(), packet);
    }

    void sendDirect(VirtualViewer viewer, PacketWrapper<?> packet) {
        ReentrantLock lock = packetSendLocks.computeIfAbsent(viewer.id(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            viewer.send(packet);
        } finally {
            lock.unlock();
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
                if (failure == null) {
                    failure = entityFailure;
                } else {
                    failure.addSuppressed(entityFailure);
                }
            }
        }
        viewerTransports.clear();
        packetSendLocks.clear();
        rethrow(failure);
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
        ReentrantLock lock = packetSendLocks.computeIfAbsent(pending.viewerId(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            VirtualViewer viewer = viewerTransports.get(pending.viewerId());
            if (viewer == null) {
                return;
            }
            flushLocked(viewer, pending.packets());
        } finally {
            lock.unlock();
        }
    }

    private static void flushLocked(VirtualViewer viewer, List<PacketWrapper<?>> packets) {
        boolean bundled = viewer.clientVersion().isNewerThanOrEquals(ClientVersion.V_1_19_4);
        boolean opened = false;
        Throwable failure = null;
        try {
            if (bundled) {
                viewer.send(new WrapperPlayServerBundle());
                opened = true;
            }
            for (PacketWrapper<?> packet : packets) {
                try {
                    viewer.send(packet);
                } catch (RuntimeException | Error packetFailure) {
                    if (failure == null) {
                        failure = packetFailure;
                    } else {
                        failure.addSuppressed(packetFailure);
                    }
                }
            }
        } finally {
            if (opened) {
                try {
                    viewer.send(new WrapperPlayServerBundle());
                } catch (RuntimeException | Error closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
            }
        }
        rethrow(failure);
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

        private void enqueue(UUID viewerId, PacketWrapper<?> packet) {
            viewers.computeIfAbsent(viewerId, PendingViewer::new).packets().add(packet);
        }

        private Collection<PendingViewer> pendingViewers() {
            return viewers.values();
        }
    }

    private record PendingViewer(UUID viewerId, List<PacketWrapper<?>> packets) {
        private PendingViewer(UUID viewerId) {
            this(viewerId, new ArrayList<>());
        }
    }

    private void sendNow(UUID viewerId, PacketWrapper<?> packet) {
        ReentrantLock lock = packetSendLocks.computeIfAbsent(viewerId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            VirtualViewer viewer = viewerTransports.get(viewerId);
            if (viewer == null) {
                return;
            }
            viewer.send(packet);
        } finally {
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
