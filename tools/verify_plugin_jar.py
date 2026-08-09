#!/usr/bin/env python3
"""Audit the deployable JAR and both managed content bundles."""

from __future__ import annotations

import argparse
import json
import sys
import zipfile
from pathlib import Path


REQUIRED_ENTRIES = (
    "plugin.yml",
    "META-INF/MANIFEST.MF",
    "META-INF/LICENSE-CODE",
    "META-INF/LICENSE-ASSETS",
    "META-INF/ASSET-CREDITS.md",
    "META-INF/THIRD-PARTY-NOTICES.md",
    "META-INF/third-party-licenses/SPARROW-YAML-GPL-3.0.txt",
    "META-INF/third-party-licenses/SNAKEYAML-APACHE-2.0.txt",
    "tavern-pack/pack.yml",
    "tavern-pack/configuration/blocks.json",
    "tavern-pack/configuration/furniture.json",
    "tavern-pack/configuration/items.json",
    "tavern-pack/configuration/render-items.json",
    "tavern-pack/configuration/worldgen.json",
    "customcrops/contents/crops/kaleidoscope_tavern.yml",
    "recipes/barrel.yml",
    "recipes/shaker.yml",
    "net/momirealms/sparrow/yaml/SparrowYaml.class",
    "customnameplates/bossbar-tavern-effects.yml",
    "tavern-pack/resourcepack/assets/kaleidoscope_tavern/font/custom_effects_hud.json",
    "tavern-pack/resourcepack/assets/kaleidoscope_tavern/textures/font/hud_effect/slightly_tipsy.png",
    "tavern-pack/resourcepack/assets/minecraft/textures/gui/sprites/boss_bar/yellow_background.png",
    "tavern-pack/resourcepack/assets/minecraft/textures/gui/sprites/boss_bar/yellow_progress.png",
    "com/github/ysbbbbbb/kaleidoscopetavern/paper/pack/PackInstaller.class",
    "com/github/ysbbbbbb/kaleidoscopetavern/paper/pack/CustomCropsInstaller.class",
    "com/github/ysbbbbbb/kaleidoscopetavern/paper/integration/EffectHudPlaceholder.class",
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("archive", type=Path)
    parser.add_argument("custom_crops_version")
    args = parser.parse_args()

    if not args.archive.is_file():
        raise SystemExit(f"Deployable JAR does not exist: {args.archive}")

    with zipfile.ZipFile(args.archive) as archive:
        names = set(archive.namelist())
        missing = [entry for entry in REQUIRED_ENTRIES if entry not in names]
        if missing:
            raise SystemExit("Deployable JAR is missing: " + ", ".join(missing))

        forbidden_prefixes = (
            "net/momirealms/craftengine/",
            "net/momirealms/customcrops/",
            "me/clip/placeholderapi/",
            "io/papermc/paper/",
            "org/bukkit/",
            "org/junit/",
        )
        embedded = sorted(
            name for name in names if name.endswith(".class")
            and name.startswith(forbidden_prefixes)
        )
        if embedded:
            raise SystemExit(
                "Runtime dependencies must remain separate plugins; embedded class found: "
                + embedded[0]
            )
        unexpected_sparrow = sorted(
            name for name in names
            if name.endswith(".class")
            and name.startswith("net/momirealms/sparrow/")
            and not name.startswith("net/momirealms/sparrow/yaml/")
        )
        if unexpected_sparrow:
            raise SystemExit(
                "Only sparrow-yaml may be embedded; unexpected class found: "
                + unexpected_sparrow[0]
            )

        legal_markers = {
            "META-INF/LICENSE-CODE": (
                "BSD 3-Clause License",
                "Kaleidoscope Official Production Team",
            ),
            "META-INF/LICENSE-ASSETS": (
                "Creative Commons Attribution-NonCommercial-ShareAlike 4.0",
                "https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode",
            ),
            "META-INF/third-party-licenses/SPARROW-YAML-GPL-3.0.txt": (
                "GNU GENERAL PUBLIC LICENSE",
                "Version 3, 29 June 2007",
            ),
            "META-INF/third-party-licenses/SNAKEYAML-APACHE-2.0.txt": (
                "Apache License",
                "Version 2.0, January 2004",
            ),
            "META-INF/ASSET-CREDITS.md": (
                "KaleidoscopeMods/KaleidoscopeTavern",
                "NonCommercial",
                "modified",
            ),
            "META-INF/THIRD-PARTY-NOTICES.md": (
                "CraftEngine",
                "CustomCrops",
                "Sparrow YAML",
                "SnakeYAML Engine",
                "GPL-3.0",
                "Apache License 2.0",
                "bundled",
                "not bundled",
            ),
        }
        for entry, markers in legal_markers.items():
            document = archive.read(entry).decode("utf-8-sig")
            missing_markers = [marker for marker in markers if marker not in document]
            if missing_markers:
                raise SystemExit(
                    f"{entry} is missing required legal marker: {missing_markers[0]}"
                )

        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        unfolded_manifest = manifest.replace("\r\n ", "").replace("\n ", "")
        expected_manifest = f"Required-CustomCrops-Version: {args.custom_crops_version}"
        if expected_manifest not in unfolded_manifest:
            raise SystemExit(f"Manifest is missing {expected_manifest!r}")

        plugin_yml = archive.read("plugin.yml").decode("utf-8")
        if "depend: [CraftEngine, CustomCrops]" not in plugin_yml:
            raise SystemExit("plugin.yml must require both CraftEngine and CustomCrops")
        if "api-version: '26.2'" not in plugin_yml:
            raise SystemExit("plugin.yml is not pinned to Paper 26.2")

        blocks_document = json.loads(
            archive.read("tavern-pack/configuration/blocks.json").decode("utf-8-sig")
        )
        if len(blocks_document.get("blocks", {})) != 44:
            raise SystemExit("Embedded CraftEngine project must contain 44 block ids")

        furniture_document = json.loads(
            archive.read("tavern-pack/configuration/furniture.json").decode("utf-8-sig")
        )
        if len(furniture_document.get("furniture", {})) != 116:
            raise SystemExit(
                "Embedded CraftEngine project must contain 116 furniture ids")

        items_document = json.loads(
            archive.read("tavern-pack/configuration/items.json").decode("utf-8-sig")
        )
        if len(items_document.get("items", {})) != 157:
            raise SystemExit("Embedded CraftEngine project must contain 157 public item ids")

        render_items_document = json.loads(
            archive.read("tavern-pack/configuration/render-items.json").decode("utf-8-sig")
        )
        if len(render_items_document.get("items", {})) != 413:
            raise SystemExit("Embedded CraftEngine project must contain 413 render item ids")

        worldgen_document = json.loads(
            archive.read("tavern-pack/configuration/worldgen.json").decode("utf-8-sig")
        )
        configured_id = "kaleidoscope_tavern:wild_grapevine_chain"
        placed_id = "kaleidoscope_tavern:wild_grapevine"
        if configured_id not in worldgen_document.get("configured_features", {}):
            raise SystemExit("Embedded CraftEngine project is missing wild grapevine worldgen")
        placed_feature = worldgen_document.get("placed_features", {}).get(placed_id)
        if not isinstance(placed_feature, dict) or placed_feature.get("feature") != configured_id:
            raise SystemExit("Embedded wild grapevine placed feature has an invalid chain reference")

        custom_crops = archive.read(
            "customcrops/contents/crops/kaleidoscope_tavern.yml"
        ).decode("utf-8")
        for crop in (
            "kaleidoscope_tavern_grape:",
            "kaleidoscope_tavern_ice_grape:",
            "kaleidoscope_tavern_gold_grape:",
        ):
            if crop not in custom_crops:
                raise SystemExit(f"Managed CustomCrops bundle is missing {crop}")

        resource_pack_entries = [
            name for name in names
            if name.startswith("tavern-pack/resourcepack/assets/")
        ]
        if not resource_pack_entries:
            raise SystemExit("Embedded CraftEngine resource pack is empty")

    print(
        f"Plugin JAR verified: Paper 26.2, CustomCrops {args.custom_crops_version}, "
        "managed CraftEngine project, resource pack and legal notices present"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
