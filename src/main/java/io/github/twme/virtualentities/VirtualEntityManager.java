package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import io.github.twme.virtualentities.metadata.EntityMetadataRegistry;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns virtual entity identity and lookup for one library instance. */
public final class VirtualEntityManager implements AutoCloseable {
    private final EntityIdProvider entityIdProvider;
    private final EntityMetadataRegistry metadataRegistry;
    private final Object relationshipLock = new Object();
    private final Map<Integer, VirtualEntity> byId = new ConcurrentHashMap<>();
    private final Map<UUID, VirtualEntity> byUuid = new ConcurrentHashMap<>();

    VirtualEntityManager(EntityIdProvider entityIdProvider, EntityMetadataRegistry metadataRegistry) {
        this.entityIdProvider = Objects.requireNonNull(entityIdProvider, "entityIdProvider");
        this.metadataRegistry = Objects.requireNonNull(metadataRegistry, "metadataRegistry");
    }

    public VirtualEntity.Builder entity(EntityType type) {
        return VirtualEntity.builder(this, Objects.requireNonNull(type, "type"));
    }

    /** Creates a player entity builder using the profile UUID as the entity UUID. */
    public VirtualEntity.Builder player(UserProfile profile) {
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

    /** Validates and dispatches a pre-26.1 interact-entity packet when it targets a visible entity. */
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

    /** Validates and dispatches a 26.1+ attack packet when it targets a visible entity. */
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
        int id = entityIdProvider.nextEntityId();
        if (byId.containsKey(id)) {
            throw new IllegalStateException("EntityIdProvider returned duplicate ID " + id);
        }
        return id;
    }

    void register(VirtualEntity entity) {
        if (byId.putIfAbsent(entity.entityId(), entity) != null) {
            throw new IllegalArgumentException("Duplicate virtual entity ID " + entity.entityId());
        }
        if (byUuid.putIfAbsent(entity.uuid(), entity) != null) {
            byId.remove(entity.entityId(), entity);
            throw new IllegalArgumentException("Duplicate virtual entity UUID " + entity.uuid());
        }
    }

    void unregister(VirtualEntity entity) {
        byId.remove(entity.entityId(), entity);
        byUuid.remove(entity.uuid(), entity);
    }

    Object relationshipLock() {
        return relationshipLock;
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
        entity.dispatchInteraction(interaction);
        return Optional.of(interaction);
    }

    @Override
    public void close() {
        for (VirtualEntity entity : java.util.List.copyOf(byId.values())) {
            entity.remove();
        }
    }
}
