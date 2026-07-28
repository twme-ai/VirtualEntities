package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.attribute.Attribute;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.Direction;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.protocol.world.PaintingType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnExperienceOrb;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPainting;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnWeatherEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import io.github.twme.virtualentities.metadata.EntityMetadataSchema;
import io.github.twme.virtualentities.metadata.VirtualMetadata;
import net.kyori.adventure.text.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A client-side entity whose lifecycle is represented entirely by PacketEvents packets.
 * State and recipient snapshots are taken under internal locks; custom transports are invoked after those locks
 * are released and are serialized per viewer UUID by the owning manager.
 */
public final class VirtualEntity {
    private final VirtualEntityManager manager;
    private final int entityId;
    private final UUID uuid;
    private final EntityType type;
    private final int objectData;
    private final short experience;
    private final PaintingType paintingType;
    private final Direction paintingDirection;
    private final VirtualMetadata metadata;
    private final Map<UUID, VirtualViewer> viewers = new LinkedHashMap<>();
    private final Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
    private final Map<Attribute, WrapperPlayServerUpdateAttributes.Property> attributes = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<VirtualEntity> passengers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<VirtualEntityInteraction>> interactionListeners = new CopyOnWriteArrayList<>();
    private final ReentrantLock operationLock = new ReentrantLock(true);
    private UserProfile playerProfile;
    private GameMode gameMode = GameMode.SURVIVAL;
    private Component tabListName;
    private boolean listed = true;
    private int latency;
    private volatile VirtualEntity vehicle;
    private Location location;
    private Vector3d velocity;
    private float headYaw;
    private boolean onGround;
    private boolean spawned;
    private volatile boolean removed;

    private VirtualEntity(Builder builder) {
        this.manager = builder.manager;
        this.entityId = builder.entityId != null ? builder.entityId : manager.nextEntityId();
        this.uuid = builder.uuid != null ? builder.uuid : UUID.randomUUID();
        this.type = builder.type;
        this.objectData = builder.objectData;
        this.experience = builder.experience;
        this.paintingType = builder.paintingType;
        this.paintingDirection = builder.paintingDirection;
        this.velocity = builder.velocity;
        this.headYaw = builder.headYaw;
        if (builder.playerProfile != null) {
            this.playerProfile = copyProfile(builder.playerProfile);
            if (!uuid.equals(playerProfile.getUUID())) {
                throw new IllegalArgumentException("Player profile UUID must match the entity UUID");
            }
        }
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

    /**
     * Returns whether this entity type can be represented by the supplied client protocol.
     *
     * @param version the viewer's client protocol version
     * @return whether PacketEvents maps this entity type for the version
     */
    public boolean supports(ClientVersion version) {
        Objects.requireNonNull(version, "version");
        return version.isNewerThanOrEquals(ClientVersion.V_1_9_3)
                && type.isRegistered()
                && type.getId(version) >= 0;
    }

    /**
     * Returns whether this entity type can be represented by the viewer's client protocol.
     *
     * @param viewer the prospective viewer
     * @return whether PacketEvents maps this entity type for the viewer
     */
    public boolean supports(VirtualViewer viewer) {
        return supports(Objects.requireNonNull(viewer, "viewer").clientVersion());
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

    public synchronized boolean hasViewer(UUID viewerId) {
        return viewers.containsKey(Objects.requireNonNull(viewerId, "viewerId"));
    }

    /** Registers an inbound interaction listener for this entity. */
    public VirtualEntityInteraction.Subscription onInteraction(Consumer<VirtualEntityInteraction> listener) {
        ensureActive();
        Consumer<VirtualEntityInteraction> checked = Objects.requireNonNull(listener, "listener");
        interactionListeners.add(checked);
        return () -> interactionListeners.remove(checked);
    }

    /** Returns a defensive copy of this entity's player profile, when configured as a player. */
    public synchronized Optional<UserProfile> playerProfile() {
        return playerProfile == null ? Optional.empty() : Optional.of(copyProfile(playerProfile));
    }

    /** Replaces the player name and textures, re-spawning the entity for current viewers when needed. */
    public VirtualEntity setPlayerProfile(UserProfile profile) {
        try (EntityOperation ignored = operation()) {
            List<VirtualViewer> currentViewers;
            synchronized (this) {
                ensureActive();
                ensurePlayer();
                UserProfile replacement = validatedProfile(profile);
                if (!uuid.equals(replacement.getUUID())) {
                    throw new IllegalArgumentException("Player profile UUID cannot change after entity creation");
                }
                playerProfile = copyProfile(replacement);
                currentViewers = spawned ? List.copyOf(viewers.values()) : List.of();
            }
            Throwable failure = null;
            for (VirtualViewer viewer : currentViewers) {
                try {
                    manager.send(viewer, new WrapperPlayServerDestroyEntities(entityId));
                    manager.send(viewer, playerInfoRemovePacket());
                    sendSpawn(viewer);
                } catch (RuntimeException | Error viewerFailure) {
                    removeViewerAfterFailure(viewer, viewerFailure);
                    failure = appendFailure(failure, viewerFailure);
                }
            }
            rethrow(failure);
            return this;
        }
    }

    public synchronized GameMode gameMode() {
        ensurePlayer();
        return gameMode;
    }

    public VirtualEntity setGameMode(GameMode gameMode) {
        try (EntityOperation ignored = operation()) {
            synchronized (this) {
                ensureActive();
                ensurePlayer();
                this.gameMode = Objects.requireNonNull(gameMode, "gameMode");
            }
            syncPlayerInfo(
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                    WrapperPlayServerPlayerInfo.Action.UPDATE_GAME_MODE
            );
            return this;
        }
    }

    public synchronized Optional<Component> tabListName() {
        ensurePlayer();
        return Optional.ofNullable(tabListName);
    }

    public VirtualEntity setTabListName(Component tabListName) {
        try (EntityOperation ignored = operation()) {
            synchronized (this) {
                ensureActive();
                ensurePlayer();
                this.tabListName = tabListName;
            }
            syncPlayerInfo(
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
                    WrapperPlayServerPlayerInfo.Action.UPDATE_DISPLAY_NAME
            );
            return this;
        }
    }

    public synchronized boolean isListed() {
        ensurePlayer();
        return listed;
    }

    public VirtualEntity setListed(boolean listed) {
        try (EntityOperation ignored = operation()) {
            PacketWrapper<?> packet;
            List<VirtualViewer> currentViewers;
            synchronized (this) {
                ensureActive();
                ensurePlayer();
                this.listed = listed;
                packet = spawned ? modernPlayerInfo()
                        ? playerInfoUpdatePacket(EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED))
                        : listed
                                ? legacyPlayerInfoPacket(WrapperPlayServerPlayerInfo.Action.ADD_PLAYER)
                                : playerInfoRemovePacket() : null;
                currentViewers = packet == null ? List.of() : List.copyOf(viewers.values());
            }
            broadcast(currentViewers, packet);
            return this;
        }
    }

    public synchronized int latency() {
        ensurePlayer();
        return latency;
    }

    public VirtualEntity setLatency(int latency) {
        try (EntityOperation ignored = operation()) {
            synchronized (this) {
                ensureActive();
                ensurePlayer();
                this.latency = latency;
            }
            syncPlayerInfo(
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                    WrapperPlayServerPlayerInfo.Action.UPDATE_LATENCY
            );
            return this;
        }
    }

    /** Returns the current equipment snapshot. */
    public synchronized Map<EquipmentSlot, ItemStack> equipment() {
        Map<EquipmentSlot, ItemStack> snapshot = new EnumMap<>(EquipmentSlot.class);
        equipment.forEach((slot, item) -> snapshot.put(slot, item.copy()));
        return Collections.unmodifiableMap(snapshot);
    }

    /** Sets one equipment slot and immediately updates current viewers when spawned. */
    public VirtualEntity setEquipment(EquipmentSlot slot, ItemStack item) {
        try (EntityOperation ignored = operation()) {
            List<VirtualViewer> currentViewers;
            PacketWrapper<?> packet;
            synchronized (this) {
                ensureActive();
                ItemStack retained = Objects.requireNonNull(item, "item").copy();
                equipment.put(Objects.requireNonNull(slot, "slot"), retained);
                packet = equipmentPacket(slot, retained);
                currentViewers = spawned ? List.copyOf(viewers.values()) : List.of();
            }
            broadcast(currentViewers, packet);
            return this;
        }
    }

    /** Clears one equipment slot and immediately updates current viewers when spawned. */
    public VirtualEntity clearEquipment(EquipmentSlot slot) {
        try (EntityOperation ignored = operation()) {
            List<VirtualViewer> currentViewers;
            PacketWrapper<?> packet;
            synchronized (this) {
                ensureActive();
                Objects.requireNonNull(slot, "slot");
                equipment.remove(slot);
                packet = equipmentPacket(slot, ItemStack.EMPTY);
                currentViewers = spawned ? List.copyOf(viewers.values()) : List.of();
            }
            broadcast(currentViewers, packet);
            return this;
        }
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
    public VirtualEntity setAttribute(
            Attribute attribute,
            double value,
            List<WrapperPlayServerUpdateAttributes.PropertyModifier> modifiers
    ) {
        try (EntityOperation ignored = operation()) {
            validateFinite(value, "attribute value");
            List<WrapperPlayServerUpdateAttributes.PropertyModifier> checkedModifiers =
                    List.copyOf(Objects.requireNonNull(modifiers, "modifiers"));
            for (WrapperPlayServerUpdateAttributes.PropertyModifier modifier : checkedModifiers) {
                validateFinite(Objects.requireNonNull(modifier, "modifier").getAmount(), "attribute modifier amount");
            }
            WrapperPlayServerUpdateAttributes.Property property;
            List<VirtualViewer> currentViewers;
            synchronized (this) {
                ensureActive();
                property = new WrapperPlayServerUpdateAttributes.Property(
                        Objects.requireNonNull(attribute, "attribute"), value, checkedModifiers);
                attributes.put(attribute, property);
                currentViewers = spawned ? List.copyOf(viewers.values()) : List.of();
            }
            broadcast(currentViewers, new WrapperPlayServerUpdateAttributes(entityId, List.of(property)));
            return this;
        }
    }

    /** Restores an attribute to its PacketEvents default value. */
    public VirtualEntity resetAttribute(Attribute attribute) {
        try (EntityOperation ignored = operation()) {
            WrapperPlayServerUpdateAttributes.Property reset;
            List<VirtualViewer> currentViewers;
            synchronized (this) {
                ensureActive();
                Objects.requireNonNull(attribute, "attribute");
                attributes.remove(attribute);
                validateFinite(attribute.getDefaultValue(), "attribute default value");
                reset = new WrapperPlayServerUpdateAttributes.Property(attribute, attribute.getDefaultValue(), List.of());
                currentViewers = spawned ? List.copyOf(viewers.values()) : List.of();
            }
            broadcast(currentViewers, new WrapperPlayServerUpdateAttributes(entityId, List.of(reset)));
            return this;
        }
    }

    /** Returns this entity's current passengers in protocol order. */
    public List<VirtualEntity> passengers() {
        return List.copyOf(passengers);
    }

    /** Returns the vehicle this entity is riding, if any. */
    public Optional<VirtualEntity> vehicle() {
        return Optional.ofNullable(vehicle);
    }

    /** Adds a managed virtual entity as a passenger. */
    public VirtualEntity addPassenger(VirtualEntity passenger) {
        try (EntityOperation ignored = operation()) {
            ensureActive();
            Objects.requireNonNull(passenger, "passenger").ensureActive();
            if (passenger == this) {
                throw new IllegalArgumentException("An entity cannot ride itself");
            }
            if (passenger.manager != manager) {
                throw new IllegalArgumentException("Passenger must belong to the same VirtualEntityManager");
            }
            VirtualEntity oldVehicle;
            synchronized (manager.relationshipLock()) {
                ensureActive();
                passenger.ensureActive();
                for (VirtualEntity ancestor = this; ancestor != null; ancestor = ancestor.vehicle) {
                    if (ancestor == passenger) {
                        throw new IllegalArgumentException("Passenger relationship would create a cycle");
                    }
                }
                if (passenger.vehicle == this) {
                    return this;
                }
                oldVehicle = passenger.vehicle;
                if (oldVehicle != null) {
                    oldVehicle.passengers.remove(passenger);
                }
                passengers.addIfAbsent(passenger);
                passenger.vehicle = this;
            }
            if (oldVehicle != null) {
                oldVehicle.syncPassengersIfSpawned();
            }
            syncPassengersIfSpawned();
            return this;
        }
    }

    /** Removes a passenger while preserving all other passengers. */
    public VirtualEntity removePassenger(VirtualEntity passenger) {
        try (EntityOperation ignored = operation()) {
            ensureActive();
            Objects.requireNonNull(passenger, "passenger");
            boolean changed;
            synchronized (manager.relationshipLock()) {
                changed = passengers.remove(passenger);
                if (changed && passenger.vehicle == this) {
                    passenger.vehicle = null;
                }
            }
            if (changed) {
                syncPassengersIfSpawned();
            }
            return this;
        }
    }

    /** Removes every passenger from this entity. */
    public VirtualEntity clearPassengers() {
        try (EntityOperation ignored = operation()) {
            ensureActive();
            boolean changed;
            synchronized (manager.relationshipLock()) {
                changed = !passengers.isEmpty();
                for (VirtualEntity passenger : passengers) {
                    if (passenger.vehicle == this) {
                        passenger.vehicle = null;
                    }
                }
                passengers.clear();
            }
            if (changed) {
                syncPassengersIfSpawned();
            }
            return this;
        }
    }

    public VirtualEntity addViewer(User user) {
        return addViewer(VirtualViewer.of(user));
    }

    /**
     * Adds a viewer when its client protocol supports this entity type.
     * Unsupported viewers are left untracked and receive no packets; use {@link #supports(VirtualViewer)}
     * when the caller needs to select a fallback representation.
     *
     * @param viewer the prospective viewer
     * @return this entity
     */
    public VirtualEntity addViewer(VirtualViewer viewer) {
        try (EntityOperation ignored = operation()) {
            Objects.requireNonNull(viewer, "viewer");
            VirtualViewer previous;
            boolean sendSpawn;
            synchronized (this) {
                ensureActive();
                if (!supports(viewer)) {
                    return this;
                }
                previous = viewers.put(viewer.id(), viewer);
                sendSpawn = previous != viewer && spawned;
            }
            boolean transportRegistered = false;
            try {
                manager.registerViewerTransport(this, viewer, previous == null);
                transportRegistered = true;
                if (sendSpawn) {
                    if (previous != null) {
                        destroyOn(previous);
                    }
                    sendSpawn(viewer);
                }
            } catch (RuntimeException | Error failure) {
                if (sendSpawn) {
                    try {
                        destroyOn(viewer);
                    } catch (RuntimeException | Error cleanupFailure) {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
                removeViewerAfterFailure(viewer, failure, transportRegistered || previous != null);
                throw failure;
            }
            return this;
        }
    }

    /**
     * Replays this entity's complete retained state to one registered viewer.
     * A failed replay removes the viewer membership so a later {@link #addViewer(VirtualViewer)} can retry naturally.
     */
    public VirtualEntity resyncViewer(UUID viewerId) {
        try (EntityOperation ignored = operation()) {
            VirtualViewer viewer;
            synchronized (this) {
                ensureSpawned();
                viewer = viewers.get(Objects.requireNonNull(viewerId, "viewerId"));
                if (viewer == null) {
                    throw new IllegalArgumentException("Viewer is not registered: " + viewerId);
                }
            }
            try {
                destroyOn(viewer);
                sendSpawn(viewer);
            } catch (RuntimeException | Error failure) {
                removeViewerAfterFailure(viewer, failure);
                throw failure;
            }
            return this;
        }
    }

    void replaceViewerTransport(VirtualViewer viewer) {
        VirtualViewer previous;
        boolean replay;
        synchronized (this) {
            previous = viewers.get(viewer.id());
            if (previous == null || previous == viewer) {
                return;
            }
            viewers.put(viewer.id(), viewer);
            replay = spawned;
        }
        if (!replay) {
            return;
        }
        try {
            destroyOn(previous);
            sendSpawn(viewer);
        } catch (RuntimeException | Error failure) {
            removeViewerAfterFailure(viewer, failure);
            throw failure;
        }
    }

    public VirtualEntity removeViewer(User user) {
        Objects.requireNonNull(user, "user");
        return removeViewer(user.getUUID());
    }

    public VirtualEntity removeViewer(UUID viewerId) {
        try (EntityOperation ignored = operation()) {
            VirtualViewer viewer;
            boolean destroy;
            VirtualEntity currentVehicle;
            synchronized (this) {
                viewer = viewers.remove(Objects.requireNonNull(viewerId, "viewerId"));
                destroy = viewer != null && spawned;
                currentVehicle = vehicle;
            }
            if (viewer != null && currentVehicle != null) {
                currentVehicle.syncPassengersForViewer(viewer);
            }
            if (destroy) {
                manager.send(viewer, new WrapperPlayServerDestroyEntities(entityId));
                if (playerProfile != null) {
                    manager.send(viewer, playerInfoRemovePacket());
                }
            }
            if (viewer != null) {
                manager.unregisterViewerTransport(viewerId);
            }
            return this;
        }
    }

    public VirtualEntity spawn(Location location) {
        try (EntityOperation ignored = operation()) {
            Location checked = validatedLocation(location);
            List<VirtualViewer> currentViewers;
            synchronized (this) {
                ensureActive();
                if (spawned) {
                    throw new IllegalStateException("Entity is already spawned");
                }
                this.location = checked;
                this.spawned = true;
                currentViewers = List.copyOf(viewers.values());
            }
            Throwable failure = null;
            for (VirtualViewer viewer : currentViewers) {
                try {
                    sendSpawn(viewer);
                } catch (RuntimeException | Error viewerFailure) {
                    removeViewerAfterFailure(viewer, viewerFailure);
                    failure = appendFailure(failure, viewerFailure);
                }
            }
            rethrow(failure);
            return this;
        }
    }

    public VirtualEntity despawn() {
        try (EntityOperation ignored = operation()) {
            List<VirtualViewer> currentViewers;
            boolean player;
            VirtualEntity currentVehicle;
            synchronized (this) {
                if (!spawned) {
                    return this;
                }
                spawned = false;
                currentViewers = List.copyOf(viewers.values());
                player = playerProfile != null;
                currentVehicle = vehicle;
            }
            Throwable failure = null;
            for (VirtualViewer viewer : currentViewers) {
                try {
                    if (currentVehicle != null) {
                        currentVehicle.syncPassengersForViewer(viewer);
                    }
                    manager.send(viewer, new WrapperPlayServerDestroyEntities(entityId));
                    if (player) {
                        manager.send(viewer, playerInfoRemovePacket());
                    }
                } catch (RuntimeException | Error viewerFailure) {
                    failure = appendFailure(failure, viewerFailure);
                }
            }
            rethrow(failure);
            return this;
        }
    }

    public VirtualEntity teleport(Location location) {
        try (EntityOperation ignored = operation()) {
            List<ViewerPacket> packets;
            synchronized (this) {
                ensureSpawned();
                this.location = validatedLocation(location);
                packets = viewerPackets(this::teleportPacket);
            }
            send(packets);
            return this;
        }
    }

    /**
     * Replaces the retained spawn location without sending a movement packet to current viewers.
     * The entity must be spawned. This leaves {@code onGround} unchanged and is intended for entities
     * whose visible movement is synchronized by an external vehicle or packet source.
     *
     * @param location the location to retain as a defensive copy
     * @return this entity
     */
    public VirtualEntity setLocationSnapshot(Location location) {
        try (EntityOperation ignored = operation()) {
                synchronized (this) {
                    ensureSpawned();
                    this.location = validatedLocation(location);
                    return this;
                }
        }
    }

    /** Applies a protocol relative move; each delta must be within the encodable range. */
    public VirtualEntity move(double deltaX, double deltaY, double deltaZ, boolean onGround) {
        try (EntityOperation ignored = operation()) {
            List<VirtualViewer> currentViewers;
            synchronized (this) {
                ensureSpawned();
                validateRelativeDelta(deltaX, deltaY, deltaZ);
                this.location = new Location(
                        location.getX() + deltaX,
                        location.getY() + deltaY,
                        location.getZ() + deltaZ,
                        location.getYaw(),
                        location.getPitch()
                );
                this.onGround = onGround;
                currentViewers = List.copyOf(viewers.values());
            }
            broadcast(currentViewers, new WrapperPlayServerEntityRelativeMove(entityId, deltaX, deltaY, deltaZ, onGround));
            return this;
        }
    }

    /** Applies a combined protocol relative move and body rotation. */
    public VirtualEntity moveAndRotate(
            double deltaX,
            double deltaY,
            double deltaZ,
            float yaw,
            float pitch,
            boolean onGround
    ) {
        try (EntityOperation ignored = operation()) {
            validateFinite(yaw, "yaw");
            validateFinite(pitch, "pitch");
            List<VirtualViewer> currentViewers;
            synchronized (this) {
                ensureSpawned();
                validateRelativeDelta(deltaX, deltaY, deltaZ);
                this.location = new Location(
                        location.getX() + deltaX,
                        location.getY() + deltaY,
                        location.getZ() + deltaZ,
                        yaw,
                        pitch
                );
                this.onGround = onGround;
                currentViewers = List.copyOf(viewers.values());
            }
            broadcast(currentViewers, new WrapperPlayServerEntityRelativeMoveAndRotation(
                    entityId,
                    deltaX,
                    deltaY,
                    deltaZ,
                    yaw,
                    pitch,
                    onGround
            ));
            return this;
        }
    }

    /** Uses the smallest correct movement packet for a new absolute location. */
    public VirtualEntity updateLocation(Location target, boolean onGround) {
        try (EntityOperation ignored = operation()) {
            Location checked = validatedLocation(target);
            List<ViewerPacket> packets;
            synchronized (this) {
                ensureSpawned();
                double deltaX = checked.getX() - location.getX();
                double deltaY = checked.getY() - location.getY();
                double deltaZ = checked.getZ() - location.getZ();
                boolean moved = deltaX != 0 || deltaY != 0 || deltaZ != 0;
                boolean rotated = checked.getYaw() != location.getYaw() || checked.getPitch() != location.getPitch();
                this.onGround = onGround;
                if (moved && !relativeDeltaFits(deltaX, deltaY, deltaZ)) {
                    this.location = checked;
                    packets = viewerPackets(this::teleportPacket);
                } else if (moved && rotated) {
                    this.location = checked;
                    PacketWrapper<?> packet = new WrapperPlayServerEntityRelativeMoveAndRotation(
                            entityId, deltaX, deltaY, deltaZ, checked.getYaw(), checked.getPitch(), onGround);
                    packets = viewerPackets(viewer -> packet);
                } else if (moved) {
                    this.location = new Location(
                            checked.getPosition(), location.getYaw(), location.getPitch());
                    PacketWrapper<?> packet = new WrapperPlayServerEntityRelativeMove(
                            entityId, deltaX, deltaY, deltaZ, onGround);
                    packets = viewerPackets(viewer -> packet);
                } else if (rotated) {
                    this.location.setYaw(checked.getYaw());
                    this.location.setPitch(checked.getPitch());
                    PacketWrapper<?> packet = new WrapperPlayServerEntityRotation(
                            entityId, checked.getYaw(), checked.getPitch(), onGround);
                    packets = viewerPackets(viewer -> packet);
                } else {
                    packets = List.of();
                }
            }
            send(packets);
            return this;
        }
    }

    public VirtualEntity rotate(float yaw, float pitch, boolean onGround) {
        try (EntityOperation ignored = operation()) {
            validateFinite(yaw, "yaw");
            validateFinite(pitch, "pitch");
            List<VirtualViewer> currentViewers;
            synchronized (this) {
                ensureSpawned();
                this.location.setYaw(yaw);
                this.location.setPitch(pitch);
                this.onGround = onGround;
                currentViewers = List.copyOf(viewers.values());
            }
            broadcast(currentViewers, new WrapperPlayServerEntityRotation(entityId, yaw, pitch, onGround));
            return this;
        }
    }

    public VirtualEntity rotateHead(float headYaw) {
        try (EntityOperation ignored = operation()) {
            validateFinite(headYaw, "headYaw");
            List<VirtualViewer> currentViewers;
            synchronized (this) {
                ensureSpawned();
                this.headYaw = headYaw;
                currentViewers = List.copyOf(viewers.values());
            }
            broadcast(currentViewers, new WrapperPlayServerEntityHeadLook(entityId, headYaw));
            return this;
        }
    }

    public VirtualEntity velocity(Vector3d velocity) {
        try (EntityOperation ignored = operation()) {
            Vector3d checked = validatedVector(velocity, "velocity");
            List<VirtualViewer> currentViewers;
            synchronized (this) {
                ensureSpawned();
                this.velocity = checked;
                currentViewers = List.copyOf(viewers.values());
            }
            broadcast(currentViewers, new WrapperPlayServerEntityVelocity(entityId, checked));
            return this;
        }
    }

    public VirtualEntity syncMetadata() {
        try (EntityOperation ignored = operation()) {
            List<VirtualViewer> currentViewers;
            List<com.github.retrooper.packetevents.protocol.entity.data.EntityData<?>> entityData;
            synchronized (this) {
                ensureSpawned();
                entityData = metadata == null ? List.of() : metadata.entityData();
                currentViewers = entityData.isEmpty() ? List.of() : List.copyOf(viewers.values());
            }
            if (!entityData.isEmpty()) {
                broadcast(currentViewers, new WrapperPlayServerEntityMetadata(entityId, entityData));
            }
            return this;
        }
    }

    public void remove() {
        try (EntityOperation ignored = operation()) {
            synchronized (this) {
                if (removed) {
                    return;
                }
                removed = true;
            }
            Throwable failure = null;
            try {
                despawn();
            } catch (RuntimeException | Error despawnFailure) {
                failure = despawnFailure;
            }
            List<UUID> viewerIds;
            try {
                detachPassengerRelationships();
            } catch (RuntimeException | Error relationshipFailure) {
                failure = appendFailure(failure, relationshipFailure);
            } finally {
                synchronized (this) {
                    viewerIds = List.copyOf(viewers.keySet());
                    viewers.clear();
                    interactionListeners.clear();
                }
                viewerIds.forEach(manager::unregisterViewerTransport);
                manager.unregister(this);
            }
            rethrow(failure);
        }
    }

    private void sendSpawn(VirtualViewer viewer) {
        List<PacketWrapper<?>> packets = new java.util.ArrayList<>();
        VirtualEntity currentVehicle;
        synchronized (this) {
            ensureSpawned();
            if (!viewers.containsKey(viewer.id())) {
                return;
            }
            if (playerProfile != null) {
                packets.add(playerInfoAddPacket());
            }
            ServerVersion version = serverVersion();
            List<com.github.retrooper.packetevents.protocol.entity.data.EntityData<?>> entityData =
                    metadata == null ? List.of() : metadata.entityData();
            boolean metadataIncludedInSpawn = metadataIncludedInSpawn(version);
            packets.add(spawnPacket(version, metadataIncludedInSpawn ? entityData : List.of()));
            if (!metadataIncludedInSpawn && !entityData.isEmpty()) {
                packets.add(new WrapperPlayServerEntityMetadata(entityId, entityData));
            }
            if (playerProfile != null) {
                packets.add(new WrapperPlayServerEntityRotation(
                        entityId, location.getYaw(), location.getPitch(), onGround));
                packets.add(new WrapperPlayServerEntityHeadLook(entityId, headYaw));
                if (!listed && !modernPlayerInfo()) {
                    packets.add(playerInfoRemovePacket());
                }
            }
            for (Map.Entry<EquipmentSlot, ItemStack> entry : equipment.entrySet()) {
                packets.add(equipmentPacket(entry.getKey(), entry.getValue()));
            }
            if (!attributes.isEmpty()) {
                packets.add(new WrapperPlayServerUpdateAttributes(entityId, List.copyOf(attributes.values())));
            }
            currentVehicle = vehicle;
        }
        for (PacketWrapper<?> packet : packets) {
            manager.send(viewer, packet);
        }
        WrapperPlayServerSetPassengers passengerState = passengersPacket(viewer);
        if (passengerState.getPassengers().length > 0) {
            manager.send(viewer, passengerState);
        }
        if (currentVehicle != null) {
            currentVehicle.syncPassengersForViewer(viewer);
        }
    }

    private void destroyOn(VirtualViewer viewer) {
        manager.sendDirect(viewer, new WrapperPlayServerDestroyEntities(entityId));
        if (playerProfile != null) {
            manager.sendDirect(viewer, playerInfoRemovePacket());
        }
    }

    private void removeViewerAfterFailure(VirtualViewer viewer, Throwable failure) {
        removeViewerAfterFailure(viewer, failure, true);
    }

    private void removeViewerAfterFailure(VirtualViewer viewer, Throwable failure, boolean transportRegistered) {
        VirtualEntity currentVehicle;
        boolean removedViewer;
        synchronized (this) {
            removedViewer = viewers.remove(viewer.id(), viewer);
            currentVehicle = vehicle;
        }
        if (removedViewer && transportRegistered) {
            manager.unregisterViewerTransport(viewer.id());
        }
        if (currentVehicle != null) {
            try {
                currentVehicle.syncPassengersForViewer(viewer);
            } catch (RuntimeException | Error relationshipFailure) {
                failure.addSuppressed(relationshipFailure);
            }
        }
    }

    private PacketWrapper<?> spawnPacket(
            ServerVersion version,
            List<com.github.retrooper.packetevents.protocol.entity.data.EntityData<?>> entityData
    ) {
        if (playerProfile != null && version.isOlderThan(ServerVersion.V_1_20_2)) {
            return new WrapperPlayServerSpawnPlayer(entityId, uuid, location, entityData);
        }
        if (type == EntityTypes.EXPERIENCE_ORB && version.isOlderThan(ServerVersion.V_1_21_5)) {
            return new WrapperPlayServerSpawnExperienceOrb(
                    entityId,
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    experience
            );
        }
        if (type == EntityTypes.LIGHTNING_BOLT && version.isOlderThan(ServerVersion.V_1_16)) {
            return new WrapperPlayServerSpawnWeatherEntity(
                    entityId,
                    (byte) 1,
                    location.getX(),
                    location.getY(),
                    location.getZ()
            );
        }
        if (type == EntityTypes.PAINTING && version.isOlderThan(ServerVersion.V_1_19)) {
            return new WrapperPlayServerSpawnPainting(
                    entityId,
                    uuid,
                    paintingType,
                    location.getPosition().toVector3i(),
                    paintingDirection
            );
        }
        if (type.isInstanceOf(EntityTypes.LIVINGENTITY) && version.isOlderThan(ServerVersion.V_1_19)) {
            return new WrapperPlayServerSpawnLivingEntity(
                    entityId,
                    uuid,
                    type,
                    location,
                    headYaw,
                    velocity,
                    entityData
            );
        }
        return new WrapperPlayServerSpawnEntity(
                entityId,
                uuid,
                type,
                location,
                headYaw,
                type == EntityTypes.EXPERIENCE_ORB ? experience : objectData,
                velocity
        );
    }

    private boolean metadataIncludedInSpawn(ServerVersion version) {
        if (!version.isOlderThan(ServerVersion.V_1_15)) {
            return false;
        }
        return playerProfile != null || type.isInstanceOf(EntityTypes.LIVINGENTITY);
    }

    private WrapperPlayServerEntityEquipment equipmentPacket(EquipmentSlot slot, ItemStack item) {
        return new WrapperPlayServerEntityEquipment(entityId, List.of(new Equipment(slot, item.copy())));
    }

    private void syncPlayerInfo(
            WrapperPlayServerPlayerInfoUpdate.Action modernAction,
            WrapperPlayServerPlayerInfo.Action legacyAction
    ) {
        PacketWrapper<?> packet;
        List<VirtualViewer> currentViewers;
        synchronized (this) {
            packet = spawned ? modernPlayerInfo()
                    ? playerInfoUpdatePacket(EnumSet.of(modernAction))
                    : legacyPlayerInfoPacket(legacyAction) : null;
            currentViewers = packet == null ? List.of() : List.copyOf(viewers.values());
        }
        broadcast(currentViewers, packet);
    }

    private PacketWrapper<?> playerInfoAddPacket() {
        if (modernPlayerInfo()) {
            return playerInfoUpdatePacket(EnumSet.of(
                    WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                    WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME
            ));
        }
        return legacyPlayerInfoPacket(WrapperPlayServerPlayerInfo.Action.ADD_PLAYER);
    }

    private WrapperPlayServerPlayerInfoUpdate playerInfoUpdatePacket(
            EnumSet<WrapperPlayServerPlayerInfoUpdate.Action> actions
    ) {
        return new WrapperPlayServerPlayerInfoUpdate(
                actions,
                new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(
                        copyProfile(playerProfile),
                        listed,
                        latency,
                        gameMode,
                        tabListName,
                        null
                )
        );
    }

    private WrapperPlayServerPlayerInfo legacyPlayerInfoPacket(WrapperPlayServerPlayerInfo.Action action) {
        return new WrapperPlayServerPlayerInfo(
                action,
                new WrapperPlayServerPlayerInfo.PlayerData(
                        tabListName,
                        copyProfile(playerProfile),
                        gameMode,
                        latency
                )
        );
    }

    private PacketWrapper<?> playerInfoRemovePacket() {
        return modernPlayerInfo()
                ? new WrapperPlayServerPlayerInfoRemove(uuid)
                : legacyPlayerInfoPacket(WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER);
    }

    private boolean modernPlayerInfo() {
        return serverVersion().isNewerThanOrEquals(ServerVersion.V_1_19_3);
    }

    private static ServerVersion serverVersion() {
        return PacketEvents.getAPI().getServerManager().getVersion();
    }

    private WrapperPlayServerSetPassengers passengersPacket(VirtualViewer viewer) {
        return new WrapperPlayServerSetPassengers(
                entityId,
                passengers.stream()
                        .filter(VirtualEntity::isSpawned)
                        .filter(passenger -> passenger.hasViewer(viewer.id()))
                        .mapToInt(VirtualEntity::entityId)
                        .toArray()
        );
    }

    private PacketWrapper<?> teleportPacket(VirtualViewer viewer) {
        if (viewer.clientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_2)) {
            return new WrapperPlayServerEntityPositionSync(
                    entityId,
                    new EntityPositionData(location.getPosition(), velocity, location.getYaw(), location.getPitch()),
                    onGround
            );
        }
        return new WrapperPlayServerEntityTeleport(entityId, location, onGround);
    }

    private void syncPassengersIfSpawned() {
        List<VirtualViewer> currentViewers;
        synchronized (this) {
            if (!spawned) {
                return;
            }
            currentViewers = List.copyOf(viewers.values());
        }
        for (VirtualViewer viewer : currentViewers) {
            manager.send(viewer, passengersPacket(viewer));
        }
    }

    private void syncPassengersForViewer(VirtualViewer viewer) {
        synchronized (this) {
            if (!spawned || !viewers.containsKey(viewer.id())) {
                return;
            }
        }
        manager.send(viewer, passengersPacket(viewer));
    }

    private void detachPassengerRelationships() {
        VirtualEntity oldVehicle;
        synchronized (manager.relationshipLock()) {
            oldVehicle = vehicle;
            if (oldVehicle != null) {
                vehicle = null;
                oldVehicle.passengers.remove(this);
            }
            for (VirtualEntity passenger : passengers) {
                if (passenger.vehicle == this) {
                    passenger.vehicle = null;
                }
            }
            passengers.clear();
        }
        if (oldVehicle != null) {
            oldVehicle.syncPassengersIfSpawned();
        }
    }

    private void broadcast(Collection<VirtualViewer> recipients, PacketWrapper<?> packet) {
        if (packet == null) {
            return;
        }
        Throwable failure = null;
        for (VirtualViewer viewer : recipients) {
            try {
                manager.send(viewer, packet);
            } catch (RuntimeException | Error viewerFailure) {
                removeViewerAfterFailure(viewer, viewerFailure);
                failure = appendFailure(failure, viewerFailure);
            }
        }
        rethrow(failure);
    }

    private List<ViewerPacket> viewerPackets(Function<VirtualViewer, PacketWrapper<?>> packetFactory) {
        return viewers.values().stream()
                .map(viewer -> new ViewerPacket(viewer, packetFactory.apply(viewer)))
                .toList();
    }

    private void send(List<ViewerPacket> packets) {
        Throwable failure = null;
        for (ViewerPacket packet : packets) {
            try {
                manager.send(packet.viewer(), packet.packet());
            } catch (RuntimeException | Error viewerFailure) {
                removeViewerAfterFailure(packet.viewer(), viewerFailure);
                failure = appendFailure(failure, viewerFailure);
            }
        }
        rethrow(failure);
    }

    void dispatchInteraction(VirtualEntityInteraction interaction) {
        for (Consumer<VirtualEntityInteraction> listener : interactionListeners) {
            listener.accept(interaction);
        }
    }

    private EntityOperation operation() {
        VirtualEntityManager.Operation managerOperation = manager.operation();
        operationLock.lock();
        return new EntityOperation(managerOperation);
    }

    private final class EntityOperation implements AutoCloseable {
        private final VirtualEntityManager.Operation managerOperation;

        private EntityOperation(VirtualEntityManager.Operation managerOperation) {
            this.managerOperation = managerOperation;
        }

        @Override
        public void close() {
            operationLock.unlock();
            managerOperation.close();
        }
    }

    private void ensureActive() {
        if (removed) {
            throw new IllegalStateException("Entity has been removed");
        }
        if (manager.isClosed()) {
            throw new IllegalStateException("VirtualEntityManager is closed");
        }
    }

    private void ensureSpawned() {
        ensureActive();
        if (!spawned) {
            throw new IllegalStateException("Entity is not spawned");
        }
    }

    private void ensurePlayer() {
        if (playerProfile == null) {
            throw new IllegalStateException("Entity is not configured as a virtual player");
        }
    }

    private static Location copy(Location location) {
        return new Location(location.getPosition(), location.getYaw(), location.getPitch());
    }

    private static Location validatedLocation(Location location) {
        Objects.requireNonNull(location, "location");
        validateFinite(location.getX(), "location.x");
        validateFinite(location.getY(), "location.y");
        validateFinite(location.getZ(), "location.z");
        validateFinite(location.getYaw(), "location.yaw");
        validateFinite(location.getPitch(), "location.pitch");
        return copy(location);
    }

    private static Vector3d validatedVector(Vector3d vector, String name) {
        Objects.requireNonNull(vector, name);
        validateFinite(vector.getX(), name + ".x");
        validateFinite(vector.getY(), name + ".y");
        validateFinite(vector.getZ(), name + ".z");
        return vector;
    }

    private static void validateFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
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

    private record ViewerPacket(VirtualViewer viewer, PacketWrapper<?> packet) {
    }

    private static UserProfile validatedProfile(UserProfile profile) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(profile.getUUID(), "profile UUID");
        String name = Objects.requireNonNull(profile.getName(), "profile name");
        if (name.isEmpty() || name.length() > 16) {
            throw new IllegalArgumentException("Player profile name must contain 1 to 16 characters");
        }
        Objects.requireNonNull(profile.getTextureProperties(), "profile texture properties");
        return profile;
    }

    private static UserProfile copyProfile(UserProfile profile) {
        UserProfile validated = validatedProfile(profile);
        return new UserProfile(
                validated.getUUID(),
                validated.getName(),
                List.copyOf(validated.getTextureProperties())
        );
    }

    private static void validateRelativeDelta(double deltaX, double deltaY, double deltaZ) {
        if (!relativeDeltaFits(deltaX, deltaY, deltaZ)) {
            throw new IllegalArgumentException("Relative movement deltas must each be within [-8, 8)");
        }
    }

    private static boolean relativeDeltaFits(double deltaX, double deltaY, double deltaZ) {
        return Double.isFinite(deltaX)
                && Double.isFinite(deltaY)
                && Double.isFinite(deltaZ)
                && deltaX >= -8 && deltaX < 8
                && deltaY >= -8 && deltaY < 8
                && deltaZ >= -8 && deltaZ < 8;
    }

    /** Builds a virtual entity and registers it with its manager. */
    public static final class Builder {
        private final VirtualEntityManager manager;
        private final EntityType type;
        private Integer entityId;
        private UUID uuid;
        private int objectData;
        private short experience = 1;
        private PaintingType paintingType = PaintingType.KEBAB;
        private Direction paintingDirection = Direction.SOUTH;
        private Vector3d velocity = Vector3d.zero();
        private float headYaw;
        private boolean metadataEnabled;
        private UserProfile playerProfile;
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

        /** Sets the value retained by an experience orb's legacy spawn packet. */
        public Builder experience(short experience) {
            if (type != EntityTypes.EXPERIENCE_ORB) {
                throw new IllegalStateException("Experience can only be configured for an experience orb");
            }
            if (experience < 0) {
                throw new IllegalArgumentException("Experience cannot be negative");
            }
            this.experience = experience;
            return this;
        }

        /** Sets the motive and facing retained by a pre-1.19 painting spawn packet. */
        public Builder painting(PaintingType paintingType, Direction direction) {
            if (type != EntityTypes.PAINTING) {
                throw new IllegalStateException("Painting state can only be configured for a painting");
            }
            this.paintingType = Objects.requireNonNull(paintingType, "paintingType");
            this.paintingDirection = Objects.requireNonNull(direction, "direction");
            if (direction.getHorizontalIndex() < 0) {
                throw new IllegalArgumentException("Painting direction must be horizontal");
            }
            return this;
        }

        public Builder velocity(Vector3d velocity) {
            this.velocity = validatedVector(velocity, "velocity");
            return this;
        }

        public Builder headYaw(float headYaw) {
            validateFinite(headYaw, "headYaw");
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

        Builder playerProfile(UserProfile profile) {
            this.playerProfile = copyProfile(profile);
            this.uuid = this.playerProfile.getUUID();
            return this;
        }

        public VirtualEntity build() {
            return new VirtualEntity(this);
        }
    }
}
