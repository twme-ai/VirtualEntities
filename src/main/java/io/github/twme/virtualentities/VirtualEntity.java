package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.attribute.Attribute;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import io.github.twme.virtualentities.metadata.EntityMetadataSchema;
import io.github.twme.virtualentities.metadata.VirtualMetadata;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
    private final Map<Attribute, WrapperPlayServerUpdateAttributes.Property> attributes = new LinkedHashMap<>();
    private final Set<VirtualEntity> passengers = new LinkedHashSet<>();
    private VirtualEntity vehicle;
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
        if (!builder.metadataEnabled) {
            this.metadata = null;
        } else {
            String requestedVersion = builder.metadataVersion != null
                    ? builder.metadataVersion
                    : PacketEvents.getAPI().getServerManager().getVersion().getReleaseName();
            EntityMetadataSchema schema = builder.entityDataName != null
                    ? manager.metadataRegistry().schema(
                            manager.metadataRegistry().resolveVersion(requestedVersion),
                            builder.entityDataName
                    )
                    : manager.metadataRegistry().schema(requestedVersion, type);
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

    /** Returns the current equipment snapshot. */
    public synchronized Map<EquipmentSlot, ItemStack> equipment() {
        return Collections.unmodifiableMap(new EnumMap<>(equipment));
    }

    /** Sets one equipment slot and immediately updates current viewers when spawned. */
    public synchronized VirtualEntity setEquipment(EquipmentSlot slot, ItemStack item) {
        ensureActive();
        equipment.put(Objects.requireNonNull(slot, "slot"), Objects.requireNonNull(item, "item"));
        if (spawned) {
            broadcast(equipmentPacket(slot, item));
        }
        return this;
    }

    /** Clears one equipment slot and immediately updates current viewers when spawned. */
    public synchronized VirtualEntity clearEquipment(EquipmentSlot slot) {
        ensureActive();
        Objects.requireNonNull(slot, "slot");
        equipment.remove(slot);
        if (spawned) {
            broadcast(equipmentPacket(slot, ItemStack.EMPTY));
        }
        return this;
    }

    /** Returns the current attribute properties keyed by PacketEvents attribute. */
    public synchronized Map<Attribute, WrapperPlayServerUpdateAttributes.Property> attributes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
    }

    /** Sets an attribute without modifiers. */
    public VirtualEntity setAttribute(Attribute attribute, double value) {
        return setAttribute(attribute, value, List.of());
    }

    /** Sets an attribute and its complete modifier list. */
    public synchronized VirtualEntity setAttribute(
            Attribute attribute,
            double value,
            List<WrapperPlayServerUpdateAttributes.PropertyModifier> modifiers
    ) {
        ensureActive();
        WrapperPlayServerUpdateAttributes.Property property = new WrapperPlayServerUpdateAttributes.Property(
                Objects.requireNonNull(attribute, "attribute"),
                value,
                List.copyOf(Objects.requireNonNull(modifiers, "modifiers"))
        );
        attributes.put(attribute, property);
        if (spawned) {
            broadcast(new WrapperPlayServerUpdateAttributes(entityId, List.of(property)));
        }
        return this;
    }

    /** Restores an attribute to its PacketEvents default value. */
    public synchronized VirtualEntity resetAttribute(Attribute attribute) {
        ensureActive();
        Objects.requireNonNull(attribute, "attribute");
        attributes.remove(attribute);
        if (spawned) {
            WrapperPlayServerUpdateAttributes.Property reset = new WrapperPlayServerUpdateAttributes.Property(
                    attribute,
                    attribute.getDefaultValue(),
                    List.of()
            );
            broadcast(new WrapperPlayServerUpdateAttributes(entityId, List.of(reset)));
        }
        return this;
    }

    /** Returns this entity's current passengers in protocol order. */
    public synchronized List<VirtualEntity> passengers() {
        return List.copyOf(passengers);
    }

    /** Returns the vehicle this entity is riding, if any. */
    public synchronized Optional<VirtualEntity> vehicle() {
        return Optional.ofNullable(vehicle);
    }

    /** Adds a managed virtual entity as a passenger. */
    public synchronized VirtualEntity addPassenger(VirtualEntity passenger) {
        ensureActive();
        Objects.requireNonNull(passenger, "passenger").ensureActive();
        if (passenger == this) {
            throw new IllegalArgumentException("An entity cannot ride itself");
        }
        if (passenger.manager != manager) {
            throw new IllegalArgumentException("Passenger must belong to the same VirtualEntityManager");
        }
        for (VirtualEntity ancestor = this; ancestor != null; ancestor = ancestor.vehicle) {
            if (ancestor == passenger) {
                throw new IllegalArgumentException("Passenger relationship would create a cycle");
            }
        }
        if (passenger.vehicle == this) {
            return this;
        }
        if (passenger.vehicle != null) {
            passenger.vehicle.removePassenger(passenger);
        }
        passengers.add(passenger);
        passenger.vehicle = this;
        syncPassengersIfSpawned();
        return this;
    }

    /** Removes a passenger while preserving all other passengers. */
    public synchronized VirtualEntity removePassenger(VirtualEntity passenger) {
        ensureActive();
        Objects.requireNonNull(passenger, "passenger");
        if (passengers.remove(passenger)) {
            passenger.vehicle = null;
            syncPassengersIfSpawned();
        }
        return this;
    }

    /** Removes every passenger from this entity. */
    public synchronized VirtualEntity clearPassengers() {
        ensureActive();
        if (!passengers.isEmpty()) {
            for (VirtualEntity passenger : passengers) {
                passenger.vehicle = null;
            }
            passengers.clear();
            syncPassengersIfSpawned();
        }
        return this;
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
        detachPassengerRelationships();
        viewers.clear();
        removed = true;
        manager.unregister(this);
    }

    private void sendSpawn(VirtualViewer viewer) {
        viewer.send(new WrapperPlayServerSpawnEntity(entityId, uuid, type, location, headYaw, objectData, velocity));
        if (metadata != null && !metadata.entityData().isEmpty()) {
            viewer.send(new WrapperPlayServerEntityMetadata(entityId, metadata.entityData()));
        }
        for (Map.Entry<EquipmentSlot, ItemStack> entry : equipment.entrySet()) {
            viewer.send(equipmentPacket(entry.getKey(), entry.getValue()));
        }
        if (!attributes.isEmpty()) {
            viewer.send(new WrapperPlayServerUpdateAttributes(entityId, List.copyOf(attributes.values())));
        }
        if (!passengers.isEmpty()) {
            viewer.send(passengersPacket());
        }
    }

    private WrapperPlayServerEntityEquipment equipmentPacket(EquipmentSlot slot, ItemStack item) {
        return new WrapperPlayServerEntityEquipment(entityId, List.of(new Equipment(slot, item)));
    }

    private WrapperPlayServerSetPassengers passengersPacket() {
        return new WrapperPlayServerSetPassengers(
                entityId,
                passengers.stream().mapToInt(VirtualEntity::entityId).toArray()
        );
    }

    private void syncPassengersIfSpawned() {
        if (spawned) {
            broadcast(passengersPacket());
        }
    }

    private void detachPassengerRelationships() {
        if (vehicle != null) {
            VirtualEntity oldVehicle = vehicle;
            vehicle = null;
            oldVehicle.passengers.remove(this);
            oldVehicle.syncPassengersIfSpawned();
        }
        if (!passengers.isEmpty()) {
            for (VirtualEntity passenger : passengers) {
                passenger.vehicle = null;
            }
            passengers.clear();
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
        private boolean metadataEnabled;
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

        /** Enables metadata using the current PacketEvents server version and entity type. */
        public Builder metadata() {
            this.metadataEnabled = true;
            this.metadataVersion = null;
            this.entityDataName = null;
            return this;
        }

        /** Enables metadata for a specific server version and resolves the entity type automatically. */
        public Builder metadata(ServerVersion version) {
            this.metadataEnabled = true;
            this.metadataVersion = Objects.requireNonNull(version, "version").getReleaseName();
            this.entityDataName = null;
            return this;
        }

        public Builder metadata(String version, String entityDataName) {
            this.metadataEnabled = true;
            this.metadataVersion = Objects.requireNonNull(version, "version");
            this.entityDataName = Objects.requireNonNull(entityDataName, "entityDataName");
            return this;
        }

        public VirtualEntity build() {
            return new VirtualEntity(this);
        }
    }
}
