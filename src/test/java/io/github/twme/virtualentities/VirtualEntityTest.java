package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.attribute.Attribute;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
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
