package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.image;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Native Java implementation of legacy image and placed-drink-model migration.
 *
 * <p>{@code projectRoot} is the checkout root. {@code outputRoot} is the
 * namespace asset directory to populate (normally
 * {@code src/paper/pack/resourcepack/assets/kaleidoscope_tavern}). The class
 * never invokes an external interpreter and never changes archived source assets.</p>
 */
public final class LegacyImageAndPlacedDrinkStage {
    public static final String NAMESPACE = "kaleidoscope_tavern";
    public static final int EMPTY_BOTTLE_GLASS_ALPHA = 176;
    public static final int SCULK_RIPPLE_ELEMENT_INDEX = 12;
    public static final String SCULK_RIPPLE_MODEL_PATH =
            "furniture/placed_drink/" + NAMESPACE + "/block/mixology/sculk_special_ripple";

    private static final Set<Integer> EMPTY_BOTTLE_CORK_RGB = Set.of(0x953616, 0xA74625, 0xD87450);
    private static final Set<String> BOTTLE_AND_GLASS_ITEMS = Set.of(
            "empty_bottle", "empty_glassware", "signature_cocktail", "mystery_cocktail",
            "white_lady", "emerald", "brass_heart", "godfather", "grasshopper",
            "screwdriver", "mojito", "allium_garden", "depth_charge", "nether_special",
            "bloody_mary", "sculk_special", "molotov", "water_bottle", "honey_bottle",
            "dragon_breath_bottle", "potion_bottle", "xp_bottle", "wine", "champagne",
            "vodka", "brandy", "carignan", "sakura_wine", "plum_wine", "whiskey",
            "ice_wine", "polaris_sweet_white", "honey_wine", "red_queen", "miners_star",
            "rum", "riesling_dry_white", "sunset_glow", "madame_shexiang",
            "sweet_berry_wine", "sherry", "mother_snow", "luminous_bride",
            "glowflower_brew", "sauvignon_blanc_dry_white", "vinegar", "watermelon_juice");
    private static final Map<String, List<Integer>> OPAQUE_ELEMENTS = opaqueElements();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path projectRoot;
    private final Path outputRoot;
    private final List<Path> sourceRoots;
    private final Map<String, Boolean> partialAlphaCache = new LinkedHashMap<>();

    public LegacyImageAndPlacedDrinkStage(Path projectRoot, Path outputRoot) {
        this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.outputRoot = Objects.requireNonNull(outputRoot, "outputRoot").toAbsolutePath().normalize();
        this.sourceRoots = List.of(this.projectRoot.resolve("src/main/resources"),
                this.projectRoot.resolve("src/generated/resources"));
    }

    /** Run the standalone empty-bottle image/model generation stage. */
    public void generateEmptyBottleOverrides() throws IOException {
        var expected = new LinkedHashMap<String, int[]>();
        expected.put("block/brew/empty_bottle", new int[]{8, 68});
        expected.put("item/empty_bottle", new int[]{6, 42});
        for (var entry : expected.entrySet()) {
            Path source = projectRoot.resolve("src/main/resources/assets/" + NAMESPACE
                    + "/textures/" + entry.getKey() + ".png");
            Path target = outputRoot.resolve("textures/" + entry.getKey() + ".png");
            BufferedImage input = readImage(source);
            BufferedImage result = new BufferedImage(input.getWidth(), input.getHeight(), BufferedImage.TYPE_INT_ARGB);
            int cork = 0;
            int glass = 0;
            for (int y = 0; y < input.getHeight(); y++) {
                for (int x = 0; x < input.getWidth(); x++) {
                    int argb = input.getRGB(x, y);
                    int alpha = argb >>> 24;
                    int rgb = argb & 0xFFFFFF;
                    int outputAlpha;
                    if (alpha == 0) outputAlpha = 0;
                    else if (EMPTY_BOTTLE_CORK_RGB.contains(rgb)) { outputAlpha = 255; cork++; }
                    else { outputAlpha = EMPTY_BOTTLE_GLASS_ALPHA; glass++; }
                    result.setRGB(x, y, (outputAlpha << 24) | rgb);
                }
            }
            int[] counts = entry.getValue();
            if (cork != counts[0] || glass != counts[1]) {
                throw new IllegalStateException(entry.getKey() + ": empty-bottle palette drifted; expected "
                        + counts[0] + "/" + counts[1] + " cork/glass texels, found " + cork + "/" + glass);
            }
            writePng(target, result);
        }
        JsonObject itemModel = new JsonObject();
        itemModel.addProperty("parent", "minecraft:item/generated");
        JsonObject textures = new JsonObject();
        textures.add("layer0", translucentTexture(NAMESPACE + ":item/empty_bottle"));
        itemModel.add("textures", textures);
        writeJson(outputRoot.resolve("models/item/empty_bottle.json"), itemModel);
    }

    /**
     * Create an ItemDisplay-safe private model copy and return an updated model tuple.
     * Non-drink ids and unresolved/non-namespaced models are returned unchanged.
     */
    public ModelReference migratePlacedDrinkModel(String blockId, ModelReference model) throws IOException {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(model, "model");
        if (!BOTTLE_AND_GLASS_ITEMS.contains(blockId)) return model;
        int colon = model.resourceId().indexOf(':');
        if (colon < 0) return model;
        String namespace = model.resourceId().substring(0, colon);
        String resourcePath = model.resourceId().substring(colon + 1);
        Path source = findFile(Path.of("assets", namespace, "models", resourcePath + ".json"));
        if (source == null) return model;

        JsonObject copied = readJson(source).deepCopy();
        JsonArray elements = arrayOrNull(copied.get("elements"));
        if (elements != null) {
            for (JsonElement rawElement : elements) {
                if (!rawElement.isJsonObject()) continue;
                JsonObject element = rawElement.getAsJsonObject();
                JsonArray from = arrayOrNull(element.get("from"));
                JsonArray to = arrayOrNull(element.get("to"));
                if (from == null || to == null || from.size() != 3 || to.size() != 3) continue;
                for (int axis = 0; axis < 3; axis++) {
                    if (from.get(axis).getAsDouble() > to.get(axis).getAsDouble()) {
                        JsonElement oldFrom = from.get(axis).deepCopy();
                        from.set(axis, to.get(axis).deepCopy());
                        to.set(axis, oldFrom);
                    }
                }
            }
        }
        if (copied.has("render_type")) migrateTranslucentModel(copied, model.resourceId());
        splitOpaquePlacedDrinkDetails(copied, blockId, model.resourceId());
        removeEmptyBottleZFighting(copied, blockId, model.resourceId());

        String privatePath = "furniture/placed_drink/" + namespace + "/" + resourcePath;
        if ("sculk_special".equals(blockId)) splitSculkRipple(copied);
        writeJson(outputRoot.resolve("models/" + privatePath + ".json"), copied);
        return model.withResourceId(NAMESPACE + ":" + privatePath);
    }

    public boolean spriteHasPartialAlpha(String sprite) throws IOException {
        Boolean cached = partialAlphaCache.get(sprite);
        if (cached != null) return cached;
        BufferedImage image = readImage(sourceTexturePath(sprite, sprite));
        boolean found = false;
        outer: for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha > 0 && alpha < 255) { found = true; break outer; }
            }
        }
        partialAlphaCache.put(sprite, found);
        return found;
    }

    public static JsonObject translucentTexture(String sprite) {
        JsonObject descriptor = new JsonObject();
        descriptor.addProperty("sprite", sprite);
        descriptor.addProperty("force_translucent", true);
        return descriptor;
    }

    public void migrateTranslucentModel(JsonObject model, String owner) throws IOException {
        JsonElement rawRenderType = model.remove("render_type");
        String renderType = rawRenderType != null && rawRenderType.isJsonPrimitive()
                ? rawRenderType.getAsString() : null;
        Set<String> allowed = Set.of("cutout", "minecraft:cutout", "translucent", "minecraft:translucent");
        require(allowed.contains(renderType), owner + ": expected Forge cutout/translucent render_type, found " + renderType);
        JsonObject textures = objectOrNull(model.get("textures"));
        require(textures != null, owner + ": missing model textures");
        Set<String> renderedSlots = new LinkedHashSet<>();
        JsonArray elements = arrayOrNull(model.get("elements"));
        if (elements != null) for (JsonElement rawElement : elements) {
            JsonObject element = objectOrNull(rawElement);
            JsonObject faces = element == null ? null : objectOrNull(element.get("faces"));
            if (faces == null) continue;
            for (JsonElement rawFace : faces.asMap().values()) {
                JsonObject face = objectOrNull(rawFace);
                JsonElement texture = face == null ? null : face.get("texture");
                if (texture != null && texture.isJsonPrimitive()) {
                    String value = texture.getAsString();
                    if (value.startsWith("#")) renderedSlots.add(value.substring(1));
                }
            }
        }
        require(!renderedSlots.isEmpty(), owner + ": translucent model has no rendered texture slots");
        boolean explicit = renderType.endsWith("translucent");
        var sortedSlots = new ArrayList<>(renderedSlots);
        sortedSlots.sort(String::compareTo);
        for (String slot : sortedSlots) {
            JsonElement rawSprite = textures.get(slot);
            require(rawSprite != null && rawSprite.isJsonPrimitive(),
                    owner + ": translucent texture slot '" + slot + "' must resolve directly");
            String sprite = rawSprite.getAsString();
            require(!sprite.startsWith("#"), owner + ": translucent texture slot '" + slot + "' must resolve directly");
            if (explicit || spriteHasPartialAlpha(sprite)) textures.add(slot, translucentTexture(sprite));
        }
    }

    private void splitOpaquePlacedDrinkDetails(JsonObject model, String blockId, String owner) throws IOException {
        List<Integer> indices = OPAQUE_ELEMENTS.get(blockId);
        if (indices == null) return;
        JsonObject textures = objectOrNull(model.get("textures"));
        JsonArray elements = arrayOrNull(model.get("elements"));
        require(textures != null && elements != null, owner + ": opaque placed-drink detail has no model data");
        List<JsonObject> targetFaces = new ArrayList<>();
        Set<String> sourceSlots = new LinkedHashSet<>();
        for (int index : indices) {
            require(index < elements.size(), owner + ": missing opaque detail element " + index);
            JsonObject element = objectOrNull(elements.get(index));
            JsonObject faces = element == null ? null : objectOrNull(element.get("faces"));
            require(faces != null && !faces.isEmpty(), owner + ": opaque detail element " + index + " has no faces");
            for (JsonElement rawFace : faces.asMap().values()) {
                JsonObject face = objectOrNull(rawFace);
                JsonElement texture = face == null ? null : face.get("texture");
                require(texture != null && texture.isJsonPrimitive() && texture.getAsString().startsWith("#"),
                        owner + ": opaque detail element " + index + " has an inline texture");
                sourceSlots.add(texture.getAsString().substring(1));
                targetFaces.add(face);
            }
        }
        require(sourceSlots.size() == 1, owner + ": opaque details must use exactly one source texture slot");
        String sourceSlot = sourceSlots.iterator().next();
        JsonObject descriptor = objectOrNull(textures.get(sourceSlot));
        require(descriptor != null && descriptor.has("sprite") && descriptor.get("sprite").isJsonPrimitive()
                        && descriptor.has("force_translucent") && descriptor.get("force_translucent").getAsBoolean(),
                owner + ": opaque detail source slot must retain forced translucency");
        require(!textures.has("opaque_detail"), owner + ": duplicate opaque_detail texture slot");
        textures.addProperty("opaque_detail", createOpaqueDetailTexture(descriptor.get("sprite").getAsString(), owner));
        for (JsonObject face : targetFaces) face.addProperty("texture", "#opaque_detail");
    }

    private String createOpaqueDetailTexture(String sprite, String owner) throws IOException {
        int colon = sprite.indexOf(':');
        require(colon >= 0, owner + ": opaque detail sprite must be namespaced");
        String namespace = sprite.substring(0, colon);
        String spritePath = sprite.substring(colon + 1);
        Path source = sourceTexturePath(sprite, owner);
        BufferedImage input = readImage(source);
        BufferedImage result = new BufferedImage(input.getWidth(), input.getHeight(), BufferedImage.TYPE_INT_ARGB);
        boolean partial = false;
        for (int y = 0; y < input.getHeight(); y++) for (int x = 0; x < input.getWidth(); x++) {
            int argb = input.getRGB(x, y);
            int alpha = argb >>> 24;
            if (alpha > 0 && alpha < 255) partial = true;
            result.setRGB(x, y, (alpha == 0 ? 0 : 0xFF000000) | (argb & 0xFFFFFF));
        }
        require(partial, owner + ": expected partially transparent source pixels in " + sprite);
        String privatePath = "furniture/placed_drink/opaque/" + namespace + "/" + spritePath;
        Path target = outputRoot.resolve("textures/" + privatePath + ".png");
        writePng(target, result);
        Path sourceMeta = Path.of(source + ".mcmeta");
        Path targetMeta = Path.of(target + ".mcmeta");
        if (Files.isRegularFile(sourceMeta)) {
            Files.createDirectories(targetMeta.getParent());
            Files.copy(sourceMeta, targetMeta, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } else Files.deleteIfExists(targetMeta);
        return NAMESPACE + ":" + privatePath;
    }

    private static void removeEmptyBottleZFighting(JsonObject model, String blockId, String owner) {
        if (!"empty_bottle".equals(blockId)) return;
        JsonArray elements = arrayOrNull(model.get("elements"));
        require(elements != null, owner + ": bottle model has no elements");
        require(elements.size() == 4, owner + ": expected four empty-bottle elements, found " + elements.size());
        JsonObject body = elements.get(2).getAsJsonObject();
        JsonObject band = elements.get(3).getAsJsonObject();
        require(arrayEquals(body.getAsJsonArray("from"), 6, 1, 6)
                        && arrayEquals(body.getAsJsonArray("to"), 10, 10, 10)
                        && arrayEquals(band.getAsJsonArray("from"), 6, 9, 6)
                        && arrayEquals(band.getAsJsonArray("to"), 10, 10, 10),
                owner + ": empty-bottle body/shoulder geometry drifted");
        JsonObject bodyFaces = objectOrNull(body.get("faces"));
        JsonObject bandFaces = objectOrNull(band.get("faces"));
        require(bodyFaces != null && bandFaces != null, owner + ": empty-bottle faces are missing");
        for (String side : List.of("north", "east", "south", "west")) {
            JsonObject face = objectOrNull(bodyFaces.get(side));
            JsonObject bandFace = objectOrNull(bandFaces.get(side));
            require(face != null && arrayEquals(face.getAsJsonArray("uv"), 9, 6, 13, 15),
                    owner + ": empty-bottle body UV " + side + " drifted");
            require(bandFace != null && arrayEquals(bandFace.getAsJsonArray("uv"), 9, 14, 13, 15),
                    owner + ": empty-bottle shoulder UV " + side + " drifted");
            face.add("uv", numericArray(9, 7, 13, 15));
        }
        JsonElement top = bodyFaces.remove("up");
        require(top != null && top.isJsonObject() && arrayEquals(top.getAsJsonObject().getAsJsonArray("uv"), 0, 0, 4, 4)
                        && !bandFaces.has("up"), owner + ": empty-bottle top face drifted");
        body.getAsJsonArray("to").set(1, new JsonPrimitive(9));
        bandFaces.add("up", top);
    }

    private void splitSculkRipple(JsonObject copied) throws IOException {
        JsonArray elements = arrayOrNull(copied.get("elements"));
        require(elements != null && elements.size() > SCULK_RIPPLE_ELEMENT_INDEX,
                "sculk_special: missing the full-block ripple model element");
        JsonObject ripple = copied.deepCopy();
        JsonArray rippleElements = new JsonArray();
        rippleElements.add(elements.get(SCULK_RIPPLE_ELEMENT_INDEX).deepCopy());
        ripple.add("elements", rippleElements);
        JsonArray remaining = new JsonArray();
        for (int i = 0; i < elements.size(); i++) if (i != SCULK_RIPPLE_ELEMENT_INDEX) remaining.add(elements.get(i));
        copied.add("elements", remaining);
        writeJson(outputRoot.resolve("models/" + SCULK_RIPPLE_MODEL_PATH + ".json"), ripple);
    }

    private Path sourceTexturePath(String sprite, String owner) throws IOException {
        int colon = sprite.indexOf(':');
        require(colon >= 0, owner + ": texture sprite must be namespaced");
        Path found = findFile(Path.of("assets", sprite.substring(0, colon), "textures",
                sprite.substring(colon + 1) + ".png"));
        if (found == null) throw new IOException(owner + ": missing source texture " + sprite);
        return found;
    }

    private Path findFile(Path relative) {
        for (Path root : sourceRoots) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private static JsonObject readJson(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static void writeJson(Path path, JsonElement value) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            GSON.toJson(value, writer);
            writer.write('\n');
        }
    }

    private static BufferedImage readImage(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) throw new IOException("Unsupported or invalid image: " + path);
        return image;
    }

    private static void writePng(Path path, BufferedImage image) throws IOException {
        Files.createDirectories(path.getParent());
        // PNG container bytes vary between Pillow and ImageIO; golden tests compare
        // decoded RGBA pixels, which are the resource-pack semantics.
        if (!ImageIO.write(image, "PNG", path.toFile())) {
            throw new IOException("No PNG writer available: " + path);
        }
    }

    private static JsonObject objectOrNull(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    private static JsonArray arrayOrNull(JsonElement value) {
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : null;
    }

    private static boolean arrayEquals(JsonArray array, int... expected) {
        if (array == null || array.size() != expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (array.get(i).getAsDouble() != expected[i]) return false;
        return true;
    }

    private static JsonArray numericArray(int... values) {
        JsonArray result = new JsonArray();
        for (int value : values) result.add(value);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static Map<String, List<Integer>> opaqueElements() {
        Map<String, List<Integer>> result = new LinkedHashMap<>();
        result.put("allium_garden", List.of(12, 13));
        result.put("bloody_mary", List.of(1, 10, 11, 12, 13));
        result.put("brass_heart", List.of(11));
        result.put("depth_charge", List.of(8, 9, 10, 11));
        result.put("emerald", List.of(5, 6, 7));
        result.put("godfather", List.of(9, 10, 11, 12, 13));
        result.put("grasshopper", List.of(0, 1, 2, 13));
        result.put("mojito", List.of(0, 10, 11, 12, 13));
        result.put("mystery_cocktail", List.of(12));
        result.put("nether_special", List.of(12, 13, 14, 15));
        result.put("screwdriver", List.of(7, 8, 9));
        result.put("sculk_special", List.of(12, 13, 14));
        result.put("signature_cocktail", List.of(12, 13, 14));
        result.put("white_lady", List.of(10, 11, 12));
        return Map.copyOf(result);
    }

    /** Immutable five-field migrated model reference. */
    public record ModelReference(String resourceId, int xRotation, int yRotation, int uvLock, boolean emissive) {
        public ModelReference {
            Objects.requireNonNull(resourceId, "resourceId");
        }
        public ModelReference withResourceId(String replacement) {
            return new ModelReference(replacement, xRotation, yRotation, uvLock, emissive);
        }
    }
}
