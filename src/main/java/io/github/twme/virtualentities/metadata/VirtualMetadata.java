package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;

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
    private final Map<String, Value<?>> values = new LinkedHashMap<>();

    public VirtualMetadata(EntityMetadataSchema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    public EntityMetadataSchema schema() {
        return schema;
    }

    public synchronized <T> VirtualMetadata set(MetadataKey<T> key, T value) {
        Objects.requireNonNull(key, "key");
        MetadataField field = schema.require(key.fieldName());
        values.put(
                key.fieldName(),
                new Value<>(field.index(), key.type(field.dataType()), Objects.requireNonNull(value, "value"))
        );
        return this;
    }

    /** Returns an explicitly assigned value without consulting the schema's textual default. */
    @SuppressWarnings("unchecked")
    public synchronized <T> Optional<T> get(MetadataKey<T> key) {
        Objects.requireNonNull(key, "key");
        Value<?> value = values.get(key.fieldName());
        if (value == null) {
            return Optional.empty();
        }
        MetadataField field = schema.require(key.fieldName());
        EntityDataType<T> expectedType = key.type(field.dataType());
        if (!expectedType.equals(value.type())) {
            throw new IllegalArgumentException(
                    "Metadata key type does not match the explicitly assigned value for '" + key.fieldName() + "'"
            );
        }
        return Optional.of((T) value.value());
    }

    /** Returns whether a value has been explicitly assigned for the key's field name. */
    public synchronized boolean contains(MetadataKey<?> key) {
        return values.containsKey(Objects.requireNonNull(key, "key").fieldName());
    }

    public synchronized VirtualMetadata remove(MetadataKey<?> key) {
        values.remove(Objects.requireNonNull(key, "key").fieldName());
        return this;
    }

    public synchronized List<EntityData<?>> entityData() {
        List<EntityData<?>> result = new ArrayList<>(values.size());
        for (Value<?> value : values.values()) {
            result.add(value.toEntityData());
        }
        result.sort(java.util.Comparator.comparingInt(EntityData::getIndex));
        return Collections.unmodifiableList(result);
    }

    private record Value<T>(int index, EntityDataType<T> type, T value) {
        private EntityData<T> toEntityData() {
            return new EntityData<>(index, type, value);
        }
    }
}
