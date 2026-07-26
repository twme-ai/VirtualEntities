package io.github.twme.virtualentities.metadata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** The flattened metadata layout for an entity in one Minecraft version. */
public final class EntityMetadataSchema {
    private final String version;
    private final String entityName;
    private final Map<String, MetadataField> fields;

    EntityMetadataSchema(String version, String entityName, Map<String, MetadataField> fields) {
        this.version = Objects.requireNonNull(version, "version");
        this.entityName = Objects.requireNonNull(entityName, "entityName");
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public String version() {
        return version;
    }

    public String entityName() {
        return entityName;
    }

    public Map<String, MetadataField> fields() {
        return fields;
    }

    public Optional<MetadataField> find(String fieldName) {
        return Optional.ofNullable(fields.get(fieldName));
    }

    public MetadataField require(String fieldName) {
        MetadataField field = fields.get(fieldName);
        if (field == null) {
            throw new IllegalArgumentException("Unknown metadata field '" + fieldName + "' for " + entityName + " in " + version);
        }
        return field;
    }
}
