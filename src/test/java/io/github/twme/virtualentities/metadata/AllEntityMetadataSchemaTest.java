package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.twme.virtualentities.PacketEventsTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.SAME_THREAD)
class AllEntityMetadataSchemaTest {
    private static final Set<String> REVIEWED_UNSUPPORTED_FIELDS = Set.of(
            "26w14a:Living Block.MOVEMENT_DATA:MovementData",
            "26w14a:Living Block.MOVEMENT_TARGET:Target"
    );
    private static EntityMetadataRegistry registry;
    private static List<Snapshot> snapshots;

    @BeforeAll
    static void initialize() {
        PacketEventsTestSupport.initialize();
        registry = new EntityMetadataRegistry();
        snapshots = registry.versions().stream().map(AllEntityMetadataSchemaTest::readSnapshot).toList();
    }

    @AfterAll
    static void clearPacketEvents() {
        PacketEventsTestSupport.clear();
    }

    @Test
    void coverageInventoryIncludesEveryBundledResource() {
        Set<String> loadedVersions = new LinkedHashSet<>();
        int entityCount = 0;
        int declaredFieldCount = 0;
        for (Snapshot snapshot : snapshots) {
            loadedVersions.add(snapshot.version());
            entityCount += snapshot.entities().size();
            declaredFieldCount += snapshot.entities().values().stream()
                    .mapToInt(entity -> entity.fields().size())
                    .sum();
        }

        assertEquals(registry.versions(), loadedVersions);
        assertTrue(entityCount > 0);
        assertTrue(declaredFieldCount > 0);
        System.out.printf(
                "All-entity schema matrix: snapshots=%d entities=%d declaredFields=%d%n",
                snapshots.size(),
                entityCount,
                declaredFieldCount
        );
    }

    @TestFactory
    Stream<DynamicNode> validatesEveryEntityInEveryBundledSnapshot() {
        return snapshots.stream().map(snapshot -> DynamicContainer.dynamicContainer(
                snapshot.version(),
                snapshot.entities().keySet().stream().map(entityName -> DynamicTest.dynamicTest(
                        entityName,
                        () -> assertSchema(snapshot, entityName)
                ))
        ));
    }

    @TestFactory
    Stream<DynamicNode> validatesEveryRegisteredEntitySchemaForEverySupportedServerVersion() {
        List<VersionMatrix> matrix = Arrays.stream(ServerVersion.values())
                .filter(version -> version != ServerVersion.ERROR)
                .filter(version -> version.isNewerThanOrEquals(ServerVersion.V_1_9_4))
                .map(version -> new VersionMatrix(
                        version,
                        EntityTypes.values().stream()
                                .filter(type -> type.getId(version.toClientVersion()) >= 0)
                                .toList()
                ))
                .toList();
        int cases = matrix.stream().mapToInt(entry -> entry.types().size()).sum();
        System.out.printf("All-entity runtime schema matrix: serverVersions=%d cases=%d%n", matrix.size(), cases);

        return matrix.stream().map(entry -> DynamicContainer.dynamicContainer(
                entry.version().getReleaseName(),
                entry.types().stream().map(type -> DynamicTest.dynamicTest(
                        type.getName().toString(),
                        () -> assertRuntimeSchema(entry.version(), type)
                ))
        ));
    }

    private static void assertRuntimeSchema(ServerVersion version, EntityType type) {
        EntityMetadataSchema schema = registry.schema(version.getReleaseName(), type);
        ClientVersion clientVersion = version.toClientVersion();
        for (MetadataField field : schema.fields().values()) {
            EntityDataType<?> serializer = EntityMetadataTypes.require(field.dataType());
            assertTrue(serializer.getId(clientVersion) >= 0, () ->
                    version.getReleaseName() + ":" + type.getName() + "." + field.fieldName()
                            + " uses unavailable serializer " + field.dataType());
        }
    }

    private static void assertSchema(Snapshot snapshot, String entityName) {
        Map<String, MetadataField> expected = new LinkedHashMap<>();
        flatten(snapshot, entityName, expected, new LinkedHashSet<>());

        Set<String> unsupported = new LinkedHashSet<>();
        for (MetadataField field : expected.values()) {
            try {
                EntityMetadataTypes.require(field.dataType());
            } catch (IllegalArgumentException exception) {
                unsupported.add(snapshot.version() + ":" + entityName + "."
                        + field.fieldName() + ":" + field.dataType());
            }
        }
        if (!unsupported.isEmpty()) {
            assertEquals(REVIEWED_UNSUPPORTED_FIELDS, unsupported);
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> registry.schema(snapshot.version(), entityName)
            );
            assertTrue(exception.getMessage().contains("MOVEMENT_DATA"));
            return;
        }

        EntityMetadataSchema actual = registry.schema(snapshot.version(), entityName);
        assertEquals(snapshot.version(), actual.version());
        assertEquals(entityName, actual.entityName());
        assertEquals(expected, actual.fields());

        ClientVersion clientVersion = clientVersionFor(snapshot.version());
        Set<Integer> indices = new LinkedHashSet<>();
        for (MetadataField field : actual.fields().values()) {
            assertTrue(indices.add(field.index()), () ->
                    snapshot.version() + ":" + entityName + " duplicates metadata index " + field.index());
            EntityDataType<?> serializer = EntityMetadataTypes.require(field.dataType());
            assertNotNull(serializer);
            if (clientVersion != null) {
                assertTrue(serializer.getId(clientVersion) >= 0, () ->
                        snapshot.version() + ":" + entityName + "." + field.fieldName()
                                + " uses unavailable serializer " + field.dataType());
            }
        }
    }

    private static void flatten(
            Snapshot snapshot,
            String entityName,
            Map<String, MetadataField> target,
            Set<String> visiting
    ) {
        assertTrue(visiting.add(entityName), () ->
                snapshot.version() + " has an inheritance cycle at " + entityName);
        RawEntity entity = Objects.requireNonNull(
                snapshot.entities().get(entityName),
                () -> snapshot.version() + " is missing entity " + entityName
        );
        if (entity.superClass() != null) {
            flatten(snapshot, entity.superClass(), target, visiting);
        }
        for (MetadataField field : entity.fields()) {
            MetadataField previous = target.put(field.fieldName(), field);
            assertTrue(previous == null, () ->
                    snapshot.version() + ":" + entityName + " shadows inherited field " + field.fieldName());
        }
        visiting.remove(entityName);
    }

    private static Snapshot readSnapshot(String version) {
        String path = "entity-data/" + version + ".json";
        try (InputStream input = AllEntityMetadataSchemaTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing classpath resource " + path);
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, RawEntity> entities = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                JsonObject rawEntity = entry.getValue().getAsJsonObject();
                String superClass = rawEntity.has("superClass") && !rawEntity.get("superClass").isJsonNull()
                        ? rawEntity.get("superClass").getAsString()
                        : null;
                JsonArray rawFields = rawEntity.has("fields")
                        ? rawEntity.getAsJsonArray("fields")
                        : new JsonArray();
                List<MetadataField> fields = new ArrayList<>(rawFields.size());
                for (JsonElement element : rawFields) {
                    JsonObject field = element.getAsJsonObject();
                    fields.add(new MetadataField(
                            field.get("index").getAsInt(),
                            field.get("dataType").getAsString(),
                            field.get("fieldName").getAsString(),
                            field.has("defaultValue") && !field.get("defaultValue").isJsonNull()
                                    ? field.get("defaultValue").getAsString()
                                    : null
                    ));
                }
                entities.put(entry.getKey(), new RawEntity(superClass, List.copyOf(fields)));
            }
            return new Snapshot(version, Map.copyOf(entities));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read " + path, exception);
        }
    }

    private static ClientVersion clientVersionFor(String snapshot) {
        return Arrays.stream(ServerVersion.values())
                .filter(version -> version.getReleaseName().equals(snapshot))
                .findFirst()
                .map(ServerVersion::toClientVersion)
                .orElse(null);
    }

    private record Snapshot(String version, Map<String, RawEntity> entities) {
    }

    private record RawEntity(String superClass, List<MetadataField> fields) {
    }

    private record VersionMatrix(ServerVersion version, List<EntityType> types) {
    }
}
