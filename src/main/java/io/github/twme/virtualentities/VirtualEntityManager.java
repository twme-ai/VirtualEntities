package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
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
    private final Map<Integer, VirtualEntity> byId = new ConcurrentHashMap<>();
    private final Map<UUID, VirtualEntity> byUuid = new ConcurrentHashMap<>();

    VirtualEntityManager(EntityIdProvider entityIdProvider, EntityMetadataRegistry metadataRegistry) {
        this.entityIdProvider = Objects.requireNonNull(entityIdProvider, "entityIdProvider");
        this.metadataRegistry = Objects.requireNonNull(metadataRegistry, "metadataRegistry");
    }

    public VirtualEntity.Builder entity(EntityType type) {
        return VirtualEntity.builder(this, Objects.requireNonNull(type, "type"));
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

    @Override
    public void close() {
        for (VirtualEntity entity : java.util.List.copyOf(byId.values())) {
            entity.remove();
        }
    }
}
