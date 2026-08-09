package com.github.ysbbbbbb.kaleidoscopetavern.paper.recipe;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.BarrelRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.ShakerRecipe;

import java.util.List;
import java.util.Objects;

/** Immutable data model decoded from barrel.yml and shaker.yml. */
public record StationRecipeSet(BarrelFallback fallback,
                               List<BarrelRecipe> barrelRecipes,
                               ShakerSpecialResults specialResults,
                               List<ShakerRecipe> shakerRecipes) {
    public StationRecipeSet {
        Objects.requireNonNull(fallback, "fallback");
        barrelRecipes = List.copyOf(barrelRecipes);
        Objects.requireNonNull(specialResults, "specialResults");
        shakerRecipes = List.copyOf(shakerRecipes);
    }

    public record BarrelFallback(String id, String result, int unitTicks, int output) {
        public BarrelFallback {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(result, "result");
        }
    }

    public record ShakerSpecialResults(String mystery, String signature) {
        public ShakerSpecialResults {
            Objects.requireNonNull(mystery, "mystery");
            Objects.requireNonNull(signature, "signature");
        }
    }
}
