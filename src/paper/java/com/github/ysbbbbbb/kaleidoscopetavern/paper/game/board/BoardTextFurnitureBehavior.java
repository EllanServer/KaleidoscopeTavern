package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.board;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture.VirtualEntityIdentity;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.momirealms.craftengine.bukkit.entity.data.DisplayData;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.bukkit.util.ComponentUtils;
import net.momirealms.craftengine.bukkit.util.EntityUtils;
import net.momirealms.craftengine.core.entity.display.TextDisplayAlignment;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviors;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.element.FurnitureElement;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.entity.player.Player;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundAddEntityPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.network.protocol.game.ClientboundSetEntityDataPacketProxy;
import net.momirealms.craftengine.proxy.minecraft.world.entity.EntityTypesProxy;
import net.momirealms.craftengine.proxy.minecraft.world.phys.Vec3Proxy;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Renders persisted board text through CE-tracked packet-only text elements. */
public final class BoardTextFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:board_text_furniture";

    private static final int TRANSPARENT_BACKGROUND = 0;
    private static final byte GLOWING_ENTITY_FLAG = 0x40;
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static final ConcurrentMap<UUID, Controller> LOADED = new ConcurrentHashMap<>();
    private static volatile Handler handler;
    private static volatile InteractionHandler interactionHandler;

    private final int maxLines;
    private final float viewRange;

    private BoardTextFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
        this.maxLines = Math.max(1, section.getInt("max_lines", 1));
        this.viewRange = Math.max(0.1F, section.getFloat("view_range", 0.75F));
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            VirtualEntityIdentity.prewarm();
            BoardTextElement.prewarm();
            FurnitureBehaviors.register(Key.of(TYPE), BoardTextFurnitureBehavior::new);
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

    /** Invalidates player-independent text layout before CE redistributes it. */
    public static void refresh(BukkitFurniture furniture) {
        Objects.requireNonNull(furniture, "furniture");
        Controller controller = LOADED.get(furniture.uuid());
        if (controller != null) {
            controller.refresh();
        } else {
            furniture.refreshElements();
        }
    }

    public static void bindInteraction(InteractionHandler newHandler) {
        interactionHandler = Objects.requireNonNull(newHandler, "newHandler");
    }

    public static void unbindInteraction(InteractionHandler oldHandler) {
        if (interactionHandler == oldHandler) {
            interactionHandler = null;
        }
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Board text requires BukkitFurniture");
        }
        return new Controller(bukkitFurniture, maxLines, viewRange);
    }

    @FunctionalInterface
    public interface Handler {
        List<Visual> visuals(BukkitFurniture furniture);
    }

    @FunctionalInterface
    public interface InteractionHandler {
        InteractionResult interact(BukkitFurniture furniture,
                                   InteractEntityContext context);
    }

    public record Visual(Component text, double x, double y, double z,
                         float yRot, float xRot, float scale,
                         boolean glowing, int glowColor) {
        public Visual {
            Objects.requireNonNull(text, "text");
        }
    }

    private record PreparedVisual(Visual visual, Object minecraftText) {
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private final int maxLines;
        private final float viewRange;
        private List<PreparedVisual> cachedVisuals = List.of();
        private boolean visualsDirty = true;

        private Controller(BukkitFurniture furniture, int maxLines, float viewRange) {
            super(furniture);
            this.bukkitFurniture = furniture;
            this.maxLines = maxLines;
            this.viewRange = viewRange;
        }

        @Override
        public void gatherElements(Consumer<FurnitureElement> consumer) {
            invalidateVisuals();
            consumer.accept(new BoardTextElement(this, maxLines, viewRange));
        }

        @Override
        public InteractionResult useOnFurniture(FurnitureHitBox hitBox,
                                                InteractEntityContext context) {
            InteractionHandler current = interactionHandler;
            return current == null
                    ? InteractionResult.PASS
                    : current.interact(bukkitFurniture, context);
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

        private void refresh() {
            invalidateVisuals();
            bukkitFurniture.refreshElements();
        }

        private void invalidateVisuals() {
            cachedVisuals = List.of();
            visualsDirty = true;
        }

        private List<PreparedVisual> visuals() {
            if (visualsDirty) {
                Handler currentHandler = handler;
                if (currentHandler == null) {
                    cachedVisuals = List.of();
                } else {
                    List<Visual> current = currentHandler.visuals(bukkitFurniture);
                    List<PreparedVisual> prepared = new ArrayList<>(current.size());
                    for (Visual visual : current) {
                        prepared.add(new PreparedVisual(
                                visual, ComponentUtils.jsonToMinecraft(
                                        GsonComponentSerializer.gson().serialize(visual.text()))));
                    }
                    cachedVisuals = List.copyOf(prepared);
                }
                visualsDirty = false;
            }
            return cachedVisuals;
        }
    }

    private static final class BoardTextElement implements FurnitureElement {
        private final Controller controller;
        private final BukkitFurniture furniture;
        private final int maxLines;
        private final float viewRange;
        private final int[] entityIds;
        private final UUID[] entityUuids;
        private final Object removePacket;

        private BoardTextElement(Controller controller, int maxLines, float viewRange) {
            this.controller = controller;
            this.furniture = controller.bukkitFurniture;
            this.maxLines = maxLines;
            this.viewRange = viewRange;
            this.entityIds = new int[maxLines];
            this.entityUuids = new UUID[maxLines];
            for (int index = 0; index < maxLines; index++) {
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
            List<PreparedVisual> current = controller.visuals();
            int count = Math.min(maxLines, current.size());
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
                PreparedVisual prepared = current.get(index);
                Visual visual = prepared.visual();
                packets.add(ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                        entityIds[index], entityUuids[index],
                        visual.x(), visual.y(), visual.z(),
                        visual.xRot(), visual.yRot(),
                        EntityTypesProxy.TEXT_DISPLAY, 0, Vec3Proxy.ZERO, 0));

                List<Object> metadata = new ArrayList<>(8);
                if (visual.glowing()) {
                    DisplayData.TextDisplayData.SharedFlags.addEntityData(
                            GLOWING_ENTITY_FLAG, metadata);
                    DisplayData.TextDisplayData.GlowColorOverride.addEntityData(
                            visual.glowColor(), metadata);
                }
                DisplayData.TextDisplayData.Scale.addEntityDataIfNotDefaultValue(
                        new Vector3f(visual.scale()), metadata);
                DisplayData.TextDisplayData.Text.addEntityData(
                        prepared.minecraftText(), metadata);
                DisplayData.TextDisplayData.LineWidth.addEntityDataIfNotDefaultValue(
                        Integer.MAX_VALUE, metadata);
                DisplayData.TextDisplayData.BackgroundColor.addEntityDataIfNotDefaultValue(
                        TRANSPARENT_BACKGROUND, metadata);
                DisplayData.TextDisplayData.Flags.addEntityDataIfNotDefaultValue(
                        DisplayData.TextDisplayData.encodeFlags(
                                false, false, false, TextDisplayAlignment.CENTER), metadata);
                DisplayData.TextDisplayData.ViewRange.addEntityDataIfNotDefaultValue(
                        (float) (viewRange * player.displayEntityViewDistance()), metadata);
                packets.add(ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                        entityIds[index], metadata));
            }
            player.sendPackets(packets, false);
        }
    }
}
