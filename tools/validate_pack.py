#!/usr/bin/env python3
"""Strict, dependency-free validation for the generated CraftEngine pack."""

from __future__ import annotations

import json
import math
import re
from collections import defaultdict
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "src/paper/pack/configuration"
CATALOG = ROOT / "src/paper/resources/catalog"
CUSTOM_CROPS = ROOT / "src/paper/customcrops/contents/crops/kaleidoscope_tavern.yml"
PLUGIN_CONFIG = ROOT / "src/paper/resources/config.yml"
NAMESPACE = "kaleidoscope_tavern"
WALL_PRESSING_TUB = "_internal/wall_pressing_tub"
WALL_PRESSING_TUB_ID = f"{NAMESPACE}:{WALL_PRESSING_TUB}"
EFFECTLESS_DRINKS = {f"{NAMESPACE}:watermelon_juice"}
COPPER_LANTERN_CARRIER_STATE = (
    "minecraft:copper_lantern[hanging=false,waterlogged=false]"
)
STORAGE_BLOCK_SPECS = {
    "bar_cabinet": (2, None, "minecraft:barrier"),
    "glass_bar_cabinet": (2, None, "minecraft:barrier"),
    "cellar_cabinet": (9, "cellar_cabinet_blocklist", "minecraft:barrier"),
    "tilted_rack": (3, "tilted_rack_blocklist", "minecraft:barrier"),
    "circular_rack": (6, "circular_rack_blocklist", "minecraft:barrier"),
    "holder": (1, "holder_blocklist", "minecraft:barrier"),
}
EXPECTED_TICKING_FURNITURE = {
    "mystery_cocktail": (
        {"channel": "mystery_particle", "chance": 49},
    ),
    "barrel": (
        {"channel": "barrel", "interval": 97, "phase": "identity"},
    ),
}
INCENSE_BLOCK_SPECS = {
    "sakura_incense": ("CHERRY_LEAVES", "CHERRY_LEAVES", -2.0, 16.0),
    "pine_incense": ("SMOKE", "CAMPFIRE_COSY_SMOKE", -2.0, 16.0),
    "ginkgo_incense": ("WAX_OFF", "COMPOSTER", -2.0, 16.0),
    "spore_incense": ("SPORE_BLOSSOM_AIR", "SPORE_BLOSSOM_AIR", -2.0, 16.0),
    "catnip_incense": ("HAPPY_VILLAGER", "HAPPY_VILLAGER", -2.0, 16.0),
    "snow_incense": ("SNOWFLAKE", "SNOWFLAKE", -2.0, 16.0),
    "butterfly_incense": ("GLOW", "GLOW", -2.0, 16.0),
    "firefly_incense": ("FIREFLY", "FIREFLY", -0.67, 5.33),
}
EXPECTED_STATE_FURNITURE = {
    "base_sandwich_board", "grass_sandwich_board", "allium_sandwich_board",
    "azure_bluet_sandwich_board", "cornflower_sandwich_board",
    "orchid_sandwich_board", "peony_sandwich_board",
    "pink_petals_sandwich_board", "pitcher_plant_sandwich_board",
    "poppy_sandwich_board", "sunflower_sandwich_board",
    "torchflower_sandwich_board", "tulip_sandwich_board",
    "wither_rose_sandwich_board",
    "barrel",
    "wine", "champagne", "vodka", "brandy", "carignan", "sakura_wine",
    "plum_wine", "whiskey", "ice_wine", "polaris_sweet_white",
    "honey_wine", "red_queen", "miners_star", "rum",
    "riesling_dry_white", "sunset_glow", "madame_shexiang",
    "sweet_berry_wine", "sherry", "mother_snow", "luminous_bride",
    "glowflower_brew", "sauvignon_blanc_dry_white", "vinegar",
    "watermelon_juice",
    # Migration-only legacy state plus the active wall-only representation.
    "pressing_tub", WALL_PRESSING_TUB,
}
EXPECTED_BOTTLE_FURNITURE = {
    "empty_bottle", "empty_glassware",
    "signature_cocktail", "mystery_cocktail", "white_lady", "emerald",
    "brass_heart", "godfather", "grasshopper", "screwdriver", "mojito",
    "allium_garden", "depth_charge", "nether_special", "bloody_mary",
    "sculk_special", "molotov", "water_bottle", "honey_bottle",
    "dragon_breath_bottle", "potion_bottle", "xp_bottle",
    "wine", "champagne", "vodka", "brandy", "carignan", "sakura_wine",
    "plum_wine", "whiskey", "ice_wine", "polaris_sweet_white",
    "honey_wine", "red_queen", "miners_star", "rum",
    "riesling_dry_white", "sunset_glow", "madame_shexiang",
    "sweet_berry_wine", "sherry", "mother_snow", "luminous_bride",
    "glowflower_brew", "sauvignon_blanc_dry_white", "vinegar",
    "watermelon_juice",
}
OPAQUE_PLACED_DRINK_ELEMENTS = {
    "allium_garden": (12, 13),
    "bloody_mary": (1, 10, 11, 12, 13),
    "brass_heart": (11,),
    "depth_charge": (8, 9, 10, 11),
    "emerald": (5, 6, 7),
    "godfather": (9, 10, 11, 12, 13),
    "grasshopper": (0, 1, 2, 13),
    "mojito": (0, 10, 11, 12, 13),
    "mystery_cocktail": (12,),
    "nether_special": (12, 13, 14, 15),
    "screwdriver": (7, 8, 9),
    "sculk_special": (12, 13, 14),
    "signature_cocktail": (12, 13, 14),
    "white_lady": (10, 11, 12),
}
EXPECTED_CONSUMABLE_COCKTAILS = {
    "signature_cocktail", "mystery_cocktail", "white_lady", "emerald",
    "brass_heart", "godfather", "grasshopper", "screwdriver", "mojito",
    "allium_garden", "depth_charge", "nether_special", "bloody_mary",
    "sculk_special",
}
SIMPLE_BOTTLES = {
    "water_bottle", "honey_bottle", "dragon_breath_bottle",
    "potion_bottle", "xp_bottle",
}
EXPECTED_STORAGE_INTERACTION_FURNITURE = {
    "glassware_holder",
}
EXPECTED_STATION_INTERACTION_FURNITURE = {
    "barrel", "shaker", "empty_glassware", WALL_PRESSING_TUB,
}
FURNITURE_COLORS = {
    "black", "blue", "brown", "cyan", "gray", "green", "light_blue",
    "light_gray", "lime", "magenta", "orange", "pink", "purple", "red",
    "white", "yellow",
}
SOFA_BLOCKS = {f"{color}_sofa" for color in FURNITURE_COLORS}
SHARED_SOFA_BLOCK = "_internal/sofa"
SHARED_SOFA_ID = f"{NAMESPACE}:{SHARED_SOFA_BLOCK}"
SOFA_CONNECTIONS = (
    "single", "left", "left_corner", "middle", "right", "right_corner",
)
SOFA_DYE_COLORS = {
    "white": "249,255,254", "orange": "249,128,29",
    "magenta": "199,78,189", "light_blue": "58,179,218",
    "yellow": "254,216,61", "lime": "128,199,31",
    "pink": "243,139,170", "gray": "71,79,82",
    "light_gray": "157,157,151", "cyan": "22,156,156",
    "purple": "137,50,184", "blue": "60,68,170",
    "brown": "131,84,50", "green": "94,124,22",
    "red": "176,46,38", "black": "29,29,33",
}
CONNECTED_GRID_BLOCKS = {"bar_counter", "table"}
MIGRATION_BLOCK_FURNITURE = SOFA_BLOCKS | CONNECTED_GRID_BLOCKS | {
    "bar_cabinet", "glass_bar_cabinet", "pressing_tub",
}
EXPECTED_LIFECYCLE_FURNITURE: dict[str, tuple[str, ...]] = {
    "base_sandwich_board": ("board",),
    "grass_sandwich_board": ("board",),
    "allium_sandwich_board": ("board",),
    "azure_bluet_sandwich_board": ("board",),
    "cornflower_sandwich_board": ("board",),
    "orchid_sandwich_board": ("board",),
    "peony_sandwich_board": ("board",),
    "pink_petals_sandwich_board": ("board",),
    "pitcher_plant_sandwich_board": ("board",),
    "poppy_sandwich_board": ("board",),
    "sunflower_sandwich_board": ("board",),
    "torchflower_sandwich_board": ("board",),
    "tulip_sandwich_board": ("board",),
    "wither_rose_sandwich_board": ("board",),
    "shaker": ("shaker",),
    "barrel": ("barrel",),
    "empty_bottle": ("tap_bottle",),
}
EXPECTED_LIFECYCLE_FURNITURE.update({
    f"{color}_bar_stool": ("bar_stool",) for color in FURNITURE_COLORS
})
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
PENDANT_LAMPS = {"bell_pendant_lamp", "blue_pendant_lamp", "yellow_pendant_lamp"}
PRESS_FLUIDS = {
    "glow_berries_juice",
    "gold_grape_juice",
    "grape_juice",
    "green_grape_juice",
    "ice_grape_juice",
    "sweet_berries_juice",
}
BARREL_FLUIDS = PRESS_FLUIDS | {"water", "lava"}
BAR_STOOL_COLORS = (
    "black", "blue", "brown", "cyan", "gray", "green", "light_blue",
    "light_gray", "lime", "magenta", "orange", "pink", "purple", "red",
    "white", "yellow",
)

# Every source blockstate property is intentionally assigned to either a CE
# representation or a named Paper runtime owner.  Comparing this manifest to
# the source blockstates makes newly introduced semantics fail validation
# instead of silently disappearing during migration.
SOURCE_STATE_OWNERS = {
    "age": "CustomCrops stage blocks",
    "axis": "CE native placement except table source axis, mapped to table_axis for ground placement",
    "connection": "ConnectedBlockBehavior sofa/counter topology plus migration furniture variants",
    "count": "BottleFurnitureService",
    "face": "CE ground/wall/ceiling placement rules",
    "facing": "CE chalkboard/tap/storage state plus native wall and four-way/sixteen-way furniture rotation",
    "half": "CE native double-high chalkboard plus composite multi-element furniture variants",
    "open": "CE incense/tap block state",
    "position": "CE chalkboard/storage state plus ConnectedBlockBehavior table topology",
    "powered": "CE incense/storage block redstone edge state",
    "rotation": "CE sixteen-way sandwich-board rotation",
    "tilt": "ground/wall pressing-tub placement variants",
    "triggered": "CE TapBlockBehavior redstone edge latch",
    "type": "CE trellis state variants",
    "waterlogged": "CE chalkboard/tap/trellis state plus water-preserving glowing string-light furniture",
    "waxed": "CE trellis state variants plus blocks.json wax events",
}

# Block-entity renderers are the easiest source of an apparently valid but
# invisible/incomplete port.  This closed manifest forces each renderer onto a
# concrete Paper implementation; a newly added source renderer cannot pass CI
# without an explicit migration decision.
RENDERER_COVERAGE = {
    "BarCabinetBlockEntityRender.java": ("block/StorageBlockConfig.java", "record SlotVisual"),
    "BarrelBlockEntityRender.java": ("StationService.java", "BarrelSemantics"),
    "BarStoolBlockEntityRender.java": ("BarStoolVisualService.java", "getBodyYaw"),
    "CellarCabinetBlockEntityRender.java": ("block/StorageBlockConfig.java", "record Orientation"),
    "ChalkboardBlockEntityRender.java": (
        "block/ChalkboardBlockBehavior.java", "class BlockTextElement"),
    "CircularRackBlockEntityRender.java": ("block/StorageBlockConfig.java", "record SlotVisual"),
    "GlasswareHolderBlockEntityRender.java": ("DisplayStorageService.java", "StorageSemantics"),
    "HolderBlockEntityRender.java": ("block/StorageBlockConfig.java", "record SlotVisual"),
    "PressingTubBlockEntityRender.java": ("PressingTubVisualFactory.java", "visuals"),
    "SandwichBlockEntityRender.java": ("BoardTextService.java", "sandwich"),
    "ShakerBlockEntityRender.java": ("ShakerVisualService.java", "ShakerAnimationSemantics"),
    "StorageBlockEntityRender.java": ("block/StorageBlockBehavior.java", "renderPosition("),
    "TextBlockEntityRender.java": ("BoardTextService.java", "boardVisuals"),
    "TiltedRackBlockEntityRender.java": ("block/StorageBlockConfig.java", "record SlotVisual"),
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
    "AbstractStorageBlock.java": (
        ("block/StorageBlockBehavior.java", "public void neighborChanged"),
        ("block/StorageBlockBehavior.java", "private InteractionResult interact("),
        ("block/StorageBlockBehavior.java", "private void launchRandom()"),
        ("block/StorageBlockConfig.java", "record Interaction("),
    ),
    "BarCabinetBlock.java": (
        ("block/ConnectedBlockBehavior.java", "private ImmutableBlockState updateLinear("),
        ("block/StorageBlockConfig.java", "boolean fallbackPut"),
        ("src/paper/pack/configuration/blocks.json", '"exclusive_items"'),
    ),
    "BarStoolBlock.java": (
        ("tools/migrate_legacy.py", "_bar_stool"),
        ("BarStoolVisualService.java", "onMount"),
    ),
    "BarrelBlock.java": (("StationService.java", "interactBarrel"),),
    "BottleBlock.java": (
        ("furniture/BottleFurnitureBehavior.java", "useOnFurniture"),
        ("BottleFurnitureService.java", "private InteractionResult interact"),
    ),
    "BottleBlockDispenseBehavior.java": (
        ("BottlePlacementService.java", "onDispenseBottle"),
    ),
    "CellarCabinetBlock.java": (
        ("block/ConnectedBlockBehavior.java", "private ImmutableBlockState updateLinear("),
        ("block/StorageBlockConfig.java", "boolean frontOnly"),
        ("src/paper/pack/configuration/blocks.json", '"selector"'),
    ),
    "ChalkboardBlock.java": (
        ("block/ChalkboardBlockBehavior.java", "private void tryMerge("),
        ("block/ChalkboardBlockBehavior.java", "private void removeOtherParts("),
        ("BoardTextService.java", "private InteractionResult interactChalkboard("),
    ),
    "CircularRackBlock.java": (
        ("block/StorageBlockConfig.java", "record ParticleEffect("),
        ("block/StorageBlockBehavior.java", "private static void tickParticle("),
        ("src/paper/pack/configuration/blocks.json", '"alternate_min_x"'),
    ),
    "CocktailBlockItem.java": (
        ("EffectService.java", "onConsume"),
        ("src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/item/behavior/"
         "SneakPlaceDrinkItemBehavior.java", "useOnBlock"),
    ),
    "DrinkBlock.java": (("BottleFurnitureService.java", "onProjectileHit"),),
    "DrinkBlockItem.java": (
        ("EffectService.java", "onConsume"),
        ("src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/item/behavior/"
         "SneakPlaceDrinkItemBehavior.java", "useOnBlock"),
    ),
    "GlasswareBlock.java": (("BottleFurnitureService.java", "onProjectileHit"),),
    "GlasswareHolderBlock.java": (("DisplayStorageService.java", "GLASSWARE_HOLDER"),),
    "GrapeCropBlock.java": (
        ("block/HangingGrapeCropBehavior.java", "addGrowthPoints"),
        ("block/HangingGrapeCropBehavior.java", "CustomCropsBridge.removeCrop"),
        ("src/paper/customcrops/contents/crops/kaleidoscope_tavern.yml", "harvest_with_shears"),
    ),
    "GrapevineItem.java": (
        ("src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/item/behavior/"
         "GrapevineItemBehavior.java", "useOnBlock"),
        ("block/BlockService.java", "useGrapevineOnBlock"),
        ("tools/migrate_legacy.py", '"fuel_time"'),
    ),
    "GrapevineTrellisBlock.java": (
        ("tools/migrate_legacy.py", "grapevine_trellis_shear_events"),
        ("block/TrellisBehavior.java", "implements BonemealableBlock"),
        ("block/TrellisBehavior.java", "public static boolean grow"),
    ),
    "HolderBlock.java": (
        ("block/StorageBlockConfig.java", "record Launch("),
        ("block/StorageBlockConfig.java", "case SINGLE ->"),
        ("src/paper/pack/configuration/blocks.json", '"origin_forward"'),
    ),
    "IncenseBlock.java": (
        ("src/paper/pack/configuration/blocks.json", "minecraft:copper_lantern"),
        ("tools/migrate_legacy.py", "incense_toggle_events"),
        ("block/IncenseBlockBehavior.java", "updateStateForPlacement"),
        ("block/IncenseBlockBehavior.java", "neighborChanged"),
        ("block/IncenseBlockBehavior.java", "spawnParticles"),
    ),
    "JuiceBucketItem.java": (("tools/migrate_legacy.py", "milk_bucket"),),
    "MolotovBlock.java": (("MolotovService.java", "onProjectileHit"),),
    "MolotovBlockItem.java": (
        ("MolotovService.java", "onStopUsing"),
        ("tools/migrate_legacy.py", "consume_seconds"),
    ),
    "MysteryCocktailBlock.java": (
        ("AmbientFurnitureService.java", "tickMysteryCocktail"),
        ("furniture/TickingFurnitureBehavior.java", "MYSTERY_PARTICLE"),
    ),
    "PressingTubBlock.java": (
        ("block/PressingTubBlockBehavior.java", "void fallOn(Object thisBlock, Object[] args)"),
        ("tools/migrate_legacy.py", '"type": "ground_block_item"'),
        ("tools/migrate_legacy.py", "WALL_PRESSING_TUB_ID"),
        ("PressingTubService.java", "interactPress"),
        ("PressingTubService.java", "boolean press"),
    ),
    "SandwichBoardBlock.java": (("BoardTextService.java", "transformSandwichBoard"),),
    "ShakerBlock.java": (("StationService.java", "interactShaker"),),
    "ShakerItem.java": (
        ("src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/item/behavior/"
         "ShakerItemBehavior.java", "InteractionResult use(World world"),
        ("StationService.java", "usePortableShaker"),
        ("ShakerSemantics.java", "AUTO_RELEASE_AFTER_TICKS"),
    ),
    "SofaBlock.java": (
        ("block/ConnectedBlockBehavior.java", "private ImmutableBlockState updateCorner("),
        ("tools/migrate_legacy.py", '"type": "seat_block"'),
    ),
    "StringLightsBlock.java": (
        ("tools/migrate_legacy.py", 'variants["wall"]'),
        ("tools/migrate_legacy.py", '"type": "glowing_furniture"'),
        ("tools/migrate_legacy.py", "string_lights_dye_events"),
        ("tools/migrate_legacy.py", '"type": "replace_furniture"'),
    ),
    "StringLightsBlockItem.java": (
        ("src/paper/resources/catalog/tags.tsv", "curios:charm"),
        ("src/paper/pack/configuration/items.json", '"type": "furniture_item"'),
    ),
    "TapBlock.java": (
        ("src/paper/pack/configuration/blocks.json", "minecraft:lightning_rod"),
        ("block/TapBlockBehavior.java", "updateStateForPlacement"),
        ("block/TapBlockBehavior.java", "neighborChanged"),
        ("block/TapBlockBehavior.java", "useOnBlock"),
        ("block/TapBlockBehavior.java", "TAKE_TICKS = 30"),
        ("TapService.java", "TapBlockBehavior.bind(this)"),
        ("TapSemantics.java", "isBarrelConnection"),
    ),
    "TiltedRackBlock.java": (
        ("block/StorageBlockConfig.java", "case SPLIT ->"),
        ("block/StorageBlockConfig.java", "public enum LaunchDirection"),
        ("src/paper/pack/configuration/blocks.json", '"x_rotation"'),
    ),
    "TrellisBlock.java": (
        ("block/BlockService.java", "useGrapevineOnBlock"),
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
    "BarrelBlockEntity.java": ("StationService.java", "barrelTickingHandler"),
    "BarStoolBlockEntity.java": ("BarStoolVisualService.java", "tickOccupied"),
    "TapBlockEntity.java": ("block/TapBlockBehavior.java", "TAKE_PARTICLE_TICKS = 5"),
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
    "BarCabinetBlockEntity.java": (("block/StorageBlockBehavior.java", "private final Item[] items"),),
    "BarrelBlockEntity.java": (("StationService.java", "barrel_items"),),
    "CellarCabinetBlockEntity.java": (("block/StorageBlockBehavior.java", "private final Item[] items"),),
    "DrinkBlockEntity.java": (("BottleFurnitureService.java", "storedItems"),),
    "PotionBottleBlockEntity.java": (("BottleFurnitureService.java", "sourceItem"),),
    "PressingTubBlockEntity.java": (
        ("block/PressingTubBlockBehavior.java",
         'private static final String DATA_KEY = "kaleidoscope_tavern:press"'),
        ("furniture/LegacyPressingTubMigrationFurnitureBehavior.java", "press_count"),
    ),
    "TapBlockEntity.java": (
        ("block/TapBlockBehavior.java", "private Cycle cycle"),
        ("block/TapBlockBehavior.java", "DRIP_LIFETIME_TICKS = 18"),
    ),
    "BarStoolBlockEntity.java": (
        ("BarStoolVisualService.java", "AnimatedItemFurnitureBehavior.updatePosition"),
    ),
    "ChalkboardBlockEntity.java": (
        ("block/ChalkboardBlockBehavior.java", "private static final String DATA_KEY"),
        ("BoardTextService.java", "controller.isLarge() ? 1_500 : 350"),
    ),
    "CircularRackBlockEntity.java": (
        ("block/StorageBlockBehavior.java", "private final Item[] items"),
        ("block/StorageBlockBehavior.java", "private static void tickParticle("),
    ),
    "GlasswareHolderBlockEntity.java": (("DisplayStorageService.java", "GLASSWARE_HOLDER"),),
    "HolderBlockEntity.java": (("block/StorageBlockBehavior.java", "Item[] items"),),
    "IncenseBlockEntity.java": (
        ("block/IncenseBlockBehavior.java", "hurtNearbyUndead"),
        ("block/IncenseBlockBehavior.java", "takeDamageDue()"),
    ),
    "SandwichBlockEntity.java": (("BoardTextService.java", "isSandwichBoard"),),
    "StorageBlockEntity.java": (("block/StorageBlockBehavior.java", "Item[] items"),),
    "TextBlockEntity.java": (("BoardTextService.java", "board_text"),),
    "TiltedRackBlockEntity.java": (("block/StorageBlockBehavior.java", "Item[] items"),),
    "ShakerBlockEntity.java": (
        ("StationService.java", "updateShakerSource"),
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


def assert_ordered_model_bounds(resource_id: str, owner: str) -> None:
    """Reject cuboids whose descending bounds invert ItemDisplay face winding."""
    model = asset_json(resource_id, "models")
    if model is None:
        raise AssertionError(f"{owner}: missing displayed model {resource_id}")
    axis_names = ("x", "y", "z")
    for index, element in enumerate(model.get("elements", [])):
        start = element.get("from")
        end = element.get("to")
        if not (isinstance(start, list) and isinstance(end, list)
                and len(start) == 3 and len(end) == 3):
            continue
        for axis, axis_name in enumerate(axis_names):
            if start[axis] > end[axis]:
                raise AssertionError(
                    f"{owner}: {resource_id} element {index} has descending {axis_name} "
                    "bounds, which exposes its back face when rendered as furniture")


def assert_forced_translucency(
    resource_id: str,
    texture_slots: set[str],
    owner: str,
) -> None:
    """Require the Minecraft 26.1+ texture descriptor on translucent geometry."""
    model = asset_json(resource_id, "models", roots=(ASSET_ROOTS[0],))
    if model is None:
        raise AssertionError(f"{owner}: missing generated translucent model {resource_id}")
    if "render_type" in model:
        raise AssertionError(
            f"{owner}: Forge render_type is ignored by the vanilla 26.2 client")
    textures = model.get("textures")
    if not isinstance(textures, dict):
        raise AssertionError(f"{owner}: generated translucent model has no textures")
    for slot in sorted(texture_slots):
        descriptor = textures.get(slot)
        if (not isinstance(descriptor, dict)
                or not isinstance(descriptor.get("sprite"), str)
                or descriptor.get("force_translucent") is not True):
            raise AssertionError(
                f"{owner}: texture slot {slot!r} must use a sprite descriptor with "
                "force_translucent=true")


def assert_no_forge_render_type(resource_id: str, owner: str) -> None:
    """Reject any Forge ``render_type`` left on a model the vanilla client loads.

    The vanilla client ignores Forge's render_type extension, so migrated
    furniture that kept it silently renders opaque instead of cutout or
    translucent.
    """
    model = asset_json(resource_id, "models")
    if model is None:
        raise AssertionError(f"{owner}: missing displayed model {resource_id}")
    if "render_type" in model:
        raise AssertionError(
            f"{owner}: {resource_id} keeps Forge render_type, which the vanilla "
            "26.2 client ignores and renders opaque")


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
    if len(blocks) != 60:
        raise AssertionError(f"Expected 60 grid/state blocks, found {len(blocks)}")
    if len(furniture) != 137:
        raise AssertionError(
            f"Expected 137 furniture definitions (legacy pressing tub plus "
            f"the private active wall tub), found {len(furniture)}")
    if len(render_items) != 503:
        raise AssertionError(f"Expected 503 private render items, found {len(render_items)}")
    if len(recipes) != 114:
        raise AssertionError(f"Expected 114 crafting recipes, found {len(recipes)}")

    for lamp in sorted(PENDANT_LAMPS):
        for half in ("top", "bottom"):
            assert_forced_translucency(
                f"{NAMESPACE}:block/deco/{lamp}/{half}", {"2"}, f"{lamp}/{half}")
    for fluid in sorted(PRESS_FLUIDS):
        assert_forced_translucency(
            f"{NAMESPACE}:furniture/pressing_fluid/{fluid}",
            {"fluid"},
            f"pressing_fluid/{fluid}",
        )
    for fluid in sorted(BARREL_FLUIDS):
        assert_forced_translucency(
            f"{NAMESPACE}:furniture/barrel_fluid/{fluid}",
            {"fluid"},
            f"barrel_fluid/{fluid}",
        )

    source_item_loot = {
        "pools": [{
            "rolls": 1,
            "entries": [{"type": "furniture_item"}],
        }]
    }
    for bottle_id in SIMPLE_BOTTLES:
        config = furniture[f"{NAMESPACE}:{bottle_id}"]
        if config.get("loot") != source_item_loot:
            raise AssertionError(
                f"{bottle_id}: vanilla-source bottle must use CE sourceItem furniture loot")
        if "item" in config.get("settings", {}):
            raise AssertionError(
                f"{bottle_id}: vanilla-source bottle must not invent a duplicate CE item")

    placed_drink_models: dict[str, str] = {}
    for bottle_id in sorted(EXPECTED_BOTTLE_FURNITURE):
        config = furniture[f"{NAMESPACE}:{bottle_id}"]
        for variant_name, variant in config.get("variants", {}).items():
            for element in variant.get("elements", []):
                if element.get("type") != "item_display":
                    continue
                render_id = element.get("item")
                definition = render_items.get(render_id, {})
                model_path = definition.get("model", {}).get("path")
                if not isinstance(model_path, str):
                    raise AssertionError(
                        f"{bottle_id}/{variant_name}: displayed drink item {render_id!r} "
                        "must select a vanilla model path")
                placed_drink_models[model_path] = f"{bottle_id}/{variant_name}"
    for render_id, definition in render_items.items():
        if not render_id.startswith(f"{NAMESPACE}:_render/storage/"):
            continue
        model_path = definition.get("model", {}).get("path")
        if isinstance(model_path, str):
            placed_drink_models[model_path] = render_id
            if "/block/brew/drink/" in model_path:
                model = asset_json(model_path, "models", roots=(ASSET_ROOTS[0],))
                if model is None:
                    raise AssertionError(
                        f"{render_id}: missing cutout storage bottle model {model_path}")
                forced_slots = {
                    slot for slot, texture in model.get("textures", {}).items()
                    if isinstance(texture, dict)
                    and texture.get("force_translucent") is True
                }
                if forced_slots:
                    raise AssertionError(
                        f"{render_id}: binary-alpha storage bottle slots "
                        f"{sorted(forced_slots)} must use cutout rendering to avoid flicker")
    for model_path, owner in placed_drink_models.items():
        assert_ordered_model_bounds(model_path, owner)
        assert_no_forge_render_type(model_path, owner)

    if set(OPAQUE_PLACED_DRINK_ELEMENTS) != EXPECTED_CONSUMABLE_COCKTAILS:
        raise AssertionError(
            "Every consumable cocktail must explicitly classify its opaque decorations")

    for drink_id, opaque_element_indices in OPAQUE_PLACED_DRINK_ELEMENTS.items():
        resource_path = (
            f"furniture/placed_drink/{NAMESPACE}/block/mixology/{drink_id}"
        )
        model_id = f"{NAMESPACE}:{resource_path}"
        model = asset_json(model_id, "models", roots=(ASSET_ROOTS[0],))
        if model is None:
            raise AssertionError(f"{drink_id}: missing private placed-drink model")
        textures = model.get("textures", {})
        opaque_sprite = (
            f"{NAMESPACE}:furniture/placed_drink/opaque/{NAMESPACE}/"
            f"block/mixology/{drink_id}"
        )
        if textures.get("opaque_detail") != opaque_sprite:
            raise AssertionError(
                f"{drink_id}: opaque detail must use its private cutout sprite")
        if not asset_exists(opaque_sprite, "textures", ".png"):
            raise AssertionError(f"{drink_id}: missing opaque detail texture")
        forced_slots = {
            slot for slot, texture in textures.items()
            if isinstance(texture, dict)
            and texture.get("force_translucent") is True
        }
        if len(forced_slots) != 1:
            raise AssertionError(
                f"{drink_id}: glass/liquid must retain one forced-translucent slot")
        elements = model.get("elements", [])
        missing_indices = [
            index for index in opaque_element_indices if index >= len(elements)
        ]
        if missing_indices:
            raise AssertionError(
                f"{drink_id}: missing opaque model elements {missing_indices}")
        translucent_slot = next(iter(forced_slots))
        for index, element in enumerate(elements):
            face_textures = {
                face.get("texture")
                for face in element.get("faces", {}).values()
            }
            if index in opaque_element_indices:
                if face_textures != {"#opaque_detail"}:
                    raise AssertionError(
                        f"{drink_id}: decoration element {index} is not fully opaque")
            elif face_textures != {f"#{translucent_slot}"}:
                raise AssertionError(
                    f"{drink_id}: glass/liquid element {index} must remain translucent")

        source_meta = (
            ROOT / f"src/main/resources/assets/{NAMESPACE}/textures/"
            f"block/mixology/{drink_id}.png.mcmeta"
        )
        generated_meta = (
            ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/textures/"
            f"furniture/placed_drink/opaque/{NAMESPACE}/block/mixology/"
            f"{drink_id}.png.mcmeta"
        )
        if source_meta.is_file() and (
                not generated_meta.is_file()
                or source_meta.read_bytes() != generated_meta.read_bytes()):
            raise AssertionError(
                f"{drink_id}: opaque animated detail must preserve source frames")

    assert_no_forge_render_type(
        f"{NAMESPACE}:furniture/bar_stool_body_base", "bar_stool_body_base")
    for color in BAR_STOOL_COLORS:
        assert_no_forge_render_type(
            f"{NAMESPACE}:furniture/bar_stool_body/{color}", f"bar_stool_body/{color}")

    game_package = (
        ROOT / "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/game")
    bottle_placement_source = (game_package / "BottlePlacementService.java").read_text(
        encoding="utf-8-sig")
    bottle_furniture_source = (game_package / "BottleFurnitureService.java").read_text(
        encoding="utf-8-sig")
    bottle_behavior_source = (
        game_package / "furniture/BottleFurnitureBehavior.java"
    ).read_text(encoding="utf-8-sig")
    block_service_source = (game_package / "block/BlockService.java").read_text(
        encoding="utf-8-sig")
    if ("grapevineFor returned null" in block_service_source
            or "onRightClickWithGrapevine: clicked" in block_service_source):
        raise AssertionError(
            "Unsupported grapevine soil is an expected rejection and must not spam the server log")
    if "Material.BONE_MEAL" in block_service_source:
        raise AssertionError(
            "Bone meal must use CraftEngine BonemealableBlock behavior, not a cancelled Bukkit event")
    trellis_behavior_source = (
        game_package / "block/TrellisBehavior.java").read_text(encoding="utf-8-sig")
    for behavior_source_path in (
            game_package / "block/TrellisBehavior.java",
            game_package / "block/WildGrapevineBehavior.java"):
        behavior_source = behavior_source_path.read_text(encoding="utf-8-sig")
        if ("public InteractionResult useOnBlock" not in behavior_source
                or "player.swingHand(context.getHand())" not in behavior_source
                or "return InteractionResult.SUCCESS;" not in behavior_source):
            raise AssertionError(
                f"{behavior_source_path.name}: CE bone-meal interaction must acknowledge use and swing the hand")
    for required_token in (
            "MutableBlockPosProxy.INSTANCE.newInstance()",
            "MutableBlockPosProxy.INSTANCE.setWithOffset(",
            "BlockGetterProxy.INSTANCE.getBlockState(level, targetPosition)"):
        if required_token not in trellis_behavior_source:
            raise AssertionError(
                "Trellis random ticks must reuse their NMS position instead of allocating "
                f"one Bukkit-to-CE BlockPos bridge per neighbour; missing {required_token}")
    for required_token in (
            "extends BukkitBlockBehavior",
            "private final Property<Direction.Axis> axisProperty",
            'block, "axis", Direction.Axis.class',
            "state.get(axisProperty)",
            "TrellisConnectionSemantics.typeFor(",
            'copyNamed(targetState, grown, "axis")',
            'copyNamed(targetState, grown, "waterlogged")'):
        if required_token not in trellis_behavior_source:
            raise AssertionError(
                "TrellisBehavior must consume CE's native axis and supplement its bucket "
                f"behavior with connection/water state; missing {required_token}")
    for stale_token in (
            "extends WaterloggedBlockBehavior", "waterloggedProperty",
            "FluidStateProxy", "FluidsProxy",
            "LevelAccessorProxy.INSTANCE.scheduleTick$1("):
        if stale_token in trellis_behavior_source:
            raise AssertionError(
                "Trellis waterlogging must be owned by CE's automatically attached "
                f"WaterloggedBlockBehavior; stale Java token: {stale_token}")
    for duplicate_token in (
            "context.getClickedFace().axis()",
            "typeForPlacement(",
            "updateType("):
        if duplicate_token in trellis_behavior_source:
            raise AssertionError(
                "TrellisBehavior must let CE own its placement axis; found duplicate "
                f"implementation {duplicate_token}")
    trellis_semantics_source = (
        game_package / "block/TrellisConnectionSemantics.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            'xConnected || baseAxis.equals("x")',
            'yConnected || baseAxis.equals("y")',
            'zConnected || baseAxis.equals("z")'):
        if required_token not in trellis_semantics_source:
            raise AssertionError(
                "Trellis connection reduction must preserve CE's native placement axis; "
                f"missing {required_token}")
    trellis_semantics_test = (
        ROOT / "src/paperTest/java/com/github/ysbbbbbb/kaleidoscopetavern/"
        "paper/game/block/TrellisConnectionSemanticsTest.java"
    ).read_text(encoding="utf-8-sig")
    if "verticalPlacementNeverCollapsesIntoAHorizontalShape" not in trellis_semantics_test:
        raise AssertionError(
            "Trellis vertical placement needs a regression test for immediate neighbour updates")
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
    item_behavior_source = (
        ROOT / "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/"
        "item/behavior/SneakPlaceDrinkItemBehavior.java"
    ).read_text(encoding="utf-8-sig")
    plugin_source = (
        ROOT / "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/"
        "KaleidoscopeTavernPlugin.java"
    ).read_text(encoding="utf-8-sig")
    runtime_counts = {
        "ITEMS": len(items) + len(render_items),
        "BLOCKS": len(blocks),
        "FURNITURE": len(furniture),
    }
    for content_type, expected_count in runtime_counts.items():
        declaration = (
            f"private static final int EXPECTED_{content_type} = {expected_count};")
        if declaration not in plugin_source:
            raise AssertionError(
                "KaleidoscopeTavernPlugin runtime content count is out of sync with "
                f"the generated pack: expected {declaration}")
    item_source = (
        ROOT / "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/"
        "item/ItemService.java"
    ).read_text(encoding="utf-8-sig")
    if "items.warmCocktailFurnitureSerialization()" not in plugin_source:
        raise AssertionError(
            "Cocktail furniture item serialization must be warmed during plugin startup")
    for required_token in (
            "warmCocktailFurnitureSerialization()",
            "catalog.cocktailItems()",
            "BukkitAdaptor.adapt(built.get()).copyWithCount(1).toBytes()"):
        if required_token not in item_source:
            raise AssertionError(
                "Cocktail furniture placement must keep its item Codec cold-path warmup; "
                f"missing token: {required_token}")
    for required_token in (
            "private final Map<String, Optional<ItemStack>> visualItemPrototypes",
            "public Optional<ItemStack> buildVisual(String id)",
            "prototype = buildBase(id, null)",
            "Optional.of(prototype.get().clone())",
            "public void clearVisualCache()"):
        if required_token not in item_source:
            raise AssertionError(
                "Static render helpers must use cloned cached prototypes without rebuilding "
                f"gameplay lore/PDC; missing token: {required_token}")
    visual_build_path = item_source.partition(
        "public Optional<ItemStack> buildVisual(String id)")[2].partition(
        "public void clearVisualCache()")[0]
    if "refreshLore(" in visual_build_path:
        raise AssertionError(
            "Static render-helper construction must not enter gameplay lore/PDC repair")
    if "items.clearVisualCache()" not in plugin_source:
        raise AssertionError(
            "CraftEngine reloads must invalidate cached static render-item prototypes")
    drink_lore_source = (
        ROOT / "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/"
        "item/DrinkLore.java"
    ).read_text(encoding="utf-8-sig")
    managed_lore_source = (
        ROOT / "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/"
        "item/ManagedLoreSemantics.java"
    ).read_text(encoding="utf-8-sig")
    effect_service_source = (game_package / "EffectService.java").read_text(
        encoding="utf-8-sig")
    effect_semantics_source = (game_package / "EffectSemantics.java").read_text(
        encoding="utf-8-sig")
    stale_item_migration_tokens = {
        "ItemService": (
            "repairLegacyDrinkMetadata", "refreshInventory(", "InventoryOpenEvent",
            "EntityPickupItemEvent", "knownEffectKeys"),
        "DrinkLore": ("isManagedOrLegacy",),
        "ManagedLoreSemantics": ("isLegacyShakerLine",),
        "KaleidoscopeTavernPlugin": ("registerEvents(items, this)",),
        "EffectService": ("items.refreshInventory",),
    }
    for owner, source in (
            ("ItemService", item_source),
            ("DrinkLore", drink_lore_source),
            ("ManagedLoreSemantics", managed_lore_source),
            ("KaleidoscopeTavernPlugin", plugin_source),
            ("EffectService", effect_service_source)):
        for stale_token in stale_item_migration_tokens[owner]:
            if stale_token in source:
                raise AssertionError(
                    "New CE item definitions own drink names, potion metadata and static lore; "
                    f"{owner} must not restore legacy inventory migration token {stale_token}")
    if ("bottle-placement.drinks" in bottle_placement_source
            or re.search(r"(?m)^\s+drinks:\s*", plugin_config)):
        raise AssertionError(
            "Custom DrinkBlockItem/CocktailBlockItem placement must remain unconditional")
    for token in (
            "SneakPlaceDrinkItemBehavior.register()",
            "FurnitureItemBehavior.FACTORY.create"):
        if token not in plugin_source + item_behavior_source:
            raise AssertionError("Vessel placement behavior must delegate to native CE furniture placement")
    for token in (
            "if (!context.isSecondaryUseActive())",
            "return InteractionResult.PASS;",
            'section.getBoolean("sync_active_use", false)',
            "bukkitPlayer.startUsingItem(equipmentSlot(hand));",
            "InteractionHand.OFF_HAND",
            "Direction.UP",
            "return InteractionResult.SUCCESS_AND_CANCEL;"):
        if token not in item_behavior_source:
            raise AssertionError(
                "Vessel CE behavior must synchronize active use, preserve normal item use "
                "and own rejected sneak placement")
    for token in (
            "event.getAction() != Action.RIGHT_CLICK_BLOCK",
            "!event.getPlayer().isSneaking()"):
        if token not in bottle_placement_source:
            raise AssertionError(
                "Vanilla-source bottle furniture placement must require sneak + right-click block")
    if "new Placement(customId" in bottle_placement_source:
        raise AssertionError("Paper must not duplicate custom drink player placement")
    for redundant_owner in (
            game_package / "BottlePlacementService.java",
            game_package / "TapService.java",
            game_package / "StationService.java"):
        if 'items("bottle_items", List.of(source))' in redundant_owner.read_text(
                encoding="utf-8-sig"):
            raise AssertionError(
                f"{redundant_owner.name}: a single bottle must use CE sourceItem, not duplicate state")
    if "onPlace(FurniturePlaceEvent" in bottle_furniture_source:
        raise AssertionError(
            "Bottle furniture placement must not copy CE sourceItem into duplicate custom state")
    for token in (
            "BottleFurnitureBehavior.register()",
            "bottleFurniture.start()",
            "bottleFurniture.stop()"):
        if token not in plugin_source:
            raise AssertionError(
                f"Bottle interactions must be owned by the CE furniture lifecycle: missing {token}")
    for token in (
            "BottleFurnitureBehavior.bind(interactionHandler)",
            "BottleFurnitureBehavior.unbind(interactionHandler)",
            "private InteractionResult interact(",
            "context.getHand()",
            "InteractionResult.SUCCESS_AND_CANCEL"):
        if token not in bottle_furniture_source:
            raise AssertionError(
                f"Bottle pickup/stacking must run through its CE controller: missing {token}")
    for stale_token in (
            "FurnitureInteractEvent", "public void onInteract("):
        if stale_token in bottle_furniture_source:
            raise AssertionError(
                "Bottle pickup/stacking must not retain a global Paper furniture listener: "
                f"found {stale_token}")
    for token in (
            "extends FurnitureBehaviorTemplate",
            "FurnitureBehaviors.register(Key.of(TYPE)",
            "public InteractionResult useOnFurniture",
            "current.interact(bukkitFurniture, context)"):
        if token not in bottle_behavior_source:
            raise AssertionError(
                f"Bottle CE furniture behavior is incomplete: missing {token}")
    for forbidden_token in (
            "org.bukkit.event", "PersistentDataType", "NamespacedKey",
            "getNearbyEntities(", "runTaskTimer"):
        if forbidden_token in bottle_behavior_source:
            raise AssertionError(
                "Bottle CE behavior must remain a controller adapter without Paper polling/PDC: "
                f"found {forbidden_token}")
    for native_bottle_state_token in (
            "Item source = furniture.sourceItem()",
            "if (maxBottleCount(furniture) > 1)",
            "BottleFurnitureSemantics.needsExpandedItemState(stored.size())",
            '? stored : List.of()',
            "new FurnitureState(event.furniture())",
            '.items("bottle_items")'):
        if native_bottle_state_token not in bottle_furniture_source:
            raise AssertionError(
                "Single bottles must bypass custom state; only stacked bottles may store a list")
    if "List<ItemStack> stored = storedItems(event.furniture());" in bottle_furniture_source:
        raise AssertionError(
            "Single-bottle breaks must use CE furniture_item loot instead of a manual duplicate drop")
    station_source = (game_package / "StationService.java").read_text(
        encoding="utf-8-sig")
    station_interaction_behavior_source = (
        game_package / "furniture/StationInteractionFurnitureBehavior.java"
    ).read_text(encoding="utf-8-sig")
    if ('state.items("shaker_ingredients"' in station_source
            or 'state.item("shaker_result"' in station_source
            or "loadPortableShaker" in station_source):
        raise AssertionError(
            "Placed shaker contents must not be duplicated outside CE sourceItem")
    for shaker_source_token in (
            "Item source = furniture.sourceItem()",
            "items.shakerIngredients(shaker)",
            "items.shakerResult(shaker)",
            "private void updateShakerSource",
            "furniture.setSourceItem(BukkitAdaptor.adapt(shaker))",
            "furniture.setUnsaved()"):
        if shaker_source_token not in station_source:
            raise AssertionError(
                f"Placed shaker CE sourceItem lifecycle is missing {shaker_source_token}")

    display_storage_source = (game_package / "DisplayStorageService.java").read_text(
        encoding="utf-8-sig")
    if "items.buildVisual(prefix + storedId.substring(PREFIX.length()))" not in display_storage_source:
        raise AssertionError(
            "Storage display-model lookup must bypass repeated gameplay lore/PDC rebuilding")
    if ("controller.get(DisplayItemFurnitureController.class, slot)"
            in display_storage_source):
        raise AssertionError(
            "CE furniture controller indexes are absolute behavior indexes, not storage slots")
    for display_controller_token in (
            "private static DisplayItemFurnitureController displayController",
            "furniture.config.behaviors().size()",
            "DisplayItemFurnitureController.class, index",
            "ordinal++ == slot"):
        if display_controller_token not in display_storage_source:
            raise AssertionError(
                "Storage must resolve CE display controllers by behavior ordinal")

    board_text_source = (game_package / "BoardTextService.java").read_text(
        encoding="utf-8-sig")
    chalkboard_behavior_source = (
        game_package / "block/ChalkboardBlockBehavior.java"
    ).read_text(encoding="utf-8-sig")
    if ("runTaskTimer" in board_text_source
            or "onMove(PlayerMoveEvent event)" not in board_text_source
            or "validateEditDistance(event.getPlayer())" not in board_text_source):
        raise AssertionError(
            "Board edit distance must be event-driven, not a global per-tick player scan")
    for required_token in (
            "private final class EditSessionListener implements Listener",
            "registerEvents(editSessionListener, plugin)",
            "HandlerList.unregisterAll(editSessionListener)",
            "private void ensureEditSessionListener()",
            "private void stopEditSessionListenerIfIdle()"):
        if required_token not in board_text_source:
            raise AssertionError(
                "Board chat, quit and movement checks must be registered only while an edit "
                "session exists; "
                f"missing token: {required_token}")
    board_start = board_text_source.partition("public void start() {")[2].partition(
        "public void stop() {")[0]
    if "editSessionListener" in board_start:
        raise AssertionError(
            "BoardTextService.start must not register idle edit-session listeners")
    for required_token in (
            "BoardTextFurnitureBehavior.bind(boardVisualHandler)",
            "BoardTextFurnitureBehavior.unbind(boardVisualHandler)",
            "BoardTextFurnitureBehavior.bindInteraction(boardInteractionHandler)",
            "BoardTextFurnitureBehavior.unbindInteraction(boardInteractionHandler)",
            "private InteractionResult interactBoard(",
            "public void onFurniturePlace(FurniturePlaceEvent event)",
            "context.getHand() != InteractionHand.MAIN_HAND",
            "InteractionResult.SUCCESS_AND_CANCEL",
            "private List<BoardTextFurnitureBehavior.Visual> boardVisuals",
            "BoardTextFurnitureBehavior.refresh(furniture)",
            "LifecycleFurnitureBehavior.Channel.BOARD, lifecycleHandler"):
        if required_token not in board_text_source:
            raise AssertionError(
                "Board text must use CE packet-only elements while retaining its spatial "
                f"lifecycle index; missing token: {required_token}")
    for required_token in (
            "ChalkboardBlockBehavior.bind(chalkboardHandler)",
            "ChalkboardBlockBehavior.unbind(chalkboardHandler)",
            "private InteractionResult interactChalkboard(",
            "private List<ChalkboardBlockBehavior.Visual> chalkboardVisuals",
            "EditSession.chalkboard(",
            "private void cancelChalkboardEditors(",
            "matchesChalkboard(world, pos)"):
        if required_token not in board_text_source:
            raise AssertionError(
                "Chalkboard block text lifecycle is incomplete; "
                f"missing token: {required_token}")
    for required_token in (
            "extends BukkitBlockBehavior",
            "implements EntityBlock",
            "private void tryMerge(",
            "resetMergedData(world,",
            "private BlockPos rootPos(",
            "double_high_block behavior",
            "blockEntity.world.blockEntityChanged(blockEntity.pos)",
            "current.unavailable(this)",
            "new WeakHashMap<>()"):
        if required_token not in chalkboard_behavior_source:
            raise AssertionError(
                "Chalkboard must keep only its source-specific horizontal merge and "
                f"block-entity text bridge; missing token: {required_token}")
    for stale_token in (
            "extends WaterloggedBlockBehavior", "FluidStateProxy",
            "FluidsProxy", "LevelAccessorProxy.INSTANCE.scheduleTick$1(",
            "public Object updateShape("):
        if stale_token in chalkboard_behavior_source:
            raise AssertionError(
                "Chalkboard waterlogging must be owned by CE's automatically attached "
                f"WaterloggedBlockBehavior; stale Java token: {stale_token}")
    chalkboard_placement_source = chalkboard_behavior_source.partition(
        "public ImmutableBlockState updateStateForPlacement(")[2].partition(
        "public InteractionResult useOnBlock(")[0]
    for configured_default_token in ("positionProperty", "halfProperty", "waterloggedProperty"):
        if configured_default_token in chalkboard_placement_source:
            raise AssertionError(
                "Chalkboard placement Java may bridge clicked-face orientation only; CE config "
                f"must own pair/default/waterlogging, found {configured_default_token}")
    for stale_token in (
            "tryMergeChalkboards(", "nearbyFurniture(",
            "chalkboardMergeOrigin(", 'currentVariant().name() + "_large"'):
        if stale_token in board_text_source:
            raise AssertionError(
                "Legacy furniture-scanning chalkboard merge must stay removed; "
                f"found {stale_token}")
    for stale_token in (
            "org.bukkit.entity.TextDisplay", "PersistentDataType", "NamespacedKey",
            "board_owner", "board_line", "board_displays", "getNearbyEntities(",
            "removeDisplay(", "TextDisplay.TextAlignment", "FurnitureInteractEvent",
            "public void onFurnitureInteract(", "BoardTextFurnitureBehavior.bindPlacement(",
            "BoardTextFurnitureBehavior.unbindPlacement("):
        if stale_token in board_text_source:
            raise AssertionError(
                "Board text must not recreate persistent Bukkit display entities or helper "
                f"PDC; stale token found: {stale_token}")

    expected_grid_blocks = {
        f"{NAMESPACE}:chalkboard",
        f"{NAMESPACE}:wild_grapevine",
        f"{NAMESPACE}:wild_grapevine_plant",
        f"{NAMESPACE}:trellis",
        f"{NAMESPACE}:grapevine_trellis",
        f"{NAMESPACE}:ice_grapevine_trellis",
        f"{NAMESPACE}:gold_grapevine_trellis",
        f"{NAMESPACE}:grape_crop",
        f"{NAMESPACE}:ice_grape_crop",
        f"{NAMESPACE}:gold_grape_crop",
        f"{NAMESPACE}:tap",
        f"{NAMESPACE}:pressing_tub",
        *(f"{NAMESPACE}:_crop/{crop}/stage_{point}"
          for crop in ("grape_crop", "ice_grape_crop", "gold_grape_crop")
          for point in range(1, 6)),
        *(f"{NAMESPACE}:{incense}" for incense in INCENSE_BLOCK_SPECS),
        *(f"{NAMESPACE}:{storage}" for storage in STORAGE_BLOCK_SPECS),
        *(f"{NAMESPACE}:{connected}" for connected in CONNECTED_GRID_BLOCKS),
        SHARED_SOFA_ID,
        *(f"{NAMESPACE}:{sofa}" for sofa in SOFA_BLOCKS),
    }
    if set(blocks) != expected_grid_blocks:
        unexpected = sorted(set(blocks) - expected_grid_blocks)
        missing = sorted(expected_grid_blocks - set(blocks))
        raise AssertionError(f"Grid/furniture classification drift: unexpected={unexpected}, missing={missing}")
    # These are real CE blocks. Their same-id furniture definitions are
    # intentionally migration-only for one release and are unreachable from
    # items/loot. No other placeable may exist in both registries.
    migration_overlap = {
        f"{NAMESPACE}:{block_id}" for block_id in MIGRATION_BLOCK_FURNITURE
    }
    unexpected_overlap = (set(blocks) & set(furniture)) - migration_overlap
    if unexpected_overlap:
        raise AssertionError(
            f"Unexpected block/furniture overlap: {sorted(unexpected_overlap)}")

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
    private_furniture = {WALL_PRESSING_TUB_ID}
    private_blocks = {SHARED_SOFA_ID}
    represented_placeables = (
        (set(blocks) - derived_crop_stages - private_blocks)
        | (set(furniture) - private_furniture)
    )
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
    grapevine_item_behavior_source = (
        ROOT / "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/item/behavior/"
        "GrapevineItemBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for evidence in (
            "String planted = grapevineFor(soil);",
            'replacement, "axis", stringProperty(trellisState, "axis")',
            'withNamed(replacement, "type", stringProperty(trellisState, "type"))',
            "void plantGrapevineOnTrellis(",
            "useGrapevineOnBlock"):
        if evidence not in block_service_source:
            raise AssertionError(f"BlockService grapevine planting evidence is missing: {evidence}")
    for evidence in (
            'TYPE = Key.of("kaleidoscope_tavern", "grapevine_item")',
            "ItemBehaviors.register(TYPE",
            "current.useOnBlock(context)"):
        if evidence not in grapevine_item_behavior_source:
            raise AssertionError(
                f"CE grapevine item behavior evidence is missing: {evidence}")
    for evidence in (
            "GrapevineItemBehavior.register()",
            "blocks.start()",
            "blocks.stop()"):
        if evidence not in plugin_source:
            raise AssertionError(
                f"CE grapevine item lifecycle evidence is missing: {evidence}")
    if "PlayerInteractEvent" in block_service_source or "onRightClickWithGrapevine" in block_service_source:
        raise AssertionError(
            "Grapevine planting must not retain a global Bukkit PlayerInteractEvent listener")
    grapevine_behaviors = items[f"{NAMESPACE}:grapevine"].get("behaviors", [])
    if [behavior.get("type") for behavior in grapevine_behaviors] != [
            f"{NAMESPACE}:grapevine_item", "block_item", "compostable_item"]:
        raise AssertionError(
            "Grapevine must run its CE trellis interaction before wild placement and composting")
    if grapevine_behaviors[1].get("block") != f"{NAMESPACE}:wild_grapevine":
        raise AssertionError("Grapevine's CE block-item fallback must place wild_grapevine")
    if '!"single".equals(stringProperty(trellisState, "type"))' not in block_service_source:
        raise AssertionError(
            "Grapevine planting must retain the original single-trellis restriction")
    for stale_listener in (
            "CustomBlockInteractEvent", "CustomBlockBreakEvent", "implements Listener",
            "onCustomBlockBreak", "interactVineTrellis", "damageTool(",
            "interactWildHead", "Material.HONEYCOMB", "WAX_ON", "WAX_OFF"):
        if stale_listener in block_service_source:
            raise AssertionError(
                "Trellis waxing and grapevine shearing are CE block events; "
                f"BlockService must not reintroduce {stale_listener}")
    if "registerEvents(blocks, this)" in plugin_source:
        raise AssertionError(
            "BlockService only binds a CE item behavior and must not be a global Bukkit listener")
    station_source = (game_package / "StationService.java").read_text(encoding="utf-8-sig")
    for required_token in (
            "player.hasActiveItem()",
            "player.getActiveItemHand() == usedHand",
            "player.clearActiveItem()"):
        if required_token not in station_source:
            raise AssertionError(
                "Successful station interactions must cancel predicted bucket/drink use; "
                f"missing token: {required_token}")
    storage_source = (game_package / "DisplayStorageService.java").read_text(
        encoding="utf-8-sig")
    storage_interaction_behavior_source = (
        game_package / "furniture/StorageInteractionFurnitureBehavior.java"
    ).read_text(encoding="utf-8-sig")
    ambient_source = (game_package / "AmbientFurnitureService.java").read_text(
        encoding="utf-8-sig")
    for owner_name, owner_source in (("StationService", station_source),
                                     ("AmbientFurnitureService", ambient_source)):
        for stale_state in ("incense_active", "interactIncense", "tickIncense",
                            "Channel.INCENSE"):
            if stale_state in owner_source:
                raise AssertionError(
                    f"{owner_name}: CE blocks own incense state/ticks; "
                    f"{stale_state} must stay deleted")

    plugin_source = (game_package.parent / "KaleidoscopeTavernPlugin.java").read_text(
        encoding="utf-8-sig")
    if "StateFurnitureBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register state_furniture before pack loading")
    if "LifecycleFurnitureBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register lifecycle_furniture before pack loading")
    if "PressingTubBlockBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register the CE pressing-tub block behavior before pack loading")
    if "IncenseBlockBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register the CE incense block behavior before pack loading")
    if "IncenseBlockBehavior.prewarmRuntime()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must warm Bukkit's entity-tag bridge before incense ticks")
    if "TapBlockBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register the CE tap block behavior before pack loading")
    if "StorageBlockBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register CE storage blocks before pack loading")
    if "ChalkboardBlockBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register the chalkboard block behavior before pack loading")
    for stale_token in ("SofaBlockBehavior", "SofaBlockShape"):
        if stale_token in plugin_source:
            raise AssertionError(
                f"KaleidoscopeTavernPlugin must leave sofas to native CE configuration: {stale_token}")
    incense_behavior_source = (
        game_package / "block/IncenseBlockBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "implements EntityBlock",
            "BlockBehaviors.register(TYPE, IncenseBlockBehavior::new)",
            "state.with(openProperty, powered).with(poweredProperty, powered)",
            "powered == state.get(poweredProperty)",
            "state.with(poweredProperty, powered)",
            "Controller.prewarm()",
            "implements BlockEntityTicker<Controller>",
            "return createTickerHelper(this)",
            "takeParticleDue()",
            "scheduleNextParticle(random)",
            "random.nextInt(PARTICLE_DELAY_BOUND)",
            "random.nextInt(3) == 0",
            "takeDamageDue()",
            "damageDelay = DAMAGE_INTERVAL - 1",
            "world.getNearbyLivingEntities(center, 32.5)",
            "zombieVillager.setConversionTime(60)"):
        if required_token not in incense_behavior_source:
            raise AssertionError(
                "CE incense behavior no longer preserves source interaction/tick semantics; "
                f"missing {required_token}")
    for stale_token in ("PersistentDataContainer", "BukkitTask", "runTaskTimer",
                        "public InteractionResult useOnBlock",
                        "random.nextInt(49) == 0",
                        "world.getGameTime() % 120L",
                        "center.clone().add(",
                        "Controller::tick"):
        if stale_token in incense_behavior_source:
            raise AssertionError(
                f"CE incense state/lifecycle must not be duplicated through {stale_token}")
    if "RedstoneFurnitureBehavior" in plugin_source:
        raise AssertionError(
            "Storage redstone is block-state driven; the furniture polling bridge must stay removed")
    if "TickingFurnitureBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register ticking_furniture before pack loading")
    for lifecycle_call in ("TickingFurnitureBehavior.start(this)",
                           "TickingFurnitureBehavior.stop()"):
        if lifecycle_call not in plugin_source:
            raise AssertionError(
                "KaleidoscopeTavernPlugin must manage the due-time furniture scheduler; "
                f"missing {lifecycle_call}")
    if "StorageVisualFurnitureBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register storage_visual_furniture before pack loading")
    if "StorageInteractionFurnitureBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register storage_interaction_furniture before pack loading")
    if "StationVisualFurnitureBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register station_visual_furniture before pack loading")
    if "StationInteractionFurnitureBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register station_interaction_furniture before pack loading")
    if "BoardTextFurnitureBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register board_text_furniture before pack loading")
    if "AnimatedItemFurnitureBehavior.register()" not in plugin_source:
        raise AssertionError(
            "KaleidoscopeTavernPlugin must register animated_item_furniture before pack loading")

    stale_ambient_scan_tokens = (
        "runTaskTimer", "Bukkit.getEntity", "CraftEngineFurniture",
        "FurniturePlaceEvent", "FurnitureBreakEvent", "EntitiesLoadEvent",
        "tracked", "bootstrap",
    )
    for stale_token in stale_ambient_scan_tokens:
        if stale_token in ambient_source:
            raise AssertionError(
                "CE furniture controllers own ambient furniture lifecycle; "
                f"AmbientFurnitureService must not reintroduce {stale_token}")
    stale_barrel_scan_tokens = (
        "tickBarrels", "loadedBarrels", "barrelTask", "barrelTickCounter",
        "bootstrapBarrels",
    )
    for stale_token in stale_barrel_scan_tokens:
        if stale_token in station_source:
            raise AssertionError(
                "CE furniture controllers own barrel lifecycle; "
                f"StationService must not reintroduce {stale_token}")

    furniture_state_source = (game_package / "FurnitureState.java").read_text(
        encoding="utf-8-sig")
    for stale_token in (
            "PersistentDataContainer", "PersistentDataType", "NamespacedKey", "JavaPlugin"):
        if stale_token in furniture_state_source:
            raise AssertionError(
                "Tavern business state must use CE controller CompoundTag data; "
                f"stale token found: {stale_token}")
    for required_token in ("UUID uuid(String name)", "List<UUID> uuids(String name)",
                           "NBT.createUUID(value)"):
        if required_token not in furniture_state_source:
            raise AssertionError(
                "CE furniture helper indexes must use typed NBT UUIDs; "
                f"missing token: {required_token}")
    if "List<String> strings(String name)" in furniture_state_source:
        raise AssertionError("CE furniture helper UUID indexes must not regress to strings")

    all_paper_java = "\n".join(
        path.read_text(encoding="utf-8-sig")
        for path in sorted((ROOT / "src/paper/java").rglob("*.java"))
    )
    if "new FurnitureState(plugin," in all_paper_java:
        raise AssertionError(
            "FurnitureState construction must not retain the obsolete Bukkit PDC owner")

    catalog_source = (
        game_package.parent / "catalog" / "ContentCatalog.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "barrelByIngredients.get(fluid)",
            "barrelPartialAnyFluid.contains(key)",
            "shakerByIngredients.get(IngredientKey.of(ingredients))",
            "shakerPartial.contains(IngredientKey.of(proposed))"):
        if required_token not in catalog_source:
            raise AssertionError(
                "Custom station recipes must use immutable precomputed indexes; "
                f"missing token: {required_token}")
    for stale_token in ("barrelRecipes.stream()", "shakerRecipes.stream()"):
        if stale_token in catalog_source:
            raise AssertionError(
                "Runtime station recipe lookup must not linearly scan recipe lists; "
                f"stale token found: {stale_token}")

    state_behavior_source = (
        game_package / "furniture" / "StateFurnitureBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "loadCustomData(CompoundTag data)",
            "saveCustomData(CompoundTag data)",
            "bukkitFurniture.setUnsaved()"):
        if required_token not in state_behavior_source:
            raise AssertionError(
                "state_furniture must persist through CE's dirty custom-data lifecycle; "
                f"missing token: {required_token}")

    pressing_behavior_source = (
        game_package / "block" / "PressingTubBlockBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "implements EntityBlock, PrioritizedFallOnHandler",
            "BlockBehaviors.register(TYPE, PressingTubBlockBehavior::new)",
            "Controller.prewarm()",
            "implements DifferentialItemDisplayElement.VisualProvider",
            "new DifferentialItemDisplayElement(\n                    this, MAX_ELEMENTS, VIEW_RANGE)",
            "public List<DisplayVisual> visuals(int limit)",
            "super.fallOn(thisBlock, args)",
            "LivingEntityProxy.CLASS.isInstance(args[3])",
            "((Number) args[4]).doubleValue()",
            "BlockBehaviorFactory.getProperty(",
            'block, "facing", Direction.class',
            "public static void bind(Handler value)",
            "public static void unbind(Handler value)",
            "public Object playerWillDestroy(",
            "public void onRemove()",
            "suppressContentDrops()"):
        if required_token not in pressing_behavior_source:
            raise AssertionError(
                "pressing_tub Java behavior must keep only the CE API gaps "
                f"(fallOn, business state, comparator and drops); missing {required_token}")
    for configured_placement_token in (
            "updateStateForPlacement(", "BlockPlaceContext",
            "waterloggedProperty", "clickedFace.axis()"):
        if configured_placement_token in pressing_behavior_source:
            raise AssertionError(
                "Pressing-tub placement/facing/waterlogging must remain CE-configured; "
                f"stale Java token: {configured_placement_token}")
    if "limit -> {" in pressing_behavior_source:
        raise AssertionError(
            "Pressing-tub controllers must implement VisualProvider directly; "
            "a captured constructor lambda would restore cold call-site linking")

    differential_display_source = (
        game_package / "visual" / "DifferentialItemDisplayElement.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "public static void prewarm()",
            "EmptyVisualProvider.INSTANCE",
            "new DifferentialItemDisplayElement("):
        if required_token not in differential_display_source:
            raise AssertionError(
                "Differential item displays must be initialized before the first "
                f"live block entity; missing token: {required_token}")

    # 冗余的 updateEntityMovementAfterFallOn override 已按 P7 删除，父类
    # BukkitBlockBehavior 提供实现；热路径截止到下一个方法 useOnBlock。
    fall_on_hot_path = pressing_behavior_source.partition(
        "public void fallOn(Object thisBlock, Object[] args) {")[2].partition(
        "public InteractionResult useOnBlock(")[0]
    for stale_token in ("getBukkitEntity", "PressingTubSemantics",
                        "hasPotentialBelow", "findBelow", "pressLandingTracker",
                        "EntityMoveEvent", "PlayerMoveEvent", "onFallDamage"):
        if stale_token in fall_on_hot_path:
            raise AssertionError(
                "pressing_tub fallOn must stay proxy-level without Bukkit entity "
                f"or landing-index machinery; stale token: {stale_token}")

    migration_behavior_source = (
        game_package / "furniture" / "LegacyPressingTubMigrationFurnitureBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "FurnitureBehaviors.register(",
            "public void onLoad()",
            "public void onPlace(Player player)",
            "public void onUnload(boolean isStopping)",
            "Bukkit.getScheduler().runTask(owner,",
            "migrateGround(origin, oldState)",
            "migrateWall(origin, oldState)",
            "CraftEngineBlocks.place(",
            "PressingTubBlockBehavior.BLOCK_ID",
            "PressingTubBlockBehavior.findController(",
            "PressingTubService.WALL_FURNITURE_ID",
            "CraftEngineFurniture.place(",
            "StateFurnitureBehavior.state(replacement)",
            "writeState(targetController, oldState)",
            "CraftEngineFurniture.remove(furniture, false, false)",
            "sameState(current, oldState)",
            "isEmpty(current)",
            "CONFLICTS.incrementAndGet()",
            "press_item", "press_count", "press_fluid", "press_amount",
            "facingFromYaw(origin.getYaw())",
            "LOADED_TUBS.incrementAndGet()",
            "MIGRATED.incrementAndGet()"):
        if required_token not in migration_behavior_source:
            raise AssertionError(
                "legacy_pressing_tub_migration must split old ground/wall furniture "
                f"into the configured targets; missing token: {required_token}")
    for stale_token in ("setVariant(", "onPlayerHit(",
                        "useOnFurniture(", "gatherElements(", "gatherHitboxes(",
                        'properties.putBoolean("tilt"'):
        if stale_token in migration_behavior_source:
            raise AssertionError(
                "Legacy pressing-tub migration must not restore placement/visual "
                f"behavior or the removed tilt block state; stale token: {stale_token}")

    lifecycle_behavior_source = (
        game_package / "furniture" / "LifecycleFurnitureBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "public void onPlace(Player player)",
            "public void onLoad()",
            "public void preRemove(Player player)",
            "public void postRemove(Player player)",
            "public void onUnload(boolean isStopping)",
            "default void onReady(BukkitFurniture furniture, ReadyReason reason, Player placingPlayer)",
            "ready(ReadyReason.PLACE, player)",
            "handler.onReady(bukkitFurniture, readyReason, placingPlayer)",
            "currentHandler.onUnavailable(bukkitFurniture, removed, stopping)",
            "public static List<BukkitFurniture> nearby(",
            "public static Optional<BukkitFurniture> atBlock(",
            "FurnitureSpatialSemantics.minimumColumn(",
            "FurnitureSpatialSemantics.insideBox("):
        if required_token not in lifecycle_behavior_source:
            raise AssertionError(
                "lifecycle_furniture must route CE readiness and unavailability; "
                f"missing token: {required_token}")
    for hot_path_token in ("furniture.isValid()", "ConcurrentHashMap", "ConcurrentMap"):
        if hot_path_token in lifecycle_behavior_source:
            raise AssertionError(
                "CE lifecycle must keep furniture spatial queries free of redundant "
                f"validity/concurrent-map overhead; found {hot_path_token}")

    board_text_behavior_source = (
        game_package / "furniture" / "BoardTextFurnitureBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "implements FurnitureElement",
            "EntityTypesProxy.TEXT_DISPLAY",
            "public static void refresh(BukkitFurniture furniture)",
            "private List<PreparedVisual> cachedVisuals = List.of()",
            "ComponentUtils.jsonToMinecraft(",
            "GsonComponentSerializer.gson().serialize(visual.text())",
            "List<PreparedVisual> current = controller.visuals()",
            "DisplayData.TextDisplayData.Text.addEntityData",
            "DisplayData.TextDisplayData.LineWidth",
            "DisplayData.TextDisplayData.BackgroundColor",
            "ClientboundAddEntityPacketProxy.INSTANCE.newInstance",
            "ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance",
            "player.sendPackets",
            "public void gatherElements",
            "public static void bindInteraction(",
            "public static void unbindInteraction(",
            "public InteractionResult useOnFurniture(",
            "current.interact(bukkitFurniture, context)"):
        if required_token not in board_text_behavior_source:
            raise AssertionError(
                "board_text_furniture must use CE-tracked packet-only text elements; "
                f"missing token: {required_token}")
    for stale_token in (
            "org.bukkit.entity.TextDisplay", "PersistentDataType", "World.spawn",
            "ComponentUtils.adventureToMinecraft", "PlacementHandler",
            "bindPlacement(", "placementHandler"):
        if stale_token in board_text_behavior_source:
            raise AssertionError(
                "board_text_furniture must never create persistent Bukkit entities; "
                f"stale token found: {stale_token}")

    lifecycle_services = {
        "BarStoolVisualService.java": "BAR_STOOL",
        "BoardTextService.java": "BOARD",
        "ShakerVisualService.java": "SHAKER",
    }
    for service_name, channel in lifecycle_services.items():
        source = (game_package / service_name).read_text(encoding="utf-8-sig")
        required_bind = f"LifecycleFurnitureBehavior.Channel.{channel}, lifecycleHandler"
        if required_bind not in source:
            raise AssertionError(
                f"{service_name} must bind its exact CE lifecycle channel")
        for stale_token in ("Bukkit.getWorlds()", "private void bootstrap(",
                            "private void bootstrapDisplays(", "FurniturePlaceEvent event"):
            if stale_token in source and not (
                    service_name == "BoardTextService.java"
                    and stale_token == "FurniturePlaceEvent event"):
                raise AssertionError(
                    f"{service_name} retained replaced lifecycle scan/event: {stale_token}")
        if "EntitiesLoadEvent" in source:
            raise AssertionError(
                f"{service_name} must let CE deliver furniture load callbacks")
        if "EntitiesUnloadEvent" in source:
            raise AssertionError(
                f"{service_name} must let CE deliver furniture unload callbacks")

    # Bar stools still need a small-radius CE lifecycle index lookup. Connected
    # sofas, tables, counters and cabinets are real CE blocks and therefore do
    # not participate in any furniture lifecycle index.
    bar_stool_source = (
        game_package / "BarStoolVisualService.java"
    ).read_text(encoding="utf-8-sig")
    if "Channel.BAR_STOOL, mount, 1.5, 1.5" not in bar_stool_source:
        raise AssertionError(
            "BarStoolVisualService must query its CE lifecycle spatial index")
    if (game_package / "FurnitureConnectionService.java").exists():
        raise AssertionError(
            "FurnitureConnectionService must stay deleted after connection owners "
            "moved to CE block updateShape")

    connected_block_source = (
        game_package / "block" / "ConnectedBlockBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "extends BukkitBlockBehavior",
            "BlockBehaviors.register(TYPE, ConnectedBlockBehavior::new)",
            'CornerConfig.parse(section.getNonNullSection("topology"))',
            'LinearConfig.parse(section.getNonNullSection("topology"))',
            'TableConfig.parse(section.getNonNullSection("topology"))',
            'section.getString("state_property"',
            'section.getString("axis_property"',
            "private ImmutableBlockState updateCorner(",
            "private ImmutableBlockState updateLinear(",
            "private ImmutableBlockState updateTable(",
            "BlockGetterProxy.INSTANCE.getBlockState("):
        if required_token not in connected_block_source:
            raise AssertionError(
                "ConnectedBlockBehavior must be one generic config-driven neighbour adapter; "
                f"missing token: {required_token}")
    for forbidden_family_token in (
            '"single"', '"middle"', '"left_corner"', '"right_corner"',
            '"bar_counter"', '"bar_cabinet"', '"cellar_cabinet"'):
        if forbidden_family_token in connected_block_source:
            raise AssertionError(
                "Connected family output names must live in CE configuration, not Java; "
                f"found {forbidden_family_token}")
    for stale_token in (
            "PlayerMoveEvent", "EntityMoveEvent", "Bukkit.getScheduler",
            "runTask", "ConcurrentHashMap", "BukkitFurniture",
            "FurnitureElement", "SeatBlockEntity", "CraftEngineFurniture",
            "waterloggedProperty", "FluidStateProxy", "FluidsProxy",
            "getFluidState("):
        if stale_token in connected_block_source:
            raise AssertionError(
                "ConnectedBlockBehavior must not duplicate CE lifecycle/static config; "
                f"stale token: {stale_token}")
    for required_token in (
            "ImmutableBlockState resolveCornerState(",
            "SofaBlockIds.isLegacy(",
            "return corner.none;"):
        if required_token not in connected_block_source:
            raise AssertionError(
                "Shared-sofa migration must connect compact legacy aliases while "
                f"they are being converted; missing token: {required_token}")

    sofa_tint_source = (
        game_package / "block" / "SofaTintSupport.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "TintSourceBlockEntityController.class",
            "controller.setSourceItem(source)",
            "world.blockEntityChanged(pos)",
            "clearSourceItem(world, pos)"):
        if required_token not in sofa_tint_source:
            raise AssertionError(
                "Shared sofa must delegate exact colour/source persistence to CE's "
                f"native tint-source controller; missing token: {required_token}")
    for stale_token in (
            "PersistentDataContainer", "ItemDisplay", "spawnEntity",
            "FurnitureConnectionService"):
        if stale_token in sofa_tint_source:
            raise AssertionError(
                "SofaTintSupport must remain a migration-only CE-native adapter; "
                f"stale token: {stale_token}")

    sofa_block_migration_source = (
        game_package / "block" / "LegacySofaBlockMigrationService.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "CELLS_PER_TICK = 8_192",
            "MIGRATIONS_PER_TICK = 32",
            "statesContainer().hasAny(",
            "SofaTintSupport.placeShared(",
            "SofaBlockIds.isLegacy("):
        if required_token not in sofa_block_migration_source:
            raise AssertionError(
                "Old colour-specific CE block ids need a bounded, palette-filtered "
                f"one-release migration; missing token: {required_token}")

    migration_source = (
        game_package / "furniture" /
        "LegacyConnectedBlockMigrationFurnitureBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "FurnitureBehaviors.register(",
            "CraftEngineBlocks.place(",
            "CraftEngineFurniture.remove(furniture, false, false)",
            "LegacyConnectedBlockMigrationSemantics.tableProperties(",
            "MIGRATED.incrementAndGet()",
            "CONFLICTS.incrementAndGet()",
            "FAILURES.incrementAndGet()"):
        if required_token not in migration_source:
            raise AssertionError(
                "Legacy connected furniture must migrate non-destructively into CE blocks; "
                f"missing token: {required_token}")
    migration_semantics_source = (
        game_package / "furniture" /
        "LegacyConnectedBlockMigrationSemantics.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "final class LegacyConnectedBlockMigrationSemantics",
            "static TableProperties tableProperties(",
            "record TableProperties(String axis, int position)"):
        if required_token not in migration_semantics_source:
            raise AssertionError(
                "Legacy connected migration variant decoding must remain a pure, "
                f"runtime-independent helper; missing token: {required_token}")
    for stale_token in ("org.bukkit", "BukkitFurniture", "CraftEngineBlocks",
                        "FurnitureBehaviorTemplate"):
        if stale_token in migration_semantics_source:
            raise AssertionError(
                "Legacy connected migration semantics must not bootstrap Bukkit/CE "
                f"runtime classes; stale token: {stale_token}")

    for stale_token in (
            "useOnFurniture(", "gatherElements(", "gatherHitboxes(",
            "setVariant(", "onPlayerHit("):
        if stale_token in migration_source:
            raise AssertionError(
                "Migration-only furniture must not restore runtime furniture behavior; "
                f"stale token: {stale_token}")

    trellis_shape_source = (
        game_package / "block" / "TrellisBlockShape.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "implements BlockShape",
            "definition.defaultState()",
            "getOptionalCustomBlockState(minecraftState)",
            "Property.formatValue(",
            "COLLISION_SHAPES.computeIfAbsent(",
            "SELECTION_SHAPES.computeIfAbsent(",
            "public Object getSupportShape"):
        if required_token not in trellis_shape_source:
            raise AssertionError(
                "CE trellis delegates must resolve the current shared block state; "
                f"missing token: {required_token}")

    animated_visual_source = (
        game_package / "furniture" / "AnimatedItemFurnitureBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "implements FurnitureElement",
            "EntityTypesProxy.ITEM_DISPLAY",
            "private static final Map<UUID, Controller> LOADED = new HashMap<>()",
            "private List<Visual> cachedVisuals = List.of()",
            "return controller.visuals()",
            "DisplayData.ItemDisplayData.ItemStack.addEntityData",
            "DisplayData.Translation.addEntityData",
            "DisplayData.LeftRotation.addEntityData",
            "EntityUtils.createUpdatePosPacket",
            "furniture.trackedBy()",
            "public static void updateTransforms",
            "public static void updatePosition"):
        if required_token not in animated_visual_source:
            raise AssertionError(
                "animated_item_furniture must use CE tracking and transform-only packets; "
                f"missing token: {required_token}")
    for stale_token in (
            "org.bukkit.entity.ItemDisplay", "PersistentDataType", "World.spawn",
            "ConcurrentHashMap", "ConcurrentMap", "synchronized (HANDLERS)"):
        if stale_token in animated_visual_source:
            raise AssertionError(
                "animated_item_furniture must never create persistent Bukkit entities; "
                f"stale token found: {stale_token}")
    for service_name in ("ShakerVisualService.java", "BarStoolVisualService.java"):
        source = (game_package / service_name).read_text(encoding="utf-8-sig")
        for required_token in (
                "AnimatedItemFurnitureBehavior.bind(",
                "AnimatedItemFurnitureBehavior.unbind("):
            if required_token not in source:
                raise AssertionError(
                    f"{service_name} must feed its CE animated visual controller")
        for stale_token in (
                "org.bukkit.entity.ItemDisplay", "PersistentDataType", "NamespacedKey",
                "getNearbyEntities(", "Bukkit.getEntity(owner)",
                "shaker_visual_owner", "shaker_visual_role",
                "bar_stool_body_owner", "shaker_base_visual",
                "shaker_lid_visual", "bar_stool_body_visual"):
            if stale_token in source:
                raise AssertionError(
                    "Animated furniture visuals must not retain Bukkit helper entities or "
                    f"recovery state; {service_name} contains {stale_token}")
    shaker_visual_service_source = (
        game_package / "ShakerVisualService.java"
    ).read_text(encoding="utf-8-sig")
    if ("implements Listener" in shaker_visual_service_source
            or "registerEvents(shakerVisuals, this)" in plugin_source):
        raise AssertionError(
            "Shaker visuals are CE lifecycle-driven and must not retain an empty Bukkit listener")
    shaker_hud_source = (
        game_package / "ShakerHudSemantics.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            'FONT_KEY = "kaleidoscope_tavern:shaker_hud"',
            "BAR_GLYPH = '\\uE400'",
            "POINTER_GLYPH = '\\uE401'",
            "INGREDIENT_GLYPH = '\\uE402'",
            "BAR_ADVANCE_PIXELS = 182",
            "Math.round(Math.max(0, ticks) * 1.5F)",
            "static Component ingredientSubtitle(List<Integer> colors)"):
        if required_token not in shaker_hud_source:
            raise AssertionError(
                "Shaker HUD must retain the archived overlay geometry and tintable markers; "
                f"missing token: {required_token}")
    for required_token in (
            "player.getTargetEntity(5)",
            "CraftEngineFurniture.getLoadedFurnitureByCollider(target)",
            "CraftEngineFurniture.getLoadedFurnitureByMetaEntity(target)",
            "items.shakerIngredients(shaker)",
            "ShakerSemantics.ingredientColor(",
            "ShakerHudSemantics.progressSubtitle(ticks)",
            "ShakerHudSemantics.ingredientSubtitle(colors)",
            "ensureIngredientHudTask()",
            "stopIngredientHudTaskIfIdle()"):
        if required_token not in shaker_visual_service_source:
            raise AssertionError(
                "Loaded shakers must drive their source-compatible progress and ingredient HUD; "
                f"missing token: {required_token}")
    bar_stool_visual_source = (
        game_package / "BarStoolVisualService.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "private final class SeatEventListener implements Listener",
            "registerEvents(seatEventListener, plugin)",
            "HandlerList.unregisterAll(seatEventListener)",
            "private void ensureSeatEventsRegistered()",
            "private void stopSeatEventsIfIdle()",
            "loaded.put(furniture.uuid(), furniture);",
            "ensureSeatEventsRegistered();",
            "loaded.remove(owner, furniture);",
            "stopSeatEventsIfIdle();"):
        if required_token not in bar_stool_visual_source:
            raise AssertionError(
                "Bar-stool mount events must follow CE loaded furniture availability; "
                f"missing token: {required_token}")
    for required_token in (
            "Map<UUID, Occupancy> occupied",
            "new Occupancy(owner, rider)",
            "LivingEntity rider = occupancy.rider()",
            "private record Occupancy(UUID owner, LivingEntity rider)"):
        if required_token not in bar_stool_visual_source:
            raise AssertionError(
                "Active bar-stool rotation must retain its short-lived rider reference; "
                f"missing token: {required_token}")
    for stale_token in ("Bukkit.getEntity(entry.getKey())", "private void activate(UUID"):
        if stale_token in bar_stool_visual_source:
            raise AssertionError(
                "Bar-stool every-tick rotation must not resolve riders through Bukkit UUID lookup; "
                f"found {stale_token}")
    if ("BarStoolVisualService implements Listener" in bar_stool_visual_source
            or "registerEvents(barStoolVisuals, this)" in plugin_source):
        raise AssertionError(
            "Bar-stool mount events must not remain globally registered without loaded stools")

    station_source = (game_package / "StationService.java").read_text(
        encoding="utf-8-sig")
    shaker_item_behavior_source = (
        ROOT / "src/paper/java/com/github/ysbbbbbb/kaleidoscopetavern/paper/item/behavior/"
        "ShakerItemBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            'TYPE = Key.of("kaleidoscope_tavern", "shaker_item")',
            "ItemBehaviors.register(TYPE",
            "InteractionResult use(World world",
            "InteractionResult useOnBlock(UseOnContext context)",
            "shouldUsePortableOnBlock(context.isSecondaryUseActive())",
            "current.use(player, hand)"):
        if required_token not in shaker_item_behavior_source:
            raise AssertionError(
                "Portable shaker right-click must use CE's existing item-use pipeline; "
                f"missing token: {required_token}")
    for required_token in (
            "ShakerItemBehavior.register()",
            "ShakerItemBehavior.bind(shakerItemHandler)",
            "ShakerItemBehavior.unbind(shakerItemHandler)",
            "private InteractionResult usePortableShaker("):
        if required_token not in plugin_source + station_source:
            raise AssertionError(
                "Portable shaker CE item lifecycle is incomplete; "
                f"missing token: {required_token}")
    if "onUsePortableShaker(PlayerInteractEvent" in station_source:
        raise AssertionError(
            "Portable shaker right-click must not retain a duplicate global Paper listener")
    shaker_behaviors = items[f"{NAMESPACE}:shaker"].get("behaviors", [])
    if ([behavior.get("type") for behavior in shaker_behaviors]
            != [f"{NAMESPACE}:shaker_item", f"{NAMESPACE}:sneak_place_drink"]
            or shaker_behaviors[1].get("furniture") != f"{NAMESPACE}:shaker"
            or shaker_behaviors[1].get("rules") != {
                "ground": {"rotation": "four", "alignment": "center"}
            }):
        raise AssertionError(
            "Shaker must keep only its portable-use callback and delegate "
            "sneak placement through the generic CE furniture adapter")
    for stale_token in (
            "bootstrapPressVisuals", "onEntitiesLoad(EntitiesLoadEvent event)",
            "pressingTubBelow", "getNearbyEntities(feet"):
        if stale_token in station_source:
            raise AssertionError(
                "CE pressing-tub lifecycle/index must replace global or nearby entity scans; "
                f"stale token found: {stale_token}")
    for required_token in (
            "ensurePortableShakerTask();",
            "stopPortableShakerTaskIfIdle();",
            "new PortableShakerUse(player, hand, 0)",
            "var iterator = portableShakers.entrySet().iterator()",
            "Player player = use.player()",
            "shakerVisuals.beginMix(player)",
            "shakerVisuals.updateMix(player, ticks)",
            "shakerVisuals.endMix(player)",
            "private record PortableShakerUse(Player player, EquipmentSlot hand, int ticks)"):
        if required_token not in station_source:
            raise AssertionError(
                "Portable shaker ticking must start on demand and stop when idle; "
                f"missing token: {required_token}")
    for stale_token in (
            "new ArrayList<>(portableShakers.keySet())",
            "Bukkit.getPlayer(playerId)"):
        if stale_token in station_source:
            raise AssertionError(
                "Portable shaker every-tick path must retain the active player reference; "
                f"found {stale_token}")
    station_start = station_source.partition("public void start() {")[2].partition(
        "public void stop() {")[0]
    if "portableShakerTask" in station_start:
        raise AssertionError(
            "StationService.start must not schedule an idle every-tick portable shaker task")
    if "fallingCleanupTask" in station_start:
        raise AssertionError(
            "StationService.start must not schedule an idle falling-entity cleanup task")
    for required_token in (
            "StationInteractionFurnitureBehavior.bind(stationInteractionHandler)",
            "StationInteractionFurnitureBehavior.unbind(stationInteractionHandler)",
            "private InteractionResult interactStation(",
            "context.getHand() != InteractionHand.MAIN_HAND",
            "Vec3d click = context.getClickLocation()",
            "InteractionResult.SUCCESS_AND_CANCEL",
            "StationVisualFurnitureBehavior.bind(stationVisualHandler)",
            "StationVisualFurnitureBehavior.unbind(stationVisualHandler)",
            "StationVisualFurnitureBehavior.refresh(furniture)",
            "public boolean shouldSchedule(BukkitFurniture furniture)",
            "return shouldTickBarrel(furniture)",
            "TickingFurnitureBehavior.refreshSchedule(",
            "private boolean shouldTickBarrel(",
            "BarrelSemantics.needsTick(false,",
            "LifecycleFurnitureBehavior.Channel.BARREL, center, 3.0, 3.0",
            'open ? "ground" : "ground_closed"',
            'currentVariant().name().equals("ground")'):
        if required_token not in station_source:
            raise AssertionError(
                "StationService must keep the barrel/shaker furniture bridge; "
                f"missing token: {required_token}")
    for stale_token in (
            "PressLandingTracker", "PressingTubFurnitureBehavior",
            "pressLandingTracker", "pressLandingListener", "onFallDamage",
            "EntityMoveEvent", "PlayerMoveEvent", "fallingCleanupTask",
            "ensureFallingCleanupTask", "stopFallingCleanupTaskIfIdle",
            "hasPotentialBelow", "hasGroundTubInWorld", "occupiesBlock(",
            "bindAvailability", "unbindAvailability", "registerEvents(",
            "HandlerList.unregisterAll"):
        if stale_token in station_source:
            raise AssertionError(
                "Movement/landing-index machinery must stay deleted from "
                f"StationService; stale token: {stale_token}")
    for stale_token in (
            "FurnitureInteractEvent", "public void onFurnitureInteract(",
            "FurniturePlaceEvent", "public void onFurniturePlace(",
            "stationPlacementHandler", "private void onStationPlaced(",
            "StationInteractionFurnitureBehavior.bindPlacement(",
            "StationInteractionFurnitureBehavior.unbindPlacement("):
        if stale_token in station_source:
            raise AssertionError(
                "StationService must not retain a global Paper furniture interaction listener; "
                f"found {stale_token}")
    # 压榨桶玩法已拆到独立服务：StationService 不再绑定 PressingTubBlockBehavior
    # 或维护压榨配方/视觉布局。
    pressing_service_source = (
        game_package / "PressingTubService.java").read_text(encoding="utf-8-sig")
    for required_token in (
            "implements PressingTubBlockBehavior.Handler",
            "WALL_FURNITURE_ID",
            "PressingTubBlockBehavior.bind(this)",
            "PressingTubBlockBehavior.unbind(this)",
            "List<DisplayVisual> furnitureVisuals(",
            "InteractionResult interactFurniture(",
            "Optional<ItemStack> furnitureIngredientDrop(",
            "private InteractionResult interactPress(",
            "private static final class FurnitureTub implements TubAccess",
            "PRESS_MIN_FALL_DISTANCE = 0.5",
            "PlayerProxy.CLASS.isInstance(nmsEntity)",
            "BukkitCraftEngine.instance().antiGriefProvider()",
            "GameRule.MOB_GRIEFING",
            "ejectInvalidPressContents(tub"):
        if required_token not in pressing_service_source:
            raise AssertionError(
                "PressingTubService must own the press gameplay, permissions and "
                f"eject rules; missing token: {required_token}")
    for stale_token in (
            "EntityMoveEvent", "PlayerMoveEvent", "PressLandingTracker",
            "PressingTubLandingIndex", "hasPotentialBelow", "onFallDamage"):
        if stale_token in pressing_service_source:
            raise AssertionError(
                "PressingTubService must stay free of Bukkit landing-index "
                f"machinery; stale token: {stale_token}")
    visual_factory_source = (
        game_package / "PressingTubVisualFactory.java").read_text(encoding="utf-8-sig")
    for required_token in (
            "static double[] tiltDisplay(",
            "static Quaternionf tiltRotation(",
            "displayYaw = 0",
            "rotation = tiltRotation(facing, yRotation, zRotation)",
            "case NORTH -> 0",
            "case EAST -> 90",
            "case SOUTH -> -180",
            "case WEST -> -90",
            "DisplayVisual.of("):
        if required_token not in visual_factory_source:
            raise AssertionError(
                "PressingTubVisualFactory must keep the complete source wall transform "
                f"in one quaternion; missing token: {required_token}")
    if "facingYaw(" in visual_factory_source:
        raise AssertionError(
            "Tilted contents must not split source facing into entity yaw, because CE "
            "wall-furniture yaw would compose it a second time")
    for required_token in (
            "extends FurnitureBehaviorTemplate",
            "FurnitureBehaviors.register(Key.of(TYPE)",
            "public InteractionResult useOnFurniture(",
            "current.interact(bukkitFurniture, context)"):
        if required_token not in station_interaction_behavior_source:
            raise AssertionError(
                "Station CE interaction adapter is incomplete; "
                f"missing token: {required_token}")
    for forbidden_token in (
            "org.bukkit.event", "PersistentDataType", "NamespacedKey",
            "getNearbyEntities(", "runTaskTimer", "PlacementHandler",
            "bindPlacement(", "onPlace("):
        if forbidden_token in station_interaction_behavior_source:
            raise AssertionError(
                "Station CE interaction adapter must not own Paper polling/PDC; "
                f"found {forbidden_token}")
    for stale_station_visual_token in (
            "org.bukkit.entity.ItemDisplay", "PersistentDataType",
            "press_visual_owner", "press_visual_role", "press_visual_index",
            "barrel_visual_owner", "barrel_visual_role", "barrel_visual_index",
            "press_item_visuals", "press_fluid_visual",
            "barrel_item_visuals", "barrel_fluid_visual",
            "getNearbyEntities("):
        if stale_station_visual_token in station_source:
            raise AssertionError(
                "Station visuals must be CE packet-only elements without Bukkit helpers; "
                f"stale token: {stale_station_visual_token}")

    station_visual_source = (
        game_package / "furniture" / "StationVisualFurnitureBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_station_element_token in (
            "implements FurnitureElement",
            "public static void refresh(BukkitFurniture furniture)",
            "private VisualSnapshot currentSnapshot",
            "currentHandler.visuals(bukkitFurniture, maxElements)",
            "VisualSnapshot current = controller.currentSnapshot()",
            "DisplayData.ItemDisplayData.ItemStack.addEntityData",
            "DisplayData.ItemDisplayData.ItemTransform.addEntityData(",
            "DisplayData.Scale.addEntityData(",
            "DisplayData.LeftRotation.addEntityData(",
            "ClientboundAddEntityPacketProxy.INSTANCE.newInstance",
            "ClientboundRemoveEntitiesPacketProxy.INSTANCE.newInstance",
            "player.sendPackets",
            "public void gatherElements"):
        if required_station_element_token not in station_visual_source:
            raise AssertionError(
                "station_visual_furniture must use one CE tracked packet-only element; "
                f"missing token: {required_station_element_token}")
    for stale_station_element_token in (
            "org.bukkit.entity.ItemDisplay", "PersistentDataType", "World.spawn"):
        if stale_station_element_token in station_visual_source:
            raise AssertionError(
                "station_visual_furniture must never create persistent Bukkit entities; "
                f"stale token: {stale_station_element_token}")
    for stale_station_metadata_token in (
            "ItemTransform.addEntityDataIfNotDefaultValue",
            "Scale.addEntityDataIfNotDefaultValue",
            "LeftRotation.addEntityDataIfNotDefaultValue"):
        if stale_station_metadata_token in station_visual_source:
            raise AssertionError(
                "Reused station visual slots must write transform/scale/rotation "
                "unconditionally so clients reset to defaults; stale token: "
                f"{stale_station_metadata_token}")
    tap_source = (game_package / "TapService.java").read_text(
        encoding="utf-8-sig")
    tap_block_source = (game_package / "block/TapBlockBehavior.java").read_text(
        encoding="utf-8-sig")
    tap_semantics_source = (game_package / "TapSemantics.java").read_text(
        encoding="utf-8-sig")
    for required_token in (
            "TapSemantics.shouldDelegateBarrelTapPlacement(",
            "context.isSecondaryUseActive()",
            "context.getItem().id().toString()",
            "placeHeldTapBlockWithCraftEngine(furniture, context)",
            "behavior.getFirst(BlockItem.class)",
            "origin.getBlockX() + x * 2",
            "origin.getBlockY() + 1",
            "origin.getBlockZ() + z * 2",
            "placementBehavior.useOnBlock(new UseOnContext("):
        if required_token not in station_source:
            raise AssertionError(
                "Sneaking with a tap on barrel furniture must place the CE block in its "
                "canonical front-centre cell; "
                f"missing token: {required_token}")
    if 'secondaryUse && TAP_ITEM.equals(heldItemId)' not in tap_semantics_source:
        raise AssertionError(
            "Barrel tap placement must retain Forge's secondary-use bypass rule")
    tap_item_behavior = items[f"{NAMESPACE}:tap"].get("behavior", {})
    if tap_item_behavior != {
            "type": "block_item", "block": f"{NAMESPACE}:tap"}:
        raise AssertionError(
            "Tap installation must be owned by CE's native block_item behavior")
    if f"{NAMESPACE}:tap" in furniture:
        raise AssertionError("Tap must not retain a duplicate CE furniture definition")

    tap_definition = blocks[f"{NAMESPACE}:tap"]
    tap_states = tap_definition.get("states", {})
    tap_properties = tap_states.get("properties", {})
    if set(tap_properties) != {"facing", "open", "triggered", "waterlogged"}:
        raise AssertionError(
            f"Tap CE block properties drifted: {sorted(tap_properties)}")
    if tap_definition.get("behavior") != {"type": f"{NAMESPACE}:tap"}:
        raise AssertionError("Tap must use the Tavern CE block behavior")
    tap_appearances = tap_states.get("appearances", {})
    tap_variants = tap_states.get("variants", {})
    if len(tap_appearances) != 16 or len(tap_variants) != 32:
        raise AssertionError(
            "Tap needs 4 facing x 2 open x 2 waterlogged appearances and "
            f"32 complete states; found {len(tap_appearances)}/{len(tap_variants)}")
    tap_render_items: set[str] = set()
    for variant_key, mapped in tap_variants.items():
        properties = dict(part.split("=", 1) for part in variant_key.split(","))
        appearance = tap_appearances[mapped["appearance"]]
        expected_carrier = (
            "minecraft:lightning_rod"
            f"[facing={properties['facing']},powered=false,"
            f"waterlogged={properties['waterlogged']}]"
        )
        if appearance.get("state") != expected_carrier:
            raise AssertionError(
                f"Tap {variant_key} carrier must preserve facing/waterlogging")
        expected_settings = ({"fluid_state": "water"}
                             if properties["waterlogged"] == "true" else None)
        if mapped.get("settings") != expected_settings:
            raise AssertionError(
                f"Tap {variant_key} must preserve CE's server-side fluid state")
        renderer = appearance.get("entity_renderer", {})
        render_item = renderer.get("item")
        tap_render_items.add(render_item)
        expected_model = (f"{NAMESPACE}:block/brew/tap/open"
                          if properties["open"] == "true"
                          else f"{NAMESPACE}:block/brew/tap/close")
        if (renderer.get("type") != "item_display"
                or render_items.get(render_item, {}).get("model", {}).get("path")
                != expected_model):
            raise AssertionError(
                f"Tap {variant_key} must render its authored open/closed model")
        expected_rotation = {
            "north": "0,180,0",
            "south": None,
            "east": "0,90,0",
            "west": "0,270,0",
        }[properties["facing"]]
        if renderer.get("rotation") != expected_rotation:
            raise AssertionError(
                f"Tap {variant_key} north/south visual mapping drifted: "
                f"expected rotation {expected_rotation!r}, got {renderer.get('rotation')!r}")
    if len(tap_render_items) != 2:
        raise AssertionError(
            f"Tap must reuse exactly two private render items, found {tap_render_items}")
    for facing in ("north", "south", "west", "east"):
        for waterlogged in ("false", "true"):
            common = f"facing={facing},triggered=false,waterlogged={waterlogged}"
            closed_key = (
                f"facing={facing},open=false,triggered=false,waterlogged={waterlogged}")
            open_key = (
                f"facing={facing},open=true,triggered=false,waterlogged={waterlogged}")
            closed = tap_appearances[tap_variants[closed_key]["appearance"]]
            opened = tap_appearances[tap_variants[open_key]["appearance"]]
            if closed["state"] != opened["state"]:
                raise AssertionError(
                    f"Tap open/closed collision carrier changed for {common}")
            for open_value in ("false", "true"):
                untriggered = (
                    f"facing={facing},open={open_value},triggered=false,"
                    f"waterlogged={waterlogged}")
                triggered = untriggered.replace("triggered=false", "triggered=true")
                if tap_variants[untriggered] != tap_variants[triggered]:
                    raise AssertionError(
                        "Tap triggered is a server edge latch and must not change rendering")
    tap_settings = tap_definition.get("settings", {})
    if (tap_settings.get("hardness") != 0.8
            or tap_settings.get("push_reaction") != "NORMAL"
            or tap_settings.get("tags") != ["minecraft:mineable/pickaxe"]
            or any(tap_settings.get("sounds", {}).get(action)
                   != f"minecraft:block.metal.{action}"
                   for action in ("break", "step", "place", "hit", "fall"))):
        raise AssertionError("Tap CE block must retain the source metal settings")

    for variant_name, variant in furniture[f"{NAMESPACE}:barrel"]["variants"].items():
        if any(hitbox.get("can_use_item_on") is not True
               for hitbox in variant.get("hitboxes", [])):
            raise AssertionError(
                f"Barrel {variant_name} hitboxes must allow CE furniture-item placement")
    for required_token in (
            "LifecycleFurnitureBehavior.Channel.TAP_BOTTLE, block",
            "LifecycleFurnitureBehavior.Channel.BARREL,",
            "center, 3.25, 3.25",
            "private TapPlan resolve(Block tapBlock, BlockFace facing",
            "tapBlock.getRelative(facing.getOppositeFace())",
            "tapBlock.getRelative(BlockFace.DOWN)",
            "findConnectedBarrel(tapBlock, facing)",
            "TapBlockBehavior.bind(this)",
            "TapBlockBehavior.unbind(this)"):
        if required_token not in tap_source:
            raise AssertionError(
                "TapService must remain a business-only block handler using CE lifecycle "
                "indexes for placed bottles and barrels; "
                f"missing token: {required_token}")
    if "findFurnitureAtBlock" in tap_source:
        raise AssertionError(
            "TapService must not rediscover indexed placed bottles through Bukkit entities")

    for required_token in (
            "extends BukkitBlockBehavior",
            "implements EntityBlock",
            "BlockBehaviors.register(TYPE, TapBlockBehavior::new)",
            "context.getHand() != InteractionHand.MAIN_HAND",
            "clickedFace.axis().isHorizontal()",
            "SignalGetterProxy.INSTANCE.hasNeighborSignal(level, minecraftPos)",
            "LocationUtils.above(minecraftPos)",
            "powered && !triggered",
            "!powered && triggered",
            "TAKE_TICKS = 30",
            "TAKE_PARTICLE_TICKS = 5",
            "EMPTY_OPEN_TICKS = 6",
            "DRIP_LIFETIME_TICKS = 18",
            "private boolean open;",
            "this.open = blockEntity.blockState.get(behavior.openProperty)",
            "public void preBlockStateChange(ImmutableBlockState newState)",
            "open = newState.get(behavior.openProperty)",
            "if (!open)",
            "current.finish("):
        if required_token not in tap_block_source:
            raise AssertionError(
                "TapBlockBehavior must own source-equivalent state, redstone and timing; "
                f"missing token: {required_token}")
    for stale_token in (
            "extends WaterloggedBlockBehavior", "waterloggedProperty",
            "FluidStateProxy", "FluidsProxy",
            "LevelAccessorProxy.INSTANCE.scheduleTick$1(",
            "public Object updateShape("):
        if stale_token in tap_block_source:
            raise AssertionError(
                "Tap waterlogging must be owned by CE's automatically attached "
                f"WaterloggedBlockBehavior; stale Java token: {stale_token}")
    tap_placement_source = tap_block_source.partition(
        "public ImmutableBlockState updateStateForPlacement(")[2].partition(
        "public InteractionResult useOnBlock(")[0]
    for configured_default_token in ("openProperty", "triggeredProperty", "waterlogged"):
        if configured_default_token in tap_placement_source:
            raise AssertionError(
                "Tap placement Java may bridge clicked-face orientation only; CE config must "
                f"own defaults/waterlogging, found {configured_default_token}")
    if "if (!state.get(behavior.openProperty))" in tap_block_source:
        raise AssertionError(
            "TapBlockBehavior must cache its open state instead of resolving the CE "
            "property table from every loaded tap on every tick")
    for stale_token in ("BukkitTask", "runTaskTimer", "PersistentDataContainer",
                        "BukkitFurniture", "FurnitureInteractEvent"):
        if stale_token in tap_block_source:
            raise AssertionError(
                "The CE tap block must not duplicate its state through furniture/PDC/tasks; "
                f"found {stale_token}")
    for stale_token in ("BukkitRunnable", "BukkitTask", "running",
                        "RedstoneFurnitureBehavior", "TapGeometry", "geometry(tap)"):
        if stale_token in tap_source:
            raise AssertionError(
                "TapService must remain free of scheduler, furniture-redstone and geometry state; "
                f"found {stale_token}")

    storage_block_source = (
        game_package / "block/StorageBlockBehavior.java"
    ).read_text(encoding="utf-8-sig")
    storage_config_source = (
        game_package / "block/StorageBlockConfig.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "implements EntityBlock",
            "BlockBehaviors.register(TYPE, StorageBlockBehavior::new)",
            "StorageBlockConfig.parse(section)",
            "config.selector().select(",
            "config.interaction()",
            "config.orientation(facing)",
            "controller.config().slots().get(slot)",
            "config.launch()",
            "config.particle()",
            "public void neighborChanged",
            "SignalGetterProxy.INSTANCE.hasNeighborSignal(level, minecraftPos)",
            "state.with(poweredProperty, powered)",
            "private final Item[] items",
            "private int occupiedSlots",
            "occupiedSlots++",
            "occupiedSlots--",
            "return occupiedSlots != 0",
            "saveCustomData(CompoundTag tag)",
            "loadCustomData(CompoundTag tag)",
            "blockEntity.world.blockEntityChanged(blockEntity.pos)",
            "implements BlockEntityElement",
            "ClientboundAddEntityPacketProxy.INSTANCE.newInstance",
            "private static void tickParticle("):
        if required_token not in storage_block_source:
            raise AssertionError(
                "CE storage blocks must use one generic config-driven slot engine; "
                f"missing token: {required_token}")
    for required_token in (
            "record Orientation(", "record SlotVisual(", "record Selector(",
            "record Interaction(", "record Launch(", "record ParticleEffect(",
            "positionYaw", "modelYaw", "allowedItems", "blockedItems",
            "exclusiveItems", "refreshProperties"):
        if required_token not in storage_config_source:
            raise AssertionError(
                "Storage family data must be parsed from CE configuration; "
                f"missing token: {required_token}")
    for forbidden_family_token in (
            "StorageSemantics.Kind", "case BAR_CABINET", "case CELLAR_CABINET",
            "case TILTED_RACK", "case CIRCULAR_RACK", "case HOLDER",
            '"bar_cabinet"', '"cellar_cabinet"', '"tilted_rack"',
            '"circular_rack"', '"holder"'):
        if forbidden_family_token in storage_block_source or forbidden_family_token in storage_config_source:
            raise AssertionError(
                "Active storage family rules must stay in CE configuration; "
                f"found {forbidden_family_token}")
    connected_block_source = (
        game_package / "block/ConnectedBlockBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_token in (
            "case LINEAR -> updateLinear",
            "LinearConfig.parse(",
            "linear.output(left, right)"):
        if required_token not in connected_block_source:
            raise AssertionError(
                "Connected grid topology must be selected by CE-configured output maps; "
                f"missing token: {required_token}")
    if "state.with(\n                    facingProperty" in connected_block_source:
        raise AssertionError(
            "ConnectedBlockBehavior must not override CE's native facing placement")

    if "Arrays.stream(items)" in storage_block_source:
        raise AssertionError(
            "CE storage ticking must use the maintained occupied-slot count instead "
            "of allocating and scanning a stream every tick")
    for stale_token in ("BukkitTask", "runTaskTimer", "PersistentDataContainer",
                        "BukkitFurniture", "ConcurrentHashMap", "BlockRedstoneEvent"):
        if stale_token in storage_block_source:
            raise AssertionError(
                "CE storage blocks must not restore furniture/PDC polling; found "
                f"{stale_token}")
    if (game_package / "furniture/RedstoneFurnitureBehavior.java").exists():
        raise AssertionError(
            "The obsolete redstone furniture polling bridge must stay deleted")
    ticking_behavior_source = (
        game_package / "furniture/TickingFurnitureBehavior.java"
    ).read_text(encoding="utf-8-sig")
    ticking_scheduler_source = (
        game_package / "furniture/TickingScheduler.java"
    ).read_text(encoding="utf-8-sig")
    # 纯调度内核持有唯一 due-time 队列；时钟与唤醒由外部注入，保持可单测。
    for required_token in (
            "PriorityQueue<DueBucket>",
            "Map<Long, DueBucket> bucketsByTick",
            "enqueueLocked",
            "dispatchAction",
            "dispatchDue",
            "scheduleWakeLocked",
            "finishRunIfCurrent",
            "peekLiveBucketLocked",
            "pruneStaleHeadLocked",
            "maybeCompactQueueLocked",
            "liveQueuedRuns",
            "staleQueuedRuns",
            "postTickScheduleDecision",
            "LongSupplier",
            "WakeTarget"):
        if required_token not in ticking_scheduler_source:
            raise AssertionError(
                "Sparse furniture ticks must be driven by one pure due-time queue "
                f"in TickingScheduler; missing token: {required_token}")
    if "PriorityQueue<ScheduledRun>" in ticking_scheduler_source:
        raise AssertionError(
            "Sparse furniture runs with equal due ticks must stay coalesced into "
            "DueBucket heap nodes")
    for stale_token in ("org.bukkit", "BukkitTask", "Bukkit.getScheduler",
                        "runTaskLater", "net.momirealms.craftengine"):
        if stale_token in ticking_scheduler_source:
            raise AssertionError(
                "The TickingScheduler core must stay decoupled from the server "
                f"(clock/wake injected); found {stale_token}")
    for required_token in (
            "implements TickingScheduler.Host",
            "runTaskLater",
            "geometricDelay",
            "firstFutureDelay",
            "public void onLoad()",
            "public void onPlace(Player player)",
            "public void preRemove(Player player)",
            "public static void refreshSchedule(",
            "default boolean shouldSchedule(",
            "default Boolean tickAndScheduleDecision(",
            "handler.shouldSchedule(bukkitFurniture)",
            "postTickScheduleDecision(",
            "owner, action, delayTicks",
            "targetChannel.activeControllers.get(targetFurniture.uuid())"):
        if required_token not in ticking_behavior_source:
            raise AssertionError(
                "TickingFurnitureBehavior must stay a thin CE/Bukkit adapter over "
                f"the TickingScheduler core; missing token: {required_token}")
    if "createFurnitureTicker" in ticking_behavior_source:
        raise AssertionError(
            "Sparse furniture must not retain one CraftEngine ticker callback per instance")
    if "runTaskTimer" in ticking_behavior_source:
        raise AssertionError(
            "Sparse furniture due-time scheduling must stay wake-on-demand instead of "
            "polling an idle queue every server tick")
    if "runTaskLater(owner, () ->" in ticking_behavior_source:
        raise AssertionError(
            "Sparse furniture wakeups must pass the cached dispatch action directly "
            "instead of allocating one wrapper lambda per wake")
    if "bukkitFurniture.isValid()" in ticking_behavior_source:
        raise AssertionError(
            "CE lifecycle removal must keep the sparse due queue free of repeated "
            "Bukkit entity validity lookups")
    if "registerEvents(taps, this)" in plugin_source:
        raise AssertionError(
            "TapService has no Paper events after CE interaction/removal migration")
    stale_redstone_tokens = (
        "pollRedstone", "pollIncenseRedstone", "tap_triggered",
        "storage_powered", "storage_power_initialized", "incense_powered",
        "incense_initialized", "barrel_initialized", "barrel_open",
    )
    for stale_token in stale_redstone_tokens:
        if stale_token in all_paper_java:
            raise AssertionError(
                "CE furniture controllers own redstone/variant state throughout Paper; "
                f"{stale_token} must stay deleted")

    for required_token in (
            "StorageBlockBehavior.bind(storageBlockHandler)",
            "StorageBlockBehavior.unbind(storageBlockHandler)",
            "private void launchConfiguredItem(",
            "private Item storageBlockVisual(StorageBlockBehavior.Controller",
            "StorageInteractionFurnitureBehavior.bind(storageInteractionHandler)",
            "StorageInteractionFurnitureBehavior.unbind(storageInteractionHandler)",
            "private InteractionResult interact(",
            "public void onRemove(BukkitFurniture furniture, boolean dropItems)",
            "private void dropAndClearStorage(BukkitFurniture furniture, boolean dropItems)",
            "setControllerItem(furniture, slot, null, false)",
            "furniture.world().dropItemNaturally(furniture.position(), item)",
            "context.getHand() != InteractionHand.MAIN_HAND",
            "Vec3d click = context.getClickLocation()",
            "InteractionResult.SUCCESS_AND_CANCEL"):
        if required_token not in storage_source:
            raise AssertionError(
                "Display storage interaction must run before native CE slot controllers; "
                f"missing token: {required_token}")
    for stale_token in (
            "FurnitureInteractEvent", "FurnitureBreakEvent", "public void onInteract(",
            "public void onBreak(", "implements Listener", "Bukkit.getScheduler()"):
        if stale_token in storage_source:
            raise AssertionError(
                "DisplayStorageService must not retain a global Paper furniture listener; "
                f"found {stale_token}")
    if "registerEvents(displayStorage, this)" in plugin_source:
        raise AssertionError(
            "Display storage removal is CE lifecycle-owned and must not be globally registered")
    for required_token in (
            "extends FurnitureBehaviorTemplate",
            "FurnitureBehaviors.register(Key.of(TYPE)",
            "public InteractionResult useOnFurniture(",
            "current.interact(bukkitFurniture, context)",
            "public void preRemove(Player player)",
            "current.onRemove(bukkitFurniture,",
            "player != null && !player.canInstabuild()"):
        if required_token not in storage_interaction_behavior_source:
            raise AssertionError(
                "Storage CE interaction adapter is incomplete; "
                f"missing token: {required_token}")
    for forbidden_token in (
            "org.bukkit.event", "PersistentDataType", "NamespacedKey",
            "getNearbyEntities(", "runTaskTimer"):
        if forbidden_token in storage_interaction_behavior_source:
            raise AssertionError(
                "Storage CE interaction adapter must not own Paper polling/PDC; "
                f"found {forbidden_token}")
    for stale_storage_visual_token in (
            "ItemDisplay", "cabinet_visual", "PersistentDataType",
            "getNearbyEntities", "new FurnitureState", "Channel.STORAGE, storageLifecycle"):
        if stale_storage_visual_token in storage_source:
            raise AssertionError(
                "Storage visuals must be CE packet-only elements without Bukkit helper state; "
                f"stale token: {stale_storage_visual_token}")
    for required_storage_visual_token in (
            "StorageVisualFurnitureBehavior.bind(storageVisualHandler)",
            "StorageVisualFurnitureBehavior.unbind(storageVisualHandler)",
            "StorageVisualFurnitureBehavior.refresh(furniture)",
            "private StorageVisualFurnitureBehavior.Visual storageVisual"):
        if required_storage_visual_token not in storage_source:
            raise AssertionError(
                "Display storage must feed the CE virtual visual controller; "
                f"missing token: {required_storage_visual_token}")

    storage_visual_source = (
        game_package / "furniture" / "StorageVisualFurnitureBehavior.java"
    ).read_text(encoding="utf-8-sig")
    for required_storage_element_token in (
            "implements FurnitureElement",
            "public static void refresh(BukkitFurniture furniture)",
            "private final Visual[] cachedVisuals",
            "private final boolean[] visualsDirty",
            "consumer.accept(new StorageVisualElement(this, slots))",
            "private final int[] entityIds",
            "new IntArrayList(entityIds)",
            "Visual visual = controller.visual(slot)",
            "packets.add(removePacket)",
            "DisplayData.ItemDisplayData.ItemStack.addEntityData",
            "DisplayData.ItemDisplayData.LeftRotation.addEntityDataIfNotDefaultValue",
            "new Quaternionf().rotateX((float) Math.toRadians(visual.xRot()))",
            "0, position.yRot()",
            "ClientboundAddEntityPacketProxy.INSTANCE.newInstance",
            "player.sendPackets",
            "public void gatherElements"):
        if required_storage_element_token not in storage_visual_source:
            raise AssertionError(
                "storage_visual_furniture must use one batched CE tracked packet-only element; "
                f"missing token: {required_storage_element_token}")
    for stale_storage_element_token in (
            "org.bukkit.entity.ItemDisplay", "PersistentDataType", "World.spawn",
            "class StorageItemElement"):
        if stale_storage_element_token in storage_visual_source:
            raise AssertionError(
                "storage_visual_furniture must never create persistent Bukkit entities; "
                f"stale token: {stale_storage_element_token}")
    if "furniture.setUnsaved()" in storage_source:
        raise AssertionError(
            "CE display-item controllers already own storage dirty state; "
            "DisplayStorageService must not duplicate that write")

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

    # Vision, upside-down and slightly-tipsy are point-of-view effects. They
    # must never write shared entity state, otherwise unrelated players see
    # the outline/name or the server gains a fake nausea effect.
    effect_service_source = (game_package / "EffectService.java").read_text(
        encoding="utf-8-sig")
    viewer_packet_source = (game_package / "ViewerEffectPackets.java").read_text(
        encoding="utf-8-sig")
    for shared_state_write in (
            "living.addPotionEffect(new PotionEffect(glowing",
            'customName(Component.text("Grumm"))',
            'setCustomNameVisible(false)'):
        if shared_state_write in effect_service_source:
            raise AssertionError(
                f"Viewer-only drink effect writes shared entity state: {shared_state_write}")
    if "viewer.sendPotionEffectChange" not in effect_service_source:
        raise AssertionError("Vision must use Paper's per-viewer effect packet")
    for tipsy_packet_token in (
            "private final Map<UUID, Long> privateTipsyExpiry",
            "syncPrivateTipsyVisual(player, effects)",
            "tipsy.visibleExpiryTick()",
            "player.sendPotionEffectChange(player, new PotionEffect(",
            "player.sendPotionEffectChangeRemove(player, type)",
            "restorePrivateTipsyVisual(player)"):
        if tipsy_packet_token not in effect_service_source:
            raise AssertionError(
                "Slightly-tipsy must retain its viewer-only client visual; "
                f"missing {tipsy_packet_token}")
    if "nausea, PotionEffect.INFINITE_DURATION" in effect_service_source:
        raise AssertionError(
            "Slightly-tipsy nausea proxy must carry the remaining tipsy "
            "duration, not an infinite effect: a missed restore would leave "
            "permanent nausea on the player")
    if "player.addPotionEffect(new PotionEffect(nausea" in effect_service_source:
        raise AssertionError(
            "Slightly-tipsy must not add a real server-side nausea effect")
    effect_start = re.search(
        r"public void start\(\) \{(.*?)\n    \}\n\n    public void stop\(\)",
        effect_service_source,
        flags=re.DOTALL)
    if effect_start is None or "runTaskTimer" in effect_start.group(1):
        raise AssertionError(
            "Custom effects must not leave an unconditional global tick task running")
    for on_demand_token in (
            "private final Map<UUID, LivingEntity> activeEntities",
            "LivingEntity living = activeEntities.get(entry.getKey())",
            "public void onEntityRemove(EntityRemoveEvent event)",
            "event.getCause() == EntityRemoveEvent.Cause.UNLOAD",
            "private void ensureTickTask()",
            "if (active.isEmpty())",
            "if (task != null && fastTickTask)",
            "runTaskTimer(plugin, tickAction, 1L, 1L)",
            "runTaskLater(plugin, tickAction, delay)",
            "nextIntervalTick(",
            "scheduledWakeTick",
            "private void stopTickTaskIfIdle()",
            "if (!active.isEmpty())"):
        if on_demand_token not in effect_service_source:
            raise AssertionError(
                f"Custom effect on-demand tick lifecycle is missing {on_demand_token}")
    effect_tick_source = effect_service_source.partition(
        "private void tick()")[2].partition(
        "private void ensureTickTask()")[0]
    for stale_effect_entity_probe in (
            "Bukkit.getEntity(entry.getKey())", "living.isValid()", "living.isDead()"):
        if stale_effect_entity_probe in effect_tick_source:
            raise AssertionError(
                "Paper entity lifecycle events must keep custom-effect ticks free of repeated "
                f"validity/UUID probes; found {stale_effect_entity_probe}")
    for event_particle_token in (
            "private final Map<UUID, EffectParticleState> particleStates",
            "private final Set<UUID> pendingEffectParticleRefresh",
            "scheduleEffectParticleRefresh(event.getEntity(), 2L)",
            "syncEffectParticleMetadata(target, effects)",
            "syncEffectParticleMetadata(living, effects)",
            "refreshAfterVanillaPotionChange(",
            "restoreVanillaParticleState(living)",
            "restoreAllEffectParticleMetadata()",
            "private final Set<UUID> fastTickEntities",
            "private final Runnable tickAction = this::tick",
            "tickFastEffects()",
            "maintainEffects(",
            "MAINTENANCE_INTERVAL_TICKS",
            "PERSIST_INTERVAL_TICKS",
            "nextPassiveExpiryTick",
            "effect.tickKind() == TickKind.NONE",
            "private final TickKind tickKind"):
        if event_particle_token not in effect_service_source:
            raise AssertionError(
                "Custom effect visuals must use event-driven client metadata while passive "
                f"effects stay out of the per-tick entity scan; missing {event_particle_token}")
    for stale_particle_replay in (
            "sendEffectParticleMetadata(",
            "effectParticleDataCache",
            "buildEffectParticleMetadata"):
        if stale_particle_replay in effect_service_source:
            raise AssertionError(
                "Custom effect particles must not be replayed through PlayerTrackEntityEvent "
                f"or per-viewer packet sends; stale token: {stale_particle_replay}")
    for real_metadata_token in (
            "readEffectParticles(",
            "readEffectAmbience(",
            "setEffectParticleMetadata(",
            "LivingEntityData.EffectParticles.entityDataAccessor()",
            "LivingEntityData.EffectAmbience.entityDataAccessor()",
            "SynchedEntityDataProxy.INSTANCE.set("):
        if real_metadata_token not in viewer_packet_source:
            raise AssertionError(
                "Custom effect particles must write the real SynchedEntityData through CE's "
                f"LivingEntityData accessors; missing {real_metadata_token}")
    for stale_packall_bridge in ("findDataValueBySerializer(", "EntityDataSerializersProxy.PARTICLES"):
        if stale_packall_bridge in viewer_packet_source:
            raise AssertionError(
                "Custom effect particles must not scan packed metadata for serializer ids; "
                f"stale token: {stale_packall_bridge}")
    for track_listener_token in (
            "private final TrackReplayListener trackReplayListener",
            "HandlerList.unregisterAll(trackReplayListener)",
            "ensureTrackReplayListener()",
            "stopTrackReplayListenerIfIdle()"):
        if track_listener_token not in effect_service_source:
            raise AssertionError(
                "Track replay must live in a dynamically registered listener that only "
                f"handles upside_down; missing {track_listener_token}")
    if "living.getWorld().spawnParticle(Particle.ENTITY_EFFECT,\n                box." in effect_service_source:
        raise AssertionError(
            "Custom effect particles must not scan every player in the world")
    for client_particle_bridge_token in (
            'Class.forName("org.bukkit.craftbukkit.CraftParticle")',
            '"createParticleParam", Particle.class, Object.class'):
        if client_particle_bridge_token not in viewer_packet_source:
            raise AssertionError(
                "Custom effect particle metadata must retain its one-time native option bridge; "
                f"missing {client_particle_bridge_token}")
    for server_particle_fallback in (
            "spawnEffectParticles(",
            "sendEntityEffectParticle(",
            "spawnParticle(Particle.ENTITY_EFFECT, receivers, null",
            "particlePacketsAvailable",
            "sendParticlesSource",
            "ServerLevelProxy.CLASS.getMethods()",
            "CraftWorldProxy.INSTANCE.getWorld(world)"):
        if server_particle_fallback in effect_service_source + viewer_packet_source:
            raise AssertionError(
                "Decorative effect particles must animate from client entity metadata, not "
                f"server tick packets; found {server_particle_fallback}")
    for allocation_free_tick_token in (
            "Iterator<Map.Entry<String, ActiveEffect>> effectIterator",
            "effectIterator.remove()",
            "boolean remainsActive = effect.advanceTo(elapsedTicks)",
            "private final EffectSemantics.MutableEffectState state",
            "state.snapshotAfter(elapsedSince(currentTick))",
            "private boolean tickEffect(LivingEntity living, ActiveEffect effect)"):
        if allocation_free_tick_token not in effect_service_source:
            raise AssertionError(
                "Custom effect steady-state ticking must mutate countdowns without snapshots; "
                f"missing {allocation_free_tick_token}")
    for mutable_state_token in (
            "static final class MutableEffectState",
            "remainingTicks[index] -= elapsedTicks",
            "while (firstLayer < remainingTicks.length",
            "EffectState snapshot()",
            "EffectState snapshotAfter(int elapsedTicks)"):
        if mutable_state_token not in effect_semantics_source:
            raise AssertionError(
                "Runtime custom-effect state must preserve hidden-layer semantics without "
                f"per-tick object chains; missing {mutable_state_token}")
    for stale_effect_tick_allocation in (
            "new ArrayList<>(effects.values())",
            "EffectSemantics.advanceEffect(effect.state()",
            "effectEntry.setValue(new ActiveEffect(effect.effect()"):
        if stale_effect_tick_allocation in effect_service_source:
            raise AssertionError(
                "Custom effect ticking must not rebuild collection or state snapshots every "
                f"entity tick; found {stale_effect_tick_allocation}")
    for typed_effect_storage_token in (
            "PersistentDataType.LIST.strings()",
            "PersistentDataType.LIST.longArrays()",
            ".get(activeKey, PersistentDataType.TAG_CONTAINER)",
            "owner.set(activeKey, PersistentDataType.TAG_CONTAINER, encoded)",
            "owner.set(splashCustomEffectsKey, PersistentDataType.TAG_CONTAINER, encoded)",
            "effect.stateAt(currentTick)",
            "EffectSemantics.encodeState(state)",
            "EffectSemantics.decodeState(values.get(index))"):
        if typed_effect_storage_token not in effect_service_source:
            raise AssertionError(
                "Custom effect persistence must use typed compound/list NBT instead of "
                f"delimiter parsing; missing {typed_effect_storage_token}")
    for stale_effect_storage_token in (
            "get(activeKey, PersistentDataType.STRING)",
            "set(activeKey, PersistentDataType.STRING",
            "encodeSplashEffects", "decodeSplashEffects",
            'new StringBuilder("v3|")', "decodeRemainingTicks"):
        if stale_effect_storage_token in effect_service_source:
            raise AssertionError(
                "Legacy string effect persistence is intentionally unsupported; "
                f"found {stale_effect_storage_token}")
    for packet_token in (
            "ClientboundSetEntityDataPacketProxy",
            'ComponentProxy.INSTANCE.literal("Grumm")',
            "restoreCustomName"):
        if packet_token not in viewer_packet_source:
            raise AssertionError(
                f"Upside-down viewer packet bridge is missing {packet_token}")

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
    for block_id in expected_geometryless - {"chalkboard"}:
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

    # ChalkboardBlock parity now uses real CE block cells: native double-high
    # behavior owns each vertical pair, released closed iron-door states provide
    # a continuous two-block client target, and Tavern owns only the source's
    # three-wide blank-board merge and text block entity.
    chalkboard_id = f"{NAMESPACE}:chalkboard"
    if chalkboard_id in furniture:
        raise AssertionError("Chalkboard must not remain CE furniture")
    chalkboard = blocks[chalkboard_id]
    chalkboard_states = chalkboard.get("states", {})
    expected_chalkboard_properties = {
        "facing": {
            "type": "horizontal_direction",
            "default": "north",
            "values": ["north", "east", "south", "west"],
        },
        "half": {
            "type": "double_block_half",
            "default": "lower",
            "values": ["lower", "upper"],
        },
        "position": {
            "type": "string",
            "default": "single",
            "values": ["single", "left", "middle", "right"],
        },
        "waterlogged": {"type": "boolean", "default": "false"},
    }
    if chalkboard_states.get("properties") != expected_chalkboard_properties:
        raise AssertionError("Chalkboard CE state schema drifted")

    chalkboard_variants = chalkboard_states.get("variants", {})
    expected_chalkboard_variant_keys = {
        f"facing={facing},half={half},position={position},waterlogged={waterlogged}"
        for facing in ("north", "east", "south", "west")
        for half in ("lower", "upper")
        for position in ("single", "left", "middle", "right")
        for waterlogged in ("false", "true")
    }
    if set(chalkboard_variants) != expected_chalkboard_variant_keys:
        raise AssertionError(
            "Chalkboard must expose all 64 facing/half/position/fluid states")

    chalkboard_appearances = chalkboard_states.get("appearances", {})
    referenced_appearances: set[str] = set()
    # The base north model has its panel on the south edge and its painted front
    # looking north. Minecraft 26.2's ItemDisplay renderer turns the submitted
    # item model +180 degrees after CE's metadata transform, so each configured
    # yaw must include the inverse compensation. Lock both the carrier-side
    # position and visible direction; checking only the raw rotation string
    # previously allowed every board to render opposite its closed-door state.
    facing_yaw = {"north": 180, "east": 90, "south": None, "west": 270}
    expected_panel_edge = {
        "north": "south", "east": "west",
        "south": "north", "west": "east",
    }
    expected_front_direction = {
        "north": "north", "east": "east",
        "south": "south", "west": "west",
    }

    def rotated_cardinal(x: int, z: int, yaw: int) -> str:
        # ItemDisplayRenderer.submitInner contributes the final +180-degree Y
        # item turn; horizontal rotations commute with CE's metadata quaternion.
        angle = math.radians(yaw + 180)
        rotated_x = round(x * math.cos(angle) + z * math.sin(angle))
        rotated_z = round(-x * math.sin(angle) + z * math.cos(angle))
        return {
            (0, -1): "north", (1, 0): "east",
            (0, 1): "south", (-1, 0): "west",
        }[(rotated_x, rotated_z)]

    for variant_key, variant in chalkboard_variants.items():
        properties = dict(part.split("=", 1) for part in variant_key.split(","))
        facing = properties["facing"]
        half = properties["half"]
        position = properties["position"]
        waterlogged = properties["waterlogged"]
        visible_size = (
            "small" if half == "lower" and position == "single"
            else "large" if half == "lower" and position == "middle"
            else None
        )
        appearance_name = (
            f"{visible_size}_{facing}_{half}"
            if visible_size is not None
            else f"hidden_{facing}_{half}"
        )
        if variant.get("appearance") != appearance_name:
            raise AssertionError(
                f"Chalkboard {variant_key} maps to the wrong renderer")
        expected_variant_settings = (
            {"fluid_state": "water"} if waterlogged == "true" else {})
        if variant.get("settings", {}) != expected_variant_settings:
            raise AssertionError(
                f"Chalkboard {variant_key} fluid state drifted")

        appearance = chalkboard_appearances.get(appearance_name, {})
        referenced_appearances.add(appearance_name)
        expected_carrier = (
            "minecraft:iron_door"
            f"[facing={facing},half={half},hinge=left,open=false,powered=true]"
        )
        if (appearance.get("state") != expected_carrier
                or appearance.get("transparent") is not True):
            raise AssertionError(
                f"Chalkboard {variant_key} must use its released closed-door carrier")
        renderer = appearance.get("entity_renderer")
        if visible_size is None:
            if renderer is not None:
                raise AssertionError(
                    f"Chalkboard side/upper cell {variant_key} must stay visually hidden")
            continue
        expected_renderer = {
            "type": "item_display",
            "item": f"{NAMESPACE}:_render/chalkboard/{visible_size}",
            "display_transform": "none",
            "shadow_radius": 0,
            "view_range": 1.25,
        }
        if facing_yaw[facing] is not None:
            expected_renderer["rotation"] = f"0,{facing_yaw[facing]},0"
        if renderer != expected_renderer:
            raise AssertionError(
                f"Chalkboard {variant_key} model renderer drifted: {renderer}")
        yaw = facing_yaw[facing] or 0
        if (rotated_cardinal(0, 1, yaw) != expected_panel_edge[facing]
                or rotated_cardinal(0, -1, yaw)
                != expected_front_direction[facing]):
            raise AssertionError(
                f"Chalkboard {facing} model must share its door edge and look outward")
    if (referenced_appearances != set(chalkboard_appearances)
            or len(chalkboard_appearances) != 16):
        raise AssertionError("Chalkboard appearance set contains stale or missing states")

    expected_chalkboard_behaviors = [
        {"type": "double_high_block"},
        {"type": f"{NAMESPACE}:chalkboard"},
    ]
    if chalkboard.get("behaviors") != expected_chalkboard_behaviors:
        raise AssertionError(
            "Chalkboard vertical lifecycle must remain CE-native and precede Tavern merge/text")
    settings = chalkboard.get("settings", {})
    if (settings.get("item") != chalkboard_id
            or settings.get("hardness") != 0.8
            or settings.get("resistance") != 0.8
            or settings.get("push_reaction") != "NORMAL"
            or settings.get("tags") != ["minecraft:mineable/axe"]
            or settings.get("destroy_stages")
            != {"template": "internal:destroy_stages"}
            or settings.get("map_color") != 13
            or settings.get("instrument") != "guitar"
            or settings.get("burnable") is not True
            or settings.get("burn_chance") != 5
            or settings.get("fire_spread_chance") != 20):
        raise AssertionError("Chalkboard survival mining settings drifted")

    expected_count_functions = [
        {
            "type": "set_count",
            "count": 3,
            "add": False,
            "conditions": [{
                "type": "match_block_property",
                "properties": {"position": position},
            }],
        }
        for position in ("left", "middle", "right")
    ]
    expected_chalkboard_loot = {
        "pools": [{
            "rolls": 1,
            "conditions": [{"type": "survives_explosion"}],
            "entries": [{
                "type": "item",
                "item": chalkboard_id,
                "functions": expected_count_functions,
            }],
        }],
    }
    if chalkboard.get("loot") != expected_chalkboard_loot:
        raise AssertionError(
            "CE must own chalkboard drops and return three items for any merged cell")
    expected_chalkboard_item_behavior = {
        "type": "double_high_block_item",
        "block": chalkboard_id,
    }
    if items[chalkboard_id].get("behavior") != expected_chalkboard_item_behavior:
        raise AssertionError(
            "Chalkboard placement must use CE's native double_high_block_item")

    # The archived entity models use CubeListBuilder.texOffs(0, 0): a complete
    # six-face net with one-pixel depth. Lock the exact normalized UV rectangles
    # so the large merged board cannot regress to the former cropped two-face
    # approximation (and keep the small/large sides visually identical).
    expected_chalkboard_models = {
        "small": {
            "from": [0, 2, 15],
            "to": [16, 30, 16],
            "texture": f"{NAMESPACE}:entity/deco/small_chalkboard",
            "faces": {
                "down": [0.25, 0, 4.25, 0.25],
                "up": [4.25, 0, 8.25, 0.25],
                "west": [0, 0.25, 0.25, 7.25],
                "north": [0.25, 0.25, 4.25, 7.25],
                "east": [4.25, 0.25, 4.5, 7.25],
                "south": [4.5, 0.25, 8.5, 7.25],
            },
        },
        "large": {
            "from": [-16, 2, 15],
            "to": [32, 30, 16],
            "texture": f"{NAMESPACE}:entity/deco/large_chalkboard",
            "faces": {
                "down": [0.125, 0, 6.125, 0.25],
                "up": [6.125, 0, 12.125, 0.25],
                "west": [0, 0.25, 0.125, 7.25],
                "north": [0.125, 0.25, 6.125, 7.25],
                "east": [6.125, 0.25, 6.25, 7.25],
                "south": [6.25, 0.25, 12.25, 7.25],
            },
        },
    }
    for size, expected in expected_chalkboard_models.items():
        helper_id = f"{NAMESPACE}:_render/chalkboard/{size}"
        if render_items.get(helper_id, {}).get("model", {}).get("path") != (
                f"{NAMESPACE}:furniture/chalkboard_{size}"):
            raise AssertionError(f"Chalkboard {size} render helper drifted")
        model = asset_json(f"{NAMESPACE}:furniture/chalkboard_{size}", "models")
        elements = [] if model is None else model.get("elements", [])
        if len(elements) != 1:
            raise AssertionError(
                f"Chalkboard {size} must contain exactly one source cuboid")
        element = elements[0]
        actual_faces = {
            face: data.get("uv")
            for face, data in element.get("faces", {}).items()
            if data.get("texture") == "#board"
        }
        if (element.get("from") != expected["from"]
                or element.get("to") != expected["to"]
                or model.get("textures", {}).get("board") != expected["texture"]
                or actual_faces != expected["faces"]):
            raise AssertionError(
                f"Chalkboard {size} geometry/UV no longer matches its archived entity cube")

    trellis = blocks[f"{NAMESPACE}:trellis"]
    if "support_shape" in trellis.get("settings", {}):
        raise AssertionError("Trellis must not expose a full-cube support/occlusion shape")
    vine_trellis_ids = (
        "grapevine_trellis", "ice_grapevine_trellis", "gold_grapevine_trellis")

    # A carrier is all the client ever sees, so it decides both what the player
    # collides with and what can be aimed at. Every trellis appearance uses a
    # directional lightning-rod state: vertical members use facing=up, while
    # horizontal members use their matching axis. Dry and waterlogged variants
    # use matching carrier/fluid states; the carrier remains transparent to the
    # authored ItemDisplay and collidable for connected shapes.
    collidable_trellises = 0
    for block_id in ("trellis", *vine_trellis_ids):
        definition = blocks[f"{NAMESPACE}:{block_id}"]
        states = definition["states"]
        expected_axis = {
            "type": "axis", "default": "y", "values": ["x", "y", "z"]}
        if states.get("properties", {}).get("axis") != expected_axis:
            raise AssertionError(
                f"{block_id}: CE must own clicked-face placement through native axis state")
        for name, appearance in states["appearances"].items():
            if appearance.get("entity_renderer", {}).get("type") != "item_display":
                raise AssertionError(f"{block_id}/{name} must keep its authored item-display model")
            state = appearance.get("state", "")
            if not state.startswith("minecraft:lightning_rod["):
                raise AssertionError(
                    f"{block_id}/{name}: every trellis shape needs a colliding lightning-rod carrier")
            if "powered=false" not in state or "waterlogged=" not in state:
                raise AssertionError(
                    f"{block_id}/{name}: trellis carrier must remain unpowered and water-aware")
            collidable_trellises += 1
        for variant_key, mapped in states["variants"].items():
            properties = dict(part.split("=", 1) for part in variant_key.split(","))
            waterlogged = properties["waterlogged"]
            appearance = states["appearances"][mapped["appearance"]]
            if f"waterlogged={waterlogged}" not in appearance["state"]:
                raise AssertionError(
                    f"{block_id}/{variant_key}: carrier lost its waterlogged state")
            expected_settings = ({"fluid_state": "water"}
                                 if waterlogged == "true" else None)
            if mapped.get("settings") != expected_settings:
                raise AssertionError(
                    f"{block_id}/{variant_key}: CE fluid state does not match waterlogged")
    if collidable_trellises != 74:
        raise AssertionError(
            f"Expected 74 dry/waterlogged trellis appearances, found {collidable_trellises}")
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
    wild_head_appearances = (
        blocks[f"{NAMESPACE}:wild_grapevine"].get("states", {}).get("appearances", {}))
    if len(wild_head_appearances) != 1:
        raise AssertionError("Wild grapevine head must keep one shared authored appearance")
    wild_head_appearance = next(iter(wild_head_appearances.values()))
    wild_body_appearance = blocks[f"{NAMESPACE}:wild_grapevine_plant"].get("state", {})
    expected_wild_carrier = {
        "type": "cave_vines",
        "id": "kaleidoscope-tavern-wild-grapevine-transparent",
    }
    for label, appearance in (
            ("head", wild_head_appearance),
            ("body", wild_body_appearance)):
        if (appearance.get("auto_state") != expected_wild_carrier
                or "state" in appearance
                or appearance.get("transparent") is not True
                or appearance.get("entity_renderer", {}).get("item")
                not in render_items):
            raise AssertionError(
                f"Wild grapevine {label} must share CE's cave-vines auto-state carrier")
    if any("weeping_vines" in json.dumps(appearance)
           for appearance in (wild_head_appearance, wild_body_appearance)):
        raise AssertionError(
            "Wild grapevine must not reserve vanilla weeping-vine texture states")
    for crop in ("grape_crop", "ice_grape_crop", "gold_grape_crop"):
        for point in range(6):
            crop_id = (f"{NAMESPACE}:{crop}" if point == 0
                       else f"{NAMESPACE}:_crop/{crop}/stage_{point}")
            appearance = blocks[crop_id].get("state", {})
            if (appearance.get("auto_state") != expected_wild_carrier
                    or "state" in appearance
                    or appearance.get("transparent") is not True
                    or appearance.get("entity_renderer", {}).get("item")
                    not in render_items):
                raise AssertionError(
                    f"{crop_id}: hanging crop must share CE's cave-vines carrier")
    wild_settings = blocks[f"{NAMESPACE}:wild_grapevine"]["settings"]
    if (wild_settings.get("hardness") != 0
            or wild_settings.get("resistance") != 0
            or wild_settings.get("sounds", {}).get("break")
            != "minecraft:block.cave_vines.break"):
        raise AssertionError("Wild grapevine must retain instant break and cave-vine sounds")
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
            renderer = appearance.get("entity_renderer")
            if renderer is None:
                if block_id == f"{NAMESPACE}:chalkboard":
                    # Upper and side cells keep only the transparent door
                    # carrier; the lower root renders the complete board.
                    continue
                raise AssertionError(f"{block_id}: missing entity renderer")
            render_id = renderer.get("item")
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
        item_behaviors = (
            item.get("behaviors", []) or ([item["behavior"]] if "behavior" in item else [])
        )
        behavior_types = {behavior.get("type") for behavior in item_behaviors}
        if (item.get("material") != "potion"
                or behavior_types != {f"{NAMESPACE}:sneak_place_drink"}):
            raise AssertionError(
                f"{item_id}: drinks must remain consumable items with CE-owned sneak placement")
        if (len(item_behaviors) != 1
                or item_behaviors[0].get("furniture") != item_id
                or item_behaviors[0].get("rules") != {
                    "ground": {"rotation": "four", "alignment": "center"}
                }
                or item_behaviors[0].get("sync_active_use") is not True):
            raise AssertionError(
                f"{item_id}: CE drink placement target/rules or active-use sync drifted "
                "from BottleBlockItem")
        components = item.get("data", {}).get("components", {})
        if components.get("minecraft:max_stack_size") != 16:
            raise AssertionError(f"{item_id}: bottle/glassware stack size must remain 16")
        if components.get("minecraft:consumable") != {
                "consume_seconds": 1.6,
                "animation": "drink",
                "sound": "minecraft:entity.generic.drink",
                "has_consume_particles": False,
                }:
            raise AssertionError(
                f"{item_id}: drinks must declare the complete server-visible drink use contract")
        data = item.get("data", {})
        if data.get("custom_name") != data.get("item_name"):
            raise AssertionError(
                f"{item_id}: potion drinks require custom_name because PotionItem "
                "ignores item_name when deriving its hover title")
        if data.get("hide_tooltip") != ["minecraft:potion_contents"]:
            raise AssertionError(
                f"{item_id}: drinks must hide only the vanilla potion_contents tooltip; "
                "their real server-side effects are rendered as custom lore")
        potion_contents = components.get("minecraft:potion_contents")
        if (not isinstance(potion_contents, dict)
                or potion_contents.get("potion") != "minecraft:mundane"
                or not set(potion_contents).issubset({"potion", "custom_color"})
                or ("custom_color" in potion_contents
                    and not isinstance(potion_contents["custom_color"], int))):
            raise AssertionError(
                f"{item_id}: drink potion_contents must use the effectless mundane base "
                "to prevent PotionItem water-on-dirt conversion, and may only add an "
                "integer custom_color")

    public_vessel_ids = {
        f"{NAMESPACE}:{vessel_id}"
        for vessel_id in EXPECTED_BOTTLE_FURNITURE | {"shaker"}
        if f"{NAMESPACE}:{vessel_id}" in items
    }
    expected_ground_rule = {
        "ground": {"rotation": "four", "alignment": "center"}
    }
    for item_id in public_vessel_ids:
        item = items[item_id]
        item_behaviors = (
            item.get("behaviors", []) or ([item["behavior"]] if "behavior" in item else [])
        )
        placement_behaviors = [
            behavior for behavior in item_behaviors
            if behavior.get("furniture") == item_id
        ]
        placement_type = f"{NAMESPACE}:sneak_place_drink"
        expected_placement_behavior = {
                "type": placement_type,
                "furniture": item_id,
                "rules": expected_ground_rule,
        }
        if item_id in drink_ids or item_id == f"{NAMESPACE}:molotov":
            expected_placement_behavior["sync_active_use"] = True
        if placement_behaviors != [expected_placement_behavior]:
            raise AssertionError(
                f"{item_id}: portable vessels must delegate sneak placement through "
                "the generic native-CE furniture adapter with the correct active-use policy")

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
        if any("<insert:kaleidoscope_tavern_managed_lore>" not in line for line in lore):
            raise AssertionError(
                f"{item_id}: generated cocktail lore must carry the managed insertion marker")

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
                "animation": "trident",
                "has_consume_particles": False,
            }):
        raise AssertionError(
            "Molotov must retain its 72,000-tick trident charge instead of instant splash-potion use")
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
                "animation": "none",
                "has_consume_particles": False,
            }):
        raise AssertionError(
            "Shaker must retain active-use timing without the brush animation's lateral sway")
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
    if set(barrel_variants) != {"ground", "ground_closed"}:
        raise AssertionError(
            "The source barrel must place open through CE's native ground variant")
    open_barrel = barrel_variants["ground"]
    closed_barrel = barrel_variants["ground_closed"]
    expected_barrel_hitbox = [{
        "type": "happy_ghast",
        "position": "0,0,0",
        "scale": 0.75,
        "hard_collision": True,
        "can_use_item_on": True,
        "can_be_hit_by_projectile": True,
        "blocks_building": True,
    }]
    if any(variant.get("hitboxes") != expected_barrel_hitbox
           for variant in barrel_variants.values()):
        raise AssertionError(
            "The barrel must use one CE happy-ghast collider with the exact 3x3x3 footprint")
    closed_element = closed_barrel["elements"][0]
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

    stool = furniture[f"{NAMESPACE}:white_bar_stool"]["variants"]["ground"]
    bottle = furniture[f"{NAMESPACE}:empty_bottle"]["variants"]["ground"]
    if stool["hitboxes"][0].get("height") != 1.3125:
        raise AssertionError("Bar-stool hitbox must retain the Forge VoxelShape height")
    # BukkitSeat adds a flat 0.6 to the seat position and then sinks the small
    # armour stand by its own 0.9875 height so the stand's top-mounted passenger
    # lands back on that point. Those two cancel each other, not the 0.6, so the
    # player's feet end up at `furniture origin + seat.y + 0.6` and each seat y is
    # its cushion height minus 0.6.
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
    hud_offset_powers = (1, 2, 4, 8, 16, 32, 64, 128, 256)
    expected_shaker_hud_providers = [{
        "type": "space",
        "advances": {
            **{chr(0xE410 + index): power / 2
               for index, power in enumerate(hud_offset_powers)},
            **{chr(0xE420 + index): -power / 2
               for index, power in enumerate(hud_offset_powers)},
        },
    }, {
        "type": "bitmap",
        "file": f"{NAMESPACE}:font/shaker/bar.png",
        "ascent": 0,
        "height": 9,
        "chars": [chr(0xE400)],
    }, {
        "type": "bitmap",
        "file": f"{NAMESPACE}:font/shaker/pointer.png",
        "ascent": 3,
        "height": 7,
        "chars": [chr(0xE401)],
    }, {
        "type": "bitmap",
        "file": f"{NAMESPACE}:gui/rhombus.png",
        "ascent": 3,
        "height": 8,
        "chars": [chr(0xE402)],
    }]
    shaker_hud_font = asset_json(
        f"{NAMESPACE}:shaker_hud", "font", paper_asset_roots)
    if (shaker_hud_font is None
            or shaker_hud_font.get("providers") != expected_shaker_hud_providers):
        raise AssertionError(
            "Shaker HUD font must preserve the source bar, pointer and ingredient layout")
    for shaker_texture in ("bar", "pointer"):
        if not asset_exists(
                f"{NAMESPACE}:font/shaker/{shaker_texture}", "textures", ".png"):
            raise AssertionError(f"Missing generated shaker HUD texture: {shaker_texture}")
    if not asset_exists(f"{NAMESPACE}:gui/rhombus", "textures", ".png"):
        raise AssertionError("Missing archived tintable shaker ingredient rhombus")
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

    # String lights are wall-only CE furniture. Native furniture_item selects
    # the clicked horizontal face and rotates one north-authored model onto all
    # four walls; native furniture events also own every dye transformation.
    dye_colors = ("white", "orange", "magenta", "light_blue", "yellow", "lime",
                  "pink", "gray", "light_gray", "cyan", "purple", "blue",
                  "brown", "green", "red", "black")
    expected_hitboxes = [
        {
            "type": "interaction",
            "position": f"{x},-0.25,0.1875",
            "width": 0.375,
            "height": 0.75,
            "can_use_item_on": True,
            "can_be_hit_by_projectile": True,
            "interactive": True,
            "blocks_building": True,
        }
        for x in ("-0.3125", "0", "0.3125")
    ]
    expected_string_sounds = {
        action: f"minecraft:block.chain.{action}"
        for action in ("break", "place", "hit")
    }
    string_ids = {
        f"{NAMESPACE}:string_lights_{color}"
        for color in ("colorless", *dye_colors)
    }
    if string_ids & set(blocks):
        raise AssertionError("String lights must not retain CE custom-block definitions")
    for color in ("colorless", *dye_colors):
        full_id = f"{NAMESPACE}:string_lights_{color}"
        lights = furniture.get(full_id)
        if lights is None:
            raise AssertionError(f"string_lights_{color}: missing CE furniture")
        if lights.get("settings") != {
                "hit_times": 3,
                "sounds": expected_string_sounds,
                "item": full_id,
        }:
            raise AssertionError(
                f"string_lights_{color}: chain furniture settings drifted")
        variants = lights.get("variants", {})
        if set(variants) != {"wall"}:
            raise AssertionError(
                f"string_lights_{color}: must expose only CE's native wall anchor")
        wall = variants["wall"]
        elements = wall.get("elements", [])
        if len(elements) != 1 or wall.get("hitboxes") != expected_hitboxes:
            raise AssertionError(
                f"string_lights_{color}: thin three-part wall interaction shape drifted")
        element = elements[0]
        render_id = element.get("item")
        if (element.get("type") != "item_display"
                or element.get("display_transform") != "none"
                or element.get("position") != "0,0,0.01"
                # Keep CE's 0.01 lighting-safe entity offset, then move the
                # model one pixel up and one pixel wallward: 0.49 - 1/16.
                or element.get("translation") != "0,0.0625,0.4275"
                or any(key in element for key in ("rotation", "scale"))
                or not isinstance(render_id, str)):
            raise AssertionError(
                f"string_lights_{color}: wall item-display transform drifted")
        expected_model = f"{NAMESPACE}:block/deco/string_lights/{color}"
        source_model = (ROOT / "src/main/resources"
                        / f"assets/{NAMESPACE}/models/block/deco/string_lights/{color}.json")
        if not source_model.is_file():
            raise AssertionError(f"string_lights_{color}: archived source model is missing")
        model = render_items.get(render_id, {}).get("model", {})
        if (model.get("type") != "minecraft:model"
                or model.get("path") != expected_model):
            raise AssertionError(
                f"string_lights_{color}: furniture must render its archived source model")
        if lights.get("loot") != {
                "pools": [{
                    "rolls": 1,
                    "entries": [{"type": "furniture_item", "item": full_id}],
                }],
        }:
            raise AssertionError(
                f"string_lights_{color}: CE native furniture loot drifted")
        if (lights.get("behavior") != {
                "type": "glowing_furniture", "lights": ["0,0,0.5 15"]}
                or "behaviors" in lights):
            raise AssertionError(
                f"string_lights_{color}: light must use only CE glowing_furniture")
        if items[full_id].get("behavior") != {
                "type": "furniture_item",
                "furniture": full_id,
                "rules": {
                    "wall": {"rotation": "four", "alignment": "center"},
                },
                "ignore_placer": True,
        }:
            raise AssertionError(
                f"string_lights_{color}: placement must use native CE furniture_item")

        expected_dyes = {dye for dye in dye_colors if dye != color}
        dye_events = lights.get("events", [])
        if len(dye_events) != len(expected_dyes):
            raise AssertionError(
                f"string_lights_{color}: expected {len(expected_dyes)} CE dye events")
        seen_dyes: set[str] = set()
        expected_particle_positions = {
            "0.0": ("<arg:position.x>", "<arg:position.z> + 0.5"),
            "180.0": ("<arg:position.x>", "<arg:position.z> - 0.5"),
            "90.0": ("<arg:position.x> - 0.5", "<arg:position.z>"),
            "-90.0": ("<arg:position.x> + 0.5", "<arg:position.z>"),
        }

        def validate_dye_particle(particle: dict[str, Any], x: str, z: str) -> bool:
            return particle == {
                "type": "particle",
                "particle": "minecraft:happy_villager",
                "x": x,
                "y": "<arg:position.y>",
                "z": z,
                "count": 15,
                "offset_x": 0.5,
                "offset_y": 0.375,
                "offset_z": 0.5,
            }

        for dye_event in dye_events:
            conditions = dye_event.get("conditions", [])
            if (dye_event.get("on") != "right_click"
                    or len(conditions) != 2
                    or conditions[0].get("type") != "match_item"
                    or conditions[1] != {"type": "hand", "hand": "main_hand"}):
                raise AssertionError(
                    f"string_lights_{color}: CE dye event conditions drifted")
            dye_item = conditions[0].get("item", "")
            match = re.fullmatch(r"minecraft:(.+)_dye", dye_item)
            if match is None or match.group(1) not in expected_dyes:
                raise AssertionError(
                    f"string_lights_{color}: unexpected dye event {dye_item}")
            dye = match.group(1)
            if dye in seen_dyes:
                raise AssertionError(
                    f"string_lights_{color}: duplicate CE event for {dye_item}")
            seen_dyes.add(dye)

            event_functions = dye_event.get("functions", [])
            if len(event_functions) != 1 or event_functions[0].get("type") != "if_else":
                raise AssertionError(
                    f"string_lights_{color}: protection must use CE if_else")
            rules = event_functions[0].get("rules", [])
            if len(rules) != 2:
                raise AssertionError(
                    f"string_lights_{color}: CE dye protection branches drifted")
            allowed, denied = rules
            if allowed.get("conditions") != [{"type": "test_flag", "flag": "interact"}]:
                raise AssertionError(
                    f"string_lights_{color}: dye event must use CE interact protection")
            functions = allowed.get("functions", [])
            if [function.get("type") for function in functions] != [
                    "update_interaction_tick", "set_count", "play_sound", "when",
                    "swing_hand", "replace_furniture"]:
                raise AssertionError(
                    f"string_lights_{color}: native dye function sequence drifted")
            if functions[1] != {
                    "type": "set_count", "add": True, "count": -1,
                    "conditions": [{
                        "type": "!equals",
                        "value1": "<arg:player.gamemode>",
                        "value2": "CREATIVE",
                    }],
            }:
                raise AssertionError(
                    f"string_lights_{color}: CE must consume dye outside creative mode")
            if functions[2] != {
                    "type": "play_sound", "sound": "minecraft:item.dye.use",
                    "source": "block"}:
                raise AssertionError(
                    f"string_lights_{color}: CE dye sound drifted")
            particle_switch = functions[3]
            cases = particle_switch.get("cases", [])
            if (particle_switch.get("source") != "<arg:furniture.yaw>"
                    or len(cases) != 4):
                raise AssertionError(
                    f"string_lights_{color}: wall-relative CE particles drifted")
            for case in cases:
                yaw = str(case.get("when"))
                position = expected_particle_positions.get(yaw)
                case_functions = case.get("functions", [])
                if (position is None or len(case_functions) != 1
                        or not validate_dye_particle(
                            case_functions[0], position[0], position[1])):
                    raise AssertionError(
                        f"string_lights_{color}: invalid particle case for yaw {yaw}")
            fallback = particle_switch.get("fallback", [])
            if (len(fallback) != 1 or not validate_dye_particle(
                    fallback[0], "<arg:position.x>", "<arg:position.z>")):
                raise AssertionError(
                    f"string_lights_{color}: CE particle fallback drifted")
            if functions[5] != {
                    "type": "replace_furniture",
                    "furniture": f"{NAMESPACE}:string_lights_{dye}",
                    "variant": "wall",
                    "drop_loot": False,
                    "play_sound": False,
            }:
                raise AssertionError(
                    f"string_lights_{color}: CE replacement target drifted for {dye}")
            if denied != {
                    "functions": [{"type": "update_interaction_tick"}]}:
                raise AssertionError(
                    f"string_lights_{color}: denied dye click must remain claimed")
        if seen_dyes != expected_dyes:
            raise AssertionError(
                f"string_lights_{color}: CE dye coverage is incomplete")

    stale_string_sources = (
        game_package / "furniture/StringLightsFurnitureBehavior.java",
        game_package / "furniture/StringLightsSemantics.java",
        ROOT / "src/paperTest/java/com/github/ysbbbbbb/kaleidoscopetavern/"
               "paper/game/furniture/StringLightsFurnitureBehaviorTest.java",
    )
    if ("StringLightsFurnitureBehavior" in plugin_source
            or "StringLightsBlockBehavior" in plugin_source
            or any(path.exists() for path in stale_string_sources)
            or (game_package / "block/StringLightsBlockBehavior.java").exists()):
        raise AssertionError(
            "String-light placement, glow and dye interactions must remain entirely native CE")

    # Trellis waxing/scraping and both kinds of grapevine shearing are CE block
    # events. Incense interaction is also a CE block event. The global
    # Java block interaction listener is deliberately gone and must not return.
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

    for block_id in (
            f"{NAMESPACE}:grapevine_trellis",
            f"{NAMESPACE}:ice_grapevine_trellis",
            f"{NAMESPACE}:gold_grapevine_trellis"):
        vine_events = blocks[block_id].get("events", [])
        if len(vine_events) != 1:
            raise AssertionError(f"{block_id}: expected exactly one shear event")
        shear = vine_events[0]
        conditions = {condition["type"]: condition for condition in shear["conditions"]}
        functions = {function["type"]: function for function in shear["functions"]}
        loot = functions.get("drop_loot", {}).get("loot", {})
        pools = loot.get("pools", [])
        entries = pools[0].get("entries", []) if len(pools) == 1 else []
        if (shear.get("on") != "right_click"
                or set(conditions) != {"match_item"}
                or conditions["match_item"].get("item") != "minecraft:shears"
                or functions.get("transform_block", {}).get("block")
                != f"{NAMESPACE}:trellis"
                or len(entries) != 1
                or entries[0].get("type") != "item"
                or entries[0].get("item") != f"{NAMESPACE}:grapevine"
                or functions.get("damage_item", {}).get("amount") != 1
                or functions.get("play_sound", {}).get("sound")
                != "minecraft:block.beehive.shear"
                or functions["play_sound"].get("target") != "self"
                or "swing_hand" not in functions
                or "cancel_event" not in functions):
            raise AssertionError(f"{block_id}: shear event drift")

    wild_events = blocks[f"{NAMESPACE}:wild_grapevine"].get("events", [])
    if len(wild_events) != 2:
        raise AssertionError(
            "wild_grapevine: expected active-shear plus already-sheared consume events")
    shear = wild_events[0]
    shear_conditions = {c["type"]: c for c in shear["conditions"]}
    shear_functions = {f["type"]: f for f in shear["functions"]}
    if (shear.get("on") != "right_click"
            or shear_conditions["match_item"].get("item") != "minecraft:shears"
            or shear_conditions["match_block_property"].get("properties") != {"sheared": "false"}
            or "hand" in shear_conditions
            or shear_functions["update_block_property"].get("properties") != {"sheared": "true"}
            or shear_functions["play_sound"].get("sound") != "minecraft:entity.sheep.shear"
            or shear_functions["play_sound"].get("target") != "self"
            or "damage_item" not in shear_functions
            or "swing_hand" not in shear_functions
            or "cancel_event" not in shear_functions):
        raise AssertionError("wild_grapevine: shear event drift")
    consumed = wild_events[1]
    consumed_conditions = {c["type"]: c for c in consumed["conditions"]}
    if (consumed.get("on") != "right_click"
            or consumed_conditions.get("match_item", {}).get("item") != "minecraft:shears"
            or consumed_conditions.get("match_block_property", {}).get("properties")
            != {"sheared": "true"}
            or consumed.get("functions") != [{"type": "cancel_event"}]):
        raise AssertionError("wild_grapevine: already-sheared click must only be consumed")

    if any(fid.endswith("_incense") for fid in furniture):
        raise AssertionError("Incense must not retain CE furniture definitions")
    copper_lantern = "minecraft:copper_lantern[hanging=false,waterlogged=false]"
    ordinary_incense_use = {
        "type": "any_of",
        "terms": [
            {
                "type": "!equals",
                "value1": "<arg:player.is_sneaking>",
                "value2": "true",
            },
            {
                "type": "all_of",
                "terms": [
                    {
                        "type": "equals",
                        "value1": "<arg:player.main_hand_item.count>",
                        "value2": "0",
                    },
                    {
                        "type": "equals",
                        "value1": "<arg:player.off_hand_item.count>",
                        "value2": "0",
                    },
                ],
            },
        ],
    }
    expected_incense_events = []
    for before, after, sound in (
            ("false", "true", "minecraft:block.stone_button.click_on"),
            ("true", "false", "minecraft:block.stone_button.click_off")):
        expected_incense_events.append({
            "on": "right_click",
            "conditions": [
                ordinary_incense_use,
                {"type": "match_block_property", "properties": {"open": before}},
                {"type": "test_flag", "flag": "interact"},
            ],
            "functions": [
                {"type": "update_interaction_tick"},
                {
                    "type": "update_block_property",
                    "properties": {"open": after},
                    "update_flags": 2,
                },
                {"type": "play_sound", "sound": sound, "source": "block"},
                {"type": "swing_hand"},
                {"type": "cancel_event"},
            ],
        })
    expected_incense_events.append({
        "on": "right_click",
        "conditions": [
            ordinary_incense_use,
            {"type": "!test_flag", "flag": "interact"},
        ],
        "functions": [
            {"type": "update_interaction_tick"},
            {"type": "cancel_event"},
        ],
    })
    for incense_name, particle_spec in INCENSE_BLOCK_SPECS.items():
        incense_id = f"{NAMESPACE}:{incense_name}"
        definition = blocks.get(incense_id)
        if definition is None:
            raise AssertionError(f"{incense_id}: missing CE block definition")
        states = definition.get("states", {})
        properties = states.get("properties", {})
        if (set(properties) != {"facing", "open", "powered"}
                or properties["facing"].get("type") != "horizontal_direction"
                or properties["open"] != {"type": "boolean", "default": "false"}
                or properties["powered"] != {"type": "boolean", "default": "false"}):
            raise AssertionError(f"{incense_id}: facing/open/powered state schema drifted")
        appearances = states.get("appearances", {})
        variants = states.get("variants", {})
        if len(appearances) != 8 or len(variants) != 16:
            raise AssertionError(
                f"{incense_id}: expected 8 visual appearances and 16 state variants")
        render_helpers = set()
        for appearance in appearances.values():
            renderer = appearance.get("entity_renderer", {})
            if (appearance.get("state") != copper_lantern
                    or appearance.get("transparent") is not True
                    or renderer.get("type") != "item_display"):
                raise AssertionError(
                    f"{incense_id}: must use the released standing copper-lantern carrier")
            render_helpers.add(renderer.get("item"))
        if len(render_helpers) != 2 or None in render_helpers:
            raise AssertionError(
                f"{incense_id}: closed/open directions must share exactly two render items")
        expected_behavior = {
            "type": f"{NAMESPACE}:incense",
            "small_particle": particle_spec[0],
            "large_particle": particle_spec[1],
            "large_particle_y_offset": particle_spec[2],
            "large_particle_y_range": particle_spec[3],
        }
        if definition.get("behavior") != expected_behavior:
            raise AssertionError(
                f"{incense_id}: incense CE behavior config drifted")
        if definition.get("events") != expected_incense_events:
            raise AssertionError(
                f"{incense_id}: manual toggle/protection must use native CE events")
        settings = definition.get("settings", {})
        expected_sounds = {
            action: f"minecraft:block.decorated_pot.{action}"
            for action in ("break", "step", "place", "hit", "fall")
        }
        if (settings.get("hardness") != 0.0
                or settings.get("resistance") != 0.0
                or settings.get("sounds") != expected_sounds
                or "luminance" in settings):
            raise AssertionError(
                f"{incense_id}: source instant-break, sound or non-luminous settings drifted")
        item_behavior = items[incense_id].get("behavior", {})
        if item_behavior != {"type": "block_item", "block": incense_id}:
            raise AssertionError(f"{incense_id}: item must place the CE block directly")

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
        "bell_pendant_lamp": ("chain", 3),
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
        expected_sounds: dict[str, object] = {
            action: f"minecraft:block.{family}.{action}"
            for action in ("break", "place", "hit")
        }
        if family == "glass":
            expected_sounds["place"] = {
                "id": "minecraft:block.glass.place",
                "volume": 1.0,
                "pitch": 0.8,
            }
        if settings.get("hit_times") != hit_times or settings.get("sounds") != expected_sounds:
            raise AssertionError(f"{furniture_id}: source material/break behavior drifted")

    # Table is now a real CE block. CE owns block-item placement, the
    # waterlogged carrier, collision, rendering and loot; Java computes only
    # the source world-axis neighbour topology that configuration cannot express.
    table_id = f"{NAMESPACE}:table"
    table_block = blocks[table_id]
    if table_block.get("behavior") != {
            "type": f"{NAMESPACE}:connected_block",
            "mode": "table",
            "connects": [table_id],
            "axis_property": "table_axis",
            "state_property": "position",
            "topology": {
                "default_axis": "z",
                "perpendicular_to_player": True,
                "allow_cross_axis_singles": True,
                "outputs": {
                    "none": 0,
                    "positive": 1,
                    "negative": 3,
                    "both": 2,
                },
            }}:
        raise AssertionError(
            "Table must keep every family-specific topology value in CE config")
    if items[table_id].get("behavior") != {
            "type": "block_item", "block": table_id}:
        raise AssertionError("Table item placement must be native CE block_item")
    table_states = table_block.get("states", {})
    if table_states.get("properties") != {
            "table_axis": {"type": "axis", "default": "x", "values": ["x", "z"]},
            "position": {"type": "int", "default": 0, "range": "0~3"}}:
        raise AssertionError("Furniture-style table state properties drifted")
    # Endpoint source files are selected declaratively in the opposite slot so
    # the visible tabletop edge meets the neighbour. Topology numbers remain
    # source-compatible and Java does not know model names.
    expected_table_models = {
        ("x", 0): f"{NAMESPACE}:block/deco/table/single",
        ("x", 1): f"{NAMESPACE}:block/deco/table/left",
        ("x", 2): f"{NAMESPACE}:block/deco/table/middle",
        ("x", 3): f"{NAMESPACE}:block/deco/table/right",
        ("z", 0): f"{NAMESPACE}:block/deco/table/single",
        ("z", 1): f"{NAMESPACE}:block/deco/table/left_rot",
        ("z", 2): f"{NAMESPACE}:block/deco/table/middle_rot",
        ("z", 3): f"{NAMESPACE}:block/deco/table/right_rot",
    }
    expected_table_keys = {
        f"position={position},table_axis={axis}"
        for axis in ("x", "z") for position in range(4)
    }
    table_variants = table_states.get("variants", {})
    if set(table_variants) != expected_table_keys:
        raise AssertionError("Table must expose exactly eight axis/endpoint states")
    table_render_ids: dict[tuple[str, int], set[str]] = defaultdict(set)
    for variant_key, variant in table_variants.items():
        props = dict(part.split("=", 1) for part in variant_key.split(","))
        axis = props["table_axis"]
        position = int(props["position"])
        appearance = table_states["appearances"][variant["appearance"]]
        if (appearance.get("state") != "minecraft:barrier"
                or "auto_state" in appearance
                or appearance.get("transparent") is not None):
            raise AssertionError(
                f"table/{variant_key}: must use CE sofa-style barrier rendering")
        renderer = appearance.get("entity_renderer", {})
        render_id = renderer.get("item")
        table_render_ids[(axis, position)].add(render_id)
        if render_items.get(render_id, {}).get("model", {}).get("path") \
                != expected_table_models[(axis, position)]:
            raise AssertionError(f"table/{variant_key}: source model drifted")
        if variant != {"appearance": variant["appearance"]}:
            raise AssertionError(f"table/{variant_key}: unexpected state settings")
    if any(len(ids) != 1 for ids in table_render_ids.values()):
        raise AssertionError("Table states must share their seven render helpers")
    if len({next(iter(ids)) for ids in table_render_ids.values()}) != 7:
        raise AssertionError("Table must retain exactly seven authored source models")

    legacy_table = furniture.get(table_id)
    table_base_variants = {
        "ground",
        *(f"ground_axis_{axis}_position_{position}"
          for axis in ("x", "z") for position in range(1, 4)),
    }
    expected_legacy_table_variants = {
        base if facing == "south" else f"{base}_facing_{facing}"
        for base in table_base_variants
        for facing in ("south", "west", "north", "east")
    }
    if (legacy_table is None
            or "item" in legacy_table.get("settings", {})
            or "loot" in legacy_table
            or legacy_table.get("behavior") != {
                "type": f"{NAMESPACE}:legacy_connected_block_migration"}
            or set(legacy_table.get("variants", {}))
            != expected_legacy_table_variants):
        raise AssertionError(
            "Old table furniture must remain unreachable and migration-only for one release")

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
    pressing_tub_id = f"{NAMESPACE}:pressing_tub"
    legacy_tub = furniture.get(pressing_tub_id)
    if legacy_tub is None:
        raise AssertionError(
            "The old pressing-tub furniture id must remain migration-only so "
            "existing ground and wall saves can be split safely")
    if "item" in legacy_tub.get("settings", {}) or "loot" in legacy_tub:
        raise AssertionError(
            "Legacy pressing-tub furniture must be unreachable from native "
            "placement and must not duplicate target loot")
    if set(legacy_tub.get("variants", {})) != {"ground", "wall"}:
        raise AssertionError(
            "Legacy pressing-tub migration must retain both old anchor variants")
    if legacy_tub.get("behaviors") != [
            {"type": f"{NAMESPACE}:state_furniture"},
            {"type": f"{NAMESPACE}:legacy_pressing_tub_migration"}]:
        raise AssertionError(
            "Legacy pressing-tub furniture must keep only state storage plus "
            "the split migration behavior")

    wall_tub = furniture.get(WALL_PRESSING_TUB_ID)
    if wall_tub is None:
        raise AssertionError(
            "The non-pressable wall tub must be a private native CE furniture")
    if wall_tub.get("settings", {}).get("item") != pressing_tub_id:
        raise AssertionError(
            "Wall pressing-tub furniture must map back to the public tub item")
    if set(wall_tub.get("variants", {})) != {"wall"}:
        raise AssertionError(
            "The active wall tub must expose no ground fallback variant")
    expected_wall_loot = {
        "pools": [{
            "rolls": 1,
            "entries": [{
                "type": "furniture_item",
                "item": pressing_tub_id,
            }],
        }],
    }
    if wall_tub.get("loot") != expected_wall_loot:
        raise AssertionError(
            "Wall pressing-tub item drops must be CE-configured")
    expected_wall_behaviors = [
        {"type": f"{NAMESPACE}:state_furniture"},
        {
            "type": f"{NAMESPACE}:station_visual_furniture",
            "max_elements": 17,
            "view_range": 1.25,
        },
        {"type": f"{NAMESPACE}:station_interaction_furniture"},
    ]
    if wall_tub.get("behaviors") != expected_wall_behaviors:
        raise AssertionError(
            "Wall pressing-tub runtime-sized state must use the shared generic "
            "furniture adapters, not a dedicated placement/lifecycle behavior")
    wall_variant = wall_tub["variants"]["wall"]
    if (wall_variant.get("elements")
            != legacy_tub["variants"]["wall"].get("elements")):
        raise AssertionError(
            "Active and legacy wall tubs must preserve the same authored model")
    expected_wall_hitboxes = [{
        "type": "interaction",
        "position": "0,-0.5,0.5",
        "width": 1.0,
        "height": 1.0,
        "can_use_item_on": True,
        "can_be_hit_by_projectile": True,
        "interactive": True,
        "blocks_building": True,
    }]
    for position in (
            "-0.25,-0.5,0.75", "0.25,-0.5,0.75",
            "-0.25,-0.25,0.5", "0.25,-0.25,0.5",
            "-0.25,0,0.25", "0.25,0,0.25"):
        expected_wall_hitboxes.append({
            "type": "shulker",
            "position": position,
            "peek": 0,
            "interaction_entity": False,
            "can_use_item_on": True,
            "can_be_hit_by_projectile": True,
            "interactive": False,
            "blocks_building": True,
            "scale": 0.5,
        })
    if wall_variant.get("hitboxes") != expected_wall_hitboxes:
        raise AssertionError(
            "Wall pressing-tub selection and tilted-shell collision must be CE-configured")

    pressing_block = blocks[pressing_tub_id]
    pressing_states = pressing_block.get("states", {})
    expected_pressing_properties = {
        "facing": {
            "type": "horizontal_direction",
            "default": "north",
            "values": ["north", "east", "south", "west"],
        },
        "waterlogged": {"type": "boolean", "default": "false"},
    }
    if pressing_states.get("properties") != expected_pressing_properties:
        raise AssertionError(
            "Ground pressing-tub state must leave facing/waterlogging to CE's "
            "hard-coded property behaviors and contain no wall-only tilt state")
    pressing_variants = pressing_states.get("variants", {})
    expected_pressing_variant_keys = {
        f"facing={facing},waterlogged={waterlogged}"
        for facing in ("north", "east", "south", "west")
        for waterlogged in ("false", "true")
    }
    if set(pressing_variants) != expected_pressing_variant_keys:
        raise AssertionError(
            "Ground pressing tub must expose exactly 8 facing/fluid states")
    pressing_appearances = pressing_states.get("appearances", {})
    pressing_yaw = {"north": 180, "east": 90, "south": None, "west": 270}
    referenced_pressing_appearances: set[str] = set()
    for variant_key, variant in pressing_variants.items():
        properties = dict(part.split("=", 1) for part in variant_key.split(","))
        appearance_name = (
            f"ground_{properties['facing']}_{properties['waterlogged']}")
        if variant.get("appearance") != appearance_name:
            raise AssertionError(
                f"Pressing tub {variant_key} maps to the wrong renderer")
        appearance = pressing_appearances.get(appearance_name, {})
        referenced_pressing_appearances.add(appearance_name)
        expected_carrier = (
            "minecraft:cut_copper_slab"
            f"[type=bottom,waterlogged={properties['waterlogged']}]"
        )
        if (appearance.get("state") != expected_carrier
                or appearance.get("transparent") is not True):
            raise AssertionError(
                f"Pressing tub {variant_key} must use its released "
                "bottom cut-copper-slab carrier")
        expected_renderer = {
            "type": "item_display",
            "item": f"{NAMESPACE}:_render/pressing_tub/ff80d8a10a",
            "display_transform": "none",
            "shadow_radius": 0,
            "view_range": 1.25,
        }
        yaw = pressing_yaw[properties["facing"]]
        if yaw is not None:
            expected_renderer["rotation"] = f"0,{yaw},0"
        if appearance.get("entity_renderer") != expected_renderer:
            raise AssertionError(
                f"Pressing tub {variant_key} model renderer drifted")
    if (referenced_pressing_appearances != set(pressing_appearances)
            or len(pressing_appearances) != 8):
        raise AssertionError(
            "Ground pressing-tub appearance set contains stale wall states")
    if pressing_block.get("behaviors") != {
            "type": f"{NAMESPACE}:pressing_tub_block"}:
        raise AssertionError(
            "Pressing tub must route only the fallOn/state API gap through Java")
    pressing_settings = pressing_block.get("settings", {})
    if (pressing_settings.get("item") != pressing_tub_id
            or pressing_settings.get("hardness") != 0.8
            or pressing_settings.get("resistance") != 0.8
            or pressing_settings.get("push_reaction") != "BLOCK"
            or pressing_settings.get("tags") != ["minecraft:mineable/axe"]
            or pressing_settings.get("destroy_stages")
            != {"template": "internal:destroy_stages"}
            or pressing_settings.get("map_color") != 13
            or pressing_settings.get("instrument") != "guitar"
            or pressing_settings.get("burnable") is not True
            or pressing_settings.get("burn_chance") != 5
            or pressing_settings.get("fire_spread_chance") != 20):
        raise AssertionError("Pressing-tub survival mining settings drifted")
    if items[pressing_tub_id].get("behaviors") != [
            {
                "type": "ground_block_item",
                "block": pressing_tub_id,
            },
            {
                "type": "ceiling_block_item",
                "block": pressing_tub_id,
            },
            {
                "type": "furniture_item",
                "furniture": WALL_PRESSING_TUB_ID,
                "rules": {
                    "wall": {"rotation": "four", "alignment": "center"},
                },
            },
            ]:
        raise AssertionError(
            "Pressing-tub upright/wall routing must be an ordered native CE "
            "item-behavior chain")
    for token in (
            "displayYaw = 0",
            "tiltDisplay(facing, x, y, z)",
            "tiltRotation(facing, yRotation, zRotation)",
            "ITEM_X_DEGREES"):
        if token not in visual_factory_source:
            raise AssertionError(
                "Wall pressing-tub contents must retain the complete source "
                f"four-direction transform; missing {token}")

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

    configured_state: dict[str, tuple[int, dict[str, Any]]] = {}
    state_type = f"{NAMESPACE}:state_furniture"
    for furniture_id, definition in furniture.items():
        all_behaviors = list(definition.get("behaviors", []))
        single_behavior = definition.get("behavior")
        if single_behavior is not None:
            all_behaviors.append(single_behavior)
        state_behaviors = [
            (index, behavior) for index, behavior in enumerate(all_behaviors)
            if behavior.get("type") == state_type
        ]
        if len(state_behaviors) > 1:
            raise AssertionError(f"{furniture_id}: duplicate state_furniture behaviors")
        if state_behaviors:
            configured_state[furniture_id] = state_behaviors[0]

    expected_state_ids = {
        f"{NAMESPACE}:{block_id}" for block_id in EXPECTED_STATE_FURNITURE
    }
    if set(configured_state) != expected_state_ids:
        missing = sorted(expected_state_ids - set(configured_state))
        unexpected = sorted(set(configured_state) - expected_state_ids)
        raise AssertionError(
            "State furniture coverage drift: "
            f"missing={missing}, unexpected={unexpected}")
    for furniture_id, (index, behavior) in configured_state.items():
        if index != 0 or behavior != {"type": state_type}:
            raise AssertionError(
                f"{furniture_id}: state_furniture must be the exact index-zero behavior")

    lifecycle_type = f"{NAMESPACE}:lifecycle_furniture"
    configured_lifecycle: dict[str, list[tuple[int, dict[str, Any]]]] = {}
    for furniture_id, definition in furniture.items():
        all_behaviors = list(definition.get("behaviors", []))
        single_behavior = definition.get("behavior")
        if single_behavior is not None:
            all_behaviors.append(single_behavior)
        lifecycle_behaviors = [
            (index, behavior) for index, behavior in enumerate(all_behaviors)
            if behavior.get("type") == lifecycle_type
        ]
        if lifecycle_behaviors:
            configured_lifecycle[furniture_id] = lifecycle_behaviors

    expected_lifecycle_ids = {
        f"{NAMESPACE}:{block_id}" for block_id in EXPECTED_LIFECYCLE_FURNITURE
    }
    if set(configured_lifecycle) != expected_lifecycle_ids:
        missing = sorted(expected_lifecycle_ids - set(configured_lifecycle))
        unexpected = sorted(set(configured_lifecycle) - expected_lifecycle_ids)
        raise AssertionError(
            "Lifecycle furniture coverage drift: "
            f"missing={missing}, unexpected={unexpected}")
    for block_id, expected_channels in EXPECTED_LIFECYCLE_FURNITURE.items():
        furniture_id = f"{NAMESPACE}:{block_id}"
        actual = configured_lifecycle[furniture_id]
        expected_start = 1 if block_id in EXPECTED_STATE_FURNITURE else 0
        expected_indices = list(range(expected_start,
                                      expected_start + len(expected_channels)))
        actual_indices = [index for index, _ in actual]
        actual_behaviors = [behavior for _, behavior in actual]
        expected_behaviors = [
            {"type": lifecycle_type, "channel": channel}
            for channel in expected_channels
        ]
        if actual_indices != expected_indices or actual_behaviors != expected_behaviors:
            raise AssertionError(
                f"{furniture_id}: lifecycle order drifted: "
                f"indices={actual_indices}, behaviors={actual_behaviors}")

    board_text_type = f"{NAMESPACE}:board_text_furniture"
    expected_board_text = {
        block_id: 8
        for block_id in EXPECTED_STATE_FURNITURE
        if block_id.endswith("_sandwich_board")
    }
    configured_board_text: dict[str, tuple[int, dict[str, Any]]] = {}
    for furniture_id, definition in furniture.items():
        all_behaviors = list(definition.get("behaviors", []))
        single_behavior = definition.get("behavior")
        if single_behavior is not None:
            all_behaviors.append(single_behavior)
        matches = [
            (index, behavior) for index, behavior in enumerate(all_behaviors)
            if behavior.get("type") == board_text_type
        ]
        if len(matches) > 1:
            raise AssertionError(f"{furniture_id}: duplicate board_text_furniture behaviors")
        if matches:
            configured_board_text[furniture_id] = matches[0]
    expected_board_ids = {
        f"{NAMESPACE}:{block_id}" for block_id in expected_board_text
    }
    if set(configured_board_text) != expected_board_ids:
        missing = sorted(expected_board_ids - set(configured_board_text))
        unexpected = sorted(set(configured_board_text) - expected_board_ids)
        raise AssertionError(
            "Board text furniture coverage drift: "
            f"missing={missing}, unexpected={unexpected}")
    for block_id, max_lines in expected_board_text.items():
        furniture_id = f"{NAMESPACE}:{block_id}"
        index, behavior = configured_board_text[furniture_id]
        expected_behavior = {
            "type": board_text_type,
            "max_lines": max_lines,
            "view_range": 0.75,
        }
        if index != 2 or behavior != expected_behavior:
            raise AssertionError(
                f"{furniture_id}: board_text_furniture order/config drifted: "
                f"index={index}, behavior={behavior}")

    animated_visual_type = f"{NAMESPACE}:animated_item_furniture"
    expected_animated_visuals = {
        "shaker": ("shaker", 2),
        **{
            f"{color}_bar_stool": ("bar_stool", 1)
            for color in FURNITURE_COLORS
        },
    }
    configured_animated_visuals: dict[str, tuple[int, dict[str, Any]]] = {}
    for furniture_id, definition in furniture.items():
        all_behaviors = list(definition.get("behaviors", []))
        single_behavior = definition.get("behavior")
        if single_behavior is not None:
            all_behaviors.append(single_behavior)
        matches = [
            (index, behavior) for index, behavior in enumerate(all_behaviors)
            if behavior.get("type") == animated_visual_type
        ]
        if len(matches) > 1:
            raise AssertionError(
                f"{furniture_id}: duplicate animated_item_furniture behaviors")
        if matches:
            configured_animated_visuals[furniture_id] = matches[0]
    expected_animated_ids = {
        f"{NAMESPACE}:{block_id}" for block_id in expected_animated_visuals
    }
    if set(configured_animated_visuals) != expected_animated_ids:
        missing = sorted(expected_animated_ids - set(configured_animated_visuals))
        unexpected = sorted(set(configured_animated_visuals) - expected_animated_ids)
        raise AssertionError(
            "Animated visual furniture coverage drift: "
            f"missing={missing}, unexpected={unexpected}")
    for block_id, (channel, max_elements) in expected_animated_visuals.items():
        furniture_id = f"{NAMESPACE}:{block_id}"
        index, behavior = configured_animated_visuals[furniture_id]
        expected_behavior = {
            "type": animated_visual_type,
            "channel": channel,
            "max_elements": max_elements,
            "view_range": 1.25,
        }
        if index != 1 or behavior != expected_behavior:
            raise AssertionError(
                f"{furniture_id}: animated_item_furniture order/config drifted: "
                f"index={index}, behavior={behavior}")

    bottle_type = f"{NAMESPACE}:bottle_furniture"
    configured_bottles: dict[str, tuple[int, dict[str, Any]]] = {}
    for furniture_id, definition in furniture.items():
        all_behaviors = list(definition.get("behaviors", []))
        single_behavior = definition.get("behavior")
        if single_behavior is not None:
            all_behaviors.append(single_behavior)
        matches = [
            (index, behavior) for index, behavior in enumerate(all_behaviors)
            if behavior.get("type") == bottle_type
        ]
        if len(matches) > 1:
            raise AssertionError(f"{furniture_id}: duplicate bottle_furniture behaviors")
        if matches:
            configured_bottles[furniture_id] = matches[0]
    expected_bottle_ids = {
        f"{NAMESPACE}:{block_id}" for block_id in EXPECTED_BOTTLE_FURNITURE
    }
    if set(configured_bottles) != expected_bottle_ids:
        missing = sorted(expected_bottle_ids - set(configured_bottles))
        unexpected = sorted(set(configured_bottles) - expected_bottle_ids)
        raise AssertionError(
            "Bottle CE interaction coverage drift: "
            f"missing={missing}, unexpected={unexpected}")
    for block_id in EXPECTED_BOTTLE_FURNITURE:
        furniture_id = f"{NAMESPACE}:{block_id}"
        index, behavior = configured_bottles[furniture_id]
        expected_index = (
            (1 if block_id in EXPECTED_STATE_FURNITURE else 0)
            + len(EXPECTED_LIFECYCLE_FURNITURE.get(block_id, ()))
        )
        if index != expected_index or behavior != {"type": bottle_type}:
            raise AssertionError(
                f"{furniture_id}: bottle_furniture order/config drifted: "
                f"index={index}, behavior={behavior}")

    expected_station_visuals = {
        "barrel": ({
            "type": f"{NAMESPACE}:station_visual_furniture",
            "max_elements": 17,
            "view_range": 2.5,
        }, 2),
        WALL_PRESSING_TUB: ({
            "type": f"{NAMESPACE}:station_visual_furniture",
            "max_elements": 17,
            "view_range": 1.25,
        }, 1),
    }
    for block_id, (expected_visual, expected_index) in expected_station_visuals.items():
        configured_behaviors = list(
            furniture[f"{NAMESPACE}:{block_id}"].get("behaviors", []))
        single_behavior = furniture[f"{NAMESPACE}:{block_id}"].get("behavior")
        if single_behavior is not None:
            configured_behaviors.append(single_behavior)
        visual_behaviors = [
            behavior for behavior in configured_behaviors
            if behavior.get("type") == f"{NAMESPACE}:station_visual_furniture"
        ]
        if visual_behaviors != [expected_visual]:
            raise AssertionError(
                f"{block_id}: CE virtual station visual coverage drifted")
        if configured_behaviors.index(visual_behaviors[0]) != expected_index:
            raise AssertionError(
                f"{block_id}: station visual controller order drifted")

    station_interaction_type = f"{NAMESPACE}:station_interaction_furniture"
    configured_station_interactions: dict[str, tuple[int, dict[str, Any]]] = {}
    for furniture_id, definition in furniture.items():
        all_behaviors = list(definition.get("behaviors", []))
        single_behavior = definition.get("behavior")
        if single_behavior is not None:
            all_behaviors.append(single_behavior)
        matches = [
            (index, behavior) for index, behavior in enumerate(all_behaviors)
            if behavior.get("type") == station_interaction_type
        ]
        if len(matches) > 1:
            raise AssertionError(
                f"{furniture_id}: duplicate station_interaction_furniture behaviors")
        if matches:
            configured_station_interactions[furniture_id] = matches[0]
    expected_station_interaction_ids = {
        f"{NAMESPACE}:{block_id}"
        for block_id in EXPECTED_STATION_INTERACTION_FURNITURE
    }
    if set(configured_station_interactions) != expected_station_interaction_ids:
        missing = sorted(expected_station_interaction_ids
                         - set(configured_station_interactions))
        unexpected = sorted(set(configured_station_interactions)
                            - expected_station_interaction_ids)
        raise AssertionError(
            "Station CE interaction coverage drift: "
            f"missing={missing}, unexpected={unexpected}")
    expected_station_interaction_indices = {
        "barrel": 3,
        "shaker": 2,
        "empty_glassware": 1,
        WALL_PRESSING_TUB: 2,
    }
    for block_id, expected_index in expected_station_interaction_indices.items():
        furniture_id = f"{NAMESPACE}:{block_id}"
        index, behavior = configured_station_interactions[furniture_id]
        if (index != expected_index
                or behavior != {"type": station_interaction_type}):
            raise AssertionError(
                f"{furniture_id}: station interaction order/config drifted: "
                f"index={index}, behavior={behavior}")

    redstone_type = f"{NAMESPACE}:redstone_furniture"
    for furniture_id, definition in furniture.items():
        all_behaviors = list(definition.get("behaviors", []))
        single_behavior = definition.get("behavior")
        if single_behavior is not None:
            all_behaviors.append(single_behavior)
        if any(behavior.get("type") == redstone_type for behavior in all_behaviors):
            raise AssertionError(
                f"{furniture_id}: storage launchers are CE blocks; "
                "redstone_furniture must not be generated")

    configured_ticking: dict[str, list[dict[str, Any]]] = {}
    ticking_type = f"{NAMESPACE}:ticking_furniture"
    for furniture_id, definition in furniture.items():
        all_behaviors = list(definition.get("behaviors", []))
        single_behavior = definition.get("behavior")
        if single_behavior is not None:
            all_behaviors.append(single_behavior)
        ticking_behaviors = [
            behavior for behavior in all_behaviors
            if behavior.get("type") == ticking_type
        ]
        if ticking_behaviors:
            configured_ticking[furniture_id] = ticking_behaviors

    expected_ticking_ids = {
        f"{NAMESPACE}:{block_id}" for block_id in EXPECTED_TICKING_FURNITURE
    }
    if set(configured_ticking) != expected_ticking_ids:
        missing = sorted(expected_ticking_ids - set(configured_ticking))
        unexpected = sorted(set(configured_ticking) - expected_ticking_ids)
        raise AssertionError(
            "Ticking furniture coverage drift: "
            f"missing={missing}, unexpected={unexpected}")
    for block_id, schedules in EXPECTED_TICKING_FURNITURE.items():
        furniture_id = f"{NAMESPACE}:{block_id}"
        expected_behaviors = [
            {"type": ticking_type, **schedule}
            for schedule in schedules
        ]
        if configured_ticking[furniture_id] != expected_behaviors:
            raise AssertionError(
                f"{furniture_id}: ticking behaviors must be exactly {expected_behaviors!r}")

    storage_slot_counts = {
        "glassware_holder": 4,
    }
    storage_interaction_type = f"{NAMESPACE}:storage_interaction_furniture"
    configured_storage_interactions: dict[str, tuple[int, dict[str, Any]]] = {}
    for furniture_id, definition in furniture.items():
        all_behaviors = list(definition.get("behaviors", []))
        single_behavior = definition.get("behavior")
        if single_behavior is not None:
            all_behaviors.append(single_behavior)
        matches = [
            (index, behavior) for index, behavior in enumerate(all_behaviors)
            if behavior.get("type") == storage_interaction_type
        ]
        if len(matches) > 1:
            raise AssertionError(
                f"{furniture_id}: duplicate storage_interaction_furniture behaviors")
        if matches:
            configured_storage_interactions[furniture_id] = matches[0]
    expected_storage_interaction_ids = {
        f"{NAMESPACE}:{block_id}"
        for block_id in EXPECTED_STORAGE_INTERACTION_FURNITURE
    }
    if set(configured_storage_interactions) != expected_storage_interaction_ids:
        missing = sorted(expected_storage_interaction_ids
                         - set(configured_storage_interactions))
        unexpected = sorted(set(configured_storage_interactions)
                            - expected_storage_interaction_ids)
        raise AssertionError(
            "Storage CE interaction coverage drift: "
            f"missing={missing}, unexpected={unexpected}")
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
        expected_display_behaviors = [{
            "type": "display_item_furniture",
            "data_key": f"{NAMESPACE}:display_slot_{slot}",
            "sounds": {
                "put": "minecraft:block.decorated_pot.insert",
                "take": "minecraft:block.decorated_pot.insert_fail",
            },
        } for slot in range(slot_count)]
        if behaviors != expected_display_behaviors:
            raise AssertionError(
                f"{storage_id}: native CE controllers must own storage without duplicate sprites")
        visual_behaviors = [
            behavior for behavior in configured_behaviors
            if behavior.get("type") == f"{NAMESPACE}:storage_visual_furniture"
        ]
        expected_visual = {
            "type": f"{NAMESPACE}:storage_visual_furniture",
            "slots": slot_count,
        }
        if visual_behaviors != [expected_visual]:
            raise AssertionError(
                f"{storage_id}: CE virtual storage visual coverage drifted")
        display_indices = [
            index for index, behavior in enumerate(configured_behaviors)
            if behavior.get("type") == "display_item_furniture"
        ]
        interaction_index, interaction_behavior = configured_storage_interactions[
            f"{NAMESPACE}:{storage_id}"]
        if (not display_indices
                or interaction_index != display_indices[0] - 1
                or interaction_behavior != {"type": storage_interaction_type}):
            raise AssertionError(
                f"{storage_id}: CE storage interaction must immediately precede native slots")
        visual_index = configured_behaviors.index(visual_behaviors[0])
        if not display_indices or visual_index != display_indices[-1] + 1:
            raise AssertionError(
                f"{storage_id}: storage visual controller must follow its native slot controllers")

    connection_names = SOFA_CONNECTIONS
    legacy_connection_variants = {
        "ground" if connection == "single"
        else f"ground_connection_{connection}"
        for connection in connection_names
    }
    # Authored block-model rotations must be compensated for the final
    # +180-degree item turn performed by Minecraft's ItemDisplay renderer.
    facing_rotations = {
        "north": "0,180,0", "east": "0,90,0",
        "south": None, "west": "0,270,0",
    }
    sofa_connect_ids = [
        SHARED_SOFA_ID,
        *(f"{NAMESPACE}:{name}" for name in sorted(SOFA_BLOCKS)),
    ]

    shared = blocks.get(SHARED_SOFA_ID)
    if shared is None:
        raise AssertionError("Shared tint-source sofa block is missing")
    expected_corner_topology = {
        "outputs": {
            "none": "single",
            "left": "right",
            "right": "left",
            "both": "middle",
            "front_left": "right_corner",
            "front_left_with_right": "left",
            "front_right": "left_corner",
            "front_right_with_left": "right",
        },
        "compatibility": {
            "left_perpendicular": ["single", "right", "right_corner"],
            "right_perpendicular": ["single", "left", "left_corner"],
            "front_left_excluded": "left_corner",
            "front_right_excluded": "right_corner",
        },
    }
    expected_shared_behaviors = [
        {
            "type": f"{NAMESPACE}:connected_block",
            "mode": "corner",
            "connects": sofa_connect_ids,
            "state_property": "connection",
            "topology": expected_corner_topology,
        },
        {"type": "seat_block", "seats": ["0,-0.1,0 180"]},
        {"type": "tint_source_block", "drop_item": True},
    ]
    if shared.get("behaviors") != expected_shared_behaviors:
        raise AssertionError(
            "Shared sofa must delegate colour/drop/seat ownership to CE")
    shared_states = shared.get("states", {})
    if shared_states.get("properties") != {
            "connection": {
                "type": "string", "default": "single",
                "values": ["single", "left", "left_corner", "middle",
                           "right", "right_corner"],
            },
            "facing": {
                "type": "horizontal_direction", "default": "north",
                "values": ["north", "east", "south", "west"],
            }}:
        raise AssertionError("Shared sofa state product drifted")
    expected_shared_keys = {
        f"connection={connection},facing={facing}"
        for connection in connection_names
        for facing in ("north", "east", "south", "west")
    }
    shared_variants = shared_states.get("variants", {})
    if set(shared_variants) != expected_shared_keys:
        raise AssertionError("Shared sofa must expose exactly 24 active states")
    tint_render_ids: dict[str, set[str]] = defaultdict(set)
    for variant_key, variant in shared_variants.items():
        props = dict(part.split("=", 1) for part in variant_key.split(","))
        appearance = shared_states["appearances"][variant["appearance"]]
        if appearance.get("state") != "minecraft:barrier":
            raise AssertionError(f"Shared sofa/{variant_key}: carrier drifted")
        renderer = appearance.get("entity_renderer", {})
        if renderer.get("rotation") != facing_rotations[props["facing"]]:
            raise AssertionError(f"Shared sofa/{variant_key}: rotation drifted")
        if renderer.get("tint_source") != "minecraft:dyed_color":
            raise AssertionError(f"Shared sofa/{variant_key}: tint source missing")
        render_id = renderer.get("item")
        tint_render_ids[props["connection"]].add(render_id)
        render_model = render_items.get(render_id, {}).get("model", {})
        if (render_model.get("path") !=
                f"{NAMESPACE}:block/deco/sofa/tint/{props['connection']}"
                or render_model.get("tints") != [{
                    "type": "minecraft:dye", "default": 16_777_215}]):
            raise AssertionError(
                f"Shared sofa/{variant_key}: tintable render model drifted")
    if set(tint_render_ids) != set(connection_names) \
            or any(len(ids) != 1 for ids in tint_render_ids.values()):
        raise AssertionError("Shared sofa must use six tintable render items")

    tint_model_root = (
        ROOT / f"src/paper/pack/resourcepack/assets/{NAMESPACE}/models"
        / "block/deco/sofa/tint"
    )
    for connection in connection_names:
        wrapper = json.loads((tint_model_root / f"{connection}.json")
                             .read_text(encoding="utf-8-sig"))
        if wrapper.get("parent") != (
                f"{NAMESPACE}:block/deco/sofa/tint/base/{connection}"):
            raise AssertionError(f"Tint sofa {connection}: wrapper drifted")
        base = json.loads((tint_model_root / "base" / f"{connection}.json")
                          .read_text(encoding="utf-8-sig"))
        tinted_faces = [
            face
            for element in base.get("elements", [])
            for face in element.get("faces", {}).values()
            if face.get("texture") == "#texture"
        ]
        if not tinted_faces or any(face.get("tintindex") != 0
                                   for face in tinted_faces):
            raise AssertionError(
                f"Tint sofa {connection}: every upholstery face needs tintindex 0")

    for color in sorted(FURNITURE_COLORS):
        sofa_name = f"{color}_sofa"
        sofa_id = f"{NAMESPACE}:{sofa_name}"
        item = items[sofa_id]
        if item.get("behavior") != {
                "type": "block_item", "block": SHARED_SOFA_ID}:
            raise AssertionError(
                f"{sofa_name}: public item must place the shared sofa")
        if item.get("data", {}).get("dyed_color") != SOFA_DYE_COLORS[color]:
            raise AssertionError(f"{sofa_name}: fixed dye colour drifted")

        alias = blocks.get(sofa_id)
        if alias is None or "behavior" in alias or "behaviors" in alias:
            raise AssertionError(
                f"{sofa_name}: old id must be a passive migration alias")
        alias_states = alias.get("states", {})
        expected_alias_keys = {
            f"facing={facing}" for facing in
            ("north", "east", "south", "west")
        }
        if set(alias_states.get("variants", {})) != expected_alias_keys:
            raise AssertionError(
                f"{sofa_name}: migration alias must consume only four states")
        settings = alias.get("settings", {})
        if settings.get("item") != sofa_id:
            raise AssertionError(f"{sofa_name}: alias pickup item drifted")
        loot_entries = alias.get("loot", {}).get("pools", [{}])[0] \
            .get("entries", [])
        if loot_entries != [{"type": "item", "item": sofa_id}]:
            raise AssertionError(f"{sofa_name}: alias loot drifted")

        legacy = furniture.get(sofa_id)
        if (legacy is None
                or "item" in legacy.get("settings", {})
                or "loot" in legacy
                or legacy.get("behavior") != {
                    "type": f"{NAMESPACE}:legacy_connected_block_migration"}
                or set(legacy.get("variants", {}))
                != legacy_connection_variants):
            raise AssertionError(
                f"{sofa_name}: old furniture must remain migration-only")

    sofa_state_total = len(shared_variants) + sum(
        len(blocks[f"{NAMESPACE}:{name}"]["states"]["variants"])
        for name in SOFA_BLOCKS
    )
    if sofa_state_total != 88:
        raise AssertionError(
            f"Sofa family must use 24 active + 64 alias states, found "
            f"{sofa_state_total}")

    counter_id = f"{NAMESPACE}:bar_counter"
    counter = blocks[counter_id]
    if counter.get("behavior") != {
            "type": f"{NAMESPACE}:connected_block",
            "mode": "corner",
            "connects": [counter_id],
            "state_property": "connection",
            "topology": expected_corner_topology}:
        raise AssertionError(
            "Bar counter topology/output ownership must stay in CE config")
    if items[counter_id].get("behavior") != {
            "type": "block_item", "block": counter_id}:
        raise AssertionError("Bar counter placement must use native CE block_item")
    counter_states = counter.get("states", {})
    expected_counter_keys = {
        f"connection={connection},facing={facing}"
        for connection in connection_names
        for facing in ("east", "north", "south", "west")
    }
    if set(counter_states.get("variants", {})) != expected_counter_keys:
        raise AssertionError("Bar counter must retain all 24 source states")
    counter_render_ids: dict[str, set[str]] = defaultdict(set)
    for variant_key, variant in counter_states["variants"].items():
        props = dict(part.split("=", 1) for part in variant_key.split(","))
        appearance = counter_states["appearances"][variant["appearance"]]
        if (appearance.get("state") != "minecraft:barrier"
                or "auto_state" in appearance
                or appearance.get("transparent") is not None):
            raise AssertionError(
                f"bar_counter/{variant_key}: must use CE sofa-style barrier rendering")
        renderer = appearance.get("entity_renderer", {})
        if renderer.get("rotation") != facing_rotations[props["facing"]]:
            raise AssertionError(f"bar_counter/{variant_key}: rotation drifted")
        render_id = renderer.get("item")
        counter_render_ids[props["connection"]].add(render_id)
        expected_model = (
            f"{NAMESPACE}:block/deco/bar_counter/{props['connection']}")
        if render_items.get(render_id, {}).get("model", {}).get("path") \
                != expected_model:
            raise AssertionError(f"bar_counter/{variant_key}: source model drifted")
    if any(len(ids) != 1 for ids in counter_render_ids.values()):
        raise AssertionError("Bar counter facings must share six render items")
    legacy_counter = furniture.get(counter_id)
    if (legacy_counter is None
            or "item" in legacy_counter.get("settings", {})
            or "loot" in legacy_counter
            or legacy_counter.get("behavior") != {
                "type": f"{NAMESPACE}:legacy_connected_block_migration"}
            or set(legacy_counter.get("variants", {}))
            != legacy_connection_variants):
        raise AssertionError(
            "Old bar-counter furniture must be unreachable and migration-only")

    for expected_token in (
            "EXPECTED_ITEMS = 660",
            "EXPECTED_BLOCKS = 60",
            "EXPECTED_FURNITURE = 137"):
        if expected_token not in plugin_source:
            raise AssertionError(
                f"Runtime CE content count guard is stale: {expected_token}")

    storage_facing_rotations = {
        "east": "0,90,0", "north": "0,180,0",
        "south": None, "west": "0,270,0",
    }
    expected_storage_orientations = {
        "north": {
            "position_yaw": 0, "model_yaw": 0,
            "local_x": "1-x", "local_z": "z", "reverse_slots": False,
        },
        "east": {
            "position_yaw": -90, "model_yaw": -90,
            "local_x": "1-z", "local_z": "1-x", "reverse_slots": False,
        },
        "south": {
            "position_yaw": 180, "model_yaw": 180,
            "local_x": "x", "local_z": "1-z", "reverse_slots": False,
        },
        "west": {
            "position_yaw": 90, "model_yaw": 90,
            "local_x": "z", "local_z": "x", "reverse_slots": False,
        },
    }
    for storage_id, (slot_count, blocklist, carrier_type) in STORAGE_BLOCK_SPECS.items():
        full_id = f"{NAMESPACE}:{storage_id}"
        legacy_cabinet = storage_id in {"bar_cabinet", "glass_bar_cabinet"}
        if full_id in furniture and not legacy_cabinet:
            raise AssertionError(
                f"{storage_id}: migrated storage must not remain active furniture")
        if legacy_cabinet and full_id not in furniture:
            raise AssertionError(
                f"{storage_id}: old furniture id must remain for one-release migration")

        definition = blocks[full_id]
        actual_behavior = (definition.get("behaviors")
                           if storage_id in {
                               "bar_cabinet", "glass_bar_cabinet", "cellar_cabinet"
                           } else definition.get("behavior"))
        if storage_id in {"bar_cabinet", "glass_bar_cabinet", "cellar_cabinet"}:
            if not isinstance(actual_behavior, list) or len(actual_behavior) != 2:
                raise AssertionError(
                    f"{storage_id}: connected storage must compose topology + storage")
            topology, configured_storage = actual_behavior
            if topology != {
                    "type": f"{NAMESPACE}:connected_block",
                    "mode": "linear",
                    "connects": [full_id],
                    "state_property": "position",
                    "topology": {
                        "outputs": {
                            "none": "single",
                            "left": "right",
                            "right": "left",
                            "both": "middle",
                        },
                    }}:
                raise AssertionError(
                    f"{storage_id}: linear connection values must live in CE config")
        else:
            configured_storage = actual_behavior

        if not isinstance(configured_storage, dict):
            raise AssertionError(f"{storage_id}: missing configured storage behavior")
        if (configured_storage.get("type") != f"{NAMESPACE}:storage"
                or configured_storage.get("data_key")
                != f"{NAMESPACE}:storage_{storage_id}"
                or configured_storage.get("render_item_prefix")
                != f"{NAMESPACE}:_render/storage/"
                or configured_storage.get("view_range") != 1.25
                or len(configured_storage.get("slots", [])) != slot_count):
            raise AssertionError(
                f"{storage_id}: generic multi-slot storage config drifted")

        orientations = configured_storage.get("orientations")
        expected_orientations = {
            key: dict(value) for key, value in expected_storage_orientations.items()
        }
        if legacy_cabinet:
            expected_orientations["east"]["reverse_slots"] = True
            expected_orientations["west"]["reverse_slots"] = True
        if storage_id == "cellar_cabinet":
            expected_orientations["east"]["model_yaw"] = 90
            expected_orientations["west"]["model_yaw"] = 270
        if orientations != expected_orientations:
            raise AssertionError(
                f"{storage_id}: source-space click/model orientation drifted: {orientations!r}")
        # The -90-degree pitch used only by cellar bottles changes the effective
        # east/west longitudinal axis. Those two model yaws need a half-turn;
        # every upright/tilted packet display keeps the source position yaw.
        for facing, orientation in orientations.items():
            expected_offset = (180 if storage_id == "cellar_cabinet"
                               and facing in {"east", "west"} else 0)
            actual_offset = (
                orientation["model_yaw"] - orientation["position_yaw"]
            ) % 360
            if actual_offset != expected_offset:
                raise AssertionError(
                    f"{storage_id}/{facing}: packet model yaw offset must be "
                    f"{expected_offset}, found {actual_offset}")

        selector = configured_storage.get("selector", {})
        expected_selector_type = {
            "bar_cabinet": "split", "glass_bar_cabinet": "split",
            "cellar_cabinet": "grid", "tilted_rack": "split",
            "circular_rack": "radial", "holder": "single",
        }[storage_id]
        if selector.get("type") != expected_selector_type:
            raise AssertionError(f"{storage_id}: click selector is not config-owned")
        interaction = configured_storage.get("interaction", {})
        if (not interaction.get("allowed_items")
                or "consume_in_creative" not in interaction
                or "sounds" not in interaction):
            raise AssertionError(
                f"{storage_id}: item rules/sounds must live in CE configuration")
        if blocklist is not None and "blocked_items" not in interaction:
            raise AssertionError(
                f"{storage_id}: configured blocklist key was not flattened into the behavior")
        if legacy_cabinet and (not interaction.get("exclusive_items")
                or interaction.get("exclusive_slot") != 0
                or interaction.get("fallback_take") is not True
                or interaction.get("fallback_put") is not True):
            raise AssertionError(
                f"{storage_id}: irregular two-slot behavior must be config-owned")
        if not legacy_cabinet and "launch" not in configured_storage:
            raise AssertionError(
                f"{storage_id}: redstone launch parameters must be config-owned")
        if storage_id == "circular_rack":
            particle = configured_storage.get("particle", {})
            if (particle.get("alternate_min_x") != 0.625
                    or particle.get("alternate_max_x") != 0.875
                    or particle.get("alternate_min_z") != 0.625
                    or particle.get("alternate_max_z") != 0.875):
                raise AssertionError(
                    "Circular-rack edge particle ranges must remain in CE config")

        properties = definition.get("states", {}).get("properties", {})
        if legacy_cabinet:
            expected_properties = {"facing", "position"}
        elif storage_id == "cellar_cabinet":
            expected_properties = {"facing", "powered", "position"}
        else:
            expected_properties = {"facing", "powered"}
        if set(properties) != expected_properties:
            raise AssertionError(
                f"{storage_id}: storage state properties drifted: "
                f"{sorted(properties)}")
        if "position" in expected_properties:
            position = properties["position"]
            if (position.get("default") != "single"
                    or set(position.get("values", []))
                    != {"single", "left", "middle", "right"}):
                raise AssertionError(
                    f"{storage_id}: connected cabinet position property drifted")

        states = definition.get("states", {})
        appearances = states.get("appearances", {})
        variants = states.get("variants", {})
        expected_appearances = 16 if (
            legacy_cabinet or storage_id == "cellar_cabinet") else 4
        expected_variants = (16 if legacy_cabinet
                             else 32 if storage_id == "cellar_cabinet"
                             else 8)
        if (len(appearances) != expected_appearances
                or len(variants) != expected_variants):
            raise AssertionError(
                f"{storage_id}: storage appearance/state coverage drifted: "
                f"{len(appearances)}/{len(variants)}")

        render_ids: set[str] = set()
        for appearance in appearances.values():
            if (appearance.get("state") != "minecraft:barrier"
                    or "auto_state" in appearance
                    or appearance.get("transparent") is not None):
                raise AssertionError(
                    f"{storage_id}: must use CE sofa-style barrier rendering")
            renderer = appearance.get("entity_renderer", {})
            if renderer.get("type") != "item_display":
                raise AssertionError(
                    f"{storage_id}: authored model must use an ItemDisplay renderer")
            render_ids.add(renderer.get("item"))

        expected_render_items = 4 if (
            legacy_cabinet or storage_id == "cellar_cabinet") else 1
        if len(render_ids) != expected_render_items:
            raise AssertionError(
                f"{storage_id}: expected {expected_render_items} shared base "
                f"render items, found {render_ids}")

        rotations = storage_facing_rotations
        powered_suffix = ",powered=false" if "powered" in properties else ""
        position_suffix = ",position=single" if "position" in properties else ""
        for facing, expected_rotation in rotations.items():
            variant_key = f"facing={facing}{position_suffix}{powered_suffix}"
            variant = variants.get(variant_key)
            if variant is None:
                raise AssertionError(
                    f"{storage_id}: missing canonical state {variant_key}")
            appearance = appearances[variant["appearance"]]
            actual_rotation = appearance["entity_renderer"].get("rotation")
            if actual_rotation != expected_rotation:
                raise AssertionError(
                    f"{storage_id}: {facing} model rotation drifted: "
                    f"{actual_rotation!r}")

        settings = definition.get("settings", {})
        expected_mining_tag = (
            "minecraft:mineable/axe"
            if storage_id in {
                "bar_cabinet", "glass_bar_cabinet", "cellar_cabinet"
            } else "minecraft:mineable/pickaxe")
        if (settings.get("hardness") != 2.5
                or settings.get("resistance") != 2.5
                or settings.get("push_reaction") != "NORMAL"
                or settings.get("tags") != [expected_mining_tag]
                or settings.get("destroy_stages") != {
                    "template": "internal:destroy_stages"}):
            raise AssertionError(
                f"{storage_id}: source mining settings or CE destroy stages drifted")
        expected_luminance = 14 if storage_id == "circular_rack" else None
        if settings.get("luminance") != expected_luminance:
            raise AssertionError(f"{storage_id}: source luminance drifted")
        if legacy_cabinet:
            if (settings.get("map_color") != 13
                    or settings.get("instrument") != "guitar"
                    or settings.get("burnable") is not True
                    or settings.get("burn_chance") != 5
                    or settings.get("fire_spread_chance") != 20):
                raise AssertionError(
                    f"{storage_id}: wood cabinet settings drifted")
        if items[full_id].get("behavior") != {
                "type": "block_item", "block": full_id}:
            raise AssertionError(
                f"{storage_id}: placement must use CE's native block_item")

        if legacy_cabinet:
            legacy = furniture[full_id]
            expected_legacy_variants = {
                "ground", "ground_position_left",
                "ground_position_middle", "ground_position_right",
            }
            expected_legacy_behaviors = [
                {"type": f"{NAMESPACE}:legacy_connected_block_migration"},
                {"type": "display_item_furniture",
                 "data_key": f"{NAMESPACE}:display_slot_0"},
                {"type": "display_item_furniture",
                 "data_key": f"{NAMESPACE}:display_slot_1"},
            ]
            if ("item" in legacy.get("settings", {})
                    or "loot" in legacy
                    or set(legacy.get("variants", {}))
                    != expected_legacy_variants
                    or legacy.get("behaviors") != expected_legacy_behaviors):
                raise AssertionError(
                    f"{storage_id}: old furniture must be unreachable, retain "
                    "two native CE persistence slots and migrate only")
            legacy_render_ids = {
                variant["elements"][0]["item"]
                for variant in legacy["variants"].values()
            }
            if legacy_render_ids != render_ids:
                raise AssertionError(
                    f"{storage_id}: active block and migration furniture must "
                    "share the same four render helpers")

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
