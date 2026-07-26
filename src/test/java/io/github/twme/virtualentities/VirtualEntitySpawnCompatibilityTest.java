package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.Direction;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.protocol.world.PaintingType;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnExperienceOrb;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnLivingEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPainting;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnWeatherEntity;
import io.github.twme.virtualentities.metadata.GeneratedEntityMetadataKeys;
import io.github.twme.virtualentities.metadata.EntityMetadataKeys;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.when;

class VirtualEntitySpawnCompatibilityTest {
    private static ServerManager serverManager;

    @BeforeAll
    static void initializePacketEvents() {
        serverManager = PacketEventsTestSupport.initialize();
    }

    @AfterEach
    void restoreLatestVersion() {
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_21_11);
    }

    @AfterAll
    static void clearPacketEvents() {
        PacketEventsTestSupport.clear();
    }

    @Test
    void usesLegacyLivingSpawnAndItsMetadataBoundary() {
        List<PacketWrapper<?>> packets = spawnPig(ServerVersion.V_1_14_4);

        WrapperPlayServerSpawnLivingEntity legacy = assertInstanceOf(
                WrapperPlayServerSpawnLivingEntity.class,
                packets.get(0)
        );
        assertEquals(1, legacy.getEntityMetadata().size());
        assertEquals(1, packets.size());

        packets = spawnPig(ServerVersion.V_1_15);
        WrapperPlayServerSpawnLivingEntity split = assertInstanceOf(
                WrapperPlayServerSpawnLivingEntity.class,
                packets.get(0)
        );
        assertEquals(0, split.getEntityMetadata().size());
        assertInstanceOf(WrapperPlayServerEntityMetadata.class, packets.get(1));

        packets = spawnPig(ServerVersion.V_1_18_2);
        assertInstanceOf(WrapperPlayServerSpawnLivingEntity.class, packets.get(0));
        assertInstanceOf(WrapperPlayServerEntityMetadata.class, packets.get(1));

        packets = spawnPig(ServerVersion.V_1_19);
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, packets.get(0));
        assertInstanceOf(WrapperPlayServerEntityMetadata.class, packets.get(1));
    }

    @Test
    void writesLogicalCustomNamesWithTheLegacyStringSerializer() {
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_9_4);
        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualEntity pig = VirtualEntities.create().entity(EntityTypes.PIG).metadata().build();
        pig.metadata().set(EntityMetadataKeys.CUSTOM_NAME, Optional.of(Component.text("Legacy name")));
        pig.addViewer(VirtualViewer.of(UUID.randomUUID(), packets::add)).spawn(location());

        WrapperPlayServerSpawnLivingEntity spawn = assertInstanceOf(
                WrapperPlayServerSpawnLivingEntity.class,
                packets.get(0)
        );
        EntityData<?> customName = spawn.getEntityMetadata().stream()
                .filter(data -> data.getIndex() == 2)
                .findFirst()
                .orElseThrow();
        assertEquals(EntityDataTypes.STRING, customName.getType());
        assertEquals("Legacy name", customName.getValue());
        assertEquals(1, packets.size());
    }

    @Test
    void replaysLegacyEquipmentAndPassengerPacketsWithoutCombiningSlots() {
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_9_4);
        VirtualEntityManager manager = VirtualEntities.create();
        VirtualEntity pig = manager.entity(EntityTypes.PIG).build()
                .setEquipment(EquipmentSlot.MAIN_HAND, ItemStack.EMPTY)
                .setEquipment(EquipmentSlot.HELMET, ItemStack.EMPTY);
        VirtualEntity passenger = manager.entity(EntityTypes.ARMOR_STAND).build();
        pig.addPassenger(passenger);

        List<PacketWrapper<?>> packets = new ArrayList<>();
        pig.addViewer(VirtualViewer.of(UUID.randomUUID(), packets::add)).spawn(location());

        List<WrapperPlayServerEntityEquipment> equipmentPackets = packets.stream()
                .filter(WrapperPlayServerEntityEquipment.class::isInstance)
                .map(WrapperPlayServerEntityEquipment.class::cast)
                .toList();
        assertEquals(2, equipmentPackets.size());
        assertEquals(List.of(EquipmentSlot.MAIN_HAND, EquipmentSlot.HELMET), equipmentPackets.stream()
                .map(packet -> packet.getEquipment().get(0).getSlot())
                .toList());
        assertEquals(List.of(1, 1), equipmentPackets.stream()
                .map(packet -> packet.getEquipment().size())
                .toList());
        WrapperPlayServerSetPassengers passengers = packets.stream()
                .filter(WrapperPlayServerSetPassengers.class::isInstance)
                .map(WrapperPlayServerSetPassengers.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(pig.entityId(), passengers.getEntityId());
        assertEquals(List.of(passenger.entityId()), java.util.Arrays.stream(passengers.getPassengers()).boxed().toList());
    }

    @Test
    void sendsPlayerMetadataSeparatelyStartingWithOneFifteen() {
        List<PacketWrapper<?>> packets = spawnPlayer(ServerVersion.V_1_14_4);
        WrapperPlayServerSpawnPlayer legacy = assertInstanceOf(
                WrapperPlayServerSpawnPlayer.class,
                packets.get(1)
        );
        assertEquals(1, legacy.getEntityMetadata().size());
        assertEquals(4, packets.size());

        packets = spawnPlayer(ServerVersion.V_1_15);
        WrapperPlayServerSpawnPlayer split = assertInstanceOf(
                WrapperPlayServerSpawnPlayer.class,
                packets.get(1)
        );
        assertEquals(0, split.getEntityMetadata().size());
        assertInstanceOf(WrapperPlayServerEntityMetadata.class, packets.get(2));
        assertEquals(5, packets.size());
    }

    @Test
    void selectsSpecializedLegacySpawnPackets() {
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_15);
        List<PacketWrapper<?>> lightningPackets = new ArrayList<>();
        VirtualEntities.create().entity(EntityTypes.LIGHTNING_BOLT).build()
                .addViewer(VirtualViewer.of(UUID.randomUUID(), lightningPackets::add))
                .spawn(location());
        assertInstanceOf(WrapperPlayServerSpawnWeatherEntity.class, lightningPackets.get(0));

        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_16);
        List<PacketWrapper<?>> modernLightningPackets = new ArrayList<>();
        VirtualEntities.create().entity(EntityTypes.LIGHTNING_BOLT).build()
                .addViewer(VirtualViewer.of(UUID.randomUUID(), modernLightningPackets::add))
                .spawn(location());
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, modernLightningPackets.get(0));

        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_18_2);
        List<PacketWrapper<?>> paintingPackets = new ArrayList<>();
        VirtualEntities.create().entity(EntityTypes.PAINTING)
                .painting(PaintingType.AZTEC, Direction.WEST)
                .build()
                .addViewer(VirtualViewer.of(UUID.randomUUID(), paintingPackets::add))
                .spawn(location());
        WrapperPlayServerSpawnPainting painting = assertInstanceOf(
                WrapperPlayServerSpawnPainting.class,
                paintingPackets.get(0)
        );
        assertEquals(PaintingType.AZTEC, painting.getType().orElseThrow());
        assertEquals(Direction.WEST, painting.getDirection());
    }

    @Test
    void retainsExperienceAcrossTheUnifiedSpawnTransition() {
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_20_5);
        List<PacketWrapper<?>> legacyPackets = new ArrayList<>();
        VirtualEntities.create().entity(EntityTypes.EXPERIENCE_ORB).experience((short) 7).build()
                .addViewer(VirtualViewer.of(UUID.randomUUID(), legacyPackets::add))
                .spawn(location());
        WrapperPlayServerSpawnExperienceOrb legacy = assertInstanceOf(
                WrapperPlayServerSpawnExperienceOrb.class,
                legacyPackets.get(0)
        );
        assertEquals(7, legacy.getCount());

        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_21_5);
        List<PacketWrapper<?>> modernPackets = new ArrayList<>();
        VirtualEntities.create().entity(EntityTypes.EXPERIENCE_ORB).experience((short) 7).build()
                .addViewer(VirtualViewer.of(UUID.randomUUID(), modernPackets::add))
                .spawn(location());
        WrapperPlayServerSpawnEntity modern = assertInstanceOf(
                WrapperPlayServerSpawnEntity.class,
                modernPackets.get(0)
        );
        assertEquals(7, modern.getData());
    }

    private static List<PacketWrapper<?>> spawnPig(ServerVersion version) {
        when(serverManager.getVersion()).thenReturn(version);
        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualEntity pig = VirtualEntities.create().entity(EntityTypes.PIG).metadata().build();
        pig.metadata().set(GeneratedEntityMetadataKeys.Entity.SHARED_FLAGS, (byte) 0x40);
        pig.addViewer(VirtualViewer.of(UUID.randomUUID(), packets::add)).spawn(location());
        return packets;
    }

    private static List<PacketWrapper<?>> spawnPlayer(ServerVersion version) {
        when(serverManager.getVersion()).thenReturn(version);
        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualEntity player = VirtualEntities.create()
                .player(new UserProfile(UUID.randomUUID(), "LegacyPlayer"))
                .metadata()
                .build();
        player.metadata().set(GeneratedEntityMetadataKeys.Entity.SHARED_FLAGS, (byte) 0x40);
        player.addViewer(VirtualViewer.of(UUID.randomUUID(), packets::add)).spawn(location());
        return packets;
    }

    private static Location location() {
        return new Location(1, 64, 2, 10, 20);
    }
}
