#!/usr/bin/env python3
"""Convert the archived Forge data generators into a CraftEngine content pack.

The Forge source tree is intentionally kept as the auditable migration input.  It
is not part of the Paper source set.  Running this script is deterministic and
only rewrites files under ``src/paper/pack/configuration`` and
``src/paper/resources/catalog``.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
from collections import defaultdict
from copy import deepcopy
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
NAMESPACE = "kaleidoscope_tavern"
HANGING_GRAPE_CROPS = {"grape_crop", "ice_grape_crop", "gold_grape_crop"}
JAVA_INIT = ROOT / "src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/init"
GENERATED = ROOT / "src/generated/resources"
MAIN_RESOURCES = ROOT / "src/main/resources"
CONFIGURATION = ROOT / "src/paper/pack/configuration"
CATALOG = ROOT / "src/paper/resources/catalog"

ITEM_REGISTER = JAVA_INIT / "ModItems.java"
BLOCK_REGISTER = JAVA_INIT / "ModBlocks.java"
BLOCKSTATES = (
    GENERATED / f"assets/{NAMESPACE}/blockstates",
    MAIN_RESOURCES / f"assets/{NAMESPACE}/blockstates",
)
ITEM_MODELS = (
    GENERATED / f"assets/{NAMESPACE}/models/item",
    MAIN_RESOURCES / f"assets/{NAMESPACE}/models/item",
)
RECIPES = GENERATED / f"data/{NAMESPACE}/recipes"
DRINK_EFFECTS = GENERATED / f"data/{NAMESPACE}/datamap/drink_effect"


# Vanilla renamed these resource ids after the archived Forge data was generated.
# Keep the original data auditable and normalize only at the Paper 26.2 migration boundary.
LEGACY_RESOURCE_RENAMES: dict[str, str] = {
    "minecraft:chain": "minecraft:iron_chain",
    "minecraft:grass": "minecraft:short_grass",
}


def normalize_legacy_resource_id(resource_id: str) -> str:
    tag_prefix = "#" if resource_id.startswith("#") else ""
    bare_id = resource_id[1:] if tag_prefix else resource_id
    return tag_prefix + LEGACY_RESOURCE_RENAMES.get(bare_id, bare_id)


COMMON_TAG_FALLBACKS: dict[str, list[str]] = {
    "forge:rods/wooden": ["minecraft:stick"],
    "forge:ingots/iron": ["minecraft:iron_ingot"],
    "forge:nuggets/iron": ["minecraft:iron_nugget"],
    "forge:nuggets/gold": ["minecraft:gold_nugget"],
    "forge:gems/diamond": ["minecraft:diamond"],
    "forge:glass": ["minecraft:glass"],
    "forge:glass_panes": ["minecraft:glass_pane"],
    "forge:string": ["minecraft:string"],
    "forge:leather": ["minecraft:leather"],
    "forge:slimeballs": ["minecraft:slime_ball"],
    "forge:dyes/white": ["minecraft:white_dye"],
    "forge:dyes/light_gray": ["minecraft:light_gray_dye"],
    "forge:dyes/gray": ["minecraft:gray_dye"],
    "forge:dyes/black": ["minecraft:black_dye"],
    "forge:dyes/brown": ["minecraft:brown_dye"],
    "forge:dyes/red": ["minecraft:red_dye"],
    "forge:dyes/orange": ["minecraft:orange_dye"],
    "forge:dyes/yellow": ["minecraft:yellow_dye"],
    "forge:dyes/lime": ["minecraft:lime_dye"],
    "forge:dyes/green": ["minecraft:green_dye"],
    "forge:dyes/cyan": ["minecraft:cyan_dye"],
    "forge:dyes/light_blue": ["minecraft:light_blue_dye"],
    "forge:dyes/blue": ["minecraft:blue_dye"],
    "forge:dyes/purple": ["minecraft:purple_dye"],
    "forge:dyes/magenta": ["minecraft:magenta_dye"],
    "forge:dyes/pink": ["minecraft:pink_dye"],
}


def is_grid_block(block_id: str) -> bool:
    """Content that benefits from real block state/physics stays a block.

    Everything else in the old Forge block registry is entity-based furniture.
    This distinction is deliberate: Forge needed a Block for most placeable
    content, while CraftEngine does not.
    """

    return (
        block_id.startswith("string_lights_")
        or block_id in {
            "wild_grapevine", "wild_grapevine_plant", "trellis",
            "grapevine_trellis", "grape_crop",
        }
        or block_id.endswith("_grapevine_trellis")
        or block_id.endswith("_grape_crop")
    )


SOLID_BLOCKS = {"trellis"}

PAINTINGS = {
    "ysbb_painting",
    "tartaric_acid_painting",
    "cr019_painting",
    "unknown_painting",
    "master_marisa_painting",
    "son_of_man_painting",
    "david_painting",
    "girl_with_pearl_earring_painting",
    "starry_night_painting",
    "van_gogh_self_portrait_painting",
    "father_painting",
    "great_wave_painting",
    "mona_lisa_painting",
    "mondrian_painting",
}

PENDANT_LAMPS = {"bell_pendant_lamp", "yellow_pendant_lamp", "blue_pendant_lamp"}
SEATS = {"sofa", "bar_stool"}
SMALL_FURNITURE = {
    "tap",
    "empty_bottle",
    "empty_glassware",
    "signature_cocktail",
    "mystery_cocktail",
    "white_lady",
    "emerald",
    "brass_heart",
    "godfather",
    "grasshopper",
    "screwdriver",
    "mojito",
    "allium_garden",
    "depth_charge",
    "nether_special",
    "bloody_mary",
    "sculk_special",
    "shaker",
    "molotov",
    "water_bottle",
    "honey_bottle",
    "dragon_breath_bottle",
    "potion_bottle",
    "xp_bottle",
    "wine",
    "champagne",
    "vodka",
    "brandy",
    "carignan",
    "sakura_wine",
    "plum_wine",
    "whiskey",
    "ice_wine",
    "polaris_sweet_white",
    "honey_wine",
    "red_queen",
    "miners_star",
    "rum",
    "riesling_dry_white",
    "sunset_glow",
    "madame_shexiang",
    "sweet_berry_wine",
    "sherry",
    "mother_snow",
    "luminous_bride",
    "glowflower_brew",
    "sauvignon_blanc_dry_white",
    "vinegar",
    "watermelon_juice",
}
BOTTLE_AND_GLASS_ITEMS = SMALL_FURNITURE - {"tap", "shaker"}
GRAPE_ITEMS = {"grape", "ice_grape", "gold_grape", "green_grape"}


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8-sig") as stream:
        return json.load(stream)


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=False)
        stream.write("\n")


def write_tsv(path: Path, header: Iterable[str], rows: Iterable[Iterable[Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write("\t".join(header) + "\n")
        for row in rows:
            cells = [str(cell).replace("\t", " ").replace("\r", " ").replace("\n", " ") for cell in row]
            stream.write("\t".join(cells) + "\n")


def registry_ids(path: Path, owner: str) -> list[str]:
    source = path.read_text(encoding="utf-8-sig")
    pattern = re.compile(rf"\b{re.escape(owner)}\.register\(\s*\"([^\"]+)\"")
    ids = pattern.findall(source)
    if len(ids) != len(set(ids)):
        raise ValueError(f"Duplicate ids in {path}")
    return ids


def find_file(roots: Iterable[Path], relative: Path) -> Path | None:
    for root in roots:
        candidate = root / relative
        if candidate.is_file():
            return candidate
    return None


def load_raw_tags() -> dict[str, list[str]]:
    tags: dict[str, list[str]] = {}
    for resource_root in (MAIN_RESOURCES, GENERATED):
        data_root = resource_root / "data"
        if not data_root.is_dir():
            continue
        for namespace_dir in sorted(path for path in data_root.iterdir() if path.is_dir()):
            for folder_name in ("items", "item"):
                tags_root = namespace_dir / "tags" / folder_name
                if not tags_root.is_dir():
                    continue
                for path in sorted(tags_root.rglob("*.json")):
                    tag = f"{namespace_dir.name}:{path.relative_to(tags_root).with_suffix('').as_posix()}"
                    data = read_json(path)
                    values: list[str] = [] if data.get("replace", False) else list(tags.get(tag, []))
                    for raw in data.get("values", []):
                        if isinstance(raw, str):
                            values.append(normalize_legacy_resource_id(raw))
                        elif isinstance(raw, dict) and raw.get("id"):
                            values.append(normalize_legacy_resource_id(str(raw["id"])))
                    tags[tag] = values
    for key, values in COMMON_TAG_FALLBACKS.items():
        tags.setdefault(key, values)
    return tags


def load_raw_registry_tags(folder_names: tuple[str, ...]) -> dict[str, list[str]]:
    """Load non-item registry tags without resolving vanilla/external references."""
    tags: dict[str, list[str]] = {}
    for resource_root in (MAIN_RESOURCES, GENERATED):
        data_root = resource_root / "data"
        if not data_root.is_dir():
            continue
        for namespace_dir in sorted(path for path in data_root.iterdir() if path.is_dir()):
            for folder_name in folder_names:
                tags_root = namespace_dir / "tags" / folder_name
                if not tags_root.is_dir():
                    continue
                for path in sorted(tags_root.rglob("*.json")):
                    tag = f"{namespace_dir.name}:{path.relative_to(tags_root).with_suffix('').as_posix()}"
                    data = read_json(path)
                    values: list[str] = [] if data.get("replace", False) else list(tags.get(tag, []))
                    for raw in data.get("values", []):
                        if isinstance(raw, str):
                            values.append(normalize_legacy_resource_id(raw))
                        elif isinstance(raw, dict) and raw.get("id") and raw.get("required", True):
                            values.append(normalize_legacy_resource_id(str(raw["id"])))
                    tags[tag] = values
    return tags


def flatten_tags(raw_tags: dict[str, list[str]]) -> dict[str, list[str]]:
    memo: dict[str, list[str]] = {}

    def resolve(tag: str, stack: tuple[str, ...] = ()) -> list[str]:
        if tag in memo:
            return memo[tag]
        if tag in stack:
            raise ValueError(f"Recursive item tag: {' -> '.join(stack + (tag,))}")
        flattened: list[str] = []
        for value in raw_tags.get(tag, []):
            candidates = resolve(value[1:], stack + (tag,)) if value.startswith("#") else [value]
            for candidate in candidates:
                if candidate not in flattened:
                    flattened.append(candidate)
        memo[tag] = flattened
        return flattened

    for tag in sorted(raw_tags):
        resolve(tag)
    return memo


def ingredient_values(raw: Any, tags: dict[str, list[str]]) -> list[str]:
    if isinstance(raw, list):
        merged: list[str] = []
        for entry in raw:
            for value in ingredient_values(entry, tags):
                if value not in merged:
                    merged.append(value)
        return merged
    if isinstance(raw, str):
        return [normalize_legacy_resource_id(raw)]
    if not isinstance(raw, dict):
        raise ValueError(f"Unsupported ingredient: {raw!r}")
    if "item" in raw:
        return [normalize_legacy_resource_id(str(raw["item"]))]
    if "id" in raw:
        return [normalize_legacy_resource_id(str(raw["id"]))]
    if "tag" in raw:
        tag = str(raw["tag"])
        values = tags.get(tag, [])
        if not values and tag.startswith("minecraft:"):
            return [f"#{tag}"]
        if not values:
            raise ValueError(f"Item tag {tag} has no resolvable members")
        return values
    raise ValueError(f"Unsupported ingredient object: {raw!r}")


def compact_ingredient(raw: Any, tags: dict[str, list[str]]) -> str | list[str]:
    values = ingredient_values(raw, tags)
    return values[0] if len(values) == 1 else values


def result_entry(raw: Any) -> dict[str, Any]:
    if isinstance(raw, str):
        return {"id": normalize_legacy_resource_id(raw), "count": 1}
    item_id = raw.get("item", raw.get("id"))
    if not item_id:
        raise ValueError(f"Recipe result has no item id: {raw!r}")
    return {
        "id": normalize_legacy_resource_id(str(item_id)),
        "count": int(raw.get("count", 1)),
    }


def convert_standard_recipes(tags: dict[str, list[str]]) -> dict[str, Any]:
    converted: dict[str, Any] = {}
    for path in sorted(RECIPES.glob("*.json")):
        source = read_json(path)
        recipe_type = str(source.get("type", "")).split(":")[-1]
        recipe_id = f"{NAMESPACE}:{path.stem}"
        if recipe_type == "crafting_shaped":
            ingredients = {
                key: compact_ingredient(value, tags)
                for key, value in source.get("key", {}).items()
            }
            converted[recipe_id] = {
                "type": "shaped",
                "category": "building",
                "pattern": source["pattern"],
                "ingredients": ingredients,
                "result": result_entry(source["result"]),
                "unlock_on_join": True,
            }
        elif recipe_type == "crafting_shapeless":
            converted[recipe_id] = {
                "type": "shapeless",
                "category": "misc",
                "ingredients": [compact_ingredient(entry, tags) for entry in source["ingredients"]],
                "result": result_entry(source["result"]),
                "unlock_on_join": True,
            }
        else:
            raise ValueError(f"Unsupported standard recipe type {recipe_type} in {path}")
    return converted


def parse_variant_key(key: str) -> dict[str, str]:
    properties: dict[str, str] = {}
    if not key:
        return properties
    for pair in key.split(","):
        name, value = pair.split("=", 1)
        properties[name] = value
    return properties


def property_definition(name: str, values: list[str]) -> dict[str, Any]:
    ordered = list(dict.fromkeys(values))
    preferred = {
        "facing": "north",
        "axis": "y",
        "waterlogged": "false",
        "powered": "false",
        "triggered": "false",
        "open": "false",
        "waxed": "false",
        "age": "0",
        "count": "1",
        "rotation": "0",
        "connection": "single",
        "position": "0",
        "half": "bottom",
        "face": "floor",
        "type": "single",
    }.get(name)
    default = preferred if preferred in ordered else ordered[0]

    if set(ordered) <= {"true", "false"}:
        return {"type": "boolean", "default": default}
    if name == "facing" and set(ordered) <= {"north", "east", "south", "west"}:
        return {"type": "horizontal_direction", "default": default, "values": ordered}
    if name == "facing" and set(ordered) <= {"north", "east", "south", "west", "up", "down"}:
        return {"type": "direction", "default": default, "values": ordered}
    if name == "axis" and set(ordered) <= {"x", "y", "z"}:
        return {"type": "axis", "default": default, "values": ordered}
    if name == "face" and set(ordered) <= {"floor", "wall", "ceiling"}:
        return {"type": "anchor_type", "default": default, "values": ordered}
    if name == "half" and set(ordered) <= {"top", "bottom"}:
        return {"type": "single_block_half", "default": default, "values": ordered}
    if name == "half" and set(ordered) <= {"upper", "lower"}:
        return {"type": "double_block_half", "default": default, "values": ordered}
    if all(re.fullmatch(r"-?\d+", value) for value in ordered):
        numbers = sorted(int(value) for value in ordered)
        return {"type": "int", "default": int(default), "range": f"{numbers[0]}~{numbers[-1]}"}
    return {"type": "string", "default": default, "values": ordered}


def carrier_type(block_id: str) -> tuple[str, str]:
    if block_id in SOLID_BLOCKS:
        return "solid", "kaleidoscope-tavern-solid-transparent"
    return "higher_tripwire", "kaleidoscope-tavern-decor-transparent"


def normalize_model_entry(raw: Any) -> tuple[str, int, int, int, bool]:
    if isinstance(raw, list):
        if not raw:
            raise ValueError("Empty weighted blockstate model list")
        raw = raw[0]
    if not isinstance(raw, dict) or "model" not in raw:
        raise ValueError(f"Unsupported blockstate model: {raw!r}")
    return (
        str(raw["model"]),
        int(raw.get("x", 0)),
        int(raw.get("y", 0)),
        int(raw.get("z", 0)),
        bool(raw.get("uvlock", False)),
    )


def behavior_for(block_id: str, property_names: set[str]) -> dict[str, Any] | list[dict[str, Any]] | None:
    if block_id == "wild_grapevine":
        return [{
            "type": "vine_crop_head_block",
            "body": f"{NAMESPACE}:wild_grapevine_plant",
            "direction": "down",
            "max_height": 16,
            # Survival/body conversion comes from the native behavior. Growth
            # is delegated so the legacy `sheared` state can suppress it.
            "grow_speed": 0,
        }, {
            "type": f"{NAMESPACE}:wild_grapevine",
            "grow_speed": 0.15,
            "max_height": 16,
        }]
    if block_id == "wild_grapevine_plant":
        return {
            "type": "vine_crop_body_block",
            "head": f"{NAMESPACE}:wild_grapevine",
            "direction": "down",
            "bone_meal": {"behavior": "grow", "grow_blocks": 1},
        }
    if block_id == "trellis":
        return {"type": f"{NAMESPACE}:trellis"}
    if block_id.endswith("_grapevine_trellis") or block_id == "grapevine_trellis":
        return [{
            "type": "crop_block",
            "grow_speed": 0.25,
            "light_requirement": 9,
            "max_light_requirement": 15,
            "is_bone_meal_target": True,
            "bone_meal_age_bonus": {"type": "uniform", "min": 1, "max": 2},
        }, {
            "type": f"{NAMESPACE}:trellis",
            "spread_chance": 0.25,
        }]
    if block_id.endswith("_grape_crop") or block_id == "grape_crop":
        # Growth points, bone meal, persistence, interaction and drops are
        # owned by CustomCrops. CraftEngine only enforces the Tavern-specific
        # rule that the visual block must hang below a mature vine trellis.
        return {
            "type": f"{NAMESPACE}:hanging_grape_crop",
        }
    return None


def block_settings(block_id: str, has_item: bool) -> dict[str, Any]:
    solid = block_id in SOLID_BLOCKS
    settings: dict[str, Any] = {
        "hardness": 0.8 if solid else 0.3,
        "resistance": 1.0,
        "push_reaction": "DESTROY" if not solid else "NORMAL",
        "is_redstone_conductor": False,
        "is_suffocating": False,
        "is_view_blocking": False,
        "can_occlude": False,
        "propagate_skylight": True,
        "sounds": {
            "break": "minecraft:block.wood.break",
            "step": "minecraft:block.wood.step",
            "place": "minecraft:block.wood.place",
            "hit": "minecraft:block.wood.hit",
            "fall": "minecraft:block.wood.fall",
        },
        "tags": ["minecraft:mineable/axe"],
    }
    if solid:
        settings["support_shape"] = "minecraft:stone"
    if has_item:
        settings["item"] = f"{NAMESPACE}:{block_id}"
    if "lamp" in block_id or block_id.startswith("string_lights_"):
        settings["luminance"] = 15
    if block_id.endswith("_incense"):
        settings["luminance"] = 7
    return settings


def split_hanging_crop_stages(block_id: str, config: dict[str, Any]) -> dict[str, Any]:
    """Expose one CE block id per CustomCrops stage.

    CustomCrops' CraftEngine provider places blocks by id and deliberately does
    not reach into CraftEngine's internal state properties.  Keeping the old
    ``age`` property would therefore make every CustomCrops stage resolve to
    the default visual.  The public age-zero id stays stable; later stages are
    private CE ids referenced only by the managed CustomCrops content file.
    """

    states = config.get("states")
    if not isinstance(states, dict):
        raise ValueError(f"{block_id}: hanging crop must expose age states")
    properties = states.get("properties", {})
    age = properties.get("age", {})
    if age.get("range") != "0~5":
        raise ValueError(f"{block_id}: expected legacy age range 0~5, found {age!r}")

    appearances = states.get("appearances", {})
    variants = states.get("variants", {})
    stage_blocks: dict[str, Any] = {}
    for point in range(6):
        variant = variants.get(f"age={point}")
        if not isinstance(variant, dict):
            raise ValueError(f"{block_id}: missing age={point} visual")
        appearance_name = variant.get("appearance")
        appearance = appearances.get(appearance_name)
        if not isinstance(appearance, dict):
            raise ValueError(f"{block_id}: missing appearance {appearance_name!r}")

        stage_id = block_id if point == 0 else f"_crop/{block_id}/stage_{point}"
        stage_config: dict[str, Any] = {
            "state": deepcopy(appearance),
            "settings": deepcopy(config["settings"]),
            "behavior": {"type": f"{NAMESPACE}:hanging_grape_crop"},
        }
        if point > 0:
            stage_config["settings"].pop("item", None)
        stage_blocks[f"{NAMESPACE}:{stage_id}"] = stage_config
    return stage_blocks


def build_blocks(block_ids: list[str], item_ids: set[str]) -> tuple[dict[str, Any], dict[str, Any], dict[str, int]]:
    blocks: dict[str, Any] = {}
    render_items: dict[str, Any] = {}
    metrics = {"appearances": 0, "weighted_variants_reduced": 0}

    for block_id in block_ids:
        state_path = find_file(BLOCKSTATES, Path(f"{block_id}.json"))
        if state_path is None:
            raise FileNotFoundError(f"No blockstate for {block_id}")
        blockstate = read_json(state_path)
        if "multipart" in blockstate:
            raise ValueError(f"Multipart blockstate is not supported: {state_path}")
        variants = blockstate.get("variants", {})
        if not variants:
            raise ValueError(f"No variants in {state_path}")

        property_values: dict[str, list[str]] = defaultdict(list)
        for key in variants:
            for name, value in parse_variant_key(key).items():
                for option in value.split("|"):
                    if option not in property_values[name]:
                        property_values[name].append(option)

        # GrowingPlantHeadBlock's age is intentionally omitted by the legacy
        # blockstate because every age uses the same model. CraftEngine's vine
        # behavior still needs the state property to drive growth.
        if block_id == "wild_grapevine":
            property_values["age"] = [str(age) for age in range(26)]
            property_values["sheared"] = ["false", "true"]

        carrier, carrier_id = carrier_type(block_id)
        appearance_names: dict[tuple[str, int, int, int, bool], str] = {}
        appearances: dict[str, Any] = {}
        mapped_variants: dict[str, Any] = {}

        for variant_key, raw_model in variants.items():
            if isinstance(raw_model, list) and len(raw_model) > 1:
                metrics["weighted_variants_reduced"] += 1
            model = normalize_model_entry(raw_model)
            appearance_name = appearance_names.get(model)
            if appearance_name is None:
                appearance_name = f"appearance_{len(appearance_names)}"
                appearance_names[model] = appearance_name
                render_hash = hashlib.sha1("|".join(map(str, model)).encode("utf-8")).hexdigest()[:10]
                render_path = f"_render/{block_id}/{render_hash}"
                render_id = f"{NAMESPACE}:{render_path}"
                render_items[render_id] = {
                    "material": "paper",
                    "data": {"item_name": f"<!i>{block_id} render"},
                    "model": {"type": "minecraft:model", "path": model[0]},
                    "settings": {"tags": [f"{NAMESPACE}:internal_render_items"]},
                }
                renderer: dict[str, Any] = {
                    "type": "item_display",
                    "item": render_id,
                    "display_transform": "none",
                    "shadow_radius": 0,
                    "view_range": 1.25,
                }
                if any(model[1:4]):
                    renderer["rotation"] = f"{model[1]},{model[2]},{model[3]}"
                appearances[appearance_name] = {
                    "auto_state": {"type": carrier, "id": carrier_id},
                    "transparent": True,
                    "entity_renderer": renderer,
                }
            if property_values:
                mapped_variants[variant_key] = {"appearance": appearance_name}

        if block_id == "wild_grapevine":
            appearance = next(iter(appearances))
            mapped_variants = {
                f"age={age},sheared={str(sheared).lower()}": {"appearance": appearance}
                for age in range(26)
                for sheared in (False, True)
            }

        metrics["appearances"] += len(appearances)
        config: dict[str, Any] = {}
        if property_values:
            config["states"] = {
                "properties": {
                    name: property_definition(name, values)
                    for name, values in property_values.items()
                },
                "appearances": appearances,
                "variants": mapped_variants,
            }
        else:
            config["state"] = next(iter(appearances.values()))

        config["settings"] = block_settings(block_id, block_id in item_ids)
        behavior = behavior_for(block_id, set(property_values))
        if behavior is not None:
            config["behavior"] = behavior
        if block_id in item_ids:
            config["loot"] = {
                "pools": [{
                    "rolls": 1,
                    "conditions": [{"type": "survives_explosion"}],
                    "entries": [{"type": "item", "item": f"{NAMESPACE}:{block_id}"}],
                }]
            }
        if block_id in HANGING_GRAPE_CROPS:
            blocks.update(split_hanging_crop_stages(block_id, config))
        else:
            blocks[f"{NAMESPACE}:{block_id}"] = config

    return blocks, render_items, metrics


def blockstate_records(block_id: str) -> list[tuple[dict[str, str], tuple[str, int, int, int, bool]]]:
    path = find_file(BLOCKSTATES, Path(f"{block_id}.json"))
    if path is None:
        raise FileNotFoundError(f"No blockstate for {block_id}")
    data = read_json(path)
    if "multipart" in data:
        raise ValueError(f"Multipart blockstate is not supported: {path}")
    records = [
        (parse_variant_key(key), normalize_model_entry(model))
        for key, model in data.get("variants", {}).items()
    ]
    if not records:
        raise ValueError(f"No variants in {path}")
    return records


def record_score(record: tuple[dict[str, str], tuple[str, int, int, int, bool]]) -> tuple[int, int, str]:
    properties, model = record
    preferred = {
        "facing": "north",
        "waterlogged": "false",
        "powered": "false",
        "triggered": "false",
        "open": "false",
        "connection": "single",
        "position": "single",
        "count": "1",
        "rotation": "0",
        "axis": "x",
        "half": "bottom",
        "face": "wall",
        "tilt": "false",
        "waxed": "false",
    }
    mismatches = 0
    for key, value in properties.items():
        if key == "position" and value in {"single", "0"}:
            continue
        if value != preferred.get(key, value):
            mismatches += 1
    rotation_cost = sum(abs(value) for value in model[1:4])
    return mismatches, rotation_cost, model[0]


def select_record(
    records: list[tuple[dict[str, str], tuple[str, int, int, int, bool]]],
    required: dict[str, str],
) -> tuple[dict[str, str], tuple[str, int, int, int, bool]]:
    matches = [
        record for record in records
        if all(record[0].get(key) == value for key, value in required.items())
    ]
    if not matches:
        raise ValueError(f"No blockstate variant matches {required}")
    return min(matches, key=record_score)


def ensure_render_item(
    render_items: dict[str, Any],
    block_id: str,
    label: str,
    model: tuple[str, int, int, int, bool],
) -> str:
    digest = hashlib.sha1("|".join(map(str, model)).encode("utf-8")).hexdigest()[:10]
    render_id = f"{NAMESPACE}:_render/{block_id}/{digest}"
    render_items.setdefault(render_id, {
        "material": "paper",
        "data": {"item_name": f"<!i>{block_id} {label} render"},
        "model": {"type": "minecraft:model", "path": model[0]},
        "settings": {"tags": [f"{NAMESPACE}:internal_render_items"]},
    })
    return render_id


def furniture_element(
    render_items: dict[str, Any],
    block_id: str,
    label: str,
    model: tuple[str, int, int, int, bool],
    anchor: str,
    translation: str | None = None,
) -> dict[str, Any]:
    if block_id == "barrel":
        # The placed Forge barrel was rendered by a 3x3x3 block-entity model;
        # its blockstate model is intentionally empty. Reuse the authored item
        # model at furniture scale instead of emitting an invisible furniture.
        model = (f"{NAMESPACE}:item/barrel", 0, 0, 0, False)
    element: dict[str, Any] = {
        "type": "item_display",
        "item": ensure_render_item(render_items, block_id, label, model),
        "display_transform": "none",
        "shadow_radius": 0,
        "view_range": 1.25,
    }
    if block_id == "barrel":
        element["position"] = "0,1,0"
        element["scale"] = "3,3,3"
        element["view_range"] = 2.5
    # Potion bottles are placed from a vanilla potion, while signature
    # cocktails store a dynamically mixed color. Both take their furniture
    # tint from the exact source item's potion_contents component.
    if block_id in {"potion_bottle", "signature_cocktail"}:
        element["tint_source"] = ["potion_contents"]
    if any(model[1:4]):
        element["rotation"] = f"{model[1]},{model[2]},{model[3]}"
    if anchor == "wall":
        element["position"] = "0,0,0.01"
        element["translation"] = "0,0,-0.01"
    elif anchor == "ceiling":
        element["position"] = "0,-0.01,0"
        element["translation"] = "0,0.01,0"
    if translation is not None:
        element["translation"] = translation
    return element


def furniture_hitboxes(block_id: str, anchor: str) -> list[dict[str, Any]]:
    if block_id == "barrel":
        return [
            {
                "type": "shulker",
                "position": f"{x},{y},{z}",
                "peek": 100,
                "interaction_entity": True,
                "interactive": True,
                "blocks_building": True,
            }
            for y in range(3)
            for x in (-1, 0, 1)
            for z in (-1, 0, 1)
        ]
    if block_id == "stepladder":
        return [
            {
                "type": "shulker",
                "position": "0,0,0",
                "peek": 100,
                "interaction_entity": True,
                "interactive": True,
                "blocks_building": True,
            },
            {
                "type": "shulker",
                "position": "0,1,0",
                "peek": 100,
                "interaction_entity": True,
                "interactive": True,
                "blocks_building": True,
            },
        ]
    if block_id.endswith("_sofa"):
        return [{
            "type": "shulker",
            "position": "0,0,0",
            "peek": 100,
            "interaction_entity": True,
            "interactive": True,
            "blocks_building": True,
            "seats": ["0,0.45,0"],
        }]
    if block_id.endswith("_bar_stool"):
        return [{
            "type": "shulker",
            "position": "0,0,0",
            "scale": 0.75,
            "peek": 100,
            "interaction_entity": True,
            "interactive": True,
            "blocks_building": True,
            "seats": ["0,0.85,0 0"],
        }]
    if block_id == "chalkboard" or block_id.endswith("_sandwich_board"):
        return [{
            "type": "interaction",
            "position": "0,1,0",
            "width": 1,
            "height": 2,
            "interactive": True,
            "blocks_building": True,
        }]
    if block_id in PENDANT_LAMPS:
        return [{
            "type": "interaction",
            "position": "0,-1,0",
            "width": 1,
            "height": 2,
            "interactive": True,
            "blocks_building": True,
        }]
    if block_id in PAINTINGS or anchor == "wall":
        return [{
            "type": "interaction",
            "position": "0,0.5,0",
            "width": 0.9,
            "height": 1,
            "interactive": True,
            "blocks_building": True,
        }]
    if block_id in SMALL_FURNITURE or block_id.endswith("_incense"):
        return [{
            "type": "interaction",
            "position": "0,0.35,0",
            "width": 0.55,
            "height": 0.7,
            "interactive": True,
            "blocks_building": True,
        }]
    return [{
        "type": "shulker",
        "position": "0,0,0",
        "peek": 100,
        "interaction_entity": True,
        "interactive": True,
        "blocks_building": True,
    }]


def create_chalkboard_models() -> None:
    model_root = ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/models/furniture"
    texture_root = f"{NAMESPACE}:entity/deco"
    small = {
        "ambientocclusion": False,
        "textures": {"board": f"{texture_root}/small_chalkboard", "particle": f"{NAMESPACE}:block/deco/chalkboard_particle"},
        "elements": [{
            "from": [0, 0, 7.5],
            "to": [16, 28, 8.5],
            "faces": {
                "north": {"uv": [0, 0, 4, 7], "texture": "#board"},
                "south": {"uv": [0, 0, 4, 7], "texture": "#board"},
            },
        }],
    }
    large = {
        "ambientocclusion": False,
        "textures": {"board": f"{texture_root}/large_chalkboard", "particle": f"{NAMESPACE}:block/deco/chalkboard_particle"},
        "elements": [{
            "from": [-16, 0, 7.5],
            "to": [32, 28, 8.5],
            "faces": {
                "north": {"uv": [0, 0, 6, 7], "texture": "#board"},
                "south": {"uv": [0, 0, 6, 7], "texture": "#board"},
            },
        }],
    }
    write_json(model_root / "chalkboard_small.json", small)
    write_json(model_root / "chalkboard_large.json", large)


def semantic_variant_name(anchor: str, properties: tuple[tuple[str, str], ...], index: int) -> str:
    if index == 0:
        return anchor
    parts: list[str] = []
    for key, value in properties:
        if key == "open" and value == "true":
            parts.append("open")
        elif key == "tilt" and value == "true":
            parts.append("tilted")
        elif key == "waxed" and value == "true":
            parts.append("waxed")
        else:
            parts.append(f"{key}_{value}")
    suffix = "_".join(parts) or f"variant_{index}"
    return f"{anchor}_{suffix}"


def furniture_behaviors(block_id: str, variants: list[str]) -> list[dict[str, Any]]:
    behaviors: list[dict[str, Any]] = []

    def display_slots(positions: list[str], width: float, height: float) -> None:
        for index, position in enumerate(positions):
            behaviors.append({
                "type": "display_item_furniture",
                "data_key": f"{NAMESPACE}:display_slot_{index}",
                "sounds": {
                    "put": "minecraft:block.decorated_pot.insert",
                    "take": "minecraft:block.decorated_pot.insert_fail",
                },
                "variants": {
                    variant: {
                        "item_position": position,
                        # Each legacy storage slot was selected from the exact
                        # clicked region. Dedicated behavior hitboxes preserve
                        # that instead of letting every click fall through to
                        # the first empty CraftEngine display controller.
                        "hitboxes": [{
                            "type": "interaction",
                            "position": position,
                            "width": width,
                            "height": height,
                            "interactive": True,
                            "blocks_building": False,
                        }],
                    }
                    for variant in variants
                },
            })

    if block_id in {"bar_cabinet", "glass_bar_cabinet"}:
        display_slots(["-0.24,0.62,0.18", "0.24,0.62,0.18"], 0.42, 0.8)
    elif block_id == "cellar_cabinet":
        # This was a visible 3x3 bottle cabinet, not a generic inventory GUI.
        display_slots([
            "0.325,0.78,0.375", "0,0.78,0.375", "-0.325,0.78,0.375",
            "0.325,0.49,0.375", "0,0.49,0.375", "-0.325,0.49,0.375",
            "0.325,0.20,0.375", "0,0.20,0.375", "-0.325,0.20,0.375",
        ], 0.25, 0.27)
    elif block_id == "tilted_rack":
        display_slots(["-0.375,0.32,0", "0,0.32,0", "0.375,0.32,0"], 0.3, 0.62)
    elif block_id == "circular_rack":
        display_slots([
            "0,0.13,-0.375", "0.375,0.13,-0.19", "0.375,0.13,0.19",
            "0,0.13,0.375", "-0.375,0.13,0.19", "-0.375,0.13,-0.19",
        ], 0.26, 0.46)
    elif block_id == "holder":
        display_slots(["0,0.13,0.25"], 0.4, 0.8)
    elif block_id == "glassware_holder":
        display_slots([
            "-0.25,-0.24,-0.25", "0.25,-0.24,-0.25",
            "-0.25,-0.24,0.25", "0.25,-0.24,0.25",
        ], 0.35, 0.35)

    if block_id in PENDANT_LAMPS:
        behaviors.append({"type": "glowing_furniture", "lights": ["0,-1,0 13"]})
    elif block_id == "glassware_holder":
        behaviors.append({"type": "glowing_furniture", "lights": ["0,0,0 8"]})
    elif block_id in {"circular_rack", "molotov"}:
        behaviors.append({"type": "glowing_furniture", "lights": ["0,0,0 14"]})
    return behaviors


def furniture_rules(block_id: str, variant_names: list[str]) -> dict[str, Any]:
    anchors = [name for name in ("ground", "wall", "ceiling") if name in variant_names]
    rules: dict[str, Any] = {}
    free_place = block_id in SMALL_FURNITURE or block_id.endswith("_sandwich_board") or block_id in PAINTINGS
    rotation = "sixteen" if free_place else "four"
    alignment = "any" if free_place else "center"
    for anchor in anchors:
        rules[anchor] = {"rotation": rotation, "alignment": alignment}
    return rules


def build_furniture(
    furniture_ids: list[str],
    item_ids: set[str],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, dict[str, Any]], dict[str, int]]:
    furniture: dict[str, Any] = {}
    render_items: dict[str, Any] = {}
    placement: dict[str, dict[str, Any]] = {}
    metrics = {"furniture_variants": 0}
    create_chalkboard_models()

    ignored_semantics = {"facing", "waterlogged", "powered", "triggered", "rotation", "axis", "half", "face"}
    for block_id in furniture_ids:
        records = blockstate_records(block_id)
        variants: dict[str, Any] = {}

        if block_id == "chalkboard":
            small_model = (f"{NAMESPACE}:furniture/chalkboard_small", 0, 0, 0, False)
            large_model = (f"{NAMESPACE}:furniture/chalkboard_large", 0, 0, 0, False)
            variants["ground"] = {
                "elements": [furniture_element(render_items, block_id, "small", small_model, "ground")],
                "hitboxes": furniture_hitboxes(block_id, "ground"),
            }
            variants["ground_large"] = {
                "elements": [furniture_element(render_items, block_id, "large", large_model, "ground")],
                "hitboxes": [{
                    "type": "interaction", "position": "0,1,0", "width": 3, "height": 2,
                    "interactive": True, "blocks_building": True,
                }],
            }
        elif block_id.endswith("_sandwich_board"):
            bottom = select_record(records, {"half": "bottom", "rotation": "0", "waterlogged": "false"})[1]
            top = select_record(records, {"half": "top", "rotation": "0", "waterlogged": "false"})[1]
            variants["ground"] = {
                "elements": [
                    furniture_element(render_items, block_id, "bottom", bottom, "ground"),
                    furniture_element(render_items, block_id, "top", top, "ground", "0,1,0"),
                ],
                "hitboxes": furniture_hitboxes(block_id, "ground"),
            }
        elif block_id in PENDANT_LAMPS:
            upper = select_record(records, {"half": "upper", "facing": "north"})[1]
            lower = select_record(records, {"half": "lower", "facing": "north"})[1]
            variants["ceiling"] = {
                "elements": [
                    furniture_element(render_items, block_id, "upper", upper, "ceiling"),
                    furniture_element(render_items, block_id, "lower", lower, "ceiling", "0,-1,0"),
                ],
                "hitboxes": furniture_hitboxes(block_id, "ceiling"),
            }
        elif block_id == "pressing_tub":
            normal = select_record(records, {
                "facing": "north", "tilt": "false", "waterlogged": "false",
            })[1]
            tilted = select_record(records, {
                "facing": "north", "tilt": "true", "waterlogged": "false",
            })[1]
            variants["ground"] = {
                "elements": [furniture_element(render_items, block_id, "ground", normal, "ground")],
                "hitboxes": furniture_hitboxes(block_id, "ground"),
            }
            variants["wall"] = {
                "elements": [furniture_element(render_items, block_id, "wall", tilted, "wall")],
                "hitboxes": furniture_hitboxes(block_id, "wall"),
            }
        elif block_id == "stepladder":
            bottom = select_record(records, {
                "facing": "north", "half": "bottom", "waterlogged": "false",
            })[1]
            top = select_record(records, {
                "facing": "north", "half": "top", "waterlogged": "false",
            })[1]
            variants["ground"] = {
                "elements": [
                    furniture_element(render_items, block_id, "bottom", bottom, "ground"),
                    furniture_element(render_items, block_id, "top", top, "ground", "0,1,0"),
                ],
                "hitboxes": furniture_hitboxes(block_id, "ground"),
            }
        elif block_id in PAINTINGS:
            for anchor, face in (("ground", "floor"), ("wall", "wall"), ("ceiling", "ceiling")):
                selected = select_record(records, {"face": face, "facing": "north", "waterlogged": "false"})[1]
                variants[anchor] = {
                    "elements": [furniture_element(render_items, block_id, anchor, selected, anchor)],
                    "hitboxes": furniture_hitboxes(block_id, anchor),
                }
        else:
            anchor = "wall" if block_id == "tap" else "ceiling" if block_id == "glassware_holder" else "ground"
            grouped: dict[tuple[tuple[str, str], ...], list[tuple[dict[str, str], tuple[str, int, int, int, bool]]]] = defaultdict(list)
            for record in records:
                semantic = tuple(sorted(
                    (key, value) for key, value in record[0].items()
                    if key not in ignored_semantics
                ))
                grouped[semantic].append(record)
            ordered = sorted(grouped.items(), key=lambda entry: (record_score(min(entry[1], key=record_score)), entry[0]))
            used_names: set[str] = set()
            for index, (semantic, candidates) in enumerate(ordered):
                selected = min(candidates, key=record_score)[1]
                name = semantic_variant_name(anchor, semantic, index)
                if name in used_names:
                    name = f"{name}_{index}"
                used_names.add(name)
                variants[name] = {
                    "elements": [furniture_element(render_items, block_id, name, selected, anchor)],
                    "hitboxes": furniture_hitboxes(block_id, anchor),
                }

        config: dict[str, Any] = {
            "settings": {
                "hit_times": 3,
                "sounds": {
                    "break": "minecraft:block.wood.break",
                    "place": "minecraft:block.wood.place",
                    "hit": "minecraft:block.wood.hit",
                },
            },
            "variants": variants,
        }
        full_id = f"{NAMESPACE}:{block_id}"
        if block_id in item_ids:
            config["settings"]["item"] = full_id
            config["loot"] = {
                "pools": [{
                    "rolls": 1,
                    "entries": [{"type": "furniture_item", "item": full_id}],
                }]
            }
            placement[block_id] = furniture_rules(block_id, list(variants))
        behaviors = furniture_behaviors(block_id, list(variants))
        if len(behaviors) == 1:
            config["behavior"] = behaviors[0]
        elif behaviors:
            config["behaviors"] = behaviors
        furniture[full_id] = config
        metrics["furniture_variants"] += len(variants)

    return furniture, render_items, placement, metrics


def load_drink_effects() -> tuple[set[str], list[list[Any]]]:
    drink_ids: set[str] = set()
    rows: list[list[Any]] = []
    for path in sorted(DRINK_EFFECTS.glob("*.json")):
        data = read_json(path)
        item_id = str(data["item"])
        drink_ids.add(item_id.split(":", 1)[-1])
        for level, effect_group in enumerate(data["effects"], start=1):
            for entry in effect_group:
                rows.append([
                    item_id,
                    level,
                    entry["effect"],
                    int(entry["duration"]) * 20,
                    int(entry["amplifier"]),
                    float(entry["probability"]),
                ])
    return drink_ids, rows


def material_for(item_id: str, drink_ids: set[str], block_ids: set[str]) -> str:
    if item_id in drink_ids or item_id == "signature_cocktail":
        return "potion"
    if item_id == "molotov":
        return "splash_potion"
    if item_id.endswith("_bucket"):
        # JuiceBucketItem is a drinkable, effect-clearing milk-bucket analogue.
        return "milk_bucket"
    if item_id in GRAPE_ITEMS:
        return "sweet_berries"
    if item_id == "shaker":
        return "brush"
    if item_id in block_ids:
        return "paper"
    return "paper"


def build_items(
    item_ids: list[str],
    block_ids: set[str],
    furniture_ids: set[str],
    furniture_placement: dict[str, dict[str, Any]],
    drink_ids: set[str],
    tags: dict[str, list[str]],
) -> dict[str, Any]:
    memberships: dict[str, list[str]] = defaultdict(list)
    custom_prefix = f"{NAMESPACE}:"
    for tag, members in tags.items():
        for member in members:
            if member.startswith(custom_prefix):
                memberships[member.split(":", 1)[1]].append(tag)

    items: dict[str, Any] = {}
    for item_id in item_ids:
        model = find_file(ITEM_MODELS, Path(f"{item_id}.json"))
        if model is None:
            raise FileNotFoundError(f"No item model for {item_id}")
        config: dict[str, Any] = {
            "material": material_for(item_id, drink_ids, block_ids),
            "data": {"item_name": f"<!i><lang:item.{NAMESPACE}.{item_id}>"},
            "model": {"type": "minecraft:model", "path": f"{NAMESPACE}:item/{item_id}"},
        }
        behaviors: list[dict[str, Any]] = []
        # Drinks keep vanilla potion consumption. Their sneak-placement is
        # performed by the Paper layer; attaching CE's unconditional
        # furniture_item behavior here would place a bottle on every normal
        # right-click instead of drinking it.
        manually_placed_drink = item_id in drink_ids or item_id == "signature_cocktail"
        if item_id in BOTTLE_AND_GLASS_ITEMS or item_id.endswith("_bucket"):
            # The Forge BottleBlockItem/GlasswareBlockItem hierarchy stacks to
            # 16. Potion is used as the server-side material for drinking, but
            # its vanilla stack limit is 1, so preserve the original component
            # explicitly for both drink and place-only bottle items.
            config["data"]["components"] = {"minecraft:max_stack_size": 16}
        lore_keys: list[str] = []
        if item_id == "grapevine":
            lore_keys = [f"tooltip.{NAMESPACE}.grapevine.{index}" for index in range(1, 4)]
        elif item_id == "trellis":
            lore_keys = [f"tooltip.{NAMESPACE}.trellis.{index}" for index in range(1, 3)]
        elif item_id in GRAPE_ITEMS or item_id.endswith("_bucket"):
            lore_keys = [f"tooltip.{NAMESPACE}.{item_id}"]
        if lore_keys:
            config["data"]["lore"] = [f"<!i><gray><lang:{key}>" for key in lore_keys]
        if item_id in GRAPE_ITEMS:
            config["data"]["food"] = {
                "nutrition": 2,
                "saturation": 2.0,
                "can_always_eat": True,
            }
        if item_id in furniture_ids and not manually_placed_drink:
            behaviors.append({
                "type": "furniture_item",
                "furniture": f"{NAMESPACE}:{item_id}",
                "rules": furniture_placement[item_id],
            })
        elif item_id in block_ids:
            behaviors.append({"type": "block_item", "block": f"{NAMESPACE}:{item_id}"})
        elif item_id == "grapevine":
            behaviors.append({"type": "block_item", "block": f"{NAMESPACE}:wild_grapevine"})
        compost_chance = 0.25 if item_id == "grapevine" else (
            0.5 if item_id in {"grape", "ice_grape", "gold_grape", "green_grape"} else None
        )
        if compost_chance is not None:
            behaviors.append({"type": "compostable_item", "chance": compost_chance})
        if len(behaviors) == 1:
            config["behavior"] = behaviors[0]
        elif behaviors:
            config["behaviors"] = behaviors
        item_tags = sorted(set(memberships.get(item_id, [])))
        if item_tags:
            config["settings"] = {"tags": item_tags}
        if compost_chance is not None:
            config.setdefault("settings", {})["compost_probability"] = compost_chance
        if item_id == "grapevine":
            config.setdefault("settings", {})["fuel_time"] = 200
        if item_id.endswith("_bucket"):
            config.setdefault("settings", {}).update({
                "consume_replacement": "minecraft:bucket",
                "craft_remainder": "minecraft:bucket",
            })
        items[f"{NAMESPACE}:{item_id}"] = config
    return items


def selector(raw: dict[str, Any]) -> str:
    if "item" in raw:
        return f"item={normalize_legacy_resource_id(str(raw['item']))}"
    if "tag" in raw:
        return f"tag={normalize_legacy_resource_id(str(raw['tag']))}"
    raise ValueError(f"Unsupported station selector: {raw!r}")


def build_runtime_catalogs(tags: dict[str, list[str]], effect_rows: list[list[Any]],
                           block_tags: dict[str, list[str]],
                           entity_tags: dict[str, list[str]]) -> dict[str, int]:
    pressing_rows: list[list[Any]] = []
    for path in sorted((RECIPES / "pressing_tub").glob("*.json")):
        data = read_json(path)
        pressing_rows.append([
            f"{NAMESPACE}:{path.stem}",
            selector(data["ingredient"]),
            data["fluid"],
            int(data["fluid_amount"]),
            f"{NAMESPACE}:{path.stem}",
        ])

    barrel_rows: list[list[Any]] = []
    for path in sorted((RECIPES / "barrel").glob("*.json")):
        data = read_json(path)
        barrel_rows.append([
            f"{NAMESPACE}:{path.stem}",
            data["result"]["item"],
            selector(data["carrier"]),
            data["fluid"],
            ";".join(selector(entry) for entry in data.get("ingredients", [])),
            int(data["unit_time"]),
        ])

    shaker_rows: list[list[Any]] = []
    for path in sorted((RECIPES / "shaker").glob("*.json")):
        data = read_json(path)
        shaker_rows.append([
            f"{NAMESPACE}:{path.stem}",
            data["result"]["item"],
            ";".join(selector(entry) for entry in data["ingredients"]),
        ])

    tag_rows = [[tag, member] for tag in sorted(tags) for member in tags[tag]]
    registry_tag_rows = (
        [["block", tag, member] for tag in sorted(block_tags) for member in block_tags[tag]]
        + [["entity_type", tag, member] for tag in sorted(entity_tags) for member in entity_tags[tag]]
    )
    write_tsv(
        CATALOG / "pressing.tsv",
        ("recipe", "ingredient", "fluid", "amount", "bucket"),
        pressing_rows,
    )
    write_tsv(
        CATALOG / "barrel.tsv",
        ("recipe", "result", "carrier", "fluid", "ingredients", "unit_ticks"),
        barrel_rows,
    )
    write_tsv(
        CATALOG / "shaker.tsv",
        ("recipe", "result", "ingredients"),
        shaker_rows,
    )
    write_tsv(
        CATALOG / "drink-effects.tsv",
        ("item", "level", "effect", "duration_ticks", "amplifier", "probability"),
        effect_rows,
    )
    write_tsv(CATALOG / "tags.tsv", ("tag", "item"), tag_rows)
    write_tsv(CATALOG / "registry-tags.tsv", ("registry", "tag", "member"), registry_tag_rows)
    return {
        "pressing": len(pressing_rows),
        "barrel": len(barrel_rows),
        "shaker": len(shaker_rows),
        "drink_effect_items": len({row[0] for row in effect_rows}),
        "drink_effect_entries": len(effect_rows),
        "tag_memberships": len(tag_rows),
        "registry_tag_memberships": len(registry_tag_rows),
    }


def main() -> None:
    item_ids = registry_ids(ITEM_REGISTER, "ITEMS")
    legacy_placeable_ids = registry_ids(BLOCK_REGISTER, "BLOCKS")
    block_ids = [block_id for block_id in legacy_placeable_ids if is_grid_block(block_id)]
    furniture_ids = [block_id for block_id in legacy_placeable_ids if not is_grid_block(block_id)]
    raw_tags = load_raw_tags()
    tags = flatten_tags(raw_tags)
    block_tags = load_raw_registry_tags(("blocks", "block"))
    entity_tags = load_raw_registry_tags(("entity_types", "entity_type"))
    drink_ids, effect_rows = load_drink_effects()

    if CONFIGURATION.exists():
        shutil.rmtree(CONFIGURATION)
    if CATALOG.exists():
        shutil.rmtree(CATALOG)
    CONFIGURATION.mkdir(parents=True)
    CATALOG.mkdir(parents=True)

    generated_furniture_models = ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/models/furniture"
    if generated_furniture_models.exists():
        shutil.rmtree(generated_furniture_models)

    blocks, block_render_items, block_metrics = build_blocks(block_ids, set(item_ids))
    furniture, furniture_render_items, furniture_placement, furniture_metrics = build_furniture(
        furniture_ids, set(item_ids)
    )
    render_items = {**block_render_items, **furniture_render_items}
    items = build_items(
        item_ids,
        set(block_ids),
        set(furniture_ids),
        furniture_placement,
        drink_ids,
        tags,
    )
    recipes = convert_standard_recipes(tags)
    runtime_metrics = build_runtime_catalogs(tags, effect_rows, block_tags, entity_tags)

    write_json(CONFIGURATION / "items.json", {"items": items})
    write_json(CONFIGURATION / "render-items.json", {"items": render_items})
    write_json(CONFIGURATION / "blocks.json", {"blocks": blocks})
    write_json(CONFIGURATION / "furniture.json", {"furniture": furniture})
    write_json(CONFIGURATION / "recipes.json", {"recipes": recipes})
    write_json(CONFIGURATION / "categories.json", {
        "categories": {
            f"{NAMESPACE}:all": {
                "name": "<!i><dark_aqua>森罗酒馆</dark_aqua>",
                "icon": f"{NAMESPACE}:wine",
                "priority": 10,
                "list": [f"{NAMESPACE}:{item_id}" for item_id in item_ids],
            }
        }
    })

    report = {
        "source": "KaleidoscopeTavern Forge 1.20.1 data generators",
        "target": "Paper 26.2 + CraftEngine 26.7.4",
        "items": len(items),
        "blocks": len(blocks),
        "furniture": len(furniture),
        "legacy_placeables": len(legacy_placeable_ids),
        "render_items": len(render_items),
        "standard_recipes": len(recipes),
        **block_metrics,
        **furniture_metrics,
        **runtime_metrics,
    }
    write_json(CONFIGURATION / "migration-report.json", {"kaleidoscope_tavern_migration": report})
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
