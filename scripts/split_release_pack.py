#!/usr/bin/env python3
"""Split a large model pack into GitHub Release-sized assets and write a manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


def sha256_file(path: Path, chunk: int = 8 * 1024 * 1024) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            data = f.read(chunk)
            if not data:
                break
            h.update(data)
    return h.hexdigest()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--part-mib", type=int, default=1800)
    args = ap.parse_args()

    src = args.input.resolve()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    part_size = args.part_mib * 1024 * 1024

    for old in out.glob(src.name + ".part-*"):
        old.unlink()
    manifest_path = out / "model-manifest.json"
    if manifest_path.exists():
        manifest_path.unlink()

    full_hash = hashlib.sha256()
    parts = []
    total = 0
    index = 1

    with src.open("rb") as f:
        while True:
            part_name = f"{src.name}.part-{index:03d}"
            part_path = out / part_name
            part_hash = hashlib.sha256()
            written = 0

            with part_path.open("wb") as p:
                while written < part_size:
                    data = f.read(min(8 * 1024 * 1024, part_size - written))
                    if not data:
                        break
                    p.write(data)
                    part_hash.update(data)
                    full_hash.update(data)
                    written += len(data)
                    total += len(data)

            if written == 0:
                part_path.unlink(missing_ok=True)
                break

            parts.append({
                "name": part_name,
                "size": written,
                "sha256": part_hash.hexdigest(),
            })
            print(f"{part_name}: {written / (1024**2):.1f} MiB", flush=True)
            index += 1

    manifest = {
        "format": 1,
        "archive": src.name,
        "archive_size": total,
        "archive_sha256": full_hash.hexdigest(),
        "parts": parts,
    }
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(json.dumps(manifest, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
