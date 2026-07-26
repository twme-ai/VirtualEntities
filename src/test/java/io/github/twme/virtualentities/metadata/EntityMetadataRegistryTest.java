package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityMetadataRegistryTest {
    private final EntityMetadataRegistry registry = new EntityMetadataRegistry();

    @Test
    void loadsBundledVersionsAndInheritedFields() {
        assertTrue(registry.versions().contains("1.21.11"));

        EntityMetadataSchema pig = registry.schema("1.21.11", "Pig");
        assertEquals(0, pig.require("SHARED_FLAGS").index());
        assertEquals(17, pig.require("BOOST_TIME").index());
        assertEquals(18, pig.require("VARIANT").index());
    }

    @Test
    void createsEntityDataUsingResolvedIndex() {
        VirtualMetadata metadata = new VirtualMetadata(registry.schema("1.21.11", "Pig"));
        EntityDataType<Boolean> booleanType = new EntityDataType<>(
                null,
                wrapper -> false,
                (wrapper, value) -> { }
        );
        metadata.set(MetadataKey.of("CUSTOM_NAME_VISIBLE", booleanType), true);

        assertEquals(1, metadata.entityData().size());
        assertEquals(3, metadata.entityData().get(0).getIndex());
        assertEquals(true, metadata.entityData().get(0).getValue());
    }

    @Test
    void rejectsUnknownVersionsAndFields() {
        assertThrows(IllegalArgumentException.class, () -> registry.schema("not-a-version", "Pig"));
        EntityMetadataSchema pig = registry.schema("1.21.11", "Pig");
        assertThrows(IllegalArgumentException.class, () -> pig.require("DOES_NOT_EXIST"));
    }
}
