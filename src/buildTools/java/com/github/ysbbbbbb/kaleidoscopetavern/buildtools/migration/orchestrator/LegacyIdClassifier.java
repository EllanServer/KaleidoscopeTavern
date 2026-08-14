package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.orchestrator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Reproduces the legacy placeable block/furniture partition. */
public final class LegacyIdClassifier {
    private static final Set<String> INCENSE = Set.of("sakura_incense","pine_incense","ginkgo_incense","spore_incense","catnip_incense","snow_incense","butterfly_incense","firefly_incense");
    private static final Set<String> STORAGE = Set.of("bar_cabinet","glass_bar_cabinet","cellar_cabinet","tilted_rack","circular_rack","holder");
    private static final Set<String> CONNECTED = Set.of("bar_counter", "table");
    private static final Set<String> DIRECT = Set.of("tap","chalkboard","pressing_tub","wild_grapevine","wild_grapevine_plant","trellis","grapevine_trellis","grape_crop");
    private static final Set<String> SOFAS = sofaIds();

    public Classification classify(List<String> legacyIds) {
        List<String> blocks = new ArrayList<>();
        List<String> furniture = new ArrayList<>();
        List<String> sofas = new ArrayList<>();
        for (String id : legacyIds) {
            if (isGridBlock(id)) blocks.add(id);
            else if (SOFAS.contains(id)) sofas.add(id);
            else furniture.add(id);
        }
        return new Classification(blocks, furniture, sofas);
    }

    public boolean isGridBlock(String id) {
        return INCENSE.contains(id) || STORAGE.contains(id) || CONNECTED.contains(id) || DIRECT.contains(id)
                || id.endsWith("_grapevine_trellis") || id.endsWith("_grape_crop");
    }

    private static Set<String> sofaIds() {
        Set<String> result = new LinkedHashSet<>();
        for (String color : List.of("white","orange","magenta","light_blue","yellow","lime","pink","gray","light_gray","cyan","purple","blue","brown","green","red","black"))
            result.add(color + "_sofa");
        return Set.copyOf(result);
    }

    public record Classification(List<String> blockIds, List<String> furnitureIds, List<String> sofaIds) {
        public Classification { blockIds=List.copyOf(blockIds); furnitureIds=List.copyOf(furnitureIds); sofaIds=List.copyOf(sofaIds); }
    }
}
