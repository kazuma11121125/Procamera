# 外部HDMI入力(External HDMI Input)— 映像入力の設計書

最終更新: 2026-07-18。コード照合コミット: `56eb688`。

**実装状況(2026-07-18, Sonnet)**: **未着手**。本文書は設計のみ。アプリコードは一切変更していない。
後続の Sonnet コーディングセッションが、他の文脈なしにこの文書だけから実装できることを目標に書いている。

この文書は、AuCamPRO (`com.aucampro.recorder`) に「USB接続のHDMIキャプチャカード経由の**映像入力**録画」を
追加するための**アーキテクチャ確定版**である。各設計上の問いに対して「選択肢の羅列」ではなく**1つの結論と
その根拠**を書く(`docs/HIRES_AUDIO_DESIGN.md` と同じ流儀)。実機検証なしには断定できない Android/HAL 挙動は、
末尾の「未確定リスク」に明示的に切り出してある。

> **前提(ユーザー明言)**: ユーザーは USB3.0 HDMIキャプチャカード(UVC系デバイス。外部HDMIソース
> ——別カメラのクリーンHDMI出力・ゲーム機・カムコーダ等——を受けて USB-C 経由で端末に流す)を所有している。
> 要望は「映像入力もできるように設計考えて」。**音声側は設計不要**——このキャプチャカードの音声は既存の
> `AudioDeviceRouter`(`TYPE_USB_DEVICE`/`TYPE_USB_HEADSET`/`TYPE_USB_ACCESSORY` を `InputKind.Usb` として
> 既にサポート)経由で、設定の「マイク入力」で "USB Audio" を選べば**今日のコードのまま動く**(§8 の Q-Audio
> は「映像ソース選択時に音声入力を自動でUSBへ寄せるか」という UX 上のカップリング判断のみ、オープン扱い)。
> 本設計は **映像(VIDEO)側**に限定する。

---

## 0. 実機で確認済みのハードウェア事実(Sony SO-51C, `dumpsys media.camera`)

キャプチャカードを挿し HDMIソースを流した状態での `adb shell dumpsys media.camera` の実測(**実機で確認済み**)。
これらは推測ではなく、この端末の HAL が実際に返した値である。

- **第3の facing 種別として列挙される**: `android.lens.facing = EXTERNAL`(FRONT/BACK とは別)。バッキングは
  `/dev/video2`、HALデバイス `device@3.4/external//dev/video2`(**v3.4** — 内蔵カメラの v3.5 とは別実装)。
- `android.info.supportedHardwareLevel = **EXTERNAL**`(Camera2 の最も制限されたHWレベル)。
- `android.control.afAvailableModes = [0]` → **AF_MODE_OFF のみ。オートフォーカス能力なし**(レンズが無く
  HDMIピクセルを中継するだけ)。
- `android.control.aeAvailableModes = [1]` → **AE_MODE_ON のみ**。手動露出/ISO/シャッター不可、AEロック/AWBロック
  不可(`aeLockAvailable=FALSE`, `awbLockAvailable=FALSE`)。
- `android.control.zoomRatioRange = [1.0, 1.0]` → **ズームなし**。
- `android.control.availableVideoStabilizationModes = [0]`, `availableOpticalStabilization = [0]` → 手ブレ補正なし。
- `android.scaler.availableStreamConfigurations`: format 33=JPEG(BLOB)/35=YUV_420_888/**34=PRIVATE**、
  サイズは **1920x1080 / 1680x720 / 1280x720 / 640x480 / 640x360 / 320x240**(**最大1080p、4Kなし**)。
  format 34 (PRIVATE) は既存の `MediaCodec.createInputSurface()` 録画経路が内蔵カメラで狙っている format なので、
  **1920x1080/PRIVATE は既存の録画Surface配管とそのまま互換のはず**(要実機検証 → §8 R3)。
- `android.control.aeAvailableTargetFpsRanges = [12,25], [15,30], [15,60]`。
- Sony vendor拡張(`com.sonymobile.external.*`): `availableManualDataSpace`(10個のint32 = 入力HDMI信号を
  どう解釈するかのカラー/HDRデータスペース選択肢)、`recommendDataSpace = 281083904`(推奨デフォルト)、
  `connectionType = 0`、`realSupportedFrameRateFor30fps = 30.0`、`realSupportedFrameRateFor60fps = 60.0`
  (後2者は**公称要求ではなく実測の入力信号カデンス**を報告する。端末はソース側の出力レートを制御できない
  ため、接続機器が実際に何fpsを吐いているかの検証に使える)。`manualDataSpace` は `availableSessionKeys` に
  含まれる → キャプチャセッション単位で設定可能。
- `android.sensor.info.timestampSource = **UNKNOWN**` → **最重要リスク(§3 / §8 R1)**。
- `android.request.availableCapabilities = [BACKWARD_COMPATIBLE] のみ`(MANUAL_SENSOR/RAW 等なし。上の
  AE/AF 制限と整合)。
- Resource cost 100、conflicting devices なし(HALレベルの排他競合は原理上なし。ただしこのアプリは元々
  常に1カメラセッションしか走らせない——既存制約)。
- Sony純正 `com.sonymobile.extmonitorapp`(「外部モニター」アプリと思われる)が同じ `/dev/video2` を
  同時期に open/close していた → **共有ハードウェアリソースであり、このアプリの専有ではない**(§4.3)。
- USBとしては `product_name=USB3.0 capture` で列挙され、挿入した瞬間に `/dev/video2`+`/dev/video3` として
  **ホットプラグ検出**された(boot時に存在するものではない)。

### 既存パイプラインで「既にカバー済み」の事実(実装者が再発明しないための棚卸し)

- **`cameraId` 切替は既存のフルリオープン経路をそのまま通る**: `CameraSessionController.reconfigureSession()`
  は「同一 `cameraId` のときだけ device を開いたまま session を作り直す」。外部カメラへの切替は `cameraId` が
  変わるので、`if (existingDevice == null || openCameraId != cameraId) { stop(); startRepeating(...) }` の
  **full-reopen フォールバック経路に自然に落ちる**。ここは**新規コード不要**。
- **「他アプリがカメラ使用中」のエラーハンドリングは既存**: `CameraSessionController.openCamera()` の
  `StateCallback.onError`/`onDisconnected` は `resumeWithException` する。extmonitorapp が `/dev/video2` を
  掴んでいて open に失敗する場合、この経路が例外を上げ、`startPreview()` の `catch` が `null` を返す
  (§4.3 で「録画中の切断」との違いを明記)。
- 音声側 `AudioDeviceRouter` は**変更不要**(前提のとおり)。

---

## 1. Q1 — モデリング: **`AvailableLens` の4つ目にはせず、独立した「入力ソース(InputSource)」概念にする**

### 結論
外部HDMIソースを既存のレンズスイッチャUI(`AvailableLens`)の4つ目のエントリとして扱わ**ない**。
**別概念の「入力ソース」**として、内蔵カメラ(=既存のレンズ群をまとめた1つの選択肢)と外部HDMI入力の
**2択トップレベルセレクタ**を新設する。内蔵カメラを選んだ時だけ既存のレンズ strip(16mm/24mm/88mm)が
出る、という2階層にする。

### 根拠(自分で読んで確認した内容)
- `AvailableLens` は **`equivalentFocalLengthMm` / `zoomLabel` / `maxDigitalZoom` / `sensorDiagonalMm` を
  中心に組まれた data class**(`CameraCapabilityInspector.kt` L225-233)。外部カメラには焦点距離もズームも
  センサー対角線も**存在しない**——全フィールドが無意味 or 偽値になる。
- `findStandardRearLens()`(L54-58)と `allRearLenses()`(L264-276)は**両方 `LENS_FACING == LENS_FACING_BACK`
  で厳格にフィルタ**し、さらに `sensorDiagonalMm < MIN_PLAUSIBLE_MAIN_SENSOR_DIAGONAL_MM (5.0mm)` を
  除外する。外部カメラ(`LENS_FACING_EXTERNAL`、センサーサイズ無し)は**この2条件のどちらでも確実に
  弾かれる**。無理に通すと `dedupeByFocalLengthCluster()` の焦点距離クラスタリングにも巻き込まれ、
  内蔵レンズの選定(実機で苦労して確定させた 23mm/24mm 重複排除など)を壊すリスクがある。
- したがって、外部ソースを `AvailableLens` に押し込むのは**既存の全フィールドと戦う**羽目になる。
  独立概念にすれば、内蔵レンズ経路(実機検証済み)には**一切触れない**。

### 具体モデル
`CameraCapabilityInspector` に外部カメラ列挙を**独立メソッドとして**追加する(既存の rear 列挙を汚さない):

```kotlin
data class ExternalVideoSource(
    val cameraId: String,          // e.g. "2" (LENS_FACING_EXTERNAL のカメラID)
    val label: String = "HDMI入力", // UI表示
    val maxSize: android.util.Size, // 1920x1080 (availableStreamConfigurations の最大)
    val availableFps: List<Int>,   // stream/fps能力とアプリ候補の交差(§5/§9参照)
    val availability: Availability, // Available / Busy。物理接続とは別状態
)

/** LENS_FACING_EXTERNAL のカメラを列挙。物理接続時のみ非空。内蔵レンズ経路とは完全に独立。 */
fun externalVideoSources(): List<ExternalVideoSource>
```

UI状態(`CameraUiState`)にはトップレベル選択を持たせる:

```kotlin
sealed interface VideoInputSource {
    data object InternalCamera : VideoInputSource                 // 既存のレンズ strip を出す
    data class ExternalHdmi(val cameraId: String) : VideoInputSource
}
```

`startPreview(surface, params, targetCameraId = externalSource.cameraId)` は**既存のシグネチャで足りる**
(`targetCameraId` パラメータは lens 切替のために既に存在——L638-674)。外部カメラの `cameraId` を渡すだけで、
`CameraSessionController` は既存の full-reopen 経路で開く。

---

## 2. Q2 — 能力ゲーティング(AF/手動露出/ズーム無し・最大1080p・制限fps)の流し方

### 大原則: `buildRequestBuilder` の無条件 `apply*` 呼び出しが**デフォルトparamsで即座に壊れる**

これは UX の問題ではなく、**最初のフレームが出ない**正しさの問題である。`CameraSessionController.
buildRequestBuilder()`(L314-326)は**全キャプチャリクエストで無条件に**次を呼ぶ:

```kotlin
factory.applyManualExposure(builder, params.iso, params.exposureTimeNanos, params.fps)
factory.applyFocus(builder, params.focusDistanceDiopters, params.afAuto)
factory.applyWhiteBalance(builder, params)
factory.applyZoom(builder, params.zoomRatio)
```

そして `CameraParams` のデフォルトは **`afAuto = true`**(`CameraParams.kt` L41)。よって `applyFocus` は
`CONTROL_AF_MODE_CONTINUOUS_VIDEO` をセットする(`ManualCaptureRequestFactory.kt` L51-52)が、外部カメラは
`afAvailableModes = [OFF]` **のみ**。**ユーザーが何も触らなくても、デフォルトparamsのまま最初のリクエストが
HALに拒否され得る**。同様に:

- `applyManualExposure`(L38-47): `AE_MODE_OFF` + 手動 `SENSOR_SENSITIVITY`/`SENSOR_EXPOSURE_TIME`/
  `SENSOR_FRAME_DURATION` をセット。外部カメラは **AE_MODE_ON のみ・MANUAL_SENSOR なし**。
- `applyZoom`(L106-107): `SENSOR_INFO_ACTIVE_ARRAY_SIZE` を読む。**このHALでは absent の可能性**があり、
  `activeArray ?: return` で no-op にはなるが、そもそもズーム能力ゼロ。
- `applyWhiteBalance`: `wbAuto=true`(デフォルト)なら `CONTROL_AWB_MODE_AUTO`。これは AE_MODE_ON 機でも
  通常受け付けられるはずだが**要実機検証**(§8 R5)。

### 結論: `startPreview` 時に1回問い合わせる能力プロファイルを `CameraCapabilities` に載せ、各 `apply*` をゲートする

`supportsManualWb` が既に辿っているのと**全く同じパターン**(`RecordingPipeline.startPreview()` L700 で
`capabilityInspector.supportsManualWhiteBalance(lens.cameraId)` を呼んで `CameraCapabilities` に格納)を、
AF/AE/Zoom にも広げる。

1. **`CameraCapabilityInspector` に能力クエリを追加**:
   ```kotlin
   fun supportsManualExposure(cameraId: String): Boolean  // aeAvailableModes に OFF が含まれ、かつ
                                                           // REQUEST_AVAILABLE_CAPABILITIES に MANUAL_SENSOR
   fun supportsAutoFocus(cameraId: String): Boolean        // afAvailableModes が [OFF] だけでないか
   fun supportsZoom(cameraId: String): Boolean             // zoomRatioRange 上限 > 1.0 または
                                                           // SCALER_AVAILABLE_MAX_DIGITAL_ZOOM > 1.0
   ```
   (`supportsManualWhiteBalance` が `NoSuchFieldError` を実機で踏んだ教訓——L187-195——に倣い、
   `CameraCharacteristics` の存在しないキーではなく `REQUEST_AVAILABLE_CAPABILITIES` / 実在キーで判定する。)

2. **`CameraCapabilities` data class にフラグを追加**(`RecordingPipeline.kt` L101-114):
   `supportsAutoFocus: Boolean`, `supportsManualExposure: Boolean`, `supportsZoom: Boolean`
   (`supportsManualWb` の隣に並べる)。

3. **`ManualCaptureRequestFactory` の各 `apply*` を能力対応にする**。2案あるが**(A)を採る**:
   - **(A) 推奨: `ManualCaptureRequestFactory` を能力対応にする**。コンストラクタで
     `CameraCapabilityInspector`(または上の3フラグ)を受け取り、各 `apply*` の先頭で
     「非対応ならスキップして安全なフォールバックだけ set」する。外部カメラの場合:
     - `applyManualExposure`: 手動露出非対応なら **`CONTROL_AE_MODE_ON` だけ set して return**
       (`SENSOR_*` は触らない)。
     - `applyFocus`: AF非対応なら **`CONTROL_AF_MODE_OFF` だけ set して return**(`LENS_FOCUS_DISTANCE`
       は触らない——レンズが無いので焦点距離キー自体が無意味)。
     - `applyZoom`: ズーム非対応なら**何もせず return**(`SCALER_CROP_REGION` を触らない)。
     - `applyWhiteBalance`: 現状のまま(`wbAuto=true` の `AWB_MODE_AUTO` は AE_MODE_ON 機でも可のはず。
       §8 R5 で検証)。
     こうすると `CameraSessionController.buildRequestBuilder()` の呼び出し側は**一切変えず**、能力ゲートが
     factory 内に閉じる。`ManualCaptureRequestFactory` は既に `CameraCharacteristics` を受け取っている
     (L24-27)ので、そこから能力を導出できる。
   - **(B) 不採用: 呼び出し側(`CameraSessionController`)で `if (caps.supportsX)` 分岐**。呼び出し箇所が
     `buildRequestBuilder`/`capturePhoto`/`submitSingleRequest` の3つに散っており、外部カメラでは
     `capturePhoto`(JPEG stills)自体が別議論になる——集約されず漏れやすいので採らない。

4. **`FocusController` を外部カメラでは配線しない**(`FocusController.kt`)。`RecordingPipeline.startPreview()`
   L715-727 で `focusController = FocusController(...)` を生成しているが、**外部カメラ(`supportsAutoFocus=false`)
   のときは `focusController = null` のままにする**。これで `requestTapToFocus()`(L1499-1501)は
   `focusController?.onTap(...)` の safe-call で自動的に no-op になる。加えて UI 側でタップtoフォーカスの
   ジェスチャ affordance 自体を隠す(§5)。「HALが拒否/無視するリクエストを黙って投げる」ことを避ける。

### UI ゲーティング(既存の precedent に合わせる)
既存の `MainScreen.kt` は手動WBを能力でゲートしている——**この precedent を AF/ISO/シャッター/ズームにも
展開する**:
- L1007 `enabled = caps?.supportsManualWb ?: false`(コントロールを disable)
- L1013 `if (caps?.supportsManualWb == false) { ... }`(非対応の旨をユーザーに表示)

外部HDMI入力アクティブ時は、ISO/シャッター/フォーカス/ズーム/タップtoフォーカスの各コントロールを
**visibly disable または非表示**にし、「HDMI入力ソースでは調整できません」的な文言を出す
(**黙ってno-opにしない**——ユーザーが操作したのに何も起きないのが最悪)。WB(オート)とEQ/ゲイン等の
音声系はそのまま有効。

---

## 3. Q3 — タイムスタンプドメインのリスク(**最優先・出荷前必須検証 = §8 R1**)

### なぜ危険か(コードの現状を正確に)
`PtsClockDomain.kt` は**かつて** `SENSOR_INFO_TIMESTAMP_SOURCE`(REALTIME/UNKNOWN)で分岐・較正する設計
だったが、実機(内蔵カメラ v3.5、SENSOR_INFO_TIMESTAMP_SOURCE=REALTIME)で
「`MediaCodec` InputSurface経路の `bufferInfo.presentationTimeUs` は**REALTIMEソース機でも常に
`System.nanoTime()`(CLOCK_MONOTONIC)ドメイン**」と判明したため、**REALTIME/UNKNOWN分岐・較正機構を
全廃**した(L14-37 のクラスdoc、および `docs/ARCHITECTURE.md` §Phase4 の post-mortem)。この確認は
`sensorTimestampNanos − presentationTimeUs×1000` と `elapsedRealtimeNanos − nanoTime`(スリープ蓄積による
約79591秒のギャップ)が**1.7μs差**で一致したことで確定した。

現在の `normalizeVideoPtsUs()`(L114-125)は:
```kotlin
val ptsUs = ((presentationTimeNanos - recordingStartNanos) / 1000L).coerceAtLeast(0L)
```
で、**`recordingStartNanos`(= `System.nanoTime()` 由来、L46-47/85-88)を素で引く=CLOCK_MONOTONIC を
ハードコードしている**。較正パスはもう**存在しない**。

### リスクの核心
この CLOCK_MONOTONIC 前提は**内蔵HAL(v3.5)でしか検証されていない**。外部カメラは**別実装の v3.4 HAL** で
`timestampSource=UNKNOWN` を報告する。**このカメラ由来フレームで同じ前提が成り立つ証拠は無い。** もし
v3.4 HAL の `presentationTimeUs` が MONOTONIC ではなく **BOOTTIME**(生のHALセンサタイムスタンプ)を素通し
していた場合、`normalizeVideoPtsUs` は `recordingStartNanos`(MONOTONIC)を引くので、**スリープ蓄積分
(前回の事例では約79591秒)だけ PTS がズレる**——旧バグと全く同じ「Videoフレームが1枚しか書かれない/
音声だけ長い」形で顕在化しうる。

### 出荷前の判別テスト(具体的手法)
`VideoEncoder` のフレーム受領地点(`onOutputBufferAvailable`)で、外部カメラ録画時に次の3値を並べてログる:

```
presentationTimeUs, System.nanoTime(), SystemClock.elapsedRealtimeNanos()
```

- `presentationTimeUs×1000` が **`nanoTime` を追う** ⇒ **CLOCK_MONOTONIC**(現状の実装でそのまま正しい)。
- `presentationTimeUs×1000` が **`elapsedRealtimeNanos` を追う** ⇒ **BOOTTIME**(要フォールバック、下記)。

内蔵カメラの検証はサブμs精度が要ったが、**今回は精度不要**: 2つのクロックは端末のスリープ蓄積分(数万秒
規模)だけ離れているので、どちらを追うかは**一目で分離できる**。同じ相関手法を `PtsClockDomain` の
クラスdocが既に確立している。

### フォールバック(MONOTONIC でなかった場合)
**推奨(A)**: `VideoEncoder` 内で、外部カメラのフレームに限り `presentationTimeUs` を**MONOTONICドメインへ
変換してから** `PtsClockDomain` に渡す(`presentationTimeUs_monotonic = presentationTimeUs_boottime −
(elapsedRealtimeNanos − nanoTime)`、フレーム受領時に両クロックを1回読んで差分を確定)。これで
`PtsClockDomain` の「両トラック単一ドメイン」不変条件を**壊さず**に済む——最もクリーン。
**代替(B)**: `PtsClockDomain` にドメイン正規化ブランチを再導入する。ただしこれは「較正機構を全廃した」
設計判断を部分的に巻き戻すことになり、Audioパスとの単一基準系という現在の美点を薄める。**(A)を推奨。**

音声側(§8 R2)も外部カメラ経由では別途要確認だが、音声は既存の `getInputTimestamp()`(MONOTONIC 明記)
基準のままなので、**映像側の変換だけで A/V が揃う**——音声の基準系は変えない。

---

## 4. Q4 — ホットプラグ発見 と セッション中切断のハンドリング

現状、`CameraManager.AvailabilityCallback`(`onCameraAvailable`/`onCameraUnavailable`)を**誰もリッスンして
いない**——レンズは起動時に1回列挙されるだけ(`allRearLenses()` を `attachPreviewSurface` で1回)。外部カメラは
物理接続時のみ現れるので、この機能には**2つの独立した機構**が要る(発見と切断は別物)。

### 4.1 発見(接続時にだけUIに現れる)
**結論: `CameraManager.AvailabilityCallback` を登録する**(遅延再列挙ではなく)。

- `RecordingPipeline`(または新設の小クラス)で `cameraManager.registerAvailabilityCallback(callback, handler)`
  を登録。`onCameraAvailable(cameraId)`/`onCameraUnavailable(cameraId)` で、その `cameraId` が
  `LENS_FACING_EXTERNAL` かどうかを `characteristicsFor(cameraId).get(LENS_FACING)` で確認し、外部カメラの
  接続/切断を UI 状態(`VideoInputSource` セレクタの表示/非表示)に反映する。
- **代替(不採用)**: 「ソースピッカーを開いた瞬間に `externalVideoSources()` を再列挙」。実装は軽いが、
  接続してもピッカーを開くまでバッジ等で気づけない。AvailabilityCallback はプレビュー中でも即座に
  「HDMI入力が使えるようになった」を出せるので、そちらを推奨。登録は `AudioDeviceRouter.register()` の
  ライフサイクル(pipeline全体、`stopAll()` で unregister)と揃える。
- **注意**: extmonitorapp(§0)が `/dev/video2` を掴んでいる間は `onCameraUnavailable` が飛ぶ(=「使用中」)
  可能性がある。発見ロジックは「列挙に存在するか」だけでなく「available か」も見るべきだが、最終的に
  `openCamera()` が例外を返すかどうかが真実の source(既存の `startPreview` catch → `null` 経路で処理)。

### 4.2 名指しすべき既存のギャップ: 切断が**伝播しない**
`CameraSessionController.openCamera()`(L376-415)の `StateCallback.onDisconnected`(L395-402)は、
**openコルーチンを `resumeWithException` するだけ**。**device が既に open した後の切断**では、Camera2 は
`onDisconnected` を呼んで device を close するが、`RecordingPipeline` には**何も通知されない**——
`session`/`device` が stale になり、`reconfigureRecordingSurfacesLocked()` 自身のdoc(L1562-1569)が
言うとおり、フレームが止まっても**「エラー報告ではなく無音のstall」として現れる**。これは外部USB-Cの
緩み等で**実際に起きやすいシナリオ**。

### 4.3 結論: 切断通知経路を新設し、録画中は take を安全に finalize する
`CameraSessionController` に、**open後の** `StateCallback.onDisconnected`/`onError` を上位へ伝える
コールバック seam を足す(`captureResultListener` と同じ「疎結合seam」の作り):

```kotlin
// CameraSessionController
var onDeviceDisconnected: ((cameraId: String) -> Unit)? = null
// openCamera() の StateCallback を保存し、onOpened 後に onDisconnected/onError が来たら
// device を close した上で onDeviceDisconnected?.invoke(cameraId) する
```

`RecordingPipeline` 側の受け側の振る舞い:

1. **録画中に外部カメラが切断**:
   - `MuxerController`は1 takeを1ファイルへ書く。切断ハンドラで正常停止シーケンス
     (`stopRecordingInternalLockedAsync`)を走らせて**現在のtakeをfinalize**する
     (EOSドレイン→muxer stop→WAVエクスポート)。カメラは既に死んでいるので
     `sessionController.stop()` は best-effort(既に close 済みでも既存の例外guardが吸収)。
   - **UI**: `Event.Failed(message, sessionStopped = true)` を出す(この Event 型は既存 L83、
     `sessionStopped=true` で「呼び出し側が `startPreview()` を再度呼んで viewfinder を戻す」契約も既存)。
2. **切断後の自動フォールバック**: 録画停止後、**内蔵の標準レンズ(`findStandardRearLens()`)へ自動的に
   `startPreview()` し直す**ことを推奨(ユーザーが真っ暗な画面に取り残されない)。ただし「切断された旨」を
   エラーバナーで明示してから内蔵に戻す(黙ってソースが変わったように見せない)。
3. **プレビュー中(非録画)に切断**: 録画finalizeは不要。エラーバナー + 内蔵レンズへフォールバック
   `startPreview()`。

**発見(§4.1, `AvailabilityCallback`)と切断(§4.2-4.3, active device の `StateCallback.onDisconnected`)は
別機構である**——前者はピッカー用、後者はアクティブセッション用。両方必要。

---

## 5. UI/UX と反映方式

### 入力ソースセレクタ(新設)
既存のレンズ strip(`MainScreen.kt` L843-852、`state.availableLenses` を横並びボタンで描画)の**上位**に、
「内蔵カメラ / HDMI入力」のトップレベル2択を置く。`state.availableExternalSources`(§1)が非空のときだけ
「HDMI入力」ボタンを出す(=物理接続時のみ)。内蔵カメラ選択時のみ既存レンズ strip を表示。

- HDMI入力を選ぶ → `viewModel.selectVideoSource(ExternalHdmi(cameraId))` → `pipeline.startPreview(surface,
  params, targetCameraId = cameraId)`(既存経路)。`cameraId` 変更なので `reconfigureSession` の
  full-reopen フォールバックが走る(§0 棚卸し)。
- 内蔵へ戻す → `startPreview(surface, params, targetCameraId = null or 標準レンズID)`(既存 `switchLens` と
  同型)。
- **録画中はソース切替不可**(`switchLens` が L392 `if (_uiState.value.isRecording) return` で既に録画中
  ガードしているのと同じ。UI側も録画中は disable)。

### 能力ゲートUI
§2 のとおり、HDMI入力アクティブ時は ISO/シャッター/フォーカス/ズーム/タップtoフォーカスを disable/非表示。
既存の `caps?.supportsManualWb` パターン(`MainScreen.kt` L1007/L1013)を各能力フラグに展開。

### フレームレート候補の制限(judder 対策)— 重要
外部カメラの `availableVideoConfigs` は、既存の `supportedVideoConfigs(cameraId)`(`CameraCapabilityInspector.kt`
L363、`MediaCodecList` チェック)を**そのまま使うと不十分**。理由と対策:

- **最大解像度 1080p**: 外部カメラは 1920x1080 が上限(4Kなし)。既存 `supportedVideoConfigs` の候補リストは
  4K HEVC を先頭に含むので、`isVideoConfigSupported`(MediaCodec観点)は通してしまうが、**カメラ側の
  `availableStreamConfigurations` に無いサイズ**は Camera2 session で拒否される。→ 外部カメラでは候補を
  **カメラの `SCALER_STREAM_CONFIGURATION_MAP` が実際に提供する PRIVATE サイズと突き合わせて絞る**
  (`supportedVideoConfigs` に cameraId 別の stream-config 交差を足すか、外部カメラ専用の候補生成関数を作る。
  `supportedVideoConfigs` は既に `@Suppress("UNUSED_PARAMETER") cameraId` を「将来の per-camera クエリ用」と
  予約済み——L362——なので、ここを実装する)。
- **フレームレートは実測カデンスに合わせる(judder 回避)**: 端末は**HDMIソース側の出力レートを制御できない**。
  ソースが実際に30fpsを吐いているのに MediaCodec に60fps公称で要求すると、**重複フレーム/judder** の
  リスクがある。Sony vendor拡張 `realSupportedFrameRateFor30fps`/`realSupportedFrameRateFor60fps`(§0)は
  **実測の入力カデンス**を報告する。→ **fps候補はこの実測値が示す実カデンスに合わせる**のを推奨:
  30fpsソースなら30fpsのみ、60fpsソースなら60fpsも許可。`ExternalVideoSource.availableFps`(§1)に
  この実測ベースの候補を格納する。
  - 実装上、これらは vendor tag なので `CameraCharacteristics.get()` に vendor key 名(
    `com.sonymobile.external.realSupportedFrameRateFor30fps` 等)を `CaptureRequest.Key`/`CameraCharacteristics.Key`
    の文字列コンストラクタ経由で読む(**要実機検証** → §8 R4。読めなかった場合のフォールバックは
    `aeAvailableTargetFpsRanges = [12,25],[15,30],[15,60]` から保守的に 30fps を既定にする)。

### 設定の永続化
`VideoInputSource` の選択を永続化するかは**しない**を推奨(外部ソースは物理接続依存なので、次回起動時に
挿さっている保証がない。挿さっていなければ内蔵に落ちるだけ)。`UserPreferencesStore` は変更不要。

---

## 6. ファイル別・変更内容(実装チェックリスト)

| ファイル | 変更 |
|---|---|
| `camera/CameraCapabilityInspector.kt` | **新規** `externalVideoSources(): List<ExternalVideoSource>`(`LENS_FACING_EXTERNAL` を列挙。内蔵rear経路には触れない)。**新規** `supportsAutoFocus`/`supportsManualExposure`/`supportsZoom`(実在キー/`REQUEST_AVAILABLE_CAPABILITIES` で判定、`supportsManualWhiteBalance` の教訓に倣う)。`supportedVideoConfigs(cameraId)` に**外部カメラ向けの stream-config 交差 + 実測fps絞り込み**を実装(予約済み `cameraId` 引数を使う)。 |
| `camera/ManualCaptureRequestFactory.kt` | 各 `apply*` を能力対応に(§2 案A):非対応時は安全なフォールバックのみ set。`applyManualExposure`→`AE_MODE_ON` のみ、`applyFocus`→`AF_MODE_OFF` のみ、`applyZoom`→no-op。呼び出し側(`CameraSessionController`)は変更不要。 |
| `camera/CameraSessionController.kt` | **新規** seam `var onDeviceDisconnected: ((String) -> Unit)?`。`openCamera()` の `StateCallback` を保存し、**open後の** `onDisconnected`/`onError` で device close + `onDeviceDisconnected` 発火(§4.2)。`reconfigureSession` の cameraId 変更→full-reopen は既存のまま(変更不要)。 |
| `camera/FocusController.kt` | 変更なし。ただし `RecordingPipeline` が外部カメラ時に生成しない(下記)。 |
| `pipeline/RecordingPipeline.kt` | `CameraCapabilities` に `supportsAutoFocus`/`supportsManualExposure`/`supportsZoom` を追加(`startPreview` L700 付近で `capabilityInspector` から埋める)。外部カメラ時は `focusController = null`(§2-4)。`sessionController.onDeviceDisconnected` を配線し、切断ハンドラ(録画中なら take finalize→`Event.Failed(sessionStopped=true)`、その後内蔵レンズへ自動 `startPreview`)を実装(§4.3)。`CameraManager.AvailabilityCallback` を登録/解除(§4.1、`stopAll()` で unregister)。録画時の video config は外部カメラでは1080p/実測fpsに絞った候補から選ぶ。 |
| `ui/viewmodel/CameraUiState.kt` | `availableExternalSources: List<ExternalVideoSource>`、`selectedVideoSource: VideoInputSource`(または `isExternalSourceActive: Boolean`)を追加。`CameraCapabilities` の新フラグは既存の `capabilities` フィールド経由で参照。 |
| `ui/viewmodel/CameraControlViewModel.kt` | **新規** `selectVideoSource(source)`(内蔵/HDMI 切替→`pipeline.startPreview(targetCameraId=...)`、`switchLens` と同型・録画中ガード)。`AvailabilityCallback`/切断コールバックを collect して `availableExternalSources` と切断エラー/フォールバックを state に反映。 |
| `ui/MainScreen.kt` | レンズ strip の上位に入力ソースセレクタ(`availableExternalSources` 非空時のみ HDMI ボタン)。HDMI入力時は ISO/シャッター/フォーカス/ズーム/タップtoフォーカスを disable/非表示(既存 `supportsManualWb` L1007/L1013 パターンを展開)。 |
| `ui/components/SettingsBottomSheet.kt` | (任意)音声auto-switch を採用する場合のみ変更(§8 Q-Audio)。MVP では不要。 |

**音声側(`audio/*`)は変更なし**(前提のとおり)。

---

## 7. フェーズ計画(複雑度/リスク付き)

### Phase 1 = 出荷可能な MVP: **HDMI入力プレビュー + 録画(1080p、能力ゲート、切断ハンドリング)**
ユーザーが求めた「映像入力の録画」を実際に届けるのが MVP。含む:
- `externalVideoSources()` 列挙 + `AvailabilityCallback` による発見(§4.1)。
- 入力ソースセレクタUI + 内蔵/HDMI切替(既存 `targetCameraId` 経路、§1/§5)。
- 能力ゲート:`apply*` の能力対応 + `focusController` 非生成 + UI disable(§2)。**デフォルトparamsで
  最初のフレームが出る**ことがこのPhaseの最低条件(§2 の landmine)。
- 外部カメラ向け video config 絞り込み(1080p + 実測fps、§5)。
- **切断伝播 + 録画中の安全な finalize + 内蔵フォールバック**(§4.2-4.3)。
- **§3 のタイムスタンプドメイン判別テストを実機で通す**(= §8 R1 を潰す)。MONOTONIC でなければ
  `VideoEncoder` にドメイン変換を入れる(§3 案A)。
- **実装順の推奨(de-risk)**:
  - **1a**: プレビューのみ(録画なし)で HDMI ソースを開き、`buildRequestBuilder` の能力ゲートが
    デフォルトparamsで壊れないことを実機で通す(最初のフレームが出る)。ここで §8 R2(session 受理)/
    R5(AWB_AUTO 可否)を潰す。
  - **1b**: 録画経路(InputSurface + encoder + mux)を通し、**§3 のタイムスタンプ判別テストを実行**。
    ffprobe で A/V の尺一致を確認(旧BOOTTIMEバグと同じ症状が出ないこと)。
  - **1c**: 切断ハンドリング(録画中に物理的に抜いて、現在segmentが再生可能に finalize され、内蔵に
    フォールバックすること)。
  - 出荷点は **1c 完了時**。
- 複雑度: 中。リスク: **中〜高**(R1 タイムスタンプが最大。次いで session 受理・切断中finalize の
  マルチスレッドタイミング)。

### Phase 2 = データスペース/HDR とUX磨き
- Sony vendor拡張 `manualDataSpace`(`availableSessionKeys` にあり、セッション単位で設定可)による
  入力信号のカラー/HDR解釈選択。`recommendDataSpace` を既定に、UIで切替。HDMIソースが HDR/BT.2020 を
  吐く場合の正しい取り込みに要る可能性。複雑度: 中。リスク: 中(vendor key・実機依存)。
- 音声auto-switch(§8 Q-Audio)の採用可否をここで決める。
- 複雑度: 小〜中。リスク: 中。

### Phase 3 = 拡張
- 720p/低解像度ソース対応の最適化、複数外部ソースのピッカー体裁向上、extmonitorapp との共存の
  厳密化(必要なら)。複雑度: 小。リスク: 低。

---

## 8. 未確定リスク(実機検証なしに断定できない事項 — 明示)

- **R1 (最高影響・出荷前必須)**: 外部カメラ(v3.4 HAL, `timestampSource=UNKNOWN`)の InputSurface経路
  `presentationTimeUs` が CLOCK_MONOTONIC か BOOTTIME か。現行 `PtsClockDomain` は MONOTONIC をハードコード
  (較正機構は全廃済み)。BOOTTIME なら旧バグと同型(数万秒ズレ→Videoフレーム1枚)を再発する。§3 の
  判別テスト(`nanoTime` vs `elapsedRealtimeNanos` 相関)を実機で必ず実行。MONOTONIC でなければ
  `VideoEncoder` にドメイン変換(§3 案A)。
- **R2 (高影響)**: 外部カメラで **プレビューSurface + encoder InputSurface の同時ストリーム session** が
  実機で受理されるか。内蔵カメラでは 16:9-preview + non-16:9-encoder 以外を拒否した実績があり
  (`supportedVideoConfigs` のdoc)、外部カメラは別HALなので session 受理は別途要確認。1920x1080/PRIVATE は
  format的には互換のはず(§0)だが、**要実機検証**。拒否時は `onConfigureFailed` → 既存の
  `startPreview` catch でハンドリング。
- **R3 (中影響)**: 外部カメラの 1920x1080/PRIVATE が既存録画Surface配管とバイト単位で互換か(format 34 が
  内蔵と同じ意味で扱えるか)。
- **R4 (中影響)**: Sony vendor tag(`realSupportedFrameRateFor*` 等)を `CameraCharacteristics` の文字列key
  経由で実際に読めるか。読めない場合は `aeAvailableTargetFpsRanges` から 30fps 既定に保守フォールバック。
- **R5 (中影響)**: `CONTROL_AWB_MODE_AUTO`(`applyWhiteBalance` の `wbAuto=true` 経路)が AE_MODE_ON-only の
  外部カメラで受理されるか。拒否されるなら AWB 自体もゲート対象に加える。
- **R6 (中影響)**: 録画中の物理切断時、`stopRecordingInternalLockedAsync` の finalize が **既に死んだ
  CameraDevice** 相手に安全に完走するか(encoder EOSドレインが止まったフレーム供給下でハングしないか)。
  マルチスレッドタイミング依存なので実機で複数回の抜線テストが要る。
- **R7 (低影響)**: extmonitorapp が `/dev/video2` を保持中に `openCamera()` が返すエラーの具体
  (`onError` の error code)。既存の例外guardで吸収されるはずだが、UI文言を適切にするため実機で確認。

### Q-Audio(明示的オープン質問 — 推奨付き)
**「HDMI入力を映像ソースに選んだとき、音声入力preferenceを自動で USB Audio に寄せるべきか」** は
技術要件ではなく UX カップリング判断。
- **推奨: 弱いカップリング(auto-suggest、auto-switch しない)**。HDMI入力選択時に「音声もHDMIキャプチャの
  USB音声を使いますか?」的な**ワンタップ提案**を出すが、`preferredInputKind` を**黙って書き換えない**。
  理由: (a) ユーザーが意図的に別マイク(内蔵/別USB)で録りたいケースがある(キャプチャカードの音声が
  必ずしも欲しい音とは限らない——例えば会場PAは別系統でマイク録り)。(b) `AudioDeviceRouter` は既に
  fallback チェーンを持つので、黙って寄せると「なぜ音が変わった」が分かりにくい(既存の「report what
  actually happened」原則に反する)。
- **代替(不採用寄り)**: 選択時に `setPreferredInputKind(InputKind.Usb)` を自動発火。UXは楽だが上記(a)(b)で
  ユーザーの意図を上書きするリスク。**この判断は Phase 2 でユーザーに確認してから確定する**(MVP では
  何もしない=現状の音声preferenceを尊重)。

---

## 9. コード照合で確定した実装契約（2026-07-18追補）

この節はコミット `56eb688` の実コードを通読して確定した契約であり、本文中の古い行番号、fps vendor tagの
解釈、availabilityの扱いと矛盾する場合は**この節を優先する**。実装前に曖昧さを残さないための最終補正である。

### 9.1 入力モデルをPipeline内部まで一貫させる

外部入力を `AvailableLens` に入れないという§1の結論は維持する。さらに現行 `startPreview(targetCameraId)`
分岐が作る `focalLengthMm=0f` のダミー `LensInfo` も流用しない。`RecordingPipeline.selectedLens` は録画開始時に
実質cameraIdしか使っていないため、`SelectedCamera(cameraId, inputKind)`（名称は任意）の焦点距離非依存型へ
置き換える。UI状態は次で確定する。

```kotlin
sealed interface VideoInputSource {
    data object InternalCamera : VideoInputSource
    data class ExternalHdmi(val cameraId: String) : VideoInputSource
}

enum class Availability { Available, Busy }
```

外部入力選択は永続化しない。内蔵側の既存lens preferenceはそのまま維持し、外部からInternalへ戻すときは
セッション内で最後に選んだ内蔵レンズ、無ければ `findStandardRearLens()` に戻す。

### 9.2 AvailabilityCallbackは接続検出器ではなく再列挙トリガー

`onCameraUnavailable` は物理切断だけでなく、このアプリまたは他アプリがcameraをopenしたときにも発火する。
従って、unavailableを受けただけでHDMIボタンを消す設計は禁止する。

- `RecordingPipeline` が小さな `ExternalCameraMonitor` を所有し、
  `registerAvailabilityCallback(executor, callback)` を一度登録、`stopAll()` で必ず解除する。
- callbackのたびに `cameraIdList` を再列挙し、`LENS_FACING_EXTERNAL` の完全なスナップショットを通知する。
  callback引数のIDだけを足し引きしない。ホットプラグ直後の順序差やイベント取りこぼしから自己修復できる。
- IDが列挙に残りunavailableなら `Busy` として表示する。ボタンをdisabledにし「使用中」を示す。
- IDが列挙から消えたときだけ物理切断扱いにする。ただしactive device喪失の即時処理は次項の
  `CameraDevice.StateCallback` が担当する。
- callback状態はUXの事前表示であり、open可否の最終判断は `openCamera()` の成功/失敗とする。

### 9.3 active device喪失は世代管理して一度だけ通知する

現行 `openCamera()` はopen完了後の `onDisconnected` / `onError` を上位へ伝えない。通知seam追加は§4どおり
必須だが、単純なcameraId callbackだけではレンズ/入力切替時に旧deviceの遅延callbackが新セッションを停止する
競合が残る。次を満たす。

- openごとに単調増加generationを割り当てる（またはactive `CameraDevice` の参照同一性で判定する）。
- `onOpened` 前の失敗はopen coroutineの例外だけにする。`onOpened` 後は、現在activeなgenerationと一致する
  deviceの喪失だけを上位へ通知する。
- 同じdeviceから `onError` と `onDisconnected` が続いても `AtomicBoolean` 等で一度しか通知しない。
- callback内ではdevice/内部参照を失効させて通知するだけにし、重い録画停止は
  `pipelineScope(Dispatchers.Main.immediate)` に渡して `sessionMutex` 上で直列化する。

録画中は既存の正常停止手順（camera停止→video EOS drain→audio停止→muxer/WAV finalize）をbest-effortで走らせ、
`Event.Failed(sessionStopped=true)` を一度送る。このため録画開始時に渡されたevent sinkをtake中だけ保持し、
終了時に必ずclearする。Pipelineは復旧previewを再帰的に開始しない。ViewModelが既存の
`sessionStopped=true` 復旧経路でsourceをInternalへ更新し、所有するSurfaceを使って標準内蔵カメラを開く。
プレビュー中の喪失も同じくPipelineのdevice-lost eventを受けたViewModelが復旧する。

### 9.4 能力判定は共通値オブジェクトを一度生成する

Inspectorと `ManualCaptureRequestFactory` に同じ判定式を二重実装しない。
`CameraControlCapabilities.from(characteristics)` のような純粋値を1回生成し、Pipeline/UIとFactoryの双方へ渡す。

- manual exposure: `AE_MODE_OFF` と `MANUAL_SENSOR` の両方がある。
- autofocus: advertised AF modeにOFF以外がある。
- zoom: active arrayがあり、max digital zoom > 1（API 29互換の現行crop実装基準）。
- manual WB: AWB OFFと `MANUAL_POST_PROCESSING` の両方がある（既存判定を移す）。

非対応時のFactory動作は§2どおり、AE ONのみ、AF OFFのみ、zoom no-opとする。外部時は
`FocusController` を生成せず、capture-result listenerもAF距離を読まない。WB AUTOが拒否される実機だった場合
のみ、advertised AWB modesから安全なmodeを選ぶ追加gateを入れる。

### 9.5 camera別video configを唯一の候補源にする

現行 `startPreview()` はdevice-globalな `videoConfigCandidates()`（4K30/1080p60）を先にcodec判定し、失敗時だけ
`supportedVideoConfigs(cameraId)` を見る。このままでは外部cameraでも4K30が
`CameraCapabilities.videoConfig` に入る。また `startRecording()` の `nextVideoConfig` 再検証もMediaCodec能力
しか見ず、切替前cameraの4K設定が漏れ得る。次で統一する。

1. `supportedVideoConfigs(cameraId)` は各候補について、(a) PRIVATE output size、(b) 候補fpsを包含する
   `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`、(c) MediaCodecのsize/fps/bitrate、の3条件を交差する。
2. `startPreview()` はこの戻り値の先頭だけをdefaultにする。外部cameraは1080p30をdefaultにする明示的な
   sort policyを持つ（内蔵cameraの既存4K優先は維持）。
3. `startRecording()` は `nextVideoConfig` が**現在cameraIdの戻り値に値一致で含まれること**まで再検証する。
4. 候補が空ならpreview開始を失敗させ、「対応する録画形式がありません」とUIへ返す。codecだけを見た
   device-global fallbackへ戻さない。

Sony vendor tag `realSupportedFrameRateFor30fps` / `realSupportedFrameRateFor60fps` は、名称とdumpsys値だけでは
現在のHDMI入力カデンスなのかモード能力なのか断定できない。MVPの選択ロジックには使わず診断ログ限定とする。
1080p60は上記3条件を満たす場合だけ候補に残すがdefaultは30fps。Phase 1bで録画ファイルの連続video PTS差分を
集計し、29.97/30/59.94/60、重複、欠落を判定してからvendor tag利用の是非を決める。

### 9.6 外部入力MVPでは静止画・ヒストグラムSurfaceを構成しない

現行previewは `preview + smallest YUV histogram + JPEG photo` の最大3 Surfaceをまず試し、失敗すると
preview-onlyへ落とす。外部HALの目的は映像入力録画で、追加SurfaceはR2の検証行列と失敗原因を増やす。

- 外部入力時は `histogramReader` / `photoReader` を生成しない。
- 外部入力を選んだらCaptureModeをVideoへ切り替え、Photoをdisabled表示して理由を示す。
- Phase 3でJPEG sizeと `preview + JPEG` の組合せを実機確認後に静止画を解禁する。
- 録画sessionは `preview + encoder`、画面消灯時は既存どおり `encoder only` の2ケースに絞って検証する。

### 9.7 非同期切替とUI操作の競合を閉じる

`switchLens()` はcoroutine完了順でstateを更新するため、連打すると古いopen結果が後からstateを上書きし得る。
入力ソース切替で同じパターンを増やさないよう、ViewModelにswitch generation（または切替Jobのcancel +
generation確認）を入れ、最新要求だけがUI stateをcommitする。録画中だけでなく
`StartingPreview` / `StartingRecording` / `Stopping` 中もsource/lensボタンをdisabledにする。

外部入力中はISO、shutter、AF/MF、focus slider、zoom、tap/long-press focusをdisabledまたは非表示にする。
UIだけでなくViewModel setterもcapabilityを確認してreturnし、アクセシビリティ操作やstale callbackから
非対応requestが入らないよう二重に守る。外部切断後はfocus reticleと古いhistogram表示を即clearする。

### 9.8 実装順と受け入れ条件（確定版）

1. **列挙・純粋ロジック**: 外部sourceモデル、共通能力値、camera別config交差を実装し、fake metadataを使える
   範囲はunit test化する。内蔵lens列挙と内蔵config順序が変わらないことも回帰確認する。
2. **preview**: monitor、source UI、能力gate、generation付き切替を実装。外部ではpreview 1 Surfaceだけで
   1080p30の連続表示、内蔵往復、他アプリ使用中表示、抜き差し20回を確認する。
3. **record**: `preview + encoder` を追加。10秒/5分、1080p30、可能なら1080p60を録画し、ffprobeで
   解像度、平均/実fps、単調PTS、A/V開始差、duration差、再生可否を確認する。
4. **clock**: 最初の数十video bufferについて `presentationTimeUs`、`nanoTime`、
   `elapsedRealtimeNanos` の差を診断ログへ出す。MONOTONICなら現状維持、BOOTTIMEなら§3案Aのvideo側変換を
   入れ、同じ受け入れ試験を再実行する。診断ログは判定後にdebug flag配下へ置く。
5. **障害系**: preview中、録画開始session再構成中、10秒録画中、segment境界付近、画面消灯encoder-only中に
   抜線する。クラッシュ/ANRなし、通知一度、停止処理がタイムアウトせず、確定済みsegmentが再生可能、
   current segmentも可能な限りfinalize、UIがInternalへ戻ることを各5回確認する。

MVPの完了条件は上記5段階がSO-51C実機で通り、既存内蔵cameraのpreview/photo/record、レンズ切替、画面消灯録画、
音声入力選択に回帰がないこと。Phase 2のdataspace/HDRと音声auto-suggest、Phase 3の外部静止画はMVPに含めない。
