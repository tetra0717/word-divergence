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

Force Layoutを途中で止めて既存ノードを固定する方式ではありません。

ノード作成時に、

- 半径 = ルートとの意味距離
- 角度 = 親枝のセクタ内

として座標を一度だけ決定し、既存ノードは後から移動させません。

描画も5,000ノードを毎フレーム走査しません。固定サイズの空間ハッシュから、現在のviewport周辺にあるノードだけ取得してCanvasに描画します。ズームアウト時はラベルを省略します。

## 意味検索

実モデルでは日本語fastText 300次元を使います。頻出語だけに切り詰めません。

全語彙を毎回総当たりする代わりに、USearchのHNSWを使用します。

- 300 dimensions
- cosine
- int8 scalar storage
- HNSW
- disk-backed / memory-mapped index
- SQLiteで word / vector id / POS を管理

USearch 2.26.2のfat JARにはAndroid用native buildが含まれるため、ビルド時にGitHub Releaseから自動取得します。

モデルパックの作り方は docs/MODEL_PACK.md を参照してください。

## 現在の状態

モデルパックが端末に無い場合は、UI確認用の小さなデモ辞書で起動します。
model-v1 Releaseにモデルパックを置けば、アプリ内の「モデル」ボタンからフル語彙パックを取得して切り替えます。

## Build

Android Studioでリポジトリを開くか:

~~~bash
./gradlew assembleDebug
~~~

最低AndroidバージョンはAndroid 10 (API 29)、target/compile SDKは37です。

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
