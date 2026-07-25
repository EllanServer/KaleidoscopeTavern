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
PLUGIN_CONFIG = ROOT / "src/paper/resources/config.yml"
NAMESPACE = "kaleidoscope_tavern"
EN_US = ROOT / f"src/main/resources/assets/{NAMESPACE}/lang/en_us.json"
ZH_CN = ROOT / f"src/main/resources/assets/{NAMESPACE}/lang/zh_cn.json"
MOD_BLOCKS = ROOT / f"src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/init/ModBlocks.java"
SOURCE_RENDERERS = ROOT / f"src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/client/render/block"
SOURCE_BLOCKS = ROOT / f"src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/block"
SOURCE_ITEMS = ROOT / f"src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/item"
SOURCE_BLOCK_ENTITIES = ROOT / f"src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/blockentity"
SOURCE_TAP_BEHAVIORS = ROOT / f"src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/game/tap/impl"
SOURCE_EFFECTS = ROOT / f"src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/effect"
SOURCE_EVENTS = ROOT / f"src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/event"
SOURCE_ENTITIES = ROOT / f"src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/entity"
SOURCE_ASSET_ROOTS = (
    ROOT / "src/generated/resources/assets",
    ROOT / "src/main/resources/assets",
)
ASSET_ROOTS = (
    ROOT / "src/paper/pack/resourcepack/assets",
    ROOT / "src/generated/resources/assets",
    ROOT / "src/main/resources/assets",
)
OBSOLETE_VANILLA_IDS = {"minecraft:chain", "minecraft:grass"}

# Every source blockstate property is intentionally assigned to either a CE
# representation or a named Paper runtime owner.  Comparing this manifest to
# the source blockstates makes newly introduced semantics fail validation
# instead of silently disappearing during migration.
SOURCE_STATE_OWNERS = {
    "age": "CustomCrops stage blocks",
    "axis": "FurnitureConnectionService yaw/line selection",
    "connection": "FurnitureConnectionService",
    "count": "BottleFurnitureService",
    "face": "CE ground/wall/ceiling placement rules",
    "facing": "CE four-way/sixteen-way furniture rotation",
    "half": "composite multi-element furniture variants",
    "open": "StationService and TapService",
    "position": "FurnitureConnectionService",
    "powered": "StationService and DisplayStorageService redstone polling",
    "rotation": "CE sixteen-way sandwich-board rotation",
    "tilt": "ground/wall pressing-tub placement variants",
    "triggered": "TapService",
    "type": "CE trellis state variants",
    "waterlogged": "entity furniture / CE non-displacing carrier semantics",
    "waxed": "CE trellis state variants",
}

# Block-entity renderers are the easiest source of an apparently valid but
# invisible/incomplete port.  This closed manifest forces each renderer onto a
# concrete Paper implementation; a newly added source renderer cannot pass CI
# without an explicit migration decision.
RENDERER_COVERAGE = {
    "BarCabinetBlockEntityRender.java": ("DisplayStorageService.java", "StorageSemantics"),
    "BarrelBlockEntityRender.java": ("StationService.java", "BarrelSemantics"),
    "BarStoolBlockEntityRender.java": ("BarStoolVisualService.java", "getBodyYaw"),
    "CellarCabinetBlockEntityRender.java": ("DisplayStorageService.java", "StorageSemantics"),
    "ChalkboardBlockEntityRender.java": ("BoardTextService.java", "chalkboard"),
    "CircularRackBlockEntityRender.java": ("DisplayStorageService.java", "StorageSemantics"),
    "GlasswareHolderBlockEntityRender.java": ("DisplayStorageService.java", "StorageSemantics"),
    "HolderBlockEntityRender.java": ("DisplayStorageService.java", "StorageSemantics"),
    "PressingTubBlockEntityRender.java": ("StationService.java", "refreshPressVisuals"),
    "SandwichBlockEntityRender.java": ("BoardTextService.java", "sandwich"),
    "ShakerBlockEntityRender.java": ("ShakerVisualService.java", "ShakerAnimationSemantics"),
    "StorageBlockEntityRender.java": ("DisplayStorageService.java", "StorageSemantics"),
    "TextBlockEntityRender.java": ("BoardTextService.java", "TextDisplay"),
    "TiltedRackBlockEntityRender.java": ("DisplayStorageService.java", "StorageSemantics"),
}

# Runtime semantics need the same closed-world treatment as renderers. These
# methods are the source entry points that change inventories, entities,
# blocks, player state or projectiles. Every class containing one must name a
# concrete Paper/CE owner and an evidence token, so a new source interaction
# cannot silently become a decorative-only port.
RUNTIME_METHODS = (
    "use", "useOn", "onUseTick", "releaseUsing", "finishUsingItem",
    "neighborChanged", "fallOn", "onProjectileHit", "destroy",
    "execute", "animateTick", "randomTick", "performBonemeal", "pickupBlock",
    "getBurnTime", "getEquipmentSlot",
)
RUNTIME_BEHAVIOR_COVERAGE = {
    "AbstractStorageBlock.java": (("DisplayStorageService.java", "pollRedstone"),),
    "BarCabinetBlock.java": (
        ("DisplayStorageService.java", "BAR_CABINET"),
        ("FurnitureConnectionService.java", "bar_cabinet"),
    ),
    "BarStoolBlock.java": (
        ("tools/migrate_legacy.py", "_bar_stool"),
        ("BarStoolVisualService.java", "onMount"),
    ),
    "BarrelBlock.java": (("StationService.java", "interactBarrel"),),
    "BottleBlock.java": (("BottleFurnitureService.java", "onInteract"),),
    "BottleBlockDispenseBehavior.java": (
        ("BottlePlacementService.java", "onDispenseBottle"),
    ),
    "CellarCabinetBlock.java": (
        ("DisplayStorageService.java", "CELLAR_CABINET"),
        ("FurnitureConnectionService.java", "cellar_cabinet"),
    ),
    "ChalkboardBlock.java": (("BoardTextService.java", "CHALKBOARD"),),
    "CircularRackBlock.java": (
        ("DisplayStorageService.java", "CIRCULAR_RACK"),
        ("AmbientFurnitureService.java", "tickCircularRack"),
    ),
    "CocktailBlockItem.java": (
        ("EffectService.java", "onConsume"),
        ("BottlePlacementService.java", "onPlaceVanillaBottle"),
    ),
    "DrinkBlock.java": (("BottleFurnitureService.java", "onProjectileHit"),),
    "DrinkBlockItem.java": (
        ("EffectService.java", "onConsume"),
        ("BottlePlacementService.java", "onPlaceVanillaBottle"),
    ),
    "GlasswareBlock.java": (("BottleFurnitureService.java", "onProjectileHit"),),
    "GlasswareHolderBlock.java": (("DisplayStorageService.java", "GLASSWARE_HOLDER"),),
    "GrapeCropBlock.java": (
        ("block/HangingGrapeCropBehavior.java", "addGrowthPoints"),
        ("src/paper/customcrops/contents/crops/kaleidoscope_tavern.yml", "harvest_with_shears"),
    ),
    "GrapevineItem.java": (("tools/migrate_legacy.py", '"fuel_time"'),),
    "GrapevineTrellisBlock.java": (
        ("block/BlockService.java", "interactVineTrellis"),
        ("block/TrellisBehavior.java", "public static boolean grow"),
    ),
    "HolderBlock.java": (("DisplayStorageService.java", "HOLDER"),),
    "IncenseBlock.java": (
        ("StationService.java", "interactIncense"),
        ("AmbientFurnitureService.java", "tickIncense"),
    ),
    "JuiceBucketItem.java": (("tools/migrate_legacy.py", "milk_bucket"),),
    "MolotovBlock.java": (("MolotovService.java", "onProjectileHit"),),
    "MolotovBlockItem.java": (
        ("MolotovService.java", "onStopUsing"),
        ("tools/migrate_legacy.py", "consume_seconds"),
    ),
    "MysteryCocktailBlock.java": (
        ("AmbientFurnitureService.java", "tickMysteryCocktail"),
    ),
    "PressingTubBlock.java": (
        ("StationService.java", "interactPress"),
        ("StationService.java", "pressOne"),
    ),
    "SandwichBoardBlock.java": (("BoardTextService.java", "transformSandwichBoard"),),
    "ShakerBlock.java": (("StationService.java", "interactShaker"),),
    "ShakerItem.java": (
        ("StationService.java", "onUsePortableShaker"),
        ("ShakerSemantics.java", "AUTO_RELEASE_AFTER_TICKS"),
    ),
    "SofaBlock.java": (
        ("tools/migrate_legacy.py", "_sofa"),
        ("FurnitureConnectionService.java", "connectionFor"),
    ),
    "StringLightsBlock.java": (("block/BlockService.java", "interactStringLights"),),
    "StringLightsBlockItem.java": (
        ("src/paper/resources/catalog/tags.tsv", "curios:charm"),
    ),
    "TapBlock.java": (
        ("TapService.java", "openTap"),
        ("TapSemantics.java", "isBarrelConnection"),
    ),
    "TiltedRackBlock.java": (("DisplayStorageService.java", "TILTED_RACK"),),
    "TrellisBlock.java": (
        ("block/BlockService.java", "interactPlainTrellis"),
        ("block/TrellisBehavior.java", "updateStateForPlacement"),
    ),
    "WildGrapevineBlock.java": (
        ("block/BlockService.java", "interactWildHead"),
        ("block/WildGrapevineBehavior.java", "randomTick"),
    ),
}

TAP_BEHAVIOR_COVERAGE = {
    "BarrelTapBehavior.java": ("TapService.java", "BOTTLE_BARREL"),
    "BeehiveTapBehavior.java": ("TapService.java", "BOTTLE_HONEY"),
    "DragonHeadTapBehavior.java": ("TapService.java", "BOTTLE_DRAGON_BREATH"),
    "LavaCauldronTapBehavior.java": ("TapService.java", "FILL_LAVA_CAULDRON"),
    "WaterCauldronTapBehavior.java": ("TapService.java", "FILL_WATER_CAULDRON"),
    "WaterloggedBehavior.java": ("TapService.java", "BOTTLE_WATER"),
    "WatermelonTapBehavior.java": ("TapService.java", "BOTTLE_WATERMELON"),
}

TICKING_BLOCK_ENTITY_COVERAGE = {
    "BarrelBlockEntity.java": ("StationService.java", "tickBarrels"),
    "BarStoolBlockEntity.java": ("BarStoolVisualService.java", "tickOccupied"),
    "TapBlockEntity.java": ("TapService.java", "TAKE_PARTICLE_TICKS"),
    "TextBlockEntity.java": ("BoardTextService.java", "validateEditDistance"),
}

# Non-block runtime systems are part of source parity too. Keeping these
# manifests closed prevents a future effect, event hook, projectile, seat, or
# persistent block entity from being copied as assets while losing behavior.
EFFECT_BEHAVIOR_COVERAGE = {
    "ArdentHeatEffect.java": (("EffectService.java", "ardentHeat"),),
    "BaseEffect.java": (
        ("EffectService.java", "slightly_tipsy"),
        ("EffectService.java", "bloody_mary"),
        ("EffectService.java", "tomb_raider"),
    ),
    "GrassStealthEffect.java": (("EffectService.java", "grassStealth"),),
    "HighHeelsEffect.java": (("EffectService.java", "Attribute.STEP_HEIGHT"),),
    "LongReachEffect.java": (("EffectService.java", "Attribute.BLOCK_INTERACTION_RANGE"),),
    "ShriekAttackEffect.java": (("EffectService.java", "DamageType.SONIC_BOOM"),),
    "UpsideDownEffect.java": (("EffectService.java", "upside_down"),),
    "VisionEffect.java": (("EffectService.java", "void vision"),),
    "XpDrainEffect.java": (("EffectService.java", "xpDrain"),),
    "ZenithEffect.java": (("EffectService.java", "zenith"),),
}

EVENT_BEHAVIOR_COVERAGE = {
    "AddFeaturesEvent.java": (("WorldgenService.java", "wild_grapevine_generation"),),
    "ChangeTargetEvent.java": (("EffectService.java", "onTarget"),),
    "EffectEvent.java": (
        ("EffectService.java", "onDeath"),
        ("EffectService.java", "onDamage"),
        ("EffectService.java", "ardentHeat"),
    ),
    "VanillaBottlePlaceEvent.java": (
        ("BottlePlacementService.java", "onPlaceVanillaBottle"),
    ),
}

ENTITY_BEHAVIOR_COVERAGE = {
    "SitEntity.java": (
        ("tools/migrate_legacy.py", "_sofa"),
        ("tools/migrate_legacy.py", "_bar_stool"),
    ),
    "ThrownMolotovEntity.java": (("MolotovService.java", "spreadFire"),),
}

BLOCK_ENTITY_COVERAGE = {
    "BarCabinetBlockEntity.java": (("DisplayStorageService.java", "BAR_CABINET"),),
    "BarrelBlockEntity.java": (("StationService.java", "barrel_ingredients"),),
    "CellarCabinetBlockEntity.java": (("DisplayStorageService.java", "CELLAR_CABINET"),),
    "DrinkBlockEntity.java": (("BottleFurnitureService.java", "bottle_items"),),
    "PotionBottleBlockEntity.java": (("BottlePlacementService.java", "placed_potion"),),
    "PressingTubBlockEntity.java": (("StationService.java", "press_count"),),
    "TapBlockEntity.java": (("TapService.java", "running"),),
    "BarStoolBlockEntity.java": (("BarStoolVisualService.java", "refreshBody"),),
    "ChalkboardBlockEntity.java": (("BoardTextService.java", "CHALKBOARD"),),
    "CircularRackBlockEntity.java": (
        ("DisplayStorageService.java", "CIRCULAR_RACK"),
        ("AmbientFurnitureService.java", "tickCircularRack"),
    ),
    "GlasswareHolderBlockEntity.java": (("DisplayStorageService.java", "GLASSWARE_HOLDER"),),
    "HolderBlockEntity.java": (("DisplayStorageService.java", "HOLDER"),),
    "IncenseBlockEntity.java": (
        ("StationService.java", "pulseIncense"),
        ("AmbientFurnitureService.java", "tickIncense"),
    ),
    "SandwichBlockEntity.java": (("BoardTextService.java", "isSandwichBoard"),),
    "StorageBlockEntity.java": (("DisplayStorageService.java", "StorageSpec"),),
    "TextBlockEntity.java": (("BoardTextService.java", "board_text"),),
    "TiltedRackBlockEntity.java": (("DisplayStorageService.java", "TILTED_RACK"),),
    "ShakerBlockEntity.java": (
        ("StationService.java", "shaker_ingredients"),
        ("ShakerVisualService.java", "animatePut"),
    ),
    "SignatureCocktailBlockEntity.java": (
        ("StationService.java", "signature_cocktail"),
        ("src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/item/ItemService.java",
         "signatureColor"),
    ),
}


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


def asset_json(resource_id: str, folder: str, roots=ASSET_ROOTS) -> dict[str, Any] | None:
    namespace, path = resource_id.split(":", 1)
    relative = Path(namespace) / folder / f"{path}.json"
    for root in roots:
        candidate = root / relative
        if candidate.is_file():
            with candidate.open("r", encoding="utf-8-sig") as stream:
                return json.load(stream)
    return None


def model_references(value: Any):
    if isinstance(value, dict):
        model = value.get("model")
        if isinstance(model, str):
            yield model
        for child in value.values():
            yield from model_references(child)
    elif isinstance(value, list):
        for child in value:
            yield from model_references(child)


def model_has_geometry(resource_id: str, roots=ASSET_ROOTS, seen=frozenset()) -> bool:
    if resource_id in seen:
        return False
    model = asset_json(resource_id, "models", roots)
    if model is None:
        # Vanilla model parents such as minecraft:block/cross provide their
        # geometry outside this repository. A missing custom model is caught
        # separately by asset_exists().
        return resource_id.startswith("minecraft:")
    if model.get("elements"):
        return True
    parent = model.get("parent")
    return isinstance(parent, str) and model_has_geometry(parent, roots, seen | {resource_id})


def source_registry_ids() -> list[str]:
    source = MOD_BLOCKS.read_text(encoding="utf-8-sig")
    return re.findall(r'BLOCKS\.register\("([a-z0-9_]+)"', source)


def source_state_properties(block_ids: list[str]) -> set[str]:
    result: set[str] = set()

    def collect_when(value: Any) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                if key in {"OR", "AND"}:
                    collect_when(child)
                else:
                    result.add(key)
        elif isinstance(value, list):
            for child in value:
                collect_when(child)

    for block_id in block_ids:
        blockstate = asset_json(
            f"{NAMESPACE}:{block_id}", "blockstates", SOURCE_ASSET_ROOTS)
        if blockstate is None:
            raise AssertionError(f"Source block {block_id} has no blockstate")
        for selector in blockstate.get("variants", {}):
            for assignment in selector.split(","):
                if "=" in assignment:
                    result.add(assignment.split("=", 1)[0])
        for part in blockstate.get("multipart", []):
            collect_when(part.get("when", {}))
    return result


def source_runtime_behavior_files() -> set[str]:
    methods = "|".join(map(re.escape, RUNTIME_METHODS))
    declaration = re.compile(
        rf"(?m)^\s*(?:public|protected)\s+[^\n;{{]+\b(?:{methods})\s*\(")
    return {
        path.name
        for source_root in (SOURCE_BLOCKS, SOURCE_ITEMS)
        for path in source_root.rglob("*.java")
        if declaration.search(path.read_text(encoding="utf-8-sig"))
    }


def source_ticking_block_entities() -> set[str]:
    declaration = re.compile(r"(?m)^\s*public\s+(?:static\s+)?void\s+tick\s*\(")
    return {
        path.name
        for path in SOURCE_BLOCK_ENTITIES.rglob("*.java")
        if declaration.search(path.read_text(encoding="utf-8-sig"))
    }


def paper_owner_path(game_package: Path, implementation: str) -> Path:
    if implementation.startswith("src/") or implementation.startswith("tools/"):
        return ROOT / implementation
    return game_package / implementation


def assert_owner_evidence(
    source_name: str,
    owners: tuple[tuple[str, str], ...],
    game_package: Path,
) -> None:
    for implementation, evidence in owners:
        implementation_path = paper_owner_path(game_package, implementation)
        if not implementation_path.is_file():
            raise AssertionError(f"{source_name}: missing Paper owner {implementation}")
        if evidence not in implementation_path.read_text(encoding="utf-8-sig"):
            raise AssertionError(
                f"{source_name}: Paper owner {implementation} lacks evidence token {evidence!r}")


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
    if len(render_items) != 554:
        raise AssertionError(f"Expected 554 private render items, found {len(render_items)}")
    if len(recipes) != 114:
        raise AssertionError(f"Expected 114 crafting recipes, found {len(recipes)}")

    game_package = (
        ROOT / "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game")
    bottle_placement_source = (game_package / "BottlePlacementService.java").read_text(
        encoding="utf-8-sig")
    plugin_config = PLUGIN_CONFIG.read_text(encoding="utf-8-sig")
    if ("bottle-placement.drinks" in bottle_placement_source
            or re.search(r"(?m)^\s+drinks:\s*", plugin_config)
            or "new Placement(customId, null, false)" not in bottle_placement_source):
        raise AssertionError(
            "Custom DrinkBlockItem/CocktailBlockItem placement must remain unconditional")

    board_text_source = (game_package / "BoardTextService.java").read_text(
        encoding="utf-8-sig")
    if ("runTaskTimer" in board_text_source
            or "onMove(PlayerMoveEvent event)" not in board_text_source
            or "validateEditDistance(event.getPlayer())" not in board_text_source):
        raise AssertionError(
            "Board edit distance must be event-driven, not a global per-tick player scan")

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

    source_ids = source_registry_ids()
    if len(source_ids) != len(set(source_ids)):
        raise AssertionError("ModBlocks contains duplicate source block registrations")
    source_placeables = {f"{NAMESPACE}:{block_id}" for block_id in source_ids}
    derived_crop_stages = {
        block_id for block_id in blocks
        if block_id.startswith(f"{NAMESPACE}:_crop/")
    }
    represented_placeables = (set(blocks) - derived_crop_stages) | set(furniture)
    if represented_placeables != source_placeables:
        missing = sorted(source_placeables - represented_placeables)
        unexpected = sorted(represented_placeables - source_placeables)
        raise AssertionError(
            f"Source-to-CE placeable coverage drift: missing={missing}, unexpected={unexpected}")

    source_properties = source_state_properties(source_ids)
    if source_properties != set(SOURCE_STATE_OWNERS):
        missing_owners = sorted(source_properties - set(SOURCE_STATE_OWNERS))
        stale_owners = sorted(set(SOURCE_STATE_OWNERS) - source_properties)
        raise AssertionError(
            "Source blockstate semantics changed without an explicit owner: "
            f"unowned={missing_owners}, stale={stale_owners}")

    renderer_files = {
        path.name for path in SOURCE_RENDERERS.glob("*BlockEntityRender.java")
    }
    if renderer_files != set(RENDERER_COVERAGE):
        unhandled = sorted(renderer_files - set(RENDERER_COVERAGE))
        stale = sorted(set(RENDERER_COVERAGE) - renderer_files)
        raise AssertionError(
            f"Source block-entity renderer coverage drift: unhandled={unhandled}, stale={stale}")
    for renderer, (implementation, evidence) in RENDERER_COVERAGE.items():
        assert_owner_evidence(renderer, ((implementation, evidence),), game_package)

    runtime_behavior_files = source_runtime_behavior_files()
    if runtime_behavior_files != set(RUNTIME_BEHAVIOR_COVERAGE):
        unhandled = sorted(runtime_behavior_files - set(RUNTIME_BEHAVIOR_COVERAGE))
        stale = sorted(set(RUNTIME_BEHAVIOR_COVERAGE) - runtime_behavior_files)
        raise AssertionError(
            f"Source runtime behavior coverage drift: unhandled={unhandled}, stale={stale}")
    for source_name, owners in RUNTIME_BEHAVIOR_COVERAGE.items():
        assert_owner_evidence(source_name, owners, game_package)

    block_service_source = (game_package / "block/BlockService.java").read_text(
        encoding="utf-8-sig")
    for evidence in (
            "ItemStack eventItem = event.item();",
            "String planted = grapevineFor(block.getRelative(BlockFace.DOWN));",
            'withNamed(replacement, "type", stringProperty(state, "type"))'):
        if evidence not in block_service_source:
            raise AssertionError(f"BlockService grapevine planting evidence is missing: {evidence}")
    if '"single".equals(stringProperty(state, "type"))' in block_service_source:
        raise AssertionError("Grapevine planting must support connected trellis shapes")

    tap_behavior_files = {
        path.name for path in SOURCE_TAP_BEHAVIORS.glob("*Behavior.java")
    }
    if tap_behavior_files != set(TAP_BEHAVIOR_COVERAGE):
        unhandled = sorted(tap_behavior_files - set(TAP_BEHAVIOR_COVERAGE))
        stale = sorted(set(TAP_BEHAVIOR_COVERAGE) - tap_behavior_files)
        raise AssertionError(
            f"Source tap behavior coverage drift: unhandled={unhandled}, stale={stale}")
    for source_name, owner in TAP_BEHAVIOR_COVERAGE.items():
        assert_owner_evidence(source_name, (owner,), game_package)

    ticking_block_entities = source_ticking_block_entities()
    if ticking_block_entities != set(TICKING_BLOCK_ENTITY_COVERAGE):
        unhandled = sorted(ticking_block_entities - set(TICKING_BLOCK_ENTITY_COVERAGE))
        stale = sorted(set(TICKING_BLOCK_ENTITY_COVERAGE) - ticking_block_entities)
        raise AssertionError(
            f"Source ticking block-entity coverage drift: unhandled={unhandled}, stale={stale}")
    for source_name, owner in TICKING_BLOCK_ENTITY_COVERAGE.items():
        assert_owner_evidence(source_name, (owner,), game_package)

    effect_files = {path.name for path in SOURCE_EFFECTS.glob("*.java")}
    if effect_files != set(EFFECT_BEHAVIOR_COVERAGE):
        unhandled = sorted(effect_files - set(EFFECT_BEHAVIOR_COVERAGE))
        stale = sorted(set(EFFECT_BEHAVIOR_COVERAGE) - effect_files)
        raise AssertionError(
            f"Source effect coverage drift: unhandled={unhandled}, stale={stale}")
    for source_name, owners in EFFECT_BEHAVIOR_COVERAGE.items():
        assert_owner_evidence(source_name, owners, game_package)

    event_files = {path.name for path in SOURCE_EVENTS.glob("*.java")}
    if event_files != set(EVENT_BEHAVIOR_COVERAGE):
        unhandled = sorted(event_files - set(EVENT_BEHAVIOR_COVERAGE))
        stale = sorted(set(EVENT_BEHAVIOR_COVERAGE) - event_files)
        raise AssertionError(
            f"Source event coverage drift: unhandled={unhandled}, stale={stale}")
    for source_name, owners in EVENT_BEHAVIOR_COVERAGE.items():
        assert_owner_evidence(source_name, owners, game_package)

    entity_files = {path.name for path in SOURCE_ENTITIES.glob("*.java")}
    if entity_files != set(ENTITY_BEHAVIOR_COVERAGE):
        unhandled = sorted(entity_files - set(ENTITY_BEHAVIOR_COVERAGE))
        stale = sorted(set(ENTITY_BEHAVIOR_COVERAGE) - entity_files)
        raise AssertionError(
            f"Source entity coverage drift: unhandled={unhandled}, stale={stale}")
    for source_name, owners in ENTITY_BEHAVIOR_COVERAGE.items():
        assert_owner_evidence(source_name, owners, game_package)

    block_entity_files = {
        path.name
        for path in SOURCE_BLOCK_ENTITIES.rglob("*BlockEntity.java")
        if path.name != "BaseBlockEntity.java"
    }
    if block_entity_files != set(BLOCK_ENTITY_COVERAGE):
        unhandled = sorted(block_entity_files - set(BLOCK_ENTITY_COVERAGE))
        stale = sorted(set(BLOCK_ENTITY_COVERAGE) - block_entity_files)
        raise AssertionError(
            f"Source block-entity coverage drift: unhandled={unhandled}, stale={stale}")
    for source_name, owners in BLOCK_ENTITY_COVERAGE.items():
        assert_owner_evidence(source_name, owners, game_package)

    geometryless_source_models: set[str] = set()
    for block_id in source_ids:
        blockstate = asset_json(
            f"{NAMESPACE}:{block_id}", "blockstates", SOURCE_ASSET_ROOTS)
        references = set(model_references(blockstate))
        if references and not any(
                model_has_geometry(model, SOURCE_ASSET_ROOTS) for model in references):
            geometryless_source_models.add(block_id)
    expected_geometryless = {"barrel", "chalkboard", "shaker"}
    if geometryless_source_models != expected_geometryless:
        raise AssertionError(
            "Source particle-only model set changed: "
            f"found={sorted(geometryless_source_models)}")
    for block_id in expected_geometryless:
        definition = furniture[f"{NAMESPACE}:{block_id}"]
        for variant_name, variant in definition["variants"].items():
            if not variant.get("elements"):
                raise AssertionError(f"{block_id}/{variant_name}: particle-only source has no replacement")
            render_id = variant["elements"][0].get("item")
            model = render_items.get(render_id, {}).get("model", {}).get("path")
            if block_id == "shaker":
                if model != f"{NAMESPACE}:block/mixology/shaker":
                    raise AssertionError("Shaker CE anchor must remain the invisible source block model")
                continue
            if not model or not model_has_geometry(model):
                raise AssertionError(
                    f"{block_id}/{variant_name}: particle-only source still maps to invisible model {model}")

    stepladder = furniture[f"{NAMESPACE}:stepladder"]
    stepladder_variants = stepladder.get("variants", {})
    if set(stepladder_variants) != {"ground"}:
        raise AssertionError(
            f"Stepladder must expose only its ground variant, found {sorted(stepladder_variants)}")
    stepladder_ground = stepladder_variants["ground"]
    stepladder_elements = stepladder_ground.get("elements", [])
    if len(stepladder_elements) != 2 or any(
            element.get("type") != "item_display" for element in stepladder_elements):
        raise AssertionError("Stepladder must keep exactly two ItemDisplay halves")
    stepladder_hitboxes = stepladder_ground.get("hitboxes", [])
    if len(stepladder_hitboxes) != 6 or any(
            hitbox.get("type") != "shulker" for hitbox in stepladder_hitboxes):
        raise AssertionError("Stepladder must use six physical shulker hitboxes")
    expected_stepladder_hitboxes = {
        ("0,0,0.125", 0.75, 39),
        ("0,1,0.125", 0.75, 39),
        ("-0.25,0,-0.375", 0.5, 0),
        ("0.25,0,-0.375", 0.5, 0),
        ("-0.25,1,-0.375", 0.5, 0),
        ("0.25,1,-0.375", 0.5, 0),
    }
    actual_stepladder_hitboxes = {
        (hitbox.get("position"), hitbox.get("scale", 1), hitbox.get("peek", 0))
        for hitbox in stepladder_hitboxes
    }
    if actual_stepladder_hitboxes != expected_stepladder_hitboxes:
        raise AssertionError(
            "Stepladder hitboxes must retain full-height bodies and half-height treads: "
            f"found={sorted(actual_stepladder_hitboxes)}")

    trellis = blocks[f"{NAMESPACE}:trellis"]
    if "support_shape" in trellis.get("settings", {}):
        raise AssertionError("Trellis must not expose a full-cube support/occlusion shape")
    vine_trellis_ids = (
        "grapevine_trellis", "ice_grapevine_trellis", "gold_grapevine_trellis")

    # A carrier is all the client ever sees, so it decides both what the player
    # collides with and what can be aimed at. Trellises use one transparent CE
    # cactus auto-state: its client model is empty, while its near-full-block
    # collision keeps every connected shape interactive without z-fighting.
    collidable_trellises = 0
    expected_carrier = {
        "type": "cactus",
        "id": "kaleidoscope-tavern-trellis-collidable",
    }
    for block_id in ("trellis", *vine_trellis_ids):
        definition = blocks[f"{NAMESPACE}:{block_id}"]
        states = definition["states"]
        for name, appearance in states["appearances"].items():
            if appearance.get("entity_renderer", {}).get("type") != "item_display":
                raise AssertionError(f"{block_id}/{name} must keep its authored item-display model")
            if appearance.get("auto_state") != expected_carrier:
                raise AssertionError(
                    f"{block_id}/{name}: trellis must use the shared cactus auto-state carrier")
            if appearance.get("transparent") is not True:
                raise AssertionError(
                    f"{block_id}/{name}: trellis carrier must use a transparent client model")
            collidable_trellises += 1
    if collidable_trellises != 37:
        raise AssertionError(
            f"Expected 37 collidable trellis appearances, found {collidable_trellises}")
    for block_id in ("trellis", *vine_trellis_ids):
        definition = blocks[f"{NAMESPACE}:{block_id}"]
        settings = definition.get("settings", {})
        if (settings.get("hardness") != 0.8
                or settings.get("resistance") != 0.8
                or settings.get("sounds", {}).get("break") != "minecraft:block.wood.break"):
            raise AssertionError(f"{block_id}: source trellis hardness must remain 0.8")
    if blocks[f"{NAMESPACE}:trellis"]["settings"].get("push_reaction") != "NORMAL":
        raise AssertionError("Plain trellis must retain the source default piston reaction")
    for block_id in vine_trellis_ids:
        behavior = blocks[f"{NAMESPACE}:{block_id}"].get("behavior")
        if behavior != {
                "type": f"{NAMESPACE}:trellis",
                "spread_chance": 0.25}:
            raise AssertionError(
                f"{block_id}: growth must have one source-compatible owner, found {behavior!r}")

    wild_behaviors = blocks[f"{NAMESPACE}:wild_grapevine"].get("behavior", [])
    if not isinstance(wild_behaviors, list) or {
            entry.get("type") for entry in wild_behaviors} != {
                "vine_crop_head_block", f"{NAMESPACE}:wild_grapevine"}:
        raise AssertionError("Wild grapevine must keep native survival plus custom shearing growth")
    if any("max_height" in entry for entry in wild_behaviors):
        raise AssertionError("Wild grapevine must not retain the invented 16-block growth cap")
    wild_settings = blocks[f"{NAMESPACE}:wild_grapevine"]["settings"]
    if (wild_settings.get("hardness") != 0
            or wild_settings.get("resistance") != 0
            or wild_settings.get("sounds", {}).get("break")
            != "minecraft:block.cave_vines.break"):
        raise AssertionError("Wild grapevine must retain instant break and cave-vine sounds")
    string_settings = blocks[f"{NAMESPACE}:string_lights_white"]["settings"]
    if (string_settings.get("hardness") != 0.8
            or string_settings.get("resistance") != 0.8
            or string_settings.get("push_reaction") != "NORMAL"
            or string_settings.get("sounds", {}).get("break")
            != "minecraft:block.chain.break"
            or string_settings.get("tags") != ["minecraft:mineable/pickaxe"]):
        raise AssertionError("String lights must retain source chain material semantics")

    with EN_US.open("r", encoding="utf-8-sig") as stream:
        language_keys = set(json.load(stream))
    with ZH_CN.open("r", encoding="utf-8-sig") as stream:
        chinese_language = json.load(stream)
    chinese_language_keys = set(chinese_language)
    if any("\ufffd" in value for value in chinese_language.values()):
        raise AssertionError("zh_cn.json contains a Unicode replacement character")
    for full_item_id, item in items.items():
        item_id = full_item_id.split(":", 1)[1]
        raw_name = item.get("data", {}).get("item_name", "")
        matches = re.fullmatch(r"<!i><lang:([^>]+)>", raw_name)
        if matches is None:
            raise AssertionError(f"{full_item_id}: malformed translatable item_name {raw_name!r}")
        actual_key = matches.group(1)
        if actual_key not in language_keys:
            raise AssertionError(f"{full_item_id}: missing item-name translation {actual_key}")
        if actual_key not in chinese_language_keys:
            raise AssertionError(
                f"{full_item_id}: missing Chinese item-name translation {actual_key}")

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
            settings = blocks[stage_id].get("settings", {})
            if (settings.get("hardness") != 0
                    or settings.get("resistance") != 0
                    or settings.get("sounds", {}).get("break")
                    != "minecraft:block.crop.break"
                    or settings.get("sounds", {}).get("place")
                    != "minecraft:item.crop.plant"):
                raise AssertionError(f"{stage_id}: crop material semantics drifted")

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
    if custom_crops_text.count("ignore-random-tick: true") != 3:
        raise AssertionError(
            "Every managed grape crop must delegate vanilla random ticks to CraftEngine")
    if "grow-conditions:" in custom_crops_text or "kaleidoscope-tavern-growth-roll" in custom_crops_text:
        raise AssertionError("CustomCrops must not add a second grape random-growth scheduler")

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

    molotov_item = items[f"{NAMESPACE}:molotov"]
    molotov_components = molotov_item.get("data", {}).get("components", {})
    if (molotov_item.get("material") != "paper"
            or molotov_components.get("minecraft:max_stack_size") != 16
            or molotov_components.get("minecraft:consumable") != {
                "consume_seconds": 3_600.0,
                "animation": "spear",
                "has_consume_particles": False,
            }):
        raise AssertionError(
            "Molotov must retain its 72,000-tick spear charge instead of instant splash-potion use")

    barrel_variants = furniture[f"{NAMESPACE}:barrel"]["variants"]
    if set(barrel_variants) != {"ground", "ground_open"}:
        raise AssertionError("The source barrel must expose closed and open lid states")
    barrel = barrel_variants["ground"]
    open_barrel = barrel_variants["ground_open"]
    if len(barrel.get("hitboxes", [])) != 27 or len(open_barrel.get("hitboxes", [])) != 27:
        raise AssertionError("The legacy barrel must retain its 3x3x3 furniture footprint")
    if any(hitbox.get("peek") != 0 for variant in barrel_variants.values()
           for hitbox in variant["hitboxes"]):
        raise AssertionError("The 3x3x3 barrel model/collision must span y=0..3 exactly")
    closed_element = barrel["elements"][0]
    open_body, open_lid = open_barrel["elements"]
    barrel_models = [
        render_items[element["item"]]["model"]["path"]
        for element in (closed_element, open_body, open_lid)
    ]
    if barrel_models != [
            f"{NAMESPACE}:furniture/barrel_closed",
            f"{NAMESPACE}:furniture/barrel_body",
            f"{NAMESPACE}:furniture/barrel_open_lid"]:
        raise AssertionError("Barrel must use the exact source entity body/lid geometry")
    if ([closed_element.get("translation"), open_body.get("translation"),
         open_lid.get("translation"), open_lid.get("rotation")]
            != ["0,1.5,0", "0,1.5,0", "0,3,0.5", "72.5,0,0"]):
        raise AssertionError("Barrel body/lid pivot no longer matches BarrelModel")

    sofa = furniture[f"{NAMESPACE}:white_sofa"]["variants"]["ground"]
    stool = furniture[f"{NAMESPACE}:white_bar_stool"]["variants"]["ground"]
    bottle = furniture[f"{NAMESPACE}:empty_bottle"]["variants"]["ground"]
    if sofa["elements"][0].get("translation") != "0,0.5,0":
        raise AssertionError("Ground block models must be lifted to the authored target block")
    if sofa["hitboxes"][0].get("height") != 1.125 or stool["hitboxes"][0].get("height") != 1.3125:
        raise AssertionError("Seat hitboxes must retain the Forge VoxelShape height")
    # BukkitSeat adds a flat 0.6 to the seat position and then sinks the small
    # armour stand by its own 0.9875 height so the stand's top-mounted passenger
    # lands back on that point. Those two cancel each other, not the 0.6, so the
    # player's feet end up at `furniture origin + seat.y + 0.6` and each seat y is
    # its cushion height minus 0.6.
    if sofa["hitboxes"][0].get("seats") != ["0,-0.1,0 0"]:
        raise AssertionError("Sofa seat must rest on the 8/16 cushion, not float above it")
    if stool["hitboxes"][0].get("seats") != ["0,0.3375,0 0"]:
        raise AssertionError("Bar-stool seat must rest on the 15/16 cushion, not float above it")
    stool_render_id = stool["elements"][0].get("item")
    if render_items.get(stool_render_id, {}).get("model", {}).get("path") != (
            f"{NAMESPACE}:block/deco/bar_stool/white"):
        raise AssertionError("Bar-stool furniture must keep the static source pedestal model")
    stool_body_helpers = {
        item_id for item_id in render_items
        if item_id.startswith(f"{NAMESPACE}:_render/bar_stool_body/")
    }
    if len(stool_body_helpers) != 16:
        raise AssertionError("Every source dye color needs a dynamic bar-stool body model")
    stool_body_model = asset_json(
        f"{NAMESPACE}:furniture/bar_stool_body_base", "models")
    if stool_body_model is None or len(stool_body_model.get("elements", [])) != 4:
        raise AssertionError("Bar-stool seat/back/arms must remain a four-cuboid dynamic body")
    shaker = furniture[f"{NAMESPACE}:shaker"]["variants"]["ground"]
    shaker_render_id = shaker["elements"][0].get("item")
    if render_items.get(shaker_render_id, {}).get("model", {}).get("path") != (
            f"{NAMESPACE}:block/mixology/shaker"):
        raise AssertionError("Shaker CE anchor must remain the invisible source block model")
    if "position" in shaker["elements"][0]:
        raise AssertionError("Shaker anchor must not expand culling bounds to hide its source model")
    shaker_helpers = {
        item_id: render_items.get(f"{NAMESPACE}:_render/shaker_{item_id}", {})
        for item_id in ("base", "lid")
    }
    if {part: helper.get("model", {}).get("path")
            for part, helper in shaker_helpers.items()} != {
                "base": f"{NAMESPACE}:furniture/shaker_base",
                "lid": f"{NAMESPACE}:furniture/shaker_lid",
            }:
        raise AssertionError("Animated shaker body/lid helper items are incomplete")
    shaker_base = asset_json(f"{NAMESPACE}:furniture/shaker_base", "models")
    shaker_lid = asset_json(f"{NAMESPACE}:furniture/shaker_lid", "models")
    if (shaker_base is None or len(shaker_base.get("elements", [])) != 2
            or shaker_lid is None or len(shaker_lid.get("elements", [])) != 3):
        raise AssertionError("ShakerModel must remain split as 2 root + 3 animated lid cuboids")
    if bottle["hitboxes"][0].get("width") != 0.375 or bottle["hitboxes"][0].get("height") != 0.875:
        raise AssertionError("Bottle hitboxes must retain the 6x14x6 source VoxelShape")

    material_examples = {
        "white_sofa": ("wool", 3),
        "bell_pendant_lamp": ("chain", 3),
        "sakura_incense": ("decorated_pot", 1),
        "tap": ("metal", 3),
        "glassware_holder": ("metal", 3),
        "shaker": ("lantern", 1),
        "empty_bottle": ("glass", 1),
        "white_lady": ("glass", 1),
        "wine": ("glass", 1),
        "molotov": ("glass", 1),
        "barrel": ("wood", 3),
    }
    for furniture_id, (family, hit_times) in material_examples.items():
        settings = furniture[f"{NAMESPACE}:{furniture_id}"]["settings"]
        expected_sounds = {
            action: f"minecraft:block.{family}.{action}"
            for action in ("break", "place", "hit")
        }
        if settings.get("hit_times") != hit_times or settings.get("sounds") != expected_sounds:
            raise AssertionError(f"{furniture_id}: source material/break behavior drifted")

    table_variants = furniture[f"{NAMESPACE}:table"]["variants"]
    expected_table_variants = {
        "ground",
        *(f"ground_axis_{axis}_position_{position}"
          for axis in ("x", "z") for position in range(1, 4)),
    }
    if set(table_variants) != expected_table_variants:
        raise AssertionError("Table must retain both source AXIS state families")
    expected_table_models = {
        "ground": f"{NAMESPACE}:block/deco/table/single",
        "ground_axis_x_position_1": f"{NAMESPACE}:block/deco/table/right",
        "ground_axis_x_position_2": f"{NAMESPACE}:block/deco/table/middle",
        "ground_axis_x_position_3": f"{NAMESPACE}:block/deco/table/left",
        "ground_axis_z_position_1": f"{NAMESPACE}:block/deco/table/right_rot",
        "ground_axis_z_position_2": f"{NAMESPACE}:block/deco/table/middle_rot",
        "ground_axis_z_position_3": f"{NAMESPACE}:block/deco/table/left_rot",
    }
    for variant_name, variant in table_variants.items():
        model = render_items[variant["elements"][0]["item"]]["model"]["path"]
        shulkers = [hitbox for hitbox in variant["hitboxes"] if hitbox["type"] == "shulker"]
        if model != expected_table_models[variant_name]:
            raise AssertionError(f"table/{variant_name}: source axis/position model drifted")
        if (len(variant["hitboxes"]) != 17 or len(shulkers) != 16
                or any(hitbox.get("scale") != 0.25
                       or hitbox.get("position", "").split(",")[1] != "0.75"
                       for hitbox in shulkers)):
            raise AssertionError(
                f"table/{variant_name}: full-cube collision returned instead of the top slab")

    board = furniture[f"{NAMESPACE}:base_sandwich_board"]["variants"]["ground"]
    if [element.get("translation") for element in board["elements"]] != ["0,0.5,0", "0,1.5,0"]:
        raise AssertionError("Two-block sandwich-board model halves are vertically misaligned")
    pendant = furniture[f"{NAMESPACE}:bell_pendant_lamp"]["variants"]["ceiling"]
    if [element.get("translation") for element in pendant["elements"]] != ["0,-0.49,0", "0,-1.49,0"]:
        raise AssertionError("Ceiling pendant model halves are vertically misaligned")
    tap = furniture[f"{NAMESPACE}:tap"]["variants"]["wall"]["elements"][0]
    if tap.get("position") != "0,0,0.01" or tap.get("translation") != "0,0,0.49":
        raise AssertionError("Wall model must retain its target-block offset after anti-blackening compensation")
    if "rotation" in tap or render_items[tap["item"]]["model"]["path"] != (
            f"{NAMESPACE}:block/brew/tap/close"):
        raise AssertionError("Tap must retain the north-authored mounting-plate orientation")
    tap_hitbox = furniture[f"{NAMESPACE}:tap"]["variants"]["wall"]["hitboxes"][0]
    if tap_hitbox.get("position") != "0,-0.1875,0.6875":
        raise AssertionError("Tap hitbox must run from its z=16 mounting plate toward z=6")

    paintings = [item_id for item_id in items if item_id.endswith("_painting")]
    if len(paintings) != 14:
        raise AssertionError(f"Expected 14 paintings, found {len(paintings)}")
    for painting_id in paintings:
        behavior = items[painting_id].get("behavior", {})
        wall = furniture[painting_id]["variants"]["wall"]
        if (set(behavior.get("rules", {})) != {"ground", "wall", "ceiling"}
                or behavior.get("ignore_placer") is not True):
            raise AssertionError(f"{painting_id}: wall/ceiling placement rules are incomplete")
        if any(hitbox.get("blocks_building") is not False for hitbox in wall["hitboxes"]):
            raise AssertionError(f"{painting_id}: square wall hitbox must not block placement")

    storage_slot_counts = {
        "bar_cabinet": 2,
        "glass_bar_cabinet": 2,
        "cellar_cabinet": 9,
        "tilted_rack": 3,
        "circular_rack": 6,
        "holder": 1,
        "glassware_holder": 4,
    }
    for storage_id, slot_count in storage_slot_counts.items():
        configured_behaviors = list(
            furniture[f"{NAMESPACE}:{storage_id}"].get("behaviors", []))
        single_behavior = furniture[f"{NAMESPACE}:{storage_id}"].get("behavior")
        if single_behavior is not None:
            configured_behaviors.append(single_behavior)
        behaviors = [
            behavior
            for behavior in configured_behaviors
            if behavior.get("type") == "display_item_furniture"
        ]
        if len(behaviors) != slot_count or any(
                rule.get("item_position") != "0,-4096,0" or "hitboxes" in rule
                for behavior in behaviors
                for rule in behavior.get("variants", {}).values()
        ):
            raise AssertionError(
                f"{storage_id}: CE sprites must be hidden behind source-compatible Paper visuals")
    storage_helpers = {
        item_id for item_id in render_items
        if item_id.startswith(f"{NAMESPACE}:_render/storage/")
    }
    fluid_helpers = {
        item_id for item_id in render_items
        if item_id.startswith(f"{NAMESPACE}:_render/pressing_fluid/")
    }
    barrel_fluid_helpers = {
        item_id for item_id in render_items
        if item_id.startswith(f"{NAMESPACE}:_render/barrel_fluid/")
    }
    if (len(storage_helpers) != 33 or len(fluid_helpers) != 6
            or len(barrel_fluid_helpers) != 8):
        raise AssertionError("Storage/pressing runtime visual helper set is incomplete")
    potion_helper = render_items[f"{NAMESPACE}:_render/storage/potion_bottle"]
    if potion_helper.get("model", {}).get("tints") != [{
            "type": "minecraft:potion", "default": -13083194}]:
        raise AssertionError("Stored potion block models must preserve potion_contents tint")
    water_helper = render_items[f"{NAMESPACE}:_render/barrel_fluid/water"]
    if water_helper.get("model", {}).get("tints") != [{
            "type": "minecraft:constant", "value": 0x3F76E4}]:
        raise AssertionError("Open-barrel water surface must retain its source fluid tint")

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
        "source-placeables": len(source_placeables),
        "source-state-properties": len(source_properties),
        "source-block-entity-renderers": len(renderer_files),
        "source-runtime-behaviors": len(runtime_behavior_files),
        "source-tap-behaviors": len(tap_behavior_files),
        "source-ticking-block-entities": len(ticking_block_entities),
        "source-effect-behaviors": len(effect_files),
        "source-event-behaviors": len(event_files),
        "source-entity-behaviors": len(entity_files),
        "source-block-entities": len(block_entity_files),
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
