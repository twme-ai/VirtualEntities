package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.settings.PacketEventsSettings;
import com.github.retrooper.packetevents.util.LogManager;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class PacketEventsTestSupport {
    private PacketEventsTestSupport() {
    }

    public static ServerManager initialize() {
        PacketEventsAPI<?> api = mock(PacketEventsAPI.class);
        ServerManager serverManager = mock(ServerManager.class);
        when(api.getServerManager()).thenReturn(serverManager);
        when(api.getNettyManager()).thenReturn(new NettyManagerImpl());
        when(api.getSettings()).thenReturn(new PacketEventsSettings());
        // PacketEvents 2.14.0 touches the log manager while parsing built-in item
        // component data, so the mocked API must supply one.
        when(api.getLogManager()).thenReturn(mock(LogManager.class));
        when(serverManager.getVersion()).thenReturn(ServerVersion.V_1_21_11);
        PacketEvents.setAPI(api);
        return serverManager;
    }

    public static void clear() {
        PacketEvents.setAPI(null);
    }
}
