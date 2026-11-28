# たぺいの天気（weather-widget）

気象庁の予報を数日分並べて見せる自分用のホーム画面ウィジェット。アプリを開くと設定画面（場所・日数・文字の大きさ・更新）。

## 共通ルール

このアプリは自分用アプリの共通ルールに従う：
https://github.com/tapeinooo/app-rules/blob/main/RULES.md

作業を始める前に読むこと（読めないときは `add_repo` で `tapeinooo/app-rules` を足すか、
https://raw.githubusercontent.com/tapeinooo/app-rules/main/RULES.md を開く）。
主な点：GitHub Actions でビルド・main への push で自動リリース・版の番号は自動・同じ署名の鍵・アプリの中の更新・
コミットの作者は tapeinooo の noreply・秘密はリポジトリに入れない・利用者への説明は日本語でやさしく。

## このアプリだけの決まり

- 【例外】版の番号：手で付けていた頃（v1.25・versionCode 26）より大きくするため、gradle.properties の versionOffset=100（versionCode に足す）と versionMajor=2（versionName は 2.0.<実行番号>）を使う。
- アプリの更新は AppUpdater.kt（PackageInstaller で入れる。Android 12 以降、2回目からは確認なしで入る）。タグの versionName をくらべる。
- 【例外】署名の鍵は Mac の debug 鍵（app/debug.keystore、パスワード android、別名 androiddebugkey）。v1.25 までこの鍵で入れてあるので変えない（v2.0.1〜2.0.3 は誤って別の鍵で作ったため、v1.25 以前から上書きできなかった。その鍵は使わない）。公開リポジトリなので鍵そのものは入れず、GitHub の Secret DEBUG_KEYSTORE_B64（base64）に置き、Actions がそこから app/debug.keystore を作る。Mac の控えは ~/keys/android-debug.keystore（手元でビルドするときは app/debug.keystore に置く）。app/debug.keystore は絶対にコミットしない。
- 本物の気象庁などにつなぐテスト（*LiveTest）は GitHub Actions では外している。
