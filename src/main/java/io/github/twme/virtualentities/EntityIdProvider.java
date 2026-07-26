package io.github.twme.virtualentities;

/** Supplies unique protocol entity identifiers. */
@FunctionalInterface
public interface EntityIdProvider {
    int nextEntityId();
}
