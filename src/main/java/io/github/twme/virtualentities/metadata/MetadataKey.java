package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;

import java.util.Map;
import java.util.Objects;

/** A type-safe metadata field name resolved to a protocol index at runtime. */
public final class MetadataKey<T> {
    private final String fieldName;
    private final EntityDataType<T> type;
    private final Map<String, EntityDataType<T>> versionedTypes;

    public MetadataKey(String fieldName, EntityDataType<T> type) {
        this(fieldName, type, Map.of());
    }

    private MetadataKey(
            String fieldName,
            EntityDataType<T> type,
            Map<String, EntityDataType<T>> versionedTypes
    ) {
        this.fieldName = Objects.requireNonNull(fieldName, "fieldName");
        this.type = Objects.requireNonNull(type, "type");
        this.versionedTypes = Map.copyOf(Objects.requireNonNull(versionedTypes, "versionedTypes"));
        if (fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName cannot be blank");
        }
    }

    public static <T> MetadataKey<T> of(String fieldName, EntityDataType<T> type) {
        return new MetadataKey<>(fieldName, type, Map.of());
    }

    /** Creates a key that selects its serializer from the schema's raw entity-data type. */
    public static <T> MetadataKey<T> versioned(
            String fieldName,
            EntityDataType<T> latestType,
            Map<String, EntityDataType<T>> versionedTypes
    ) {
        if (Objects.requireNonNull(versionedTypes, "versionedTypes").isEmpty()) {
            throw new IllegalArgumentException("versionedTypes cannot be empty");
        }
        return new MetadataKey<>(fieldName, latestType, versionedTypes);
    }

    public String fieldName() {
        return fieldName;
    }

    /** Returns the default serializer, which is the newest serializer for generated keys. */
    public EntityDataType<T> type() {
        return type;
    }

    EntityDataType<T> type(String rawDataType) {
        if (versionedTypes.isEmpty()) {
            return type;
        }
        EntityDataType<T> resolved = versionedTypes.get(rawDataType);
        if (resolved == null) {
            throw new IllegalArgumentException(
                    "Metadata field '" + fieldName + "' does not support entity-data type '" + rawDataType + "'"
            );
        }
        return resolved;
    }

    @Override
    public boolean equals(Object other) {
        return other == this || other instanceof MetadataKey<?> key
                && fieldName.equals(key.fieldName)
                && type.equals(key.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, type);
    }

    @Override
    public String toString() {
        return "MetadataKey[fieldName=" + fieldName + ", type=" + type + "]";
    }
}
