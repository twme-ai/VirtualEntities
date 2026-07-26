package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.twme.virtualentities.metadata.GeneratedEntityMetadataKeys;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualEntityManagerBundleTest {
    @BeforeAll
    static void initializePacketEvents() {
        PacketEventsTestSupport.initialize();
    }

    @AfterAll
    static void clearPacketEvents() {
        PacketEventsTestSupport.clear();
    }

    @Test
    void bundlesOrderedUpdatesAcrossEntitiesForSharedViewer() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(2_000));
        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualViewer viewer = viewer(UUID.randomUUID(), ClientVersion.V_1_21_11, packets);
        VirtualEntity first = textDisplay(manager, viewer, new Location(0, 64, 0, 0, 0));
        VirtualEntity second = textDisplay(manager, viewer, new Location(0, 64, 0, 0, 0));
        VirtualEntity root = textDisplay(manager, viewer, new Location(0, 64, 0, 0, 0));
        packets.clear();

        manager.bundle(() -> {
            first.metadata().set(GeneratedEntityMetadataKeys.Display.TRANSLATION, new Vector3f(1, 2, 3));
            first.syncMetadata();
            second.metadata().set(GeneratedEntityMetadataKeys.Display.TRANSLATION, new Vector3f(4, 5, 6));
            second.syncMetadata();
            root.teleport(new Location(8, 70, -2, 0, 0));
        });

        assertEquals(5, packets.size());
        assertInstanceOf(WrapperPlayServerBundle.class, packets.get(0));
        assertEquals(first.entityId(), metadataPacket(packets.get(1)).getEntityId());
        assertEquals(second.entityId(), metadataPacket(packets.get(2)).getEntityId());
        assertEquals(root.entityId(), positionSyncPacket(packets.get(3)).getId());
        assertInstanceOf(WrapperPlayServerBundle.class, packets.get(4));
    }

    @Test
    void sendsOnlyRelevantPacketsToDifferentViewerSets() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(2_100));
        UUID sharedId = UUID.randomUUID();
        List<PacketWrapper<?>> sharedPackets = new ArrayList<>();
        List<PacketWrapper<?>> firstPackets = new ArrayList<>();
        List<PacketWrapper<?>> secondPackets = new ArrayList<>();
        VirtualViewer shared = viewer(sharedId, ClientVersion.V_1_21_11, sharedPackets);
        VirtualEntity first = textDisplay(
                manager,
                shared,
                viewer(UUID.randomUUID(), ClientVersion.V_1_21_11, firstPackets),
                new Location(0, 64, 0, 0, 0)
        );
        VirtualEntity second = textDisplay(
                manager,
                viewer(sharedId, ClientVersion.V_1_21_11, sharedPackets),
                viewer(UUID.randomUUID(), ClientVersion.V_1_21_11, secondPackets),
                new Location(0, 64, 0, 0, 0)
        );
        sharedPackets.clear();
        firstPackets.clear();
        secondPackets.clear();

        manager.bundle(() -> {
            first.teleport(new Location(1, 64, 0, 0, 0));
            second.teleport(new Location(2, 64, 0, 0, 0));
        });

        assertEquals(List.of(first.entityId(), second.entityId()), teleportEntityIds(sharedPackets));
        assertEquals(List.of(first.entityId()), teleportEntityIds(firstPackets));
        assertEquals(List.of(second.entityId()), teleportEntityIds(secondPackets));
        assertDelimiterPair(sharedPackets);
        assertDelimiterPair(firstPackets);
        assertDelimiterPair(secondPackets);
    }

    @Test
    void coalescesNestedScopesAndFallsBackPerLegacyViewer() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(2_200));
        List<PacketWrapper<?>> modernPackets = new ArrayList<>();
        List<PacketWrapper<?>> legacyPackets = new ArrayList<>();
        VirtualEntity entity = manager.entity(EntityTypes.PIG)
                .metadata()
                .build()
                .addViewer(viewer(UUID.randomUUID(), ClientVersion.V_1_21_11, modernPackets))
                .addViewer(viewer(UUID.randomUUID(), ClientVersion.V_1_19_3, legacyPackets))
                .spawn(new Location(0, 64, 0, 0, 0));
        modernPackets.clear();
        legacyPackets.clear();

        manager.bundle(() -> {
            entity.metadata().set(GeneratedEntityMetadataKeys.Pig.BOOST_TIME, 120);
            entity.syncMetadata();
            manager.bundle(() -> entity.teleport(new Location(3, 64, 0, 0, 0)));
        });

        assertEquals(4, modernPackets.size());
        assertDelimiterPair(modernPackets);
        assertInstanceOf(WrapperPlayServerEntityMetadata.class, modernPackets.get(1));
        assertInstanceOf(WrapperPlayServerEntityPositionSync.class, modernPackets.get(2));
        assertEquals(2, legacyPackets.size());
        assertInstanceOf(WrapperPlayServerEntityMetadata.class, legacyPackets.get(0));
        assertInstanceOf(WrapperPlayServerEntityTeleport.class, legacyPackets.get(1));
    }

    @Test
    void flushesStateBeforeRethrowingCallbackFailure() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(2_300));
        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualEntity entity = textDisplay(
                manager,
                viewer(UUID.randomUUID(), ClientVersion.V_1_21_11, packets),
                new Location(0, 64, 0, 0, 0)
        );
        packets.clear();
        IllegalStateException expected = new IllegalStateException("update failed");

        IllegalStateException actual = assertThrows(IllegalStateException.class, () -> manager.bundle(() -> {
            entity.teleport(new Location(5, 66, 7, 0, 0));
            throw expected;
        }));

        assertSame(expected, actual);
        assertEquals(3, packets.size());
        assertDelimiterPair(packets);
        assertInstanceOf(WrapperPlayServerEntityPositionSync.class, packets.get(1));
        assertEquals(5, entity.location().getX());
    }

    @Test
    void replaysFinalBundledStateToLateViewer() {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(2_400));
        List<PacketWrapper<?>> initialPackets = new ArrayList<>();
        VirtualEntity entity = textDisplay(
                manager,
                viewer(UUID.randomUUID(), ClientVersion.V_1_21_11, initialPackets),
                new Location(0, 64, 0, 0, 0)
        );
        Vector3f translation = new Vector3f(2, 3, 4);

        manager.bundle(() -> {
            entity.metadata().set(GeneratedEntityMetadataKeys.Display.TRANSLATION, translation);
            entity.syncMetadata();
            entity.teleport(new Location(9, 70, 4, 0, 0));
        });

        List<PacketWrapper<?>> latePackets = new ArrayList<>();
        entity.addViewer(viewer(UUID.randomUUID(), ClientVersion.V_1_21_11, latePackets));

        WrapperPlayServerSpawnEntity spawn = assertInstanceOf(WrapperPlayServerSpawnEntity.class, latePackets.get(0));
        WrapperPlayServerEntityMetadata metadata = assertInstanceOf(
                WrapperPlayServerEntityMetadata.class,
                latePackets.get(1)
        );
        assertEquals(9, spawn.getPosition().getX());
        assertEquals(translation, metadata.getEntityMetadata().stream()
                .filter(value -> value.getIndex() == entity.metadata().schema().require("TRANSLATION").index())
                .findFirst()
                .orElseThrow()
                .getValue());
        assertTrue(latePackets.stream().noneMatch(WrapperPlayServerBundle.class::isInstance));
    }

    @Test
    void preventsConcurrentPacketsFromEnteringBundleDelimiters() throws Exception {
        VirtualEntityManager manager = VirtualEntities.create(new AtomicEntityIdProvider(2_500));
        List<PacketWrapper<?>> packets = new ArrayList<>();
        CountDownLatch bundleOpened = new CountDownLatch(1);
        CountDownLatch releaseBundle = new CountDownLatch(1);
        CountDownLatch concurrentUpdateStarted = new CountDownLatch(1);
        AtomicInteger delimiters = new AtomicInteger();
        VirtualViewer viewer = VirtualViewer.of(UUID.randomUUID(), ClientVersion.V_1_21_11, packet -> {
            packets.add(packet);
            if (packet instanceof WrapperPlayServerBundle && delimiters.incrementAndGet() == 1) {
                bundleOpened.countDown();
                try {
                    if (!releaseBundle.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out releasing bundle sender");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding bundle sender", exception);
                }
            }
        });
        VirtualEntity entity = textDisplay(manager, viewer, new Location(0, 64, 0, 0, 0));
        entity.metadata().set(GeneratedEntityMetadataKeys.TextDisplay.LINE_WIDTH, 80);
        packets.clear();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var bundled = executor.submit(() -> manager.bundle(entity::syncMetadata));
            assertTrue(bundleOpened.await(5, TimeUnit.SECONDS));
            var concurrent = executor.submit(() -> {
                concurrentUpdateStarted.countDown();
                entity.teleport(new Location(4, 64, 0, 0, 0));
            });
            assertTrue(concurrentUpdateStarted.await(5, TimeUnit.SECONDS));
            assertFalse(concurrent.isDone());
            releaseBundle.countDown();
            bundled.get(5, TimeUnit.SECONDS);
            concurrent.get(5, TimeUnit.SECONDS);
        } finally {
            releaseBundle.countDown();
            executor.shutdownNow();
        }

        assertEquals(4, packets.size());
        assertInstanceOf(WrapperPlayServerBundle.class, packets.get(0));
        assertInstanceOf(WrapperPlayServerEntityMetadata.class, packets.get(1));
        assertInstanceOf(WrapperPlayServerBundle.class, packets.get(2));
        assertInstanceOf(WrapperPlayServerEntityPositionSync.class, packets.get(3));
    }

    private static VirtualEntity textDisplay(
            VirtualEntityManager manager,
            VirtualViewer viewer,
            Location location
    ) {
        return textDisplay(manager, viewer, null, location);
    }

    private static VirtualEntity textDisplay(
            VirtualEntityManager manager,
            VirtualViewer firstViewer,
            VirtualViewer secondViewer,
            Location location
    ) {
        VirtualEntity entity = manager.entity(EntityTypes.TEXT_DISPLAY).metadata().build().addViewer(firstViewer);
        if (secondViewer != null) {
            entity.addViewer(secondViewer);
        }
        return entity.spawn(location);
    }

    private static VirtualViewer viewer(
            UUID id,
            ClientVersion version,
            List<PacketWrapper<?>> packets
    ) {
        return VirtualViewer.of(id, version, packets::add);
    }

    private static void assertDelimiterPair(List<PacketWrapper<?>> packets) {
        assertInstanceOf(WrapperPlayServerBundle.class, packets.get(0));
        assertInstanceOf(WrapperPlayServerBundle.class, packets.get(packets.size() - 1));
    }

    private static List<Integer> teleportEntityIds(List<PacketWrapper<?>> packets) {
        return packets.stream()
                .filter(WrapperPlayServerEntityPositionSync.class::isInstance)
                .map(WrapperPlayServerEntityPositionSync.class::cast)
                .map(WrapperPlayServerEntityPositionSync::getId)
                .toList();
    }

    private static WrapperPlayServerEntityMetadata metadataPacket(PacketWrapper<?> packet) {
        return assertInstanceOf(WrapperPlayServerEntityMetadata.class, packet);
    }

    private static WrapperPlayServerEntityPositionSync positionSyncPacket(PacketWrapper<?> packet) {
        return assertInstanceOf(WrapperPlayServerEntityPositionSync.class, packet);
    }
}
