package io.github.twme.virtualentities.metadata;

/** One metadata field from the kennytv entity-data dataset. */
public record MetadataField(int index, String dataType, String fieldName, String defaultValue) {
}
