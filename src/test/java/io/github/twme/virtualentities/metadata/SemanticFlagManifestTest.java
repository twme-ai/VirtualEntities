package io.github.twme.virtualentities.metadata;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.twme.virtualentities.PacketEventsTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticFlagManifestTest {
    @BeforeAll
    static void initializePacketEvents() {
        PacketEventsTestSupport.initialize();
    }

    @AfterAll
    static void clearPacketEvents() {
        PacketEventsTestSupport.clear();
    }

    @Test
    void reviewedManifestMatchesPublicDescriptors() throws IOException {
        Path manifestPath = Path.of("data", "metadata-flags", "semantic-flags.json");
        JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath)).getAsJsonObject();

        for (JsonElement element : manifest.getAsJsonArray("flags")) {
            JsonObject entry = element.getAsJsonObject();
            MetadataFlag descriptor = descriptor(entry.get("constant").getAsString());
            assertEquals(entry.get("mask").getAsByte(), descriptor.mask(), entry.get("id").getAsString());
            assertEquals(entry.get("field").getAsString(), descriptor.key().fieldName(), entry.get("id").getAsString());
        }
    }

    private static MetadataFlag descriptor(String constant) {
        String[] parts = constant.split("\\.");
        if (parts.length < 2 || !parts[0].equals("EntityMetadataFlags")) {
            throw new IllegalArgumentException("Unsupported semantic constant path: " + constant);
        }
        try {
            Class<?> holder = EntityMetadataFlags.class;
            for (int index = 1; index < parts.length - 1; index++) {
                holder = Class.forName(holder.getName() + "$" + parts[index]);
            }
            Field field = holder.getField(parts[parts.length - 1]);
            return (MetadataFlag) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot resolve semantic constant " + constant, exception);
        }
    }
}
