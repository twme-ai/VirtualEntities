package io.github.twme.virtualentities.metadata;

/**
 * Reviewed semantic names for packed metadata bits.
 *
 * <p>These constants are deliberately maintained separately from the
 * generated entity-data keys. The generated keys describe field locations and
 * serializers; this class describes the meaning of individual bits.</p>
 */
public final class EntityMetadataFlags {
    /** The entity is on fire. */
    public static final MetadataFlag ON_FIRE = new MetadataFlag(
            EntityMetadataKeys.SHARED_FLAGS,
            (byte) 0x01
    );

    /** The entity is crouching (the protocol documentation also calls this sneaking). */
    public static final MetadataFlag CROUCHING = new MetadataFlag(
            EntityMetadataKeys.SHARED_FLAGS,
            (byte) 0x02
    );

    /** The entity is sprinting. */
    public static final MetadataFlag SPRINTING = new MetadataFlag(
            EntityMetadataKeys.SHARED_FLAGS,
            (byte) 0x08
    );

    /** The entity is invisible. */
    public static final MetadataFlag INVISIBLE = new MetadataFlag(
            EntityMetadataKeys.SHARED_FLAGS,
            (byte) 0x20
    );

    /** The entity has the glowing effect. */
    public static final MetadataFlag GLOWING = new MetadataFlag(
            EntityMetadataKeys.SHARED_FLAGS,
            (byte) 0x40
    );

    /** The entity is fall-flying with an elytra. */
    public static final MetadataFlag FALL_FLYING = new MetadataFlag(
            EntityMetadataKeys.SHARED_FLAGS,
            (byte) 0x80
    );

    /** Semantic bits in {@code TextDisplay.STYLE_FLAGS}. */
    public static final class TextDisplay {
        /** Render the text with a shadow. */
        public static final MetadataFlag SHADOW = new MetadataFlag(
                GeneratedEntityMetadataKeys.TextDisplay.STYLE_FLAGS,
                (byte) 0x01
        );

        /** Render the text through blocks. */
        public static final MetadataFlag SEE_THROUGH = new MetadataFlag(
                GeneratedEntityMetadataKeys.TextDisplay.STYLE_FLAGS,
                (byte) 0x02
        );

        /** Use the default background color instead of the explicit color. */
        public static final MetadataFlag DEFAULT_BACKGROUND = new MetadataFlag(
                GeneratedEntityMetadataKeys.TextDisplay.STYLE_FLAGS,
                (byte) 0x04
        );

        /** Align the text to the left. */
        public static final MetadataFlag ALIGN_LEFT = new MetadataFlag(
                GeneratedEntityMetadataKeys.TextDisplay.STYLE_FLAGS,
                (byte) 0x08
        );

        /** Align the text to the right. */
        public static final MetadataFlag ALIGN_RIGHT = new MetadataFlag(
                GeneratedEntityMetadataKeys.TextDisplay.STYLE_FLAGS,
                (byte) 0x10
        );

        private TextDisplay() {
        }
    }

    private EntityMetadataFlags() {
    }
}
