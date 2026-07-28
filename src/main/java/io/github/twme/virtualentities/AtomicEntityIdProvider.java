package io.github.twme.virtualentities;

import java.util.concurrent.atomic.AtomicInteger;

/** A thread-safe descending entity ID source which avoids normal server IDs. */
public final class AtomicEntityIdProvider implements EntityIdProvider {
    private static final AtomicInteger GLOBAL_NEXT = new AtomicInteger(Integer.MAX_VALUE);

    private final AtomicInteger next;

    /** Uses the process-wide counter shared by every default manager in this classloader. */
    public AtomicEntityIdProvider() {
        this.next = GLOBAL_NEXT;
    }

    /** Creates an isolated counter, primarily for deterministic tests and platform integrations. */
    public AtomicEntityIdProvider(int firstId) {
        this.next = new AtomicInteger(firstId);
    }

    @Override
    public int nextEntityId() {
        return next.getAndDecrement();
    }
}
