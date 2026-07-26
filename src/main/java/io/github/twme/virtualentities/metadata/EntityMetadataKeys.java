package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import net.kyori.adventure.text.Component;

import java.util.Optional;

/** Common metadata keys inherited by every entity. */
public final class EntityMetadataKeys {
    public static final MetadataKey<Byte> SHARED_FLAGS = GeneratedEntityMetadataKeys.Entity.SHARED_FLAGS;
    public static final MetadataKey<Integer> AIR_SUPPLY = GeneratedEntityMetadataKeys.Entity.AIR_SUPPLY;
    public static final MetadataKey<Optional<Component>> CUSTOM_NAME = GeneratedEntityMetadataKeys.Entity.CUSTOM_NAME;
    public static final MetadataKey<Boolean> CUSTOM_NAME_VISIBLE = GeneratedEntityMetadataKeys.Entity.CUSTOM_NAME_VISIBLE;
    public static final MetadataKey<Boolean> SILENT = GeneratedEntityMetadataKeys.Entity.SILENT;
    public static final MetadataKey<Boolean> NO_GRAVITY = GeneratedEntityMetadataKeys.Entity.NO_GRAVITY;
    public static final MetadataKey<EntityPose> POSE = GeneratedEntityMetadataKeys.Entity.POSE;
    public static final MetadataKey<Integer> TICKS_FROZEN = GeneratedEntityMetadataKeys.Entity.TICKS_FROZEN;

    private EntityMetadataKeys() {
    }
}
