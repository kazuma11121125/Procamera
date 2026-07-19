# AuCamPRO 仕様書

最終更新: 2026-07-16。実装（`com.aucampro.recorder`, versionName 0.1.0）の現状を反映した仕様書。
`docs/ARCHITECTURE.md` / `progress_summary.md` はフェーズ単位の開発ログ・判断ログであり、本書は
それらとは別に「現時点で実際に何ができるアプリか」をまとめ直したもの。

## 1. 概要

学祭ライブバンド演奏(音圧110〜125dB SPL程度の大音量環境)を、Android端末1台で長時間・高音質・
高画質録画するためのプロフェッショナル向け録画アプリ。設計/保守性・リアルタイム性能・安定性・
商用品質の信頼性を優先する。

当初の数値目標(命令書 `android_recording_app_prompt_v2` 由来。一部は今回未再検証):

| 項目 | 目標値 |
|---|---|
| 映像 | 3840×2160 @30fps HEVC 50Mbps(フォールバック: 1920×1080 @60fps H.264 20Mbps) |
| 音声 | AAC-LC 48kHz ステレオ 256kbps |
| A/V同期ドリフト | 120分連続録画後でも ±20ms以内 |
| オーディオコールバック処理時間 | バースト長の50%以内 |
| オーディオxrun | 120分で0件目標 |
| フレームドロップ | 常温で0.1%未満 |
| 連続録画時間 | 120分以上(外部給電・機内モード前提)、熱制限時も可能な限り録画継続 |
| ストレージ書き込み | 120分間、約6.3MB/sのシーケンシャル書き込みを維持 |

## 2. カメラ/映像機能

### レンズ・ズーム
- 背面の全レンズを列挙し、35mm換算焦点距離を `物理焦点距離 × 43.27mm / センサー対角線mm` で算出。
- 「標準」レンズの選定優先順位: `LOGICAL_MULTI_CAMERA`対応 → ハードウェアレベル → 28mmに最も近いもの。
- センサー対角線5.0mm未満(補助/深度センサー等)は除外。換算焦点距離の差が3mm以内のレンズは重複として除外。
- UIには実機(Sony SO-51C)で確認した例として「16mm / 24mm / 88mm」のようなピルボタンで表示。
- ズームは選択レンズ内の連続デジタルズーム。範囲は`1.0×`〜端末の`SCALER_AVAILABLE_MAX_DIGITAL_ZOOM`
  (未対応端末では1×固定)。スライダー表示は実効mm(`基準焦点距離mm × ズーム倍率`)。

### 露出
- **ISO**: `SENSOR_INFO_SENSITIVITY_RANGE`から取得(未対応時は100〜800にフォールバック)。デフォルト400。
- **シャッタースピード**: `SENSOR_INFO_EXPOSURE_TIME_RANGE`の連続値(未対応時は1ms〜1000msにフォールバック)。
  フリッカー回避用の固定プリセットとして **1/50, 1/60, 1/100, 1/120** を提供。デフォルト1/60。

### フォーカス
- AF/MF切り替えスイッチ。
- MF: `LENS_FOCUS_DISTANCE`(ディオプター)。範囲は`0`(無限遠)〜端末の`LENS_INFO_MINIMUM_FOCUS_DISTANCE`。
- AF: `CONTROL_AF_MODE_CONTINUOUS_VIDEO`。
- タップトゥフォーカス: 基本はMFロック状態。プレビューへの**長押し**で一時的に
  `CONTROL_AF_MODE_AUTO` + `AF_TRIGGER_START`によるスキャンを実行(3秒タイムアウト)、
  収束した距離でMFに戻す。単純タップはUIコントロールの表示/非表示切り替えに使うため、
  競合を避けて長押しにしている。

### ホワイトバランス
- オート(ISPのAWB)とマニュアルKelvin(範囲 **2500K〜8000K**)を切り替え可能。
- マニュアルモデル: 緑ゲインを1.0に固定し、赤/青は暖色端(2500K: R=1.4, B=2.8)と
  寒色端(8000K: R=2.8, B=1.4)の間を線形補間する、Bayerセンサー向けの経験則的近似
  (色彩工学的な厳密な導出ではなく、複数端末での精度は未検証)。
- クイックプリセット4種: ☀️5500K, ☁️6500K, 蛍光灯4000K, 電球3200K。
- マニュアルWBは`CONTROL_AWB_MODE_OFF` + `MANUAL_POST_PROCESSING`対応が必須。非対応機種では
  「この機種ではサポートされていません」を表示し、機能自体を無効化する。

### 動画解像度・フレームレート・ビットレート
実行時に`MediaCodecList`で対応状況を確認した上で提示する候補(いずれも16:9固定。非16:9は過去に
試して実機でFOV/AFハンチング系の不具合を招いたため撤回済み):

| コーデック | 解像度 | fps | ビットレート |
|---|---|---|---|
| HEVC | 3840×2160(4K) | 30 | 50Mbps |
| AVC | 1920×1080 | 60 | 20Mbps |
| AVC | 1920×1080 | 30 | 10Mbps |
| AVC | 1280×720 | 60 | 8Mbps |

画素数の降順でソートし、対応している先頭候補をデフォルトに採用する。

### 撮影補助
- **フレームラインガイド**: プレビューのみのオーバーレイ(録画映像はクロップされない)。
  Off / 1:1 / 3:2 / 4:3 / 16:9 / 9:16。
- **ヒストグラム表示**: プレビュー中のみのライブ輝度ヒストグラム。録画中は自動的に非表示になる。
- **水準器(レベルゲージ)**: 画面中央の人工水平線オーバーレイ。常時表示。
- **フォーカスリティクル**: タップ/長押し位置にAFスキャン状態を表示し、Locked/Failedから
  数秒後に自動的に消える。

## 3. 音声機能

ネイティブのOboeフルデュプレックスエンジン(C++20、JNI経由)で、**48000Hz・ステレオ(2ch)**、
最大192kHz時に約21.8秒分となるリングバッファ容量（`192000 × 20` framesを
次の2冪へ切り上げ）で駆動する。

### DSPチェーン(固定順序)
入力ゲイン → ハイパスフィルター → 3バンドEQ → メイクアップゲイン → セーフティリミッター → Peak/RMSメーター

| 項目 | 範囲 | デフォルト |
|---|---|---|
| 入力ゲイン(録音レベル、EQ前) | -24dB 〜 +12dB | 0dB |
| ハイパスフィルター カットオフ | 40Hz〜240Hz(内部初期値100Hz) | オフ |
| EQ Low | freq 20–500Hz / Q 0.1–4 / gain ±12dB | freq 80Hz, Q 0.8, gain -6dB |
| EQ Mid | freq 200–8000Hz / Q 0.1–4 / gain ±12dB | freq 1500Hz, Q 1.2, gain +3dB |
| EQ High | freq 2000–20000Hz / Q 0.1–4 / gain ±12dB | freq 8000Hz, Q 0.7, gain -4dB |
| メイクアップゲイン(EQ後、ブーストのみ) | 0dB 〜 +18dB | 0dB(オフ推奨、ノイズも増幅されるため) |
| セーフティリミッター | ソフトニー(tanh)、しきい値 -1.0dBFS、ルックアヘッドなし | 常時有効 |

- ハイパスフィルターはButterworth Q=0.707のRBJクックブックbiquad。変更/切替時は約5ms
  (48kHzで240サンプル)のクリックノイズ防止用ランプを適用。
- Peak/RMSメーターはチャンネル(L/R)独立、Peakリリース0.3秒、RMSウィンドウ0.3秒。
  RTオーディオスレッドからはプッシュせず、Kotlin側からJNI経由で約30Hzでポーリングする。
- **モニタリング(ヘッドホンパススルー)**: `AudioDeviceRouter.hasSafeMonitoringOutput()`で、
  有線/USBヘッドホン系の出力が接続されている場合のみ有効化可能。Bluetoothと内蔵スピーカーは
  ハウリング防止のため明示的に除外。
- **入力デバイスルーティング**: 優先順位 USBオーディオ > 有線ヘッドセット > 内蔵マイク
  (Auto/Usb/Wired/BuiltInをユーザーが指定可能。指定してもフォールバックチェーンは維持)。
  デバイスの抜き差しに対応(ネイティブ側`reopenInputStream`を呼び、途切れは`insertSilence`で埋める)。
- エンコード: AAC-LC、48000Hz/2ch/256000bps。

## 4. 録画パイプライン

- **単一ファイル録画**: 時間によるセグメント分割は行わない。1回の録画につきMP4を1本生成し、
  ハイレゾ録音時は同じtake名のWAVサイドカーを1本追加する。
- **保存先**(`StorageLocation`):
  - `AppPrivate` — アプリ専用領域(`getExternalFilesDir("recordings")`)。ギャラリー非表示、
    アンインストールで消える。
  - `PublicMovies` — MediaStore経由で`Movies/AuCamPRO`に保存、ギャラリー表示。**デフォルト**。
  - `Custom` — SAFディレクトリURI。データモデル・永続化層には存在するが、
    **UI上のピッカーが未実装のため現状ユーザーからは到達不可**。
- **ファイル命名規則**: `AuCamPRO_<takeTimestamp>.mp4`、ハイレゾWAVは
  `AuCamPRO_<takeTimestamp>.wav`。
  写真: `AuCamPRO_IMG_<timestampMs>.jpg`。公開エクスポート先は`Movies/AuCamPRO` /
  `Pictures/AuCamPRO`。
- **PTS/クロックドメイン**: 映像・音声とも`recordingStartNanos`を0とする共有エポック
  (`CLOCK_MONOTONIC`/`System.nanoTime()`系。`SENSOR_INFO_TIMESTAMP_SOURCE=REALTIME`でも
  `presentationTimeUs`はこのドメインで一致することを実機(SO-51C)で確認済み)。音声側の
  アンカーは`getInputTimestamp()`のフレーム相関から導出(入力レイテンシのオフセットを回避)。
  他ベンダー/コーデック実装での再検証はまだ行っていない。
- **画面回転**: Activityは`sensorLandscape`に固定(`configChanges`でActivity再生成を抑止し、
  長時間録画中のPTS連続性を保護)。縦持ち録画は実フレームを回転させず、出力ファイルの
  向きヒントメタデータ(`DeviceOrientationTracker`)で対応する。
- **フォアグラウンドサービス**: `FOREGROUND_SERVICE_TYPE_CAMERA|MICROPHONE`、
  `PARTIAL_WAKE_LOCK`(最大4時間の安全上限)、`START_NOT_STICKY`
  (OSに強制終了・再起動されてもサービス自身では復帰しない設計。タスクスワイプや
  OOM Killによるプロセス完全終了までは保護対象外で、画面オフ/バックグラウンド中の
  プロセス生存のみをカバーする)。
- **ハードウェアシャッターキー**: `KEYCODE_CAMERA`。`MainActivity.dispatchKeyEvent`で
  Composeのキー処理より先に横取りし、`repeatCount==0`条件 + **1500msデバウンス**で処理
  (連打による実機クラッシュ`ForegroundServiceDidNotStartInTimeException`の対策として追加)。
  `CaptureMode`(写真/動画)により、静止画撮影とREC切り替えのどちらかに分岐する。
- **写真撮影**: 専用`ImageReader`(JPEG、最大解像度、バッファ2枚)経由。`PREVIEWING`中のみ有効
  (`RECORDING`中は無効 — 実機クラッシュ`IllegalArgumentException: unconfigured
  Input/Output Surface`の対策)。動画の保存先設定に関わらず、常に公開領域
  `Pictures/AuCamPRO`に保存する。
- **サーマル制御**: `PowerManager`の温度状態リスナー(`ThermalMonitor`)で
  `THERMAL_STATUS_SEVERE`(≥3)以上を警告バナー表示。録画は自動停止しない
  (熱制限時も録画継続を優先する方針)。実機でのSEVERE遷移時の挙動は
  エミュレータでのリスナー登録確認止まりで、未検証。

## 5. UI/UX設計

- **レイアウト**(横画面、`MainScreen.kt`): 左側にカメラプレビュー(`weight(1f)`、実際の録画
  解像度によらず内部バッファは16:9固定1920×1080)、右側にドッキングされた縦型コントロール
  パネル(CAMERA/AUDIOタブ)。Sony Video Pro/Photo Pro風のデザインで、以前はフルワイドの
  ボトムシートだったが、実機フィードバック(スライダー調整中に構図/ピントが判断できない)を
  受けて右ドック方式に変更した経緯がある。
- **プレビュー上のオーバーレイ**(衝突を避けるため各コーナーに配置):
  - 左上: ステレオオーディオメーター(L/Rバー)
  - 中央: 水準器
  - 左下: ヒストグラム + ギャラリーサムネイルボタン
  - 上中央: ステータスオーバーレイ(REC表示/経過時間/設定歯車、
    `showControls`でタップ表示切替可能)
  - 下中央: 常時表示のREC状態テキスト(「STANDBY」/経過時間)+ シャッター行
    (写真モードでは撮影ボタン、動画モードではREC操作はハードウェアキー専用のため何も表示しない)
- **設定ボトムシート**(`SettingsBottomSheet.kt`、スクロール可能): 解像度/fpsのラジオリスト、
  保存先ラジオリスト(現状AppPrivate/PublicMoviesのみ — 上記のギャップ参照)、
  フレームラインガイドのラジオリスト、マイク入力の優先設定のラジオリスト。
  カスタム保存先のUIは現状なし。
- **CAMERAタブ**(表示順): レンズピル、ZOOM、FPS(表示のみ)、ISO、SHUTTER + フリッカー
  プリセット4種、AF/MFスイッチ、FOCUSスライダー、WBスイッチ、WB(Kelvin)スライダー + プリセット4種。
- **AUDIOタブ**(表示順): 入力デバイス表示 + xrun/overrun統計、GAINスライダー、
  ハイパスフィルタースイッチ + CUTOFFスライダー、3バンドEQ(freq/Q/gainの3行)、
  MAKEUP GAIN(BOOST)スライダー、MONITORトグル。
- 両タブとも`LazyColumn`で仮想化(実機の`dumpsys gfxinfo`計測でjankyフレーム94%だった
  未仮想化版からの改善)。
- **ダークテーマ**(`AuCamPROTheme.kt`): ほぼ黒のサーフェス(`SurfaceBlack #080A0C`,
  `SurfaceDark #12161A`)、アンバー`#FF9500`(REC表示/アクティブコントロール/ハイライト)、
  赤`#E53935`(REC状態)、メーター専用色(緑/黄/橙/赤)。
- RECインジケーターの点滅アニメーションやオーディオメーター・ヒストグラムなど高頻度更新
  (20〜30Hz)のUI状態は、`CameraUiState`本体とは別の`StateFlow`に意図的に分離し、
  毎ティックのサイドバー全体再コンポジションを避けている(実機計測で同等ネイティブアプリの
  3〜5倍のアイドルCPU使用を確認し、対策として実施)。

## 6. 技術アーキテクチャ

- パッケージ: `com.aucampro.recorder`(2026-07-16に`com.procamera.recorder`からリネーム)。
- UI: Kotlin + Jetpack Compose(Material3)、単一Activity(`MainActivity`)、
  `MainScreen`コンポーザブル、`CameraControlViewModel`(AndroidX ViewModel)が
  `CameraUiState`/`AudioMeterUiState`の`StateFlow`を介した単一の状態源となる。
- カメラ: Camera2 API(`CameraSessionController`, `ManualCaptureRequestFactory`,
  `CameraCapabilityInspector`, `CaptureRangeClamper`, `FocusController`,
  `TapToFocusStateMachine`/`TapToMeteringRegion`)。
- エンコード: `MediaCodec`(`VideoEncoder`/`AudioEncoder`)、`MuxerController`
  (`MediaMuxer`をラップし1 takeを1 MP4へ出力)、`PtsClockDomain`、`PcmDither`
  (Float32→16bitのTPDFディザ。MediaCodecの多くのAACエンコーダはPCM_16BIT入力前提のため)。
- ネイティブオーディオ: C++20、Google Oboe 1.10.0によるフルデュプレックスエンジン
  (`OboeFullDuplexEngine`)。JNI(`NativeEngineBridge`, `System.loadLibrary("aucampro_native")`)
  経由でKotlinに公開。ロックフリーSPSCリングバッファ(`SpscRingBuffer`)、
  UIスレッド→オーディオスレッドへのEQ係数受け渡し用トリプルバッファ(`TripleBuffer`)、
  自前の`Result<T, E>`(`-fno-exceptions`指定 + C++20には`std::expected`が無いため)。
- DI: 手動DI。`AuCamPROApplication`が保持する`AppContainer`(Hilt/Dagger不使用)。
- 永続化: 素の`SharedPreferences`(`UserPreferencesStore`、prefsファイル名
  `aucampro_user_prefs`。DataStoreは不使用)。
- クラッシュハンドリング: `AuCamPROApplication`が
  `Thread.setDefaultUncaughtExceptionHandler`をインストールし、最後の後始末を試みてから
  元のハンドラに委譲する(クラッシュ自体は防がず、データ損失を減らす仕組み)。

## 7. ビルド/プラットフォーム仕様

| 項目 | 値 |
|---|---|
| applicationId / namespace | `com.aucampro.recorder` |
| minSdk / targetSdk / compileSdk | 29 / 36 / 36 |
| NDK | 27.3.13750724 (r27d LTS) |
| CMake | 3.31.6 |
| 出荷ABI | arm64-v8a のみ(release/デフォルトconfig)。x86_64はdebug buildTypeのみ
  (エミュレータでのインストゥルメンテーションテスト用、出荷には含めない) |
| Kotlin | 2.4.0(AGP 9のビルトインKotlinを使用。`org.jetbrains.kotlin.android`プラグインは
  併用不可のため未使用) |
| Compose BOM | 2026.06.01 |
| Oboe | 1.10.0(prefab/CMake `find_package`経由。ソースvendoringはしない) |
| C++標準 | C++20、`-fno-exceptions`、`-std=c++20` |
| versionName / versionCode | 0.1.0 / 1(リリース前、release buildTypeもdebug署名で仮運用) |
| 権限 | CAMERA, RECORD_AUDIO, POST_NOTIFICATIONS,
  FOREGROUND_SERVICE(+_CAMERA/_MICROPHONE), WAKE_LOCK,
  REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, MODIFY_AUDIO_SETTINGS
  (スコープドストレージのMediaStore書き込みのみのため、広範なストレージ権限は要求しない) |

## 8. 既知の制限事項・未実装ギャップ

- **カスタム保存先(SAF)**: `StorageLocation`型と永続化層には存在するが、UIピッカー未実装で
  ユーザーからは到達不可。
- **単一ファイル録画のクラッシュ耐性**: 正常停止またはJVM例外時のbest-effort finalizeでは
  MP4/WAVを確定するが、強制kill・電源断・ネイティブクラッシュではtake全体が失われ得る。
- **on-device ASan/UBSan用のwrap.sh**: 未整備。デバッグビルドではASanランタイムの`.so`が
  `dlopen`できないため`AUCAMPRO_ENABLE_SANITIZERS=OFF`で強制無効化中(本日
  `PROCAMERA_ENABLE_SANITIZERS`からリネーム)。ホストGTestビルドはサニタイザー有効のまま影響なし。
- **120分連続録画目標**: 短時間の検証のみ。約94秒の実機録画で映像が平均約27.8fps
  (約7%のフレーム損失)になった一方、約23秒の短いクリップでは安定した30.0002fpsだった例があり、
  根本原因(バッファ飽和/熱/adb・UI負荷の並走など)は未特定。
- **A/V同期**: 実機で3回のクラップテストにより±20ms予算内であることを計測(フレーム間隔
  約33msと被写体のモーションブラーにより計測分解能に制約あり)。厳密な複数ベンダー検証ではない。
- **複数メーカーでの検証**: 命令書は2メーカー以上での検証を求めていたが、プロジェクト全体を通じて
  実機検証はSony SO-51C 1機種のみ。
- **CI/GitHub Actions**: 「Phase5」で計画されていたが、`.github/workflows/`はリポジトリに未作成。
- **マニュアルWB**: `MANUAL_POST_PROCESSING` + `AWB_MODE_OFF`非対応機種(LIMITEDレベル等)では
  機能自体を無効化し、非対応メッセージを表示する。
- 2026-07-19の単一ファイル化はホスト単体テストとdebug APKビルドまで確認済み。
  `PublicMovies`へのMediaStoreエクスポートを含む実機確認は未実施。
  また、初回起動時の保存先デフォルトが`AppPrivate`に誤ってフォールバックしていた不具合も
  同日中に修正し、`PublicMovies`が正しくデフォルトになることを実機で確認済み。
