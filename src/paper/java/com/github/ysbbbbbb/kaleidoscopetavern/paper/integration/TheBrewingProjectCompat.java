package com.github.ysbbbbbb.kaleidoscopetavern.paper.integration;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/**
 * Soft compatibility boundary with TheBrewingProject.
 * <p>
 * TBP seals brews by sneaking and right-clicking a crafting table with
 * paper in the off hand. Tavern therefore lets any TBP brew bypass its
 * CraftEngine vanilla-potion placement behavior. Every TBP brew, sealed
 * or not, is a vanilla potion carrying the
 * {@code brewery:version} PDC key
 * ({@code BrewAdapterAccess#applyBrewData}), which is all this boundary
 * needs; no hard dependency on TheBrewingProject is introduced.
 */
public final class TheBrewingProjectCompat {
    private static final String PLUGIN_NAME = "TheBrewingProject";
    private static final NamespacedKey BREWERY_DATA_VERSION = new NamespacedKey("brewery", "version");

    /**
     * Resolved lazily because plugin enable order is not guaranteed; every
     * plugin is enabled before the first player interaction can occur.
     * Written exactly once with an idempotent value, volatile keeps the
     * read cheap on Folia region threads.
     */
    private static volatile Boolean installed;

    private TheBrewingProjectCompat() {
    }

    /**
     * True when {@code stack} is a TheBrewingProject brew. Costs a single
     * cached boolean read when TBP is absent; otherwise one material check
     * plus one PDC lookup, short-circuited in that order.
     */
    public static boolean isBrew(ItemStack stack) {
        if (stack.getType() != Material.POTION || !installed()) {
            return false;
        }
        return stack.getPersistentDataContainer().has(BREWERY_DATA_VERSION);
    }

    private static boolean installed() {
        Boolean resolved = installed;
        if (resolved == null) {
            resolved = Bukkit.getPluginManager().isPluginEnabled(PLUGIN_NAME);
            installed = resolved;
        }
        return resolved;
    }
}
