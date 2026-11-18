"""Restore the exact Android assets from a full local CatsCo checkout."""

import argparse
from hashlib import sha256
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Path to the complete local CatsCo Android project")
    source = parser.parse_args().source.resolve()
    target = Path(__file__).resolve().parents[1]
    source_resources = source / "app/src/main/res"
    target_resources = target / "app/src/main/res"

    fonts = json.loads((target / "design-assets/fonts/manifest.json").read_text(encoding="utf-8"))
    icons = json.loads((target / "design-assets/icons/manifest.json").read_text(encoding="utf-8"))
    resources = {
        Path("font", font["resource"]): font["sha256"] for font in fonts["files"]
    }
    resources.update({
        Path("drawable", icon["file"]): icon["sha256"] for icon in icons["localAndroidResources"]
    })

    files = {}
    for relative, expected in resources.items():
        original = source_resources / relative
        if not original.is_file():
            parser.exit(1, f"Asset missing: {original}\n")
        data = original.read_bytes()
        checked = data.replace(b"\r\n", b"\n") if relative.suffix == ".xml" else data
        if sha256(checked).hexdigest() != expected:
            parser.exit(1, f"Asset version differs from the manifest: {original}\n")
        files[relative] = data

    for relative, data in files.items():
        output = target_resources / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
    print(f"Restored {len(fonts['files'])} font files and {len(icons['localAndroidResources'])} Android icons.")


if __name__ == "__main__":
    main()
