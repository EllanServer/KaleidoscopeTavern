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
EFFECTLESS_DRINKS = {f"{NAMESPACE}:watermelon_juice"}
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
    "open": "furniture.json incense toggle events, StationService and TapService",
    "position": "FurnitureConnectionService",
    "powered": "StationService and DisplayStorageService redstone polling",
    "rotation": "CE sixteen-way sandwich-board rotation",
    "tilt": "ground/wall pressing-tub placement variants",
    "triggered": "TapService",
    "type": "CE trellis state variants",
    "waterlogged": "entity furniture / CE non-displacing carrier semantics",
    "waxed": "CE trellis state variants plus blocks.json wax events",
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
        ("block/TrellisBehavior.java", "implements BonemealableBlock"),
        ("block/TrellisBehavior.java", "public static boolean grow"),
    ),
    "HolderBlock.java": (("DisplayStorageService.java", "HOLDER"),),
    "IncenseBlock.java": (
        ("src/paper/pack/configuration/furniture.json", "set_furniture_variant"),
        ("StationService.java", "pollIncenseRedstone"),
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
    "StringLightsBlock.java": (("src/paper/pack/configuration/blocks.json", "item.dye.use"),),
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
        ("src/paper/pack/configuration/blocks.json", "item.axe.wax_off"),
    ),
    "WildGrapevineBlock.java": (
        ("src/paper/pack/configuration/blocks.json", "entity.sheep.shear"),
        ("block/WildGrapevineBehavior.java", "implements BonemealableBlock"),
        ("block/WildGrapevineBehavior.java", "isValidBonemealTarget"),
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
    "AddFeaturesEvent.java": (("src/paper/pack/configuration/worldgen.json", "wild_grapevine"),),
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
    "BarrelBlockEntity.java": (("StationService.java", "barrel_items"),),
    "CellarCabinetBlockEntity.java": (("DisplayStorageService.java", "CELLAR_CABINET"),),
    "DrinkBlockEntity.java": (("BottleFurnitureService.java", "bottle_items"),),
    "PotionBottleBlockEntity.java": (("BottlePlacementService.java", "bottle_items"),),
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


def item_model_paths(value: Any):
    """Yield every vanilla block-model path nested in an item definition."""
    if isinstance(value, dict):
        if (value.get("type") in {"model", "minecraft:model"}
                and isinstance(value.get("path"), str)):
            yield value["path"]
        for child in value.values():
            yield from item_model_paths(child)
    elif isinstance(value, list):
        for child in value:
            yield from item_model_paths(child)


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
    block_service_source = (game_package / "block/BlockService.java").read_text(
        encoding="utf-8-sig")
    if ("grapevineFor returned null" in block_service_source
            or "onRightClickWithGrapevine: clicked" in block_service_source):
        raise AssertionError(
            "Unsupported grapevine soil is an expected rejection and must not spam the server log")
    if "Material.BONE_MEAL" in block_service_source:
        raise AssertionError(
            "Bone meal must use CraftEngine BonemealableBlock behavior, not a cancelled Bukkit event")
    for behavior_source_path in (
            game_package / "block/TrellisBehavior.java",
            game_package / "block/WildGrapevineBehavior.java"):
        behavior_source = behavior_source_path.read_text(encoding="utf-8-sig")
        if ("public InteractionResult useOnBlock" not in behavior_source
                or "player.swingHand(context.getHand())" not in behavior_source
                or "return InteractionResult.SUCCESS;" not in behavior_source):
            raise AssertionError(
                f"{behavior_source_path.name}: CE bone-meal interaction must acknowledge use and swing the hand")
    wild_behavior_source = (
        game_package / "block/WildGrapevineBehavior.java").read_text(encoding="utf-8-sig")
    for leaf_attachment_token in (
            'Key.of("minecraft", "leaves")',
            "if (!isAttachedToLeaves(args))",
            "return isAttachedToLeaves(args)",
            "LocationUtils.above(args[2])",
            "BlockStateUtils.isTag(attachedState, LEAVES)",
            "|| lifecycle().canSurvive(thisBlock, args)"):
        if leaf_attachment_token not in wild_behavior_source:
            raise AssertionError(
                "Wild grapevine head/body must preserve the source leaves attachment rule")
    plugin_config = PLUGIN_CONFIG.read_text(encoding="utf-8-sig")
    if ("bottle-placement.drinks" in bottle_placement_source
            or re.search(r"(?m)^\s+drinks:\s*", plugin_config)
            or not re.search(
                r"new Placement\(customId,\s*null(?:,\s*false)?\)",
                bottle_placement_source)):
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

    for wild_id in ("wild_grapevine", "wild_grapevine_plant"):
        behavior = blocks[f"{NAMESPACE}:{wild_id}"].get("behavior")
        if (not isinstance(behavior, dict)
                or behavior.get("type") != f"{NAMESPACE}:wild_grapevine"):
            raise AssertionError(
                f"{wild_id}: wild-vine lifecycle and bone meal must use one CE behavior wrapper")

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
            "String planted = grapevineFor(soil);",
            'withNamed(replacement, "type", stringProperty(trellisState, "type"))',
            "void plantGrapevineOnTrellis(",
            "onRightClickWithGrapevine"):
        if evidence not in block_service_source:
            raise AssertionError(f"BlockService grapevine planting evidence is missing: {evidence}")
    if '"single".equals(stringProperty(state, "type"))' in block_service_source:
        raise AssertionError("Grapevine planting must support connected trellis shapes")
    for stale_listener in ("interactWildHead", "Material.HONEYCOMB", "WAX_ON", "WAX_OFF"):
        if stale_listener in block_service_source:
            raise AssertionError(
                "Trellis waxing and wild-grapevine shearing are CE block events; "
                f"BlockService must not reintroduce {stale_listener}")
    station_source = (game_package / "StationService.java").read_text(encoding="utf-8-sig")
    ambient_source = (game_package / "AmbientFurnitureService.java").read_text(
        encoding="utf-8-sig")
    for owner_name, owner_source in (("StationService", station_source),
                                     ("AmbientFurnitureService", ambient_source)):
        for stale_state in ("incense_active", "interactIncense"):
            if stale_state in owner_source:
                raise AssertionError(
                    f"{owner_name}: the *_open furniture variant is the only lit-incense "
                    f"state; {stale_state} must stay deleted")

    # DisplayStorageService must cancel off-hand furniture interactions to
    # prevent CraftEngine's built-in display_item_furniture behavior from
    # duplicating items and desyncing storage visuals.
    storage_source = (game_package / "DisplayStorageService.java").read_text(
        encoding="utf-8-sig")
    if "event.hand() != InteractionHand.MAIN_HAND" not in storage_source \
            or "event.setCancelled(true)" not in storage_source:
        raise AssertionError(
            "DisplayStorageService must cancel off-hand interactions to prevent item duplication")

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
    if len(stepladder_hitboxes) != 4 or any(
            hitbox.get("type") != "shulker" for hitbox in stepladder_hitboxes):
        raise AssertionError("Stepladder must use four physical shulker hitboxes")
    expected_stepladder_hitboxes = {
        ("0,0,0", 0.75, 0, "up", True, False),
        ("0,0.75,-0.25", 0.625, 25, "north", False, False),
        ("-0.25,1.5,-0.25", 0.4, 35, "up", False, False),
        ("0.25,1.5,-0.25", 0.4, 35, "up", False, False),
    }
    actual_stepladder_hitboxes = {
        (
            hitbox.get("position"),
            hitbox.get("scale", 1),
            hitbox.get("peek", 0),
            hitbox.get("direction", "up"),
            hitbox.get("blocks_building"),
            hitbox.get("invisible"),
        )
        for hitbox in stepladder_hitboxes
    }
    if actual_stepladder_hitboxes != expected_stepladder_hitboxes:
        raise AssertionError(
            "Stepladder hitboxes must retain the server-tested compact layout: "
            f"found={sorted(actual_stepladder_hitboxes)}")

    trellis = blocks[f"{NAMESPACE}:trellis"]
    if "support_shape" in trellis.get("settings", {}):
        raise AssertionError("Trellis must not expose a full-cube support/occlusion shape")
    vine_trellis_ids = (
        "grapevine_trellis", "ice_grapevine_trellis", "gold_grapevine_trellis")

    # A carrier is all the client ever sees, so it decides both what the player
    # collides with and what can be aimed at. Every trellis appearance uses a
    # directional lightning-rod state: vertical members use facing=up, while
    # horizontal members use their matching axis. The state is transparent to
    # the authored ItemDisplay and remains collidable for connected shapes.
    collidable_trellises = 0
    for block_id in ("trellis", *vine_trellis_ids):
        definition = blocks[f"{NAMESPACE}:{block_id}"]
        states = definition["states"]
        for name, appearance in states["appearances"].items():
            if appearance.get("entity_renderer", {}).get("type") != "item_display":
                raise AssertionError(f"{block_id}/{name} must keep its authored item-display model")
            state = appearance.get("state", "")
            if not state.startswith("minecraft:lightning_rod["):
                raise AssertionError(
                    f"{block_id}/{name}: every trellis shape needs a colliding lightning-rod carrier")
            if "powered=false" not in state or "waterlogged=false" not in state:
                raise AssertionError(
                    f"{block_id}/{name}: trellis carrier must remain unpowered and dry")
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

    wild_behavior = blocks[f"{NAMESPACE}:wild_grapevine"].get("behavior", {})
    if wild_behavior != {
            "type": f"{NAMESPACE}:wild_grapevine",
            "body": f"{NAMESPACE}:wild_grapevine_plant",
            "direction": "down",
            "grow_speed": 0.15}:
        raise AssertionError(
            "Wild grapevine must wrap CE's native lifecycle and custom shearing in one behavior")
    wild_body_behavior = blocks[f"{NAMESPACE}:wild_grapevine_plant"].get("behavior", {})
    if wild_body_behavior != {
            "type": f"{NAMESPACE}:wild_grapevine",
            "head": f"{NAMESPACE}:wild_grapevine",
            "direction": "down",
            "bone_meal": {"behavior": "grow", "grow_blocks": 1}}:
        raise AssertionError("Wild grapevine body must delegate native bone meal to its head")
    if "max_height" in wild_behavior:
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
    bone_meal_sections = re.findall(
        r"(?ms)^  custom-bone-meal:\n(?:(?!^[a-z0-9_]+:).)*",
        custom_crops_text,
    )
    if (len(bone_meal_sections) != 3
            or any("type: swing-hand" not in section for section in bone_meal_sections)):
        raise AssertionError("Every managed grape bone-meal action must swing the player's hand")
    if custom_crops_text.count("ignore-random-tick: true") != 3:
        raise AssertionError(
            "Every managed grape crop must delegate vanilla random ticks to CraftEngine")
    if "grow-conditions:" in custom_crops_text or "kaleidoscope-tavern-growth-roll" in custom_crops_text:
        raise AssertionError("CustomCrops must not add a second grape random-growth scheduler")

    for item_id, item in {**items, **render_items}.items():
        models = set(item_model_paths(item.get("model", {})))
        if not models:
            raise AssertionError(f"{item_id}: item definition has no block-model path")
        for model in models:
            if not asset_exists(model, "models"):
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
        recipe_type = recipe.get("type")
        if recipe_type not in {"shaped", "shapeless"}:
            raise AssertionError(f"{recipe_id}: unsupported standard recipe type {recipe_type!r}")
        if recipe.get("unlock_on_ingredient_obtained") is not True:
            raise AssertionError(
                f"{recipe_id}: must set unlock_on_ingredient_obtained to true"
            )
        if "unlock_on_join" in recipe:
            raise AssertionError(f"{recipe_id}: must not use unlock_on_join")
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
    effect_rows = tsv_rows("drink-effects.tsv")
    if len({row[0] for row in effect_rows}) != 37:
        raise AssertionError("Expected drink effects for 37 items")

    effect_drink_ids = {row[0] for row in effect_rows}
    unexpected_effects = effect_drink_ids & EFFECTLESS_DRINKS
    if unexpected_effects:
        raise AssertionError(
            f"Effectless drinks unexpectedly declare drink effects: {sorted(unexpected_effects)}")
    drink_ids = effect_drink_ids | EFFECTLESS_DRINKS
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
        data = item.get("data", {})
        if data.get("custom_name") != data.get("item_name"):
            raise AssertionError(
                f"{item_id}: potion drinks require custom_name because PotionItem "
                "ignores item_name when deriving its hover title")
        if data.get("hide_tooltip") != ["minecraft:potion_contents"]:
            raise AssertionError(
                f"{item_id}: drinks must hide only the vanilla potion_contents tooltip; "
                "their real server-side effects are rendered as custom lore")
        potion_contents = data.get("components", {}).get("minecraft:potion_contents")
        if (not isinstance(potion_contents, dict)
                or potion_contents.get("potion") != "minecraft:water"
                or not set(potion_contents).issubset({"potion", "custom_color"})
                or ("custom_color" in potion_contents
                    and not isinstance(potion_contents["custom_color"], int))):
            raise AssertionError(
                f"{item_id}: drink potion_contents must use the neutral water base "
                "and may only add an integer custom_color")

    for item_id in EFFECTLESS_DRINKS:
        replacement = items[item_id].get("settings", {}).get("consume_replacement")
        if replacement != f"{NAMESPACE}:empty_bottle":
            raise AssertionError(
                f"{item_id}: effectless bottle drinks must return empty_bottle after consumption")

    fixed_cocktails = {row[1] for row in tsv_rows("shaker.tsv")}
    fixed_cocktails.add(f"{NAMESPACE}:mystery_cocktail")
    for item_id in fixed_cocktails:
        lore = items[item_id].get("data", {}).get("lore", [])
        expected_effects = {
            f"effect.{row[2].replace(':', '.')}"
            for row in effect_rows if row[0] == item_id and row[1] == "1"
        }
        if not lore or any(not any(effect in line for line in lore)
                           for effect in expected_effects):
            raise AssertionError(
                f"{item_id}: fixed cocktail creative preview is missing real effect lore")

    legacy_attribute_keys = {
        "attribute.name.generic.step_height",
        "attribute.name.player.block_interaction_range",
        "attribute.name.player.entity_interaction_range",
    }
    generated_lore = {
        line
        for item in items.values()
        for line in item.get("data", {}).get("lore", [])
    }
    if any(key in line for key in legacy_attribute_keys for line in generated_lore):
        raise AssertionError("Drink lore still contains pre-26.2 attribute translation keys")
    expected_attribute_lore = {
        f"{NAMESPACE}:white_lady": {"attribute.name.step_height"},
        f"{NAMESPACE}:emerald": {
            "attribute.name.block_interaction_range",
            "attribute.name.entity_interaction_range",
        },
    }
    for item_id, attribute_keys in expected_attribute_lore.items():
        lore = items[item_id].get("data", {}).get("lore", [])
        missing = {key for key in attribute_keys if not any(key in line for line in lore)}
        if missing:
            raise AssertionError(
                f"{item_id}: missing canonical 26.2 attribute lore keys {sorted(missing)}")

    for item_id, item in items.items():
        if not item_id.endswith("_bucket"):
            continue
        settings = item.get("settings", {})
        if (item.get("material") != "milk_bucket"
                or item.get("data", {}).get("components", {}).get("minecraft:max_stack_size") != 16
                or settings.get("consume_replacement") != "minecraft:bucket"
                or settings.get("craft_remainder") != "minecraft:bucket"):
            raise AssertionError(f"{item_id}: juice buckets must remain stackable drinkable items")

    for grape_id in ("grape", "ice_grape", "gold_grape", "green_grape"):
        grape_item = items[f"{NAMESPACE}:{grape_id}"]
        if (grape_item.get("material") != "paper"
                or grape_item.get("data", {}).get("food") != {
                    "nutrition": 2,
                    "saturation": 2.0,
                    "can_always_eat": True,
                }
                or grape_item.get("data", {}).get("components", {}).get("minecraft:consumable") != {
                    "consume_seconds": 1.6,
                    "animation": "eat",
                }):
            raise AssertionError(
                f"{grape_id}: grapes must stay non-placeable plain food "
                "(paper base with explicit food/consumable components)")

    placeable_materials = {
        "sweet_berries", "glow_berries", "cocoa_beans", "wheat_seeds",
        "melon_seeds", "pumpkin_seeds", "beetroot_seeds", "torchflower_seeds",
        "pitcher_pod", "nether_wart", "bamboo", "sugar_cane", "kelp",
        "sea_pickle", "redstone", "string", "carrot", "potato", "chorus_fruit",
    }
    for item_id, item in items.items():
        if item.get("material") in placeable_materials:
            raise AssertionError(
                f"{item_id}: base material {item['material']!r} leaks the vanilla "
                "block-placement path; use a non-placeable material and declare "
                "components explicitly")

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
    molotov_model = molotov_item.get("model", {})
    if (molotov_model.get("property") != "minecraft:using_item"
            or molotov_model.get("on_true", {}).get("path") != f"{NAMESPACE}:item/molotov_charging"):
        raise AssertionError(
            "Molotov must swap to the charging display model while using_item is true")
    if asset_json(f"{NAMESPACE}:item/molotov_charging", "models") is None:
        raise AssertionError("Missing generated molotov charging display model")

    shaker_item = items[f"{NAMESPACE}:shaker"]
    shaker_components = shaker_item.get("data", {}).get("components", {})
    if (shaker_item.get("material") != "paper"
            or shaker_components.get("minecraft:max_stack_size") != 1
            or shaker_components.get("minecraft:consumable") != {
                "consume_seconds": 3_600.0,
                "animation": "brush",
                "has_consume_particles": False,
            }):
        raise AssertionError(
            "Shaker must use a behavior-free material with a component-only brush animation")
    if shaker_item.get("model") != {
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
            }:
        raise AssertionError(
            "Shaker must use the 2D icon only in GUI/FIXED display contexts")

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
    shaker_item_model = asset_json(f"{NAMESPACE}:item/shaker", "models")
    if shaker_item_model != {
            "parent": "minecraft:item/generated",
            "textures": {"layer0": f"{NAMESPACE}:item/shaker"},
            }:
        raise AssertionError(
            "Shaker inventory model must remain vanilla-compatible instead of using Forge loaders")
    paper_asset_roots = (ROOT / "src/paper/pack/resourcepack/assets",)
    custom_effect_font = asset_json(
        f"{NAMESPACE}:custom_effects", "font", paper_asset_roots)
    expected_effect_providers = [{
        "type": "bitmap",
        "file": f"{NAMESPACE}:mob_effect/{effect_id}.png",
        "ascent": 8,
        "height": 9,
        "chars": [chr(0xE100 + index)],
    } for index, effect_id in enumerate(CUSTOM_EFFECT_ICON_IDS)]
    if (custom_effect_font is None
            or custom_effect_font.get("providers") != expected_effect_providers):
        raise AssertionError(
            "Custom drink-effect HUD font must map all archived icons deterministically")
    for effect_id in CUSTOM_EFFECT_ICON_IDS:
        if not asset_exists(f"{NAMESPACE}:mob_effect/{effect_id}", "textures", ".png"):
            raise AssertionError(f"Missing custom drink-effect HUD icon: {effect_id}")

    # The corner HUD font must mirror tools/migrate_legacy.py and the glyph
    # tables hard-coded in CustomEffectHudSemantics exactly.
    hud_offset_powers = (1, 2, 4, 8, 16, 32, 64, 128, 256)
    expected_hud_providers: list[dict] = [{
        "type": "space",
        "advances": {
            **{chr(0xE300 + index): power for index, power in enumerate(hud_offset_powers)},
            **{chr(0xE310 + index): -power for index, power in enumerate(hud_offset_powers)},
        },
    }]
    for bg_char, bg_ascent, icon_base, icon_ascent in (
            (0xE320, 9, 0xE330, 6), (0xE321, -16, 0xE340, -19)):
        expected_hud_providers.append({
            "type": "bitmap",
            "file": "minecraft:gui/sprites/hud/effect_background.png",
            "ascent": bg_ascent,
            "height": 24,
            "chars": [chr(bg_char)],
        })
        expected_hud_providers.extend({
            "type": "bitmap",
            "file": f"{NAMESPACE}:font/hud_effect/{effect_id}.png",
            "ascent": icon_ascent,
            "height": 18,
            "chars": [chr(icon_base + index)],
        } for index, effect_id in enumerate(CUSTOM_EFFECT_ICON_IDS))
    hud_font = asset_json(f"{NAMESPACE}:custom_effects_hud", "font", paper_asset_roots)
    if hud_font is None or hud_font.get("providers") != expected_hud_providers:
        raise AssertionError(
            "Corner HUD font must keep the deterministic space/frame/icon glyph layout")
    for effect_id in CUSTOM_EFFECT_ICON_IDS:
        if not asset_exists(f"{NAMESPACE}:font/hud_effect/{effect_id}", "textures", ".png"):
            raise AssertionError(f"Missing padded corner HUD icon: {effect_id}")
    for sprite in ("yellow_background", "yellow_progress"):
        if not (ROOT / "src/paper/pack/resourcepack/assets/minecraft/textures"
                / f"gui/sprites/boss_bar/{sprite}.png").is_file():
            raise AssertionError(
                f"Corner HUD needs the transparent YELLOW boss bar sprite: {sprite}")
    # The vanilla overlay splits rows by MobEffectCategory; the archived Forge
    # registrations are the source of truth for which effects are beneficial.
    forge_effect_root = ROOT / "src/main/java/com/github/ysbbbbbb/kaleidoscopetavern/effect"
    mod_effects_source = (MOD_BLOCKS.parent / "ModEffects.java").read_text(encoding="utf-8-sig")
    neutral_effects = set()
    if "SLIGHTLY_TIPSY = EFFECTS.register(\"slightly_tipsy\", () -> new BaseEffect(MobEffectCategory.NEUTRAL" in mod_effects_source:
        neutral_effects.add("slightly_tipsy")
    for source_file in sorted(forge_effect_root.glob("*Effect.java")):
        body = source_file.read_text(encoding="utf-8-sig")
        if "MobEffectCategory.NEUTRAL" in body and source_file.name != "BaseEffect.java":
            neutral_effects.add(re.sub(
                r"(?<!^)(?=[A-Z])", "_", source_file.stem.removesuffix("Effect")).lower())
        if "MobEffectCategory.HARMFUL" in body:
            raise AssertionError(
                f"{source_file.name}: harmful category is new; update the corner HUD row split")
    if neutral_effects != {"slightly_tipsy", "upside_down"}:
        raise AssertionError(
            f"Corner HUD row-two set drifted from the Forge registrations: {sorted(neutral_effects)}")
    hud_semantics_source = (game_package / "CustomEffectHudSemantics.java").read_text(
        encoding="utf-8-sig")
    # The ambient swirl colours must stay byte-identical to the archived
    # Forge registrations.
    registered_colors = dict(re.findall(
        r'EFFECTS\.register\("(\w+)",[^\n]*?0x([0-9A-Fa-f]{6})\)', mod_effects_source))
    if set(registered_colors) != set(CUSTOM_EFFECT_ICON_IDS):
        raise AssertionError(
            f"ModEffects colour extraction drifted: {sorted(registered_colors)}")
    for effect_id, color in registered_colors.items():
        entry = f'Map.entry("{NAMESPACE}:{effect_id}", 0x{color.upper()})'
        if entry not in hud_semantics_source:
            raise AssertionError(
                f"CustomEffectHudSemantics colour table is missing {entry}")
    for row2_effect in ("slightly_tipsy", "upside_down"):
        if f'"{NAMESPACE}:{row2_effect}"' not in hud_semantics_source.split("HUD_ROW2_EFFECTS")[1].split(";")[0]:
            raise AssertionError(
                f"CustomEffectHudSemantics.HUD_ROW2_EFFECTS must contain {row2_effect}")

    # Drinking hands the authored vessel back through CE's consume_replacement.
    cocktail_ids = {row[1] for row in tsv_rows("shaker.tsv")} | {
        f"{NAMESPACE}:mystery_cocktail",
        f"{NAMESPACE}:signature_cocktail",
    }
    for item_id in drink_ids:
        replacement = items[item_id].get("settings", {}).get("consume_replacement")
        expected = (f"{NAMESPACE}:empty_glassware" if item_id in cocktail_ids
                    else f"{NAMESPACE}:empty_bottle")
        if replacement != expected:
            raise AssertionError(
                f"{item_id}: consume_replacement must be {expected}, got {replacement!r}")
    empty_glassware = items[f"{NAMESPACE}:empty_glassware"]
    if "consume_replacement" in empty_glassware.get("settings", {}):
        raise AssertionError("empty_glassware must not return itself as a consume replacement")

    # String-lights dyeing is expressed as CE block events.
    dye_colors = ("white", "orange", "magenta", "light_blue", "yellow", "lime",
                  "pink", "gray", "light_gray", "cyan", "purple", "blue",
                  "brown", "green", "red", "black")
    for color in dye_colors:
        lights = blocks[f"{NAMESPACE}:string_lights_{color}"]
        light_events = lights.get("events", [])
        if len(light_events) != len(dye_colors) - 1:
            raise AssertionError(
                f"string_lights_{color}: expected {len(dye_colors) - 1} dye events")
        for entry in light_events:
            functions = {f["type"] for f in entry["functions"]}
            if not {"transform_block", "cancel_event", "set_count"} <= functions:
                raise AssertionError(
                    f"string_lights_{color}: dye event missing core functions")
            if {c["type"] for c in entry["conditions"]} != {"match_item", "hand"}:
                raise AssertionError(
                    f"string_lights_{color}: dye event needs match_item plus hand")

    # Trellis waxing/scraping and wild-grapevine shearing are CE block events;
    # incense toggling is a CE furniture event. The Java listener branches for
    # all three are deliberately gone and must not come back.
    trellis_events = blocks[f"{NAMESPACE}:trellis"].get("events", [])
    if len(trellis_events) != 2:
        raise AssertionError("trellis: expected exactly wax-on plus wax-off events")
    for entry, item_id, waxed_before, waxed_after, sound in (
            (trellis_events[0], "minecraft:honeycomb", "false", "true",
             "minecraft:item.honeycomb.wax_on"),
            (trellis_events[1], "minecraft:.+_axe", "true", "false",
             "minecraft:item.axe.wax_off")):
        conditions = {c["type"]: c for c in entry["conditions"]}
        functions = {f["type"]: f for f in entry["functions"]}
        if (entry.get("on") != "right_click"
                or conditions["match_item"].get("item") != item_id
                or conditions["match_block_property"].get("properties") != {"waxed": waxed_before}
                or "hand" not in conditions
                or functions["update_block_property"].get("properties") != {"waxed": waxed_after}
                or functions["play_sound"].get("sound") != sound
                or "particle" not in functions
                or "cancel_event" not in functions):
            raise AssertionError(f"trellis: wax event drift for {item_id}")
        if "set_count" in functions or "damage_item" in functions:
            raise AssertionError(
                "trellis: the source never consumes the honeycomb nor damages the axe")
    if not trellis_events[1]["conditions"][0].get("regex"):
        raise AssertionError("trellis: wax-off must match every axe via regex")

    wild_events = blocks[f"{NAMESPACE}:wild_grapevine"].get("events", [])
    if len(wild_events) != 1:
        raise AssertionError("wild_grapevine: expected a single shear event")
    shear = wild_events[0]
    shear_conditions = {c["type"]: c for c in shear["conditions"]}
    shear_functions = {f["type"]: f for f in shear["functions"]}
    if (shear.get("on") != "right_click"
            or shear_conditions["match_item"].get("item") != "minecraft:shears"
            or shear_conditions["match_block_property"].get("properties") != {"sheared": "false"}
            or "hand" not in shear_conditions
            or shear_functions["update_block_property"].get("properties") != {"sheared": "true"}
            or shear_functions["play_sound"].get("sound") != "minecraft:entity.sheep.shear"
            or "damage_item" not in shear_functions
            or "cancel_event" not in shear_functions):
        raise AssertionError("wild_grapevine: shear event drift")

    incense_ids = sorted(fid for fid in furniture if fid.endswith("_incense"))
    if len(incense_ids) != 8:
        raise AssertionError(f"Expected 8 incense furniture definitions, found {len(incense_ids)}")
    for incense_id in incense_ids:
        incense_events = furniture[incense_id].get("events", [])
        if len(incense_events) != 1 or incense_events[0].get("on") != "right_click":
            raise AssertionError(f"{incense_id}: expected one right_click toggle event")
        toggle_functions = {f["type"]: f for f in incense_events[0]["functions"]}
        # Two sibling events on the same trigger would both run in order (the
        # second sees the variant the first just set and flips it straight
        # back); only a single if_else keeps the toggle atomic.
        if "if_else" not in toggle_functions or "cancel_event" not in toggle_functions:
            raise AssertionError(
                f"{incense_id}: toggle must be an atomic if_else plus cancel_event")
        transitions = {}
        for rule in toggle_functions["if_else"]["rules"]:
            source = next(c for c in rule["conditions"]
                          if c["type"] == "match_furniture_variant")["variant"]
            rule_functions = {f["type"]: f for f in rule["functions"]}
            transitions[source] = rule_functions["set_furniture_variant"].get("variant")
            if "play_sound" not in rule_functions or "message" not in rule_functions:
                raise AssertionError(f"{incense_id}: toggle branch missing sound or message")
        if transitions != {"ground": "ground_open", "ground_open": "ground"}:
            raise AssertionError(f"{incense_id}: toggle must swap ground and ground_open")

    # Wild grapevine worldgen rides CraftEngine's feature pipeline now; the
    # plugin must not re-implement a Bukkit-side generator.
    worldgen = json.loads((ROOT / "src/paper/pack/configuration/worldgen.json").read_text(
        encoding="utf-8-sig"))
    chain = worldgen["configured_features"][f"{NAMESPACE}:wild_grapevine_chain"]
    layers = chain["config"]["layers"]
    if (chain["type"] != "minecraft:block_column"
            or chain["config"]["direction"] != "down"
            or layers[0]["height"] != {
                "type": "minecraft:uniform", "min_inclusive": 0, "max_inclusive": 6}
            or layers[0]["provider"]["state"]["Name"]
            != f"{NAMESPACE}:wild_grapevine_plant"
            or layers[1]["height"] != 1
            or layers[1]["provider"]["state"]["Name"]
            != f"{NAMESPACE}:wild_grapevine"):
        raise AssertionError("Wild grapevine feature must hang body segments above a head")
    placed_feature = worldgen["placed_features"][f"{NAMESPACE}:wild_grapevine"]
    placements = {entry["type"]: entry for entry in placed_feature["placement"]}
    for required in ("minecraft:rarity_filter", "minecraft:count", "minecraft:in_square",
                     "minecraft:heightmap", "minecraft:environment_scan",
                     "minecraft:block_predicate_filter"):
        if required not in placements:
            raise AssertionError(f"Wild grapevine placed feature is missing {required}")
    environment_scan = placements["minecraft:environment_scan"]
    target_condition = environment_scan.get("target_condition", {})
    target_predicates = target_condition.get("predicates", [])
    expected_air = {"type": "minecraft:matching_blocks", "blocks": "minecraft:air"}
    expected_leaves = {
        "type": "minecraft:matching_blocks",
        "offset": [0, 1, 0],
        "blocks": ["minecraft:oak_leaves", "minecraft:birch_leaves"],
    }
    if (environment_scan.get("direction_of_search") != "down"
            or target_condition.get("type") != "minecraft:all_of"
            or expected_air not in target_predicates
            or expected_leaves not in target_predicates):
        raise AssertionError(
            "Wild grapevine worldgen must anchor the head/body chain directly below oak or birch leaves")
    if (ROOT / "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game"
            / "WorldgenService.java").exists():
        raise AssertionError("WorldgenService must stay deleted; CE features own worldgen")

    # The CustomNameplates hand-off: the bundled reference config, the
    # PlaceholderAPI expansion and the soft dependencies must stay consistent.
    hud_placeholder_source = (
        ROOT / "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper"
        "/integration/EffectHudPlaceholder.java").read_text(encoding="utf-8-sig")
    for token in ('return "kaleidoscopetavern";', '"effect_hud"', '"effect_count"'):
        if token not in hud_placeholder_source:
            raise AssertionError(
                f"EffectHudPlaceholder must keep the documented placeholder API: {token}")
    nameplates_snippet = (
        ROOT / "src/paper/customnameplates/bossbar-tavern-effects.yml").read_text(
        encoding="utf-8-sig")
    for token in ("%kaleidoscopetavern_effect_hud%",
                  "%kaleidoscopetavern_effect_count%",
                  "'!equals':"):
        if token not in nameplates_snippet:
            raise AssertionError(
                f"CustomNameplates reference bossbar config is missing {token}")
    paper_plugin_yml = (ROOT / "src/paper/resources/plugin.yml").read_text(
        encoding="utf-8-sig")
    if "softdepend: [PlaceholderAPI, CustomNameplates]" not in paper_plugin_yml:
        raise AssertionError(
            "plugin.yml must soft-depend on PlaceholderAPI and CustomNameplates for load order")
    if "mode: auto" not in plugin_config or "effect-hud:" not in plugin_config:
        raise AssertionError(
            "config.yml must document the effect-hud mode switch and default to auto")
    if "style: corner" not in plugin_config or "gui-half-width: 240" not in plugin_config:
        raise AssertionError(
            "config.yml must default the effect HUD to the vanilla-position corner style")
    shaker_3d_model = asset_json(
        f"{NAMESPACE}:item/shaker_3d", "models", paper_asset_roots)
    source_shaker_3d = asset_json(
        f"{NAMESPACE}:item/shaker_3d", "models", SOURCE_ASSET_ROOTS)
    if (shaker_3d_model is None or source_shaker_3d is None
            or shaker_3d_model != {
                key: value for key, value in source_shaker_3d.items()
                if key != "groups"
            }
            or len(shaker_3d_model.get("elements", [])) != 5
            or set(shaker_3d_model.get("display", {})) != {
                "thirdperson_righthand", "thirdperson_lefthand",
                "firstperson_righthand", "firstperson_lefthand",
                "ground", "head",
            }):
        raise AssertionError(
            "Shaker held/ground/head model must retain the authored 3D geometry and transforms")
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
    for pendant_id in ("bell_pendant_lamp", "blue_pendant_lamp", "yellow_pendant_lamp"):
        for half in ("top", "bottom"):
            model = asset_json(
                f"{NAMESPACE}:block/deco/{pendant_id}/{half}",
                "models",
                paper_asset_roots,
            )
            particle = None if model is None else model.get("textures", {}).get("particle")
            if particle != "minecraft:block/iron_chain":
                raise AssertionError(
                    f"{pendant_id}/{half}: Paper 26.2 requires the iron_chain particle texture")
    tap = furniture[f"{NAMESPACE}:tap"]["variants"]["wall"]["elements"][0]
    if tap.get("position") != "0,0,0.01" or tap.get("translation") != "0,0,0.49":
        raise AssertionError("Wall model must retain its target-block offset after anti-blackening compensation")
    if "rotation" in tap or render_items[tap["item"]]["model"]["path"] != (
            f"{NAMESPACE}:block/brew/tap/close"):
        raise AssertionError("Tap must retain the north-authored mounting-plate orientation")
    tap_hitbox = furniture[f"{NAMESPACE}:tap"]["variants"]["wall"]["hitboxes"][0]
    if tap_hitbox.get("position") != "0,-0.1875,0.35":
        raise AssertionError("Tap interaction hitbox must retain its corrected wall depth")
    tap_open_hitbox = furniture[
        f"{NAMESPACE}:tap"]["variants"]["wall_open"]["hitboxes"][0]
    if tap_open_hitbox.get("position") != "0,-0.1875,0.6875":
        raise AssertionError("Open tap interaction hitbox must retain its authored depth")

    pressing_tub_wall = furniture[
        f"{NAMESPACE}:pressing_tub"]["variants"]["wall"]["elements"][0]
    if (pressing_tub_wall.get("position") != "0,0,0.19"
            or pressing_tub_wall.get("translation") != "0,0,-0.627"):
        raise AssertionError("Tilted pressing tub must retain its corrected wall depth")

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
        wall_element = wall["elements"][0]
        if (wall_element.get("position") != "0,0,0.19"
                or wall_element.get("translation") != "0,0,-0.627"):
            raise AssertionError(f"{painting_id}: wall display depth drifted")

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
