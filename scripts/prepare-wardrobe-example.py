#!/usr/bin/env python3
"""Build a local-only resource pack from the user's extracted PMX fixtures (stdlib only)."""
import argparse
import io
import json
from pathlib import Path
import zipfile


def unique(root, pack, filename):
    matches = sorted((root / pack).rglob(filename))
    if len(matches) != 1:
        raise ValueError(f"Expected one {pack}/{filename}, found {len(matches)}")
    return matches[0]


def archive(model, replacement=None):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", zipfile.ZIP_DEFLATED) as output:
        output.write(model, "model.pmx")
        for path in sorted(model.parent.rglob("*")):
            if not path.is_file() or path.is_symlink() or path.suffix.lower() not in {
                ".png", ".jpg", ".jpeg", ".bmp", ".tga", ".sph", ".spa"
            }:
                continue
            source = replacement if replacement and path.name == "Dress_Sapphire.jpg" else path
            output.write(source, path.relative_to(model.parent).as_posix())
    return result.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, required=True, help="Extracted pack_1 ... pack_9 directory")
    parser.add_argument("--output", type=Path, required=True, help="Local resource-pack directory, outside Git")
    parser.add_argument("--enable", action="store_true", help="Enable this pack in the parent test client's options.txt")
    args = parser.parse_args()
    root = args.fixtures.resolve()
    output = args.output.resolve()
    if args.enable and output.parent.name != "resourcepacks":
        raise ValueError("--enable requires --output <test-client>/resourcepacks/<pack-name>")
    body = unique(root, "pack_7", "TDA Base Edit by Ayuchan.pmx")
    short = unique(root, "pack_4", "Dress_ShortSleeves.pmx")
    long = unique(root, "pack_4", "Dress_LongSleeves.pmx")
    rose = unique(root, "pack_4", "Dress_Rose.jpg")
    dress = unique(root, "pack_3", "Dress by Ayuchan513.pmx")
    hair = unique(root, "pack_8", "13.pmx")
    # Keep original texture bytes and PMX geometry. The alternate archive only substitutes
    # the dress texture; it preloads a second atlas sprite, never a second rendered mesh.
    models = {"body": (body, None), "short": (short, None), "short_rose": (short, rose),
              "long": (long, None), "ayuchan": (dress, None), "hair": (hair, None)}
    assets = output / "assets/kasuga_wardrobe/models"
    assets.mkdir(parents=True, exist_ok=True)
    inventory = {}
    for name, (model, replacement) in models.items():
        (assets / f"{name}.mmd.zip").write_bytes(archive(model, replacement))
        (assets / f"{name}.mmd.json").write_text(json.dumps({"model_scale": 0.1}), encoding="utf-8")
        inventory[name] = str(model.relative_to(root))
    (assets / "model_proxy.json").write_text(json.dumps({"kasuga_wardrobe": [
        f"models/{name}.mmd.zip" for name in models
    ]}, indent=2), encoding="utf-8")
    (output / "pack.mcmeta").write_text(json.dumps({"pack": {
        "pack_format": 34, "description": "Kasuga wardrobe example - local user fixtures"
    }}, indent=2), encoding="utf-8")
    (output / "wardrobe-fixtures.json").write_text(json.dumps(inventory, indent=2), encoding="utf-8")
    if args.enable:
        options = output.parent.parent / "options.txt"
        lines = options.read_text(encoding="utf-8").splitlines() if options.exists() else []
        index = next((i for i, line in enumerate(lines) if line.startswith("resourcePacks:")), None)
        packs = json.loads(lines[index].split(":", 1)[1]) if index is not None else []
        entry = "file/" + output.name
        if entry not in packs:
            packs.append(entry)
        line = "resourcePacks:" + json.dumps(packs)
        if index is None:
            lines.append(line)
        else:
            lines[index] = line
        options.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Created {output} from {len(models)} local model variants; assets must stay outside Git.")


if __name__ == "__main__":
    main()
