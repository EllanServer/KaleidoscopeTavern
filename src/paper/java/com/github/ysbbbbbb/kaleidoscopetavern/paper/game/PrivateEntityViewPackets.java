package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.momirealms.craftengine.bukkit.entity.data.BaseEntityData;
import net.momirealms.craftengine.bukkit.plugin.network.BukkitNetworkManager;
import net.momirealms.craftengine.bukkit.util.ComponentUtils;
import net.momirealms.craftengine.core.plugin.network.NetWorkUser;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Optional;
import java.util.logging.Level;

/**
 * Sends viewer-specific entity metadata through CraftEngine's fixed 26.7
 * network bridge. Keeping this unstable boundary in one class makes a future
 * CraftEngine upgrade auditable.
 */
final class PrivateEntityViewPackets {
    private static final String UPSIDE_DOWN_NAME_JSON = "{\"text\":\"Grumm\"}";

    private final JavaPlugin plugin;
    private Object upsideDownName;
    private boolean available = true;

    PrivateEntityViewPackets(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    boolean showUpsideDown(Player viewer, LivingEntity target) {
        if (!available) {
            return false;
        }
        try {
            if (upsideDownName == null) {
                upsideDownName = ComponentUtils.jsonToMinecraft(UPSIDE_DOWN_NAME_JSON);
            }
            return sendCustomName(viewer, target, Optional.of(upsideDownName), false);
        } catch (RuntimeException | LinkageError exception) {
            return disable(exception);
        }
    }

    boolean restoreCustomName(Player viewer, LivingEntity target) {
        if (!available) {
            return false;
        }
        try {
            Optional<Object> currentName = Optional.ofNullable(target.customName())
                    .map(ComponentUtils::paperAdventureToJson)
                    .map(ComponentUtils::jsonToMinecraft);
            return sendCustomName(viewer, target, currentName, target.isCustomNameVisible());
        } catch (RuntimeException | LinkageError exception) {
            return disable(exception);
        }
    }

    private boolean sendCustomName(Player viewer, LivingEntity target,
                                   Optional<Object> name, boolean visible) {
        BukkitNetworkManager network = BukkitNetworkManager.instance();
        NetWorkUser user = network == null ? null : network.getOnlineUser(viewer.getUniqueId());
        if (user == null) {
            return false;
        }
        Object nameValue = BaseEntityData.CustomName.create(
                BaseEntityData.CustomName.entityDataAccessor(), name);
        Object visibilityValue = BaseEntityData.CustomNameVisible.create(
                BaseEntityData.CustomNameVisible.entityDataAccessor(), visible);
        Object packet = ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                target.getEntityId(), List.of(nameValue, visibilityValue));
        network.sendPacket(user, packet, false, null);
        return true;
    }

    private boolean disable(Throwable exception) {
        available = false;
        plugin.getLogger().log(Level.WARNING,
                "Failed to send private upside-down entity metadata; disabling that visual", exception);
        return false;
    }
}
