#!/usr/bin/env python3
"""Strict, dependency-free validation for the generated CraftEngine pack."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "src/paper/pack/configuration"
CATALOG = ROOT / "src/paper/resources/catalog"
CUSTOM_CROPS = ROOT / "src/paper/customcrops/contents/crops/kaleidoscope_tavern.yml"
NAMESPACE = "kaleidoscope_tavern"
ASSET_ROOTS = (
    ROOT / "src/paper/pack/resourcepack/assets",
    ROOT / "src/generated/resources/assets",
    ROOT / "src/main/resources/assets",
)


def load(name: str, root_key: str) -> dict[str, Any]:
    with (CONFIG / name).open("r", encoding="utf-8-sig") as stream:
        data = json.load(stream)
    if set(data) != {root_key}:
        raise AssertionError(f"{name}: expected only root key {root_key!r}")
    return data[root_key]


def asset_exists(resource_id: str, folder: str, suffix: str = ".json") -> bool:
    namespace, path = resource_id.split(":", 1)
    relative = Path(namespace) / folder / f"{path}{suffix}"
    return any((root / relative).is_file() for root in ASSET_ROOTS)


def tsv_rows(name: str) -> list[list[str]]:
    lines = (CATALOG / name).read_text(encoding="utf-8-sig").splitlines()
    if not lines:
        raise AssertionError(f"{name} is empty")
    width = len(lines[0].split("\t"))
    rows = [line.split("\t") for line in lines[1:] if line]
    if any(len(row) != width for row in rows):
        raise AssertionError(f"{name} contains a malformed row")
    return rows


def validate() -> dict[str, int]:
    items = load("items.json", "items")
    render_items = load("render-items.json", "items")
    blocks = load("blocks.json", "blocks")
    furniture = load("furniture.json", "furniture")
    recipes = load("recipes.json", "recipes")
    categories = load("categories.json", "categories")

    if len(items) != 157:
        raise AssertionError(f"Expected 157 public items, found {len(items)}")
    if len(blocks) != 41:
        raise AssertionError(f"Expected 41 grid/state blocks, found {len(blocks)}")
    if len(furniture) != 133:
        raise AssertionError(f"Expected 133 furniture definitions, found {len(furniture)}")
    if len(recipes) != 114:
        raise AssertionError(f"Expected 114 crafting recipes, found {len(recipes)}")

    expected_grid_blocks = {
        f"{NAMESPACE}:wild_grapevine",
        f"{NAMESPACE}:wild_grapevine_plant",
        f"{NAMESPACE}:trellis",
        f"{NAMESPACE}:grapevine_trellis",
        f"{NAMESPACE}:ice_grapevine_trellis",
        f"{NAMESPACE}:gold_grapevine_trellis",
        f"{NAMESPACE}:grape_crop",
        f"{NAMESPACE}:ice_grape_crop",
        f"{NAMESPACE}:gold_grape_crop",
        *(f"{NAMESPACE}:_crop/{crop}/stage_{point}"
          for crop in ("grape_crop", "ice_grape_crop", "gold_grape_crop")
          for point in range(1, 6)),
        *(f"{NAMESPACE}:string_lights_{color}" for color in (
            "colorless", "white", "light_gray", "gray", "black", "brown", "red", "orange",
            "yellow", "lime", "green", "cyan", "light_blue", "blue", "purple", "magenta", "pink",
        )),
    }
    if set(blocks) != expected_grid_blocks:
        unexpected = sorted(set(blocks) - expected_grid_blocks)
        missing = sorted(expected_grid_blocks - set(blocks))
        raise AssertionError(f"Grid/furniture classification drift: unexpected={unexpected}, missing={missing}")
    if set(blocks) & set(furniture):
        raise AssertionError("A placeable definition cannot be both a CE block and CE furniture")

    crop_models: dict[str, list[str]] = {
        "kaleidoscope_tavern_grape": [
            f"{NAMESPACE}:grape_crop",
            *(f"{NAMESPACE}:_crop/grape_crop/stage_{point}" for point in range(1, 6)),
        ],
        "kaleidoscope_tavern_ice_grape": [
            f"{NAMESPACE}:ice_grape_crop",
            *(f"{NAMESPACE}:_crop/ice_grape_crop/stage_{point}" for point in range(1, 6)),
        ],
        "kaleidoscope_tavern_gold_grape": [
            f"{NAMESPACE}:gold_grape_crop",
            *(f"{NAMESPACE}:_crop/gold_grape_crop/stage_{point}" for point in range(1, 6)),
        ],
    }
    for stage_ids in crop_models.values():
        for stage_id in stage_ids:
            behavior = blocks[stage_id].get("behavior")
            behaviors = behavior if isinstance(behavior, list) else [behavior]
            behavior_types = {entry.get("type") for entry in behaviors if isinstance(entry, dict)}
            if behavior_types != {f"{NAMESPACE}:hanging_grape_crop"}:
                raise AssertionError(f"{stage_id}: crop lifecycle must be owned by CustomCrops")
            if "states" in blocks[stage_id]:
                raise AssertionError(f"{stage_id}: CustomCrops stages must be addressable by block id")

    custom_crops_text = CUSTOM_CROPS.read_text(encoding="utf-8-sig")
    configured_crops = set(re.findall(r"^([a-z0-9_]+):$", custom_crops_text, flags=re.MULTILINE))
    if not set(crop_models) <= configured_crops:
        raise AssertionError("Managed CustomCrops file is missing a grape crop definition")
    for crop_id, stage_ids in crop_models.items():
        for stage_id in stage_ids:
            if f"model: {stage_id}" not in custom_crops_text:
                raise AssertionError(f"{crop_id}: missing CustomCrops model {stage_id}")
    if custom_crops_text.count("custom-bone-meal:") != 3:
        raise AssertionError("Every managed grape crop must delegate bone meal to CustomCrops")

    for item_id, item in {**items, **render_items}.items():
        model = item.get("model", {}).get("path")
        if not model or not asset_exists(model, "models"):
            raise AssertionError(f"{item_id}: missing model {model!r}")

    for block_id, block in blocks.items():
        public_item = block.get("settings", {}).get("item")
        if public_item is not None and public_item not in items:
            raise AssertionError(f"{block_id}: missing bound item {public_item}")
        states = block.get("states")
        appearances = states["appearances"] if states else {"default": block["state"]}
        for appearance in appearances.values():
            render_id = appearance.get("entity_renderer", {}).get("item")
            if render_id not in render_items:
                raise AssertionError(f"{block_id}: missing renderer item {render_id}")

    for furniture_id, definition in furniture.items():
        public_item = definition.get("settings", {}).get("item")
        if public_item is not None and public_item not in items:
            raise AssertionError(f"{furniture_id}: missing bound item {public_item}")
        variants = definition.get("variants", {})
        if not variants:
            raise AssertionError(f"{furniture_id}: has no variants")
        for variant_name, variant in variants.items():
            if not variant.get("hitboxes"):
                raise AssertionError(f"{furniture_id}/{variant_name}: has no hitbox")
            for element in variant.get("elements", []):
                render_id = element.get("item")
                if render_id not in render_items:
                    raise AssertionError(f"{furniture_id}/{variant_name}: missing renderer item {render_id}")

    all_items = set(items) | {item_id for item_id in render_items}
    for recipe_id, recipe in recipes.items():
        result = recipe["result"]["id"]
        if result.startswith(f"{NAMESPACE}:") and result not in all_items:
            raise AssertionError(f"{recipe_id}: unknown result {result}")

    category_items = categories[f"{NAMESPACE}:all"]["list"]
    if category_items != list(items):
        raise AssertionError("The CraftEngine category is not in registry order")

    expected_catalogs = {
        "pressing.tsv": 6,
        "barrel.tsv": 24,
        "shaker.tsv": 12,
        "drink-effects.tsv": None,
        "tags.tsv": None,
        "registry-tags.tsv": None,
    }
    catalog_counts: dict[str, int] = {}
    for name, expected in expected_catalogs.items():
        count = len(tsv_rows(name))
        if expected is not None and count != expected:
            raise AssertionError(f"{name}: expected {expected} rows, found {count}")
        catalog_counts[name] = count
    if len({row[0] for row in tsv_rows("drink-effects.tsv")}) != 37:
        raise AssertionError("Expected drink effects for 37 items")

    drink_ids = {row[0] for row in tsv_rows("drink-effects.tsv")}
    drink_ids.add(f"{NAMESPACE}:signature_cocktail")
    for item_id in drink_ids:
        item = items[item_id]
        behavior_types = {
            behavior.get("type") for behavior in (
                item.get("behaviors", []) or ([item["behavior"]] if "behavior" in item else [])
            )
        }
        if item.get("material") != "potion" or "furniture_item" in behavior_types:
            raise AssertionError(f"{item_id}: drinks must remain consumable items with manual sneak placement")
        if item.get("data", {}).get("components", {}).get("minecraft:max_stack_size") != 16:
            raise AssertionError(f"{item_id}: bottle/glassware stack size must remain 16")

    for item_id, item in items.items():
        if not item_id.endswith("_bucket"):
            continue
        settings = item.get("settings", {})
        if (item.get("material") != "milk_bucket"
                or item.get("data", {}).get("components", {}).get("minecraft:max_stack_size") != 16
                or settings.get("consume_replacement") != "minecraft:bucket"
                or settings.get("craft_remainder") != "minecraft:bucket"):
            raise AssertionError(f"{item_id}: juice buckets must remain stackable drinkable items")

    barrel = furniture[f"{NAMESPACE}:barrel"]["variants"]["ground"]
    if len(barrel.get("hitboxes", [])) != 27 or barrel.get("elements", [{}])[0].get("scale") != "3,3,3":
        raise AssertionError("The legacy barrel must retain its 3x3x3 furniture footprint")

    return {
        "items": len(items),
        "blocks": len(blocks),
        "furniture": len(furniture),
        "appearances": len(render_items),
        "recipes": len(recipes),
        "customcrops-crops": len(crop_models),
        **catalog_counts,
    }


if __name__ == "__main__":
    result = validate()
    print("CraftEngine pack validation passed")
    for key, value in result.items():
        print(f"  {key}: {value}")
