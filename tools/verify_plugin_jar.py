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
    "tavern-pack/pack.yml",
    "tavern-pack/configuration/blocks.json",
    "customcrops/contents/crops/kaleidoscope_tavern.yml",
    "customnameplates/bossbar-tavern-effects.yml",
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
            "net/momirealms/customcrops/",
            "net/momirealms/sparrow/",
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
        if len(blocks_document.get("blocks", {})) != 41:
            raise SystemExit("Embedded CraftEngine project must contain 41 block ids")

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
        "managed CraftEngine project and resource pack present"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
