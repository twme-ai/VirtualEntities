package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.attribute.Attribute;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.entity.type.StaticEntityType;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.resources.ResourceLocation;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRelativeMoveAndRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfo;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.util.Vector3d;
import io.github.twme.virtualentities.metadata.GeneratedEntityMetadataKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VirtualEntityTest {
    private static ServerManager serverManager;

    @BeforeAll
    static void initializePacketEvents() {
        serverManager = PacketEventsTestSupport.initialize();
    }

    @AfterAll
    static void clearPacketEvents() {
        PacketEventsTestSupport.clear();
    }

    private static EntityType testType() {
        return namedType("test");
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
        assertInstanceOf(WrapperPlayServerEntityPositionSync.class, packets.get(1));
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
        ItemStack helmet = ItemStack.EMPTY;
        Attribute maxHealth = mock(Attribute.class);

        vehicle.setEquipment(EquipmentSlot.HELMET, helmet)
                .setAttribute(maxHealth, 40)
                .addPassenger(passenger);

        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualViewer viewer = VirtualViewer.of(UUID.randomUUID(), packets::add);
        passenger.addViewer(viewer).spawn(new Location(0, 65, 0, 0, 0));
        packets.clear();
        vehicle.addViewer(viewer);
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
    void replaysPassengerStateForLateAndAsymmetricViewers() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(350));
        VirtualEntity vehicle = manager.entity(testType()).build();
        VirtualEntity passenger = manager.entity(testType()).build();
        UUID sharedId = UUID.randomUUID();
        List<PacketWrapper<?>> sharedPackets = new ArrayList<>();
        VirtualViewer sharedViewer = VirtualViewer.of(sharedId, sharedPackets::add);
        UUID vehicleOnlyId = UUID.randomUUID();
        List<PacketWrapper<?>> vehicleOnlyPackets = new ArrayList<>();
        VirtualViewer vehicleOnly = VirtualViewer.of(vehicleOnlyId, vehicleOnlyPackets::add);

        vehicle.addViewer(sharedViewer).addViewer(vehicleOnly).spawn(new Location(0, 64, 0, 0, 0));
        passenger.spawn(new Location(0, 65, 0, 0, 0));
        vehicle.addPassenger(passenger);

        WrapperPlayServerSetPassengers vehicleOnlyState = assertInstanceOf(
                WrapperPlayServerSetPassengers.class,
                vehicleOnlyPackets.get(vehicleOnlyPackets.size() - 1)
        );
        assertArrayEquals(new int[0], vehicleOnlyState.getPassengers());

        sharedPackets.clear();
        passenger.addViewer(sharedViewer);
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, sharedPackets.get(0));
        WrapperPlayServerSetPassengers mounted = assertInstanceOf(
                WrapperPlayServerSetPassengers.class,
                sharedPackets.get(sharedPackets.size() - 1)
        );
        assertEquals(vehicle.entityId(), mounted.getEntityId());
        assertArrayEquals(new int[]{passenger.entityId()}, mounted.getPassengers());

        sharedPackets.clear();
        passenger.removeViewer(sharedId);
        WrapperPlayServerSetPassengers unmounted = assertInstanceOf(
                WrapperPlayServerSetPassengers.class,
                sharedPackets.get(0)
        );
        assertArrayEquals(new int[0], unmounted.getPassengers());
        assertInstanceOf(WrapperPlayServerDestroyEntities.class, sharedPackets.get(1));

        sharedPackets.clear();
        passenger.addViewer(sharedViewer);
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, sharedPackets.get(0));
        assertArrayEquals(
                new int[]{passenger.entityId()},
                assertInstanceOf(
                        WrapperPlayServerSetPassengers.class,
                        sharedPackets.get(sharedPackets.size() - 1)
                ).getPassengers()
        );

        sharedPackets.clear();
        passenger.despawn();
        assertArrayEquals(
                new int[0],
                assertInstanceOf(WrapperPlayServerSetPassengers.class, sharedPackets.get(0)).getPassengers()
        );
        passenger.spawn(new Location(0, 65, 0, 0, 0));
        assertArrayEquals(
                new int[]{passenger.entityId()},
                assertInstanceOf(
                        WrapperPlayServerSetPassengers.class,
                        sharedPackets.get(sharedPackets.size() - 1)
                ).getPassengers()
        );
    }

    @Test
    void excludesUnsupportedPassengerTypesFromViewerState() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(375));
        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualViewer legacy = VirtualViewer.of(
                UUID.randomUUID(), ClientVersion.V_1_19_3, packets::add);
        VirtualEntity vehicle = manager.entity(EntityTypes.PIG).build()
                .addViewer(legacy).spawn(new Location(0, 64, 0, 0, 0));
        VirtualEntity unsupportedPassenger = manager.entity(EntityTypes.TEXT_DISPLAY).build()
                .addViewer(legacy).spawn(new Location(0, 65, 0, 0, 0));
        packets.clear();

        vehicle.addPassenger(unsupportedPassenger);

        assertFalse(unsupportedPassenger.hasViewer(legacy.id()));
        assertArrayEquals(
                new int[0],
                assertInstanceOf(WrapperPlayServerSetPassengers.class, packets.get(0)).getPassengers()
        );
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
        VirtualViewer viewer = VirtualViewer.of(UUID.randomUUID(), packets::add);
        VirtualEntity vehicle = manager.entity(testType()).build()
                .addViewer(viewer)
                .spawn(new Location(0, 64, 0, 0, 0));
        VirtualEntity passenger = manager.entity(testType()).build()
                .addViewer(viewer)
                .spawn(new Location(0, 65, 0, 0, 0));
        ItemStack helmet = ItemStack.EMPTY;
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

    @Test
    void managesModernVirtualPlayerProfileAndTabState() {
        UUID profileId = UUID.randomUUID();
        List<TextureProperty> textures = new ArrayList<>();
        textures.add(new TextureProperty("textures", "first", "signature"));
        UserProfile profile = new UserProfile(profileId, "Guide", textures);
        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualEntity player = VirtualEntities.create(new AtomicEntityIdProvider(700))
                .player(profile)
                .build()
                .setGameMode(GameMode.CREATIVE)
                .setLatency(42)
                .addViewer(VirtualViewer.of(UUID.randomUUID(), packets::add))
                .spawn(new Location(0, 64, 0, 10, 20));
        textures.clear();

        WrapperPlayServerPlayerInfoUpdate addInfo = assertInstanceOf(
                WrapperPlayServerPlayerInfoUpdate.class,
                packets.get(0)
        );
        assertTrue(addInfo.getActions().contains(WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER));
        assertEquals(GameMode.CREATIVE, addInfo.getEntries().get(0).getGameMode());
        assertEquals(42, addInfo.getEntries().get(0).getLatency());
        assertEquals(1, player.playerProfile().orElseThrow().getTextureProperties().size());
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, packets.get(1));
        assertEquals(4, packets.size());

        player.setListed(false);
        WrapperPlayServerPlayerInfoUpdate listedUpdate = assertInstanceOf(
                WrapperPlayServerPlayerInfoUpdate.class,
                packets.get(packets.size() - 1)
        );
        assertEquals(false, listedUpdate.getEntries().get(0).isListed());

        player.setPlayerProfile(new UserProfile(
                profileId,
                "GuideTwo",
                List.of(new TextureProperty("textures", "second", null))
        ));
        int refreshStart = packets.size() - 6;
        assertInstanceOf(WrapperPlayServerDestroyEntities.class, packets.get(refreshStart));
        assertInstanceOf(WrapperPlayServerPlayerInfoRemove.class, packets.get(refreshStart + 1));
        assertInstanceOf(WrapperPlayServerPlayerInfoUpdate.class, packets.get(refreshStart + 2));
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, packets.get(refreshStart + 3));
        assertEquals("GuideTwo", player.playerProfile().orElseThrow().getName());

        player.removeViewer(player.viewers().iterator().next().id());
        assertInstanceOf(WrapperPlayServerDestroyEntities.class, packets.get(packets.size() - 2));
        assertInstanceOf(WrapperPlayServerPlayerInfoRemove.class, packets.get(packets.size() - 1));
    }

    @Test
    void handlesLegacyAndHybridPlayerProtocolTransitions() {
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_19_2);
        try {
            List<PacketWrapper<?>> packets = new ArrayList<>();
            VirtualEntity player = VirtualEntities.create(new AtomicEntityIdProvider(800))
                    .player(new UserProfile(UUID.randomUUID(), "LegacyGuide"))
                    .build()
                    .addViewer(VirtualViewer.of(UUID.randomUUID(), packets::add))
                    .spawn(new Location(0, 64, 0, 0, 0));

            assertInstanceOf(WrapperPlayServerPlayerInfo.class, packets.get(0));
            assertInstanceOf(WrapperPlayServerSpawnPlayer.class, packets.get(1));
            assertEquals(4, packets.size());

            player.setListed(false);
            WrapperPlayServerPlayerInfo removeInfo = assertInstanceOf(
                    WrapperPlayServerPlayerInfo.class,
                    packets.get(packets.size() - 1)
            );
            assertEquals(WrapperPlayServerPlayerInfo.Action.REMOVE_PLAYER, removeInfo.getAction());

            when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_20_1);
            packets.clear();
            VirtualEntities.create(new AtomicEntityIdProvider(900))
                    .player(new UserProfile(UUID.randomUUID(), "HybridGuide"))
                    .build()
                    .addViewer(VirtualViewer.of(UUID.randomUUID(), packets::add))
                    .spawn(new Location(0, 64, 0, 0, 0));
            assertInstanceOf(WrapperPlayServerPlayerInfoUpdate.class, packets.get(0));
            assertInstanceOf(WrapperPlayServerSpawnPlayer.class, packets.get(1));
        } finally {
            when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_21_11);
        }
    }

    @Test
    void keepsPassengerRelationshipConsistentDuringConcurrentReassignment() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(1000));
            VirtualEntity firstVehicle = manager.entity(testType()).build();
            VirtualEntity secondVehicle = manager.entity(testType()).build();
            VirtualEntity passenger = manager.entity(testType()).build();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < 1_000; iteration++) {
                        firstVehicle.addPassenger(passenger);
                    }
                    return null;
                });
                var second = executor.submit(() -> {
                    start.await();
                    for (int iteration = 0; iteration < 1_000; iteration++) {
                        secondVehicle.addPassenger(passenger);
                    }
                    return null;
                });
                start.countDown();
                first.get();
                second.get();
            } finally {
                executor.shutdownNow();
            }

            VirtualEntity finalVehicle = passenger.vehicle().orElseThrow();
            assertTrue(finalVehicle == firstVehicle || finalVehicle == secondVehicle);
            assertEquals(finalVehicle == firstVehicle, firstVehicle.passengers().contains(passenger));
            assertEquals(finalVehicle == secondVehicle, secondVehicle.passengers().contains(passenger));
        });
    }

    @Test
    void supportsConcurrentPassengerViewerReconciliation() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(1125));
            VirtualViewer viewer = VirtualViewer.of(UUID.randomUUID(), packet -> { });
            VirtualEntity vehicle = manager.entity(testType()).build()
                    .addViewer(viewer).spawn(new Location(0, 64, 0, 0, 0));
            VirtualEntity passenger = manager.entity(testType()).build()
                    .spawn(new Location(0, 65, 0, 0, 0));
            vehicle.addPassenger(passenger);

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                var passengerUpdates = executor.submit(() -> {
                    for (int index = 0; index < 200; index++) {
                        passenger.addViewer(viewer);
                        passenger.removeViewer(viewer.id());
                    }
                });
                var vehicleUpdates = executor.submit(() -> {
                    for (int index = 0; index < 200; index++) {
                        vehicle.removeViewer(viewer.id());
                        vehicle.addViewer(viewer);
                    }
                });
                passengerUpdates.get();
                vehicleUpdates.get();
            } finally {
                executor.shutdownNow();
            }

            assertEquals(vehicle, passenger.vehicle().orElseThrow());
            assertEquals(List.of(passenger), vehicle.passengers());
        });
    }

    @Test
    void linearizesConcurrentUpdatesForTheSameEntity() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            CountDownLatch firstDeliveryStarted = new CountDownLatch(1);
            CountDownLatch releaseFirstDelivery = new CountDownLatch(1);
            CountDownLatch secondUpdateStarted = new CountDownLatch(1);
            VirtualEntity entity = VirtualEntities.create(new AtomicEntityIdProvider(1150))
                    .entity(testType())
                    .build()
                    .addViewer(VirtualViewer.of(UUID.randomUUID(), packet -> {
                        if (packet instanceof WrapperPlayServerEntityPositionSync) {
                            firstDeliveryStarted.countDown();
                            awaitTransportRelease(releaseFirstDelivery);
                        }
                    }))
                    .spawn(new Location(0, 64, 0, 0, 0));

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> entity.teleport(new Location(1, 64, 0, 0, 0)));
                assertTrue(firstDeliveryStarted.await(1, TimeUnit.SECONDS));
                var second = executor.submit(() -> {
                    secondUpdateStarted.countDown();
                    return entity.teleport(new Location(2, 64, 0, 0, 0));
                });
                assertTrue(secondUpdateStarted.await(1, TimeUnit.SECONDS));

                assertFalse(second.isDone());
                assertEquals(1, entity.location().getX());

                releaseFirstDelivery.countDown();
                first.get(1, TimeUnit.SECONDS);
                second.get(1, TimeUnit.SECONDS);
                assertEquals(2, entity.location().getX());
            } finally {
                releaseFirstDelivery.countDown();
                executor.shutdownNow();
            }
        });
    }

    @Test
    void slowTransportDoesNotBlockAnUnrelatedEntity() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(1175));
            CountDownLatch deliveryStarted = new CountDownLatch(1);
            CountDownLatch releaseDelivery = new CountDownLatch(1);
            VirtualEntity slowEntity = manager.entity(testType()).build()
                    .addViewer(VirtualViewer.of(UUID.randomUUID(), packet -> {
                        if (packet instanceof WrapperPlayServerEntityPositionSync) {
                            deliveryStarted.countDown();
                            awaitTransportRelease(releaseDelivery);
                        }
                    }))
                    .spawn(new Location(0, 64, 0, 0, 0));
            VirtualEntity independentEntity = manager.entity(testType()).build()
                    .addViewer(VirtualViewer.of(UUID.randomUUID(), packet -> { }))
                    .spawn(new Location(0, 64, 0, 0, 0));

            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                var slowUpdate = executor.submit(
                        () -> slowEntity.teleport(new Location(1, 64, 0, 0, 0)));
                assertTrue(deliveryStarted.await(1, TimeUnit.SECONDS));

                executor.submit(() -> independentEntity.teleport(new Location(2, 64, 0, 0, 0)))
                        .get(1, TimeUnit.SECONDS);
                assertEquals(2, independentEntity.location().getX());

                releaseDelivery.countDown();
                slowUpdate.get(1, TimeUnit.SECONDS);
            } finally {
                releaseDelivery.countDown();
                executor.shutdownNow();
            }
        });
    }

    @Test
    void selectsRelativeMovementOrTeleportFromAbsoluteUpdates() {
        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualEntity entity = VirtualEntities.create(new AtomicEntityIdProvider(1100))
                .entity(testType())
                .build()
                .addViewer(VirtualViewer.of(UUID.randomUUID(), packets::add))
                .spawn(new Location(0, 64, 0, 0, 0));
        packets.clear();

        entity.updateLocation(new Location(1, 64.5, -1, 0, 0), true);
        assertInstanceOf(WrapperPlayServerEntityRelativeMove.class, packets.get(0));
        assertEquals(1, entity.location().getX());

        entity.updateLocation(new Location(2, 65, -2, 90, 10), false);
        assertInstanceOf(WrapperPlayServerEntityRelativeMoveAndRotation.class, packets.get(1));

        entity.updateLocation(new Location(2, 65, -2, 45, 5), false);
        assertInstanceOf(WrapperPlayServerEntityRotation.class, packets.get(2));

        entity.updateLocation(new Location(10, 65, -2, 45, 5), true);
        assertInstanceOf(WrapperPlayServerEntityPositionSync.class, packets.get(3));
        assertEquals(10, entity.location().getX());

        assertThrows(IllegalArgumentException.class, () -> entity.move(8, 0, 0, true));
        assertThrows(IllegalArgumentException.class, () -> entity.move(Double.NaN, 0, 0, true));
    }

    @Test
    void updatesOnlyTheRetainedLocationSnapshotForLateViewers() {
        VirtualEntity unspawned = VirtualEntities.create(new AtomicEntityIdProvider(1_149))
                .entity(testType())
                .build();
        assertThrows(
                IllegalStateException.class,
                () -> unspawned.setLocationSnapshot(new Location(1, 2, 3, 0, 0))
        );
        unspawned.remove();

        List<PacketWrapper<?>> currentPackets = new ArrayList<>();
        VirtualEntity entity = VirtualEntities.create(new AtomicEntityIdProvider(1_150))
                .entity(testType())
                .build()
                .addViewer(VirtualViewer.of(UUID.randomUUID(), currentPackets::add))
                .spawn(new Location(0, 64, 0, 0, 0));
        currentPackets.clear();
        Location replacement = new Location(8, 70, -3, 45, 10);

        entity.setLocationSnapshot(replacement);

        assertTrue(currentPackets.isEmpty());
        assertEquals(8, entity.location().getX());
        replacement.setPosition(new Vector3d(100, 100, 100));
        replacement.setYaw(0);
        assertEquals(new Vector3d(8, 70, -3), entity.location().getPosition());
        assertEquals(45, entity.location().getYaw());

        List<PacketWrapper<?>> latePackets = new ArrayList<>();
        entity.addViewer(VirtualViewer.of(UUID.randomUUID(), latePackets::add));
        WrapperPlayServerSpawnEntity spawn = assertInstanceOf(WrapperPlayServerSpawnEntity.class, latePackets.get(0));
        assertEquals(new Vector3d(8, 70, -3), spawn.getPosition());
        assertEquals(45, spawn.getYaw());

        entity.remove();
        assertThrows(
                IllegalStateException.class,
                () -> entity.setLocationSnapshot(new Location(1, 2, 3, 0, 0))
        );
    }

    @Test
    void filtersViewersByEntityTypeProtocolSupport() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(1_175));
        ItemStack helmet = ItemStack.EMPTY;
        Attribute attribute = mock(Attribute.class);
        VirtualEntity passenger = manager.entity(EntityTypes.PIG).build();
        VirtualEntity textDisplay = manager.entity(EntityTypes.TEXT_DISPLAY)
                .metadata()
                .build()
                .setEquipment(EquipmentSlot.HELMET, helmet)
                .setAttribute(attribute, 1)
                .addPassenger(passenger);
        textDisplay.metadata().set(GeneratedEntityMetadataKeys.TextDisplay.LINE_WIDTH, 120);
        UUID legacyId = UUID.randomUUID();
        List<PacketWrapper<?>> legacyPackets = new ArrayList<>();
        UUID modernId = UUID.randomUUID();
        List<PacketWrapper<?>> modernPackets = new ArrayList<>();
        VirtualViewer legacy = VirtualViewer.of(legacyId, ClientVersion.V_1_19_3, legacyPackets::add);
        VirtualViewer modern = VirtualViewer.of(modernId, ClientVersion.V_1_19_4, modernPackets::add);
        User legacyUser = mock(User.class);
        UUID legacyUserId = UUID.randomUUID();
        when(legacyUser.getUUID()).thenReturn(legacyUserId);
        when(legacyUser.getClientVersion()).thenReturn(ClientVersion.V_1_19_3);
        VirtualEntity happyGhast = manager.entity(EntityTypes.HAPPY_GHAST).build();
        VirtualEntity unregistered = manager.entity(new StaticEntityType(null, null)).build();

        assertFalse(textDisplay.supports(ClientVersion.V_1_19_3));
        assertTrue(textDisplay.supports(ClientVersion.V_1_19_4));
        assertFalse(passenger.supports(ClientVersion.V_1_8));
        assertFalse(textDisplay.supports(legacy));
        assertTrue(textDisplay.supports(modern));
        assertFalse(happyGhast.supports(ClientVersion.V_1_21_5));
        assertTrue(happyGhast.supports(ClientVersion.V_1_21_6));
        assertFalse(unregistered.supports(ClientVersion.V_1_21_11));

        passenger.addViewer(modern).spawn(new Location(0, 65, 0, 0, 0));
        modernPackets.clear();
        textDisplay.addViewer(legacy).addViewer(legacyUser).addViewer(modern)
                .spawn(new Location(0, 64, 0, 0, 0));

        assertFalse(textDisplay.hasViewer(legacyId));
        assertFalse(textDisplay.hasViewer(legacyUserId));
        assertTrue(textDisplay.hasViewer(modernId));
        assertTrue(legacyPackets.isEmpty());
        verify(legacyUser, never()).sendPacket(any(PacketWrapper.class));
        assertEquals(5, modernPackets.size());
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, modernPackets.get(0));
        assertInstanceOf(WrapperPlayServerEntityMetadata.class, modernPackets.get(1));
        assertInstanceOf(WrapperPlayServerEntityEquipment.class, modernPackets.get(2));
        assertInstanceOf(WrapperPlayServerUpdateAttributes.class, modernPackets.get(3));
        assertInstanceOf(WrapperPlayServerSetPassengers.class, modernPackets.get(4));
        textDisplay.syncMetadata();
        textDisplay.teleport(new Location(1, 64, 0, 0, 0));
        textDisplay.clearEquipment(EquipmentSlot.HELMET);
        textDisplay.resetAttribute(attribute);
        textDisplay.clearPassengers();
        textDisplay.despawn();
        assertTrue(legacyPackets.isEmpty());
        verify(legacyUser, never()).sendPacket(any(PacketWrapper.class));
        assertInstanceOf(WrapperPlayServerDestroyEntities.class, modernPackets.get(modernPackets.size() - 1));
    }

    @Test
    void validatesAndDispatchesInboundInteractions() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(1300));
        UUID actorId = UUID.randomUUID();
        User actor = mock(User.class);
        when(actor.getUUID()).thenReturn(actorId);
        VirtualEntity entity = manager.entity(testType())
                .build()
                .addViewer(VirtualViewer.of(actorId, packet -> { }))
                .spawn(new Location(0, 64, 0, 0, 0));
        List<VirtualEntityInteraction> received = new ArrayList<>();
        VirtualEntityInteraction.Subscription subscription = entity.onInteraction(received::add);

        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(
                entity.entityId(),
                WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT,
                new Vector3d(0.25, 1, 0.5),
                InteractionHand.MAIN_HAND,
                true
        );
        assertTrue(manager.handleInteraction(actor, packet).isEmpty());
        assertTrue(received.isEmpty());

        manager.interactionValidator(interaction -> true);
        VirtualEntityInteraction interaction = manager.handleInteraction(actor, packet).orElseThrow();
        assertEquals(VirtualEntityInteraction.Action.INTERACT_AT, interaction.action());
        assertEquals(InteractionHand.MAIN_HAND, interaction.hand().orElseThrow());
        assertEquals(0.25, interaction.target().orElseThrow().getX());
        assertTrue(interaction.sneaking());
        assertEquals(List.of(interaction), received);

        subscription.close();
        VirtualEntityInteraction attack = manager.handleInteraction(
                actor,
                new WrapperPlayClientAttack(entity.entityId())
        ).orElseThrow();
        assertEquals(VirtualEntityInteraction.Action.ATTACK, attack.action());
        assertTrue(attack.hand().isEmpty());
        assertEquals(1, received.size());

        User stranger = mock(User.class);
        when(stranger.getUUID()).thenReturn(UUID.randomUUID());
        assertTrue(manager.handleInteraction(stranger, packet).isEmpty());
        assertTrue(manager.handleInteraction(actor, new WrapperPlayClientAttack(999_999)).isEmpty());
    }

    @Test
    void rollsBackFailedViewerDeliveryAndAllowsNaturalRetry() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(1400));
        VirtualEntity entity = manager.entity(testType()).build().spawn(new Location(0, 64, 0, 0, 0));
        UUID viewerId = UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> entity.addViewer(VirtualViewer.of(viewerId, packet -> {
            throw new IllegalStateException("transport failed");
        })));
        assertFalse(entity.hasViewer(viewerId));

        List<PacketWrapper<?>> retryPackets = new ArrayList<>();
        entity.addViewer(VirtualViewer.of(viewerId, retryPackets::add));
        assertTrue(entity.hasViewer(viewerId));
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, retryPackets.get(0));

        retryPackets.clear();
        entity.resyncViewer(viewerId);
        assertInstanceOf(WrapperPlayServerDestroyEntities.class, retryPackets.get(0));
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, retryPackets.get(1));
    }

    @Test
    void isolatesSpawnFailuresPerViewer() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(1450));
        UUID failedId = UUID.randomUUID();
        UUID successfulId = UUID.randomUUID();
        List<PacketWrapper<?>> successfulPackets = new ArrayList<>();
        VirtualEntity entity = manager.entity(testType()).build()
                .addViewer(VirtualViewer.of(failedId, packet -> {
                    throw new IllegalStateException("transport failed");
                }))
                .addViewer(VirtualViewer.of(successfulId, successfulPackets::add));

        assertThrows(IllegalStateException.class, () -> entity.spawn(new Location(0, 64, 0, 0, 0)));
        assertTrue(entity.isSpawned());
        assertFalse(entity.hasViewer(failedId));
        assertTrue(entity.hasViewer(successfulId));
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, successfulPackets.get(0));
    }

    @Test
    void neverInvokesTransportWhileHoldingEntityMonitor() {
        AtomicReference<VirtualEntity> reference = new AtomicReference<>();
        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualViewer viewer = VirtualViewer.of(UUID.randomUUID(), packet -> {
            assertFalse(Thread.holdsLock(reference.get()));
            packets.add(packet);
        });
        VirtualEntity entity = VirtualEntities.create(new AtomicEntityIdProvider(1500))
                .entity(testType()).build();
        reference.set(entity);

        entity.addViewer(viewer).spawn(new Location(0, 64, 0, 0, 0));
        entity.teleport(new Location(1, 64, 0, 0, 0));
        entity.setEquipment(EquipmentSlot.HELMET, ItemStack.EMPTY);
        entity.despawn();

        assertFalse(packets.isEmpty());
    }

    @Test
    void validatesFiniteWireValuesAndInteractionAuthorization() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(1550));
        UUID actorId = UUID.randomUUID();
        User actor = mock(User.class);
        when(actor.getUUID()).thenReturn(actorId);
        VirtualEntity entity = manager.entity(testType()).build()
                .addViewer(VirtualViewer.of(actorId, packet -> { }))
                .spawn(new Location(0, 64, 0, 0, 0));

        assertThrows(IllegalArgumentException.class,
                () -> entity.teleport(new Location(Double.NaN, 64, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> entity.velocity(new Vector3d(0, Double.POSITIVE_INFINITY, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> entity.rotate(Float.NaN, 0, false));

        manager.interactionValidator(interaction -> false);
        assertTrue(manager.handleInteraction(actor, new WrapperPlayClientAttack(entity.entityId())).isEmpty());

        manager.interactionValidator(interaction -> true);
        WrapperPlayClientInteractEntity nonFinite = new WrapperPlayClientInteractEntity(
                entity.entityId(),
                WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT,
                new Vector3d(Double.NaN, 0, 0),
                InteractionHand.MAIN_HAND,
                false
        );
        assertTrue(manager.handleInteraction(actor, nonFinite).isEmpty());
    }

    @Test
    void managerCloseIsTerminal() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(1600));
        manager.entity(testType()).build();

        manager.close();
        manager.close();

        assertTrue(manager.isClosed());
        assertTrue(manager.entities().isEmpty());
        assertThrows(IllegalStateException.class, () -> manager.entity(testType()));
        assertThrows(IllegalStateException.class, () -> manager.bundle(() -> { }));
    }

    @Test
    void defaultManagersShareEntityIdAllocation() {
        VirtualEntity first = VirtualEntities.create().entity(testType()).build();
        VirtualEntity second = VirtualEntities.create().entity(testType()).build();

        assertTrue(first.entityId() != second.entityId());
    }

    @Test
    void releasesViewerTransportAfterLastMembership() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(1700));
        UUID viewerId = UUID.randomUUID();
        VirtualViewer viewer = VirtualViewer.of(viewerId, packet -> { });
        VirtualEntity first = manager.entity(testType()).build().addViewer(viewer);
        VirtualEntity second = manager.entity(testType()).build().addViewer(viewer);

        first.removeViewer(viewerId);
        manager.replaceViewer(VirtualViewer.of(viewerId, packet -> { }));
        second.removeViewer(viewerId);

        assertThrows(IllegalArgumentException.class,
                () -> manager.replaceViewer(VirtualViewer.of(viewerId, packet -> { })));
    }

    @Test
    void isolatesUpdateTransportFailuresAcrossViewers() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(1750));
        UUID failedId = UUID.randomUUID();
        UUID successfulId = UUID.randomUUID();
        AtomicInteger failedPackets = new AtomicInteger();
        List<PacketWrapper<?>> successfulPackets = new ArrayList<>();
        VirtualViewer failed = VirtualViewer.of(failedId, packet -> {
            if (failedPackets.incrementAndGet() > 1) {
                throw new IllegalStateException("transport failed");
            }
        });
        VirtualEntity entity = manager.entity(testType()).build()
                .addViewer(failed)
                .addViewer(VirtualViewer.of(successfulId, successfulPackets::add))
                .spawn(new Location(0, 64, 0, 0, 0));
        successfulPackets.clear();

        assertThrows(IllegalStateException.class,
                () -> entity.teleport(new Location(1, 64, 0, 0, 0)));

        assertFalse(entity.hasViewer(failedId));
        assertTrue(entity.hasViewer(successfulId));
        assertEquals(1, successfulPackets.size());
    }

    @Test
    void managerCloseCleansEveryEntityWhenATransportFails() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(1650));
        manager.entity(testType()).build()
                .addViewer(VirtualViewer.of(UUID.randomUUID(), packet -> {
                    if (packet instanceof WrapperPlayServerDestroyEntities) {
                        throw new IllegalStateException("disconnect during close");
                    }
                }))
                .spawn(new Location(0, 64, 0, 0, 0));
        manager.entity(testType()).build().spawn(new Location(1, 64, 0, 0, 0));

        assertThrows(IllegalStateException.class, manager::close);
        assertTrue(manager.isClosed());
        assertTrue(manager.entities().isEmpty());
    }

    private static void awaitTransportRelease(CountDownLatch release) {
        try {
            release.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Transport wait was interrupted", interrupted);
        }
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
