# ProCamera — Phase 1: アーキテクチャ設計書

学祭ライブバンド演奏(音圧110〜125dB SPL)を Android 端末1台で長時間・高音質・高画質録画するプロフェッショナル向け録画アプリの Phase 1 成果物。命令書 `android_recording_app_prompt_v2 (1).md` §0〜§5 に基づく。

このフェーズで出力するファイル一覧:

- `settings.gradle.kts` / `build.gradle.kts` (root) / `app/build.gradle.kts`
- `gradle/libs.versions.toml`(Version Catalog)
- `gradle.properties` / `gradlew` / `gradle/wrapper/gradle-wrapper.properties`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/cpp/CMakeLists.txt` + `app/src/main/cpp/jni/native-lib.cpp`(ビルド疎通確認用スタブ。実装は Phase 2)
- `app/src/main/java/com/procamera/recorder/{ProCameraApplication,AppContainer}.kt`
- `app/src/main/java/com/procamera/recorder/ui/MainActivity.kt`(スタブ)
- `app/src/main/java/com/procamera/recorder/service/RecordingService.kt`(スタブ)
- `app/src/main/java/com/procamera/recorder/audio/NativeEngineBridge.kt`(スタブ)
- `app/src/main/res/**`(values, drawable, mipmap-anydpi-v26)
- 本ドキュメント `docs/ARCHITECTURE.md`

**実ビルド検証について**: 本プロジェクトは Claude Code(ファイルシステム直接操作可能なエージェント)が実装しているため、命令書 §0 が前提とするチャット分割出力(「次へ」で継続、トークン上限での中断・再開)ではなく、**実ファイルをリポジトリに書き出す方式**を採用する。またこの環境に JDK 21(Android Studio 同梱 JBR)・NDK r27d・Gradle 9.6.1・SDK cmake 3.31.6 を導入し、上記スタブ構成で **`./gradlew assembleDebug` / `assembleRelease` / `lint` / `testDebugUnitTest` の実ビルドに成功済み**(Kotlin/Compose コンパイル、CMake/ninja による C++20 ネイティブビルド、Oboe の prefab 経由リンクまで通過)。ただし実機・エミュレータは無いため、**録画動作そのものの実機検証は行っていない**。

---

## 採用バージョン一覧(2026-07-13 時点で実在確認済み)

| 項目 | 採用値 | 確認方法 |
|---|---|---|
| compileSdk / targetSdk | 36 (Android 16) | Google Play targetSdk 要件(2026-08-31 以降 API36必須)に一致。SDK Platform `android-36.1` 導入済み |
| minSdk | 29 (Android 10) | 命令書指定どおり |
| AGP (Android Gradle Plugin) | **9.2.1** | Google Maven `maven-metadata.xml` で確認した最新安定版(9.3.0 系はまだ rc) |
| Kotlin | **2.4.0** | Maven Central で確認した最新安定版 |
| Gradle | **9.6.1** | `services.gradle.org/versions/current` で確認 |
| NDK | **27.3.13750724 (r27d, 2024 LTS)** | `android/ndk` wiki で確認。r30 はまだ beta のため現行 LTS は r27d のまま(命令書の想定どおり) |
| CMake (SDK component) | 3.31.6 | Google Maven repo XML から取得・導入 |
| C++ 標準 | C++20 | 命令書指定どおり。NDK r27d の libc++ で問題なくビルド確認済み |
| Jetpack Compose BOM | **2026.06.01** | Google Maven `compose-bom` metadata で確認 |
| androidx.core / core-ktx | **1.18.0** | 1.19.0 は `minCompileSdk=37` を要求し compileSdk=36 と衝突するため 1 段階前の 1.18.0(`minCompileSdk=36`)を採用(実ビルドの AAR metadata チェックで検出・確定) |
| Google Oboe | **1.10.0** | GitHub Releases/Tags で確認した最新タグ。Maven Central 経由の **prefab パッケージ**(`com.google.oboe:oboe:1.10.0`)として消費し、C++ 側は `find_package(oboe CONFIG)` で解決(ソース vendoring はしない) |
| DI | 手動 DI(`AppContainer`) | 後述 |

---

## DI方式の明確化

**手動 DI**(`ProCameraApplication` が保持する `AppContainer` に手で依存を積む方式)を採用する。Hilt/Dagger は導入しない。

理由: 本プロジェクトは単一 `:app` モジュールで、DI グラフの規模も中程度(Camera/Audio/Encoder/Muxer/Service 程度のシングルトン群)。すでに NDK ネイティブビルドでビルド時間の重い構成になっているところへ KSP/KAPT のアノテーション処理を追加するコストが見合わない。将来複数モジュール化や DI グラフが複雑化した場合は Hilt への移行を再検討する(移行コストは小さい規模のうちに留めておく)。

---

## スレッドモデル図

```
┌─────────────────────────────────────────────────────────────────────────┐
│ Main Thread (UI)                                                        │
│  - Compose UI / Choreographer 60fps ポーリング(Peak/RMSメーター表示)     │
│  - CameraDevice/CaptureSession コールバック(Camera2 のデフォルト挙動)     │
│  - StateFlow/SharedFlow の collect                                       │
└───────────────┬───────────────────────────────────────────────────────--┘
                │ Coroutine (Dispatchers.Default / IO) 経由の非同期呼び出しのみ
                │ ※ Audio コールバックスレッドからの直接呼び出しは禁止(§4.2)
┌───────────────▼───────────────────────────────────────────────────────--┐
│ Kotlin Coroutine Workers (Dispatchers.Default / IO)                     │
│  - PtsClockDomain(オフセット計算・単調増加ガード)                        │
│  - SegmentManager(N分毎の Muxer 切替オーケストレーション)                │
│  - ThermalMonitor / StorageMonitor / BackpressureMonitor                │
└───────────────┬─────────────────────────────┬───────────────────────--─┘
                │                              │
┌───────────────▼──────────────┐  ┌────────────▼──────────────────────--──┐
│ Video Encoder Callback Thread │  │ Audio Encoder Thread(専用 Thread)     │
│ (MediaCodec.Callback, 非同期) │  │  - RingBuffer からポーリングドレイン   │
│  - InputSurface 経由でCamera  │  │  - Float32→16bit(TPDF dither)変換     │
│    HALが直接書き込む(アプリ   │  │  - queueInputBuffer                    │
│    コードはPTS/バッファ管理の │  │                                        │
│    み)                        │  │                                        │
└───────────────┬──────────────┘  └────────────┬──────────────────────--─┘
                │  onOutputBufferAvailable       │ onOutputBufferAvailable
                └───────────────┬────────────────┘
                                ▼
                  ┌──────────────────────────┐
                  │ Muxer Writer Thread        │
                  │ (専用 Thread。両トラック   │
                  │  のFORMAT_CHANGED後start)  │
                  │  - ペンディングキュー保持  │
                  │  - セグメント境界でシーム  │
                  │    レス切替               │
                  └──────────────────────────┘

──────────────────────────── C++ / Native 側 ────────────────────────────

┌───────────────────────────────────────────────────────────────────────┐
│ Oboe Audio Callback Thread (RT優先度, Oboe/AAudioが生成・管理)          │
│  【禁止事項厳守】malloc/new/delete, Mutex, Sleep, CondVar, ブロッキング │
│  I/O, 大量ログ, JNI呼び出し 一切禁止(§4.2)                             │
│                                                                         │
│  Input stream                                                          │
│    → Biquad 3-band EQ (Atomicダブルバッファから係数読取)                │
│    → Safety Limiter (soft clip, -1.0dBFS)                              │
│    → Peak/RMS 計算 → Atomic<float> へ書込(Kotlin側がpull)              │
│    → SPSC Lock-Free RingBuffer へ push(→ Audio Encoder Thread が drain)│
│    → (ヘッドホン検出時のみ) パススルー出力ストリームへ書込               │
└───────────────────────────────────────────────────────────────────────┘
```

**設計思想**: オーディオコールバックスレッドは他のどのスレッドとも直接同期しない。Kotlin 側・Video 側との通信は SPSC Lock-Free RingBuffer と Atomic 変数のみを経由し、コールバックスレッドが「待たされる」経路を物理的に作らない。これにより爆音環境下でのバースト処理時間超過(xrun)リスクを最小化する。

---

## データフロー図

```
                         ┌───────────────────┐
                         │  Camera2 HAL       │
                         └─────────┬──────────┘
                    ┌───────────────┴────────────────┐
                    ▼                                  ▼
        ┌───────────────────────┐         ┌──────────────────────────┐
        │ Preview Surface        │         │ MediaCodec InputSurface   │
        │ (SurfaceTexture)       │         │ (createInputSurface)      │
        │  → Focus Peaking /     │         │  → SENSOR_TIMESTAMP 付き  │
        │    Digital Zoom 後処理 │         │    フレームが直接エンコーダへ│
        │  ※録画ストリームに影響 │         │  → Video Encoder Callback │
        │    を与えない独立経路  │         │    Thread                 │
        └───────────────────────┘         └────────────┬──────────────┘
                                                          │ Video PTS
                                                          ▼
  ┌──────────────┐    Float32 PCM   ┌──────────────────────┐         ┌───────────────┐
  │ Oboe Input   │ ───────────────► │ SPSC RingBuffer        │──────► │ Audio Encoder  │
  │ Stream       │  (Audio Callback │ (Lock-Free, cache-line │ drain  │ Thread          │
  │ (マイク)      │   Thread内で push)│  aligned)              │        │ (16bit+dither)  │
  └──────┬───────┘                  └────────────────────────┘        └───────┬────────┘
         │ (ヘッドホン検出時のみ)                                              │ Audio PTS
         ▼                                                                    │ (累積サンプル数/48000)
  ┌──────────────┐                                                            │
  │ Oboe Output   │                                                            │
  │ Stream        │                                                            │
  │ (モニタ出力)  │                                                            │
  └──────────────┘                                                            │
                                                                                │
         ┌──────────────────────────────────────────────────────────────────┘
         │
         ▼
  ┌─────────────────────────────┐
  │ PtsClockDomain               │  Video/Audio 両PTSを録画開始=0の同一エポックへ正規化
  │ (単調増加ガード含む)          │  UNKNOWNクロック較正はここで実施(後述)
  └───────────────┬───────────────┘
                   ▼
  ┌─────────────────────────────┐
  │ Muxer Writer Thread          │  両トラックFORMAT_CHANGED後にstart。
  │ (ペンディングキュー→          │  N分毎にセグメント切替(次Muxerを事前準備し
  │  セグメント分割)              │  キーフレーム境界でシームレス切替)
  └───────────────┬───────────────┘
                   ▼
        MediaStore (Movies/ProCamera/*.mp4, IS_PENDING運用)
```

---

## PTS同期設計(詳説)

### Video PTS

`createInputSurface()` 経由のフレームは Camera HAL が `SENSOR_TIMESTAMP` 由来の PTS を自動付与する。起動時に `SENSOR_INFO_TIMESTAMP_SOURCE` を確認し、次の2経路に分岐する。

- **`TIMESTAMP_SOURCE_REALTIME`**: 公式ドキュメント上、この場合の `SENSOR_TIMESTAMP` は `SystemClock.elapsedRealtimeNanos()`(= `CLOCK_BOOTTIME`)と同一クロックドメインであることが規定されている。Audio 側アンカー(後述、`CLOCK_MONOTONIC` 系)との差分は録画開始時に一度だけ `elapsedRealtimeNanos() - (Audio アンカー時点の同義クロック値)` で確定できる。
- **`TIMESTAMP_SOURCE_UNKNOWN`**: 下記の較正手順が必須(命令書 §4.3 で具体化を要求されている箇所)。

### `UNKNOWN` クロックソース較正アルゴリズム(具体案)

**確信度の明示**: 以下はベンダー実装のクロック特性(オフセットのみか、ドリフトも持つか)を実機なしに断定できないため、「オフセットは一定」という仮定に立った較正手順であり、ドリフトの有無自体は録画中の継続監視でしか判定できないという前提を置く。

1. **サンプリング**: 録画開始後、最初の `K = 10` フレームについて、`CameraCaptureSession.CaptureCallback#onCaptureCompleted` が呼ばれた瞬間に `System.nanoTime()`(Android では `CLOCK_MONOTONIC`)を取得し、同コールバックの `TotalCaptureResult` から同一フレームの `SENSOR_TIMESTAMP` を取得する。`onCaptureCompleted` と InputSurface へ渡る実フレームは同一キャプチャリクエストに属するため、両者は同一フレームの2つのタイムスタンプ表現とみなせる。
2. **オフセット推定**: 各サンプル `i` について `offset_i = nanoTime_i - sensorTimestamp_i` を計算し、**中央値**(外れ値耐性のため平均ではなく median を採用)を較正オフセット `offset_calibrated` として確定する。
3. **適用**: 以降到着する全フレームの Video PTS を `videoPts = sensorTimestamp + offset_calibrated` として `CLOCK_MONOTONIC` 系(Audio PTS と同じ基準)へ正規化する。
4. **較正完了までの扱い**: Muxer は両トラックの `INFO_OUTPUT_FORMAT_CHANGED` 受領後まで `start()` しない(§4.4 の既定動作)ため、較正中の最初の数フレームはこのペンディング期間内に収まる想定であり、較正未完了フレームがそのまま出力される実害は小さい。ただし理論上のトレードオフとして、K=10 フレーム分(30fps なら約 333ms)は較正確定前のため、この間に取得したフレームの PTS 精度はやや低い。
5. **継続監視**: 較正はオフセットの一点推定に過ぎず、ドリフト(較正後に both クロックの進み方が乖離するケース)を検出できない。そのため §4.3 で規定される定期的な `AudioStream::getTimestamp()` によるオーディオ HW クロックと monotonic クロックの乖離監視と同様に、以後も定期的に `onCaptureCompleted` タイミングでオフセットを再サンプリングし、`offset_calibrated` からの乖離が閾値(暫定 15ms、Audio 側と同一閾値)を超えたら警告ログ+UI表示に留める(自動補正はしない。理由: サンプリング自体に数msのスケジューリング揺らぎが避けられず、揺らぎに対して自動再較正をかけるとPTSが逆に不安定化するリスクがあるため)。

### Audio PTS

録画開始時、最初に取得したオーディオバーストに対して `CLOCK_MONOTONIC` 上のアンカー時刻を記録。以降は壁時計に依存せず「累積サンプル数 ÷ 48000」で厳密算出する(ドリフトフリー)。

### クロックドメイン統合とPTS単調性

`PtsClockDomain` クラスが Video/Audio 双方のPTSを録画開始=0のエポックへ正規化する。Muxer 手前で「直前に書き込んだPTS以下の値が来た場合は破棄しログを残す」単調増加ガードを実装し、クロックの逆行・重複によるMuxer破損を防ぐ。

---

## クラス構成(パッケージ構成)

```
com.procamera.recorder
├── ProCameraApplication / AppContainer      … 手動DIコンポジションルート
├── camera/
│   ├── CameraCapabilityInspector             … HW Level/4K対応/ISO・露出範囲等の実機検証ロジック
│   ├── Camera2Controller                     … CaptureSession/Request管理、マニュアル露出/AF/WB
│   ├── FocusController                       … Touch-to-Focusハイブリッド機構(§4.1)
│   └── ColorTemperatureConverter              … Kelvin→RGGBゲイン変換
├── audio/
│   ├── NativeEngineBridge                     … JNIブリッジ(C++ Oboeエンジンへの唯一の入口)
│   ├── AudioDeviceRouter                      … registerAudioDeviceCallback監視・優先順位ルーティング
│   └── AudioMeterPoller                       … Choreographerでのpull方式ポーリング(§4.5)
├── encoder/
│   ├── VideoEncoder                           … MediaCodec非同期モード、InputSurface
│   └── AudioEncoder                           … RingBufferドレイン専用スレッド
├── muxer/
│   ├── SegmentedMuxerController                … N分毎シームレス分割(§4.4)
│   └── PtsClockDomain                          … PTS正規化・単調増加ガード・UNKNOWN較正
├── service/
│   └── RecordingService                        … Foreground Service(camera|microphone)
├── ui/
│   ├── MainActivity / ProCameraRoot(Compose)
│   └── (CameraControlsPanel, AudioControlsPanel, RecordingStatusBar 等 Phase4で追加)
└── utils/
    ├── ThermalManager                          … API29/30分岐(§4.6)
    ├── CrashHandler
    └── Logger

cpp/
├── engine/   … OboeFullDuplexEngine(Full-Duplex構成)
├── dsp/      … BiquadEq(RBJ Cookbook)、SafetyLimiter、PeakRmsMeter
├── buffer/   … SpscRingBuffer(cache-line整列、Acquire/Release memory ordering)
└── jni/      … JNIバインディング(唯一Kotlin側と接する層)
```

---

## ディレクトリ構造

実際にリポジトリへ作成済み(詳細は各ファイル参照):

```
ProCamera/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/gradle-wrapper.properties
├── gradlew
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/procamera/recorder/{camera,audio,encoder,muxer,service,ui,utils}/
│       │   ├── res/{values,drawable,mipmap-anydpi-v26}/
│       │   └── cpp/{engine,dsp,buffer,jni}/ + CMakeLists.txt
│       ├── test/java/com/procamera/recorder/        … JUnit (Phase5)
│       └── androidTest/java/com/procamera/recorder/ … Instrumented tests (Phase5)
├── docs/ARCHITECTURE.md  … 本ファイル
└── .github/workflows/    … Phase5で追加
```

---

## 前提・判断ログ

命令書の記述だけでは一意に決まらず、実装者(Claude)が判断を下した箇所。後続フェーズはこのログと矛盾しないよう実装する。

1. **アプリ名 / パッケージ名**: 命令書に指定がないため、リポジトリ名 `ProCamera` に合わせ、アプリ名 "ProCamera"、`applicationId`/`namespace` = `com.procamera.recorder` とした。
2. **AGP 9 のビルトインKotlin**: 実ビルド検証で判明した重要事実として、AGP 9.0 以降は Kotlin サポートがビルトイン化され、`org.jetbrains.kotlin.android` プラグインを併用するとビルドエラーになる(`kotlin-compose` プラグインのみ明示適用すればよい)。Version Catalog・root/app の `build.gradle.kts` はこれに合わせて構成済み。命令書は「Kotlin 2.x」を実装時点の最新版と確認のうえ採用するよう指示しているため、この対応はその指示の範囲内の判断とみなす。
3. **`kotlinOptions`/`compilerOptions` DSL**: AGP 9 + KGP 2.4.0 のこの組み合わせでは `android.compilerOptions {}` DSL も解決できなかった(実ビルドで確認)。Kotlin の jvmTarget は `compileOptions`(Java 17)からビルトインKotlinが自動導出する挙動に依存しており、明示指定はしていない。将来 AGP/KGP を更新する際は改めて確認が必要。
4. **androidx.core-ktx バージョン**: 1.19.0 は `minCompileSdk=37` を要求し、compileSdk=36(Play targetSdk要件に合わせた値)と衝突したため、1.18.0(`minCompileSdk=36`)を採用。compileSdkを37へ上げない理由は、命令書 §1.1 が明示的にcompileSdk=36を指定し、37を要求する既存Google Playポリシーの根拠が(検索時点で)確認できなかったため。
5. **Oboe の導入方法**: ソースを vendoring せず、Maven の **prefab パッケージ** (`com.google.oboe:oboe:1.10.0` + `android.buildFeatures.prefab = true` + CMake `find_package(oboe CONFIG)`)として消費する。ソース vendoring より依存管理・CI再現性の面で優れ、Oboe 公式が推奨する現行の配布形態であるため。
6. **ABI フィルタとx86_64の扱い**: 命令書 §4.8「CI上のホストGTest実行やエミュレータ検証で必要な場合のみx86_64を補助的に含める」を、**出荷用(release/debug通常ビルド)のABIはarm64-v8aのみとし、x86_64はPhase5のCI設定内でエミュレータ実行時のみオーバーライドして含める**、と解釈した。`app/build.gradle.kts` の `defaultConfig.ndk.abiFilters` は arm64-v8a 固定。
7. **PCMフォーマット(Float32→16bit)**: MediaCodecの標準AAC-LCエンコーダ(`OMX.google.aac.encoder`/`c2.android.aac.encoder`)はPCM_16BIT入力を前提とすることが広く確認できる一方、PCM_FLOAT入力の全FULL級端末での動作は実機検証なしに断定できない。安全側に倒し、**Oboeから得たFloat32をエンコーダ手前でTPDFディザ付き16bit整数へ変換する**方針とする(実装はPhase2/3)。
8. **画面回転の扱い**: 120分の連続録画中にActivity/Camera2セッションが再生成されるとPTS連続性が壊れるリスクが大きいため、`android:screenOrientation="locked"` でActivity自体の回転再生成を止め、UI要素(アイコン等)の回転表示のみ別途 `OrientationEventListener` 等で対応する設計とする(Phase4で実装)。命令書の「画面回転を含むライフサイクル管理」は、録画継続性を最優先する前提のもとこの意味で満たす。
9. **セグメント分割デフォルト長**: 命令書の「デフォルト5分、設定可」をそのまま採用。
10. **UNKNOWNクロック較正のサンプル数K**: 上記の通りK=10フレームを暫定値として採用(実機での揺らぎ計測結果次第でPhase3実装時に調整の余地を残す)。

**矛盾・実現不可能な要求の有無**: 命令書自体が主要な既知の落とし穴(minSdk29と`getThermalHeadroom`のAPIレベル不整合、Float/16bit変換精度、UNKNOWNクロック較正の要求)を事前に自己解決する形で書かれており、Phase1着手時点で追加の承認が必要な技術的矛盾・実現不可能な要求は見つからなかった。上記1〜10は「粒度不足を埋めた判断」であり、矛盾の指摘とは別categoryである。
