package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviors;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.element.FurnitureElement;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.Vec3d;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.bukkit.Location;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Renders CE display-slot contents as packet-only furniture elements.
 *
 * <p>The native {@code display_item_furniture} controllers remain the sole
 * inventory and persistence owner. Their built-in dropped-item renderer can
 * only choose a position, while the archived Forge renderers require per-slot
 * pitch, yaw and scale. This controller supplies only that missing visual layer
 * through CE's tracked virtual elements, without persistent Bukkit display
 * entities or recovery PDC.</p>
 */
public final class StorageVisualFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:storage_visual_furniture";
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final ConcurrentMap<UUID, Controller> LOADED = new ConcurrentHashMap<>();
    private static volatile Handler handler;

    private final int slots;

    private StorageVisualFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
        this.slots = Math.max(0, section.getInt("slots", 0));
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(Key.of(TYPE), StorageVisualFurnitureBehavior::new);
        }
    }

    public static void bind(Handler newHandler) {
        handler = Objects.requireNonNull(newHandler, "newHandler");
        LOADED.values().forEach(controller -> controller.bukkitFurniture.refreshElements());
    }

    public static void unbind(Handler oldHandler) {
        if (handler == oldHandler) {
            handler = null;
            LOADED.values().forEach(controller -> controller.bukkitFurniture.refreshElements());
        }
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Storage visuals require BukkitFurniture");
        }
        return new Controller(bukkitFurniture, slots);
    }

    @FunctionalInterface
    public interface Handler {
        Visual visual(BukkitFurniture furniture, int slot);
    }

    public record Visual(Item item, double centerX, double centerY, double centerZ,
                         float scale, float yRot, float xRot,
                         boolean rotateWithFacing) {
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private final int slots;

        private Controller(BukkitFurniture furniture, int slots) {
            super(furniture);
            this.bukkitFurniture = furniture;
            this.slots = slots;
        }

        @Override
        public void gatherElements(Consumer<FurnitureElement> consumer) {
            for (int slot = 0; slot < slots; slot++) {
                consumer.accept(new StorageItemElement(bukkitFurniture, slot));
            }
        }

        @Override
        public void onPlace(Player player) {
            LOADED.put(bukkitFurniture.uuid(), this);
        }

        @Override
        public void onLoad() {
            LOADED.put(bukkitFurniture.uuid(), this);
        }

        @Override
        public void postRemove(Player player) {
            LOADED.remove(bukkitFurniture.uuid(), this);
        }

        @Override
        public void onUnload(boolean isStopping) {
            LOADED.remove(bukkitFurniture.uuid(), this);
        }
    }

    private static final class StorageItemElement implements FurnitureElement {
        private static final float VIEW_RANGE = 1.25F;

        private final BukkitFurniture furniture;
        private final int slot;
        private final int entityId = EntityUtils.ENTITY_COUNTER.incrementAndGet();
        private final UUID entityUuid = UUID.randomUUID();
        private final Object removePacket = ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(
                new IntArrayList(new int[]{entityId}));

        private StorageItemElement(BukkitFurniture furniture, int slot) {
            this.furniture = furniture;
            this.slot = slot;
        }

        @Override
        public void gatherInteractableEntityId(Consumer<Integer> collector) {
        }

        @Override
        public void show(Player player) {
            Visual current = currentVisual();
            if (current != null && current.item() != null && !current.item().isEmpty()) {
                sendVisual(player, current, false);
            }
        }

        @Override
        public void hide(Player player) {
            player.sendPacket(removePacket, false);
        }

        @Override
        public void update(Player player) {
            Visual current = currentVisual();
            if (current == null || current.item() == null || current.item().isEmpty()) {
                hide(player);
                return;
            }
            sendVisual(player, current, true);
        }

        private Visual currentVisual() {
            Handler currentHandler = handler;
            return currentHandler == null ? null : currentHandler.visual(furniture, slot);
        }

        private void sendVisual(Player player, Visual visual, boolean replace) {
            RenderPosition position = renderPosition(furniture, visual);
            Object spawnPacket = ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                    entityId, entityUuid,
                    position.x(), position.y(), position.z(),
                    visual.xRot(), position.yRot(),
                    EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0);

            List<Object> metadata = new ArrayList<>();
            DisplayData.ItemDisplayData.ItemStack.addEntityData(
                    visual.item().minecraftItem(), metadata);
            DisplayData.ItemDisplayData.Scale.addEntityDataIfNotDefaultValue(
                    new Vector3f(visual.scale()), metadata);
            DisplayData.ItemDisplayData.ViewRange.addEntityDataIfNotDefaultValue(
                    (float) (VIEW_RANGE * player.displayEntityViewDistance()), metadata);
            Object metadataPacket = ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                    entityId, metadata);

            if (replace) {
                player.sendPackets(List.of(removePacket, spawnPacket, metadataPacket), false);
            } else {
                player.sendPackets(List.of(spawnPacket, metadataPacket), false);
            }
        }
    }

    private static RenderPosition renderPosition(BukkitFurniture furniture, Visual visual) {
        Location origin = furniture.location();
        if (visual.rotateWithFacing()) {
            Vec3d center = furniture.getRelativePosition(new Vector3f(
                    (float) (visual.centerX() - 0.5), 0,
                    (float) (0.5 - visual.centerZ())));
            return new RenderPosition(
                    center.x,
                    origin.getY() + visual.centerY(),
                    center.z,
                    origin.getYaw() + visual.yRot());
        }
        return new RenderPosition(
                origin.getX() + visual.centerX() - 0.5,
                origin.getY() - 1 + visual.centerY(),
                origin.getZ() + visual.centerZ() - 0.5,
                180 + visual.yRot());
    }

    private record RenderPosition(double x, double y, double z, float yRot) {
    }
}
