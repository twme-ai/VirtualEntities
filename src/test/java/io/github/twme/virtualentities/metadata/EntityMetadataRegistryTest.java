package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.util.Vector3f;
import io.github.twme.virtualentities.PacketEventsTestSupport;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        assertTrue(registry.versions().contains("1.9.4"));
        assertTrue(registry.versions().contains("1.13.1"));
        assertTrue(registry.versions().contains("1.13.2"));
        assertTrue(registry.versions().contains("1.14"));
        assertTrue(registry.versions().contains("1.14.1"));
        assertTrue(registry.versions().contains("1.21.11"));

        EntityMetadataSchema pig = registry.schema("1.21.11", "Pig");
        assertEquals(0, pig.require("SHARED_FLAGS").index());
        assertEquals(17, pig.require("BOOST_TIME").index());
        assertEquals(18, pig.require("VARIANT").index());
    }

    @Test
    void resolvesReviewedLegacySchemasAndProtocolChangePoints() {
        EntityMetadataSchema pig194 = registry.schema("1.9.4", EntityTypes.PIG);
        assertEquals("Pig", pig194.entityName());
        assertEquals(0, pig194.require("SHARED_FLAGS").index());
        assertEquals(2, pig194.require("CUSTOM_NAME").index());
        assertEquals("String", pig194.require("CUSTOM_NAME").dataType());
        assertEquals(12, pig194.require("SADDLE").index());

        EntityMetadataSchema arrow113 = registry.schema("1.13", EntityTypes.ARROW);
        EntityMetadataSchema arrow1131 = registry.schema("1.13.1", EntityTypes.ARROW);
        EntityMetadataSchema arrow1132 = registry.schema("1.13.2", EntityTypes.ARROW);
        assertTrue(arrow113.find("OWNERUUID").isEmpty());
        assertEquals(7, arrow113.require("ID_EFFECT_COLOR").index());
        assertEquals(7, arrow1131.require("OWNERUUID").index());
        assertEquals(8, arrow1131.require("ID_EFFECT_COLOR").index());
        assertEquals(7, arrow1132.require("OWNERUUID").index());
        assertEquals(8, arrow1132.require("ID_EFFECT_COLOR").index());

        EntityMetadataSchema pig1132 = registry.schema("1.13.2", EntityTypes.PIG);
        assertTrue(pig1132.find("POSE").isEmpty());
        assertTrue(pig1132.find("SLEEPING_POS").isEmpty());
        assertEquals(13, pig1132.require("SADDLE").index());
        assertEquals(6, registry.schema("1.14.4", EntityTypes.PIG).require("POSE").index());

        EntityMetadataSchema villager = registry.schema("1.13.2", EntityTypes.VILLAGER);
        assertEquals(13, villager.require("PROFESSION").index());
        assertTrue(villager.find("UNHAPPY_COUNTER").isEmpty());

        EntityMetadataSchema zombie = registry.schema("1.13.2", EntityTypes.ZOMBIE);
        assertEquals(14, zombie.require("ARMS_RAISED").index());
        assertEquals(15, zombie.require("DROWNED_CONVERSION").index());

        EntityMetadataSchema zombieVillager = registry.schema("1.13.2", EntityTypes.ZOMBIE_VILLAGER);
        assertEquals(16, zombieVillager.require("CONVERTING").index());
        assertEquals(17, zombieVillager.require("PROFESSION").index());

        assertEquals(
                12,
                registry.schema("1.13.2", EntityTypes.SKELETON).require("SWINGING_ARMS").index()
        );
        assertEquals(
                13,
                registry.schema("1.13.2", EntityTypes.EVOKER).require("SPELL_CASTING").index()
        );

        // The pre-1.14 Wolf schema contains a second health value in addition
        // to LivingEntity.  It must keep its distinct protocol name so the
        // inherited field is not silently overwritten during flattening.
        EntityMetadataSchema wolf194 = registry.schema("1.9.4", EntityTypes.WOLF);
        assertEquals(6, wolf194.require("HEALTH").index());
        assertEquals(13, wolf194.require("WOLF_HEALTH").index());

        EntityMetadataSchema villager114 = registry.schema("1.14", EntityTypes.VILLAGER);
        EntityMetadataSchema villager1141 = registry.schema("1.14.1", EntityTypes.VILLAGER);
        assertTrue(villager114.find("UNHAPPY_COUNTER").isEmpty());
        assertEquals(15, villager114.require("VILLAGER_DATA").index());
        assertEquals(15, villager1141.require("UNHAPPY_COUNTER").index());
        assertEquals(16, villager1141.require("VILLAGER_DATA").index());
    }

    @Test
    void encodesLogicalCustomNamesForLegacyAndModernMetadata() {
        Component name = Component.text("Legacy name");
        VirtualMetadata legacy = new VirtualMetadata(registry.schema("1.12.2", EntityTypes.PIG));
        legacy.set(EntityMetadataKeys.CUSTOM_NAME, Optional.of(name));

        assertEquals(Optional.of(name), legacy.get(EntityMetadataKeys.CUSTOM_NAME).orElseThrow());
        assertSame(EntityDataTypes.STRING, legacy.entityData().get(0).getType());
        assertEquals("Legacy name", legacy.entityData().get(0).getValue());

        VirtualMetadata modern = new VirtualMetadata(registry.schema("1.13.2", EntityTypes.PIG));
        modern.set(EntityMetadataKeys.CUSTOM_NAME, Optional.of(name));
        assertSame(EntityDataTypes.OPTIONAL_ADV_COMPONENT, modern.entityData().get(0).getType());
        assertEquals(Optional.of(name), modern.entityData().get(0).getValue());
    }

    @Test
    void createsEntityDataUsingResolvedIndex() {
        VirtualMetadata metadata = new VirtualMetadata(registry.schema("1.21.11", "Pig"));
        metadata.set(MetadataKey.of("CUSTOM_NAME_VISIBLE", EntityDataTypes.BOOLEAN), true);

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
    void resolvesEveryRegisteredEntityTypeAcrossLegacyProtocolBoundaries() {
        Map<String, ClientVersion> boundaries = new LinkedHashMap<>();
        boundaries.put("1.9.4", ClientVersion.V_1_9_3);
        boundaries.put("1.10", ClientVersion.V_1_10);
        boundaries.put("1.11", ClientVersion.V_1_11);
        boundaries.put("1.12", ClientVersion.V_1_12);
        boundaries.put("1.13", ClientVersion.V_1_13);
        boundaries.put("1.13.1", ClientVersion.V_1_13_1);
        boundaries.put("1.13.2", ClientVersion.V_1_13_2);
        boundaries.put("1.14", ClientVersion.V_1_14);
        boundaries.put("1.14.1", ClientVersion.V_1_14_1);

        List<String> unresolved = new ArrayList<>();
        for (Map.Entry<String, ClientVersion> boundary : boundaries.entrySet()) {
            for (EntityType type : EntityTypes.values()) {
                if (type.getId(boundary.getValue()) < 0) {
                    continue;
                }
                try {
                    registry.schema(boundary.getKey(), type);
                } catch (IllegalArgumentException exception) {
                    unresolved.add(boundary.getKey() + ":" + type.getName().getKey());
                }
            }
        }

        assertEquals(List.of(), unresolved);
    }

    @Test
    void resolvesEveryPacketEventsEntityTypeAtEveryBundledProtocolSnapshot() {
        List<String> unresolved = new ArrayList<>();
        for (String snapshot : registry.versions()) {
            ClientVersion clientVersion = clientVersionFor(snapshot);
            if (clientVersion == null) {
                // 26w14a is a data snapshot without a matching PacketEvents
                // enum value; the structural verifier still covers it.
                continue;
            }
            for (EntityType type : EntityTypes.values()) {
                if (type.getId(clientVersion) < 0) {
                    continue;
                }
                try {
                    registry.schema(snapshot, type);
                } catch (IllegalArgumentException exception) {
                    unresolved.add(snapshot + ":" + type.getName().getKey());
                }
            }
        }

        assertEquals(List.of(), unresolved);
    }

    @Test
    void resolvesEveryPacketEventsServerReleaseInTheSupportedRange() {
        List<String> unresolved = new ArrayList<>();
        for (ServerVersion serverVersion : ServerVersion.values()) {
            if (serverVersion.getProtocolVersion() < ServerVersion.V_1_9_4.getProtocolVersion()
                    || serverVersion.getReleaseName().equals("ERROR")) {
                continue;
            }
            ClientVersion clientVersion = serverVersion.toClientVersion();
            for (EntityType type : EntityTypes.values()) {
                if (type.getId(clientVersion) < 0) {
                    continue;
                }
                try {
                    registry.schema(serverVersion.getReleaseName(), type);
                } catch (IllegalArgumentException exception) {
                    unresolved.add(serverVersion.getReleaseName() + ":" + type.getName().getKey());
                }
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

    @Test
    void readsOnlyExplicitFixedAndVersionedMetadataValuesByFieldName() {
        VirtualMetadata metadata = new VirtualMetadata(registry.schema("1.21.11", "Pig"));
        MetadataKey<Boolean> fixed = MetadataKey.of("CUSTOM_NAME_VISIBLE", EntityDataTypes.BOOLEAN);
        MetadataKey<Boolean> sameField = MetadataKey.of("CUSTOM_NAME_VISIBLE", EntityDataTypes.BOOLEAN);

        assertTrue(metadata.get(fixed).isEmpty());
        assertTrue(!metadata.contains(fixed));
        metadata.set(fixed, true);
        assertEquals(true, metadata.get(sameField).orElseThrow());
        assertTrue(metadata.contains(sameField));
        metadata.set(sameField, false);
        assertEquals(false, metadata.get(fixed).orElseThrow());
        assertThrows(
                IllegalArgumentException.class,
                () -> metadata.get(MetadataKey.of("CUSTOM_NAME_VISIBLE", EntityDataTypes.INT))
        );
        metadata.remove(fixed);
        assertTrue(metadata.get(sameField).isEmpty());
        assertTrue(!metadata.contains(sameField));

        metadata.set(GeneratedEntityMetadataKeys.Pig.BOOST_TIME, 12);
        assertEquals(12, metadata.get(GeneratedEntityMetadataKeys.Pig.BOOST_TIME).orElseThrow());
        metadata.remove(GeneratedEntityMetadataKeys.Pig.BOOST_TIME);
        assertTrue(metadata.get(GeneratedEntityMetadataKeys.Pig.BOOST_TIME).isEmpty());
    }

    @Test
    void resolvesTextDisplayMetadataAtOldestAndLatestBundledVersions() {
        EntityMetadataSchema oldest = registry.schema("1.19.4", EntityTypes.TEXT_DISPLAY);
        EntityMetadataSchema latest = registry.schema("26.2", EntityTypes.TEXT_DISPLAY);

        assertEquals("Text Display", oldest.entityName());
        assertEquals(10, oldest.require("TRANSLATION").index());
        assertEquals(22, oldest.require("TEXT").index());
        assertEquals(11, latest.require("TRANSLATION").index());
        assertEquals(23, latest.require("TEXT").index());

        Vector3f translation = new Vector3f(1, 2, 3);
        VirtualMetadata metadata = new VirtualMetadata(oldest);
        metadata.set(GeneratedEntityMetadataKeys.Display.TRANSLATION, translation);
        assertEquals(translation, metadata.get(GeneratedEntityMetadataKeys.Display.TRANSLATION).orElseThrow());
        assertSame(EntityDataTypes.VECTOR3F, metadata.entityData().get(0).getType());
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

    private static ClientVersion clientVersionFor(String snapshot) {
        if (snapshot.equals("26w14a")) {
            return null;
        }
        String enumName = "V_" + snapshot.replace('.', '_');
        try {
            return ClientVersion.valueOf(enumName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
