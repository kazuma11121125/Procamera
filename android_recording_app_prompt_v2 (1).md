# 命令

あなたはAndroidネイティブ開発(C++/NDK)、リアルタイムDSP、Camera2、MediaCodec、Google Oboe、GitHub Actionsに精通したシニアソフトウェアアーキテクト兼リードエンジニアです。

開発対象は、**学祭のライブバンド演奏(音圧110〜125dB SPLの爆音環境)をAndroid端末1台で高音質・高画質に長時間録画する、プロフェッショナル向け動画撮影アプリ**です。設計・保守性・リアルタイム性能・安定性を最優先し、実運用を前提とした商用品質で実装してください。

**確信度の明示**: Camera2のベンダー実装差異など、実機検証なしには断定できない事項について、それらしい具体値で埋めることは禁止します。「仕様上は〜だが端末依存のため実機で要確認」のように、断定できる事実と推測・一般論を文中で明確に区別してください。

---

# 0. 出力プロトコル(最重要・厳守)

全コードを1回の応答に収めることは物理的に不可能なため、以下のフェーズに分割して出力します。**各フェーズ内のファイルは省略・疑似コード・「// TODO」・「// 以下同様」を一切禁止**し、そのままプロジェクトに配置してビルド可能な完全形で出力してください。

- **Phase 1**: アーキテクチャ全体説明(スレッドモデル図、PTS同期設計、データフロー図)、クラス構成、ディレクトリ構造、Gradle/CMake/Manifestの全ビルド設定
- **Phase 2**: C++レイヤー全実装(Oboe Engine、DSP、Lock-Free RingBuffer、JNIバインディング)
- **Phase 3**: Kotlinレイヤー前半(Camera2制御、Encoder、Muxer、PTS管理)
- **Phase 4**: Kotlinレイヤー後半(UI、Foreground Service、権限、CrashHandler、熱管理)
- **Phase 5**: ユニットテスト(GTest/JUnit)、GitHub Actionsワークフロー、導入手順と実機検証手順

各フェーズの冒頭で「このフェーズで出力するファイル一覧」を宣言し、末尾で「次フェーズの内容」を予告してください。私が「次へ」と入力したら次フェーズを出力します。

**中断・再開ルール**: フェーズ途中でトークン上限に達しそうな場合、ファイルの途中では絶対に中断しないでください。1ファイルが長大でそれ自体を分割せざるを得ない場合に限り、関数/クラス単位などの論理的な区切りで中断し、中断宣言には「中断したファイルの完全パス」「直前に完了した関数・クラス名」「このフェーズで残っているファイル一覧」を明記して、続きから機械的に再開できるようにしてください。また、各ファイルの出力直前にはそのファイルの完全パスを見出しまたはコメントとして明記し(例: `// path: app/src/main/cpp/engine/OboeEngine.cpp`)、後からファイルへ分離しやすい形式を維持してください。

技術的に矛盾する要求・実現不可能な要求をこの命令書内に発見した場合は、**黙って独自解釈せず、代替案とトレードオフを明示して私の承認を得てから**実装してください。**この原則はPhase 1に限らず全フェーズに適用します**。Phase 2以降の実装中に、Phase 1の時点では気づけなかった矛盾や実現不可能な点が判明した場合も同様に、その場で手を止めて代替案を提示し、承認を得てから続行してください。

**バージョン表記の扱い**: 本書中のSDK/NDK/ライブラリのバージョン指定(§1.1等)は本書作成時点のものであり、実装時にはすでに古くなっている可能性があります。実装時点で入手可能な最新の安定版(NDKはLTSを優先)を確認したうえで採用し、**Phase 1冒頭に採用した全バージョン(compileSdk/targetSdk/minSdk、AGP、NDK、Kotlin、Compose BOM、Oboe等)の一覧表を明記**してください。特にGoogle PlayのターゲットAPIレベル要件は年1回引き上げられており、2026年8月31日以降は新規アプリ・既存アプリの更新提出にAndroid 16 (API 36) 以上への対応が必須となるため、本書の指定値を鵜呑みにせず必ず実装時点の要件を確認してください。

**Phase 1末尾の前提整理**: Phase 1の末尾に、本書の記述だけでは一意に決まらず実装者(あなた)が判断を下した箇所を「前提・判断ログ」として箇条書きで列挙してください(明確な矛盾の指摘とは別に、単なる仕様の粒度不足を埋めた箇所の記録です)。後続フェーズはこのログと矛盾しないよう実装してください。

---

# 1. 前提条件と非機能要件(数値目標)

## 1.1 対象環境
- minSdk 29 (Android 10) / targetSdk 36 / compileSdk 36 (2026年8月31日以降のGoogle Play target APIレベル要件引き上げ(Android 16 / API 36)に対応させたもの。実装時点でさらに新しい要件が出ていればそちらを優先し、Phase 1で採用値を明記すること)
- Camera2 `INFO_SUPPORTED_HARDWARE_LEVEL` が **FULL 以上**の端末を主対象とする。LIMITED端末では起動時に機能チェックを行い、非対応機能(Manual WB等)をUI上でグレーアウトすること
- **対象カメラの選定**: 背面の標準(メイン/ワイド)レンズを既定カメラとする。`CameraManager`でカメラID列挙時、単純な配列インデックスに依存せず`LENS_FACING`・焦点距離等の特性から標準レンズを判別して既定選択すること。複数の背面レンズ(超広角・望遠等)を持つ端末では設定画面から切替可能な構造とし、切替時は対象レンズのHW Level・対応解像度/FPSを再検証すること
- **4K対応可否の実機検証ロジック**: `INFO_SUPPORTED_HARDWARE_LEVEL=FULL`であっても、3840x2160@30fps HEVCエンコードの実サポートを保証しない。起動時に`MediaCodecList`/`CodecCapabilities.getVideoCapabilities()`で対象解像度・fps・ビットレートの組み合わせが実際にサポートされるか検証し、非対応時は下記フォールバック(1920x1080@60fps H.264)へ自動移行すること
- NDK r27 LTS(実装時点でより新しいLTSが安定版として出ていればそちらを優先し、Phase 1で確定値を明記) / C++20 / Kotlin 2.x / Jetpack Compose(いずれも実装時点の最新安定版を確認のうえ採用し、Phase 1で確定値を明記)

## 1.2 性能目標(受け入れ基準)
| 項目 | 目標値 |
|---|---|
| 映像 | 3840x2160@30fps HEVC 50Mbps(端末非対応時 1920x1080@60fps H.264 20Mbpsへフォールバック) |
| 音声 | AAC-LC 48kHz Stereo 256kbps |
| A/V同期ずれ | 連続120分録画後も **±20ms以内**(ドリフト累積ゼロ設計) |
| Audioコールバック処理時間 | バースト長の**50%以下**(例: 48kHz/192frames → 2ms以下) |
| Audio glitch (xrun) | 120分録画で0回を目標、発生時はカウントしUIに表示 |
| ドロップフレーム | 通常温度下で0.1%未満 |
| 連続録画 | 外部給電・機内モード想定で最低120分、熱制限時も録画継続を最優先 |
| **ストレージ書込スループット** | 映像50Mbps+音声256kbps ≈ 実測6.3MB/s前後の持続シーケンシャル書込を120分間維持できること。録画開始前に書込先ボリュームの実効速度を簡易ベンチマークするか、Encoder出力〜Muxer間のキュー滞留量を継続監視し、閾値超過(バックプレッシャーの兆候)でUI警告を出すこと |

(補足: ビットレート側の目標だけでなく、低速ストレージやサーマルスロットリングで劣化するeMMC/UFSコントローラ等、書込側のボトルネックも受け入れ基準に含めています)

## 1.3 検証端末
Camera2のFULLレベル実装はOEM間で挙動差が大きいため、最低2メーカー以上(例: Pixel系+Samsung/Xiaomi等の他OEM系)の実機での動作確認を前提とする。Phase 5の「実機検証手順」に、検証対象端末の選定基準と確認項目一覧を含めること。

---

# 2. 開発方針

- 可読性・保守性・拡張性の確保(単一責務、依存性注入可能な構造)
- **DI方式の明確化**: 「依存性注入可能な構造」とあるが、具体的な採用手段(Hilt/Daggerの導入か、手動DIか)は本書内で未指定。Phase 1でどちらを採るか明示し、採用理由(ビルド時間・学習コスト・本アプリの規模感等)を一言添えること
- **非同期処理方針**: Kotlin層の状態管理・UI更新はCoroutines + StateFlow/SharedFlowを基本とする。C++側のAudioコールバックスレッドとは完全に分離し、Coroutineディスパッチャの切替やJNI経由の呼び出しをオーディオコールバック内から行わないこと(§4.2の禁止事項と整合させる)
- C++レイヤーの完全なメモリ安全性(RAII、`std::unique_ptr`/`std::shared_ptr`優先、生ポインタの所有は禁止)
- Android標準APIの正しいライフサイクル管理(画面回転・バックグラウンド遷移・電話着信割り込みを含む)
- エラー処理の完全実装: Kotlinは`Result`型または例外+リカバリ、C++はエラーコード/`expected`パターン。**録画中のエラーは可能な限り録画継続を優先**し、続行不能時のみ安全にファイナライズして停止
- PTS同期・スレッド管理・Lock-Freeアルゴリズムには設計思想をコメントで詳述

---

# 3. 技術スタック・プロジェクト構成

【技術スタック】
- UI & Main Logic: Kotlin + Jetpack Compose
- Camera: Camera2 API(CameraX使用禁止)
- Audio I/O & DSP: C++20 / NDK / Google Oboe(JavaのAudioRecord禁止)
- Encode: MediaCodec(HEVC優先、非対応時H.264 / AAC-LC)
- Container: MediaMuxer(.mp4)+ 後述のセグメント分割戦略
- Native Build: CMake / CI: GitHub Actions
- 依存関係バージョンはGradle Version Catalog(`libs.versions.toml`)で一元管理する

【プロジェクト構成】
単一`:app`モジュールを基本とする(本プロジェクトの規模では過度な多モジュール化を優先しない。将来分割が必要と判断した場合はPhase 1で代替案として提案してよい)。

【ディレクトリ構成】
    app/src/main/
     ├── java/.../camera/    (Camera2制御、Capabilityチェック)
     ├── java/.../audio/     (Oboe JNIラッパー、AudioDeviceルーティング)
     ├── java/.../encoder/   (MediaCodec Video/Audio)
     ├── java/.../muxer/     (MediaMuxer、セグメント管理)
     ├── java/.../service/   (Foreground Service)
     ├── java/.../ui/        (Compose)
     ├── java/.../utils/     (Logger、CrashHandler、Thermal)
     └── cpp/
          ├── engine/        (Oboe Full-Duplex Engine)
          ├── dsp/           (EQ、Peak/RMSメーター)
          ├── buffer/        (Lock-Free SPSC RingBuffer)
          └── jni/           (JNI Bindings)

---

# 4. コア機能要件

## 4.1 映像パイプライン (Camera2)

オート機能を完全に無効化した完全マニュアル制御を実装してください。

- **Manual Exposure**: `CONTROL_AE_MODE_OFF` + `SENSOR_SENSITIVITY` / `SENSOR_EXPOSURE_TIME` 固定。設定可能範囲は `SENSOR_INFO_SENSITIVITY_RANGE` 等から動的取得し、UIスライダーの上下限に反映
- **Touch to Focus (One-shot AF)**: 基本状態は `CONTROL_AF_MODE_OFF` + `LENS_FOCUS_DISTANCE` のMF固定とするが、プレビュー画面のタップ時のみ一時的に `CONTROL_AF_MODE_AUTO` へ移行し、タップ座標(`METERING_RECTANGLES`)でAFを実行(`AF_TRIGGER_START`)。合焦(または失敗)後に自動で `CONTROL_AF_MODE_OFF` へ戻し、合焦位置の `LENS_FOCUS_DISTANCE` を取得してUI側のマニュアルスライダーへ反映・ロックするハイブリッド機構を実装すること
- **Manual WB**: `CONTROL_AWB_MODE_OFF` + `COLOR_CORRECTION_MODE_TRANSFORM_MATRIX` + `COLOR_CORRECTION_GAINS`。色温度(2500K〜8000K)からRGGBゲインへの変換ロジックを実装し、UIから録画中も動的変更可能とすること
- **Surface構成**: プレビュー用SurfaceView(またはSurfaceTexture)と、MediaCodec `createInputSurface()` の2系統へ同時ストリーム出力。両者の解像度・fps設定の整合性チェックを実装
- **FPS固定**: `CONTROL_AE_TARGET_FPS_RANGE` に依存せず、`SENSOR_FRAME_DURATION` で厳密固定(AE OFF時の正しい手法を用いること)
- ステージ照明のフリッカー(LED PWM)対策として、シャッタースピードのプリセットに 1/50・1/60・1/100・1/120 を用意
- **v1スコープ外(明示的非対応)**: 10bit HEVC/HDR録画、複数カメラの同時録画(マルチカムAPI)は本バージョンでは対象外とする。実装中にこれらを提案したい場合はPhase 1で代替案として提示するに留め、無断で実装に組み込まないこと

## 4.2 音声パイプライン (Oboe & C++ DSP)

- **Stream設定**: `PerformanceMode::LowLatency`、`SharingMode::Exclusive`(取得失敗時Sharedへフォールバックしログ記録)、Float、48000Hz、Stereo。デバイスネイティブレートが48kHz以外の場合はOboeのリサンプラ品質設定を明示
- **音源設定**: `InputPreset::Unprocessed` を必須とし、OSのAGC/ノイズ抑制を確実にバイパス。取得失敗時は `VoiceRecognition` へフォールバック
- **入力ルーティング**: `AudioManager.registerAudioDeviceCallback` で監視し、**USB Audio > 有線Headset Mic > 内蔵Mic** の優先順で `setInputDevice`(deviceId明示指定)。録画中のデバイス抜き差し時は、無音を挿入してサンプル連続性を保ちつつ再ルーティング(PTS連続性を壊さないこと)
- **爆音対策(必須)**: 内蔵MEMSマイクの音響過負荷点(AOP)は一般に120dB SPL前後であり、**アナログ段でクリップした歪みはDSPでは除去不能**である。この事実をアプリ内ヘルプに明記し、(1) USBオーディオIF+外部マイクの使用を推奨する警告UI、(2) 内蔵マイク使用時の入力ゲイン最小化ガイド、を実装すること
- **Full Duplex モニタリング**: 入力→DSP→(a)エンコーダ用RingBuffer、(b)出力ストリームへパススルーの分岐構成。**ハウリング防止のため、モニタ出力は有線/USBヘッドホン検出時のみ有効化可能**とし、内蔵スピーカーへのルーティングは禁止
- **DSP (3Band Parametric EQ)**: Biquad(RBJ Cookbook準拠)によるピーキングEQ。初期値: Low 80Hz Q=0.8 −6dB / Mid 1500Hz Q=1.2 +3dB / High 8000Hz Q=0.7 −4dB。係数はUIスレッドで計算し、Atomicなダブルバッファでコールバックへ受け渡し(コールバック内での係数計算・補間切替時のクリックノイズ対策を実装)
- **セイフティリミッター**: EQ通過後の信号に対し、ブリックウォールリミッターまたはソフトクリッパー(目安閾値: −1.0dBFS。Look-aheadなしの簡易実装で可)を追加すること。理由: Mid帯+3dBブースト等のEQ処理自体が、入力段では発生していなかった新たなデジタルクリッピング(inter-sample overs含む)を生む可能性があるため。このリミッターもAudioコールバック内制約を厳守した実装とすること
- **【厳守】Audio Callback内の禁止事項**: `malloc`/`new`/`delete`系、Mutexロック、Sleep、Condition Variable、ブロッキングI/O、大量ログ、JNI呼び出し。データ受け渡しは**SPSC Lock-Free RingBuffer + Atomic変数のみ**。RingBufferはFalse Sharing対策(cache line alignment)とmemory ordering(acquire/release)の根拠をコメントで説明すること
- **エンコーダ渡し前のフォーマット変換精度**: エンコーダへ渡すPCMフォーマット(Float32のままか16bit整数か)は、使用するMediaCodec/AudioFormatの実際の要求仕様をPhase 1で確認したうえで決定すること。Float→16bit整数への変換が必要な場合は単純な切り捨てではなく、TPDF等のディザを用いた丸めを実装し、量子化歪みを回避すること(理由をコメントに記載)

## 4.3 PTS同期設計(最重要要件)

長時間録画で音ズレを絶対に発生させないこと。以下の設計を採用してください(単純に`System.nanoTime()`でVideo PTSを打刻する方式は、InputSurface経由ではフレームにカメラHALのタイムスタンプが付与されるため**誤り**です)。

- **Video PTS**: `createInputSurface()` 経由のフレームには `SENSOR_TIMESTAMP` 由来のタイムスタンプが自動付与される。`SENSOR_INFO_TIMESTAMP_SOURCE` を確認し、`REALTIME`(CLOCK_BOOTTIME)の場合と `UNKNOWN` の場合それぞれのクロックドメイン整合処理を実装
- **`UNKNOWN`クロックソース時の較正手順の明文化(必須)**: `SENSOR_INFO_TIMESTAMP_SOURCE`が`UNKNOWN`の場合、`SENSOR_TIMESTAMP`は他のクロックと直接比較できません。この一文だけでは実装方針が一意に決まらないため、具体的な較正アルゴリズム(例: 録画開始直後の数フレームについて、フレームがアプリ側コールバックに到達した時刻をCLOCK_MONOTONIC/CLOCK_BOOTTIMEで記録し、その平均オフセットをSENSOR_TIMESTAMPとの変換係数として採用する、等)を**Phase 1で具体的に提案し、較正精度や録画開始直後数フレームの精度低下リスクなどのトレードオフを明示してから実装**すること
- **Audio PTS**: 録画開始時、最初に取得したオーディオバーストに対して単調クロック上のアンカー時刻を記録。以降は **累積サンプル数 ÷ 48000** から厳密算出(壁時計に依存しない)。これによりオーディオクロック基準の無ドリフトPTSとなる
- **クロックドメイン統合**: VideoとAudioのPTSを同一エポック(録画開始時点=0)へ正規化するオフセット管理クラスを実装。オーディオHWクロックとmonotonicクロックの乖離は `AudioStream::getTimestamp()` で定期測定し、閾値(例: 15ms)超過時は警告ログ+UI表示(自動リサンプル補正は行わず、測定と可視化に留める。理由をコメントで説明)
- PTSの単調増加保証(同値・逆行フレームのガード)を Muxer 手前で実装

## 4.4 エンコードとMuxer

- Video: MediaCodec非同期モード(`setCallback`)、InputSurface方式。`MediaFormat` にはビットレートモード(CBR優先、非対応時VBR)、Iフレーム間隔1秒、Profile/Level を明示
- Audio: 専用エンコーダスレッドがRingBufferからドレインし `queueInputBuffer`
- Muxer開始条件: Video/Audio両トラックの `INFO_OUTPUT_FORMAT_CHANGED` 受領後に `start()`。それ以前の出力バッファは破棄せずペンディングキューに保持して開始後に書き出し
- **ファイル破損対策(必須)**: MediaMuxerは `stop()` 前にプロセスが死ぬとmoov未書込でファイル全損となる。対策として**N分ごと(デフォルト5分、設定可)のシームレスなセグメント分割**(次セグメントのMuxerを事前準備し、キーフレーム境界で切替、PTS連続)を実装すること。**Video・Audio両トラックとも**サンプルの欠落・重複なく次セグメントのMuxerへ引き継ぐこと。分割方式のトレードオフ(単一巨大ファイル vs 分割)はPhase 1で説明
- **書込バックプレッシャー検知**: Encoder出力〜Muxer書込間のキュー滞留量を監視し、§1.2のストレージ書込スループット要件における閾値超過判定に用いること
- 停止シーケンス: EOS送出→全バッファドレイン→`stop()`→`release()` の順序保証。ストレージは MediaStore API(`RELATIVE_PATH=Movies/<AppName>`)へ保存し、`IS_PENDING` フラグを正しく運用
- 空き容量監視: 現在ビットレートから残り録画可能時間を推定表示し、閾値(残1GB)で警告、限界で安全停止

## 4.5 UI & アシスト機能

- Camera UI: ISO / Shutter / Focus / WB(Kelvin) / FPS のスライダー・トグル(範囲は端末Capabilityから動的生成)
- Audio UI: 現在の入力デバイス表示、入力Gain、EQ 3バンド(Freq/Q/Gain)、モニタON/OFF(ヘッドホン未接続時は無効化)
- Recording Status: RECボタン、経過時間、セグメント番号、Encoderキュー滞留状況、ドロップフレーム数、xrun回数、端末温度、残り録画可能時間
- Audio Meter: C++側でPeak/RMS(dBFS)を計算しAtomicに保持、Kotlin側がChoreographerで約60fpsポーリング(JNI push方式ではなくpull方式とし、理由をコメントで説明)。−0.1dBFS到達で「CLIPPING」警告を3秒保持表示
- Focus Assist: MF操作中のみプレビューへDigital Zoom(`SCALER_CROP_REGION`)とFocus Peaking(プレビューストリームのみへの後処理)を適用。**録画ストリームには一切影響を与えない**構成をPhase 1のデータフロー図で示すこと

## 4.6 安定性・熱管理・デバッグ

- 録画はカメラ+マイクタイプの **Foreground Service** 上で実行(Manifest: `foregroundServiceType="camera|microphone"`、Android 14+の起動時権限要件に準拠)。画面OFF時も録画継続
- `FLAG_KEEP_SCREEN_ON` と `WAKE_LOCK` の適切な取得・解放
- **熱管理**: `PowerManager.addThermalStatusListener` + `getThermalHeadroom()` を監視し、SEVERE到達で段階的品質低下(プレビュー解像度低下→プレビューfps低下→ユーザーへ警告。**録画品質の自動変更は行わずユーザー判断に委ねる**)
- **API levelの不整合に注意(必須修正)**: `addThermalStatusListener`はAPI 29以降で利用可能だが、`getThermalHeadroom()`はAPI **30**以降でのみ利用可能であり、本書のminSdk 29とは不整合です。API 29(Android 10)端末では`getThermalHeadroom()`を呼び出さず、`getCurrentThermalStatus()`(ヘッドルーム予測なしのステータスのみ)に基づく段階的品質低下ロジックへフォールバックすること。`Build.VERSION.SDK_INT`によるガードを必ず実装すること
- **メモリ低下対応**: `onTrimMemory`/`onLowMemory`を実装し、録画継続を最優先しつつ非必須リソース(プレビュー解像度、Focus Peaking処理等)を段階的に縮退させること
- 権限フロー: CAMERA / RECORD_AUDIO / POST_NOTIFICATIONS の要求・拒否時UI・設定誘導を完全実装
- **バッテリー最適化除外(推奨)**: カメラ+マイクのFGSはDoze/App Standbyの制約を受けにくいが、外部給電なしでの長時間録画継続性を高めるため`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`への導線をUIに用意することを推奨する(必須ではなく、Phase 1で採否を判断してよい)
- Crash Handler: `Thread.setDefaultUncaughtExceptionHandler` でスタックトレースを端末内に保存し、**可能であればクラッシュ前に録画中セグメントのファイナライズを試行**
- Native Debug: CMakeでdebugビルド時のみASan/UBSanを有効化(releaseには含めない)。`ndk.abiFilters` と ASan 用の `wrap.sh` 構成も出力

## 4.7 テスト

Google Test対象: Biquad EQ(周波数応答の数値検証)、Peak/RMS計算、Lock-Free RingBuffer(マルチスレッドストレステスト含む)、PTS計算・単調増加ガード、Kelvin→RGGBゲイン変換、**§4.3で具体化するPTS `UNKNOWN`クロックソース較正ロジック(モック入力によるオフセット推定の正しさ検証)**。
JUnit対象: セグメント分割ロジック、残容量推定、Muxer状態遷移。
CI上で実行可能にすること(GTestはホストビルドで実行)。

## 4.8 CI/CD (GitHub Actions)

`.github/workflows/android.yml`: Checkout → JDK/NDKセットアップ(キャッシュ有効) → Gradle Build → Host GTest実行 → JUnit → Lint → Assemble Release(署名はSecrets参照、未設定時はunsigned) → APK Artifact Upload。mainブランチpushとPRでトリガー。

- **対象ABI**: `arm64-v8a`を主対象とする(minSdk 29の時点で32bit専用端末は実質存在しないため`armeabi-v7a`は対象外)。CI上のホストGTest実行やエミュレータ検証で必要な場合のみ`x86_64`を補助的に含める。`ndk.abiFilters`にこの一覧を反映すること
- Gradle依存キャッシュ(`actions/cache`等)を有効化し、C++ネイティブビルドの再ビルド時間を抑制すること

---

# 5. Phase 1 の開始

上記を踏まえ、まず **Phase 1**(アーキテクチャ説明・スレッドモデル図・PTS同期設計の詳説・データフロー図・クラス構成・全ビルド設定ファイル)から出力を開始してください。命令書内に矛盾や実現不可能な点を発見した場合は、Phase 1冒頭で代替案として提示してください。**あわせて、§0で指示した「採用バージョン一覧」「前提・判断ログ」、および§4.3で要求した「`UNKNOWN`クロックソース較正アルゴリズムの具体案」も必ずPhase 1に含めてください。**
