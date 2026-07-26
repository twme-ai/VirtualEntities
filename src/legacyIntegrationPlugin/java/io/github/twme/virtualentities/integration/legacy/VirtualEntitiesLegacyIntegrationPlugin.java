package io.github.twme.virtualentities.integration.legacy;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import io.github.twme.virtualentities.VirtualEntities;
import io.github.twme.virtualentities.VirtualEntity;
import io.github.twme.virtualentities.VirtualEntityInteraction;
import io.github.twme.virtualentities.VirtualEntityManager;
import io.github.twme.virtualentities.metadata.EntityMetadataKeys;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Java 17 fixture for Minecraft servers predating the modern Paper API baseline. */
public final class VirtualEntitiesLegacyIntegrationPlugin extends JavaPlugin {
    private final List<VirtualEntity> testEntities = new ArrayList<>();
    private VirtualEntityManager entities;
    private PacketListenerAbstract packetListener;

    @Override
    public void onEnable() {
        entities = VirtualEntities.create();
        packetListener = new PacketListenerAbstract() {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
                    entities.handleInteraction(event.getUser(), new WrapperPlayClientInteractEntity(event));
                }
            }
        };
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
    }

    @Override
    public void onDisable() {
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        if (entities != null) {
            entities.close();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!(sender instanceof Player)) {
            return true;
        }
        Player player = (Player) sender;
        testEntities.forEach(VirtualEntity::remove);
        testEntities.clear();

        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        org.bukkit.Location origin = player.getLocation();
        Location spawn = new Location(origin.getX() + 2, origin.getY(), origin.getZ(), 0, 0);

        VirtualEntity pig = entities.entity(EntityTypes.PIG).metadata().build();
        pig.metadata()
                .set(EntityMetadataKeys.CUSTOM_NAME, Optional.of(Component.text("VE_LEGACY_PIG")))
                .set(EntityMetadataKeys.CUSTOM_NAME_VISIBLE, true)
                .set(EntityMetadataKeys.SHARED_FLAGS, (byte) 0x40);
        pig.setEquipment(
                EquipmentSlot.MAIN_HAND,
                ItemStack.builder().type(ItemTypes.STONE).amount(1).build()
        );
        pig.onInteraction(interaction -> {
            if (interaction.action() == VirtualEntityInteraction.Action.ATTACK) {
                player.sendMessage("VE_LEGACY_ATTACK_OK");
            }
        });

        VirtualEntity passenger = entities.entity(EntityTypes.ARMOR_STAND).metadata().build();
        passenger.metadata()
                .set(EntityMetadataKeys.CUSTOM_NAME, Optional.of(Component.text("VE_LEGACY_PASSENGER")))
                .set(EntityMetadataKeys.CUSTOM_NAME_VISIBLE, true);

        pig.addViewer(user).spawn(spawn);
        passenger.addViewer(user).spawn(spawn);
        pig.addPassenger(passenger);
        testEntities.add(pig);
        testEntities.add(passenger);

        player.sendMessage("VE_LEGACY_READY:" + pig.entityId() + ":" + passenger.entityId());
        getServer().getScheduler().runTaskLater(this, () -> {
            if (pig.isSpawned() && passenger.isSpawned()) {
                pig.move(1, 0, 0, true);
                player.sendMessage("VE_LEGACY_MOVED");
            }
        }, 20L);
        return true;
    }
}
