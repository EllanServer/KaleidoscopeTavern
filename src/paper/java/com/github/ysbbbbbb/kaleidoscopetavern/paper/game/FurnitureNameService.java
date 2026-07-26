package com.github.ysbbbbbb.kaleidoscopetavern.paper.game;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.item.ItemService;
import net.momirealms.craftengine.bukkit.api.CraftEngineFurniture;
import net.momirealms.craftengine.bukkit.api.event.FurniturePlaceEvent;
import net.momirealms.craftengine.bukkit.entity.furniture.BukkitFurniture;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Names every furniture meta ItemDisplay after its furniture item.
 *
 * <p>Multi-element furniture keeps its visuals on child element displays, so
 * the meta display carries no item; Waila-style mods (Jade) then fall back to
 * the raw "Item Display" entity type. An invisible custom name keeps the
 * hover readable everywhere without touching the resource pack.
 */
public final class FurnitureNameService implements Listener {
    private static final String PREFIX = "kaleidoscope_tavern:";
    private final ItemService items;

    public FurnitureNameService(ItemService items) {
        this.items = items;
    }

    public void start() {
        for (World world : Bukkit.getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (CraftEngineFurniture.isFurniture(display)) {
                    name(CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(FurniturePlaceEvent event) {
        name(event.furniture());
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemDisplay display && CraftEngineFurniture.isFurniture(display)) {
                name(CraftEngineFurniture.getLoadedFurnitureByMetaEntity(display));
            }
        }
    }

    private void name(BukkitFurniture furniture) {
        // Tavern furniture only, and strictly once: the custom name persists
        // in the entity NBT, so every later load exits on the null check and
        // no per-load work accumulates.
        if (furniture == null || !furniture.isValid()
                || !furniture.id().toString().startsWith(PREFIX)
                || !(furniture.bukkitEntity() instanceof ItemDisplay display)
                || display.customName() != null) {
            return;
        }
        // The furniture item's effective name is the already-localised
        // <lang:...> component; custom-name-visible stays false so no tag
        // renders in the world.
        items.build(furniture.id().toString(), null)
                .ifPresent(stack -> display.customName(stack.effectiveName()));
    }
}
