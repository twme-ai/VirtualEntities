package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.StaticEntityType;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualAudienceTrackerTest {
    @BeforeAll
    static void initializePacketEvents() {
        PacketEventsTestSupport.initialize();
    }

    @AfterAll
    static void clearPacketEvents() {
        PacketEventsTestSupport.clear();
    }

    @Test
    void reconcilesVisibilityDisconnectsAndTransportReplacement() {
        VirtualEntity entity = VirtualEntities.create(new AtomicEntityIdProvider(1200))
                .entity(testType())
                .build()
                .spawn(new Location(0, 64, 0, 0, 0));
        Candidate first = candidate(true);
        Candidate second = candidate(true);
        VirtualAudienceTracker<Candidate> tracker = VirtualAudienceTracker.of(
                entity,
                candidate -> VirtualViewer.of(candidate.id(), candidate.packets()::add),
                Candidate::visible
        );

        assertTrue(tracker.update(first));
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, first.packets().get(0));
        tracker.update(first);
        assertEquals(1, first.packets().size());

        Candidate hiddenFirst = new Candidate(first.id(), first.packets(), false);
        assertFalse(tracker.update(hiddenFirst));
        assertInstanceOf(WrapperPlayServerDestroyEntities.class, first.packets().get(1));

        tracker.reconcile(List.of(first, second));
        assertEquals(2, tracker.trackedViewerIds().size());
        assertInstanceOf(WrapperPlayServerSpawnEntity.class, second.packets().get(0));

        tracker.reconcile(List.of(first));
        assertEquals(1, tracker.trackedViewerIds().size());
        assertInstanceOf(WrapperPlayServerDestroyEntities.class, second.packets().get(1));

        tracker.close();
        assertTrue(tracker.isClosed());
        assertTrue(tracker.trackedViewerIds().isEmpty());
        assertThrows(IllegalStateException.class, () -> tracker.update(first));
    }

    @Test
    void preservesViewerMembershipOwnedByTheCaller() {
        UUID viewerId = UUID.randomUUID();
        List<PacketWrapper<?>> packets = new ArrayList<>();
        VirtualEntity entity = VirtualEntities.create(new AtomicEntityIdProvider(1250))
                .entity(testType())
                .build()
                .addViewer(VirtualViewer.of(viewerId, packets::add))
                .spawn(new Location(0, 64, 0, 0, 0));
        Candidate candidate = new Candidate(viewerId, new ArrayList<>(), true);
        VirtualAudienceTracker<Candidate> tracker = VirtualAudienceTracker.of(
                entity,
                value -> VirtualViewer.of(value.id(), value.packets()::add),
                Candidate::visible
        );

        assertTrue(tracker.update(candidate));
        tracker.close();

        assertTrue(entity.hasViewer(viewerId));
        assertEquals(1, packets.size());
        assertTrue(candidate.packets().isEmpty());
    }

    private static Candidate candidate(boolean visible) {
        return new Candidate(UUID.randomUUID(), new ArrayList<>(), visible);
    }

    private static EntityType testType() {
        return new StaticEntityType(null, null);
    }

    private record Candidate(UUID id, List<PacketWrapper<?>> packets, boolean visible) {
    }
}
