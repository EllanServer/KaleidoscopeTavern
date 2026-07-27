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
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.joml.Quaternionf;
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
 * Renders the dynamic contents of pressing tubs and barrels as one CE-managed
 * packet-only furniture element.
 *
 * <p>The source block-entity renderers can show dozens of ingredient copies
 * with arbitrary rotations. CE's static furniture variants cannot express a
 * runtime-sized list, so this behavior keeps CE responsible for tracking and
 * culling while Tavern supplies only the exact source transforms. No Bukkit
 * display entity or recovery PDC is created.</p>
 */
public final class StationVisualFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:station_visual_furniture";
    public static final byte ITEM_TRANSFORM_NONE = 0;
    public static final byte ITEM_TRANSFORM_FIXED = 8;

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final ConcurrentMap<UUID, Controller> LOADED = new ConcurrentHashMap<>();
    private static volatile Handler handler;

    private final int maxElements;
    private final float viewRange;

    private StationVisualFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
        this.maxElements = Math.max(1, section.getInt("max_elements", 1));
        this.viewRange = Math.max(0.1F, section.getFloat("view_range", 1.25F));
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            VirtualEntityIdentity.prewarm();
            StationVisualElement.prewarm();
            FurnitureBehaviors.register(Key.of(TYPE), StationVisualFurnitureBehavior::new);
        }
    }

    public static void bind(Handler newHandler) {
        handler = Objects.requireNonNull(newHandler, "newHandler");
        LOADED.values().forEach(Controller::refresh);
    }

    public static void unbind(Handler oldHandler) {
        if (handler == oldHandler) {
            handler = null;
            LOADED.values().forEach(Controller::refresh);
        }
    }

    /** Invalidates player-independent visual content before CE redistributes it. */
    public static void refresh(BukkitFurniture furniture) {
        Objects.requireNonNull(furniture, "furniture");
        Controller controller = LOADED.get(furniture.uuid());
        if (controller != null) {
            controller.refresh();
        } else {
            furniture.refreshElements();
        }
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Station visuals require BukkitFurniture");
        }
        return new Controller(bukkitFurniture, maxElements, viewRange);
    }

    @FunctionalInterface
    public interface Handler {
        List<Visual> visuals(BukkitFurniture furniture);
    }

    public record Visual(Item item, double x, double y, double z,
                         float yRot, float xRot, float scale,
                         Quaternionf leftRotation, byte itemTransform) {
        public Visual {
            Objects.requireNonNull(item, "item");
            leftRotation = new Quaternionf(Objects.requireNonNull(
                    leftRotation, "leftRotation"));
        }
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private final int maxElements;
        private final float viewRange;
        private List<Visual> cachedVisuals = List.of();
        private boolean visualsDirty = true;

        private Controller(BukkitFurniture furniture, int maxElements, float viewRange) {
            super(furniture);
            this.bukkitFurniture = furniture;
            this.maxElements = maxElements;
            this.viewRange = viewRange;
        }

        @Override
        public void gatherElements(Consumer<FurnitureElement> consumer) {
            invalidateVisuals();
            consumer.accept(new StationVisualElement(
                    this, maxElements, viewRange));
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

        private void refresh() {
            invalidateVisuals();
            bukkitFurniture.refreshElements();
        }

        private void invalidateVisuals() {
            cachedVisuals = List.of();
            visualsDirty = true;
        }

        private List<Visual> visuals() {
            if (visualsDirty) {
                Handler currentHandler = handler;
                cachedVisuals = currentHandler == null
                        ? List.of()
                        : List.copyOf(currentHandler.visuals(bukkitFurniture));
                visualsDirty = false;
            }
            return cachedVisuals;
        }
    }

    private static final class StationVisualElement implements FurnitureElement {
        private final Controller controller;
        private final BukkitFurniture furniture;
        private final int maxElements;
        private final float viewRange;
        private final int[] entityIds;
        private final UUID[] entityUuids;
        private final Object removePacket;

        private StationVisualElement(Controller controller, int maxElements,
                                     float viewRange) {
            this.controller = controller;
            this.furniture = controller.bukkitFurniture;
            this.maxElements = maxElements;
            this.viewRange = viewRange;
            this.entityIds = new int[maxElements];
            this.entityUuids = new UUID[maxElements];
            for (int index = 0; index < maxElements; index++) {
                entityIds[index] = EntityUtils.ENTITY_COUNTER.incrementAndGet();
                entityUuids[index] = VirtualEntityIdentity.fromEntityId(entityIds[index]);
            }
            this.removePacket = ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(
                    new IntArrayList(entityIds));
        }

        private static void prewarm() {
        }

        @Override
        public void gatherInteractableEntityId(Consumer<Integer> collector) {
        }

        @Override
        public void show(Player player) {
            sendVisuals(player, false);
        }

        @Override
        public void hide(Player player) {
            player.sendPacket(removePacket, false);
        }

        @Override
        public void update(Player player) {
            sendVisuals(player, true);
        }

        private void sendVisuals(Player player, boolean replace) {
            List<Visual> current = controller.visuals();
            int count = Math.min(maxElements, current.size());
            if (count == 0) {
                if (replace) {
                    hide(player);
                }
                return;
            }

            List<Object> packets = new ArrayList<>(count * 2 + (replace ? 1 : 0));
            if (replace) {
                packets.add(removePacket);
            }
            for (int index = 0; index < count; index++) {
                Visual visual = current.get(index);
                if (visual.item().isEmpty()) {
                    continue;
                }
                packets.add(ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                        entityIds[index], entityUuids[index],
                        visual.x(), visual.y(), visual.z(),
                        visual.xRot(), visual.yRot(),
                        EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0));

                List<Object> metadata = new ArrayList<>(5);
                DisplayData.ItemDisplayData.ItemStack.addEntityData(
                        visual.item().minecraftItem(), metadata);
                DisplayData.ItemDisplayData.ItemTransform.addEntityDataIfNotDefaultValue(
                        visual.itemTransform(), metadata);
                DisplayData.Scale.addEntityDataIfNotDefaultValue(
                        new Vector3f(visual.scale()), metadata);
                DisplayData.LeftRotation.addEntityDataIfNotDefaultValue(
                        visual.leftRotation(), metadata);
                DisplayData.ViewRange.addEntityDataIfNotDefaultValue(
                        (float) (viewRange * player.displayEntityViewDistance()), metadata);
                packets.add(ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                        entityIds[index], metadata));
            }
            if (!packets.isEmpty()) {
                player.sendPackets(packets, false);
            }
        }
    }
}
