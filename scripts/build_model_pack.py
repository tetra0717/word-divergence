#!/usr/bin/env python3
"""Build the Android model pack without pruning fastText vocabulary.

Accepts either:
  --vec /path/to/cc.ja.300.vec
  --vec /path/to/cc.ja.300.vec.gz
  --vec -                         # read decompressed .vec bytes from stdin

Output:
  vectors.usearch
  words.sqlite
  metadata.json
  ATTRIBUTION.txt
  ja-fasttext-usearch-pack.zip

Every well-formed unique vocabulary row is indexed. No frequency pruning and
no dimensionality reduction are performed.
"""

from __future__ import annotations

import argparse
import gzip
import json
import sqlite3
import sys
import time
import zipfile
from contextlib import contextmanager
from pathlib import Path

import numpy as np
from usearch.index import Index

try:
    import fugashi
except Exception:
    fugashi = None


ATTRIBUTION = """Japanese fastText word vectors

Source:
https://fasttext.cc/docs/en/crawl-vectors.html

The source vectors were published by the fastText project and trained on
Common Crawl and Wikipedia. The Japanese vectors are 300-dimensional and the
Japanese corpus was tokenized with MeCab.

License of the source vectors:
Creative Commons Attribution-ShareAlike 3.0
https://creativecommons.org/licenses/by-sa/3.0/

Reference:
Grave, E.; Bojanowski, P.; Gupta, P.; Joulin, A.; Mikolov, T.
Learning Word Vectors for 157 Languages. LREC 2018.

This model pack is a transformed search representation of those vectors.
No vocabulary-frequency pruning or dimensionality reduction is performed.
"""


@contextmanager
def open_vec_stream(spec: str):
    if spec == "-":
        yield sys.stdin.buffer
        return

    path = Path(spec)
    if path.suffix.lower() == ".gz":
        with gzip.open(path, "rb") as fp:
            yield fp
    else:
        with path.open("rb") as fp:
            yield fp


def classify_pos(tagger, word: str) -> str:
    if tagger is None:
        return "unknown"
    try:
        tokens = list(tagger(word))
        if len(tokens) != 1:
            return "other"
        token = tokens[0]
        pos1 = getattr(token.feature, "pos1", "") or ""
        pos2 = getattr(token.feature, "pos2", "") or ""
        if "固有名詞" in pos2:
            return "proper"
        if "名詞" in pos1:
            return "noun"
        if "動詞" in pos1:
            return "verb"
        if "形容詞" in pos1:
            return "adjective"
        if "副詞" in pos1:
            return "adverb"
        return "other"
    except Exception:
        return "unknown"


def read_header(fp):
    first = fp.readline().decode("utf-8", errors="strict").strip().split()
    if len(first) != 2:
        raise ValueError("Expected fastText .vec header: <count> <dimensions>")
    return int(first[0]), int(first[1])


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--vec", required=True, help="fastText .vec, .vec.gz, or '-' for stdin")
    ap.add_argument("--source-name", default=None)
    ap.add_argument("--out", default=Path("model-pack"), type=Path)
    ap.add_argument("--batch", default=12000, type=int)
    ap.add_argument("--no-pos", action="store_true", help="Skip fugashi tagging")
    args = ap.parse_args()

    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    index_path = out / "vectors.usearch"
    db_path = out / "words.sqlite"
    meta_path = out / "metadata.json"
    attribution_path = out / "ATTRIBUTION.txt"
    zip_path = out / "ja-fasttext-usearch-pack.zip"

    for p in (index_path, db_path, meta_path, attribution_path, zip_path):
        if p.exists():
            p.unlink()

    tagger = None if args.no_pos or fugashi is None else fugashi.Tagger()
    started = time.time()

    with open_vec_stream(args.vec) as fp:
        declared_count, dims = read_header(fp)
        if dims != 300:
            print(f"warning: expected 300 dimensions, got {dims}", file=sys.stderr)

        index = Index(
            ndim=dims,
            metric="cos",
            dtype="f16",
            connectivity=16,
            expansion_add=96,
            expansion_search=96,
        )

        db = sqlite3.connect(db_path)
        db.execute("PRAGMA journal_mode=WAL")
        db.execute("PRAGMA synchronous=OFF")
        db.execute("PRAGMA temp_store=MEMORY")
        db.execute("CREATE TABLE words(id INTEGER PRIMARY KEY, word TEXT NOT NULL UNIQUE, pos TEXT NOT NULL)")
        db.execute("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execute("CREATE INDEX idx_words_word ON words(word)")

        ids: list[int] = []
        vectors: list[np.ndarray] = []
        rows: list[tuple[int, str, str]] = []
        seen: set[str] = set()
        next_id = 1
        malformed = 0
        duplicate = 0

        def flush():
            nonlocal ids, vectors, rows
            if not ids:
                return
            matrix = np.asarray(vectors, dtype=np.float32)
            keys = np.asarray(ids, dtype=np.uint64)
            index.add(keys, matrix, threads=0)
            db.executemany("INSERT INTO words(id,word,pos) VALUES(?,?,?)", rows)
            db.commit()
            ids, vectors, rows = [], [], []

        for raw in fp:
            try:
                line = raw.decode("utf-8").rstrip("\n")
                if not line:
                    malformed += 1
                    continue
                word, vector_text = line.split(" ", 1)
                if not word:
                    malformed += 1
                    continue
                if word in seen:
                    duplicate += 1
                    continue

                vec = np.fromstring(vector_text, sep=" ", dtype=np.float32)
                if vec.size != dims or not np.isfinite(vec).all():
                    malformed += 1
                    continue

                seen.add(word)
                pos = classify_pos(tagger, word)
                ids.append(next_id)
                vectors.append(vec)
                rows.append((next_id, word, pos))
                next_id += 1

                if len(ids) >= args.batch:
                    flush()
                    done = next_id - 1
                    elapsed = max(time.time() - started, 0.001)
                    pct = min(100.0, done * 100.0 / max(declared_count, 1))
                    print(
                        f"{done:,}/{declared_count:,} ({pct:5.1f}%) "
                        f"{done/elapsed:,.0f} vectors/s",
                        flush=True,
                    )
            except Exception:
                malformed += 1

        flush()
        actual_count = next_id - 1
        db.execute("INSERT INTO meta(key,value) VALUES('max_id',?)", (str(actual_count),))
        db.execute("INSERT INTO meta(key,value) VALUES('dimensions',?)", (str(dims),))
        db.execute("INSERT INTO meta(key,value) VALUES('declared_count',?)", (str(declared_count),))
        db.execute("INSERT INTO meta(key,value) VALUES('format',?)", ("1",))
        db.commit()
        db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        db.close()

        index.save(str(index_path))

    source_name = args.source_name
    if not source_name:
        source_name = "stdin" if args.vec == "-" else Path(args.vec).name

    metadata = {
        "format": 1,
        "source": source_name,
        "source_page": "https://fasttext.cc/docs/en/crawl-vectors.html",
        "source_license": "CC BY-SA 3.0",
        "declared_count": declared_count,
        "indexed_count": actual_count,
        "dimensions": dims,
        "metric": "cos",
        "quantization": "f16",
        "connectivity": 16,
        "expansion_add": 96,
        "expansion_search": 96,
        "malformed_rows": malformed,
        "duplicate_spellings_skipped": duplicate,
        "vocabulary_pruned": False,
        "dimensionality_reduced": False,
        "pos_tagging": tagger is not None,
        "builder_usearch_version": "2.26.0",
        "elapsed_seconds": round(time.time() - started, 2),
    }
    meta_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")
    attribution_path.write_text(ATTRIBUTION, encoding="utf-8")

    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as z:
        z.write(index_path, index_path.name)
        z.write(db_path, db_path.name)
        z.write(meta_path, meta_path.name)
        z.write(attribution_path, attribution_path.name)

    print(json.dumps(metadata, ensure_ascii=False, indent=2))
    print(f"index: {index_path.stat().st_size / (1024**3):.2f} GiB")
    print(f"db:    {db_path.stat().st_size / (1024**3):.2f} GiB")
    print(f"pack:  {zip_path.stat().st_size / (1024**3):.2f} GiB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
