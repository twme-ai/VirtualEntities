package io.github.twme.virtualentities.metadata;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Loads versioned entity metadata layouts bundled from kennytv entity-data. */
public final class EntityMetadataRegistry {
    private static final String ROOT = "/entity-data/";
    private static final Gson GSON = new Gson();
    private static final Type VERSION_LIST_TYPE = new TypeToken<List<String>>() { }.getType();
    private static final Type ENTITY_MAP_TYPE = new TypeToken<Map<String, RawEntity>>() { }.getType();

    private final ClassLoader classLoader;
    private final Set<String> versions;
    private final Map<String, Map<String, RawEntity>> data = new ConcurrentHashMap<>();

    public EntityMetadataRegistry() {
        this(EntityMetadataRegistry.class.getClassLoader());
    }

    EntityMetadataRegistry(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.versions = Set.copyOf(readVersions());
    }

    public Set<String> versions() {
        return versions;
    }

    public EntityMetadataSchema schema(String version, String entityName) {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(entityName, "entityName");
        if (!versions.contains(version)) {
            throw new IllegalArgumentException("Unsupported entity-data version '" + version + "'. Available: " + versions);
        }

        Map<String, RawEntity> entities = data.computeIfAbsent(version, this::readVersion);
        if (!entities.containsKey(entityName)) {
            throw new IllegalArgumentException("Unknown entity-data entity '" + entityName + "' in " + version);
        }

        Map<String, MetadataField> flattened = new LinkedHashMap<>();
        flatten(entities, entityName, flattened, ConcurrentHashMap.newKeySet());
        return new EntityMetadataSchema(version, entityName, flattened);
    }

    private void flatten(Map<String, RawEntity> entities, String name, Map<String, MetadataField> target, Set<String> visiting) {
        if (!visiting.add(name)) {
            throw new IllegalStateException("Entity-data inheritance cycle at " + name);
        }
        RawEntity entity = entities.get(name);
        if (entity == null) {
            throw new IllegalStateException("Missing entity-data superclass '" + name + "'");
        }
        if (entity.superClass != null) {
            flatten(entities, entity.superClass, target, visiting);
        }
        if (entity.fields != null) {
            for (MetadataField field : entity.fields) {
                target.put(field.fieldName(), field);
            }
        }
        visiting.remove(name);
    }

    private List<String> readVersions() {
        try (InputStream input = resource("entity-data/versions.json");
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            List<String> result = GSON.fromJson(reader, VERSION_LIST_TYPE);
            return result == null ? List.of() : new ArrayList<>(result);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load bundled entity-data versions", exception);
        }
    }

    private Map<String, RawEntity> readVersion(String version) {
        try (InputStream input = resource("entity-data/" + version + ".json");
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            Map<String, RawEntity> result = GSON.fromJson(reader, ENTITY_MAP_TYPE);
            return result == null ? Map.of() : Map.copyOf(result);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot load bundled entity-data for " + version, exception);
        }
    }

    private InputStream resource(String path) throws IOException {
        InputStream input = classLoader.getResourceAsStream(path);
        if (input == null) {
            throw new IOException("Missing classpath resource: " + path);
        }
        return input;
    }

    private static final class RawEntity {
        private String superClass;
        private List<MetadataField> fields;
    }
}
