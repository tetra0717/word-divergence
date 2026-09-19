# Model pack

The Android app is intentionally split into two layers:

1. Vocabulary / semantic space: the full Japanese fastText vector vocabulary.
2. Search acceleration: a USearch HNSW index that can be memory-mapped on Android.

No frequent-vocabulary pruning is performed.

## Source model

Use the official Japanese Common Crawl fastText vectors (cc.ja.300.vec.gz) and decompress them to cc.ja.300.vec.

The source fastText vectors are distributed separately from this repository and have their own license/attribution requirements (CC BY-SA 3.0 for the published fastText vectors).

## Build

~~~bash
python -m venv .venv
source .venv/bin/activate
pip install -r scripts/requirements.txt

python scripts/build_model_pack.py \
  --vec /path/to/cc.ja.300.vec \
  --out model-pack
~~~

This creates:

- model-pack/vectors.usearch
- model-pack/words.sqlite
- model-pack/metadata.json
- model-pack/ja-fasttext-usearch-pack.zip

The index keeps all well-formed vocabulary rows at 300 dimensions and uses int8 scalar storage inside HNSW. It does not reduce 300 dimensions to 100/150 etc.

## Android installation

The app expects a GitHub Release:

- tag: model-v1
- asset: ja-fasttext-usearch-pack.zip

The model is downloaded on demand from the Model button and extracted into app-private storage. USearch opens vectors.usearch with viewFromPath, so the index is memory-mapped instead of copied wholesale into RAM.

For development, you can also place vectors.usearch and words.sqlite in the app files/model-pack directory using adb.

## Random / unrelated words

For a seed word the app does not scan the entire vocabulary. It randomly samples vocabulary IDs, computes cosine similarity against the seed vector, and keeps samples with abs(cosine) closest to zero. This intentionally optimizes for unrelated rather than opposite.

For an empty seed it samples vocabulary IDs directly.
