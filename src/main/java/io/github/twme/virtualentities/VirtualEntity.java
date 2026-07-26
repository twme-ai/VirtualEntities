package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.attribute.Attribute;
import com.github.retrooper.packetevents.protocol.entity.EntityPositionData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.Location;
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
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
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
import java.util.function.Consumer;
import java.util.function.Function;

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
    private final CopyOnWriteArrayList<VirtualEntity> passengers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<VirtualEntityInteraction>> interactionListeners = new CopyOnWriteArrayList<>();
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
    public synchronized VirtualEntity setPlayerProfile(UserProfile profile) {
        ensureActive();
        ensurePlayer();
        UserProfile replacement = validatedProfile(profile);
        if (!uuid.equals(replacement.getUUID())) {
            throw new IllegalArgumentException("Player profile UUID cannot change after entity creation");
        }
        playerProfile = copyProfile(replacement);
        if (spawned) {
            for (VirtualViewer viewer : viewers.values()) {
                manager.send(viewer, new WrapperPlayServerDestroyEntities(entityId));
                manager.send(viewer, playerInfoRemovePacket());
                sendSpawn(viewer);
            }
        }
        return this;
    }

    public synchronized GameMode gameMode() {
        ensurePlayer();
        return gameMode;
    }

    public synchronized VirtualEntity setGameMode(GameMode gameMode) {
        ensureActive();
        ensurePlayer();
        this.gameMode = Objects.requireNonNull(gameMode, "gameMode");
        syncPlayerInfo(
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_GAME_MODE,
                WrapperPlayServerPlayerInfo.Action.UPDATE_GAME_MODE
        );
        return this;
    }

    public synchronized Optional<Component> tabListName() {
        ensurePlayer();
        return Optional.ofNullable(tabListName);
    }

    public synchronized VirtualEntity setTabListName(Component tabListName) {
        ensureActive();
        ensurePlayer();
        this.tabListName = tabListName;
        syncPlayerInfo(
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_DISPLAY_NAME,
                WrapperPlayServerPlayerInfo.Action.UPDATE_DISPLAY_NAME
        );
        return this;
    }

    public synchronized boolean isListed() {
        ensurePlayer();
        return listed;
    }

    public synchronized VirtualEntity setListed(boolean listed) {
        ensureActive();
        ensurePlayer();
        this.listed = listed;
        if (spawned) {
            PacketWrapper<?> packet = modernPlayerInfo()
                    ? playerInfoUpdatePacket(EnumSet.of(WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LISTED))
                    : listed
                            ? legacyPlayerInfoPacket(WrapperPlayServerPlayerInfo.Action.ADD_PLAYER)
                            : playerInfoRemovePacket();
            broadcast(packet);
        }
        return this;
    }

    public synchronized int latency() {
        ensurePlayer();
        return latency;
    }

    public synchronized VirtualEntity setLatency(int latency) {
        ensureActive();
        ensurePlayer();
        this.latency = latency;
        syncPlayerInfo(
                WrapperPlayServerPlayerInfoUpdate.Action.UPDATE_LATENCY,
                WrapperPlayServerPlayerInfo.Action.UPDATE_LATENCY
        );
        return this;
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
    public List<VirtualEntity> passengers() {
        return List.copyOf(passengers);
    }

    /** Returns the vehicle this entity is riding, if any. */
    public Optional<VirtualEntity> vehicle() {
        return Optional.ofNullable(vehicle);
    }

    /** Adds a managed virtual entity as a passenger. */
    public VirtualEntity addPassenger(VirtualEntity passenger) {
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

    /** Removes a passenger while preserving all other passengers. */
    public VirtualEntity removePassenger(VirtualEntity passenger) {
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

    /** Removes every passenger from this entity. */
    public VirtualEntity clearPassengers() {
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
            manager.send(viewer, new WrapperPlayServerDestroyEntities(entityId));
            if (playerProfile != null) {
                manager.send(viewer, playerInfoRemovePacket());
            }
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
            if (playerProfile != null) {
                broadcast(playerInfoRemovePacket());
            }
            spawned = false;
        }
        return this;
    }

    public synchronized VirtualEntity teleport(Location location) {
        ensureSpawned();
        this.location = copy(Objects.requireNonNull(location, "location"));
        broadcast(this::teleportPacket);
        return this;
    }

    /** Applies a protocol relative move; each delta must be within the encodable range. */
    public synchronized VirtualEntity move(double deltaX, double deltaY, double deltaZ, boolean onGround) {
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
        broadcast(new WrapperPlayServerEntityRelativeMove(entityId, deltaX, deltaY, deltaZ, onGround));
        return this;
    }

    /** Applies a combined protocol relative move and body rotation. */
    public synchronized VirtualEntity moveAndRotate(
            double deltaX,
            double deltaY,
            double deltaZ,
            float yaw,
            float pitch,
            boolean onGround
    ) {
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
        broadcast(new WrapperPlayServerEntityRelativeMoveAndRotation(
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

    /** Uses the smallest correct movement packet for a new absolute location. */
    public synchronized VirtualEntity updateLocation(Location target, boolean onGround) {
        ensureSpawned();
        Objects.requireNonNull(target, "target");
        double deltaX = target.getX() - location.getX();
        double deltaY = target.getY() - location.getY();
        double deltaZ = target.getZ() - location.getZ();
        boolean moved = deltaX != 0 || deltaY != 0 || deltaZ != 0;
        boolean rotated = target.getYaw() != location.getYaw() || target.getPitch() != location.getPitch();

        if (moved && !relativeDeltaFits(deltaX, deltaY, deltaZ)) {
            this.onGround = onGround;
            return teleport(target);
        }
        if (moved && rotated) {
            return moveAndRotate(deltaX, deltaY, deltaZ, target.getYaw(), target.getPitch(), onGround);
        }
        if (moved) {
            return move(deltaX, deltaY, deltaZ, onGround);
        }
        if (rotated) {
            return rotate(target.getYaw(), target.getPitch(), onGround);
        }
        this.onGround = onGround;
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
        interactionListeners.clear();
        removed = true;
        manager.unregister(this);
    }

    private void sendSpawn(VirtualViewer viewer) {
        if (playerProfile != null) {
            manager.send(viewer, playerInfoAddPacket());
        }
        boolean metadataIncludedInSpawn = playerProfile != null && legacyPlayerSpawn();
        if (metadataIncludedInSpawn) {
            manager.send(viewer, new WrapperPlayServerSpawnPlayer(
                    entityId,
                    uuid,
                    location,
                    metadata == null ? List.of() : metadata.entityData()
            ));
        } else {
            manager.send(
                    viewer,
                    new WrapperPlayServerSpawnEntity(entityId, uuid, type, location, headYaw, objectData, velocity)
            );
        }
        if (!metadataIncludedInSpawn && metadata != null && !metadata.entityData().isEmpty()) {
            manager.send(viewer, new WrapperPlayServerEntityMetadata(entityId, metadata.entityData()));
        }
        if (playerProfile != null) {
            manager.send(
                    viewer,
                    new WrapperPlayServerEntityRotation(entityId, location.getYaw(), location.getPitch(), onGround)
            );
            manager.send(viewer, new WrapperPlayServerEntityHeadLook(entityId, headYaw));
            if (!listed && !modernPlayerInfo()) {
                manager.send(viewer, playerInfoRemovePacket());
            }
        }
        for (Map.Entry<EquipmentSlot, ItemStack> entry : equipment.entrySet()) {
            manager.send(viewer, equipmentPacket(entry.getKey(), entry.getValue()));
        }
        if (!attributes.isEmpty()) {
            manager.send(viewer, new WrapperPlayServerUpdateAttributes(entityId, List.copyOf(attributes.values())));
        }
        if (!passengers.isEmpty()) {
            manager.send(viewer, passengersPacket());
        }
    }

    private WrapperPlayServerEntityEquipment equipmentPacket(EquipmentSlot slot, ItemStack item) {
        return new WrapperPlayServerEntityEquipment(entityId, List.of(new Equipment(slot, item)));
    }

    private void syncPlayerInfo(
            WrapperPlayServerPlayerInfoUpdate.Action modernAction,
            WrapperPlayServerPlayerInfo.Action legacyAction
    ) {
        if (spawned) {
            broadcast(modernPlayerInfo()
                    ? playerInfoUpdatePacket(EnumSet.of(modernAction))
                    : legacyPlayerInfoPacket(legacyAction));
        }
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
        return PacketEvents.getAPI().getServerManager().getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_19_3);
    }

    private boolean legacyPlayerSpawn() {
        return !PacketEvents.getAPI().getServerManager().getVersion()
                .isNewerThanOrEquals(ServerVersion.V_1_20_2);
    }

    private WrapperPlayServerSetPassengers passengersPacket() {
        return new WrapperPlayServerSetPassengers(
                entityId,
                passengers.stream().mapToInt(VirtualEntity::entityId).toArray()
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
        if (spawned) {
            broadcast(passengersPacket());
        }
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

    private void broadcast(PacketWrapper<?> packet) {
        viewers.values().forEach(viewer -> manager.send(viewer, packet));
    }

    private void broadcast(Function<VirtualViewer, PacketWrapper<?>> packetFactory) {
        viewers.values().forEach(viewer -> manager.send(viewer, packetFactory.apply(viewer)));
    }

    void dispatchInteraction(VirtualEntityInteraction interaction) {
        for (Consumer<VirtualEntityInteraction> listener : interactionListeners) {
            listener.accept(interaction);
        }
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

    private void ensurePlayer() {
        if (playerProfile == null) {
            throw new IllegalStateException("Entity is not configured as a virtual player");
        }
    }

    private static Location copy(Location location) {
        return new Location(location.getPosition(), location.getYaw(), location.getPitch());
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
