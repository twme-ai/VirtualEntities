package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;

import java.util.Objects;

/**
 * A single, named bit in a byte-backed metadata field.
 *
 * <p>The descriptor binds one mask bit to its metadata key so callers cannot
 * accidentally pair a semantic flag with an unrelated field. The constructor
 * accepts custom byte-backed fields as well; the selected schema is validated
 * again when the descriptor is applied.</p>
 *
 * @param key the byte-backed metadata key
 * @param mask the single bit represented by this descriptor
 */
public record MetadataFlag(MetadataKey<Byte> key, byte mask) {
    /** Validates the key binding and requires one non-zero bit. */
    public MetadataFlag {
        Objects.requireNonNull(key, "key");
        if (!EntityDataTypes.BYTE.equals(key.type())) {
            throw new IllegalArgumentException("MetadataFlag key must be byte-backed");
        }
        if (mask == 0) {
            throw new IllegalArgumentException("MetadataFlag mask cannot be zero");
        }
        if (Integer.bitCount(Byte.toUnsignedInt(mask)) != 1) {
            throw new IllegalArgumentException("MetadataFlag mask must contain exactly one bit");
        }
    }
}
