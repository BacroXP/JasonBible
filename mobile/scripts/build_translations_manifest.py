#!/usr/bin/env python3
"""Build the on-demand translation manifest and the release upload folder.

The app ships with KJV and the Hebrew WLC only. Every other module lives in
mobile/translations/ and is published as a GitHub release asset, so the
manifest must carry everything the picker needs before a translation is
installed: id, language, display name, byte size, sha256 and the URL.

    python3 mobile/scripts/build_translations_manifest.py [out_dir]

Writes <out_dir>/manifest.json plus a flat copy of every module named
"<LanguageFolder>__<module>.json" (release assets cannot contain slashes),
which is exactly what the workflow uploads.
"""

import hashlib
import json
import re
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "translations"
BASE_URL = "https://github.com/BacroXP/JasonBible/releases/download/translations-latest"
NAME_FIELD = re.compile(r'"name"\s*:\s*"((?:[^"\\]|\\.)*)"')


def display_name(path: Path) -> str:
    """The module's metadata.name, read from the file prefix only."""
    with path.open("rb") as handle:
        head = handle.read(128 * 1024).decode("utf-8", "ignore")
    cut = head.find('"verses":')
    if cut != -1:
        head = head[:cut]
    match = NAME_FIELD.search(head)
    if not match:
        return path.stem
    try:
        return json.loads('"' + match.group(1) + '"').strip()
    except json.JSONDecodeError:
        return path.stem


def main() -> None:
    out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "translations-dist"
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)

    entries = []
    for path in sorted(SOURCE.rglob("*.json")):
        relative = path.relative_to(SOURCE)
        folder = relative.parts[0]
        language = folder.split("-", 1)[1].replace("_", " ") if "-" in folder else folder
        asset = relative.as_posix().replace("/", "__")
        shutil.copy2(path, out_dir / asset)
        entries.append(
            {
                "id": path.stem,
                "languageFolder": folder,
                "language": language,
                "name": display_name(path),
                "size": path.stat().st_size,
                "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                "url": f"{BASE_URL}/{asset}",
            }
        )

    manifest = {"version": 1, "translations": entries}
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
    total = sum(entry["size"] for entry in entries)
    print(f"{len(entries)} translations ({total / 1e6:.0f} MB) -> {out_dir}/manifest.json")


if __name__ == "__main__":
    main()
