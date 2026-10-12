# もう一杯！

ビールを注いで、あふれる直前で止めるギリギリゲーム。

- 遊ぶ(Web 版・広告なし): https://tfd0313-lang.github.io/mou-ippai/
- スマホを傾けて注ぐ操作に対応（端末のモーションセンサーを使用）

## このリポジトリ

- ルート: 配信用の Web 版(GitHub Pages)。ゲーム本体は index.html 1 ファイル、素材は art/・voice/
- `android/`: Google Play 用の Android アプリ。Web 版を開き、AdMob の全画面広告を出す
- `.github/workflows/android.yml`: Play に出す AAB を自動で作る(実行結果の Artifacts からダウンロード)

開発用のソース・仕様書・手順書(RELEASE.md)は別リポジトリで管理しています。
