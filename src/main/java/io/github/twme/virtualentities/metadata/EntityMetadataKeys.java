package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import net.kyori.adventure.text.Component;

import java.util.Optional;

/** Common metadata keys inherited by every entity. */
public final class EntityMetadataKeys {
    public static final MetadataKey<Byte> SHARED_FLAGS = MetadataKey.of("SHARED_FLAGS", EntityDataTypes.BYTE);
    public static final MetadataKey<Integer> AIR_SUPPLY = MetadataKey.of("AIR_SUPPLY", EntityDataTypes.INT);
    public static final MetadataKey<Optional<Component>> CUSTOM_NAME = MetadataKey.of("CUSTOM_NAME", EntityDataTypes.OPTIONAL_ADV_COMPONENT);
    public static final MetadataKey<Boolean> CUSTOM_NAME_VISIBLE = MetadataKey.of("CUSTOM_NAME_VISIBLE", EntityDataTypes.BOOLEAN);
    public static final MetadataKey<Boolean> SILENT = MetadataKey.of("SILENT", EntityDataTypes.BOOLEAN);
    public static final MetadataKey<Boolean> NO_GRAVITY = MetadataKey.of("NO_GRAVITY", EntityDataTypes.BOOLEAN);
    public static final MetadataKey<EntityPose> POSE = MetadataKey.of("POSE", EntityDataTypes.ENTITY_POSE);
    public static final MetadataKey<Integer> TICKS_FROZEN = MetadataKey.of("TICKS_FROZEN", EntityDataTypes.INT);

    private EntityMetadataKeys() {
    }
}
