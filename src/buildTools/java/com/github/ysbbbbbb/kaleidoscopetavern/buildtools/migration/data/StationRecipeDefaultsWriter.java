package com.github.ysbbbbbb.kaleidoscopetavern.buildtools.migration.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Writes the two operator-editable station recipe defaults in source order. */
public final class StationRecipeDefaultsWriter {
    public record BarrelRow(String id, String result, String tapColor, String carrier,
                            String fluid, String ingredients, int unitTicks) {}
    public record ShakerRow(String id, String result, String ingredients) {}

    private StationRecipeDefaultsWriter() {}

    public static void write(Path projectRoot, Path outputRoot, String namespace,
                             List<BarrelRow> barrelRows, List<ShakerRow> shakerRows) throws IOException {
        Path defaults = outputRoot.resolve("src/paper/resources/recipes");
        Files.createDirectories(defaults);
        Files.writeString(defaults.resolve("barrel.yml"), barrel(namespace, barrelRows), StandardCharsets.UTF_8);
        Files.writeString(defaults.resolve("shaker.yml"), shaker(namespace, shakerRows), StandardCharsets.UTF_8);
    }

    public static String barrel(String namespace, List<BarrelRow> rows) {
        List<String> lines = new ArrayList<>(List.of(
                "# 首次启动时复制到 plugins/KaleidoscopeTavern/recipes/barrel.yml。",
                "# 数据目录中的副本不会被插件升级覆盖；修改后执行 /kt reload。",
                "# selector 支持 item=<命名空间:物品> 与 tag=<命名空间:标签>。",
                "# tap-color 可选，使用 #RRGGBB 指定龙头灌装时的酒液颜色。",
                "config-version: 1", "", "# 满桶内容没有匹配 recipes 时生成的保底产物。",
                "fallback:", "  id: " + q(namespace + ":empty"),
                "  result: " + q(namespace + ":vinegar"), "  unit-ticks: 2400", "  output: 16", "", "recipes:"));
        for (BarrelRow row : rows) {
            lines.add("  - id: " + q(row.id()));
            lines.add("    result: " + q(row.result()));
            if (row.tapColor() != null && !row.tapColor().isEmpty()) lines.add("    tap-color: " + q(row.tapColor()));
            lines.add("    carrier: " + q(row.carrier()));
            lines.add("    fluid: " + q(row.fluid()));
            if (row.ingredients() == null || row.ingredients().isEmpty()) {
                lines.add("    ingredients: []");
            } else {
                lines.add("    ingredients:");
                for (String ingredient : row.ingredients().split(";", -1)) lines.add("      - " + q(ingredient));
            }
            lines.add("    unit-ticks: " + row.unitTicks());
        }
        return String.join("\n", lines) + "\n";
    }

    public static String shaker(String namespace, List<ShakerRow> rows) {
        List<String> lines = new ArrayList<>(List.of(
                "# 首次启动时复制到 plugins/KaleidoscopeTavern/recipes/shaker.yml。",
                "# 数据目录中的副本不会被插件升级覆盖；修改后执行 /kt reload。",
                "# 配方按书写顺序匹配；每份配方可使用 1 至 3 个 selector。",
                "config-version: 1", "", "# 摇动时间进入特殊区间时使用的产物；signature 也用于普通配方未命中时。",
                "special-results:", "  mystery: " + q(namespace + ":mystery_cocktail"),
                "  signature: " + q(namespace + ":signature_cocktail"), "", "recipes:"));
        for (ShakerRow row : rows) {
            lines.add("  - id: " + q(row.id()));
            lines.add("    result: " + q(row.result()));
            lines.add("    ingredients:");
            for (String ingredient : row.ingredients().split(";", -1)) lines.add("      - " + q(ingredient));
        }
        return String.join("\n", lines) + "\n";
    }

    private static String q(Object value) { return MigrationDataIO.yamlScalar(value); }
}
