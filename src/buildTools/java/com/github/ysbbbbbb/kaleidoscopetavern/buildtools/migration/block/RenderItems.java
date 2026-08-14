package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.block;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic private render-item naming and language-key lookup. */
final class RenderItems {
    private static final String NAMESPACE = "kaleidoscope_tavern";
    private final Set<String> languageKeys;

    RenderItems(Path projectRoot) throws IOException {
        Path langFile = projectRoot.resolve(
                "src/main/resources/assets/" + NAMESPACE + "/lang/en_us.json");
        JsonObject lang = JsonParser.parseString(Files.readString(langFile, StandardCharsets.UTF_8))
                .getAsJsonObject();
        this.languageKeys = new LinkedHashSet<>(lang.keySet());
    }

    /** Progressive word-dropping display name lookup (render_item_name). */
    String renderItemName(String referenceId) {
        String[] parts = referenceId.split("_");
        for (int start = 0; start < parts.length; start++) {
            StringBuilder name = new StringBuilder();
            for (int i = start; i < parts.length; i++) {
                if (i > start) name.append('_');
                name.append(parts[i]);
            }
            String candidateBase = name.toString();
            for (String prefix : List.of("block", "item")) {
                String candidate = prefix + "." + NAMESPACE + "." + candidateBase;
                if (languageKeys.contains(candidate)) return "<!i><lang:" + candidate + ">";
            }
        }
        throw new IllegalArgumentException("No display-name translation for render item " + referenceId);
    }

    /** ensure_render_item: SHA-1 digest over the model tuple, first 10 hex. */
    String ensure(JsonObject renderItems, String blockId, String label,
                  BlockStateVariants.Model model) {
        String digest;
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-1")
                    .digest(model.digestInput().getBytes(StandardCharsets.UTF_8));
            digest = java.util.HexFormat.of().formatHex(bytes).substring(0, 10);
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
        String renderId = NAMESPACE + ":_render/" + blockId + "/" + digest;
        if (!renderItems.has(renderId)) {
            JsonObject item = new JsonObject();
            item.addProperty("material", "paper");
            JsonObject data = new JsonObject();
            data.addProperty("item_name", renderItemName(blockId));
            item.add("data", data);
            JsonObject modelConfig = new JsonObject();
            modelConfig.addProperty("type", "minecraft:model");
            modelConfig.addProperty("path", model.model());
            item.add("model", modelConfig);
            JsonObject settings = new JsonObject();
            JsonArray tags = new JsonArray();
            tags.add(NAMESPACE + ":internal_render_items");
            settings.add("tags", tags);
            item.add("settings", settings);
            renderItems.add(renderId, item);
        }
        return renderId;
    }

    /** sofa_render_id: tintable white-sofa render helper with a dye-tinted model override. */
    String sofaRenderId(JsonObject renderItems, String connection,
                        BlockStateVariants.Model model) {
        String renderId = ensure(renderItems, "white_sofa", "tintable " + connection, model);
        JsonObject modelConfig = renderItems.getAsJsonObject(renderId).getAsJsonObject("model");
        modelConfig.addProperty("path", NAMESPACE + ":block/deco/sofa/tint/" + connection);
        JsonArray tints = new JsonArray();
        JsonObject tint = new JsonObject();
        tint.addProperty("type", "minecraft:dye");
        tint.addProperty("default", 16_777_215);
        tints.add(tint);
        modelConfig.add("tints", tints);
        return renderId;
    }
}
