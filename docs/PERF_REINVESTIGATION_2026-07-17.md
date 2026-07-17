# 再検証レポート: アイドル再描画残存・回帰確認・deviceId レビュー監査 (2026-07-17 深夜)

実施: Fable (読み取り専用の監査・計測セッション。コード変更なし)
対象コミット: `21bcade` (音声デバイスホットスワップ修正 + deviceId 副キー), `7053206` (P1 UAF修正 + P2-P4 再描画削減)
実機: SO-51C (QV7713DGCD), release ビルド + `cmd package compile -m speed -f` AOT 済み
前回レポート: `docs/PERF_INVESTIGATION_2026-07-17.md`

---

## 1. アイドル再描画 ~18.6fps 残存の原因究明

### 検証結果(結論先出し)

**残存 ~18.3fps はバグではなく「本物の信号変化」による正当な配信であり、修正コードは
設計どおり動作している。** ただし Sony Video Pro を同条件で実測すると 3.5fps であり、
「正しいが、まだ Sony 水準ではない」。差の正体はメーターの**数値ラベルの表示分解能
(0.5dB)が Sony(数値ラベルなし・セグメントのみ ≈2.5dB 分解能)より 5 倍細かい**こと。
追加最適化の余地はあるが、必要性はユーザー判断(下記提案参照)。

### コミット内容の独立検証(diff 実読)

両コミットともコミットメッセージどおりの実装であることをソースで確認した:

- `7053206` P1: `app/src/main/cpp/jni/native-lib.cpp` — `g_registry`
  (`std::unordered_map<jlong, engine*>`) + `std::shared_mutex`。全 JNI 呼び出しが
  `EngineGuard`(shared_lock)でハンドル解決し、`nativeDestroy()` は unique_lock 取得後に
  erase → ロック解放後に delete。**進行中の呼び出しが返るまで delete がブロックされ、
  erase 後の呼び出しは null を見て no-op になる。UAF の窓は閉じている。**
  `NativeEngineBridge.kt` の `closed` フラグは fast-path 化(コメントも正確に更新済み)。
  `AudioEncoder.kt` は `BUFFER_EMPTY_SLEEP_MS` 2→5ms のみ(P7)。
- `7053206` P2-P4: `CameraControlViewModel.kt` — メーター 0.5dB 量子化
  (`METER_DB_STEP`)、ヒストグラム L1 距離 0.5 閾値で配信スキップ
  (`HISTOGRAM_PUBLISH_THRESHOLD`、最終**配信**値との比較なので緩慢なドリフトも最終的に
  配信される実装で正しい)。`LevelGaugeOverlay.kt` — 0.1° 量子化 + `isLevel`/色の読み取りを
  両 `Canvas` の draw ラムダ内へ移動(コンポジション位相での state 読み取りは排除済み)。
- `21bcade`: `RecordingPipeline.kt` — `dispatchAudioDeviceSetChanged()` が
  `pipelineScope.launch { sessionMutex.withLock { ... } }` で両呼び出し元
  (Settings ピッカー main スレッド / `AudioDeviceCallback` の HandlerThread)を直列化し、
  ブロッキングなネイティブ呼び出しは `withContext(Dispatchers.IO)` へ。
  `AudioDeviceRouter.candidateInputDevices()` は
  `sortedWith(compareBy({ priorityOf(it.type) }, { it.id }))`(53-58行)。

### 実機再計測

| 測定 | 値 |
|---|---|
| AuCamPRO アイドル再描画 (gfxinfo, 30秒, 2回) | **549 frames / 30s = 18.3fps**(2回とも 549 — 再現性高い) |
| AuCamPRO CPU (`measure2.sh` 40秒) | **29% / 1コア**(main 323 ticks, RenderThread 305 ticks = 7.6%) |
| Sony Video Pro アイドル再描画(同室・同時刻・メーター/水準器表示あり) | **105 frames / 30s = 3.5fps** |
| Sony Video Pro CPU (同 40秒) | **20% / 1コア**(RenderThread 38 ticks = **0.95%**) |

修正前 31fps → 18.3fps は確認。CPU 37%→29% も Sonnet の計測と一致。

### 何がまだ描画しているのか — screencap 差分での画素レベル特定

1秒間隔スクリーンショット 16 枚の連続ペア差分(領域別、閾値付き):

| 領域 | 変化したペア数 |
|---|---|
| 音声メーター (左上, x[42,229] y[232,409]) | **15/15 (毎回)** |
| 水準器 (中央, x[729,1043] y[400,587]) | 3/15 |
| ヒストグラム (左下) | **0/15 (完全静止)** |
| 右コントロールパネル | **0/15 (完全静止)** |

同じ手法で Sony Video Pro のメーター領域は **2/11** しか変化しない。

**メーターがほぼ唯一の駆動源。** メーター表示は実測 -31〜-35dB 前後(「静かな部屋」でも
PC ファン等の暗騒音がこのレベルで常時存在し、有線ヘッドセットマイク経由)。ピーク/RMS は
50ms ごとに 0.5dB 量子化ステップを普通に跨ぐため、20Hz ポーリングのほぼ毎ティックで
StateFlow が配信される → 18.3fps ≈ 20Hz × (1 − 等値縮退分)。数値が綺麗に説明できる。

### 他の常時無効化源の監査(全て白)

- `AudioStatsRow` (MainScreen.kt:1373): `meterState` を collect するが AUDIO タブ内のみで
  アイドル時(CAMERA タブ)は非コンポーズ。右パネル画素も 0/15 で静止確認。
- REC インジケーター (MainScreen.kt:686-699): `rememberInfiniteTransition` は
  `state.isRecording` 分岐の内側でのみ生成 — 非録画時に VSync 購読なし。ゲート確認済み。
- `FocusReticleOverlay`: アニメーション/フレームループなし。タップフォーカス時のみ描画。
- `ThermalMonitor`: ポーリングなし、`OnThermalStatusChangedListener` のイベント駆動のみ。
- `DeviceOrientationTracker`: 素の `var` に 90° 量子化値を保持するだけ。Compose state 非接続。
- AWB/AF パッシブ測定 (RecordingPipeline.kt:594-627 の `frameCount % 10` パス):
  ViewModel 側 (CameraControlViewModel.kt:118-150) に 50K / 0.1 diopter のデッドバンドあり。
  右パネル静止の画素証拠とも整合。

### 判定と次の一手の提案

**判定: 「正当な配信」であり P2-P4 の実装ミスではない。ただし Sony 比でまだ ~15fps /
RenderThread ~6.7pt / CPU ~9pt の差があり、詰める価値はある(任意)。**

差の構造: バー本体は 24 セグメント × 60dB レンジ = **2.5dB/セグメント**
(AudioMeterBar.kt:239-241)なので、0.5dB 刻みの配信のうち約 8 割はバーの画素を 1px も
動かさず、**数値 dBFS ラベル(0.1dB 表示精度)だけを書き換えている**。Sony は数値ラベル
自体を持たない。

提案(P8 候補、実装は別途判断):
1. **配信を 2 系統に分離**: バー用はセグメント境界 (2.5dB) を跨いだときのみ配信、
   数値ラベル用は 4-5Hz に間引いて 0.5dB 量子化を維持。実装は
   `CameraControlViewModel.startMeterPolling()` 内の publish 条件変更のみで、
   見た目のバー応答性(20Hz)は変化時には保たれる。期待値: アイドル 18.3fps → 2-4fps、
   RenderThread ~7.6% → ~1-2%、CPU 29% → 24-25% 程度(Sony 20% にほぼ並ぶ)。
2. 代替案(より小さく): 非録画時のみ `METER_POLL_INTERVAL_MS` を 50→100ms に。
   半減止まり (~10fps) なので効果は限定的。案 1 を推奨。
3. 水準器 (3/15) とヒストグラム (0/15) は追加対応不要。

## 2. 回帰確認 (P1 UAF 修正 / P2-P4 量子化)

**判定: 回帰なし。** 実施した操作と確認:

- 録画 2 回(17秒 4K HEVC + 6秒)を hardware camera key (`input keyevent 27`) で開始/停止。
  両方とも正常終了し `Movies/AuCamPRO/` へエクスポート確認
  (`AuCamPRO_1784298179506_segment_0.mp4` 162MB / `AuCamPRO_1784298216489_segment_0.mp4` 24MB)。
  停止→プレビュー復帰→即再録画のサイクルも正常。
- バックグラウンド/フォアグラウンド 2 往復: pid 29460 のまま生存、プレビュー復帰正常。
- Settings のマイク入力ピッカー: 自動 → 内蔵マイク (id=22) → 自動 (有線ヘッドセット
  id=1090 に解決) を切り替え。logcat で
  `Input device set changed, switching to ...` を確認、かつ **`OboeAudio:
  openStreamInternal()` が tid 29576(バックグラウンド)で実行されている** —
  21bcade の main スレッドオフロードが実際に効いている(修正前は main 直走)。
- logcat 全 21,936 行に FATAL / SIGSEGV / SIGABRT / ANR なし(唯一の E 行は Sony
  GameEnhancer が本アプリの Play ストアページ取得に 404 — 無関係のシステムノイズ)。
- `dumpsys dropbox`: セッション後も最終エントリは 2026-07-17 13:31 の
  `data_app_anr`(com.microsoft.skydrive — 無関係)のまま。**本セッション中の新規
  native crash / ANR / tombstone はゼロ。** 2026-07-16 23:20 の UAF tombstone の再発なし。
- 体感確認(スクリーンショット比較): メーターはマイク切替の操作音に即応して
  L/R 非対称 (-40.5 / -54.5) まで振れ、ピーク/セグメント表示も正常。水準器は
  +1.1°/+1.2° を行き来し 0.1° 量子化でステッピングは知覚不能。ヒストグラムは
  静止シーンで静止(意図どおり)、シーン変化(設定シートの開閉による露出変化)で
  即時更新。ラグ・固着なし。
  ※ 手で端末を傾ける/大きな音を出す物理テストは遠隔 (adb) のため未実施。
  ユーザーに一度だけ実機で「傾け・発声時のメーター/水準器の追従感」の確認を推奨。

## 3. `deviceId` 副キーに関するレビューコメントの監査

> deviceId は再接続や再起動で変わり得るため永続識別子として不適。将来の同一物理デバイス
> 復帰・ユーザー設定保持で課題。productName/type/チャンネル数等での照合や選択デバイスの
> 永続化を提案。

**判定: 現状コードの潜在バグの指摘ではなく、スコープ外と既に整理済みの将来機能の話。
今回のコミットに対するアクションは不要。**

根拠(コード実読):

- `deviceId` の用途は `candidateInputDevices()` (AudioDeviceRouter.kt:58) の
  **同一優先度層内のソート副キーのみ**。戻り値は
  `onAudioDeviceSetChangedLocked()` (RecordingPipeline.kt) が**その場で**フォールバック
  順走に消費する。デバイス増減のたびに `getDevices()` を再列挙して作り直すため、
  再接続・再起動をまたいで id が参照される経路は存在しない。
  `RecordingPipeline.activeInputDeviceId` も現行セッションの表示/照合用で、
  ホットスワップ時に毎回上書きされる。
- 永続化されるのは `UserPreferencesStore.kt:63-65` の `InputKind` **enum 名のみ**
  (Auto/Usb/Wired/BuiltIn の「種別」)。deviceId はどこにも保存されない。UI も実機確認
  どおり種別ピッカー(自動/USB Audio/有線ヘッドセット/内蔵マイク)であり、
  「この特定の USB インターフェース」を選ぶ UI 面は存在しない。
- したがって「deviceId は永続識別子として不適」は Android の事実として正しい
  (`AudioDeviceInfo.getId()` は接続中のみ有効な一時ハンドル)が、**本コードは deviceId を
  永続識別子として使っていない**。コミットの主張(「今この瞬間に複数 USB が刺さっている
  ときの選択を決定的にする」)の範囲では正しく、範囲外の性質を要求するのはレビューの
  射程超過。
- 前セッションのスコープ整理(per-device sticky 選択 = `productName`+`type` 永続化は
  別機能として見送り)とも一致。なお参考: 仮に将来その機能を作る場合も、同一モデルの
  USB IF を 2 台挿すと `productName` は衝突するため、**id 副キーはその場の決定性担保
  として残す価値があり、置き換えではなく「永続照合を上に重ねる」形が正しい**。
- 推奨: レビューコメントは「将来 per-device 選択機能を作るときの設計メモ」として
  バックログに留める。コード変更・コメント追記とも不要(AudioDeviceRouter.kt:55-57 の
  既存コメントが主張範囲を正しく限定している)。

---

## まとめ

| 項目 | 判定 |
|---|---|
| P1 UAF 修正 | 実装正当・実機で再発なし |
| P2-P4 再描画削減 | 実装正当・31→18.3fps・回帰なし |
| 残存 18.3fps | バグではなく暗騒音による正当配信。ただし数値ラベルの 0.5dB 配信が主因で、バー(2.5dB/seg)配信との 2 系統分離で Sony 水準 (~3.5fps) に迫れる見込み(P8 提案、任意) |
| 21bcade ホットスワップ修正 | 実機で main オフロード動作確認・ANR なし |
| deviceId レビューコメント | 現状バグに非ず。スコープ外将来機能のメモとしてバックログへ。アクション不要 |
