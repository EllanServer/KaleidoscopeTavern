package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import net.momirealms.craftengine.bukkit.entity.data.LivingEntityData;
import net.momirealms.craftengine.proxy.bukkit.craftbukkit.entity.CraftEntityProxy;
import net.momirealms.craftengine.proxy.minecraft.network.chat.ComponentProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.syncher.EntityDataSerializersProxy;
import net.momirealms.craftengine.proxy.minecraft.network.syncher.SynchedEntityDataProxy;
import net.momirealms.craftengine.proxy.minecraft.server.level.ServerPlayerProxy;
import net.momirealms.craftengine.proxy.minecraft.server.network.ServerPlayerConnectionProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityProxy;
import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/** Sends visual-only entity state through CraftEngine's pinned NMS proxies. */
final class ViewerEffectPackets {
    // Entity.DATA_CUSTOM_NAME is part of the base Entity metadata layout. The
    // project is pinned to Paper 26.2 and CE 26.7.4, just like the other proxy
    // bridges in this plugin.
    private static final int CUSTOM_NAME_DATA_ID = 2;
    private static final List<Object> UPSIDE_DOWN_NAME_DATA = List.of(
            SynchedEntityDataProxy.DataValueProxy.INSTANCE.newInstance(
                    CUSTOM_NAME_DATA_ID,
                    EntityDataSerializersProxy.OPTIONAL_COMPONENT,
                    Optional.of(ComponentProxy.INSTANCE.literal("Grumm"))));

    private ViewerEffectPackets() {
    }

    /**
     * Makes one entity render upside down for one viewer without changing its
     * Bukkit custom name, save data, or metadata seen by other players.
     */
    static void showUpsideDown(Player viewer, Entity target) {
        Object targetHandle = CraftEntityProxy.INSTANCE.getEntity(target);
        sendDataValues(viewer, targetHandle, UPSIDE_DOWN_NAME_DATA);
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
     * 读取实体真实的 LivingEntity 效果粒子 metadata（纯原版状态）。由
     * {@link net.momirealms.craftengine.bukkit.entity.data.LivingEntityData} 提供
     * 与 CE 固定版本绑定的 accessor，不需要 Tavern 自行扫描字段 ID。
     */
    @SuppressWarnings("unchecked")
    static List<Object> readEffectParticles(LivingEntity living) {
        Object handle = CraftEntityProxy.INSTANCE.getEntity(living);
        Object data = EntityProxy.INSTANCE.getEntityData(handle);
        return List.copyOf(SynchedEntityDataProxy.INSTANCE.get(
                data, LivingEntityData.EffectParticles.entityDataAccessor()));
    }

    static boolean readEffectAmbience(LivingEntity living) {
        Object handle = CraftEntityProxy.INSTANCE.getEntity(living);
        Object data = EntityProxy.INSTANCE.getEntityData(handle);
        return SynchedEntityDataProxy.INSTANCE.get(
                data, LivingEntityData.EffectAmbience.entityDataAccessor());
    }

    /**
     * 把合并后的粒子列表写入实体真实的 SynchedEntityData。force=true 让原版
     * 实体追踪器在下一个实体 tick 广播 dirty metadata；此后才开始追踪该实体
     * 的玩家会从 addPairing 的初始 metadata 中直接拿到完整列表，不再需要
     * PlayerTrackEntityEvent 重放。
     */
    static void setEffectParticleMetadata(LivingEntity living,
                                          List<Object> particles, boolean ambient) {
        Object handle = CraftEntityProxy.INSTANCE.getEntity(living);
        Object data = EntityProxy.INSTANCE.getEntityData(handle);
        SynchedEntityDataProxy.INSTANCE.set(
                data, LivingEntityData.EffectParticles.entityDataAccessor(),
                List.copyOf(particles), true);
        SynchedEntityDataProxy.INSTANCE.set(
                data, LivingEntityData.EffectAmbience.entityDataAccessor(),
                ambient, true);
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

    private static void sendDataValue(Player viewer, Object targetHandle, Object dataValue) {
        sendDataValues(viewer, targetHandle, List.of(dataValue));
    }

    private static void sendDataValues(Player viewer, Object targetHandle, List<Object> dataValues) {
        Object packet = ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                EntityProxy.INSTANCE.getId(targetHandle), dataValues);

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

    }
}
