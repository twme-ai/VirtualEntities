package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** A packet recipient that can view a virtual entity. */
public interface VirtualViewer {
    UUID id();

    void send(PacketWrapper<?> packet);

    /** Returns the protocol version used to encode packets for this viewer. */
    default ClientVersion clientVersion() {
        return PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
    }

    static VirtualViewer of(User user) {
        Objects.requireNonNull(user, "user");
        return of(user.getUUID(), user.getClientVersion(), user::sendPacket);
    }

    static VirtualViewer of(UUID id, Consumer<PacketWrapper<?>> sender) {
        return create(id, null, sender);
    }

    /** Creates a custom viewer with an explicit client protocol version. */
    static VirtualViewer of(UUID id, ClientVersion clientVersion, Consumer<PacketWrapper<?>> sender) {
        return create(id, Objects.requireNonNull(clientVersion, "clientVersion"), sender);
    }

    private static VirtualViewer create(
            UUID id,
            ClientVersion clientVersion,
            Consumer<PacketWrapper<?>> sender
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sender, "sender");
        return new VirtualViewer() {
            @Override
            public UUID id() {
                return id;
            }

            @Override
            public void send(PacketWrapper<?> packet) {
                sender.accept(packet);
            }

            @Override
            public ClientVersion clientVersion() {
                return clientVersion != null ? clientVersion : VirtualViewer.super.clientVersion();
            }
        };
    }
}
