package io.github.twme.virtualentities;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.util.Vector3d;

import java.util.Objects;
import java.util.Optional;

/** An inbound interaction that passed manager ownership, spawn-state, visibility, and configured validator checks. */
public record VirtualEntityInteraction(
        VirtualEntity entity,
        User actor,
        Action action,
        Optional<InteractionHand> hand,
        Optional<Vector3d> target,
        boolean sneaking
) {
    public VirtualEntityInteraction {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        hand = Objects.requireNonNull(hand, "hand");
        target = Objects.requireNonNull(target, "target");
    }

    public enum Action {
        INTERACT,
        INTERACT_AT,
        ATTACK
    }

    /** A listener registration that can be removed without checked exceptions. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
