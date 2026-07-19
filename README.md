# AuCamPRO

AuCamPROは、Android端末で高解像度動画と高品質音声を同時に収録するカメラアプリです。USBオーディオ入力、最大192kHzのFloat音声処理、WAV／動画内音声の保存、ヘッドホンモニターに対応します。

## 制作体制

本プロジェクトは、**Codex・Claude・GeminiによるAIエージェントの共同制作**です。コードの設計、実装、修正、テストコードの作成はAIエージェントが行っており、**人間はコードの実装・編集には関与していません**。人間による実機接続や動作確認を含む場合でも、それは検証・指示の範囲であり、コード制作そのものはAIエージェントによるものです。

## 主な機能

- Camera2による動画撮影
- USB／内蔵マイクの音声入力
- 48kHz・96kHz・192kHzのサンプルレート選択と端末依存のフォールバック
- Float32 DSP処理
  - 入力ゲイン
  - ハイパスフィルター
  - 3バンドEQ
  - メイクアップゲイン
  - セーフティリミッター
  - L/Rピーク・RMSメーター
- AAC音声を含む動画ファイルの生成
- 収録音声のHi-Res WAV保存
- 録画中の音声PTSと映像PTSの同期
- 有線／USBヘッドホン向けの低遅延モニター
- USB入力・出力デバイスの切断／再接続への対応
- 録音リングバッファ、ハードウェアXRUN、モニターFIFOの診断ログ

## 動作環境

### 実行環境

- Android 10（API 29）以降
- USB Audio対応端末・USBオーディオインターフェース（使用する場合）
- モニター機能は有線またはUSBヘッドホンを推奨

### 開発環境

- JDK 17
- Android SDK、Android SDK Platform 36
- Android NDK 27.3.13750724
- CMake 3.22以上
- Gradle Wrapper（リポジトリに含まれるもの）

`local.properties`には各環境のAndroid SDKパスを設定してください。通常はAndroid Studioでプロジェクトを開けば自動設定されます。

## Xperia向け実機情報

本プロジェクトの基準実機は **Sony Xperia 1 IV（docomo SO-51C、Android 14 / API 34、arm64-v8a）** です。以下はSO-51Cで確認した内容であり、Xperiaの機種・キャリア版・Androidバージョンによって端子やAudio HALの挙動が異なる場合があります。

### 3.5mmイヤホンジャック

SO-51Cには本体の3.5mmアナログイヤホンジャックがあります。

- 3.5mmイヤホンを接続すると、Androidでは`TYPE_WIRED_HEADPHONES`または`TYPE_WIRED_HEADSET`として通知されます。
- AuCamPROのMONITORは、この有線出力またはUSBヘッドホン出力だけを安全な出力として許可します。
- 内蔵スピーカー、Bluetooth A2DP、Bluetoothスピーカーは、マイクへのハウリング防止のためMONITOR対象外です。
- マイク付き4極プラグでは、出力が有線ヘッドセットとして認識され、入力候補にも現れる場合があります。録音入力をUSB Audioに固定したい場合は、AUDIOパネルの入力デバイスで`USB Audio`を選択してください。
- イヤホンの抜き差し中はAudioDeviceCallbackが発生します。録画中に抜いた場合、MONITORは安全のためオフになり、再接続後はMONITORを再度オンにしてください。

### USB Audio入力との組み合わせ

SO-51Cでは、USB-C接続のUSBオーディオインターフェースを入力にしながら、3.5mmジャックをモニター出力にする構成を実機確認しています。入力と出力は別AudioStreamとして扱われます。

```text
USB-C USB Audio I/F → 録音入力
3.5mmイヤホン     ← MONITOR出力
```

ただし、USB-C端子が1つしかない機種では、USBハブ・OTGアダプター・給電の相性が結果を左右します。3.5mmジャックを持たないXperiaでは、USB-CヘッドホンとUSB Audio入力を同時に使えるかを機種ごとに確認してください。Bluetooth出力は現在の安全ルーティング対象ではありません。

### Xperia固有の操作・設定

- Xperiaの物理カメラキーは、アプリの録画開始／停止操作に使用できます。連打防止のため、短時間の連続入力は無視されます。
- 初回起動時にカメラ、マイク、通知の権限を許可してください。録画中はカメラ・マイクのフォアグラウンドサービスを使用します。
- 長時間録画では、設定アプリのバッテリーセーバー／STAMINAモードによるバックグラウンド制限を解除し、端末の発熱と空き容量を確認してください。
- Xperiaの「オーディオ設定」「DSEE」「Bluetooth優先」などのシステム設定は、アプリが選択したAudioDeviceの実際の経路に影響することがあります。問題発生時は`adb shell dumpsys audio`で接続デバイスとActive communication deviceを確認してください。

### Xperiaでの確認コマンド

```bash
# 接続中の入力・出力デバイスを確認
adb shell dumpsys audio | grep -iE 'headset|headphone|usb|a2dp|Active communication device'

# AuCamPROの音声ログだけを確認
adb logcat --pid=$(adb shell pidof com.aucampro.recorder) | grep -iE 'AuCamPRONative|Monitor diagnostics|RecordingPipeline'
```

`Input stream opened`の`channelCount`、`sampleRate`、`deviceId`と、`Monitor output stream opened`の`deviceId`、`sampleRate`を確認してください。要求した192kHzが端末側で利用できない場合は、アプリが96kHzまたは48kHzへフォールバックします。

## プロジェクト構成

```text
app/src/main/java/       Kotlinアプリ本体、UI、録画パイプライン、エンコーダ
app/src/main/cpp/        JNI、Oboe音声エンジン、DSP、ロックフリーバッファ
app/src/main/cpp/test/   ホスト側のC++単体テスト
docs/                    音声設計、同期、調査記録、外部入力設計
```

主要なネイティブ音声経路は次のとおりです。

```text
入力Oboeコールバック
  ├─ DSP（Gain → HPF → EQ → Makeup → Limiter → Meter）
  ├─ 録音用SPSC FIFO → AudioEncoder / WAV
  └─ モニター用SPSC FIFO
                       ↓
               独立Oboe出力コールバック
                       ↓
                 ヘッドホン出力
```

録音FIFOとモニターFIFOは別系統です。モニターの遅延や一時的な再同期が録音データの欠落を引き起こさないよう、入力コールバックから出力ストリームへ直接`write()`する設計は採用していません。

モニター出力は、入出力デバイスのクロック差・AAudioのスケジューリング差を吸収する適応リサンプラーを使用します。FIFOが過剰に滞留した場合は、短いフェードアウト／再プライミングで遅延を抑えます。

## ビルド

### Androidアプリ

```bash
./gradlew assembleDebug
```

生成物:

```text
app/build/outputs/apk/debug/app-debug.apk
```

リリースビルドはプロジェクトの署名設定に従って実行してください。

```bash
./gradlew assembleRelease
```

### Android単体テスト

```bash
./gradlew testDebugUnitTest
```

### ネイティブ単体テスト（ホスト）

OboeやJNIに依存しないDSP・FIFO・モニター経路を開発PC上でテストできます。初回はGoogleTestの取得にネットワーク接続が必要です。

```bash
cmake -S app/src/main/cpp/test -B build-host-test -DCMAKE_BUILD_TYPE=Debug
cmake --build build-host-test -j
ctest --test-dir build-host-test --output-on-failure
```

対象には以下が含まれます。

- SPSCリングバッファの順序性・ラップアラウンド・並行性
- DSP（EQ、リミッター、メーター、ゲイン）
- Hi-Res音声変換
- モニターFIFOのプライミング、アンダーフロー、オーバーフロー、再同期、クロック補正

## 実機インストール

USBまたはワイヤレスADBで端末を接続し、次を実行します。

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.aucampro.recorder -c android.intent.category.LAUNCHER 1
```

実機ログは次で確認できます。

```bash
adb logcat --pid=$(adb shell pidof com.aucampro.recorder)
```

## 使い方

1. アプリを起動します。
2. `AUDIO`パネルで入力デバイスとサンプルレートを確認します。
3. 必要に応じてゲイン、ハイパス、EQを設定します。
4. モニターを使う場合は、有線またはUSBヘッドホンを接続してから`MONITOR`を有効にします。
5. `REC`を押して録画を開始します。
6. 録画停止後、生成された動画とWAVを確認します。

モニターを内蔵スピーカーへ出力することは、マイクへのハウリングを避けるためUI側で制限されています。

## 診断ログ

録音経路とモニター経路の異常は別々に記録されます。

```text
Recorded-audio diagnostics:
  ring buffer overrun、録音ドロップフレーム、入力ハードウェアXRUN

Monitor diagnostics:
  FIFO fill / target、補正量(ppm)、アンダーフロー、オーバーフロー、再同期、出力XRUN
```

モニターのログ例:

```text
Monitor diagnostics: fill=12500/15360 frames, correction=1200ppm,
underflows=0(0 frames), overflows=0(0 frames), resyncs=0, outputXRuns=0
```

録音データの完全性を確認する場合は、`ring buffer overrun`、`droppedFrames`、`hardwareXRuns`を確認してください。モニター側の再同期は、録音FIFOとは独立しています。

## トラブルシュート

### 音声が無音または片チャンネルだけになる

- `AUDIO`パネルで入力デバイスが意図したUSBデバイスか確認します。
- USBケーブル、USBハブ、端末のOTG接続を確認します。
- 実機ログの`Input stream opened`でチャンネル数とサンプルレートを確認します。
- 端末が要求レートに対応しない場合、アプリは低いレートへフォールバックします。

### モニターが無音になる

- 有線／USBヘッドホンが接続されていることを確認します。
- `setMonitoringEnabled(true) succeeded`が出ているか確認します。
- `Monitor diagnostics`の`underflows`、`overflows`、`outputXRuns`を確認します。
- 出力デバイスを切断・再接続した場合は、MONITORを一度オフにしてからオンにします。

### 録音にドロップや音切れがある

- `Recorded-audio diagnostics`の`overruns`と`droppedFrames`を確認します。
- 録画中に高負荷なアプリを終了します。
- USBオーディオの電源・ケーブル・ハブを確認します。
- Hi-Res設定で負荷が高い場合は、まず96kHzまたは48kHzで比較します。

## 設計資料

- [アーキテクチャ](docs/ARCHITECTURE.md)
- [Hi-Res音声設計](docs/HIRES_AUDIO_DESIGN.md)
- [仕様](docs/SPEC.md)
- [音声不安定性の調査記録](docs/AUDIO_INSTABILITY_INVESTIGATION_2026-07-18.md)
- [外部HDMI入力設計](docs/EXTERNAL_HDMI_INPUT_DESIGN.md)

## 注意事項

- 録音品質・対応サンプルレート・チャンネル構成は端末とUSBオーディオ機器に依存します。
- モニターは録音品質を優先するための補助経路です。モニターFIFOの再同期時には短い無音またはフェードが発生する場合があります。
- 実機での長時間録画では、保存された動画、WAV、logcatの診断値をセットで確認してください。
