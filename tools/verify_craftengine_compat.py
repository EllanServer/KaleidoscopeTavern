#!/usr/bin/env python3
"""Validate CraftEngine source contracts used outside its stable public API.

Compilation catches public signature changes. This check covers the private
reflection bridge and the server-side visual-state groups consumed by the
generated Tavern pack, so a source-compatible but behavior-breaking CE dev
change fails the compatibility job as well.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BLOCKS = ROOT / "src/paper/pack/configuration/blocks.json"


def read_required(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing CraftEngine source file: {path}")
    return path.read_text(encoding="utf-8")


def collect_visual_states(value: object, auto: set[str], fixed: set[str]) -> None:
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "auto_state":
                if isinstance(child, str):
                    auto.add(child)
                elif isinstance(child, dict):
                    auto.add(str(child.get("type", "solid")))
            elif key == "state" and isinstance(child, str):
                fixed.add(child.removeprefix("minecraft:"))
            collect_visual_states(child, auto, fixed)
    elif isinstance(value, list):
        for child in value:
            collect_visual_states(child, auto, fixed)


def require_pattern(text: str, pattern: str, label: str) -> None:
    if re.search(pattern, text, re.MULTILINE | re.DOTALL) is None:
        raise SystemExit(f"CraftEngine dev no longer provides required contract: {label}")


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify_craftengine_compat.py <craft-engine-source>")

    ce = Path(sys.argv[1]).resolve()
    properties = read_required(ce / "gradle.properties")
    require_pattern(
        properties,
        r"^project_version\s*=\s*26\.8(?:[-.]|$)",
        "the expected 26.8 dev line",
    )

    display_controller = read_required(
        ce
        / "bukkit/src/main/java/net/momirealms/craftengine/bukkit/entity/furniture/behavior"
        / "DisplayItemFurnitureBehaviorTemplate.java"
    )
    require_pattern(
        display_controller,
        r"\bItem\s+savedItem\s*;",
        "DisplayItemFurnitureController.savedItem",
    )
    require_pattern(
        display_controller,
        r"private\s+void\s+saveDisplayItem\s*\(\s*@Nullable\s+Item\s+item\s*\)",
        "DisplayItemFurnitureController.saveDisplayItem(Item)",
    )

    block_config = json.loads(BLOCKS.read_text(encoding="utf-8"))
    auto_states: set[str] = set()
    fixed_states: set[str] = set()
    collect_visual_states(block_config, auto_states, fixed_states)

    auto_group_source = read_required(
        ce / "core/src/main/java/net/momirealms/craftengine/core/block/AutoStateGroup.java"
    )
    enum_declaration = auto_group_source.split("private final", 1)[0]
    declared_groups = set(re.findall(r'"([a-z0-9_]+)"', enum_declaration))
    missing_groups = sorted(auto_states - declared_groups)
    if missing_groups:
        raise SystemExit(
            "CraftEngine dev removed visual-state groups used by Tavern: "
            + ", ".join(missing_groups)
        )

    mappings = read_required(
        ce
        / "common-files/src/main/resources/resources/internal/configuration/mappings.yml"
    )
    mapped_sources = {
        match.group(1).removeprefix("minecraft:")
        for match in re.finditer(r"^[ \t]{2,}([^#:\n][^:\n]*):[ \t]*[^\n]+$", mappings, re.MULTILINE)
    }
    # Fixed visual states are deliberately forced by Tavern. If CE no longer
    # releases them in its internal mapping pool they may steal a visible
    # vanilla state or be rejected during pack load.
    missing_fixed = sorted(state for state in fixed_states if state not in mapped_sources)
    if missing_fixed:
        raise SystemExit(
            "CraftEngine dev no longer releases fixed visual states used by Tavern: "
            + ", ".join(missing_fixed)
        )

    print(
        "CraftEngine dev contracts verified: "
        f"{len(auto_states)} auto-state groups, {len(fixed_states)} fixed states, "
        "and the display-slot reflection bridge."
    )


if __name__ == "__main__":
    main()
