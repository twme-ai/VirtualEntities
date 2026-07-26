package io.github.twme.virtualentities.integration;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import io.github.twme.virtualentities.VirtualEntities;
import io.github.twme.virtualentities.VirtualEntity;
import io.github.twme.virtualentities.VirtualEntityInteraction;
import io.github.twme.virtualentities.VirtualEntityManager;
import io.github.twme.virtualentities.metadata.EntityMetadataKeys;
import io.github.twme.virtualentities.metadata.GeneratedEntityMetadataKeys;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Paper test fixture exercised by the Mineflayer black-box client. */
public final class VirtualEntitiesIntegrationPlugin extends JavaPlugin {
    private final Map<UUID, VirtualEntity> testEntities = new LinkedHashMap<>();
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
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] arguments
    ) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        VirtualEntity previous = testEntities.remove(player.getUniqueId());
        if (previous != null) {
            previous.remove();
        }

        User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        org.bukkit.Location origin = player.getLocation();
        Location spawn = new Location(origin.getX() + 2, origin.getY(), origin.getZ(), 0, 0);
        VirtualEntity pig = entities.entity(EntityTypes.PIG)
                .metadata()
                .build();
        pig.metadata()
                .set(EntityMetadataKeys.CUSTOM_NAME, Optional.of(Component.text("VE_TEST_PIG")))
                .set(EntityMetadataKeys.CUSTOM_NAME_VISIBLE, true)
                .set(GeneratedEntityMetadataKeys.Pig.BOOST_TIME, 10);
        pig.onInteraction(interaction -> {
            if (interaction.action() == VirtualEntityInteraction.Action.ATTACK) {
                player.sendMessage("VE_ATTACK_OK");
            }
        });
        pig.addViewer(user).spawn(spawn);
        testEntities.put(player.getUniqueId(), pig);
        player.sendMessage("VE_READY:" + pig.entityId());

        getServer().getScheduler().runTaskLater(this, () -> {
            if (!pig.isRemoved() && pig.isSpawned()) {
                pig.move(1, 0, 0, true);
                player.sendMessage("VE_MOVED");
            }
        }, 20L);
        return true;
    }
}
