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
EN_US = ROOT / f"src/main/resources/assets/{NAMESPACE}/lang/en_us.json"
ASSET_ROOTS = (
    ROOT / "src/paper/pack/resourcepack/assets",
    ROOT / "src/generated/resources/assets",
    ROOT / "src/main/resources/assets",
)
OBSOLETE_VANILLA_IDS = {"minecraft:chain", "minecraft:grass"}


def nested_strings(value: Any):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from nested_strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from nested_strings(child)


def obsolete_vanilla_ids(values) -> list[str]:
    return sorted({
        resource_id
        for value in values
        for resource_id in OBSOLETE_VANILLA_IDS
        if resource_id in value
    })


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

    with EN_US.open("r", encoding="utf-8-sig") as stream:
        language_keys = set(json.load(stream))
    for full_item_id, item in items.items():
        item_id = full_item_id.split(":", 1)[1]
        raw_name = item.get("data", {}).get("item_name", "")
        matches = re.fullmatch(r"<!i><lang:([^>]+)>", raw_name)
        if matches is None:
            raise AssertionError(f"{full_item_id}: malformed translatable item_name {raw_name!r}")
        actual_key = matches.group(1)
        if actual_key not in language_keys:
            raise AssertionError(f"{full_item_id}: missing item-name translation {actual_key}")

        if item_id.endswith("_sandwich_board"):
            expected_key = f"block.{NAMESPACE}.sandwich_board"
        elif item_id.endswith("_painting"):
            expected_key = f"block.{NAMESPACE}.painting"
            lore = item.get("data", {}).get("lore", [])
            if f"<!i><gray><lang:tooltip.{NAMESPACE}.{item_id}>" not in lore:
                raise AssertionError(f"{full_item_id}: painting variant tooltip is missing")
        else:
            block_key = f"block.{NAMESPACE}.{item_id}"
            item_key = f"item.{NAMESPACE}.{item_id}"
            expected_key = block_key if block_key in language_keys else item_key
        if actual_key != expected_key:
            raise AssertionError(
                f"{full_item_id}: expected source description id {expected_key}, found {actual_key}"
            )

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
        obsolete = obsolete_vanilla_ids(nested_strings(recipe))
        if obsolete:
            raise AssertionError(f"{recipe_id}: obsolete vanilla ids {obsolete}")
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
        rows = tsv_rows(name)
        obsolete = obsolete_vanilla_ids(cell for row in rows for cell in row)
        if obsolete:
            raise AssertionError(f"{name}: obsolete vanilla ids {obsolete}")
        count = len(rows)
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
    if barrel["elements"][0].get("translation") != "0,1.5,0" or any(
            hitbox.get("peek") != 0 for hitbox in barrel["hitboxes"]):
        raise AssertionError("The 3x3x3 barrel model/collision must span y=0..3 exactly")

    sofa = furniture[f"{NAMESPACE}:white_sofa"]["variants"]["ground"]
    stool = furniture[f"{NAMESPACE}:white_bar_stool"]["variants"]["ground"]
    bottle = furniture[f"{NAMESPACE}:empty_bottle"]["variants"]["ground"]
    if sofa["elements"][0].get("translation") != "0,0.5,0":
        raise AssertionError("Ground block models must be lifted to the authored target block")
    if sofa["hitboxes"][0].get("height") != 1.125 or stool["hitboxes"][0].get("height") != 1.3125:
        raise AssertionError("Seat hitboxes must retain the Forge VoxelShape height")
    if bottle["hitboxes"][0].get("width") != 0.375 or bottle["hitboxes"][0].get("height") != 0.875:
        raise AssertionError("Bottle hitboxes must retain the 6x14x6 source VoxelShape")

    board = furniture[f"{NAMESPACE}:base_sandwich_board"]["variants"]["ground"]
    if [element.get("translation") for element in board["elements"]] != ["0,0.5,0", "0,1.5,0"]:
        raise AssertionError("Two-block sandwich-board model halves are vertically misaligned")
    pendant = furniture[f"{NAMESPACE}:bell_pendant_lamp"]["variants"]["ceiling"]
    if [element.get("translation") for element in pendant["elements"]] != ["0,-0.49,0", "0,-1.49,0"]:
        raise AssertionError("Ceiling pendant model halves are vertically misaligned")
    tap = furniture[f"{NAMESPACE}:tap"]["variants"]["wall"]["elements"][0]
    if tap.get("position") != "0,0,0.01" or tap.get("translation") != "0,0,0.49":
        raise AssertionError("Wall model must retain its target-block offset after anti-blackening compensation")

    expected_rules = {
        f"{NAMESPACE}:empty_bottle": {"rotation": "four", "alignment": "center"},
        f"{NAMESPACE}:base_sandwich_board": {"rotation": "sixteen", "alignment": "center"},
    }
    for item_id, expected_rule in expected_rules.items():
        actual = items[item_id]["behavior"]["rules"]["ground"]
        if actual != expected_rule:
            raise AssertionError(f"{item_id}: placement rule drifted from Forge BlockItem semantics")

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
