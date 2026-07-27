package io.github.twme.virtualentities.metadata;

import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;

/** Canonical PacketEvents serializers for raw entity-data type names. */
final class EntityMetadataTypes {
    private EntityMetadataTypes() {
    }

    static EntityDataType<?> require(String rawType) {
        return switch (rawType) {
            case "Byte" -> EntityDataTypes.BYTE;
            case "Integer" -> EntityDataTypes.INT;
            case "Float" -> EntityDataTypes.FLOAT;
            case "Long" -> EntityDataTypes.LONG;
            case "Boolean" -> EntityDataTypes.BOOLEAN;
            case "String" -> EntityDataTypes.STRING;
            case "OptionalInt", "Optional<Integer>" -> EntityDataTypes.OPTIONAL_INT;
            case "Component", "ITextComponent" -> EntityDataTypes.ADV_COMPONENT;
            case "Optional<Component>" -> EntityDataTypes.OPTIONAL_ADV_COMPONENT;
            case "ParticleOptions" -> EntityDataTypes.PARTICLE;
            case "List<ParticleOptions>" -> EntityDataTypes.PARTICLES;
            case "Pose" -> EntityDataTypes.ENTITY_POSE;
            case "BlockState" -> EntityDataTypes.BLOCK_STATE;
            case "Optional<BlockState>" -> EntityDataTypes.OPTIONAL_BLOCK_STATE;
            case "ResolvableProfile" -> EntityDataTypes.RESOLVABLE_PROFILE;
            case "CompoundTag", "NBTTagCompound" -> EntityDataTypes.NBT;
            case "ItemStack" -> EntityDataTypes.ITEMSTACK;
            case "Optional<UUID>", "Optional<EntityReference<LivingEntity>>" -> EntityDataTypes.OPTIONAL_UUID;
            case "Rotations", "Vector3f", "Vector3fc" -> EntityDataTypes.VECTOR3F;
            case "BlockPos" -> EntityDataTypes.BLOCK_POSITION;
            case "Optional<BlockPos>" -> EntityDataTypes.OPTIONAL_BLOCK_POSITION;
            case "Quaternionf", "Quaternionfc" -> EntityDataTypes.QUATERNION;
            case "Sniffer.State" -> EntityDataTypes.SNIFFER_STATE;
            case "VillagerData" -> EntityDataTypes.VILLAGER_DATA;
            case "HumanoidArm" -> EntityDataTypes.HUMANOID_ARM;
            case "Direction", "EnumFacing" -> EntityDataTypes.BLOCK_FACE;
            case "CopperGolemState" -> EntityDataTypes.COPPER_GOLEM_STATE;
            case "WeatheringCopper.WeatherState" -> EntityDataTypes.WEATHERING_COPPER_STATE;
            case "Armadillo.ArmadilloState" -> EntityDataTypes.ARMADILLO_STATE;
            case "FrogVariant", "Holder<FrogVariant>" -> EntityDataTypes.TYPED_FROG_VARIANT;
            case "CatVariant", "Holder<CatVariant>" -> EntityDataTypes.TYPED_CAT_VARIANT;
            case "Holder<ChickenVariant>" -> EntityDataTypes.CHICKEN_VARIANT;
            case "Holder<CowVariant>" -> EntityDataTypes.COW_VARIANT;
            case "Holder<PaintingVariant>" -> EntityDataTypes.PAINTING_VARIANT;
            case "Holder<PigVariant>" -> EntityDataTypes.PIG_VARIANT;
            case "Holder<WolfVariant>" -> EntityDataTypes.TYPED_WOLF_VARIANT;
            case "Holder<ZombieNautilusVariant>" -> EntityDataTypes.ZOMBIE_NAUTILUS_VARIANT;
            case "Holder<WolfSoundVariant>" -> EntityDataTypes.WOLF_SOUND_VARIANT;
            case "Holder<CatSoundVariant>" -> EntityDataTypes.CAT_SOUND_VARIANT;
            case "Holder<PigSoundVariant>" -> EntityDataTypes.PIG_SOUND_VARIANT;
            case "Holder<ChickenSoundVariant>" -> EntityDataTypes.CHICKEN_SOUND_VARIANT;
            case "Holder<CowSoundVariant>" -> EntityDataTypes.COW_SOUND_VARIANT;
            default -> throw new IllegalArgumentException("Unsupported entity-data type '" + rawType + "'");
        };
    }
}
