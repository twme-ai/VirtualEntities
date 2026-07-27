package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import io.github.twme.virtualentities.PacketEventsTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualMetadataTest {
    private final EntityMetadataRegistry registry = new EntityMetadataRegistry();

    @BeforeAll
    static void initializePacketEvents() {
        PacketEventsTestSupport.initialize();
    }

    @AfterAll
    static void clearPacketEvents() {
        PacketEventsTestSupport.clear();
    }

    @Test
    void updatesFixedAndVersionedByteFlagsWithoutChangingNeighboringBits() {
        VirtualMetadata pig = new VirtualMetadata(registry.schema("1.21.11", "Pig"));
        pig.set(EntityMetadataKeys.SHARED_FLAGS, (byte) 0x41);
        pig.setFlag(EntityMetadataKeys.SHARED_FLAGS, (byte) 0x02, true);
        assertEquals((byte) 0x43, pig.get(EntityMetadataKeys.SHARED_FLAGS).orElseThrow());
        assertTrue(pig.isFlagSet(EntityMetadataKeys.SHARED_FLAGS, (byte) 0x40));
        pig.setFlag(EntityMetadataKeys.SHARED_FLAGS, (byte) 0x01, false);
        assertEquals((byte) 0x42, pig.get(EntityMetadataKeys.SHARED_FLAGS).orElseThrow());

        VirtualMetadata textDisplay = new VirtualMetadata(registry.schema("26.2", "Text Display"));
        MetadataKey<Byte> style = GeneratedEntityMetadataKeys.TextDisplay.STYLE_FLAGS;
        textDisplay.setFlag(style, (byte) 0x02, true)
                .setFlag(style, (byte) 0x04, true);
        assertEquals((byte) 0x06, textDisplay.get(style).orElseThrow());
        assertTrue(textDisplay.isFlagSet(style, (byte) 0x06));
        assertFalse(textDisplay.isFlagSet(style, (byte) 0x01));
    }

    @Test
    void treatsUnassignedFlagsAsZeroAndStoresExplicitDisabledState() {
        VirtualMetadata metadata = new VirtualMetadata(registry.schema("1.21.11", "Pig"));
        MetadataKey<Byte> key = EntityMetadataKeys.SHARED_FLAGS;

        assertFalse(metadata.isFlagSet(key, (byte) 0x01));
        assertTrue(metadata.get(key).isEmpty());
        metadata.setFlag(key, (byte) 0x01, false);
        assertTrue(metadata.contains(key));
        assertEquals((byte) 0, metadata.get(key).orElseThrow());
    }

    @Test
    void rejectsZeroMasksAndNonByteMetadataKeys() {
        VirtualMetadata metadata = new VirtualMetadata(registry.schema("1.21.11", "Pig"));

        assertThrows(
                IllegalArgumentException.class,
                () -> metadata.setFlag(EntityMetadataKeys.SHARED_FLAGS, (byte) 0, true)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> metadata.isFlagSet(EntityMetadataKeys.SHARED_FLAGS, (byte) 0)
        );
        assertThrows(IllegalArgumentException.class, () -> metadata.setFlag(asByteKey(
                MetadataKey.of("AIR_SUPPLY", EntityDataTypes.INT)
        ), (byte) 0x01, true));
        assertThrows(
                IllegalArgumentException.class,
                () -> new MetadataFlag(EntityMetadataKeys.SHARED_FLAGS, (byte) 0)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new MetadataFlag(EntityMetadataKeys.SHARED_FLAGS, (byte) 0x03)
        );
        MetadataFlag mismatchedField = new MetadataFlag(
                MetadataKey.of("AIR_SUPPLY", EntityDataTypes.BYTE),
                (byte) 0x01
        );
        assertThrows(IllegalArgumentException.class, () -> metadata.setFlag(mismatchedField, true));
        assertThrows(IllegalArgumentException.class, () -> metadata.isFlagSet(mismatchedField));
    }

    @Test
    void rejectsMismatchedCustomKeyBeforeInitialAssignment() {
        VirtualMetadata metadata = new VirtualMetadata(registry.schema("1.21.11", "Entity"));
        MetadataKey<Byte> wrong = MetadataKey.of("AIR_SUPPLY", EntityDataTypes.BYTE);

        assertThrows(IllegalArgumentException.class, () -> metadata.set(wrong, (byte) 10));
        assertFalse(metadata.contains(wrong));
        assertTrue(metadata.entityData().isEmpty());
    }

    @Test
    void namedFlagsBindTheirSemanticKeysAndPreserveUnknownBits() {
        assertEquals("SHARED_FLAGS", EntityMetadataFlags.ON_FIRE.key().fieldName());
        assertEquals((byte) 0x01, EntityMetadataFlags.ON_FIRE.mask());
        assertEquals("SHARED_FLAGS", EntityMetadataFlags.CROUCHING.key().fieldName());
        assertEquals((byte) 0x02, EntityMetadataFlags.CROUCHING.mask());
        assertEquals((byte) 0x08, EntityMetadataFlags.SPRINTING.mask());
        assertEquals((byte) 0x20, EntityMetadataFlags.INVISIBLE.mask());
        assertEquals((byte) 0x40, EntityMetadataFlags.GLOWING.mask());
        assertEquals((byte) 0x80, EntityMetadataFlags.FALL_FLYING.mask());

        VirtualMetadata pig = new VirtualMetadata(registry.schema("1.9.4", "Pig"));
        pig.set(EntityMetadataKeys.SHARED_FLAGS, (byte) 0x81);

        pig.setFlag(EntityMetadataFlags.GLOWING, true)
                .setFlag(EntityMetadataFlags.ON_FIRE, false)
                .setFlag(EntityMetadataFlags.CROUCHING, true);

        assertEquals((byte) 0xC2, pig.get(EntityMetadataKeys.SHARED_FLAGS).orElseThrow());
        assertTrue(pig.isFlagSet(EntityMetadataFlags.GLOWING));
        assertTrue(pig.isFlagSet(EntityMetadataFlags.CROUCHING));
        assertFalse(pig.isFlagSet(EntityMetadataFlags.ON_FIRE));

        VirtualMetadata textDisplay = new VirtualMetadata(
                registry.schema("1.19.4", "Text Display")
        );
        textDisplay.setFlag(EntityMetadataFlags.TextDisplay.SHADOW, true)
                .setFlag(EntityMetadataFlags.TextDisplay.SEE_THROUGH, true)
                .setFlag(EntityMetadataFlags.TextDisplay.DEFAULT_BACKGROUND, true);

        MetadataKey<Byte> styleFlags = GeneratedEntityMetadataKeys.TextDisplay.STYLE_FLAGS;
        assertEquals("STYLE_FLAGS", EntityMetadataFlags.TextDisplay.SHADOW.key().fieldName());
        assertEquals((byte) 0x01, EntityMetadataFlags.TextDisplay.SHADOW.mask());
        assertEquals((byte) 0x02, EntityMetadataFlags.TextDisplay.SEE_THROUGH.mask());
        assertEquals((byte) 0x04, EntityMetadataFlags.TextDisplay.DEFAULT_BACKGROUND.mask());
        assertEquals((byte) 0x08, EntityMetadataFlags.TextDisplay.ALIGN_LEFT.mask());
        assertEquals((byte) 0x10, EntityMetadataFlags.TextDisplay.ALIGN_RIGHT.mask());
        assertEquals((byte) 0x07, textDisplay.get(styleFlags).orElseThrow());
        assertTrue(textDisplay.isFlagSet(EntityMetadataFlags.TextDisplay.SHADOW));
        assertTrue(textDisplay.isFlagSet(EntityMetadataFlags.TextDisplay.SEE_THROUGH));
        assertTrue(textDisplay.isFlagSet(EntityMetadataFlags.TextDisplay.DEFAULT_BACKGROUND));
    }

    @Test
    void namedDescriptorMatchesTheRawOverloadWithoutImplicitSynchronization() {
        VirtualMetadata named = new VirtualMetadata(registry.schema("1.21.5", "Pig"));
        VirtualMetadata raw = new VirtualMetadata(registry.schema("1.21.5", "Pig"));

        named.setFlag(EntityMetadataFlags.GLOWING, true);
        raw.setFlag(EntityMetadataKeys.SHARED_FLAGS, (byte) 0x40, true);

        assertEquals(
                raw.get(EntityMetadataKeys.SHARED_FLAGS),
                named.get(EntityMetadataKeys.SHARED_FLAGS)
        );
        assertEquals(1, named.entityData().size());
        assertEquals(1, raw.entityData().size());
        assertEquals(raw.entityData().get(0).getIndex(), named.entityData().get(0).getIndex());
        assertEquals(raw.entityData().get(0).getType(), named.entityData().get(0).getType());
        assertEquals(raw.entityData().get(0).getValue(), named.entityData().get(0).getValue());

        VirtualMetadata explicitlyDisabled = new VirtualMetadata(registry.schema("1.9.4", "Pig"));
        explicitlyDisabled.setFlag(EntityMetadataFlags.GLOWING, false);
        assertTrue(explicitlyDisabled.contains(EntityMetadataKeys.SHARED_FLAGS));
        assertEquals((byte) 0, explicitlyDisabled.get(EntityMetadataKeys.SHARED_FLAGS).orElseThrow());
    }

    @Test
    void preservesConcurrentUpdatesToDifferentBits() throws Exception {
        VirtualMetadata metadata = new VirtualMetadata(registry.schema("1.21.11", "Pig"));
        MetadataKey<Byte> key = EntityMetadataKeys.SHARED_FLAGS;
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                start.await();
                for (int iteration = 0; iteration < 10_000; iteration++) {
                    metadata.setFlag(key, (byte) 0x01, true);
                }
                return null;
            });
            var second = executor.submit(() -> {
                start.await();
                for (int iteration = 0; iteration < 10_000; iteration++) {
                    metadata.setFlag(key, (byte) 0x80, true);
                }
                return null;
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals((byte) 0x81, metadata.get(key).orElseThrow());
        assertTrue(metadata.isFlagSet(key, (byte) 0x80));
        assertTrue(metadata.isFlagSet(key, (byte) 0x01));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static MetadataKey<Byte> asByteKey(MetadataKey<?> key) {
        return (MetadataKey) key;
    }
}
