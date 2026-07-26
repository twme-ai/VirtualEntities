package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import io.github.twme.virtualentities.PacketEventsTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class EntityMetadataRegistryTest {
    private final EntityMetadataRegistry registry = new EntityMetadataRegistry();

    @BeforeAll
    static void initializePacketEvents() {
        PacketEventsTestSupport.initialize();
    }

    @AfterAll
    static void clearPacketEvents() {
        PacketEventsTestSupport.clear();
    }

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

    @Test
    void resolvesClosestVersionAndPacketEventsEntityNames() {
        assertEquals("1.21.9", registry.resolveVersion("1.21.10"));
        assertEquals("1.17", registry.resolveVersion("1.18.2"));
        assertEquals("Mushroom Cow", registry.schema("1.21.11", entityType("mooshroom", null)).entityName());

        EntityType boat = entityType("boat", null);
        EntityType acaciaBoat = entityType("acacia_boat", boat);
        assertEquals("Boat", registry.schema("1.21.11", acaciaBoat).entityName());
    }

    @Test
    void resolvesEveryPacketEventsEntityTypeInLatestData() {
        List<String> unresolved = new ArrayList<>();
        for (EntityType type : EntityTypes.values()) {
            try {
                registry.schema("1.21.11", type);
            } catch (IllegalArgumentException exception) {
                unresolved.add(type.getName().toString());
            }
        }

        assertEquals(List.of(), unresolved);
    }

    @Test
    void generatedKeysUseInheritanceAndVersionedSerializers() {
        VirtualMetadata pig = new VirtualMetadata(registry.schema("1.21.11", "Pig"));
        pig.set(GeneratedEntityMetadataKeys.Pig.SHARED_FLAGS, (byte) 0x40)
                .set(GeneratedEntityMetadataKeys.Pig.BOOST_TIME, 20);

        assertEquals(2, pig.entityData().size());
        assertEquals(0, pig.entityData().get(0).getIndex());
        assertEquals(17, pig.entityData().get(1).getIndex());
        assertSame(
                EntityDataTypes.TYPED_CAT_VARIANT,
                GeneratedEntityMetadataKeys.Cat.VARIANT.type("CatVariant")
        );
        assertSame(
                EntityDataTypes.TYPED_CAT_VARIANT,
                GeneratedEntityMetadataKeys.Cat.VARIANT.type("Holder<CatVariant>")
        );
    }

    private static EntityType entityType(String name, EntityType parent) {
        return new EntityType() {
            @Override
            public boolean isInstanceOf(EntityType target) {
                return this == target || parent != null && parent.isInstanceOf(target);
            }

            @Override
            public Optional<EntityType> getParent() {
                return Optional.ofNullable(parent);
            }

            @Override
            public ResourceLocation getName() {
                return ResourceLocation.minecraft(name);
            }

            @Override
            public int getId(ClientVersion version) {
                return 0;
            }

            @Override
            public int getLegacyId(ClientVersion version) {
                return 0;
            }
        };
    }
}
