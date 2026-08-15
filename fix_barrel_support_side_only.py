#!/usr/bin/env python3
"""One-shot repair for PR #68's barrel support strip.

The previous fix mirrored the support strip around the model centre
(x=8, z=8), which also reversed its front-back direction.  The strip only
needs a lateral move from x=1 to x=15: the authored z direction and the
combined -39.998183678 degree rotation must be preserved.

The script rewrites three files:

* AssetMigrationStage.java        -- the migration source of truth
* barrel_body.json                -- the generated model (refreshed here so
                                     validation/build never run with the old
                                     coordinates if migration is skipped)
* BarrelSupportModelTest.java     -- regression assertions

Running the script twice is safe: once the correct values are present the
old patterns no longer match and every target marker is verified instead.
"""

from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent

JAVA_PATH = ROOT / (
    "src/buildTools/java/com/github/ysbbbbbb/kaleidoscopetavern/"
    "buildtools/migration/assets/AssetMigrationStage.java"
)
JSON_PATH = ROOT / (
    "src/paper/pack/resourcepack/assets/kaleidoscope_tavern/"
    "models/furniture/barrel_body.json"
)
TEST_PATH = ROOT / (
    "src/paperTest/java/com/github/ysbbbbbb/kaleidoscopetavern/"
    "paper/resource/BarrelSupportModelTest.java"
)

# (old, new) replacements. Each old fragment must match the PR #68 tree once
# per edit; a second run finds the new fragment instead and skips the edit.
JAVA_EDITS = [
    (
        'supportStrip.add("from", numbers(new double[] {15, 28.215627824, -2.27374797}));',
        'supportStrip.add("from", numbers(new double[] {15, 28.215627824, -1.72625203}));',
    ),
    (
        'supportStrip.add("to", numbers(new double[] {15, 30.215627824, 17.72625203}));',
        'supportStrip.add("to", numbers(new double[] {15, 30.215627824, 18.27374797}));',
    ),
    (
        'supportRotation.add("origin", numbers(new double[] {15, 28.215627824, 17.72625203}));',
        'supportRotation.add("origin", numbers(new double[] {15, 28.215627824, -1.72625203}));',
    ),
    (
        'supportRotation.addProperty("angle", 39.998183678);',
        'supportRotation.addProperty("angle", -39.998183678);',
    ),
    (
        "// 75 degree open group, so its final source angle is about 40 degrees.",
        "// 75 degree open group, so its final source angle is about -40 degrees.",
    ),
    (
        "// must be mirrored around the model centre (x=8, z=8).",
        "// only moves laterally from x=1 to x=15; the authored z direction and "
        "rotation pivot stay unchanged.",
    ),
]

JSON_EDITS = [
    ("-2.27374797", "-1.72625203"),
    (
        '''      "to": [
        15.01,
        30.215627824,
        17.72625203
      ],''',
        '''      "to": [
        15.01,
        30.215627824,
        18.27374797
      ],''',
    ),
    (
        '''        "origin": [
          15,
          28.215627824,
          17.72625203
        ],''',
        '''        "origin": [
          15,
          28.215627824,
          -1.72625203
        ],''',
    ),
    ('"angle": 39.998183678', '"angle": -39.998183678'),
]

TEST_EDITS = [
    ('\\"from\\":[14.99,28.215627824,-2.27374797]',
     '\\"from\\":[14.99,28.215627824,-1.72625203]'),
    ('\\"to\\":[15.01,30.215627824,17.72625203]',
     '\\"to\\":[15.01,30.215627824,18.27374797]'),
    ('\\"origin\\":[15,28.215627824,17.72625203]',
     '\\"origin\\":[15,28.215627824,-1.72625203]'),
    ('\\"angle\\":39.998183678', '\\"angle\\":-39.998183678'),
    (
        '"open_r1 must be mirrored to the correct side of the CE furniture"',
        '"open_r1 must move to the right side without flipping front-back"',
    ),
    (
        '"open_r1 must preserve the source combined 75° - 35° tilt"',
        '"open_r1 must preserve the source combined tilt direction and magnitude"',
    ),
]


def apply_edits(path: Path, edits, label: str) -> bool:
    if not path.is_file():
        print(f"SKIP {label}: {path} does not exist")
        return False

    raw = path.read_bytes()
    text = raw.decode("utf-8")
    crlf = "\r\n" in text
    text = text.replace("\r\n", "\n")
    updated = text
    applied = []
    verified = []

    for old, new in edits:
        if old in updated:
            updated = updated.replace(old, new)
            applied.append(old)
        elif new not in updated:
            raise SystemExit(
                f"FAIL {label}: neither the old nor the corrected fragment was "
                f"found: {new!r}"
            )
        else:
            verified.append(new)

    if updated == text:
        print(f"OK   {label}: already corrected ({len(verified)} markers)")
        return False

    output = updated
    if crlf:
        output = output.replace("\n", "\r\n")
    path.write_bytes(output.encode("utf-8"))
    print(f"FIX  {label}: applied {len(applied)} edit(s)")
    return True


def main() -> int:
    changed = False
    changed |= apply_edits(JAVA_PATH, JAVA_EDITS, "AssetMigrationStage.java")
    changed |= apply_edits(JSON_PATH, JSON_EDITS, "barrel_body.json")
    changed |= apply_edits(TEST_PATH, TEST_EDITS, "BarrelSupportModelTest.java")
    print("done" if changed else "nothing to do")
    return 0


if __name__ == "__main__":
    sys.exit(main())
