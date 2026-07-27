package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.buffer.ByteBufAllocationOperator;
import com.github.retrooper.packetevents.netty.buffer.ByteBufOperator;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.SAME_THREAD)
class AllEntitySpawnWireTest {
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

    @TestFactory
    Stream<DynamicNode> writesEveryRegisteredEntitySpawnForEverySupportedServerVersion() {
        List<VersionMatrix> matrix = Arrays.stream(ServerVersion.values())
                .filter(version -> version != ServerVersion.ERROR)
                .filter(version -> version.isNewerThanOrEquals(ServerVersion.V_1_9_4))
                .map(version -> new VersionMatrix(
                        version,
                        EntityTypes.values().stream()
                                .filter(type -> type.getId(version.toClientVersion()) >= 0)
                                .toList()
                ))
                .toList();
        int cases = matrix.stream().mapToInt(version -> version.types().size()).sum();
        System.out.printf("All-entity spawn wire matrix: serverVersions=%d cases=%d%n", matrix.size(), cases);

        return matrix.stream().map(entry -> DynamicContainer.dynamicContainer(
                entry.version().getReleaseName(),
                entry.types().stream().map(type -> DynamicTest.dynamicTest(
                        type.getName().toString(),
                        () -> writeSpawn(entry.version(), type)
                ))
        ));
    }

    private static void writeSpawn(ServerVersion version, EntityType type) {
        when(serverManager.getVersion()).thenReturn(version);
        ClientVersion clientVersion = version.toClientVersion();
        List<PacketWrapper<?>> packets = new ArrayList<>();
        try (VirtualEntityManager manager = VirtualEntities.create()) {
            VirtualEntity entity = type == EntityTypes.PLAYER
                    ? manager.player(new UserProfile(UUID.randomUUID(), "MatrixPlayer"))
                            .metadata(version)
                            .build()
                    : manager.entity(type)
                            .metadata(version)
                            .build();
            entity.addViewer(VirtualViewer.of(UUID.randomUUID(), clientVersion, packets::add));
            entity.spawn(new Location(1.25, 64, -2.5, 45, 15));

            assertFalse(packets.isEmpty(), () -> version + ":" + type.getName() + " emitted no spawn packets");
            for (PacketWrapper<?> packet : packets) {
                writePacket(version, clientVersion, type, packet);
            }
        }
    }

    private static void writePacket(
            ServerVersion serverVersion,
            ClientVersion clientVersion,
            EntityType type,
            PacketWrapper<?> packet
    ) {
        ByteBufAllocationOperator allocation = PacketEvents.getAPI()
                .getNettyManager()
                .getByteBufAllocationOperator();
        ByteBufOperator buffers = PacketEvents.getAPI().getNettyManager().getByteBufOperator();
        Object buffer = allocation.buffer();
        try {
            packet.setServerVersion(serverVersion);
            packet.setClientVersion(clientVersion);
            assertTrue(packet.getPacketId() >= 0, () ->
                    serverVersion + ":" + type.getName() + " has no packet ID for "
                            + packet.getClass().getSimpleName());
            packet.setBuffer(buffer);
            packet.write();
            assertTrue(buffers.writerIndex(buffer) > 0, () ->
                    serverVersion + ":" + type.getName() + " wrote an empty "
                            + packet.getClass().getSimpleName());
        } finally {
            buffers.release(buffer);
        }
    }

    private record VersionMatrix(ServerVersion version, List<EntityType> types) {
    }
}
