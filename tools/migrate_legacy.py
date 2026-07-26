#!/usr/bin/env python3
"""Convert the archived Forge data generators into a CraftEngine content pack.

The Forge source tree is intentionally kept as the auditable migration input.  It
is not part of the Paper source set.  Running this script is deterministic and
only rewrites generated files under ``src/paper/pack`` and
``src/paper/resources/catalog``.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
import shutil
from collections import defaultdict
from copy import deepcopy
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
NAMESPACE = "kaleidoscope_tavern"
HANGING_GRAPE_CROPS = {"grape_crop", "ice_grape_crop", "gold_grape_crop"}
# BlockLootTables: blocks without an item form still drop their recoverable
# parts — grapevine trellises return the trellis plus the vine, and wild
# grapevine segments drop one grapevine each.
ITEMLESS_BLOCK_LOOT: dict[str, tuple[str, ...]] = {
    "grapevine_trellis": ("trellis", "grapevine"),
    "ice_grapevine_trellis": ("trellis", "grapevine"),
    "gold_grapevine_trellis": ("trellis", "grapevine"),
    "wild_grapevine": ("grapevine",),
    "wild_grapevine_plant": ("grapevine",),
}
CUSTOM_EFFECT_ICON_IDS = (
    "slightly_tipsy",
    "high_heels",
    "grass_stealth",
    "vision",
    "bloody_mary",
    "ardent_heat",
    "long_reach",
    "tomb_raider",
    "xp_drain",
    "upside_down",
    "zenith",
    "shriek_attack",
)
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
EN_US = MAIN_RESOURCES / f"assets/{NAMESPACE}/lang/en_us.json"


# Vanilla renamed these resource ids after the archived Forge data was generated.
# Keep the original data auditable and normalize only at the Paper 26.2 migration boundary.
LEGACY_RESOURCE_RENAMES: dict[str, str] = {
    "minecraft:chain": "minecraft:iron_chain",
    "minecraft:grass": "minecraft:short_grass",
}

# The same 26.2 rename also affects model texture paths.  These values are
# deliberately normalized in Paper-only model overrides so the archived Forge
# assets remain an auditable migration input.
LEGACY_MODEL_TEXTURE_RENAMES: dict[str, str] = {
    "block/chain": "minecraft:block/iron_chain",
    "minecraft:block/chain": "minecraft:block/iron_chain",
}


_LANGUAGE_KEYS: set[str] | None = None


def render_item_name(reference_id: str) -> str:
    """Localised hover name for internal render helpers.

    Waila-style mods (Jade) show the displayed item's name when hovering the
    furniture's ItemDisplay, so every render helper carries the real block or
    item translation instead of a debug label.
    """
    global _LANGUAGE_KEYS
    if _LANGUAGE_KEYS is None:
        _LANGUAGE_KEYS = set(read_json(EN_US))
    # Variant blocks (base_/grass_/rose_ sandwich boards etc.) fall back to
    # the base block's name by progressively dropping leading words.
    parts = reference_id.split("_")
    names = ["_".join(parts[start:]) for start in range(len(parts))]
    for name in names:
        for prefix in ("block", "item"):
            candidate = f"{prefix}.{NAMESPACE}.{name}"
            if candidate in _LANGUAGE_KEYS:
                return f"<!i><lang:{candidate}>"
    raise KeyError(f"No display-name translation for render item {reference_id}")


def fluid_render_name(fluid: str) -> str:
    if fluid in ("water", "lava"):
        return f"<!i><lang:block.minecraft.{fluid}>"
    return render_item_name(fluid)


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


# Trellises keep their custom block state and gameplay behavior, but their
# authored model is rendered by an ItemDisplay over a directional lightning-rod
# carrier. The carrier supplies client collision/aiming while CE removes no
# gameplay behavior from the generated custom block.
TRELLIS_BLOCKS = {
    "trellis", "grapevine_trellis",
    "ice_grapevine_trellis", "gold_grapevine_trellis",
}
STURDY_BLOCKS = {"trellis"}

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

# These families mirror the VoxelShape groups passed to DrinkBlock.create() in
# ModBlocks.java.  Keeping the source-space boxes here lets the Paper migration
# preserve the authored selection/collision volume for every count variant.
TALL_DRINKS = {
    "wine", "champagne", "sakura_wine", "whiskey", "ice_wine",
    "polaris_sweet_white", "honey_wine", "red_queen", "miners_star", "rum",
    "sherry", "luminous_bride", "glowflower_brew",
    "sauvignon_blanc_dry_white", "vinegar", "watermelon_juice",
}
WIDE_DRINKS = {
    "vodka", "riesling_dry_white", "madame_shexiang",
    "sweet_berry_wine", "mother_snow",
}
BRANDY_DRINKS = {"brandy", "sunset_glow"}
COCKTAILS = {
    "empty_glassware", "signature_cocktail", "mystery_cocktail", "white_lady",
    "emerald", "brass_heart", "godfather", "grasshopper", "screwdriver",
    "mojito", "allium_garden", "depth_charge", "nether_special", "bloody_mary",
    "sculk_special",
}
CONSUMABLE_COCKTAILS = COCKTAILS - {"empty_glassware"}
# DrinkBlockItem does not require a drink-effect datamap entry.  These drinks
# still use PotionItem's consume action and return their authored container,
# but intentionally apply no effects after consumption.
EFFECTLESS_DRINKS = {"watermelon_juice"}
CABINET_BOTTLES = BOTTLE_AND_GLASS_ITEMS - COCKTAILS
STORAGE_RENDER_ITEMS = CABINET_BOTTLES | {"empty_glassware"}
PRESS_FLUIDS = {
    "glow_berries_juice",
    "gold_grape_juice",
    "grape_juice",
    "green_grape_juice",
    "ice_grape_juice",
    "sweet_berries_juice",
}
BARREL_FLUIDS = PRESS_FLUIDS | {"water", "lava"}
SIMPLE_BOTTLES = {
    "water_bottle", "honey_bottle", "dragon_breath_bottle",
    "potion_bottle", "xp_bottle",
}
BAR_STOOL_COLORS = (
    "black", "blue", "brown", "cyan", "gray", "green", "light_blue",
    "light_gray", "lime", "magenta", "orange", "pink", "purple", "red",
    "white", "yellow",
)

# The Forge blocks below intentionally shared a generic description id.  Their
# concrete variant was shown as lore where applicable, so using the registry id
# as an item translation key makes CraftEngine display a missing-language key.
GENERIC_ITEM_NAME_KEYS = {
    **{item_id: f"block.{NAMESPACE}.painting" for item_id in PAINTINGS},
    **{
        item_id: f"block.{NAMESPACE}.sandwich_board"
        for item_id in {
            "base_sandwich_board", "grass_sandwich_board", "allium_sandwich_board",
            "azure_bluet_sandwich_board", "cornflower_sandwich_board",
            "orchid_sandwich_board", "peony_sandwich_board",
            "pink_petals_sandwich_board", "pitcher_plant_sandwich_board",
            "poppy_sandwich_board", "sunflower_sandwich_board",
            "torchflower_sandwich_board", "tulip_sandwich_board",
            "wither_rose_sandwich_board",
        }
    },
}


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
                "unlock_on_ingredient_obtained": True,
            }
        elif recipe_type == "crafting_shapeless":
            converted[recipe_id] = {
                "type": "shapeless",
                "category": "misc",
                "ingredients": [compact_ingredient(entry, tags) for entry in source["ingredients"]],
                "result": result_entry(source["result"]),
                "unlock_on_ingredient_obtained": True,
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
    return "higher_tripwire", "kaleidoscope-tavern-decor-transparent"


# A CraftEngine carrier decides what the *client* collides with and can aim at,
# because the client only ever sees the vanilla state behind the custom block.
# `higher_tripwire` is non-colliding, which let players walk through trellis
# posts and made them nearly impossible to right-click: no RIGHT_CLICK_BLOCK
# packet means CustomBlockInteractEvent never fires, so grapevine planting
# silently did nothing.
#
# Lightning rods collide as a 3/16 bar along whichever way they face, one pixel
# under the 4/16 members authored by ITrellis, and they come in all six facings,
# so every trellis shape gets a carrier oriented like the part it draws. Cactus
# is the other option CraftEngine ships mappings for, but its 15/16 box is
# needlessly wide; pointed dripstone has no mappings at all.
#
# The carrier contributes no behaviour of its own: registerServerSideCustomBlocks
# puts a generated block in the world and the vanilla state exists only as the
# client's view. That is why CraftEngine's own copper_coil rides a cactus without
# hurting anyone, and why a lightning rod here neither draws strikes nor emits
# redstone.
#
# Staying on waterlogged=false keeps dry trellises from rendering water, since one
# appearance covers both waterlogged values and the carrier cannot encode it.
# Appearances sharing a state is likewise fine: the carrier only supplies collision
# and the aim target, while visible geometry always comes from each appearance's
# own ItemDisplay.
def trellis_carrier_state(trellis_type: str) -> str:
    """Pick the lightning-rod facing that matches this shape's main member."""
    # SINGLE and the crosses that include it stand on a full-height post; the
    # remaining shapes are a single horizontal beam along one axis.
    facing = {
        "single": "up",
        "cross_north_south": "up",
        "cross_east_west": "up",
        "six_direction": "up",
        "north_south": "north",
        "east_west": "east",
        "cross_up_down": "north",
    }[trellis_type]
    return f"minecraft:lightning_rod[facing={facing},powered=false,waterlogged=false]"


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
        # A single Tavern behavior wraps CE's native vine-head lifecycle and
        # adds the legacy sheared growth lock. Keeping both behaviors in a CE
        # composite would make both BonemealableBlock implementations run.
        return {
            "type": f"{NAMESPACE}:wild_grapevine",
            "body": f"{NAMESPACE}:wild_grapevine_plant",
            "direction": "down",
            "grow_speed": 0.15,
        }
    if block_id == "wild_grapevine_plant":
        return {
            # The same Tavern behavior wraps CE's native vine-body lifecycle;
            # native bone meal is delegated to the sheared-aware head.
            "type": f"{NAMESPACE}:wild_grapevine",
            "head": f"{NAMESPACE}:wild_grapevine",
            "direction": "down",
            "bone_meal": {"behavior": "grow", "grow_blocks": 1},
        }
    if block_id == "trellis":
        return {"type": f"{NAMESPACE}:trellis"}
    if block_id.endswith("_grapevine_trellis") or block_id == "grapevine_trellis":
        # TrellisBehavior owns the complete GrapevineTrellisBlock growth
        # step, including immature ages, climate probability, propagation and
        # hanging fruit. A native crop_block here would add a second random
        # tick roll, impose a source-invented light requirement and intercept
        # bone meal before the exact Paper implementation.
        return {
            "type": f"{NAMESPACE}:trellis",
            "spread_chance": 0.25,
        }
    if block_id.endswith("_grape_crop") or block_id == "grape_crop":
        # Growth points, bone meal, persistence, interaction and drops are
        # owned by CustomCrops. CraftEngine only enforces the Tavern-specific
        # rule that the visual block must hang below a mature vine trellis.
        return {
            "type": f"{NAMESPACE}:hanging_grape_crop",
        }
    return None


def block_settings(block_id: str, has_item: bool) -> dict[str, Any]:
    is_string_lights = block_id.startswith("string_lights_")
    is_wild_vine = block_id in {"wild_grapevine", "wild_grapevine_plant"}
    is_crop = block_id.endswith("_grape_crop") or block_id == "grape_crop"
    sturdy = block_id in STURDY_BLOCKS or is_string_lights
    if is_wild_vine:
        sound_type = {
            action: f"minecraft:block.cave_vines.{action}"
            for action in ("break", "step", "place", "hit", "fall")
        }
    elif is_crop:
        sound_type = {
            "break": "minecraft:block.crop.break",
            "step": "minecraft:block.grass.step",
            "place": "minecraft:item.crop.plant",
            "hit": "minecraft:block.grass.hit",
            "fall": "minecraft:block.grass.fall",
        }
    else:
        family = "chain" if is_string_lights else "wood"
        sound_type = {
            action: f"minecraft:block.{family}.{action}"
            for action in ("break", "step", "place", "hit", "fall")
        }
    hardness = 0.0 if is_wild_vine or is_crop else 0.8
    settings: dict[str, Any] = {
        "hardness": hardness,
        "resistance": hardness,
        "push_reaction": "NORMAL" if sturdy else "DESTROY",
        "is_redstone_conductor": False,
        "is_suffocating": False,
        "is_view_blocking": False,
        "can_occlude": False,
        "propagate_skylight": True,
        "sounds": sound_type,
        "tags": (["minecraft:mineable/pickaxe"] if is_string_lights
                 else [] if is_wild_vine or is_crop
                 else ["minecraft:mineable/axe"]),
    }
    if has_item:
        settings["item"] = f"{NAMESPACE}:{block_id}"
    if "lamp" in block_id or is_string_lights:
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
    metrics = {"appearances": 0, "weighted_variants_reduced": 0, "collidable_trellises": 0}

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
            trellis_type = (parse_variant_key(variant_key).get("type")
                            if block_id in TRELLIS_BLOCKS else None)
            appearance_name = appearance_names.get(model)
            if appearance_name is None:
                appearance_name = f"appearance_{len(appearance_names)}"
                appearance_names[model] = appearance_name
                render_hash = hashlib.sha1("|".join(map(str, model)).encode("utf-8")).hexdigest()[:10]
                render_path = f"_render/{block_id}/{render_hash}"
                render_id = f"{NAMESPACE}:{render_path}"
                render_items[render_id] = {
                    "material": "paper",
                    "data": {"item_name": render_item_name(block_id)},
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
                appearance: dict[str, Any] = {}
                if trellis_type is not None:
                    appearance["state"] = trellis_carrier_state(trellis_type)
                    metrics["collidable_trellises"] += 1
                else:
                    appearance["auto_state"] = {"type": carrier, "id": carrier_id}
                appearance["transparent"] = True
                appearance["entity_renderer"] = renderer
                appearances[appearance_name] = appearance
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
        if block_id.startswith("string_lights_"):
            config["events"] = string_lights_dye_events(block_id)
        if block_id in item_ids:
            config["loot"] = {
                "pools": [{
                    "rolls": 1,
                    "conditions": [{"type": "survives_explosion"}],
                    "entries": [{"type": "item", "item": f"{NAMESPACE}:{block_id}"}],
                }]
            }
        elif block_id in ITEMLESS_BLOCK_LOOT:
            config["loot"] = {
                "pools": [{
                    "rolls": 1,
                    "conditions": [{"type": "survives_explosion"}],
                    "entries": [{"type": "item", "item": f"{NAMESPACE}:{drop}"}],
                } for drop in ITEMLESS_BLOCK_LOOT[block_id]]
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
        "data": {"item_name": render_item_name(block_id)},
        "model": {"type": "minecraft:model", "path": model[0]},
        "settings": {"tags": [f"{NAMESPACE}:internal_render_items"]},
    })
    return render_id


Box = tuple[float, float, float, float, float, float]


def number(value: float) -> str:
    if abs(value) < 1.0e-8:
        return "0"
    return f"{value:.6f}".rstrip("0").rstrip(".")


def vector(values: tuple[float, float, float]) -> str:
    return ",".join(number(value) for value in values)


def parse_vector(raw: str | None) -> tuple[float, float, float]:
    if raw is None:
        return 0.0, 0.0, 0.0
    values = tuple(float(value) for value in raw.split(","))
    if len(values) != 3:
        raise ValueError(f"Expected three vector components, got {raw!r}")
    return values


def add_vector(
    first: tuple[float, float, float],
    second: tuple[float, float, float],
) -> tuple[float, float, float]:
    return tuple(a + b for a, b in zip(first, second, strict=True))


def furniture_element(
    render_items: dict[str, Any],
    block_id: str,
    label: str,
    model: tuple[str, int, int, int, bool],
    anchor: str,
    translation: str | None = None,
) -> dict[str, Any]:
    element: dict[str, Any] = {
        "type": "item_display",
        "item": ensure_render_item(render_items, block_id, label, model),
        "display_transform": "none",
        "shadow_radius": 0,
        "view_range": 1.25,
    }

    # A block model is centred around the item-display origin.  Forge placed
    # these models in a target block, while CE anchors furniture on a surface,
    # so every model needs the corresponding half-block translation.  The
    # 0.01 entity offsets keep wall/ceiling displays lit; their translation is
    # compensated so the final visual location remains exact.
    # Paintings and the tilted pressing tub use the empirically corrected wall
    # depth from the live pack. Keep it in the generator so regeneration does
    # not restore the old floating placement.
    corrected_wall_depth = anchor == "wall" and (
        block_id in PAINTINGS or block_id == "pressing_tub"
    )
    base_translation = {
        "ground": (0.0, 0.5, 0.0),
        "wall": (0.0, 0.0, -0.627 if corrected_wall_depth else 0.49),
        "ceiling": (0.0, -0.49, 0.0),
    }[anchor]
    if anchor == "wall":
        element["position"] = "0,0,0.19" if corrected_wall_depth else "0,0,0.01"
    elif anchor == "ceiling":
        element["position"] = "0,-0.01,0"

    element["translation"] = vector(add_vector(base_translation, parse_vector(translation)))

    # Potion bottles are placed from a vanilla potion, while signature
    # cocktails store a dynamically mixed color. Both take their furniture
    # tint from the exact source item's potion_contents component.
    if block_id in {"potion_bottle", "signature_cocktail"}:
        element["tint_source"] = ["potion_contents"]
    if any(model[1:4]):
        element["rotation"] = f"{model[1]},{model[2]},{model[3]}"
    return element


def drink_boxes(block_id: str, count: int) -> list[Box]:
    if block_id in TALL_DRINKS:
        return {
            1: [(6, 0, 6, 10, 16, 10)],
            2: [(2, 0, 6, 14, 16, 10)],
            3: [(2, 0, 10, 14, 16, 14), (6, 0, 2, 10, 16, 14)],
            4: [(2, 0, 2, 14, 16, 14)],
        }[count]
    if block_id in WIDE_DRINKS:
        return {
            1: [(4, 0, 4, 12, 15, 12)],
            2: [(0, 0, 4, 16, 15, 12)],
            3: [(0, 0, 8, 16, 15, 16), (4, 0, 0, 12, 15, 16)],
            4: [(0, 0, 0, 16, 16, 16)],
        }[count]
    if block_id in BRANDY_DRINKS:
        return {
            1: [(3, 0, 6, 13, 13, 10)],
            2: [(1, 0, 3, 15, 12, 12)],
            3: [(1, 0, 1, 16, 12, 13)],
        }[count]
    if block_id == "carignan":
        return {
            1: [(3, 0, 6, 13, 12, 10)],
            2: [(1, 0, 3, 15, 12, 12)],
            3: [(0, 0, 1, 16, 12, 13)],
        }[count]
    if block_id == "plum_wine":
        return {
            1: [(6, 0, 6, 10, 12, 10)],
            2: [(3, 0, 6, 13, 12, 10)],
            3: [(3, 0, 9, 13, 12, 13), (6, 0, 3, 10, 12, 13)],
            4: [(3, 0, 3, 13, 12, 13)],
        }[count]
    return []


def source_boxes(block_id: str, anchor: str, properties: dict[str, str]) -> list[Box]:
    count = int(properties.get("count", "1"))
    drinks = drink_boxes(block_id, count)
    if drinks:
        return drinks
    if block_id.endswith("_sofa"):
        return [(0, 0, 0, 16, 18, 16)]
    if block_id.endswith("_bar_stool"):
        return [(2, 0, 2, 14, 21, 14)]
    if block_id == "chalkboard":
        return [(0, 2, 15, 16, 30, 16)]
    if block_id.endswith("_sandwich_board"):
        return [(2, 0, 2, 14, 22, 14)]
    if block_id in PENDANT_LAMPS:
        # Coordinates are relative to the upper target block; the lower half
        # occupies the block below it.
        return [(1, -15, 5, 15, 16, 11)]
    if block_id == "stepladder":
        return [(0, 0, 0, 16, 32, 16)]
    if block_id in PAINTINGS:
        return {
            "ground": [(1, 0, 1, 15, 1, 15)],
            "wall": [(1, 1, 0, 15, 15, 1)],
            "ceiling": [(1, 15, 1, 15, 16, 15)],
        }[anchor]
    if block_id.endswith("_incense"):
        return [(5, 0, 5, 11, 7, 11)]
    if block_id == "pressing_tub":
        if anchor == "ground":
            # SHAPE is a half-block tub with a four-pixel floor and walls.
            return [
                (0, 0, 0, 16, 4, 16),
                (0, 4, 0, 16, 8, 2),
                (0, 4, 14, 16, 8, 16),
                (0, 4, 2, 2, 8, 14),
                (14, 4, 2, 16, 8, 14),
            ]
        # The wall rule selects Forge's facing=south tilted state.
        return [
            (0, 0, 8, 16, 8, 16),
            (0, 4, 4, 16, 12, 12),
            (0, 8, 0, 16, 16, 8),
        ]
    if block_id == "tap":
        # TapBlock's north-authored shape has its mounting plate at z=16 and
        # its nozzle extending toward z=6.  The wall furniture direction is
        # the outward direction, so this is the source orientation verbatim.
        return [(5, 5, 6, 11, 13, 16)]
    if block_id == "glassware_holder":
        return [(0, 11, 1, 16, 16, 15)]
    if block_id in COCKTAILS:
        return [(4, 0, 4, 12, 10, 12)]
    if block_id == "shaker":
        return [(4, 0, 4, 12, 16, 12)]
    if block_id in SIMPLE_BOTTLES:
        return [(5, 0, 5, 11, 10, 11)]
    if block_id in {"empty_bottle", "molotov"}:
        return [(5, 0, 5, 11, 14, 11)]
    if block_id == "table":
        return [(0, 13, 0, 16, 16, 16)]
    if block_id == "tilted_rack":
        return [(0, 0, 5, 16, 14, 15)]
    if block_id == "circular_rack":
        return [(0, 0, 0, 16, 2, 16)]
    if block_id == "holder":
        return [(5, 0, 2, 11, 16, 14)]
    return [(0, 0, 0, 16, 16, 16)]


def hitbox_position(anchor: str, x: float, y: float, z: float) -> tuple[float, float, float]:
    if anchor == "ground":
        return x / 16 - 0.5, y / 16, z / 16 - 0.5
    if anchor == "ceiling":
        return x / 16 - 0.5, -1 + y / 16, z / 16 - 0.5
    if anchor == "wall":
        return x / 16 - 0.5, y / 16 - 0.5, z / 16
    raise ValueError(f"Unknown furniture anchor {anchor!r}")


def aggregate_box(boxes: list[Box]) -> Box:
    return (
        min(box[0] for box in boxes), min(box[1] for box in boxes), min(box[2] for box in boxes),
        max(box[3] for box in boxes), max(box[4] for box in boxes), max(box[5] for box in boxes),
    )


# BukkitSeat.calculateSeatLocation adds a flat 0.6 to the configured seat
# position, then drops the small armour stand by 0.9875 so that the stand's
# passenger mount -- which sits at the top of its 0.9875-tall body -- lands back
# on the computed point. Those two terms cancel each other, not the 0.6, so the
# net result is:
#
#     player feet = furniture origin + seat.y + 0.6
#
# CraftEngine's own chairs corroborate this: wooden_chair seats at y=0 over a
# 9/16 cushion, putting feet at 0.6 for a 0.5625 surface, i.e. the small sink a
# seated pose normally shows.
SEAT_MOUNT_HEIGHT = 0.6


def seat_offset(cushion_top: float) -> float:
    """Return the CE seat y that rests a player on a cushion of this height."""
    return round(cushion_top - SEAT_MOUNT_HEIGHT, 6)


def interaction_box(box: Box, anchor: str, seats: list[str] | None = None) -> dict[str, Any]:
    min_x, min_y, min_z, max_x, max_y, max_z = box
    position = hitbox_position(anchor, (min_x + max_x) / 2, min_y, (min_z + max_z) / 2)
    result: dict[str, Any] = {
        "type": "interaction",
        "position": vector(position),
        "width": round(max(max_x - min_x, max_z - min_z) / 16, 6),
        "height": round((max_y - min_y) / 16, 6),
        "can_use_item_on": True,
        "can_be_hit_by_projectile": True,
        "interactive": True,
        "blocks_building": True,
    }
    if seats:
        result["seats"] = seats
    return result


def shulker_box(
    position: tuple[float, float, float],
    scale: float = 1.0,
    peek: int = 0,
    direction: str | None = None,
    *,
    blocks_building: bool = True,
    invisible: bool | None = None,
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "type": "shulker",
        "position": vector(position),
        "peek": peek,
        "interaction_entity": False,
        "can_use_item_on": True,
        "can_be_hit_by_projectile": True,
        "interactive": False,
        "blocks_building": blocks_building,
    }
    if abs(scale - 1.0) > 1.0e-8:
        result["scale"] = round(scale, 6)
    if direction is not None:
        result["direction"] = direction
    if invisible is not None:
        result["invisible"] = invisible
    return result


def peek_for(scale: float, height: float) -> int:
    if height <= scale:
        return 0
    physical_peek = max(0.0, min(1.0, height / scale - 1.0))
    raw = 0.5 - math.asin(1.0 - 2.0 * physical_peek) / math.pi
    return max(0, min(100, round(raw * 100)))


def physical_box(box: Box, anchor: str, tile_limit: int = 4) -> list[dict[str, Any]]:
    min_x, min_y, min_z, max_x, max_y, max_z = box
    width_x = (max_x - min_x) / 16
    width_z = (max_z - min_z) / 16
    height = (max_y - min_y) / 16
    cell = min(width_x, width_z)
    tiles_x = round(width_x / cell)
    tiles_z = round(width_z / cell)
    can_tile = (
        abs(tiles_x * cell - width_x) < 1.0e-6
        and abs(tiles_z * cell - width_z) < 1.0e-6
        and tiles_x * tiles_z <= tile_limit
    )
    if not can_tile:
        cell = max(width_x, width_z)
        tiles_x = tiles_z = 1

    result: list[dict[str, Any]] = []
    for tile_x in range(tiles_x):
        x = ((min_x + max_x) / 32 if not can_tile
             else min_x / 16 + cell * (tile_x + 0.5))
        for tile_z in range(tiles_z):
            z = ((min_z + max_z) / 32 if not can_tile
                 else min_z / 16 + cell * (tile_z + 0.5))
            remaining = height
            y = min_y / 16
            while remaining > 1.0e-8:
                segment_scale = cell
                segment_height = min(remaining, 2 * cell)
                if segment_height < cell:
                    # A narrow final segment also resembles the neck of the
                    # authored bottle shapes better than an over-tall cube.
                    segment_scale = segment_height
                source_y = y * 16
                position = hitbox_position(anchor, x * 16, source_y, z * 16)
                result.append(shulker_box(position, segment_scale, peek_for(segment_scale, segment_height)))
                y += segment_height
                remaining -= segment_height
    return result


def entity_uv_faces(u: float, v: float, dx: float, dy: float, dz: float) -> dict[str, Any]:
    """Convert ModelPart.Cube's 256x256 UV layout to block-model UVs."""
    u0 = u
    u1 = u + dz
    u2 = u + dz + dx
    u3 = u + dz + dx + dx
    u4 = u + dz + dx + dz
    u5 = u + dz + dx + dz + dx
    v0 = v
    v1 = v + dz
    v2 = v + dz + dy

    def uv(values: tuple[float, float, float, float]) -> list[float]:
        return [round(value / 16, 6) for value in values]

    source = {
        "down": uv((u1, v0, u2, v1)),
        "up": uv((u2, v1, u3, v0)),
        "west": uv((u0, v1, u1, v2)),
        "north": uv((u1, v1, u2, v2)),
        "east": uv((u2, v1, u4, v2)),
        "south": uv((u4, v1, u5, v2)),
    }
    # BarrelBlockEntityRender applies a 180-degree Z rotation before facing.
    transformed = {
        "up": source["down"],
        "down": source["up"],
        "east": source["west"],
        "west": source["east"],
        "north": source["north"],
        "south": source["south"],
    }
    return {face: {"uv": coords, "texture": "#barrel"}
            for face, coords in transformed.items()}


def entity_barrel_box(
    x: float, y: float, z: float,
    dx: float, dy: float, dz: float,
    u: float, v: float,
) -> dict[str, Any]:
    """Map one axis-aligned BarrelModel body cube through its root/Z transform."""
    return {
        "from": [-x - dx, -1 - y - dy, z - 1],
        "to": [-x, -1 - y, z + dz - 1],
        "faces": entity_uv_faces(u, v, dx, dy, dz),
    }


# BarrelModel renders with entityCutoutNoCull, so its flat interior panels show
# from both sides. Block models always cull back faces, which made those panels
# disappear when viewed from above through an open lid and left the cavity black.
# Giving each plane a hair of thickness makes it a solid that reads correctly from
# either side, without the z-fighting a coincident mirrored copy would cause.
#
# The entity UV layout (entity_uv_faces) only paints the "front" face of a flat
# panel; the "back" face UV lands on an empty/transparent region of the texture
# that was never rendered under entityCutoutNoCull.  After thickening, that back
# face becomes visible, so its UV must be copied from the front face to avoid
# being culled by FaceBakery.computeMaterialTransparency in 26.2+.
INTERIOR_PANEL_THICKNESS = 0.01

# axis → (front_face, back_face): the back face UV is overwritten with the
# front face UV so both sides show the same texture.
_FLAT_AXIS_FACE_PAIRS = {
    0: ("east", "west"),   # flat in X
    1: ("up", "down"),     # flat in Y
    2: ("north", "south"), # flat in Z
}


def solidify_planes(element: dict[str, Any]) -> dict[str, Any]:
    """Thicken a zero-thickness plane so both of its sides render."""
    flat = [index for index in range(3) if element["from"][index] == element["to"][index]]
    if len(flat) != 1:
        return element
    axis = flat[0]
    start = list(element["from"])
    end = list(element["to"])
    # Grow symmetrically so the panel keeps sitting exactly where it was authored.
    start[axis] -= INTERIOR_PANEL_THICKNESS
    end[axis] += INTERIOR_PANEL_THICKNESS
    # Copy the front face UV onto the back face so the newly-visible side maps
    # to opaque texels instead of the empty region the entity layout reserved.
    faces = {k: dict(v) for k, v in element.get("faces", {}).items()}
    front, back = _FLAT_AXIS_FACE_PAIRS[axis]
    if front in faces and back in faces:
        faces[back]["uv"] = list(faces[front]["uv"])
    return {**element, "from": start, "to": end, "faces": faces}


def create_barrel_models() -> None:
    """Recreate BarrelModel's body and articulated lid from the Forge source."""
    model_root = ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/models/furniture"
    body_specs = [
        (6, 7, -10, 4, 8, 4, 28, 136),
        (-26, 7, -10, 4, 8, 4, 28, 136),
        (-22, 7, -8, 28, 4, 2, 174, 118),
        (-22, 7, 22, 28, 4, 2, 174, 118),
        (6, 7, 22, 4, 8, 4, 28, 136),
        (-26, 7, 22, 4, 8, 4, 28, 136),
        (-28, -33, -15, 40, 40, 48, 0, 0),
        (-16, -33, 1, 16, 21, 0, 0, 160),
        (0, -33, 1, 0, 24, 16, 0, 144),
        (-16, -33, 1, 0, 21, 16, 0, 144),
        (-16, -12, 1, 16, 0, 16, 32, 160),
        (-16, -33, 17, 16, 21, 0, 0, 160),
    ]
    body_elements = [solidify_planes(entity_barrel_box(*spec)) for spec in body_specs]
    closed_lid = entity_barrel_box(-16, -33, 1, 16, 2, 16, 102, 113)
    base = {
        "ambientocclusion": False,
        "textures": {
            "barrel": f"{NAMESPACE}:entity/brew/barrel",
            "particle": "minecraft:block/barrel_side",
        },
    }
    write_json(model_root / "barrel_body.json", {**base, "elements": body_elements})
    write_json(model_root / "barrel_closed.json", {
        **base,
        "elements": [*body_elements, closed_lid],
    })

    # The open lid pivots about its rear edge. BarrelModel's nested rotations
    # combine to 107.5 degrees from its original plane, i.e. a 72.5-degree
    # opening angle. Keeping the pivot at model origin lets the CE item-display
    # element reproduce that articulation without block-model angle limits.
    open_lid = {
        "from": [0, 8, -8],
        "to": [16, 10, 8],
        "faces": closed_lid["faces"],
    }
    write_json(model_root / "barrel_open_lid.json", {**base, "elements": [open_lid]})


def furniture_hitboxes(
    block_id: str,
    anchor: str,
    properties: dict[str, str] | None = None,
) -> list[dict[str, Any]]:
    properties = properties or {}
    boxes = source_boxes(block_id, anchor, properties)
    aggregate = aggregate_box(boxes)

    if block_id == "barrel":
        return [
            shulker_box((x, y, z))
            for y in range(3)
            for x in (-1, 0, 1)
            for z in (-1, 0, 1)
        ]
    if block_id == "stepladder":
        # Keep the compact, server-tested layout. Only the base blocks building;
        # the upper rails remain physical without preventing adjacent placement.
        return [
            shulker_box((0, 0, 0), 0.75, direction="up", invisible=False),
            shulker_box(
                (0, 0.75, -0.25), 0.625, 25, "north",
                blocks_building=False, invisible=False,
            ),
            shulker_box(
                (-0.25, 1.5, -0.25), 0.4, 35, "up",
                blocks_building=False, invisible=False,
            ),
            shulker_box(
                (0.25, 1.5, -0.25), 0.4, 35, "up",
                blocks_building=False, invisible=False,
            ),
        ]
    if block_id.endswith("_sofa"):
        # Four half-block cubes reproduce the solid seat/base without filling
        # the open space in front of the authored 18-pixel-high backrest.
        return [
            # The sofa cushion ends at 8/16.
            interaction_box(aggregate, anchor, [f"0,{seat_offset(8 / 16)},0 0"]),
            *(shulker_box((x, 0, z), 0.5) for x in (-0.25, 0.25) for z in (-0.25, 0.25)),
        ]
    if block_id.endswith("_bar_stool"):
        return [
            # The broad seat ends at 15/16; the taller back remains an
            # interaction volume rather than blocking the player's torso.
            interaction_box(aggregate, anchor, [f"0,{seat_offset(15 / 16)},0 0"]),
            shulker_box((0, 3 / 16, 0), 0.75),
        ]
    if block_id.endswith("_sandwich_board"):
        return [
            interaction_box(aggregate, anchor),
            shulker_box((0, 0, 0), 0.75, peek_for(0.75, 1.375)),
        ]
    if block_id == "pressing_tub" and anchor == "ground":
        edge = (-0.375, -0.125, 0.125, 0.375)
        walls = [shulker_box((x, 0, z), 0.25, 100) for x in edge for z in (-0.375, 0.375)]
        walls.extend(shulker_box((x, 0, z), 0.25, 100)
                     for x in (-0.375, 0.375) for z in (-0.125, 0.125))
        floor = [
            shulker_box((x, 0, z), 0.25)
            for x in (-0.125, 0.125)
            for z in (-0.125, 0.125)
        ]
        return [interaction_box(aggregate, anchor), *walls, *floor]
    if block_id in PENDANT_LAMPS:
        # Forge explicitly used noCollission() for pendant lamps.
        return [interaction_box(aggregate, anchor)]
    if block_id in PAINTINGS:
        # Interaction entities are square in the horizontal plane, so a
        # 14/16-wide wall painting would otherwise become 14/16 *deep* too
        # and fail placement against a wall while overlapping the placer.
        hitbox = interaction_box(aggregate, anchor)
        hitbox["blocks_building"] = False
        return [hitbox]
    if block_id == "chalkboard" or block_id == "glassware_holder":
        return [interaction_box(aggregate, anchor)]
    if block_id == "table":
        # Shulkers cannot be flatter than their horizontal scale. A 4x4 grid
        # keeps the exact one-block footprint and differs from TableBlock's
        # 3-pixel-high top slab by only one pixel, instead of blocking the
        # entire cube as the old single-shulker carrier did.
        quarters = (-0.375, -0.125, 0.125, 0.375)
        return [
            interaction_box(aggregate, anchor),
            *(shulker_box((x, 0.75, z), 0.25) for x in quarters for z in quarters),
        ]
    if block_id in {"bar_counter", "bar_cabinet", "glass_bar_cabinet", "cellar_cabinet"}:
        # A full shulker with peek=0 is exactly one block high.  The previous
        # peek=100 doubled these colliders to two blocks.
        return [shulker_box(hitbox_position(anchor, 8, 0, 8))]
    if block_id == "circular_rack":
        return [interaction_box(aggregate, anchor)]
    if block_id == "tap":
        hitboxes = [interaction_box(aggregate, anchor), *physical_box(aggregate, anchor)]
        if properties.get("open") == "false":
            hitboxes[0]["position"] = "0,-0.1875,0.35"
        return hitboxes
    if block_id in {"tilted_rack", "holder"}:
        return [interaction_box(aggregate, anchor), *physical_box(aggregate, anchor)]
    if block_id in SMALL_FURNITURE or block_id.endswith("_incense"):
        return [interaction_box(aggregate, anchor), *(hitbox for box in boxes for hitbox in physical_box(box, anchor))]
    if block_id == "pressing_tub":
        return [
            interaction_box(aggregate, anchor),
            *(hitbox for box in boxes for hitbox in physical_box(box, anchor, tile_limit=8)),
        ]
    return [shulker_box(hitbox_position(anchor, 8, 0, 8))]


def create_chalkboard_models() -> None:
    model_root = ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/models/furniture"
    texture_root = f"{NAMESPACE}:entity/deco"
    small = {
        "ambientocclusion": False,
        "textures": {"board": f"{texture_root}/small_chalkboard", "particle": f"{NAMESPACE}:block/deco/chalkboard_particle"},
        "elements": [{
            # SmallChalkboardModel is one pixel thick at the facing edge and
            # spans source y=2..30 across the original two-block structure.
            "from": [0, 2, 15],
            "to": [16, 30, 16],
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
            "from": [-16, 2, 15],
            "to": [32, 30, 16],
            "faces": {
                "north": {"uv": [0, 0, 6, 7], "texture": "#board"},
                "south": {"uv": [0, 0, 6, 7], "texture": "#board"},
            },
        }],
    }
    write_json(model_root / "chalkboard_small.json", small)
    write_json(model_root / "chalkboard_large.json", large)


def create_pendant_lamp_models() -> None:
    """Override the six pendant halves with their Paper 26.2 particle texture.

    Vanilla renamed the chain texture to ``iron_chain``.  The particle entry is
    still resolved by CraftEngine even though every visible face uses the
    tavern's own lamp texture, so leaving the archived id emits a missing-texture
    warning while loading the pack.
    """
    source_root = MAIN_RESOURCES / f"assets/{NAMESPACE}/models/block/deco"
    target_root = ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/models/block/deco"
    for block_id in sorted(PENDANT_LAMPS):
        for half in ("top", "bottom"):
            model = read_json(source_root / block_id / f"{half}.json")
            textures = model.get("textures")
            if not isinstance(textures, dict):
                raise AssertionError(f"{block_id}/{half}: missing model textures")
            particle = textures.get("particle")
            normalized = LEGACY_MODEL_TEXTURE_RENAMES.get(particle, particle)
            if normalized != "minecraft:block/iron_chain":
                raise AssertionError(
                    f"{block_id}/{half}: unexpected particle texture {particle!r}")
            textures["particle"] = normalized
            write_json(target_root / block_id / f"{half}.json", model)


def create_custom_effect_font() -> None:
    """Expose the archived 18x18 MobEffect icons to the Paper-side HUD.

    A vanilla client cannot register the Tavern's custom mob-effect registry
    entries, but a resource-pack bitmap font can render the original icons in
    an Adventure component without replacing any vanilla effect.
    """
    providers: list[dict[str, Any]] = []
    texture_root = MAIN_RESOURCES / f"assets/{NAMESPACE}/textures/mob_effect"
    for index, effect_id in enumerate(CUSTOM_EFFECT_ICON_IDS):
        texture = texture_root / f"{effect_id}.png"
        if not texture.is_file():
            raise AssertionError(f"Missing custom effect icon: {texture}")
        providers.append({
            "type": "bitmap",
            "file": f"{NAMESPACE}:mob_effect/{effect_id}.png",
            "ascent": 8,
            "height": 9,
            "chars": [chr(0xE100 + index)],
        })
    write_json(
        ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/font/custom_effects.json",
        {"providers": providers},
    )


# The corner HUD mirrors the vanilla effect overlay: a 24x24 frame per effect,
# advancing 25 GUI pixels right-to-left, beneficial effects on row one and the
# remaining categories on row two.  The layout is composed from exact glyph
# advances, so every icon is padded to a deterministic 19px advance and every
# horizontal move uses the space provider below.
HUD_OFFSET_POWERS = (1, 2, 4, 8, 16, 32, 64, 128, 256)
HUD_BG_ROW1 = 0xE320
HUD_BG_ROW2 = 0xE321
HUD_ICON_ROW1 = 0xE330
HUD_ICON_ROW2 = 0xE340


def create_custom_effect_hud_assets() -> None:
    """Generate the vanilla-position corner HUD font, icons and bar sprites.

    Boss bar text is centre-anchored, so the line is composed as a zero-advance
    string: absolute offsets from the screen centre place each 25px slot at the
    same coordinates the vanilla effect overlay uses, assuming the configured
    GUI half-width.  The built-in renderer and CustomNameplates both display it
    through a YELLOW boss bar whose sprites are overridden to be transparent.
    """
    try:
        from PIL import Image
    except ImportError as error:  # pragma: no cover - developer machine setup
        raise AssertionError(
            "Pillow is required to regenerate the corner HUD assets (pip install pillow)"
        ) from error

    texture_root = MAIN_RESOURCES / f"assets/{NAMESPACE}/textures/mob_effect"
    padded_root = ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/textures/font/hud_effect"
    padded_root.mkdir(parents=True, exist_ok=True)
    for effect_id in CUSTOM_EFFECT_ICON_IDS:
        with Image.open(texture_root / f"{effect_id}.png") as source:
            icon = source.convert("RGBA")
        if icon.size != (18, 18):
            raise AssertionError(f"{effect_id}: expected an 18x18 icon, got {icon.size}")
        # The font renderer trims fully-transparent columns when measuring a
        # glyph.  A nearly invisible pixel on both vertical edges keeps every
        # icon at the full 18px width, so the advance is always 18 + 1.
        for x in (0, 17):
            if all(icon.getpixel((x, y))[3] == 0 for y in range(18)):
                icon.putpixel((x, 9), (255, 255, 255, 2))
        icon.save(padded_root / f"{effect_id}.png")

    sprite_root = ROOT / "src/paper/pack/resourcepack/assets/minecraft/textures/gui/sprites/boss_bar"
    sprite_root.mkdir(parents=True, exist_ok=True)
    transparent_bar = Image.new("RGBA", (182, 5), (0, 0, 0, 0))
    for sprite in ("yellow_background", "yellow_progress"):
        transparent_bar.save(sprite_root / f"{sprite}.png")

    advances: dict[str, int] = {}
    for index, power in enumerate(HUD_OFFSET_POWERS):
        advances[chr(0xE300 + index)] = power
        advances[chr(0xE310 + index)] = -power
    providers: list[dict[str, Any]] = [{"type": "space", "advances": advances}]
    # Boss bar text starts at y=3 with its baseline at y=10, so an ascent of
    # (10 - target_y) puts a glyph's top edge at the vanilla HUD coordinates:
    # frames at y=1/26 and their 18x18 icons inset by 3px.
    for bg_char, bg_ascent, icon_base, icon_ascent in (
            (HUD_BG_ROW1, 9, HUD_ICON_ROW1, 6),
            (HUD_BG_ROW2, -16, HUD_ICON_ROW2, -19),
    ):
        providers.append({
            "type": "bitmap",
            "file": "minecraft:gui/sprites/hud/effect_background.png",
            "ascent": bg_ascent,
            "height": 24,
            "chars": [chr(bg_char)],
        })
        for index, effect_id in enumerate(CUSTOM_EFFECT_ICON_IDS):
            providers.append({
                "type": "bitmap",
                "file": f"{NAMESPACE}:font/hud_effect/{effect_id}.png",
                "ascent": icon_ascent,
                "height": 18,
                "chars": [chr(icon_base + index)],
            })
    write_json(
        ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/font/custom_effects_hud.json",
        {"providers": providers},
    )


DYE_COLORS = (
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
    "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red",
    "black",
)


def string_lights_dye_events(block_id: str) -> list[dict[str, Any]]:
    """StringLightsBlock#use as CraftEngine block events.

    Each foreign dye recolours the lights in place (transform_block copies
    facing/waterlogged from the existing state), plays the dye-use sound,
    pops the growth particles and consumes one dye outside creative mode.
    The hand condition stops the duplicate off-hand interact event and
    cancel_event suppresses the vanilla item use afterwards; dyeing the
    current colour is intentionally absent, matching the source's no-op.
    """
    current = block_id.removeprefix("string_lights_")
    events: list[dict[str, Any]] = []
    for color in DYE_COLORS:
        if color == current:
            continue
        events.append({
            "on": "right_click",
            "conditions": [
                {"type": "match_item", "item": f"minecraft:{color}_dye"},
                {"type": "hand", "hand": "main_hand"},
            ],
            "functions": [
                {"type": "transform_block",
                 "block": f"{NAMESPACE}:string_lights_{color}"},
                {"type": "play_sound", "sound": "minecraft:item.dye.use",
                 "source": "block"},
                {"type": "particle", "particle": "minecraft:happy_villager",
                 "x": "<arg:position.x> + 0.5",
                 "y": "<arg:position.y> + 0.5",
                 "z": "<arg:position.z> + 0.5",
                 "count": 8,
                 "offset_x": 0.25, "offset_y": 0.25, "offset_z": 0.25},
                {"type": "set_count", "add": True, "count": -1,
                 "conditions": [{
                     "type": "expression",
                     "expression": "'<arg:player.gamemode>' != 'CREATIVE'",
                 }]},
                {"type": "swing_hand"},
                {"type": "cancel_event"},
            ],
        })
    return events


def create_molotov_charging_model() -> None:
    """A raised, tilted display variant shown while the molotov charges.

    The flat item barely moves under the vanilla SPEAR use pose, so the item
    model swaps on the minecraft:using_item condition - the same technique the
    vanilla trident uses - cocking the bottle up and back for a readable
    wind-up in both first and third person.
    """
    write_json(
        ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/models/item/molotov_charging.json",
        {
            "parent": f"{NAMESPACE}:item/molotov",
            "display": {
                "firstperson_righthand": {
                    "rotation": [-40, 0, -15],
                    "translation": [1.13, 5.2, 1.13],
                    "scale": [0.68, 0.68, 0.68],
                },
                "firstperson_lefthand": {
                    "rotation": [-40, 0, 15],
                    "translation": [-1.13, 5.2, 1.13],
                    "scale": [0.68, 0.68, 0.68],
                },
                "thirdperson_righthand": {
                    "rotation": [-80, 0, 0],
                    "translation": [0, 5.5, 2],
                    "scale": [0.55, 0.55, 0.55],
                },
                "thirdperson_lefthand": {
                    "rotation": [-80, 0, 0],
                    "translation": [0, 5.5, 2],
                    "scale": [0.55, 0.55, 0.55],
                },
            },
        },
    )


def create_worldgen_features() -> None:
    """Generate the CraftEngine worldgen features for wild grapevines.

    The chains are injected into real chunk generation through CraftEngine's
    configured/placed feature parsers instead of a Bukkit post-populate scan:
    an 8.3% (1/12) chunk rarity rolls 1-5 attempts, each scanning down from
    the world surface for air directly beneath oak or birch leaves with two
    free blocks, then hanging a 1-7 block column (body segments plus a head,
    all default states) exactly like WildGrapevineDecorator intended.
    """
    leaves = ["minecraft:oak_leaves", "minecraft:birch_leaves"]
    configured = {
        "type": "minecraft:block_column",
        "config": {
            "direction": "down",
            "allowed_placement": {
                "type": "minecraft:matching_blocks",
                "blocks": "minecraft:air",
            },
            "prioritize_tip": True,
            "layers": [
                {
                    "height": {
                        "type": "minecraft:uniform",
                        "min_inclusive": 0,
                        "max_inclusive": 6,
                    },
                    "provider": {
                        "type": "minecraft:simple_state_provider",
                        "state": {"Name": f"{NAMESPACE}:wild_grapevine_plant"},
                    },
                },
                {
                    "height": 1,
                    "provider": {
                        "type": "minecraft:simple_state_provider",
                        "state": {"Name": f"{NAMESPACE}:wild_grapevine"},
                    },
                },
            ],
        },
    }
    placed = {
        "dimensions": ["minecraft:overworld"],
        "feature": f"{NAMESPACE}:wild_grapevine_chain",
        "placement": [
            {"type": "minecraft:rarity_filter", "chance": 12},
            {
                "type": "minecraft:count",
                "count": {
                    "type": "minecraft:uniform",
                    "min_inclusive": 1,
                    "max_inclusive": 5,
                },
            },
            {"type": "minecraft:in_square"},
            {"type": "minecraft:heightmap", "heightmap": "WORLD_SURFACE"},
            {
                "type": "minecraft:environment_scan",
                "direction_of_search": "down",
                "max_steps": 32,
                "target_condition": {
                    "type": "minecraft:all_of",
                    "predicates": [
                        {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"},
                        {
                            "type": "minecraft:matching_blocks",
                            "offset": [0, 1, 0],
                            "blocks": leaves,
                        },
                    ],
                },
                "allowed_search_condition": {
                    "type": "minecraft:any_of",
                    "predicates": [
                        {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"},
                        {"type": "minecraft:matching_block_tag", "tag": "minecraft:leaves"},
                    ],
                },
            },
            {
                "type": "minecraft:block_predicate_filter",
                "predicate": {
                    "type": "minecraft:all_of",
                    "predicates": [
                        {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"},
                        {
                            "type": "minecraft:matching_blocks",
                            "offset": [0, -1, 0],
                            "blocks": "minecraft:air",
                        },
                    ],
                },
            },
        ],
    }
    write_json(ROOT / "src/paper/pack/configuration/worldgen.json", {
        "configured_features": {f"{NAMESPACE}:wild_grapevine_chain": configured},
        "placed_features": {f"{NAMESPACE}:wild_grapevine": placed},
    })


def create_pressing_fluid_models() -> None:
    """Create the six horizontal fluid surfaces rendered inside the tub."""
    model_root = ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/models/furniture/pressing_fluid"
    for fluid in sorted(PRESS_FLUIDS):
        texture = f"{NAMESPACE}:block/{fluid}_still"
        model = {
            "ambientocclusion": False,
            "render_type": "translucent",
            "textures": {"fluid": texture, "particle": texture},
            "elements": [{
                # RenderUtils.renderFluid(..., 12, y) used a centred 12x12
                # surface.  ItemDisplay/NONE centres model coordinates around
                # its origin, so y=8 becomes a zero-thickness local plane.
                "from": [2, 7.99, 2],
                "to": [14, 8.01, 14],
                "faces": {
                    "up": {"uv": [0, 0, 12, 12], "texture": "#fluid"},
                    "down": {"uv": [0, 0, 12, 12], "texture": "#fluid"},
                },
            }],
        }
        write_json(model_root / f"{fluid}.json", model)


def create_barrel_fluid_models() -> None:
    """Create the 16x16 fluid surface used by BarrelBlockEntityRender."""
    model_root = ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/models/furniture/barrel_fluid"
    for fluid in sorted(BARREL_FLUIDS):
        namespace = "minecraft" if fluid in {"water", "lava"} else NAMESPACE
        texture = f"{namespace}:block/{fluid}_still"
        face: dict[str, Any] = {"uv": [0, 0, 16, 16], "texture": "#fluid"}
        if fluid == "water":
            face["tintindex"] = 0
        model = {
            "ambientocclusion": False,
            "render_type": "translucent",
            "textures": {"fluid": texture, "particle": texture},
            "elements": [{
                "from": [0, 7.99, 0],
                "to": [16, 8.01, 16],
                "faces": {"up": face, "down": dict(face)},
            }],
        }
        write_json(model_root / f"{fluid}.json", model)


def create_bar_stool_body_models() -> None:
    """Split the source block-entity body from the authored inventory model.

    Forge rendered the pedestal as a block model and the upholstered upper
    body as a block entity so that only the latter could follow a passenger's
    body yaw.  The inventory model contains the same seven cuboids in one
    file: the first three are the pedestal and the final four are the moving
    seat/back/arms.  Paper uses the split body models from a real ItemDisplay
    while CraftEngine keeps the static pedestal.
    """
    source = read_json(
        MAIN_RESOURCES / f"assets/{NAMESPACE}/models/item/bar_stool_base.json"
    )
    if len(source.get("elements", [])) != 7:
        raise AssertionError("bar_stool_base must retain its 3 pedestal + 4 body cuboids")
    model_root = ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/models/furniture"
    body = {
        key: deepcopy(value)
        for key, value in source.items()
        if key not in {"display", "gui_light"}
    }
    body["elements"] = deepcopy(source["elements"][3:])
    write_json(model_root / "bar_stool_body_base.json", body)
    for color in BAR_STOOL_COLORS:
        write_json(model_root / "bar_stool_body" / f"{color}.json", {
            "parent": f"{NAMESPACE}:furniture/bar_stool_body_base",
            "textures": {
                "particle": f"minecraft:block/{color}_wool",
                "texture": f"{NAMESPACE}:block/deco/bar_stool/{color}",
            },
        })


def create_shaker_models() -> None:
    """Split ShakerModel's root body and animated bone2 around its pivot."""
    source = read_json(
        MAIN_RESOURCES / f"assets/{NAMESPACE}/models/item/shaker_3d.json"
    )
    if len(source.get("elements", [])) != 5:
        raise AssertionError("shaker_3d must retain its 2 body + 3 lid cuboids")
    model_root = ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/models"

    # Forge's generated item model is a forge:separate_transforms wrapper. A
    # vanilla 26.2 client ignores that loader, so the Paper item definition
    # performs the same GUI/FIXED selection with display_context. Keep both
    # vanilla child models available to that selector.
    write_json(model_root / "item/shaker.json", {
        "parent": "minecraft:item/generated",
        "textures": {"layer0": f"{NAMESPACE}:item/shaker"},
    })
    write_json(model_root / "item/shaker_3d.json", {
        key: deepcopy(value)
        for key, value in source.items()
        if key != "groups"
    })

    def model_with(elements: list[dict[str, Any]]) -> dict[str, Any]:
        return {
            key: deepcopy(value)
            for key, value in source.items()
            if key not in {"display", "groups"}
        } | {"elements": elements}

    write_json(model_root / "furniture/shaker_base.json", model_with(
        deepcopy(source["elements"][:2])))

    # bone2's authored pivot is [8, 12.16667, 8]. ItemDisplay rotates around
    # model centre [8, 8, 8], so shift the lid geometry to that centre; the
    # runtime adds the same 4.16667 pixels back as entity translation.
    pivot_delta = 12.16667 - 8.0
    lid_elements = deepcopy(source["elements"][2:])
    for element in lid_elements:
        element["from"][1] -= pivot_delta
        element["to"][1] -= pivot_delta
        rotation = element.get("rotation")
        if isinstance(rotation, dict) and isinstance(rotation.get("origin"), list):
            rotation["origin"][1] -= pivot_delta
    write_json(model_root / "furniture/shaker_lid.json", model_with(lid_elements))


def add_runtime_render_items(render_items: dict[str, Any]) -> None:
    """Add stable ids used by Paper-side block-entity visual emulation."""
    for block_id in sorted(STORAGE_RENDER_ITEMS):
        model = min(blockstate_records(block_id), key=record_score)[1]
        definition: dict[str, Any] = {
            "material": "paper",
            "data": {"item_name": render_item_name(block_id)},
            "model": {"type": "minecraft:model", "path": model[0]},
            "settings": {"tags": [f"{NAMESPACE}:internal_render_items"]},
        }
        if block_id == "potion_bottle":
            definition["model"]["tints"] = [{
                "type": "minecraft:potion",
                "default": -13083194,
            }]
        render_items[f"{NAMESPACE}:_render/storage/{block_id}"] = definition
    for fluid in sorted(PRESS_FLUIDS):
        render_items[f"{NAMESPACE}:_render/pressing_fluid/{fluid}"] = {
            "material": "paper",
            "data": {"item_name": fluid_render_name(fluid)},
            "model": {
                "type": "minecraft:model",
                "path": f"{NAMESPACE}:furniture/pressing_fluid/{fluid}",
            },
            "settings": {"tags": [f"{NAMESPACE}:internal_render_items"]},
        }
    for fluid in sorted(BARREL_FLUIDS):
        definition: dict[str, Any] = {
            "material": "paper",
            "data": {"item_name": fluid_render_name(fluid)},
            "model": {
                "type": "minecraft:model",
                "path": f"{NAMESPACE}:furniture/barrel_fluid/{fluid}",
            },
            "settings": {"tags": [f"{NAMESPACE}:internal_render_items"]},
        }
        if fluid == "water":
            definition["model"]["tints"] = [{
                "type": "minecraft:constant",
                "value": 0x3F76E4,
            }]
        render_items[f"{NAMESPACE}:_render/barrel_fluid/{fluid}"] = definition
    for color in BAR_STOOL_COLORS:
        render_items[f"{NAMESPACE}:_render/bar_stool_body/{color}"] = {
            "material": "paper",
            "data": {"item_name": render_item_name(f"{color}_bar_stool")},
            "model": {
                "type": "minecraft:model",
                "path": f"{NAMESPACE}:furniture/bar_stool_body/{color}",
            },
            "settings": {"tags": [f"{NAMESPACE}:internal_render_items"]},
        }
    for part in ("base", "lid"):
        render_items[f"{NAMESPACE}:_render/shaker_{part}"] = {
            "material": "paper",
            "data": {"item_name": render_item_name("shaker")},
            "model": {
                "type": "minecraft:model",
                "path": f"{NAMESPACE}:furniture/shaker_{part}",
            },
            "settings": {"tags": [f"{NAMESPACE}:internal_render_items"]},
        }


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

    def display_slots(
        positions: list[str],
        width: float,
        height: float,
        *,
        paper_visual: bool = False,
    ) -> None:
        for index, position in enumerate(positions):
            variant_rules: dict[str, Any] = {}
            for variant in variants:
                rule: dict[str, Any] = {
                    # Paper recreates every Forge storage renderer with its
                    # exact block-model transform and slot-selection math.
                    # Keep CE's controller solely as persistent storage and
                    # move its packet-only inventory sprite out of view.
                    "item_position": "0,-4096,0" if paper_visual else position,
                }
                if not paper_visual:
                    rule["hitboxes"] = [{
                        "type": "interaction",
                        "position": position,
                        "width": width,
                        "height": height,
                        "interactive": True,
                        "blocks_building": False,
                    }]
                variant_rules[variant] = rule
            behaviors.append({
                "type": "display_item_furniture",
                "data_key": f"{NAMESPACE}:display_slot_{index}",
                "sounds": {
                    "put": "minecraft:block.decorated_pot.insert",
                    "take": "minecraft:block.decorated_pot.insert_fail",
                },
                "variants": variant_rules,
            })

    if block_id in {"bar_cabinet", "glass_bar_cabinet"}:
        display_slots(["-0.25,0.5,0", "0.25,0.5,0"], 0.5, 1.0, paper_visual=True)
    elif block_id == "cellar_cabinet":
        # This was a visible 3x3 bottle cabinet, not a generic inventory GUI.
        display_slots([
            "0.325,0.78,0.375", "0,0.78,0.375", "-0.325,0.78,0.375",
            "0.325,0.49,0.375", "0,0.49,0.375", "-0.325,0.49,0.375",
            "0.325,0.20,0.375", "0,0.20,0.375", "-0.325,0.20,0.375",
        ], 0.25, 0.27, paper_visual=True)
    elif block_id == "tilted_rack":
        display_slots(["-0.375,0.32,0", "0,0.32,0", "0.375,0.32,0"],
                      0.3, 0.62, paper_visual=True)
    elif block_id == "circular_rack":
        display_slots([
            "0,0.13,-0.375", "0.375,0.13,-0.19", "0.375,0.13,0.19",
            "0,0.13,0.375", "-0.375,0.13,0.19", "-0.375,0.13,-0.19",
        ], 0.26, 0.46, paper_visual=True)
    elif block_id == "holder":
        display_slots(["0,0.13,0.25"], 0.4, 0.8, paper_visual=True)
    elif block_id == "glassware_holder":
        display_slots([
            "-0.25,-0.24,-0.25", "0.25,-0.24,-0.25",
            "-0.25,-0.24,0.25", "0.25,-0.24,0.25",
        ], 0.35, 0.35, paper_visual=True)

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
    # Forge BlockItem placement always occupied the target block centre.  Only
    # sandwich boards used ROTATION_16; bottles, paintings and other small
    # furniture retained horizontal cardinal facing.
    rotation = "sixteen" if block_id.endswith("_sandwich_board") else "four"
    for anchor in anchors:
        rules[anchor] = {"rotation": rotation, "alignment": "center"}
    return rules


def furniture_settings(block_id: str) -> dict[str, Any]:
    """Map each Forge block's declared SoundType and instant-break behavior."""
    if block_id.endswith("_sofa"):
        family = "wool"
    elif block_id in PENDANT_LAMPS:
        family = "chain"
    elif block_id.endswith("_incense"):
        family = "decorated_pot"
    elif block_id in {"tap", "glassware_holder"}:
        family = "metal"
    elif block_id == "shaker":
        family = "lantern"
    elif block_id in BOTTLE_AND_GLASS_ITEMS:
        family = "glass"
    else:
        family = "wood"
    instant_break = (
        block_id in BOTTLE_AND_GLASS_ITEMS
        or block_id == "shaker"
        or block_id.endswith("_incense")
    )
    return {
        "hit_times": 1 if instant_break else 3,
        "sounds": {
            action: f"minecraft:block.{family}.{action}"
            for action in ("break", "place", "hit")
        },
    }


def build_furniture(
    furniture_ids: list[str],
    item_ids: set[str],
) -> tuple[dict[str, Any], dict[str, Any], dict[str, dict[str, Any]], dict[str, int]]:
    furniture: dict[str, Any] = {}
    render_items: dict[str, Any] = {}
    placement: dict[str, dict[str, Any]] = {}
    metrics = {"furniture_variants": 0}
    create_chalkboard_models()
    create_barrel_models()

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
                "hitboxes": [interaction_box((-16, 2, 15, 32, 30, 16), "ground")],
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
                "facing": "south", "tilt": "true", "waterlogged": "false",
            })[1]
            variants["ground"] = {
                "elements": [furniture_element(render_items, block_id, "ground", normal, "ground")],
                "hitboxes": furniture_hitboxes(block_id, "ground"),
            }
            variants["wall"] = {
                "elements": [furniture_element(render_items, block_id, "wall", tilted, "wall")],
                "hitboxes": furniture_hitboxes(block_id, "wall"),
            }
        elif block_id == "table":
            # TableBlock can acquire either horizontal AXIS after placement;
            # its axis is not permanently tied to the player's initial yaw.
            # Keep both authored model axes so FurnitureConnectionService can
            # reproduce that state transition when neighbours change.
            for axis in ("x", "z"):
                for position in range(4):
                    if axis == "z" and position == 0:
                        continue
                    selected = select_record(records, {
                        "axis": axis, "position": str(position), "waterlogged": "false",
                    })[1]
                    name = "ground" if position == 0 else f"ground_axis_{axis}_position_{position}"
                    variants[name] = {
                        "elements": [furniture_element(render_items, block_id, name, selected, "ground")],
                        "hitboxes": furniture_hitboxes(block_id, "ground", {"position": str(position)}),
                    }
        elif block_id == "barrel":
            closed_model = (f"{NAMESPACE}:furniture/barrel_closed", 0, 0, 0, False)
            body_model = (f"{NAMESPACE}:furniture/barrel_body", 0, 0, 0, False)
            lid_model = (f"{NAMESPACE}:furniture/barrel_open_lid", 0, 0, 0, False)
            closed = furniture_element(
                render_items, block_id, "closed", closed_model, "ground", "0,1,0")
            closed["view_range"] = 2.5
            # Same override as the open variant so opening a barrel does not make
            # it visibly change shade.
            closed["brightness"] = {"block_light": 6, "sky_light": 6}
            variants["ground"] = {
                "elements": [closed],
                "hitboxes": furniture_hitboxes(block_id, "ground"),
            }
            body = furniture_element(
                render_items, block_id, "open body", body_model, "ground", "0,1,0")
            lid = furniture_element(
                render_items, block_id, "open lid", lid_model, "ground", "0,2.5,0.5")
            lid["rotation"] = "72.5,0,0"
            body["view_range"] = lid["view_range"] = 2.5
            # An item display samples the light where its own entity sits, and the
            # barrel's 3x3x3 collider makes that centre pitch black, so the cavity
            # walls came out as silhouettes while only the rim caught sky light.
            # BarrelBlockEntityRender instead received the block position's combined
            # light. CraftEngine only honours a brightness override when both
            # channels are given, and it cannot track a neighbour's light level, so
            # pin one modest level: bright enough to read the staves and the floor,
            # dim enough that the barrel never looks emissive in a dark cellar.
            body["brightness"] = {"block_light": 6, "sky_light": 6}
            lid["brightness"] = {"block_light": 6, "sky_light": 6}
            variants["ground_open"] = {
                "elements": [body, lid],
                "hitboxes": furniture_hitboxes(block_id, "ground"),
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
                facing = "south" if anchor == "wall" else "north"
                selected = select_record(records, {"face": face, "facing": facing, "waterlogged": "false"})[1]
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
                # CraftEngine's wall yaw already points along the clicked
                # face.  TapBlock's authored north state has its mounting
                # plate at z=16 and nozzle toward z=6, so an additional 180°
                # blockstate rotation reverses it.
                preferred_facing = "north"
                preferred = [candidate for candidate in candidates
                             if candidate[0].get("facing") == preferred_facing]
                selected = min(preferred or candidates, key=record_score)[1]
                name = semantic_variant_name(anchor, semantic, index)
                if name in used_names:
                    name = f"{name}_{index}"
                used_names.add(name)
                variants[name] = {
                    "elements": [furniture_element(render_items, block_id, name, selected, anchor)],
                    "hitboxes": furniture_hitboxes(block_id, anchor, dict(semantic)),
                }

        config: dict[str, Any] = {
            "settings": furniture_settings(block_id),
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


HARMFUL_DRINK_EFFECTS = {
    "minecraft:bad_omen", "minecraft:blindness", "minecraft:mining_fatigue",
    "minecraft:nausea",
}
NEUTRAL_DRINK_EFFECTS = {
    f"{NAMESPACE}:slightly_tipsy", f"{NAMESPACE}:upside_down",
}
MANAGED_LORE_INSERTION = "kaleidoscope_tavern_managed_lore"

# effect id -> (attribute translation key, base amount, operation id).
# Operation 0 is an absolute addition and operation 1 is a percentage, matching
# the vanilla attribute.modifier translation keys used by PotionUtils.
DRINK_EFFECT_ATTRIBUTES: dict[str, list[tuple[str, float, int]]] = {
    "minecraft:speed": [("attribute.name.movement_speed", 0.2, 1)],
    "minecraft:strength": [("attribute.name.attack_damage", 3.0, 0)],
    f"{NAMESPACE}:high_heels": [
        ("attribute.name.step_height", 0.5, 0),
    ],
    f"{NAMESPACE}:long_reach": [
        ("attribute.name.block_interaction_range", 3.0, 0),
        ("attribute.name.entity_interaction_range", 3.0, 0),
    ],
}


def format_effect_duration(ticks: int) -> str:
    total_seconds = max(1, ticks // 20)
    hours, remainder = divmod(total_seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    if hours:
        return f"{hours}:{minutes:02d}:{seconds:02d}"
    return f"{minutes}:{seconds:02d}"


def mini_message_quote(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"


def translatable_component(key: str, *arguments: str) -> str:
    suffix = "".join(f":{mini_message_quote(argument)}" for argument in arguments)
    return f"<lang:{key}{suffix}>"


def format_attribute_amount(amount: float, operation: int) -> str:
    displayed = abs(amount) * (100.0 if operation in {1, 2} else 1.0)
    return f"{displayed:.12f}".rstrip("0").rstrip(".")


def fixed_cocktail_lore(item_id: str, effect_rows: list[list[Any]]) -> list[str]:
    """Build the creative-menu preview for cocktails whose effects are fixed."""
    full_id = f"{NAMESPACE}:{item_id}"
    lore: list[str] = []
    attributes: list[tuple[str, float, int]] = []
    for effect_item, level, effect_id, duration, amplifier, probability in effect_rows:
        if effect_item != full_id or level != 1:
            continue
        namespace, path = effect_id.split(":", 1)
        color = "red" if effect_id in HARMFUL_DRINK_EFFECTS else (
            "gray" if effect_id in NEUTRAL_DRINK_EFFECTS else "blue")
        effect = translatable_component(f"effect.{namespace}.{path}")
        if amplifier > 0:
            effect = translatable_component(
                "potion.withAmplifier", effect,
                translatable_component(f"potion.potency.{amplifier}"))
        if duration > 0:
            effect = translatable_component(
                "potion.withDuration", effect, format_effect_duration(duration))
        line = (f"<!i><insert:{MANAGED_LORE_INSERTION}><{color}>" + effect)
        if probability < 1.0:
            chance = f"{probability * 100:g}%"
            line += " <dark_gray>" + translatable_component(
                f"tooltip.{NAMESPACE}.drink_effect.chance", chance)
        lore.append(line)

        if probability >= 1.0:
            for attribute_key, base_amount, operation in DRINK_EFFECT_ATTRIBUTES.get(effect_id, []):
                attributes.append((attribute_key, base_amount * (amplifier + 1), operation))

    if attributes:
        lore.append(f"<!i><insert:{MANAGED_LORE_INSERTION}>")
        lore.append(f"<!i><insert:{MANAGED_LORE_INSERTION}><dark_purple>"
                    + translatable_component("potion.whenDrank"))
        for attribute_key, amount, operation in attributes:
            sign = "plus" if amount >= 0 else "take"
            modifier = translatable_component(
                f"attribute.modifier.{sign}.{operation}",
                format_attribute_amount(amount, operation),
                translatable_component(attribute_key))
            color = "blue" if amount >= 0 else "red"
            lore.append(f"<!i><insert:{MANAGED_LORE_INSERTION}><{color}>{modifier}")
    return lore


# Exact RGB values returned by the Forge ChatFormatting entries in ColorUtils.
DRINK_COLORS: dict[str, int] = {
    "black": 0x000000, "dark_blue": 0x0000AA, "dark_green": 0x00AA00,
    "dark_aqua": 0x00AAAA, "dark_red": 0xAA0000, "dark_purple": 0xAA00AA,
    "gold": 0xFFAA00, "gray": 0xAAAAAA, "dark_gray": 0x555555,
    "blue": 0x5555FF, "green": 0x55FF55, "aqua": 0x55FFFF,
    "red": 0xFF5555, "light_purple": 0xFF55FF, "yellow": 0xFFFF55,
    "white": 0xFFFFFF,
}


def drink_color(item_tags: list[str]) -> int | None:
    prefix = f"{NAMESPACE}:cocktail_ingredient_"
    for tag in item_tags:
        if tag.startswith(prefix):
            return DRINK_COLORS.get(tag[len(prefix):])
    return None


def is_drink(item_id: str, effect_drink_ids: set[str]) -> bool:
    return (item_id in effect_drink_ids
            or item_id in EFFECTLESS_DRINKS
            or item_id == "signature_cocktail")


def material_for(item_id: str, drink_ids: set[str], block_ids: set[str]) -> str:
    if is_drink(item_id, drink_ids):
        return "potion"
    if item_id == "molotov":
        # MolotovBlockItem is a 72,000-tick spear-animation charge item, not an
        # instantly-thrown vanilla splash potion. The consumable component
        # below supplies client use state while MolotovService handles release.
        return "paper"
    if item_id.endswith("_bucket"):
        # JuiceBucketItem is a drinkable, effect-clearing milk-bucket analogue.
        return "milk_bucket"
    if item_id in GRAPE_ITEMS:
        return "sweet_berries"
    if item_id == "shaker":
        # A real BrushItem can brush suspicious blocks and take durability
        # while StationService is driving the long use state.
        return "paper"
    if item_id in block_ids:
        return "paper"
    return "paper"


def item_name_key(
    item_id: str,
    placeable_ids: set[str],
    language_keys: set[str],
) -> str:
    generic = GENERIC_ITEM_NAME_KEYS.get(item_id)
    if generic is not None:
        if generic not in language_keys:
            raise KeyError(f"Missing generic item-name translation {generic}")
        return generic

    prefixes = ("block", "item") if item_id in placeable_ids else ("item", "block")
    for prefix in prefixes:
        candidate = f"{prefix}.{NAMESPACE}.{item_id}"
        if candidate in language_keys:
            return candidate
    raise KeyError(f"No item-name translation for {NAMESPACE}:{item_id}")


def build_items(
    item_ids: list[str],
    block_ids: set[str],
    furniture_ids: set[str],
    furniture_placement: dict[str, dict[str, Any]],
    drink_ids: set[str],
    effect_rows: list[list[Any]],
    tags: dict[str, list[str]],
    language_keys: set[str],
) -> dict[str, Any]:
    memberships: dict[str, list[str]] = defaultdict(list)
    custom_prefix = f"{NAMESPACE}:"
    for tag, members in tags.items():
        for member in members:
            if member.startswith(custom_prefix):
                memberships[member.split(":", 1)[1]].append(tag)

    items: dict[str, Any] = {}
    placeable_ids = block_ids | furniture_ids
    for item_id in item_ids:
        model = find_file(ITEM_MODELS, Path(f"{item_id}.json"))
        if model is None:
            raise FileNotFoundError(f"No item model for {item_id}")
        config: dict[str, Any] = {
            "material": material_for(item_id, drink_ids, block_ids),
            "data": {
                "item_name": f"<!i><lang:{item_name_key(item_id, placeable_ids, language_keys)}>"
            },
            "model": {"type": "minecraft:model", "path": f"{NAMESPACE}:item/{item_id}"},
        }
        behaviors: list[dict[str, Any]] = []
        # Drinks keep vanilla potion consumption. Their sneak-placement is
        # performed by the Paper layer; attaching CE's unconditional
        # furniture_item behavior here would place a bottle on every normal
        # right-click instead of drinking it.
        manually_placed_drink = is_drink(item_id, drink_ids)
        if item_id in BOTTLE_AND_GLASS_ITEMS or item_id.endswith("_bucket"):
            # The Forge BottleBlockItem/GlasswareBlockItem hierarchy stacks to
            # 16. Potion is used as the server-side material for drinking, but
            # its vanilla stack limit is 1, so preserve the original component
            # explicitly for both drink and place-only bottle items.
            config["data"]["components"] = {"minecraft:max_stack_size": 16}
        if config["material"] == "potion":
            # PotionItem#getName derives its title from potion_contents and does
            # not honor ITEM_NAME. A valid neutral base avoids "Uncraftable
            # Potion", while CUSTOM_NAME wins in ItemStack#getHoverName and
            # retains the authored drink name. Tagged drinks also keep their
            # source liquid tint.
            components = config["data"].setdefault("components", {})
            potion_contents: dict[str, Any] = {"potion": "minecraft:water"}
            color = drink_color(memberships.get(item_id, []))
            if color is not None:
                potion_contents["custom_color"] = color
            components["minecraft:potion_contents"] = potion_contents
            config["data"]["custom_name"] = config["data"]["item_name"]
            # Drink effects are implemented by the Paper service rather than
            # potion_contents. Hide only that vanilla tooltip section so it
            # cannot claim "No Effects" while retaining custom lore and names.
            config["data"]["hide_tooltip"] = ["minecraft:potion_contents"]
        if item_id == "shaker":
            config["data"].setdefault("components", {}).update({
                "minecraft:max_stack_size": 1,
                "minecraft:consumable": {
                    "consume_seconds": 3_600.0,
                    "animation": "brush",
                    "has_consume_particles": False,
                },
            })
            # This is the vanilla item-model equivalent of the source
            # forge:separate_transforms wrapper: inventory/item-frame views
            # use the authored icon, while held/dropped/head views retain the
            # complete cuboid model and its original display transforms.
            config["model"] = {
                "type": "minecraft:select",
                "property": "display_context",
                "cases": [{
                    "when": ["gui", "fixed"],
                    "model": {
                        "type": "minecraft:model",
                        "path": f"{NAMESPACE}:item/shaker",
                    },
                }],
                "fallback": {
                    "type": "minecraft:model",
                    "path": f"{NAMESPACE}:item/shaker_3d",
                },
            }
        if item_id == "molotov":
            config["data"].setdefault("components", {})["minecraft:consumable"] = {
                "consume_seconds": 3_600.0,
                "animation": "spear",
                "has_consume_particles": False,
            }
            # The trident technique: swap to the cocked-back display model
            # while the throw charges, because the SPEAR pose alone is nearly
            # invisible on a flat item.
            config["model"] = {
                "type": "minecraft:condition",
                "property": "minecraft:using_item",
                "on_true": {
                    "type": "minecraft:model",
                    "path": f"{NAMESPACE}:item/molotov_charging",
                },
                "on_false": {
                    "type": "minecraft:model",
                    "path": f"{NAMESPACE}:item/molotov",
                },
            }
        lore_keys: list[str] = []
        if item_id == "grapevine":
            lore_keys = [f"tooltip.{NAMESPACE}.grapevine.{index}" for index in range(1, 4)]
        elif item_id == "trellis":
            lore_keys = [f"tooltip.{NAMESPACE}.trellis.{index}" for index in range(1, 3)]
        elif item_id in GRAPE_ITEMS or item_id.endswith("_bucket"):
            lore_keys = [f"tooltip.{NAMESPACE}.{item_id}"]
        elif item_id in PAINTINGS:
            # PaintingBlock shared the generic item name and exposed the
            # concrete artwork through its per-registry-id tooltip.
            lore_keys = [f"tooltip.{NAMESPACE}.{item_id}"]
        if lore_keys:
            config["data"]["lore"] = [f"<!i><gray><lang:{key}>" for key in lore_keys]
        elif item_id in CONSUMABLE_COCKTAILS:
            # Creative-menu entries bypass ItemService, so fixed cocktails
            # need a generated preview. Aged wines and signature cocktails are
            # rebuilt at runtime because their effects live on the item stack.
            cocktail_lore = fixed_cocktail_lore(item_id, effect_rows)
            if cocktail_lore:
                config["data"]["lore"] = cocktail_lore
        if item_id in GRAPE_ITEMS:
            config["data"]["food"] = {
                "nutrition": 2,
                "saturation": 2.0,
                "can_always_eat": True,
            }
        if item_id in furniture_ids and not manually_placed_drink:
            furniture_behavior: dict[str, Any] = {
                "type": "furniture_item",
                "furniture": f"{NAMESPACE}:{item_id}",
                "rules": furniture_placement[item_id],
            }
            if item_id in PAINTINGS:
                # CE Interaction hitboxes cannot reproduce a 1/16-thick wall
                # shape and otherwise reject placement while the player is
                # standing close enough to click the support block.
                furniture_behavior["ignore_placer"] = True
            behaviors.append(furniture_behavior)
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
        # IHasContainer#returnContainerToEntity: CraftEngine's own
        # consume_replacement setting now hands back the empty vessel, so the
        # plugin no longer rewrites PlayerItemConsumeEvent replacements.
        if item_id in CONSUMABLE_COCKTAILS:
            config.setdefault("settings", {})["consume_replacement"] = (
                f"{NAMESPACE}:empty_glassware")
        elif is_drink(item_id, drink_ids):
            config.setdefault("settings", {})["consume_replacement"] = (
                f"{NAMESPACE}:empty_bottle")
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
    language_keys = set(read_json(EN_US))

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
    create_pressing_fluid_models()
    create_barrel_fluid_models()
    create_pendant_lamp_models()
    create_custom_effect_font()
    create_custom_effect_hud_assets()
    create_molotov_charging_model()
    create_worldgen_features()
    create_bar_stool_body_models()
    create_shaker_models()
    add_runtime_render_items(render_items)
    items = build_items(
        item_ids,
        set(block_ids),
        set(furniture_ids),
        furniture_placement,
        drink_ids,
        effect_rows,
        tags,
        language_keys,
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
