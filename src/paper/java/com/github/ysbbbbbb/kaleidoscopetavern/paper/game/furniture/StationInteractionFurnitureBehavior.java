package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.furniture;

import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import net.momirealms.craftengine.core.entity.furniture.Furniture;
import net.momirealms.craftengine.core.entity.furniture.FurnitureDefinition;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviorTemplate;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureBehaviors;
import net.momirealms.craftengine.core.entity.furniture.behavior.FurnitureController;
import net.momirealms.craftengine.core.entity.furniture.hitbox.FurnitureHitBox;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.InteractEntityContext;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Routes pressing, barrel and shaker use through the owning CE furniture instance. */
public final class StationInteractionFurnitureBehavior extends FurnitureBehaviorTemplate {
    public static final String TYPE = "kaleidoscope_tavern:station_interaction_furniture";

    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private static volatile Handler handler;

    private StationInteractionFurnitureBehavior(FurnitureDefinition furniture,
                                                ConfigSection section) {
        super(furniture);
    }

    public static void register() {
        if (REGISTERED.compareAndSet(false, true)) {
            FurnitureBehaviors.register(Key.of(TYPE), StationInteractionFurnitureBehavior::new);
        }
    }

    public static void bind(Handler newHandler) {
        handler = Objects.requireNonNull(newHandler, "newHandler");
    }

    public static void unbind(Handler oldHandler) {
        if (handler == oldHandler) {
            handler = null;
        }
    }

    @Override
    public FurnitureController createController(Furniture furniture) {
        if (!(furniture instanceof BukkitFurniture bukkitFurniture)) {
            throw new IllegalArgumentException("Station interaction requires BukkitFurniture");
        }
        return new Controller(bukkitFurniture);
    }

    @FunctionalInterface
    public interface Handler {
        InteractionResult interact(BukkitFurniture furniture,
                                   InteractEntityContext context);
    }

    private static final class Controller extends FurnitureController {
        private final BukkitFurniture bukkitFurniture;

        private Controller(BukkitFurniture furniture) {
            super(furniture);
            this.bukkitFurniture = furniture;
        }

        @Override
        public InteractionResult useOnFurniture(FurnitureHitBox hitBox,
                                                InteractEntityContext context) {
            Handler current = handler;
            return current == null
                    ? InteractionResult.PASS
                    : current.interact(bukkitFurniture, context);
        }
    }
}
