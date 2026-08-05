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
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p>Refreshes are differential: the packet-only entities keep their ids
 * between versions, so a content change only re-sends changed metadata,
 * moves moved entities, spawns new slots and removes dropped ones instead of
 * destroying and recreating the whole pile for every tracking player. Packet
 * objects that do not depend on the viewer are prepared once per snapshot and
 * shared across players; only the first-time view-range metadata stays
 * per-player.</p>
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
        /** Builds at most {@code limit} visuals (including the fluid slot). */
        List<Visual> visuals(BukkitFurniture furniture, int limit);
    }

    /**
     * Immutable visual description. The left rotation is stored as four floats
     * so snapshots can share one record across every tracking player without
     * copying a mutable {@link Quaternionf} per visual per refresh.
     */
    public record Visual(Item item, double x, double y, double z,
                         float yRot, float xRot, float scale,
                         float rotW, float rotX, float rotY, float rotZ,
                         byte itemTransform) {
        public Visual {
            Objects.requireNonNull(item, "item");
        }

        public static Visual of(Item item, double x, double y, double z,
                                float yRot, float xRot, float scale,
                                Quaternionf leftRotation, byte itemTransform) {
            return new Visual(item, x, y, z, yRot, xRot, scale,
                    leftRotation.w, leftRotation.x, leftRotation.y, leftRotation.z,
                    itemTransform);
        }

        public Quaternionf leftRotation() {
            return new Quaternionf(rotW, rotX, rotY, rotZ);
        }
    }

    private record VisualSnapshot(long generation, List<Visual> visuals) {
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private final int maxElements;
        private final float viewRange;
        private VisualSnapshot previousSnapshot;
        private VisualSnapshot currentSnapshot;
        private boolean visualsDirty = true;
        private long generation;

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
            previousSnapshot = currentSnapshot;
            currentSnapshot = null;
            visualsDirty = true;
        }

        /** Lazily builds the current snapshot and returns it. */
        private VisualSnapshot currentSnapshot() {
            if (visualsDirty) {
                Handler currentHandler = handler;
                List<Visual> visuals = currentHandler == null
                        ? List.of()
                        : List.copyOf(currentHandler.visuals(bukkitFurniture, maxElements));
                currentSnapshot = new VisualSnapshot(++generation, visuals);
                visualsDirty = false;
            }
            return currentSnapshot;
        }

        private VisualSnapshot previousSnapshot() {
            return previousSnapshot;
        }
    }

    private static final class StationVisualElement implements FurnitureElement {
        private final Controller controller;
        private final BukkitFurniture furniture;
        private final int maxElements;
        private final float viewRange;
        private int[] entityIds = new int[8];
        private UUID[] entityUuids = new UUID[8];
        private int allocated;
        private final Map<Player, Long> playerGenerations = new IdentityHashMap<>();
        private final Map<Player, Integer> playerVisibleCount = new IdentityHashMap<>();
        private long preparedGeneration = -1;
        private PreparedVisual[] prepared = new PreparedVisual[0];

        private StationVisualElement(Controller controller, int maxElements,
                                     float viewRange) {
            this.controller = controller;
            this.furniture = controller.bukkitFurniture;
            this.maxElements = maxElements;
            this.viewRange = viewRange;
        }

        private static void prewarm() {
        }

        private void ensureIdentityCapacity(int required) {
            if (required > entityIds.length) {
                int capacity = Math.max(required, entityIds.length << 1);
                entityIds = Arrays.copyOf(entityIds, capacity);
                entityUuids = Arrays.copyOf(entityUuids, capacity);
            }
            while (allocated < required) {
                int id = EntityUtils.ENTITY_COUNTER.incrementAndGet();
                entityIds[allocated] = id;
                entityUuids[allocated] = VirtualEntityIdentity.fromEntityId(id);
                allocated++;
            }
        }

        /** Prepares viewer-independent packets once per snapshot and shares them. */
        private PreparedVisual[] prepared(VisualSnapshot snapshot) {
            if (preparedGeneration != snapshot.generation()) {
                List<Visual> visuals = snapshot.visuals();
                int count = Math.min(maxElements, visuals.size());
                ensureIdentityCapacity(count);
                PreparedVisual[] result = new PreparedVisual[count];
                for (int index = 0; index < count; index++) {
                    Visual visual = visuals.get(index);
                    result[index] = new PreparedVisual(visual,
                            spawnPacket(index, visual),
                            staticMetadataPacket(index, visual),
                            positionPacket(index, visual));
                }
                prepared = result;
                preparedGeneration = snapshot.generation();
            }
            return prepared;
        }

        private Object spawnPacket(int index, Visual visual) {
            return ClientboundAddEntityPacketProxy.INSTANCE.newInstance(
                    entityIds[index], entityUuids[index],
                    visual.x(), visual.y(), visual.z(),
                    visual.xRot(), visual.yRot(),
                    EntityTypesProxy.ITEM_DISPLAY, 0, Vec3Proxy.ZERO, 0);
        }

        private Object staticMetadataPacket(int index, Visual visual) {
            List<Object> metadata = new ArrayList<>(4);
            DisplayData.ItemDisplayData.ItemStack.addEntityData(
                    visual.item().minecraftItem(), metadata);
            DisplayData.ItemDisplayData.ItemTransform.addEntityDataIfNotDefaultValue(
                    visual.itemTransform(), metadata);
            DisplayData.Scale.addEntityDataIfNotDefaultValue(
                    new Vector3f(visual.scale()), metadata);
            DisplayData.LeftRotation.addEntityDataIfNotDefaultValue(
                    visual.leftRotation(), metadata);
            return ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                    entityIds[index], metadata);
        }

        private Object positionPacket(int index, Visual visual) {
            return EntityUtils.createUpdatePosPacket(
                    entityIds[index],
                    visual.x(), visual.y(), visual.z(),
                    visual.yRot(), visual.xRot(), false);
        }

        private Object viewRangePacket(int index, Player player) {
            List<Object> metadata = new ArrayList<>(1);
            DisplayData.ViewRange.addEntityDataIfNotDefaultValue(
                    (float) (viewRange * player.displayEntityViewDistance()), metadata);
            return metadata.isEmpty() ? null
                    : ClientboundSetEntityDataPacketProxy.INSTANCE.newInstance(
                            entityIds[index], metadata);
        }

        private Object removePacket(int from, int to) {
            IntArrayList ids = new IntArrayList(to - from);
            for (int index = from; index < to; index++) {
                ids.add(entityIds[index]);
            }
            return ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance(ids);
        }

        @Override
        public void gatherInteractableEntityId(Consumer<Integer> collector) {
        }

        @Override
        public void show(Player player) {
            VisualSnapshot snapshot = controller.currentSnapshot();
            PreparedVisual[] current = prepared(snapshot);
            List<Object> packets = new ArrayList<>(current.length * 3);
            for (int index = 0; index < current.length; index++) {
                PreparedVisual entry = current[index];
                if (entry.visual().item().isEmpty()) {
                    continue;
                }
                packets.add(entry.spawnPacket());
                packets.add(entry.staticMetadata());
                Object viewRange = viewRangePacket(index, player);
                if (viewRange != null) {
                    packets.add(viewRange);
                }
            }
            if (!packets.isEmpty()) {
                player.sendPackets(packets, false);
            }
            playerGenerations.put(player, snapshot.generation());
            playerVisibleCount.put(player, current.length);
        }

        @Override
        public void hide(Player player) {
            Integer count = playerVisibleCount.remove(player);
            if (count != null && count > 0) {
                player.sendPacket(removePacket(0, count), false);
            }
            playerGenerations.remove(player);
        }

        @Override
        public void update(Player player) {
            VisualSnapshot current = controller.currentSnapshot();
            VisualSnapshot previous = controller.previousSnapshot();
            Long lastGeneration = playerGenerations.get(player);
            int lastCount = playerVisibleCount.getOrDefault(player, 0);
            if (lastGeneration == null || previous == null
                    || previous.generation() != lastGeneration) {
                // First sight or more than one version behind: resend everything.
                show(player);
                return;
            }

            PreparedVisual[] currentPrepared = prepared(current);
            List<Visual> oldVisuals = previous.visuals();
            List<Object> packets = new ArrayList<>(
                    currentPrepared.length * 2 + (lastCount > currentPrepared.length ? 1 : 0));
            int common = Math.min(lastCount, currentPrepared.length);
            for (int index = 0; index < common; index++) {
                PreparedVisual entry = currentPrepared[index];
                Visual oldVisual = oldVisuals.get(index);
                Visual newVisual = entry.visual();
                if (oldVisual.item().isEmpty() || newVisual.item().isEmpty()) {
                    continue;
                }
                if (positionChanged(oldVisual, newVisual)) {
                    packets.add(entry.positionPacket());
                }
                if (metadataChanged(oldVisual, newVisual)) {
                    packets.add(entry.staticMetadata());
                }
            }
            for (int index = common; index < currentPrepared.length; index++) {
                PreparedVisual entry = currentPrepared[index];
                if (entry.visual().item().isEmpty()) {
                    continue;
                }
                packets.add(entry.spawnPacket());
                packets.add(entry.staticMetadata());
                Object viewRange = viewRangePacket(index, player);
                if (viewRange != null) {
                    packets.add(viewRange);
                }
            }
            if (lastCount > currentPrepared.length) {
                packets.add(removePacket(currentPrepared.length, lastCount));
            }
            playerGenerations.put(player, current.generation());
            playerVisibleCount.put(player, currentPrepared.length);
            if (!packets.isEmpty()) {
                player.sendPackets(packets, false);
            }
        }

        private static boolean positionChanged(Visual oldVisual, Visual newVisual) {
            return oldVisual.x() != newVisual.x()
                    || oldVisual.y() != newVisual.y()
                    || oldVisual.z() != newVisual.z()
                    || oldVisual.yRot() != newVisual.yRot()
                    || oldVisual.xRot() != newVisual.xRot();
        }

        private static boolean metadataChanged(Visual oldVisual, Visual newVisual) {
            return !Objects.equals(oldVisual.item(), newVisual.item())
                    || oldVisual.itemTransform() != newVisual.itemTransform()
                    || oldVisual.scale() != newVisual.scale()
                    || oldVisual.rotW() != newVisual.rotW()
                    || oldVisual.rotX() != newVisual.rotX()
                    || oldVisual.rotY() != newVisual.rotY()
                    || oldVisual.rotZ() != newVisual.rotZ();
        }
    }

    private record PreparedVisual(Visual visual, Object spawnPacket,
                                  Object staticMetadata, Object positionPacket) {
    }
}
