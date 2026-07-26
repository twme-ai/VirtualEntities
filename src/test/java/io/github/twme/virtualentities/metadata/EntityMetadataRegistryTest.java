package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntityMetadataRegistryTest {
    private final EntityMetadataRegistry registry = new EntityMetadataRegistry();

    @BeforeAll
    static void initializePacketEvents() {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        ServerManager serverManager = mock(ServerManager.class);
        when(api.getServerManager()).thenReturn(serverManager);
        when(api.getNettyManager()).thenReturn(new NettyManagerImpl());
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_21_11);
        PacketEvents.setAPI(api);
    }

    @AfterAll
    static void clearPacketEvents() {
        PacketEvents.setAPI(null);
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
