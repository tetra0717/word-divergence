# word-divergence

Android向けの、Obsidian Graph Viewのように意味空間を歩く日本語ブレインストーミングツールです。

## Brainstorm

入力した単語/文章を中心ノードにし、枝を自動生成します。

- 1ノードから派生する数を指定
- 中心から離れるほど、最初の入力との意味も遠くなる
- マップをパンして未探索方向へ進むと、その方向のfrontierを自動生成
- 生成中ノードはスピナー表示
- タップ: 意味距離 / 深さを表示
- 長押し: ☆ この地点を保存 / ◎ 新しい中心にする
- 新しい中心は元マップを履歴に残したまま、新規マップとして開始
- 名詞 / 動詞 / 形容詞 / 副詞 / 固有名詞 / その他を個別ON/OFF
- 最大5,000ノード

## Random

Brainstormとは別タブです。

- 入力あり: 入力とのコサイン類似度が0付近の、意味的に無関係な語
- 入力なし: 日本語語彙から完全ランダム
- 結果をタップすると、その語を中心にBrainstorm開始

## グラフ描画

意味距離と画面上の距離は連動させません。意味距離は内部データ/詳細表示として保持し、レイアウトは見やすさを優先したforce-directed graphです。

- 親子リンクのスプリング
- ノード同士の反発
- 実際のノード半径を使ったcollision
- 最後に固定回数のcollision解消パス
- 文字はノードの中に表示
- 遠距離ズームでは点表示へLOD切り替え
- 画面外ノードは空間ハッシュで描画対象から除外

「十分落ち着いたら既存ノードを固定」のようなヒューリスティックは使いません。追加生成のたびに決められた回数だけレイアウト計算します。

## 意味検索

実モデルでは日本語fastText 300次元を使います。頻出語だけに切り詰めません。

全語彙を毎回総当たりする代わりに、USearchのHNSWを使用します。

- 300 dimensions
- cosine
- float16 scalar storage
- HNSW
- disk-backed / memory-mapped index
- SQLiteで word / vector id / POS を管理

USearch 2.26.0を固定し、Android NDK/CMakeで公式JNIソースを直接ビルドします。公開JARの有無に依存しません。

モデルパックの作り方は docs/MODEL_PACK.md を参照してください。

## 現在の状態

モデルパックが端末に無い場合は、UI確認用の小さなデモ辞書で起動します。
model-v1 Releaseにモデルパックを置けば、アプリ内の「モデル」ボタンからフル語彙パックを取得して切り替えます。

## Build

Android Studioでリポジトリを開くか:

~~~bash
./gradlew assembleDebug
~~~

最低AndroidバージョンはAndroid 10 (API 29)、target/compile SDKは36です。

## Model pack

モデル自体は巨大なのでGitにはコミットしません。

~~~bash
pip install -r scripts/requirements.txt
python scripts/build_model_pack.py --vec /path/to/cc.ja.300.vec --out model-pack
~~~

## Repository layout

~~~text
app/
  engine/     fastText/USearch検索・デモ検索・モデル取得
  graph/      Obsidian風Canvas + viewport culling
  model/      graph/session data
  data/       履歴・☆保存
scripts/
  build_model_pack.py
docs/
  MODEL_PACK.md
~~~
