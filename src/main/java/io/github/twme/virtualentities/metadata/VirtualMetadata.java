package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Mutable, type-safe metadata values backed by a versioned schema. */
public final class VirtualMetadata {
    private final EntityMetadataSchema schema;
    private final Map<String, Value> values = new LinkedHashMap<>();

    public VirtualMetadata(EntityMetadataSchema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    public EntityMetadataSchema schema() {
        return schema;
    }

    public synchronized <T> VirtualMetadata set(MetadataKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        MetadataField field = schema.require(key.fieldName());
        T checkedValue = Objects.requireNonNull(value, "value");
        values.put(
                key.fieldName(),
                new Value(
                        field.index(),
                        key.type(field.dataType()),
                        key.encode(field.dataType(), checkedValue),
                        key.type(),
                        checkedValue
                )
        );
        return this;
    }

    /** Returns an explicitly assigned value without consulting the schema's textual default. */
    @SuppressWarnings("unchecked")
    public synchronized <T> Optional<T> get(MetadataKey<T> key) {
        Objects.requireNonNull(key, "key");
        Value value = values.get(key.fieldName());
        if (value == null) {
            return Optional.empty();
        }
        MetadataField field = schema.require(key.fieldName());
        key.type(field.dataType());
        if (!key.type().equals(value.logicalType())) {
            throw new IllegalArgumentException(
                    "Metadata key type does not match the explicitly assigned value for '" + key.fieldName() + "'"
            );
        }
        return Optional.of((T) value.logicalValue());
    }

    /** Returns whether a value has been explicitly assigned for the key's field name. */
    public synchronized boolean contains(MetadataKey<?> key) {
        return values.containsKey(Objects.requireNonNull(key, "key").fieldName());
    }

    /**
     * Atomically enables or disables every bit in {@code mask} while preserving all other bits.
     * An unassigned field starts at zero; disabling it therefore stores an explicit zero value.
     *
     * @param key a byte-backed metadata key
     * @param mask the non-zero bits to update
     * @param enabled whether the masked bits should be enabled
     * @return this metadata container
     */
    public synchronized VirtualMetadata setFlag(MetadataKey<Byte> key, byte mask, boolean enabled) {
        ResolvedByteKey resolved = resolveByteKey(key, mask);
        int current = Byte.toUnsignedInt(explicitByteValue(resolved));
        int maskBits = Byte.toUnsignedInt(mask);
        byte updated = (byte) (enabled ? current | maskBits : current & ~maskBits);
        values.put(
                key.fieldName(),
                new Value(resolved.field().index(), resolved.type(), updated, key.type(), updated)
        );
        return this;
    }

    /**
     * Returns whether every bit in {@code mask} is enabled, treating an unassigned field as zero.
     *
     * @param key a byte-backed metadata key
     * @param mask the non-zero bits to inspect
     * @return whether all masked bits are enabled
     */
    public synchronized boolean isFlagSet(MetadataKey<Byte> key, byte mask) {
        ResolvedByteKey resolved = resolveByteKey(key, mask);
        int maskBits = Byte.toUnsignedInt(mask);
        return (Byte.toUnsignedInt(explicitByteValue(resolved)) & maskBits) == maskBits;
    }

    public synchronized VirtualMetadata remove(MetadataKey<?> key) {
        values.remove(Objects.requireNonNull(key, "key").fieldName());
        return this;
    }

    public synchronized List<EntityData<?>> entityData() {
        List<EntityData<?>> result = new ArrayList<>(values.size());
        for (Value value : values.values()) {
            result.add(value.toEntityData());
        }
        result.sort(java.util.Comparator.comparingInt(EntityData::getIndex));
        return Collections.unmodifiableList(result);
    }

    private ResolvedByteKey resolveByteKey(MetadataKey<Byte> key, byte mask) {
        Objects.requireNonNull(key, "key");
        if (mask == 0) {
            throw new IllegalArgumentException("Metadata flag mask cannot be zero");
        }
        MetadataField field = schema.require(key.fieldName());
        EntityDataType<?> resolvedType = key.type(field.dataType());
        if (!EntityDataTypes.BYTE.equals(resolvedType)) {
            throw new IllegalArgumentException(
                    "Metadata field '" + key.fieldName() + "' is not byte-compatible"
            );
        }
        @SuppressWarnings("unchecked")
        EntityDataType<Byte> type = (EntityDataType<Byte>) resolvedType;
        return new ResolvedByteKey(field, type);
    }

    private byte explicitByteValue(ResolvedByteKey resolved) {
        Value value = values.get(resolved.field().fieldName());
        if (value == null) {
            return 0;
        }
        if (!resolved.type().equals(value.type()) || !(value.wireValue() instanceof Byte byteValue)) {
            throw new IllegalArgumentException(
                    "Metadata field '" + resolved.field().fieldName() + "' does not contain a byte value"
            );
        }
        return byteValue;
    }

    private record ResolvedByteKey(MetadataField field, EntityDataType<Byte> type) {
    }

    private record Value(
            int index,
            EntityDataType<?> type,
            Object wireValue,
            EntityDataType<?> logicalType,
            Object logicalValue
    ) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        private EntityData<?> toEntityData() {
            return new EntityData(index, type, wireValue);
        }
    }
}
