package com.github.ysbbbbbb.kaleidoscopetavern.paper.recipe;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.BarrelRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.Selector;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.ShakerRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.recipe.StationRecipeSet.BarrelFallback;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.recipe.StationRecipeSet.ShakerSpecialResults;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Atomically replaceable runtime view of configurable station recipes. */
public final class StationRecipeRegistry {
    private final ContentCatalog content;
    private volatile Snapshot snapshot;

    public StationRecipeRegistry(ContentCatalog content, StationRecipeSet recipes) {
        this.content = Objects.requireNonNull(content, "content");
        replace(recipes);
    }

    /** Builds and validates a complete snapshot before making it visible. */
    public void replace(StationRecipeSet recipes) {
        snapshot = Snapshot.create(content, Objects.requireNonNull(recipes, "recipes"));
    }

    public Optional<BarrelRecipe> barrel(String fluid, List<String> ingredients) {
        Map<IngredientKey, BarrelRecipe> byIngredients =
                snapshot.barrelByIngredients().get(fluid);
        return byIngredients == null
                ? Optional.empty()
                : Optional.ofNullable(byIngredients.get(IngredientKey.of(ingredients)));
    }

    /**
     * Whether a full, closed barrel has all inputs needed to resolve its batch.
     *
     * <p>An exact recipe is ready immediately. A strict ingredient prefix waits
     * for the missing inputs instead of entering the sparse ticker and turning
     * into vinegar. A combination that can no longer become any recipe is
     * complete for the configured fallback result.</p>
     */
    public boolean canBeginBarrel(String fluid, List<String> ingredients) {
        if (fluid == null || fluid.isBlank()) {
            return false;
        }
        IngredientKey key = IngredientKey.of(ingredients);
        Map<IngredientKey, BarrelRecipe> exact =
                snapshot.barrelByIngredients().get(fluid);
        if (exact != null && exact.containsKey(key)) {
            return true;
        }
        Set<IngredientKey> candidates = snapshot.barrelPartial().get(fluid);
        return candidates == null || !candidates.contains(key);
    }

    public Optional<BarrelRecipe> barrelById(String recipeId) {
        return Optional.ofNullable(snapshot.barrelById().get(recipeId));
    }

    public Optional<ShakerRecipe> shaker(List<String> ingredients) {
        return Optional.ofNullable(
                snapshot.shakerByIngredients().get(IngredientKey.of(ingredients)));
    }

    public boolean mayBeShakerIngredient(List<String> currentIngredients, String candidate) {
        List<String> proposed = new ArrayList<>(currentIngredients);
        proposed.add(candidate);
        return snapshot.shakerPartial().contains(IngredientKey.of(proposed));
    }

    /** Complete custom recipes may use 1-3 items; three items keep source fallback semantics. */
    public boolean canMixShaker(List<String> ingredients) {
        if (ingredients.isEmpty() || ingredients.size() > 3) {
            return false;
        }
        return ingredients.size() == 3 || shaker(ingredients).isPresent();
    }

    public BarrelFallback fallback() {
        return snapshot.fallback();
    }

    public ShakerSpecialResults specialResults() {
        return snapshot.specialResults();
    }

    public List<BarrelRecipe> barrelRecipes() {
        return snapshot.barrelRecipes();
    }

    public List<ShakerRecipe> shakerRecipes() {
        return snapshot.shakerRecipes();
    }

    private record Snapshot(BarrelFallback fallback,
                            List<BarrelRecipe> barrelRecipes,
                            Map<String, BarrelRecipe> barrelById,
                            Map<String, Map<IngredientKey, BarrelRecipe>> barrelByIngredients,
                            Map<String, Set<IngredientKey>> barrelPartial,
                            ShakerSpecialResults specialResults,
                            List<ShakerRecipe> shakerRecipes,
                            Map<IngredientKey, ShakerRecipe> shakerByIngredients,
                            Set<IngredientKey> shakerPartial) {
        private static Snapshot create(ContentCatalog content, StationRecipeSet source) {
            List<BarrelRecipe> barrel = List.copyOf(source.barrelRecipes());
            Map<String, BarrelRecipe> byId = new LinkedHashMap<>();
            Map<String, Map<IngredientKey, BarrelRecipe>> barrelIngredients =
                    new LinkedHashMap<>();
            Map<String, Set<IngredientKey>> barrelPartial = new LinkedHashMap<>();
            for (BarrelRecipe recipe : barrel) {
                BarrelRecipe previous = byId.putIfAbsent(recipe.id(), recipe);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate barrel recipe id " + recipe.id());
                }
                Set<IngredientKey> keys = ingredientKeys(content, recipe.ingredients(), false);
                if (keys.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Barrel recipe has an empty or unknown ingredient tag: " + recipe.id());
                }
                Map<IngredientKey, BarrelRecipe> exact = barrelIngredients.computeIfAbsent(
                        recipe.fluid(), ignored -> new LinkedHashMap<>());
                keys.forEach(key -> exact.putIfAbsent(key, recipe));
                barrelPartial.computeIfAbsent(recipe.fluid(), ignored -> new LinkedHashSet<>())
                        .addAll(ingredientKeys(content, recipe.ingredients(), true));
            }
            if (byId.containsKey(source.fallback().id())) {
                throw new IllegalArgumentException(
                        "Barrel fallback id conflicts with a recipe: " + source.fallback().id());
            }
            barrelIngredients.replaceAll(
                    (fluid, index) -> Collections.unmodifiableMap(index));
            barrelPartial.replaceAll(
                    (fluid, index) -> Collections.unmodifiableSet(index));
            Map<String, ShakerRecipe> shakerIds = new LinkedHashMap<>();
            List<ShakerRecipe> shaker = List.copyOf(source.shakerRecipes());
            Map<IngredientKey, ShakerRecipe> shakerIngredients = new LinkedHashMap<>();
            Set<IngredientKey> shakerPartials = new LinkedHashSet<>();
            for (ShakerRecipe recipe : shaker) {
                ShakerRecipe previous = shakerIds.putIfAbsent(recipe.id(), recipe);
                if (previous != null) {
                    throw new IllegalArgumentException("Duplicate shaker recipe id " + recipe.id());
                }
                Set<IngredientKey> keys = ingredientKeys(content, recipe.ingredients(), false);
                if (keys.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Shaker recipe has an empty or unknown ingredient tag: " + recipe.id());
                }
                keys.forEach(key -> shakerIngredients.putIfAbsent(key, recipe));
                shakerPartials.addAll(ingredientKeys(content, recipe.ingredients(), true));
            }
            return new Snapshot(
                    source.fallback(), barrel, Map.copyOf(byId),
                    Collections.unmodifiableMap(barrelIngredients),
                    Collections.unmodifiableMap(barrelPartial),
                    source.specialResults(), shaker,
                    Collections.unmodifiableMap(shakerIngredients),
                    Collections.unmodifiableSet(shakerPartials));
        }

        private static Set<IngredientKey> ingredientKeys(
                ContentCatalog content, List<Selector> selectors, boolean includePartial) {
            Set<IngredientKey> result = new LinkedHashSet<>();
            int fullMask = (1 << selectors.size()) - 1;
            int firstMask = includePartial ? 0 : fullMask;
            for (int mask = firstMask; mask <= fullMask; mask++) {
                expandIngredientKey(
                        content, selectors, mask, 0, new ArrayList<>(), result);
            }
            return result;
        }

        private static void expandIngredientKey(
                ContentCatalog content, List<Selector> selectors, int selectedMask,
                int index, List<String> ingredients, Set<IngredientKey> result) {
            if (index == selectors.size()) {
                result.add(IngredientKey.of(ingredients));
                return;
            }
            if ((selectedMask & 1 << index) == 0) {
                expandIngredientKey(
                        content, selectors, selectedMask, index + 1, ingredients, result);
                return;
            }
            Selector selector = selectors.get(index);
            Set<String> members = selector.kind() == ContentCatalog.SelectorKind.ITEM
                    ? Set.of(selector.value())
                    : content.tag(selector.value());
            for (String member : members) {
                ingredients.add(member);
                expandIngredientKey(
                        content, selectors, selectedMask, index + 1, ingredients, result);
                ingredients.removeLast();
            }
        }
    }

    private record IngredientKey(List<String> ingredients) {
        private static IngredientKey of(List<String> ingredients) {
            List<String> canonical = new ArrayList<>(ingredients);
            canonical.sort(String::compareTo);
            return new IngredientKey(List.copyOf(canonical));
        }
    }
}
