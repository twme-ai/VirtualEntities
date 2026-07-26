package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;

import java.util.Objects;

/** A type-safe metadata field name resolved to a protocol index at runtime. */
public record MetadataKey<T>(String fieldName, EntityDataType<T> type) {
    public MetadataKey {
        Objects.requireNonNull(fieldName, "fieldName");
        Objects.requireNonNull(type, "type");
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName cannot be blank");
        }
    }

    public static <T> MetadataKey<T> of(String fieldName, EntityDataType<T> type) {
        return new MetadataKey<>(fieldName, type);
    }
}
