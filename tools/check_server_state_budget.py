#!/usr/bin/env python3
"""Validate the CraftEngine server-side custom block-state budget.

CraftEngine injects a fixed number of real server-side block states during
server startup. Every generated custom block variant consumes one slot from
the global pool shared by every enabled CraftEngine resource project.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path
from typing import Any


DEFAULT_BLOCKS = Path("src/paper/pack/configuration/blocks.json")
DEFAULT_CAPACITY = 2_000
DEFAULT_RESERVE = 500
CAPACITY_STEP = 1_000


def positive_int(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("value must be non-negative")
    return parsed


def property_value_count(name: str, specification: Any) -> int:
    if not isinstance(specification, dict):
        raise ValueError(f"Property {name!r} must be an object")

    values = specification.get("values")
    if isinstance(values, list):
        if not values:
            raise ValueError(f"Property {name!r} has an empty values list")
        return len(values)

    if specification.get("type") == "boolean":
        return 2

    raw_range = specification.get("range")
    if raw_range is not None:
        match = re.fullmatch(r"\s*(-?\d+)\s*~\s*(-?\d+)\s*", str(raw_range))
        if match is None:
            raise ValueError(f"Property {name!r} has invalid range {raw_range!r}")
        lower, upper = map(int, match.groups())
        if upper < lower:
            raise ValueError(f"Property {name!r} has descending range {raw_range!r}")
        return upper - lower + 1

    raise ValueError(
        f"Cannot determine the number of values for property {name!r}: "
        f"{specification!r}"
    )


def block_state_count(block_id: str, definition: Any) -> int:
    if not isinstance(definition, dict):
        raise ValueError(f"Block {block_id!r} must be an object")

    states = definition.get("states")
    if states is None:
        return 1
    if not isinstance(states, dict):
        raise ValueError(f"Block {block_id!r}.states must be an object")

    variants = states.get("variants")
    if isinstance(variants, dict):
        if not variants:
            raise ValueError(f"Block {block_id!r} has an empty variants map")
        return len(variants)

    properties = states.get("properties", {})
    if not isinstance(properties, dict):
        raise ValueError(f"Block {block_id!r}.states.properties must be an object")

    count = 1
    for property_name, specification in properties.items():
        count *= property_value_count(property_name, specification)
    return count


def load_state_counts(blocks_path: Path) -> dict[str, int]:
    document = json.loads(blocks_path.read_text(encoding="utf-8-sig"))
    blocks = document.get("blocks")
    if not isinstance(blocks, dict):
        raise ValueError(f"{blocks_path} must contain an object at key 'blocks'")
    return {
        block_id: block_state_count(block_id, definition)
        for block_id, definition in blocks.items()
    }


def read_craftengine_capacity(config_path: Path) -> int:
    """Read block.serverside-blocks without requiring a YAML dependency."""

    block_indent: int | None = None
    for line_number, raw_line in enumerate(
        config_path.read_text(encoding="utf-8-sig").splitlines(), start=1
    ):
        content = raw_line.split("#", 1)[0].rstrip()
        if not content.strip():
            continue

        indent = len(content) - len(content.lstrip(" "))
        stripped = content.strip()

        if indent == 0:
            key = stripped.split(":", 1)[0].strip()
            block_indent = indent if key == "block" else None
            continue

        if block_indent is None or indent <= block_indent:
            continue

        key, separator, raw_value = stripped.partition(":")
        if separator and key.strip() in {"serverside-blocks", "serverside_blocks"}:
            value = raw_value.strip().replace("_", "")
            if not re.fullmatch(r"\d+", value):
                raise ValueError(
                    f"{config_path}:{line_number}: invalid serverside-blocks "
                    f"value {raw_value.strip()!r}"
                )
            return int(value)

    raise ValueError(f"{config_path} does not contain block.serverside-blocks")


def rounded_capacity(required: int) -> int:
    return max(CAPACITY_STEP, math.ceil(required / CAPACITY_STEP) * CAPACITY_STEP)


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Count generated CraftEngine server-side block states and verify "
            "that the configured global pool leaves a safety reserve."
        )
    )
    parser.add_argument("--blocks", type=Path, default=DEFAULT_BLOCKS)
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--capacity", type=positive_int)
    source.add_argument("--craftengine-config", type=Path)
    parser.add_argument("--reserve", type=positive_int, default=DEFAULT_RESERVE)
    parser.add_argument("--top", type=positive_int, default=12)
    return parser.parse_args()


def main() -> int:
    arguments = parse_arguments()

    try:
        counts = load_state_counts(arguments.blocks)
        if arguments.craftengine_config is not None:
            capacity = read_craftengine_capacity(arguments.craftengine_config)
            capacity_source = str(arguments.craftengine_config)
        else:
            capacity = (
                arguments.capacity
                if arguments.capacity is not None
                else DEFAULT_CAPACITY
            )
            capacity_source = "declared project deployment capacity"
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"CraftEngine state-budget validation failed: {error}", file=sys.stderr)
        return 2

    tavern_states = sum(counts.values())
    required_with_reserve = tavern_states + arguments.reserve
    recommendation = rounded_capacity(required_with_reserve)
    remaining = capacity - tavern_states

    print("CraftEngine server-side block-state budget")
    print(f"  Tavern generated states: {tavern_states}")
    print(f"  Capacity ({capacity_source}): {capacity}")
    print(f"  Reserved for other CE resources/future changes: {arguments.reserve}")
    print(f"  Remaining before reserve: {remaining}")

    if arguments.top:
        print("  Largest state consumers:")
        for block_id, count in sorted(
            counts.items(), key=lambda entry: (-entry[1], entry[0])
        )[: arguments.top]:
            print(f"    {count:4d}  {block_id}")

    if capacity < required_with_reserve:
        print(
            "\nInsufficient CraftEngine server-side block-state capacity.",
            file=sys.stderr,
        )
        print(
            f"Set block.serverside-blocks to at least {recommendation} in "
            "plugins/CraftEngine/config.yml, then perform a complete server "
            "restart. Do not use /ce reload for this setting and do not delete "
            "cache/custom_block_states.json.",
            file=sys.stderr,
        )
        return 1

    print(
        f"Budget passed: {capacity - required_with_reserve} states remain "
        "after the requested reserve."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
