package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.momirealms.craftengine.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.CraftWorldProxy;
import net.momirealms.craftengine.proxy.minecraft.network.chat.ComponentProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.syncher.EntityDataSerializersProxy;
import net.momirealms.craftengine.proxy.minecraft.network.syncher.SynchedEntityDataProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerLevelProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.craftengine.proxy.minecraft.server.network.ServerPlayerConnectionProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityProxy;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
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

    /**
     * Converts one Bukkit colour to the immutable native ENTITY_EFFECT option.
     * EffectService caches the result per effect id instead of making
     * CraftParticle rebuild it for every visible swirl.
     */
    static Object entityEffectParticle(Color color) {
        try {
            return ParticleBridge.CREATE_PARTICLE_PARAM.invoke(Particle.ENTITY_EFFECT, color);
        } catch (Throwable error) {
            throw packetBridgeFailure("create ENTITY_EFFECT particle data", error);
        }
    }

    /**
     * Uses Paper's own receiver-aware particle path with an already converted
     * native option. This retains its world, visibility and 32-block distance
     * checks while avoiding the hot CraftParticle registry/data conversion.
     */
    static void sendEntityEffectParticle(World world, Collection<Player> receivers,
                                         Object particle, double x, double y, double z) {
        List<Object> handles = new ArrayList<>(receivers.size());
        for (Player receiver : receivers) {
            handles.add(CraftEntityProxy.INSTANCE.getEntity(receiver));
        }
        try {
            ParticleBridge.SEND_PARTICLES_SOURCE.invoke(
                    CraftWorldProxy.INSTANCE.getWorld(world), handles, null, particle,
                    false, false, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
        } catch (Throwable error) {
            throw packetBridgeFailure("send ENTITY_EFFECT particle", error);
        }
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

    private static IllegalStateException packetBridgeFailure(String action, Throwable error) {
        if (error instanceof Error fatal) {
            throw fatal;
        }
        return new IllegalStateException("Unable to " + action + " through Paper 26.2", error);
    }

    /** Resolve the pinned Paper bridge only if custom-effect particles exist. */
    private static final class ParticleBridge {
        private static final MethodHandle CREATE_PARTICLE_PARAM = createParticleParam();
        private static final MethodHandle SEND_PARTICLES_SOURCE = sendParticlesSource();

        private static MethodHandle createParticleParam() {
            try {
                Class<?> craftParticle = Class.forName("org.bukkit.craftbukkit.CraftParticle");
                Method method = craftParticle.getMethod(
                        "createParticleParam", Particle.class, Object.class);
                return MethodHandles.publicLookup().unreflect(method);
            } catch (ReflectiveOperationException error) {
                throw new ExceptionInInitializerError(error);
            }
        }

        private static MethodHandle sendParticlesSource() {
            for (Method method : ServerLevelProxy.CLASS.getMethods()) {
                if (method.getName().equals("sendParticlesSource")
                        && method.getParameterCount() == 13
                        && method.getParameterTypes()[0] == List.class) {
                    try {
                        return MethodHandles.publicLookup().unreflect(method);
                    } catch (IllegalAccessException error) {
                        throw new ExceptionInInitializerError(error);
                    }
                }
            }
            throw new ExceptionInInitializerError(
                    "Paper 26.2 receiver-aware sendParticlesSource method is missing");
        }
    }
}
