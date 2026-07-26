package io.github.twme.virtualentities;

import java.util.concurrent.atomic.AtomicInteger;

/** A thread-safe descending entity ID source which avoids normal server IDs. */
public final class AtomicEntityIdProvider implements EntityIdProvider {
    private final AtomicInteger next;

    public AtomicEntityIdProvider() {
        this(Integer.MAX_VALUE);
    }

    public AtomicEntityIdProvider(int firstId) {
        this.next = new AtomicInteger(firstId);
    }

    @Override
    public int nextEntityId() {
        return next.getAndDecrement();
    }
}
