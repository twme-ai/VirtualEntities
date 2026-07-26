package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.attribute.Attribute;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.StaticEntityType;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VirtualEntityTest {
    @BeforeAll
    static void initializePacketEvents() {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        ServerManager serverManager = mock(ServerManager.class);
        when(api.getServerManager()).thenReturn(serverManager);
        when(api.getNettyManager()).thenReturn(new NettyManagerImpl());
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_21_11);
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        PacketEvents.setAPI(api);
    }

    @AfterAll
    static void clearPacketEvents() {
        PacketEvents.setAPI(null);
    }

    private static EntityType testType() {
        return new StaticEntityType(null, null);
    }

    @Test
    void managesViewerLifecycleAndPackets() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(100));
        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualViewer viewer = VirtualViewer.of(UUID.randomUUID(), packets::add);
        VirtualEntity entity = manager.entity(testType()).build().addViewer(viewer);

        entity.spawn(new Location(1, 2, 3, 10, 20));
        assertEquals(100, entity.entityId());
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, packets.get(0));

        entity.teleport(new Location(4, 5, 6, 30, 40));
        assertInstanceOf(WrapperPlayServerEntityTeleport.class, packets.get(1));
        assertEquals(4, entity.location().getX());

        entity.removeViewer(viewer.id());
        assertInstanceOf(WrapperPlayServerDestroyEntities.class, packets.get(2));
        assertFalse(entity.viewers().contains(viewer));

        entity.remove();
        assertTrue(entity.isRemoved());
        assertTrue(manager.find(100).isEmpty());
    }

    @Test
    void addingViewerAfterSpawnSendsCurrentState() {
        VirtualEntity entity = VirtualEntities.create(new AtomicEntityIdProvider(200))
                .entity(testType())
                .build()
                .spawn(new Location(0, 64, 0, 0, 0));
        List<PacketWrapper<?>> packets = new ArrayList<>();

        entity.addViewer(VirtualViewer.of(UUID.randomUUID(), packets::add));

        assertEquals(1, packets.size());
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, packets.get(0));
    }

    @Test
    void replaysEquipmentAttributesAndPassengersToViewers() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(300));
        VirtualEntity vehicle = manager.entity(testType()).build();
        VirtualEntity passenger = manager.entity(testType()).build();
        ItemStack helmet = mock(ItemStack.class);
        Attribute maxHealth = mock(Attribute.class);

        vehicle.setEquipment(EquipmentSlot.HELMET, helmet)
                .setAttribute(maxHealth, 40)
                .addPassenger(passenger);

        List<PacketWrapper<?>> packets = new ArrayList<>();
        vehicle.addViewer(VirtualViewer.of(UUID.randomUUID(), packets::add));
        vehicle.spawn(new Location(0, 64, 0, 0, 0));

        assertEquals(4, packets.size());
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, packets.get(0));
        assertInstanceOf(WrapperPlayServerEntityEquipment.class, packets.get(1));
        assertInstanceOf(WrapperPlayServerUpdateAttributes.class, packets.get(2));
        assertInstanceOf(WrapperPlayServerSetPassengers.class, packets.get(3));
        assertEquals(vehicle, passenger.vehicle().orElseThrow());

        assertThrows(IllegalArgumentException.class, () -> passenger.addPassenger(vehicle));

        vehicle.despawn();
        assertEquals(List.of(passenger), vehicle.passengers());
        assertEquals(vehicle, passenger.vehicle().orElseThrow());

        vehicle.spawn(new Location(1, 64, 1, 0, 0));
        assertInstanceOf(WrapperPlayServerSetPassengers.class, packets.get(packets.size() - 1));

        vehicle.remove();
        assertTrue(vehicle.passengers().isEmpty());
        assertTrue(passenger.vehicle().isEmpty());
    }

    @Test
    void resolvesBuilderMetadataFromServerVersionAndEntityType() {
        EntityType pig = namedType("pig");
        VirtualEntity entity = VirtualEntities.create(new AtomicEntityIdProvider(400))
                .entity(pig)
                .metadata(ServerVersion.V_1_21_10)
                .build();

        assertEquals("1.21.9", entity.metadata().schema().version());
        assertEquals("Pig", entity.metadata().schema().entityName());
    }

    @Test
    void sendsStateChangesImmediatelyAndValidatesPassengerOwnership() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(500));
        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualEntity vehicle = manager.entity(testType()).build()
                .addViewer(VirtualViewer.of(UUID.randomUUID(), packets::add))
                .spawn(new Location(0, 64, 0, 0, 0));
        VirtualEntity passenger = manager.entity(testType()).build();
        ItemStack helmet = mock(ItemStack.class);
        Attribute maxHealth = mock(Attribute.class);
        when(maxHealth.getDefaultValue()).thenReturn(20.0);

        vehicle.setEquipment(EquipmentSlot.HELMET, helmet);
        assertInstanceOf(WrapperPlayServerEntityEquipment.class, packets.get(packets.size() - 1));
        vehicle.clearEquipment(EquipmentSlot.HELMET);
        WrapperPlayServerEntityEquipment clearedEquipment = assertInstanceOf(
                WrapperPlayServerEntityEquipment.class,
                packets.get(packets.size() - 1)
        );
        assertEquals(ItemStack.EMPTY, clearedEquipment.getEquipment().get(0).getItem());

        vehicle.setAttribute(maxHealth, 40.0);
        vehicle.resetAttribute(maxHealth);
        WrapperPlayServerUpdateAttributes resetAttributes = assertInstanceOf(
                WrapperPlayServerUpdateAttributes.class,
                packets.get(packets.size() - 1)
        );
        assertEquals(20.0, resetAttributes.getProperties().get(0).getValue());

        vehicle.addPassenger(passenger);
        WrapperPlayServerSetPassengers mounted = assertInstanceOf(
                WrapperPlayServerSetPassengers.class,
                packets.get(packets.size() - 1)
        );
        assertArrayEquals(new int[]{passenger.entityId()}, mounted.getPassengers());
        vehicle.removePassenger(passenger);
        WrapperPlayServerSetPassengers unmounted = assertInstanceOf(
                WrapperPlayServerSetPassengers.class,
                packets.get(packets.size() - 1)
        );
        assertArrayEquals(new int[0], unmounted.getPassengers());

        VirtualEntity foreignPassenger = VirtualEntities.create(new AtomicEntityIdProvider(600))
                .entity(testType())
                .build();
        assertThrows(IllegalArgumentException.class, () -> vehicle.addPassenger(foreignPassenger));
    }

    private static EntityType namedType(String name) {
        return new EntityType() {
            @Override
            public boolean isInstanceOf(EntityType parent) {
                return this == parent;
            }

            @Override
            public Optional<EntityType> getParent() {
                return Optional.empty();
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
