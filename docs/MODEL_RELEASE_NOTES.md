# Japanese full-vocabulary model pack

This release contains the Android search pack generated from the official Japanese fastText Common Crawl/Wikipedia text vectors.

- Source vocabulary: full well-formed unique vocabulary from `cc.ja.300.vec.gz`
- Dimensions: 300 (no dimensionality reduction)
- Search index: USearch 2.26.0 HNSW
- Metric: cosine
- Vector storage: float16
- Vocabulary pruning: none
- Word lookup / POS metadata: SQLite
- Release transport: multipart assets with SHA-256 manifest

## Attribution and license

The source fastText word vectors are distributed under **Creative Commons Attribution-ShareAlike 3.0**.

Source: https://fasttext.cc/docs/en/crawl-vectors.html

Reference: Grave, E.; Bojanowski, P.; Gupta, P.; Joulin, A.; Mikolov, T. *Learning Word Vectors for 157 Languages*, LREC 2018.

The generated model pack also contains `ATTRIBUTION.txt`.
