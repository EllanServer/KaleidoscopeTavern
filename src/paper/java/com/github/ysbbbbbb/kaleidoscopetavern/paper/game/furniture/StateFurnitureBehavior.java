package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviors;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;

import java.util.concurrent.atomic.AtomicBoolean;

/** Stores Tavern business state inside CraftEngine's furniture custom data. */
public final class StateFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:state_furniture";

    private static final String DATA_KEY = "kaleidoscope_tavern:state";
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private StateFurnitureBehavior(FurnitureDefinition furniture, ConfigSection section) {
        super(furniture);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(Key.of(TYPE), StateFurnitureBehavior::new);
        }
    }

    public static StateController state(BukkitFurniture furniture) {
        // The migration always emits state_furniture as behavior index zero.
        StateController controller = furniture.controller.get(StateController.class, 0);
        if (controller == null) {
            throw new IllegalStateException(
                    "Furniture is missing its CE state controller: " + furniture.id());
        }
        return controller;
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("State furniture requires BukkitFurniture");
        }
        return new StateController(bukkitFurniture);
    }

    public static final class StateController extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;
        private CompoundTag state = new CompoundTag();

        private StateController(BukkitFurniture furniture) {
            super(furniture);
            this.bukkitFurniture = furniture;
        }

        @Override
        public void loadCustomData(CompoundTag data) {
            CompoundTag loaded = data.getCompound(DATA_KEY);
            state = loaded == null ? new CompoundTag() : loaded.deepClone();
        }

        @Override
        public void saveCustomData(CompoundTag data) {
            if (state.isEmpty()) {
                data.remove(DATA_KEY);
            } else {
                data.put(DATA_KEY, state.deepClone());
            }
        }

        public CompoundTag data() {
            return state;
        }

        public void markChanged() {
            bukkitFurniture.setUnsaved();
        }
    }
}
