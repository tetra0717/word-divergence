# Model pack

The Android app uses two layers:

1. Vocabulary / semantic space: the full Japanese fastText vector vocabulary.
2. Search acceleration: a USearch HNSW index that can be memory-mapped on Android.

No frequent-vocabulary pruning is performed.

## Source model

The pack is built from the official Japanese Common Crawl/Wikipedia fastText text vectors:

- `cc.ja.300.vec.gz`
- 300 dimensions
- Japanese corpus tokenization: MeCab
- Source license: CC BY-SA 3.0

Source page: https://fasttext.cc/docs/en/crawl-vectors.html

## Build

The builder accepts a plain .vec file, a .vec.gz file, or decompressed data on stdin.

~~~bash
python -m venv .venv
source .venv/bin/activate
pip install -r scripts/requirements.txt

python scripts/build_model_pack.py \
  --vec /path/to/cc.ja.300.vec.gz \
  --out model-pack
~~~

This creates:

- model-pack/vectors.usearch
- model-pack/words.sqlite
- model-pack/metadata.json
- model-pack/ATTRIBUTION.txt
- model-pack/ja-fasttext-usearch-pack.zip

The index keeps every well-formed unique vocabulary row at 300 dimensions and stores vectors as float16 inside HNSW. Dimensions are not reduced.

## Release transport

GitHub Release assets can be large, so the archive is split into parts:

~~~bash
python scripts/split_release_pack.py \
  --input model-pack/ja-fasttext-usearch-pack.zip \
  --out release-assets \
  --part-mib 1800
~~~

The `model-v1` release contains:

- model-manifest.json
- metadata.json
- ATTRIBUTION.txt
- ja-fasttext-usearch-pack.zip.part-001
- ja-fasttext-usearch-pack.zip.part-002
- ... if needed

The Android app downloads the manifest first, resumes only at verified part boundaries, verifies SHA-256 for every part and the reconstructed archive, extracts into a staging directory, and only then swaps the new model into place.

## Automated full build

`.github/workflows/model-pack.yml` downloads the official Japanese vectors, runs a smoke test, builds the full pack, splits it, and publishes/updates the `model-v1` GitHub Release.

## Android loading

USearch opens `vectors.usearch` with `viewFromPath`, so the HNSW index is memory-mapped instead of copied wholesale into RAM.

For local development, `vectors.usearch` and `words.sqlite` can also be placed under the app-private `files/model-pack/` directory with adb.

## Random / unrelated words

For a seed word the app does not scan the entire vocabulary. It randomly samples vocabulary IDs, computes cosine similarity against the seed vector, and keeps samples with `abs(cosine)` closest to zero. This intentionally optimizes for unrelated rather than opposite.

For an empty seed it samples vocabulary IDs directly.
