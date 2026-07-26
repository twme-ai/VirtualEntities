package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads versioned entity metadata layouts bundled from kennytv entity-data. */
public final class EntityMetadataRegistry {
    private static final Gson GSON = new Gson();
    private static final Type VERSION_LIST_TYPE = new TypeToken<List<String>>() { }.getType();
    private static final Type ENTITY_MAP_TYPE = new TypeToken<Map<String, RawEntity>>() { }.getType();
    private static final Pattern RELEASE_VERSION = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?$");
    private static final Map<String, String> ENTITY_ALIASES = Map.ofEntries(
            Map.entry("falling_block", "Falling Block Entity"),
            Map.entry("firework", "Firework Rocket Entity"),
            Map.entry("firework_rocket", "Firework Rocket Entity"),
            Map.entry("fishing_bobber", "Fishing Hook"),
            Map.entry("item", "Item Entity"),
            Map.entry("leash_knot", "Leash Fence Knot Entity"),
            Map.entry("mooshroom", "Mushroom Cow"),
            Map.entry("tnt", "Primed Tnt"),
            Map.entry("primed_tnt", "Primed Tnt"),
            Map.entry("wither", "Wither Boss"),
            Map.entry("egg", "Thrown Egg"),
            Map.entry("ender_pearl", "Thrown Enderpearl"),
            Map.entry("experience_bottle", "Thrown Experience Bottle"),
            Map.entry("thrown_exp_bottle", "Thrown Experience Bottle"),
            Map.entry("trident", "Thrown Trident"),
            Map.entry("potion", "Abstract Thrown Potion"),
            Map.entry("splash_potion", "Thrown Splash Potion"),
            Map.entry("lingering_potion", "Thrown Lingering Potion"),
            Map.entry("chest_minecart", "Minecart Chest"),
            Map.entry("command_block_minecart", "Minecart Command Block"),
            Map.entry("furnace_minecart", "Minecart Furnace"),
            Map.entry("hopper_minecart", "Minecart Hopper"),
            Map.entry("spawner_minecart", "Minecart Spawner"),
            Map.entry("tnt_minecart", "Minecart TNT")
    );

    private final ClassLoader classLoader;
    private final List<String> versionOrder;
    private final Set<String> versions;
    private final Map<String, Map<String, RawEntity>> data = new ConcurrentHashMap<>();

    public EntityMetadataRegistry() {
        this(EntityMetadataRegistry.class.getClassLoader());
    }

    EntityMetadataRegistry(ClassLoader classLoader) {
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
        this.versionOrder = List.copyOf(readVersions());
        this.versions = Collections.unmodifiableSet(new LinkedHashSet<>(versionOrder));
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

    /**
     * Resolves the best bundled snapshot at or before a requested release.
     * Exact snapshot names, such as weekly releases, are accepted as-is.
     */
    public String resolveVersion(String requestedVersion) {
        Objects.requireNonNull(requestedVersion, "requestedVersion");
        if (versions.contains(requestedVersion)) {
            return requestedVersion;
        }

        Release requested = Release.parse(requestedVersion).orElseThrow(() ->
                new IllegalArgumentException("Unsupported entity-data version '" + requestedVersion + "'"));

        return versionOrder.stream()
                .map(version -> Release.parse(version).map(release -> Map.entry(version, release)))
                .flatMap(Optional::stream)
                .filter(entry -> entry.getValue().compareTo(requested) <= 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No entity-data snapshot is available at or before '" + requestedVersion + "'"));
    }

    /** Resolves a metadata schema from a registry entity type and Minecraft release. */
    public EntityMetadataSchema schema(String requestedVersion, EntityType type) {
        Objects.requireNonNull(type, "type");
        String version = resolveVersion(requestedVersion);
        Map<String, RawEntity> entities = data.computeIfAbsent(version, this::readVersion);

        EntityType candidate = type;
        while (candidate != null) {
            String registryName = candidate.getName().getKey();
            Optional<String> entityName = resolveEntityName(entities, registryName);
            if (entityName.isPresent()) {
                return schema(version, entityName.get());
            }
            candidate = candidate.getParent().orElse(null);
        }

        throw new IllegalArgumentException(
                "Cannot map PacketEvents entity type '" + type.getName() + "' to entity-data " + version);
    }

    private Optional<String> resolveEntityName(Map<String, RawEntity> entities, String registryName) {
        String alias = ENTITY_ALIASES.get(registryName);
        if (alias != null && entities.containsKey(alias)) {
            return Optional.of(alias);
        }

        String normalized = normalize(registryName);
        return entities.keySet().stream()
                .filter(name -> normalize(name).equals(normalized))
                .findFirst();
    }

    private static String normalize(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                result.append(Character.toLowerCase(character));
            }
        }
        return result.toString();
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

    private record Release(int major, int minor, int patch) implements Comparable<Release> {
        private static Optional<Release> parse(String value) {
            Matcher matcher = RELEASE_VERSION.matcher(value);
            if (!matcher.matches()) {
                return Optional.empty();
            }
            return Optional.of(new Release(
                    Integer.parseInt(matcher.group(1)),
                    matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)),
                    matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))
            ));
        }

        @Override
        public int compareTo(Release other) {
            return Comparator.comparingInt(Release::major)
                    .thenComparingInt(Release::minor)
                    .thenComparingInt(Release::patch)
                    .compare(this, other);
        }
    }
}
