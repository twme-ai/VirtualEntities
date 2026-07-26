package io.github.twme.virtualentities;

import io.github.twme.virtualentities.metadata.EntityMetadataRegistry;

import java.util.Objects;

/** Entry point for creating and tracking virtual entities. */
public final class VirtualEntities {
    private VirtualEntities() {
    }

    public static VirtualEntityManager create() {
        return create(new AtomicEntityIdProvider());
    }

    public static VirtualEntityManager create(EntityIdProvider entityIdProvider) {
        return new VirtualEntityManager(
                Objects.requireNonNull(entityIdProvider, "entityIdProvider"),
                new EntityMetadataRegistry()
        );
    }
}
