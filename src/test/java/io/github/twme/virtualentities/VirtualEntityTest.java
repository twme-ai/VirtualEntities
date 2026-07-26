package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.StaticEntityType;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VirtualEntityTest {
    @BeforeAll
    static void initializePacketEvents() {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        ServerManager serverManager = mock(ServerManager.class);
        when(api.getServerManager()).thenReturn(serverManager);
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_21_11);
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
}
