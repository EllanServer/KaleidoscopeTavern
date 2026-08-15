#!/usr/bin/env python3
"""Exact barrel source-parity repair for PR #68.

The previous side-only fix moved open_r1 from x=1 to x=15.  That mirrored the
support across the barrel mouth and created the wrong visual direction.  The
actual source-parity repair has two parts:

1. Keep open_r1 on the Forge-mod side: x=1, original z direction and
   the -39.998183678 degree combined rotation.
2. Fix the gap between the barrel body and the open lid by using the exact
   open_r2 transform computed from the original parent/child matrices instead
   of the approximate "0,3.3225,0.3131" / "72.5,0,0" values.

Usage:
    python fix_barrel_source_exact.py --dry-run
    python fix_barrel_source_exact.py            # apply edits only
    python fix_barrel_source_exact.py --build    # apply edits and run Gradle
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent

ASSET_STAGE = ROOT / (
    "src/buildTools/java/com/github/ysbbbbbb/kaleidoscopetavern/"
    "buildtools/migration/assets/AssetMigrationStage.java"
)
FURNITURE_BUILDER = ROOT / (
    "src/buildTools/java/com/github/ysbbbbbb/kaleidoscopetavern/"
    "buildtools/migration/furniture/FurnitureBuilder.java"
)
PACK_CONFIG_RULES = ROOT / (
    "src/buildTools/java/com/github/ysbbbbbb/kaleidoscopetavern/"
    "buildtools/validation/PackConfigRules.java"
)
MODEL_TEST = ROOT / (
    "src/paperTest/java/com/github/ysbbbbbb/kaleidoscopetavern/"
    "paper/resource/BarrelSupportModelTest.java"
)
OLD_SCRIPT = ROOT / "fix_barrel_support_side_only.py"

ASSET_EDITS = [
    (
        'supportStrip.add("from", numbers(new double[] {15, 28.215627824, -1.72625203}));',
        'supportStrip.add("from", numbers(new double[] {1, 28.215627824, -1.72625203}));',
    ),
    (
        'supportStrip.add("to", numbers(new double[] {15, 30.215627824, 18.27374797}));',
        'supportStrip.add("to", numbers(new double[] {1, 30.215627824, 18.27374797}));',
    ),
    (
        'supportRotation.add("origin", numbers(new double[] {15, 28.215627824, -1.72625203}));',
        'supportRotation.add("origin", numbers(new double[] {1, 28.215627824, -1.72625203}));',
    ),
    (
        "// only moves laterally from x=1 to x=15; the authored z direction and rotation pivot stay unchanged.",
        "// stays on the source x=1 side; the gap is closed by the exact open_r2 transform.",
    ),
]

FURNITURE_EDITS = [
    (
        '"0,3.3225,0.3131"',
        '"0,2.495967497,0.440264912"',
    ),
    (
        '"72.5,0,0"',
        '"72.501658,0,0"',
    ),
]

RULES_EDITS = [
    (
        '"0,3.8225,0.3131"',
        '"0,2.995967,0.440265"',
    ),
    (
        '"72.5,0,0"',
        '"72.501658,0,0"',
    ),
]

TEST_EDITS = [
    (
        '\\"from\\":[14.99,28.215627824,-1.72625203]',
        '\\"from\\":[0.99,28.215627824,-1.72625203]',
    ),
    (
        '\\"to\\":[15.01,30.215627824,18.27374797]',
        '\\"to\\":[1.01,30.215627824,18.27374797]',
    ),
    (
        '\\"origin\\":[15,28.215627824,-1.72625203]',
        '\\"origin\\":[1,28.215627824,-1.72625203]',
    ),
    (
        '"open_r1 must move to the right side without flipping front-back"',
        '"open_r1 must stay on the source x=1 side while the lid transform closes the gap"',
    ),
]


def normalize(text: str) -> str:
    return text.replace("\r\n", "\n")


def apply_edits(path: Path, edits, label: str, dry_run: bool) -> bool:
    if not path.is_file():
        raise SystemExit(f"FAIL {label}: file does not exist: {path}")

    raw = path.read_bytes()
    text = normalize(raw.decode("utf-8"))
    crlf = "\r\n" in raw.decode("utf-8", errors="ignore")
    updated = text
    applied = []
    verified = []

    for old, new in edits:
        if old in updated:
            updated = updated.replace(old, new)
            applied.append(old)
        elif new not in updated:
            raise SystemExit(
                f"FAIL {label}: neither old nor corrected fragment found: {new!r}"
            )
        else:
            verified.append(new)

    if updated == text:
        print(f"OK   {label}: already correct ({len(verified)} markers)")
        return False

    print(f"{'WOULD FIX' if dry_run else 'FIX'} {label}: {len(applied)} edit(s)")
    if not dry_run:
        output = updated
        if crlf:
            output = output.replace("\n", "\r\n")
        path.write_bytes(output.encode("utf-8"))
    return True


def remove_old_script(dry_run: bool) -> bool:
    if not OLD_SCRIPT.exists():
        print("OK   remove fix_barrel_support_side_only.py: already absent")
        return False
    print("WOULD DELETE fix_barrel_support_side_only.py" if dry_run
          else "DELETE fix_barrel_support_side_only.py")
    if not dry_run:
        OLD_SCRIPT.unlink()
    return True


def apply_all(dry_run: bool) -> int:
    changed = False
    changed |= apply_edits(ASSET_STAGE, ASSET_EDITS, "AssetMigrationStage.java", dry_run)
    changed |= apply_edits(FURNITURE_BUILDER, FURNITURE_EDITS, "FurnitureBuilder.java", dry_run)
    changed |= apply_edits(PACK_CONFIG_RULES, RULES_EDITS, "PackConfigRules.java", dry_run)
    changed |= apply_edits(MODEL_TEST, TEST_EDITS, "BarrelSupportModelTest.java", dry_run)
    changed |= remove_old_script(dry_run)
    print("dry run complete" if dry_run else "edits applied")
    return 0 if changed or dry_run else 0


def run_gradle(*args: str) -> None:
    gradlew = ROOT / "gradlew.bat"
    print(f"RUN {gradlew} {' '.join(args)}")
    subprocess.run([str(gradlew), *args], check=True, cwd=str(ROOT))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true", help="show changes without writing")
    parser.add_argument("--build", action="store_true", help="apply edits then run Gradle checks/build")
    args = parser.parse_args()

    if args.dry_run:
        apply_all(dry_run=True)
        return 0

    apply_all(dry_run=False)

    if args.build:
        run_gradle("migrateLegacyContent", "validatePack")
        run_gradle("clean", "build")

    return 0


if __name__ == "__main__":
    sys.exit(main())