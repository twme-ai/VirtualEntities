package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** A packet recipient that can view a virtual entity. */
public interface VirtualViewer {
    UUID id();

    void send(PacketWrapper<?> packet);

    static VirtualViewer of(User user) {
        Objects.requireNonNull(user, "user");
        return of(user.getUUID(), user::sendPacket);
    }

    static VirtualViewer of(UUID id, Consumer<PacketWrapper<?>> sender) {
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
        };
    }
}
