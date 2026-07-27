package io.github.twme.virtualentities;

/** Additional platform-owned authorization for a visibility-filtered virtual entity interaction. */
@FunctionalInterface
public interface VirtualInteractionValidator {
    /**
     * Returns whether an interaction may be delivered to entity listeners.
     * Implementations should validate world, distance, line of sight, and rate limits as appropriate.
     */
    boolean test(VirtualEntityInteraction interaction);
}
