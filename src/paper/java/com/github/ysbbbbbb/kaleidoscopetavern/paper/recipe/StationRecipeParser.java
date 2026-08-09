package com.github.ysbbbbbb.kaleidoscopetavern.paper.recipe;

import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.BarrelRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.Selector;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.catalog.ContentCatalog.ShakerRecipe;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.recipe.StationRecipeSet.BarrelFallback;
import com.github.ysbbbbbb.kaleidoscopetavern.paper.recipe.StationRecipeSet.ShakerSpecialResults;
import net.momirealms.sparrow.yaml.SparrowYaml;
import net.momirealms.sparrow.yaml.YamlDocument;
import net.momirealms.sparrow.yaml.node.SectionNode;
import net.momirealms.sparrow.yaml.node.SequenceNode;
import net.momirealms.sparrow.yaml.node.YamlNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict Sparrow YAML decoder for the versioned station recipe schema. */
final class StationRecipeParser {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_BARREL_INGREDIENTS = 4;
    private static final int MAX_SHAKER_INGREDIENTS = 3;
    private static final Pattern RESOURCE_ID = Pattern.compile(
            "[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final SparrowYaml YAML = SparrowYaml.builder()
            .setAllowDuplicateKeys(false)
            .build();

    private StationRecipeParser() {
    }

    static StationRecipeSet parse(Path barrelPath, Path shakerPath) throws IOException {
        YamlDocument barrel = readYaml(barrelPath);
        YamlDocument shaker = readYaml(shakerPath);
        return new StationRecipeSet(
                barrelFallback(barrel, barrelPath),
                barrelRecipes(barrel, barrelPath),
                shakerSpecialResults(shaker, shakerPath),
                shakerRecipes(shaker, shakerPath));
    }

    private static YamlDocument readYaml(Path path) throws IOException {
        YamlDocument document;
        try {
            document = YAML.load(path);
        } catch (IOException | RuntimeException exception) {
            throw new IOException(path + " 不是有效的 YAML", exception);
        }
        int version = requiredInt(document, "config-version", path, "config-version");
        if (version != FORMAT_VERSION) {
            throw new IOException(path + " 的 config-version 必须为 " + FORMAT_VERSION
                    + "，实际为 " + version);
        }
        return document;
    }

    private static BarrelFallback barrelFallback(YamlDocument yaml, Path path)
            throws IOException {
        SectionNode section = requiredSection(yaml, "fallback", path);
        String id = resourceId(requiredString(section, "id", path, "fallback"),
                path, "fallback.id");
        String result = resourceId(requiredString(section, "result", path, "fallback"),
                path, "fallback.result");
        int unitTicks = positiveInt(section, "unit-ticks", path, "fallback");
        int output = positiveInt(section, "output", path, "fallback");
        if (output > 64) {
            throw new IOException(path + " 的 fallback.output 不能大于 64");
        }
        return new BarrelFallback(
                id, result, unitTicks, output,
                optionalRgb(section, "tap-color", path, "fallback"));
    }

    private static List<BarrelRecipe> barrelRecipes(YamlDocument yaml, Path path)
            throws IOException {
        List<SectionNode> rows = recipeRows(yaml, path);
        List<BarrelRecipe> recipes = new ArrayList<>(rows.size());
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            SectionNode row = rows.get(index);
            String owner = "recipes[" + index + ']';
            String id = resourceId(requiredString(row, "id", path, owner),
                    path, owner + ".id");
            if (!ids.add(id)) {
                throw new IOException(path + " 存在重复酒桶配方 id：" + id);
            }
            String result = resourceId(requiredString(row, "result", path, owner),
                    path, owner + ".result");
            Selector carrier = selector(requiredString(row, "carrier", path, owner),
                    path, owner + ".carrier");
            String fluid = resourceId(requiredString(row, "fluid", path, owner),
                    path, owner + ".fluid");
            List<Selector> ingredients = selectors(
                    row, "ingredients", path, owner, 0, MAX_BARREL_INGREDIENTS);
            int unitTicks = positiveInt(row, "unit-ticks", path, owner);
            recipes.add(new BarrelRecipe(
                    id, result, carrier, fluid, unitTicks, ingredients,
                    optionalRgb(row, "tap-color", path, owner)));
        }
        return List.copyOf(recipes);
    }

    private static ShakerSpecialResults shakerSpecialResults(
            YamlDocument yaml, Path path) throws IOException {
        SectionNode section = requiredSection(yaml, "special-results", path);
        return new ShakerSpecialResults(
                resourceId(requiredString(section, "mystery", path, "special-results"),
                        path, "special-results.mystery"),
                resourceId(requiredString(section, "signature", path, "special-results"),
                        path, "special-results.signature"));
    }

    private static List<ShakerRecipe> shakerRecipes(YamlDocument yaml, Path path)
            throws IOException {
        List<SectionNode> rows = recipeRows(yaml, path);
        List<ShakerRecipe> recipes = new ArrayList<>(rows.size());
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            SectionNode row = rows.get(index);
            String owner = "recipes[" + index + ']';
            String id = resourceId(requiredString(row, "id", path, owner),
                    path, owner + ".id");
            if (!ids.add(id)) {
                throw new IOException(path + " 存在重复鸡尾酒配方 id：" + id);
            }
            String result = resourceId(requiredString(row, "result", path, owner),
                    path, owner + ".result");
            List<Selector> ingredients = selectors(
                    row, "ingredients", path, owner, 1, MAX_SHAKER_INGREDIENTS);
            recipes.add(new ShakerRecipe(id, result, ingredients));
        }
        return List.copyOf(recipes);
    }

    private static List<SectionNode> recipeRows(YamlDocument yaml, Path path)
            throws IOException {
        SequenceNode sequence = yaml.getSequenceOrNull("recipes");
        if (sequence == null) {
            throw new IOException(path + " 的 recipes 必须是列表；可使用 recipes: [] 禁用全部配方");
        }
        List<SectionNode> result = new ArrayList<>(sequence.size());
        for (int index = 0; index < sequence.size(); index++) {
            YamlNode<?> entry = sequence.value().get(index);
            if (!(entry instanceof SectionNode section)) {
                throw new IOException(path + " 的 recipes[" + index + "] 必须是映射");
            }
            result.add(section);
        }
        return result;
    }

    private static List<Selector> selectors(SectionNode row, String key, Path path,
                                            String owner, int minimum, int maximum)
            throws IOException {
        SequenceNode sequence = row.getSequenceOrNull(key);
        if (sequence == null) {
            throw new IOException(path + " 的 " + owner + '.' + key + " 必须是列表");
        }
        if (sequence.size() < minimum || sequence.size() > maximum) {
            throw new IOException(path + " 的 " + owner + '.' + key + " 必须包含 "
                    + minimum + " 至 " + maximum + " 项");
        }
        List<Selector> selectors = new ArrayList<>(sequence.size());
        for (int index = 0; index < sequence.size(); index++) {
            String encoded;
            try {
                encoded = sequence.get(String.class, index);
            } catch (RuntimeException exception) {
                throw new IOException(path + " 的 " + owner + '.' + key + '[' + index
                        + "] 必须是非空 selector 字符串", exception);
            }
            if (encoded == null || encoded.isBlank()) {
                throw new IOException(path + " 的 " + owner + '.' + key + '[' + index
                        + "] 必须是非空 selector 字符串");
            }
            selectors.add(selector(encoded, path, owner + '.' + key + '[' + index + ']'));
        }
        return List.copyOf(selectors);
    }

    private static Selector selector(String encoded, Path path, String owner) throws IOException {
        Selector selector;
        try {
            selector = Selector.parse(encoded);
        } catch (IOException exception) {
            throw new IOException(path + " 的 " + owner + " 无效：" + encoded, exception);
        }
        resourceId(selector.value(), path, owner);
        return selector;
    }

    private static SectionNode requiredSection(
            SectionNode parent, String key, Path path) throws IOException {
        SectionNode section = parent.getSectionOrNull(key);
        if (section == null) {
            throw new IOException(path + " 缺少 " + key + " 映射");
        }
        return section;
    }

    private static String requiredString(
            SectionNode section, String key, Path path, String owner) throws IOException {
        String value;
        try {
            value = section.get(String.class, key);
        } catch (RuntimeException exception) {
            throw new IOException(path + " 缺少非空字符串 " + owner + '.' + key, exception);
        }
        if (value == null || value.isBlank()) {
            throw new IOException(path + " 缺少非空字符串 " + owner + '.' + key);
        }
        return value;
    }

    private static int positiveInt(
            SectionNode section, String key, Path path, String owner) throws IOException {
        int value = requiredInt(section, key, path, owner + '.' + key);
        if (value <= 0) {
            throw new IOException(path + " 的 " + owner + '.' + key + " 必须是正整数");
        }
        return value;
    }

    private static OptionalInt optionalRgb(
            SectionNode section, String key, Path path, String owner) throws IOException {
        if (section.getNodeOrNull(key) == null) {
            return OptionalInt.empty();
        }
        String encoded;
        try {
            encoded = section.get(String.class, key);
        } catch (RuntimeException exception) {
            throw new IOException(path + " 的 " + owner + '.' + key
                    + " 必须是 #RRGGBB 字符串", exception);
        }
        if (encoded == null || !encoded.matches("#[0-9a-fA-F]{6}")) {
            throw new IOException(path + " 的 " + owner + '.' + key
                    + " 必须使用 #RRGGBB 格式");
        }
        return OptionalInt.of(Integer.parseInt(encoded.substring(1), 16));
    }

    private static int requiredInt(
            SectionNode section, String key, Path path, String owner) throws IOException {
        Integer value;
        try {
            value = section.get(Integer.class, key);
        } catch (RuntimeException exception) {
            throw new IOException(path + " 的 " + owner + " 必须是整数", exception);
        }
        if (value == null) {
            throw new IOException(path + " 的 " + owner + " 必须是整数");
        }
        return value;
    }

    private static String resourceId(String value, Path path, String owner) throws IOException {
        if (!RESOURCE_ID.matcher(value).matches()) {
            throw new IOException(path + " 的 " + owner + " 不是有效命名空间 ID：" + value);
        }
        return value;
    }
}
