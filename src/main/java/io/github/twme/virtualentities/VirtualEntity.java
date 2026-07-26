package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.twme.virtualentities.metadata.EntityMetadataSchema;
import io.github.twme.virtualentities.metadata.VirtualMetadata;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** A client-side entity whose lifecycle is represented entirely by PacketEvents packets. */
public final class VirtualEntity {
    private final VirtualEntityManager manager;
    private final int entityId;
    private final UUID uuid;
    private final EntityType type;
    private final int objectData;
    private final VirtualMetadata metadata;
    private final Map<UUID, VirtualViewer> viewers = new LinkedHashMap<>();
    private Location location;
    private Vector3d velocity;
    private float headYaw;
    private boolean onGround;
    private boolean spawned;
    private boolean removed;

    private VirtualEntity(Builder builder) {
        this.manager = builder.manager;
        this.entityId = builder.entityId != null ? builder.entityId : manager.nextEntityId();
        this.uuid = builder.uuid != null ? builder.uuid : UUID.randomUUID();
        this.type = builder.type;
        this.objectData = builder.objectData;
        this.velocity = builder.velocity;
        this.headYaw = builder.headYaw;
        if ((builder.metadataVersion == null) != (builder.entityDataName == null)) {
            throw new IllegalStateException("metadataVersion and entityDataName must be configured together");
        }
        if (builder.metadataVersion == null) {
            this.metadata = null;
        } else {
            EntityMetadataSchema schema = manager.metadataRegistry().schema(builder.metadataVersion, builder.entityDataName);
            this.metadata = new VirtualMetadata(schema);
        }
        manager.register(this);
    }

    static Builder builder(VirtualEntityManager manager, EntityType type) {
        return new Builder(manager, type);
    }

    public int entityId() {
        return entityId;
    }

    public UUID uuid() {
        return uuid;
    }

    public EntityType type() {
        return type;
    }

    public synchronized Location location() {
        return location == null ? null : copy(location);
    }

    public synchronized Vector3d velocity() {
        return velocity;
    }

    public synchronized boolean isSpawned() {
        return spawned;
    }

    public synchronized boolean isRemoved() {
        return removed;
    }

    public VirtualMetadata metadata() {
        if (metadata == null) {
            throw new IllegalStateException("No metadata schema configured for this entity");
        }
        return metadata;
    }

    public synchronized Collection<VirtualViewer> viewers() {
        return Collections.unmodifiableList(java.util.List.copyOf(viewers.values()));
    }

    public VirtualEntity addViewer(User user) {
        return addViewer(VirtualViewer.of(user));
    }

    public synchronized VirtualEntity addViewer(VirtualViewer viewer) {
        ensureActive();
        Objects.requireNonNull(viewer, "viewer");
        if (viewers.putIfAbsent(viewer.id(), viewer) == null && spawned) {
            sendSpawn(viewer);
        }
        return this;
    }

    public VirtualEntity removeViewer(User user) {
        Objects.requireNonNull(user, "user");
        return removeViewer(user.getUUID());
    }

    public synchronized VirtualEntity removeViewer(UUID viewerId) {
        VirtualViewer viewer = viewers.remove(Objects.requireNonNull(viewerId, "viewerId"));
        if (viewer != null && spawned) {
            viewer.send(new WrapperPlayServerDestroyEntities(entityId));
        }
        return this;
    }

    public synchronized VirtualEntity spawn(Location location) {
        ensureActive();
        if (spawned) {
            throw new IllegalStateException("Entity is already spawned");
        }
        this.location = copy(Objects.requireNonNull(location, "location"));
        this.spawned = true;
        viewers.values().forEach(this::sendSpawn);
        return this;
    }

    public synchronized VirtualEntity despawn() {
        if (spawned) {
            broadcast(new WrapperPlayServerDestroyEntities(entityId));
            spawned = false;
        }
        return this;
    }

    public synchronized VirtualEntity teleport(Location location) {
        ensureSpawned();
        this.location = copy(Objects.requireNonNull(location, "location"));
        broadcast(new WrapperPlayServerEntityTeleport(entityId, this.location, onGround));
        return this;
    }

    public synchronized VirtualEntity rotate(float yaw, float pitch, boolean onGround) {
        ensureSpawned();
        this.location.setYaw(yaw);
        this.location.setPitch(pitch);
        this.onGround = onGround;
        broadcast(new WrapperPlayServerEntityRotation(entityId, yaw, pitch, onGround));
        return this;
    }

    public synchronized VirtualEntity rotateHead(float headYaw) {
        ensureSpawned();
        this.headYaw = headYaw;
        broadcast(new WrapperPlayServerEntityHeadLook(entityId, headYaw));
        return this;
    }

    public synchronized VirtualEntity velocity(Vector3d velocity) {
        ensureSpawned();
        this.velocity = Objects.requireNonNull(velocity, "velocity");
        broadcast(new WrapperPlayServerEntityVelocity(entityId, velocity));
        return this;
    }

    public synchronized VirtualEntity syncMetadata() {
        ensureSpawned();
        if (metadata != null && !metadata.entityData().isEmpty()) {
            broadcast(new WrapperPlayServerEntityMetadata(entityId, metadata.entityData()));
        }
        return this;
    }

    public synchronized void remove() {
        if (removed) {
            return;
        }
        despawn();
        viewers.clear();
        removed = true;
        manager.unregister(this);
    }

    private void sendSpawn(VirtualViewer viewer) {
        viewer.send(new WrapperPlayServerSpawnEntity(entityId, uuid, type, location, headYaw, objectData, velocity));
        if (metadata != null && !metadata.entityData().isEmpty()) {
            viewer.send(new WrapperPlayServerEntityMetadata(entityId, metadata.entityData()));
        }
    }

    private void broadcast(PacketWrapper<?> packet) {
        viewers.values().forEach(viewer -> viewer.send(packet));
    }

    private void ensureActive() {
        if (removed) {
            throw new IllegalStateException("Entity has been removed");
        }
    }

    private void ensureSpawned() {
        ensureActive();
        if (!spawned) {
            throw new IllegalStateException("Entity is not spawned");
        }
    }

    private static Location copy(Location location) {
        return new Location(location.getPosition(), location.getYaw(), location.getPitch());
    }

    /** Builds a virtual entity and registers it with its manager. */
    public static final class Builder {
        private final VirtualEntityManager manager;
        private final EntityType type;
        private Integer entityId;
        private UUID uuid;
        private int objectData;
        private Vector3d velocity = Vector3d.zero();
        private float headYaw;
        private String metadataVersion;
        private String entityDataName;

        private Builder(VirtualEntityManager manager, EntityType type) {
            this.manager = manager;
            this.type = type;
        }

        public Builder entityId(int entityId) {
            this.entityId = entityId;
            return this;
        }

        public Builder uuid(UUID uuid) {
            this.uuid = Objects.requireNonNull(uuid, "uuid");
            return this;
        }

        public Builder objectData(int objectData) {
            this.objectData = objectData;
            return this;
        }

        public Builder velocity(Vector3d velocity) {
            this.velocity = Objects.requireNonNull(velocity, "velocity");
            return this;
        }

        public Builder headYaw(float headYaw) {
            this.headYaw = headYaw;
            return this;
        }

        public Builder metadata(String version, String entityDataName) {
            this.metadataVersion = Objects.requireNonNull(version, "version");
            this.entityDataName = Objects.requireNonNull(entityDataName, "entityDataName");
            return this;
        }

        public VirtualEntity build() {
            return new VirtualEntity(this);
        }
    }
}
