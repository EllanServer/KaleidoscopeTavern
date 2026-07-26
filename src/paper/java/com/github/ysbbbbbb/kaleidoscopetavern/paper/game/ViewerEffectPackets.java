package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.momirealms.craftengine.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.craftengine.proxy.minecraft.network.chat.ComponentProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.syncher.EntityDataSerializersProxy;
import net.momirealms.craftengine.proxy.minecraft.network.syncher.SynchedEntityDataProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.craftengine.proxy.minecraft.server.network.ServerPlayerConnectionProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityProxy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/** Sends visual-only entity state through CraftEngine's pinned NMS proxies. */
final class ViewerEffectPackets {
    // Entity.DATA_CUSTOM_NAME is part of the base Entity metadata layout. The
    // project is pinned to Paper 26.2 and CE 26.7.4, just like the other proxy
    // bridges in this plugin.
    private static final int CUSTOM_NAME_DATA_ID = 2;

    private ViewerEffectPackets() {
    }

    /**
     * Makes one entity render upside down for one viewer without changing its
     * Bukkit custom name, save data, or metadata seen by other players.
     */
    static void showUpsideDown(Player viewer, Entity target) {
        Object targetHandle = CraftEntityProxy.INSTANCE.getEntity(target);
        Object entityData = EntityProxy.INSTANCE.getEntityData(targetHandle);
        Object currentName = findDataValue(
                SynchedEntityDataProxy.INSTANCE.packAll(entityData), CUSTOM_NAME_DATA_ID);
        Object serializer = currentName == null
                ? EntityDataSerializersProxy.OPTIONAL_COMPONENT
                : SynchedEntityDataProxy.DataValueProxy.INSTANCE.getSerializer(currentName);
        Object grumm = ComponentProxy.INSTANCE.literal("Grumm");
        Object dataValue = SynchedEntityDataProxy.DataValueProxy.INSTANCE.newInstance(
                CUSTOM_NAME_DATA_ID, serializer, Optional.of(grumm));
        sendDataValue(viewer, targetHandle, dataValue);
    }

    /** Restores the target's real server-side custom name for one viewer. */
    static void restoreCustomName(Player viewer, Entity target) {
        Object targetHandle = CraftEntityProxy.INSTANCE.getEntity(target);
        Object entityData = EntityProxy.INSTANCE.getEntityData(targetHandle);
        Object currentName = findDataValue(
                SynchedEntityDataProxy.INSTANCE.packAll(entityData), CUSTOM_NAME_DATA_ID);
        if (currentName == null) {
            currentName = SynchedEntityDataProxy.DataValueProxy.INSTANCE.newInstance(
                    CUSTOM_NAME_DATA_ID, EntityDataSerializersProxy.OPTIONAL_COMPONENT,
                    Optional.empty());
        }
        sendDataValue(viewer, targetHandle, currentName);
    }

    private static void sendDataValue(Player viewer, Object targetHandle, Object dataValue) {
        Object packet = ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                EntityProxy.INSTANCE.getId(targetHandle), List.of(dataValue));

        Object viewerHandle = CraftEntityProxy.INSTANCE.getEntity(viewer);
        Object connection = ServerPlayerProxy.INSTANCE.getConnection(viewerHandle);
        ServerPlayerConnectionProxy.INSTANCE.send(connection, packet);
    }

    private static Object findDataValue(List<Object> values, int id) {
        for (Object value : values) {
            if (SynchedEntityDataProxy.DataValueProxy.INSTANCE.getId(value) == id) {
                return value;
            }
        }
        return null;
    }
}
