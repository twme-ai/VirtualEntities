package io.github.twme.virtualentities.codegen;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generates the public typed metadata key holders from bundled entity-data. */
public final class MetadataKeysGenerator {
    private static final Gson GSON = new Gson();
    private static final Type VERSIONS_TYPE = new TypeToken<List<String>>() { }.getType();
    private static final Type ENTITIES_TYPE = new TypeToken<Map<String, RawEntity>>() { }.getType();
    private static final Pattern NUMERIC_VERSION = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?$");
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public", "return",
            "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "true", "false", "null", "record", "sealed",
            "permits", "yield", "var"
    );
    private static final Map<String, Mapping> TYPE_MAPPINGS = mappings();

    private MetadataKeysGenerator() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length < 2 || arguments.length > 3) {
            throw new IllegalArgumentException("Usage: MetadataKeysGenerator <entity-data-dir> <output> [--check]");
        }
        Path dataDirectory = Path.of(arguments[0]);
        Path output = Path.of(arguments[1]);
        boolean check = arguments.length == 3 && "--check".equals(arguments[2]);
        if (arguments.length == 3 && !check) {
            throw new IllegalArgumentException("Unknown argument: " + arguments[2]);
        }

        String generated = generate(dataDirectory);
        if (check) {
            String current = Files.exists(output) ? Files.readString(output, StandardCharsets.UTF_8) : "";
            if (!current.equals(generated)) {
                throw new IllegalStateException(
                        "Generated metadata keys are stale. Run ./gradlew generateMetadataKeys"
                );
            }
            return;
        }

        Files.createDirectories(output.getParent());
        Files.writeString(output, generated, StandardCharsets.UTF_8);
    }

    private static String generate(Path dataDirectory) throws IOException {
        List<String> versions = read(dataDirectory.resolve("versions.json"), VERSIONS_TYPE);
        String latestVersion = versions.stream()
                .filter(version -> NUMERIC_VERSION.matcher(version).matches())
                .max(Comparator.comparing(MetadataKeysGenerator::release))
                .orElseThrow(() -> new IllegalStateException("No numeric entity-data version found"));

        Map<FieldIdentity, Set<String>> rawTypes = new HashMap<>();
        for (String version : versions) {
            Map<String, RawEntity> entities = read(dataDirectory.resolve(version + ".json"), ENTITIES_TYPE);
            for (Map.Entry<String, RawEntity> entity : entities.entrySet()) {
                for (RawField field : entity.getValue().fields()) {
                    rawTypes.computeIfAbsent(
                            new FieldIdentity(entity.getKey(), field.fieldName()),
                            ignored -> new LinkedHashSet<>()
                    ).add(field.dataType());
                }
            }
        }

        Map<String, RawEntity> latest = read(dataDirectory.resolve(latestVersion + ".json"), ENTITIES_TYPE);
        validateClassNames(latest.keySet());
        Set<String> imports = new LinkedHashSet<>();
        StringBuilder classes = new StringBuilder();
        latest.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendEntityClass(
                        classes,
                        entry.getKey(),
                        entry.getValue(),
                        latestVersion,
                        rawTypes,
                        imports
                ));

        StringBuilder source = new StringBuilder();
        imports.add("com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes");
        imports.add("java.util.Map");
        source.append("// Generated by MetadataKeysGenerator from entity-data ")
                .append(latestVersion)
                .append(". Do not edit manually.\n")
                .append("package io.github.twme.virtualentities.metadata;\n\n");
        imports.stream().sorted().forEach(name -> source.append("import ").append(name).append(";\n"));
        source.append("\n")
                .append("/** Typed metadata keys grouped by the declaring Minecraft entity-data class. */\n")
                .append("@SuppressWarnings(\"unused\")\n")
                .append("public final class GeneratedEntityMetadataKeys {\n")
                .append("    private GeneratedEntityMetadataKeys() {\n")
                .append("    }\n\n")
                .append(classes)
                .append("}\n");
        return source.toString();
    }

    private static void appendEntityClass(
            StringBuilder source,
            String entityName,
            RawEntity entity,
            String latestVersion,
            Map<FieldIdentity, Set<String>> rawTypes,
            Set<String> imports
    ) {
        String className = className(entityName);
        source.append("    /** Keys declared by ").append(javadoc(entityName))
                .append(" in entity-data ").append(javadoc(latestVersion)).append(". */\n")
                .append("    public static class ").append(className);
        if (entity.superClass() != null && !entity.superClass().isBlank()) {
            source.append(" extends ").append(className(entity.superClass()));
        }
        source.append(" {\n")
                .append("        /** Prevents external instantiation. */\n")
                .append("        protected ").append(className).append("() {\n")
                .append("        }\n");

        for (RawField field : entity.fields()) {
            requireJavaIdentifier(field.fieldName(), "metadata field");
            Mapping latestMapping = mapping(field.dataType());
            imports.addAll(latestMapping.imports());
            List<Map.Entry<String, Mapping>> compatible = rawTypes
                    .getOrDefault(new FieldIdentity(entityName, field.fieldName()), Set.of(field.dataType()))
                    .stream()
                    .map(raw -> Map.entry(raw, mapping(raw)))
                    .filter(entry -> compatible(latestMapping, entry.getValue()))
                    .sorted(Map.Entry.comparingByKey())
                    .toList();
            compatible.forEach(entry -> imports.addAll(entry.getValue().imports()));

            source.append("\n        /** Metadata field {@code ").append(javadoc(field.fieldName())).append("}. */\n")
                    .append("        public static final MetadataKey<")
                    .append(latestMapping.javaType()).append("> ").append(field.fieldName())
                    .append(" = MetadataKey.versioned(\n")
                    .append("                \"").append(javaString(field.fieldName())).append("\",\n")
                    .append("                EntityDataTypes.").append(latestMapping.constant()).append(",\n")
                    .append("                Map.ofEntries(\n");
            for (int index = 0; index < compatible.size(); index++) {
                Map.Entry<String, Mapping> entry = compatible.get(index);
                source.append("                        Map.entry(\"").append(javaString(entry.getKey()))
                        .append("\", EntityDataTypes.")
                        .append(entry.getValue().constant()).append(")");
                source.append(index + 1 == compatible.size() ? "\n" : ",\n");
            }
            source.append("                )\n")
                    .append("        );\n");
        }
        source.append("    }\n\n");
    }

    private static Mapping mapping(String rawType) {
        Mapping mapping = TYPE_MAPPINGS.get(rawType);
        if (mapping == null) {
            throw new IllegalStateException("No PacketEvents mapping for entity-data type: " + rawType);
        }
        return mapping;
    }

    private static boolean compatible(Mapping latest, Mapping historical) {
        return historical.constant().equals(latest.constant())
                || latest.constant().equals("OPTIONAL_ADV_COMPONENT")
                && historical.constant().equals("STRING");
    }

    private static Map<String, Mapping> mappings() {
        Map<String, Mapping> result = new LinkedHashMap<>();
        add(result, "Byte", "Byte", "BYTE");
        add(result, "Integer", "Integer", "INT");
        add(result, "Float", "Float", "FLOAT");
        add(result, "Long", "Long", "LONG");
        add(result, "Boolean", "Boolean", "BOOLEAN");
        add(result, "String", "String", "STRING");
        add(result, "OptionalInt", "Optional<Integer>", "OPTIONAL_INT", "java.util.Optional");
        add(result, "Optional<Integer>", "Optional<Integer>", "OPTIONAL_INT", "java.util.Optional");
        add(result, "Component", "Component", "ADV_COMPONENT", "net.kyori.adventure.text.Component");
        add(result, "ITextComponent", "Component", "ADV_COMPONENT", "net.kyori.adventure.text.Component");
        add(result, "Optional<Component>", "Optional<Component>", "OPTIONAL_ADV_COMPONENT",
                "java.util.Optional", "net.kyori.adventure.text.Component");
        add(result, "ParticleOptions", "Particle<?>", "PARTICLE",
                "com.github.retrooper.packetevents.protocol.particle.Particle");
        add(result, "List<ParticleOptions>", "List<Particle<?>>", "PARTICLES",
                "java.util.List", "com.github.retrooper.packetevents.protocol.particle.Particle");
        add(result, "Pose", "EntityPose", "ENTITY_POSE",
                "com.github.retrooper.packetevents.protocol.entity.pose.EntityPose");
        add(result, "BlockState", "Integer", "BLOCK_STATE");
        add(result, "Optional<BlockState>", "Integer", "OPTIONAL_BLOCK_STATE");
        add(result, "ResolvableProfile", "ItemProfile", "RESOLVABLE_PROFILE",
                "com.github.retrooper.packetevents.protocol.component.builtin.item.ItemProfile");
        add(result, "CompoundTag", "NBTCompound", "NBT",
                "com.github.retrooper.packetevents.protocol.nbt.NBTCompound");
        add(result, "NBTTagCompound", "NBTCompound", "NBT",
                "com.github.retrooper.packetevents.protocol.nbt.NBTCompound");
        add(result, "ItemStack", "ItemStack", "ITEMSTACK",
                "com.github.retrooper.packetevents.protocol.item.ItemStack");
        add(result, "Optional<UUID>", "Optional<UUID>", "OPTIONAL_UUID",
                "java.util.Optional", "java.util.UUID");
        add(result, "Optional<EntityReference<LivingEntity>>", "Optional<UUID>", "OPTIONAL_UUID",
                "java.util.Optional", "java.util.UUID");
        add(result, "Rotations", "Vector3f", "ROTATION",
                "com.github.retrooper.packetevents.util.Vector3f");
        add(result, "Vector3f", "Vector3f", "VECTOR3F",
                "com.github.retrooper.packetevents.util.Vector3f");
        add(result, "Vector3fc", "Vector3f", "VECTOR3F",
                "com.github.retrooper.packetevents.util.Vector3f");
        add(result, "BlockPos", "Vector3i", "BLOCK_POSITION",
                "com.github.retrooper.packetevents.util.Vector3i");
        add(result, "Optional<BlockPos>", "Optional<Vector3i>", "OPTIONAL_BLOCK_POSITION",
                "java.util.Optional", "com.github.retrooper.packetevents.util.Vector3i");
        add(result, "Quaternionf", "Quaternion4f", "QUATERNION",
                "com.github.retrooper.packetevents.util.Quaternion4f");
        add(result, "Quaternionfc", "Quaternion4f", "QUATERNION",
                "com.github.retrooper.packetevents.util.Quaternion4f");
        add(result, "Sniffer.State", "SnifferState", "SNIFFER_STATE",
                "com.github.retrooper.packetevents.protocol.entity.sniffer.SnifferState");
        add(result, "VillagerData", "VillagerData", "VILLAGER_DATA",
                "com.github.retrooper.packetevents.protocol.entity.villager.VillagerData");
        add(result, "HumanoidArm", "HumanoidArm", "HUMANOID_ARM",
                "com.github.retrooper.packetevents.protocol.player.HumanoidArm");
        add(result, "Direction", "BlockFace", "BLOCK_FACE",
                "com.github.retrooper.packetevents.protocol.world.BlockFace");
        add(result, "EnumFacing", "BlockFace", "BLOCK_FACE",
                "com.github.retrooper.packetevents.protocol.world.BlockFace");
        add(result, "CopperGolemState", "CopperGolemState", "COPPER_GOLEM_STATE",
                "com.github.retrooper.packetevents.protocol.entity.data.struct.CopperGolemState");
        add(result, "WeatheringCopper.WeatherState", "WeatheringCopperState", "WEATHERING_COPPER_STATE",
                "com.github.retrooper.packetevents.protocol.entity.data.struct.WeatheringCopperState");
        add(result, "Armadillo.ArmadilloState", "ArmadilloState", "ARMADILLO_STATE",
                "com.github.retrooper.packetevents.protocol.entity.armadillo.ArmadilloState");
        addVariantMappings(result);
        return Map.copyOf(result);
    }

    private static void addVariantMappings(Map<String, Mapping> result) {
        add(result, "FrogVariant", "FrogVariant", "TYPED_FROG_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.frog.FrogVariant");
        add(result, "Holder<FrogVariant>", "FrogVariant", "TYPED_FROG_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.frog.FrogVariant");
        add(result, "CatVariant", "CatVariant", "TYPED_CAT_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.cat.CatVariant");
        add(result, "Holder<CatVariant>", "CatVariant", "TYPED_CAT_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.cat.CatVariant");
        add(result, "Holder<ChickenVariant>", "ChickenVariant", "CHICKEN_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.chicken.ChickenVariant");
        add(result, "Holder<CowVariant>", "CowVariant", "COW_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.cow.CowVariant");
        add(result, "Holder<PaintingVariant>", "PaintingVariant", "PAINTING_VARIANT",
                "com.github.retrooper.packetevents.protocol.world.painting.PaintingVariant");
        add(result, "Holder<PigVariant>", "PigVariant", "PIG_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.pig.PigVariant");
        add(result, "Holder<WolfVariant>", "WolfVariant", "TYPED_WOLF_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.wolfvariant.WolfVariant");
        add(result, "Holder<ZombieNautilusVariant>", "ZombieNautilusVariant", "ZOMBIE_NAUTILUS_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.nautilus.ZombieNautilusVariant");
        add(result, "Holder<WolfSoundVariant>", "WolfSoundVariant", "WOLF_SOUND_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.wolfvariant.WolfSoundVariant");
        add(result, "Holder<CatSoundVariant>", "CatSoundVariant", "CAT_SOUND_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.cat.CatSoundVariant");
        add(result, "Holder<PigSoundVariant>", "PigSoundVariant", "PIG_SOUND_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.pig.PigSoundVariant");
        add(result, "Holder<ChickenSoundVariant>", "ChickenSoundVariant", "CHICKEN_SOUND_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.chicken.ChickenSoundVariant");
        add(result, "Holder<CowSoundVariant>", "CowSoundVariant", "COW_SOUND_VARIANT",
                "com.github.retrooper.packetevents.protocol.entity.cow.CowSoundVariant");
    }

    private static void add(
            Map<String, Mapping> mappings,
            String rawType,
            String javaType,
            String constant,
            String... imports
    ) {
        mappings.put(rawType, new Mapping(javaType, constant, Set.of(imports)));
    }

    private static void validateClassNames(Set<String> entityNames) {
        Map<String, String> owners = new HashMap<>();
        for (String entityName : entityNames) {
            String generatedName = className(entityName);
            requireJavaIdentifier(generatedName, "generated entity-data class");
            String previous = owners.putIfAbsent(generatedName, entityName);
            if (previous != null) {
                throw new IllegalStateException(
                        "Entity-data class name collision: '" + previous + "' and '" + entityName + "'"
                );
            }
        }
    }

    private static String className(String entityName) {
        StringBuilder result = new StringBuilder();
        boolean uppercase = true;
        for (int index = 0; index < entityName.length(); index++) {
            char character = entityName.charAt(index);
            if (!isAsciiLetterOrDigit(character)) {
                uppercase = true;
            } else if (uppercase) {
                result.append(Character.toUpperCase(character));
                uppercase = false;
            } else {
                result.append(character);
            }
        }
        if (result.isEmpty() || Character.isDigit(result.charAt(0))) {
            result.insert(0, "Entity");
        }
        return result.toString();
    }

    private static boolean isAsciiLetterOrDigit(char character) {
        return character >= 'A' && character <= 'Z'
                || character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9';
    }

    private static void requireJavaIdentifier(String value, String description) {
        if (!JAVA_IDENTIFIER.matcher(value).matches() || JAVA_KEYWORDS.contains(value)) {
            throw new IllegalStateException(description + " is not a safe Java identifier: '" + value + "'");
        }
    }

    private static String javaString(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (Character.isISOControl(character)) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String javadoc(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("*/", "*&#47;");
    }

    private static Release release(String value) {
        Matcher matcher = NUMERIC_VERSION.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a numeric version: " + value);
        }
        return new Release(
                Integer.parseInt(matcher.group(1)),
                matcher.group(2) == null ? 0 : Integer.parseInt(matcher.group(2)),
                matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3))
        );
    }

    private static <T> T read(Path path, Type type) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        T value = GSON.fromJson(json, type);
        return Objects.requireNonNull(value, "Empty JSON document: " + path);
    }

    private record FieldIdentity(String entityName, String fieldName) {
    }

    private record Mapping(String javaType, String constant, Set<String> imports) {
    }

    private record RawEntity(String superClass, List<RawField> fields) {
        private RawEntity {
            fields = fields == null ? List.of() : List.copyOf(fields);
        }
    }

    private record RawField(int index, String dataType, String fieldName, String defaultValue) {
    }

    private record Release(int major, int minor, int patch) implements Comparable<Release> {
        @Override
        public int compareTo(Release other) {
            return Comparator.comparingInt(Release::major)
                    .thenComparingInt(Release::minor)
                    .thenComparingInt(Release::patch)
                    .compare(this, other);
        }
    }
}
