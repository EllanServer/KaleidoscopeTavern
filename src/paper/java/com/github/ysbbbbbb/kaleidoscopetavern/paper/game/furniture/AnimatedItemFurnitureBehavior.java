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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** CE-tracked packet-only item displays for the shaker and rotating stool body. */
public final class AnimatedItemFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:animated_item_furniture";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final Map<UUID, Controller> LOADED = new HashMap<>();
    private static final Map<Channel, Handler> HANDLERS = new EnumMap<>(Channel.class);

    private final Channel channel;
    private final int maxElements;
    private final float viewRange;

    private AnimatedItemFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
        this.channel = Channel.valueOf(section.getNonNullString("channel")
                .toUpperCase(Locale.ROOT));
        this.maxElements = Math.max(1, section.getInt("max_elements", 1));
        this.viewRange = Math.max(0.1F, section.getFloat("view_range", 1.25F));
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            VirtualEntityIdentity.prewarm();
            AnimatedItemElement.prewarm();
            FurnitureBehaviors.register(Key.of(TYPE), AnimatedItemFurnitureBehavior::new);
        }
    }

    public static void bind(Channel channel, Handler handler) {
        Channel targetChannel = Objects.requireNonNull(channel, "channel");
        HANDLERS.put(targetChannel, Objects.requireNonNull(handler, "handler"));
        refresh(targetChannel);
    }

    public static void unbind(Channel channel, Handler handler) {
        Channel targetChannel = Objects.requireNonNull(channel, "channel");
        if (HANDLERS.get(targetChannel) == handler) {
            HANDLERS.remove(targetChannel);
        }
        refresh(targetChannel);
    }

    public static void updateTransforms(BukkitFurniture furniture) {
        Controller controller = LOADED.get(furniture.uuid());
        if (controller != null) {
            controller.updateTransforms();
        }
    }

    public static void updatePosition(BukkitFurniture furniture) {
        Controller controller = LOADED.get(furniture.uuid());
        if (controller != null) {
            controller.updatePosition();
        }
    }

    private static void refresh(Channel channel) {
        for (Controller controller : LOADED.values()) {
            if (controller.channel == channel) {
                controller.refresh();
            }
        }
    }

    private static Handler handler(Channel channel) {
        return HANDLERS.get(channel);
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Animated visuals require BukkitFurniture");
        }
        return new Controller(bukkitFurniture, channel, maxElements, viewRange);
    }

    public enum Channel {
        SHAKER,
        BAR_STOOL
    }

    @FunctionalInterface
    public interface Handler {
        List<Visual> visuals(BukkitFurniture furniture);
    }

    public record Visual(Item item, float yRot, float xRot,
                         Vector3f translation, Quaternionf leftRotation,
                         float width, float height,
                         int transformDuration, int positionDuration) {
        public Visual {
            Objects.requireNonNull(item, "item");
            translation = new Vector3f(Objects.requireNonNull(
                    translation, "translation"));
            leftRotation = new Quaternionf(Objects.requireNonNull(
                    leftRotation, "leftRotation"));
        }
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private final Channel channel;
        private final int maxElements;
        private final float viewRange;
        private AnimatedItemElement element;
        private List<Visual> cachedVisuals = List.of();
        private boolean visualsDirty = true;

        private Controller(BukkitFurniture furniture, Channel channel,
                           int maxElements, float viewRange) {
            super(furniture);
            this.bukkitFurniture = furniture;
            this.channel = channel;
            this.maxElements = maxElements;
            this.viewRange = viewRange;
        }

        @Override
        public void gatherElements(Consumer<FurnitureElement> consumer) {
            invalidateVisuals();
            element = new AnimatedItemElement(
                    this, maxElements, viewRange);
            consumer.accept(element);
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
        public void onUnload() {
            LOADED.remove(bukkitFurniture.uuid(), this);
        }

        private void updateTransforms() {
            invalidateVisuals();
            if (element != null) {
                element.updateTransforms();
            }
        }

        private void updatePosition() {
            invalidateVisuals();
            if (element != null) {
                element.updatePosition();
            }
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
                Handler current = handler(channel);
                cachedVisuals = current == null
                        ? List.of()
                        : List.copyOf(current.visuals(bukkitFurniture));
                visualsDirty = false;
            }
            return cachedVisuals;
        }
    }

    private static final class AnimatedItemElement implements FurnitureElement {
        private final Controller controller;
        private final BukkitFurniture furniture;
        private final int maxElements;
        private final float viewRange;
        private final int[] entityIds;
        private final UUID[] entityUuids;
        private final Object removePacket;

        private AnimatedItemElement(Controller controller,
                                    int maxElements, float viewRange) {
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
            List<Visual> visuals = visuals();
            int count = Math.min(maxElements, visuals.size());
            if (count == 0) {
                return;
            }
            List<Object> packets = new ArrayList<>(count * 2);
            for (int index = 0; index < count; index++) {
                Visual visual = visuals.get(index);
                packets.add(spawnPacket(index, visual));
                packets.add(ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                        entityIds[index], initialMetadata(player, visual)));
            }
            player.sendPackets(packets, false);
        }

        @Override
        public void hide(Player player) {
            player.sendPacket(removePacket, false);
        }

        @Override
        public void update(Player player) {
            hide(player);
            show(player);
        }

        private void updateTransforms() {
            List<Visual> visuals = visuals();
            int count = Math.min(maxElements, visuals.size());
            if (count == 0) {
                return;
            }
            List<Object> packets = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                List<Object> metadata = new ArrayList<>(2);
                DisplayData.Translation.addEntityData(
                        new Vector3f(visuals.get(index).translation()), metadata);
                DisplayData.LeftRotation.addEntityData(
                        new Quaternionf(visuals.get(index).leftRotation()), metadata);
                packets.add(ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                        entityIds[index], metadata));
            }
            furniture.trackedBy().forEach(player -> player.sendPackets(packets, false));
        }

        private void updatePosition() {
            List<Visual> visuals = visuals();
            int count = Math.min(maxElements, visuals.size());
            if (count == 0) {
                return;
            }
            List<Object> packets = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                Visual visual = visuals.get(index);
                packets.add(EntityUtils.createUpdatePosPacket(
                        entityIds[index],
                        furniture.location().getX(), furniture.location().getY(),
                        furniture.location().getZ(), visual.yRot(), visual.xRot(), false));
            }
            furniture.trackedBy().forEach(player -> player.sendPackets(packets, false));
        }

        private List<Visual> visuals() {
            return controller.visuals();
        }

        private Object spawnPacket(int index, Visual visual) {
            return ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                    entityIds[index], entityUuids[index],
                    furniture.location().getX(), furniture.location().getY(),
                    furniture.location().getZ(), visual.xRot(), visual.yRot(),
                    EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0);
        }

        private List<Object> initialMetadata(Player player, Visual visual) {
            List<Object> metadata = new ArrayList<>(9);
            DisplayData.ItemDisplayData.ItemStack.addEntityData(
                    visual.item().minecraftItem(), metadata);
            DisplayData.Translation.addEntityDataIfNotDefaultValue(
                    new Vector3f(visual.translation()), metadata);
            DisplayData.LeftRotation.addEntityDataIfNotDefaultValue(
                    new Quaternionf(visual.leftRotation()), metadata);
            DisplayData.TransformationInterpolationDuration.addEntityDataIfNotDefaultValue(
                    visual.transformDuration(), metadata);
            DisplayData.PosRotInterpolationDuration.addEntityDataIfNotDefaultValue(
                    visual.positionDuration(), metadata);
            DisplayData.Width.addEntityDataIfNotDefaultValue(visual.width(), metadata);
            DisplayData.Height.addEntityDataIfNotDefaultValue(visual.height(), metadata);
            DisplayData.ViewRange.addEntityDataIfNotDefaultValue(
                    (float) (viewRange * player.displayEntityViewDistance()), metadata);
            return metadata;
        }
    }
}
