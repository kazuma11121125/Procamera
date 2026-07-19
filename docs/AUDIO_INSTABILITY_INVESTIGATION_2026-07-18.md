# WAV/MP4音声が時間経過で不安定になる問題 — 原因・修正・検証結果

最終更新: 2026-07-19  
コード基準: `56eb688` からの作業ツリー修正  
対象実機: Sony SO-51C  
状態: **修正実装・5分超の実機ストレス試験・生成MP4のpacket検証まで完了。**

> 対象はイヤホンのライブモニターではなく、保存されたWAVとMP4内AACである。
> モニター経路のclock driftは別件として本書末尾の調査記録に残すが、今回の修正範囲には含めない。

## 1. 症状

時間が経つにつれて、音声に次の症状が現れる。

- プチプチ、パチパチ、連続的なノイズ
- 音の途切れ、欠落
- 一部が飛ばされたように聞こえる
- 早送りまたは再生速度・ピッチが変化したように聞こえる
- 当初は正常でも、時間経過後に悪化する

調査では、症状が次のどこに存在するかを区別した。

1. **イヤホンのライブモニターだけ**
2. **MP4内のAAC録音**
3. **ハイレゾWAVサイドカー**
4. **AACとWAVの両方**

モニター経路はRingBufferへ書いた**後**に分岐しているため、モニターだけのノイズは録音データ破損を意味しない。
反対に、AAC/WAVにも同じ時刻で異常があれば、入力callback、DSP、encoder RingBufferまでの共通経路が疑わしい。

```text
Oboe input callback
  → DSP
  ├─→ SPSC RingBuffer → AudioEncoder
  │                      ├─→ raw Float WAV
  │                      └─→ decimate → Int16 → AAC → MP4
  └─→ outputStream.write() → イヤホンモニター
```

## 2. 結論

主因は1つではなく、欠落を起こす3つの欠陥と、それを連続ノイズへ悪化させる1つの欠陥が重なっていた。

| 原因 | 影響 | 確認根拠 | 対応 |
|---|---|---|---|
| Kotlin上の8次decimator + `Random.Default` TPDFが192kHzで重い | WAV/AAC共通ringが時間経過後に満杯 | 実機工程profileで変換だけで15〜18ms/21.3ms block、旧経路は104秒動画にAAC 63.6秒 | anti-alias、4:1 decimation、TPDF、Int16化を単一native処理へ移動 |
| 通常優先度の`AudioEncoderDrain`が端末高負荷時に長時間starveする | WAV/AAC共通ring overrun | CPU総負荷約92%時、consumerが約18秒遅れ、旧10.9秒ringで1,254,912 frames欠落 | Android音声thread優先度（nice -16）へ変更、ring実容量を約21.8秒へ拡張 |
| `MediaCodec.dequeueInputBuffer(10ms)`失敗時にPCMを黙って破棄 | MP4/AACだけにtimestamp穴 | 修正前実ファイルに通常21.333msに対して32.208msのpacket差を1件確認 | outputをdrainしつつ最大2秒retry、失敗時は明示error |
| ringがscalar `float`単位で部分書込み可能 | overrun後にL/R interleaveがずれ、連続ノイズ化し得る | コード上、空きsampleが奇数なら片channelだけ書込み可能 | `StereoFrame`単位 + callback全量または全破棄へ変更 |

補助的に、WAV書込みを1MiB単位でまとめ、JNI drainのblockごとのnative
`std::vector`確保と余分なcopyも除去した。WAV書込み自体は実測約0.2ms/blockであり主因ではなかったが、
storage jitterをringへ返しにくくする安全余裕として残している。

### 2.1 修正前ファイルの定量結果

`AuCamPRO_202607182338_segment_0` を解析した。

- WAV: Float32 stereo 192kHz、206.920秒
- MP4 video: 209.578秒
- MP4 AAC: 206.262秒
- WAV/AACがともにvideoより約3秒短く、共通ring側の欠落を示す
- AACにはさらに32.208msのpacket間隔が1件あり、512-frame block 1個を捨てた時の
  10.875ms追加差と一致した
- WAV sampleにNaN/Inf、full-scale clippingはなく、確認区間のpeakも約-14〜-18dBFSだった

従って、今回の「時間とともに途切れる・早送りのようになる」現象はdigital clippingでは説明できない。
「音割れが時々ある」という聴感は別途入力段・ADC・マイクの確認余地があるが、少なくとも解析ファイル上の
PCM full-scale clipではない。

### 2.2 実装後の実機検証

条件:

- Sony SO-51C、ワイヤレスADB
- 3840x2160 / 30fps / 50Mbps
- USB Audio、192kHz / stereo / Float32 WAV経路
- 5分segment、5分超連続録画

最終試験:

```text
capturedMs=321120
wallMs=321093
overruns=0
droppedFrames=0
highWaterFrames=66816   # 192kHzで約0.348秒
finalFillFrames=3840
```

native変換は長時間平均約3.2ms/blockで、旧Kotlin変換の15〜18msから大幅に短縮した。
5分segment境界も通過し、ringの欠落は発生しなかった。

生成された2 segmentを`ffprobe`/`ffmpeg`で検査した。

| segment | video | AAC | AAC packet数 | packet delta | decode error |
|---|---:|---:|---:|---|---|
| 0 | 300.588秒 | start 0.5005秒、duration 300.224秒 | 14,073 | 21.333〜21.334ms、異常gap 0 | 0 |
| 1 | 18.435秒 | start 0.1335秒、duration 19.179秒 | 899 | 21.333〜21.334ms、異常gap 0 | 0 |

segment 0のAAC終端は300.703秒で、video終端300.588秒との差は約115ms。修正前の数十秒単位の
圧縮・欠落はなく、全AAC packetが連続した1024-frame cadenceになった。

### 2.3 途中で棄却した仮説

- Muxer video queue不足: queue予約を入れた4分試験でも音声ringが欠落し、
  `videoBackpressureEvents=0`だったため原因ではない。試験コードはrevert済み。
- WAVの小刻みなwrite: 1MiB coalescing後も旧Kotlin変換版では再現し、工程profileでも平均約0.2msだった。
- AAC retryだけで全体が直る: packet timestamp穴は消えたが、WAV/AAC共通ringのthroughput不足は別に残った。
- 単純なthermal status: 失敗試験直後のAndroid thermal statusは0。実際にはCPU総負荷92%、
  `kswapd`稼働、swap逼迫を伴うsystem-load burstだった。

### 2.4 残る確認

機械検証では欠落とtimestamp穴は解消した。最終的な聴感、特に友人から指摘された「時々の音割れ」は、
一定tone/clickと実際のマイク入力を使った試聴で確認する必要がある。30分連続、96/192kHz、
USB抜き差しを含む全マトリクスは未実施であり、5分超実機試験をもって全条件の完全保証とはしない。

---

以下の§3〜§15は、修正前コードから候補を洗い出した時点の調査記録である。記述中の「現状」
「必要な修正」は当時の状態を指し、今回のWAV/MP4修正で対応済みの項目も含む。

## 3. P0-1: モニターの入力clockと出力clockが同期されていない

### 現状

`OboeFullDuplexEngine::onAudioReady()` は入力callbackが受け取った `numFrames` を、そのまま別の
AAudio output streamへ非ブロッキング `write()` している。

```cpp
output->write(samples, numFrames, 0);
```

入力streamと出力streamは、nominal sample rateが同じでも別々のhardware clockで動く。例えば両方が
192000Hzを報告していても、実際のclockには数十ppm程度の差があり得る。入力が出力より僅かに速ければoutput
bufferが徐々に満ち、遅ければ徐々に空になる。

現コミットはoutput bufferを約3 input callback分（約60ms）に増やしている。これは瞬間的jitterや
「1回のinput callbackがoutput capacityより大きい」という直近の不具合には有効だが、**継続的なclock driftを
解消しない**。bufferを増やす処置は発症までの時間を延ばすだけである。

### 症状との一致

- 起動直後は正常
- 数分〜数十分後にshort writeまたはunderrun
- プチプチ、途切れ
- buffer残量を取り戻す過程で、詰まった音が早送りされたように感じる可能性

### 判別

- 異常がイヤホンモニターだけで、同時刻のAAC/WAVが正常
- `monitorWriteShortfallCount` が異常発生時から増加
- input/outputのnominal rateは同じ
- 発症までの時間がbuffer sizeに比例して変わる

### 必要な修正の方向

入力callbackからoutput streamへ直接 `write()` しない。モニター専用frame-based FIFOを置き、output側の
data callbackがpullする構造へ変更する。その上でFIFOのfill levelを監視し、次のいずれかでclock driftを吸収する。

- 小さな非同期resamplerで比率を継続調整する（推奨）
- Oboeのfull-duplex支援機構を使い、入力・出力callbackのフレーム差を管理する
- 最低限、稀な1 frame挿入/削除をclick-freeに行う

単にbufferをさらに増やす、timeoutを長くする、入力callbackをblockさせる処置は採らない。入力callbackを
blockすると、モニター問題が録音側のxrunへ伝播する。

## 4. P0-2: RingBuffer overrunでステレオframe境界を破壊できる

### 現状

RingBufferは `float` **sample単位**で構築されている。

```cpp
ringBuffer_(kRingBufferCapacityFrames * kChannelCount)
ringBuffer_.write(samples, numFrames * kChannelCount);
```

`SpscRingBuffer::write()` は空き容量までの**部分書き込み**を許す。空きsample数が奇数なら、stereo frameの
Lだけを書いてRを書かないことが可能である。呼び出し側は未書き込み部分をretryせず、次のcallback全体へ進む。

例:

```text
本来:       L0 R0 | L1 R1 | L2 R2
空き1sample: L0   | 次callbackのL0 R0 | L1 R1 ...
consumer解釈: L0 L0 | R0 L1 | R1 ...
```

一度この形になると、単なる一瞬のdropoutではなく、その後のinterleave解釈が崩れて連続ノイズになり得る。
`drainEncoderBuffer()` 側もsample数をchannel数で整数除算しており、端数sampleを安全に回復しない。

### 症状との一致

- RingBufferが約10秒分あるため、consumerが少し遅い状態では開始直後でなく時間経過後に初めて満杯になる
- 満杯になった瞬間からAAC/WAVの両方が壊れる
- `ringBufferOverrunCount` が最初に増えた時刻と音声破綻開始が一致する
- 一度悪化すると自然回復しにくい

### 必要な修正の方向

- RingBuffer APIをsample数でなく**frame数**にする
- callback分を全量書けない場合は、frame単位の部分書き込み、またはcallback全体をdropする
- どの経路でも `writtenSamples % channelCount == 0` を不変条件にする
- debug buildでは不変条件をassertする
- overrun時は失ったframe数も数える。callback回数だけでは実損失量が分からない

これはcounter追加だけでは直らない。counterは原因確認用で、frame atomicityの保証が本修正になる。

## 5. P0-3: AAC入力bufferが10ms以内に取れないとPCMを捨てる

### 現状

`AudioEncoder.queueInput()` は次の実装になっている。

```kotlin
val inputIndex = codec.dequeueInputBuffer(10_000L)
if (inputIndex < 0) return
```

呼び出し側は、`queueInput()` が投入に成功したか確認せず、先に `cumulativeSampleCount` を進める。
従ってcodecが10ms以内にinput bufferを返さなかったblockは、次の状態になる。

- PCM内容はAAC encoderへ入らない
- PTS上のsample countだけ進む
- 警告ログもdrop counterもない
- 次のAAC packetのPTSが先へ飛ぶ

プレイヤーのgap処理によっては無音、途切れ、途中が飛んだ「早送り」のように聞こえる。WAVは
`queueInput()` より前に書かれるため、この原因なら**WAVは正常でAACだけ異常**になる。

### 高負荷で起こりやすい理由

- 510kbps AAC software encoder
- 同じthreadで192/96kHzのdecimation、TPDF dither、WAV書き込み
- MediaMuxer I/O queueのbackpressure
- thermal throttling、storage stall、segment rotation

### 必要な修正の方向

- `queueInput()` は成功/失敗を返す
- PCM blockはinput bufferへ投入できるまで保持し、`INFO_TRY_AGAIN_LATER` ならoutputをdrainしてretryする
- 無限待ちを避ける場合も、明示的なtimeout/errorとして録画を停止し、黙ってblockを捨てない
- `aacInputDroppedFrames` と最大input待ち時間を診断値にする
- EOS bufferも投入成功までretryする。現状EOS投入失敗後の `drainOutputUntilEos()` は終了しない危険がある

## 6. P1: AudioEncoder consumerが継続的に遅れる可能性

入力callbackは最大192kHz stereo Float32をRingBufferへ書く。consumerである `AudioEncoderDrain` は同じthreadで
次を順番に実行する。

1. JNI経由でRingBufferをdrain
2. WAVへFloat32を書き込み
3. 8次IIR low-passを全sampleへ適用して48kHzへdecimate
4. 全sampleへTPDF dither（1 sampleにつき乱数2回）
5. Float→Int16変換
6. MediaCodec input/output処理
7. Muxerへ渡すbufferをcopy

さらに `nativeDrainEncoderBuffer()` は呼び出しごとにnative `std::vector<float>` を確保し、
`SetFloatArrayRegion` でもcopyする。これはRT input callbackではないので即座に規約違反ではないが、約10.7msごとの
定常allocation/copyである。

平均処理速度が入力を僅かでも下回れば、約10秒のRingBuffer headroomを使い切った時点で§4のoverrunへ入る。
「しばらく正常、その後悪化」という症状と合う。

### 判別

- `ringBufferOverrunCount` が増える
- `hardwareXRunCount` は増えないこともある（input callback自体は間に合い、consumerだけ遅いケース）
- Standard 48kHzでは再現せず、96/192kHzで再現
- WAVを無効化、monitorを無効化、AAC bitrateを下げる等で発症までの時間が変わる
- thermal throttlingまたはsegment rotation付近で発生率が上がる

### 必要な測定

RingBuffer fill levelをframe単位で低頻度サンプリングし、以下を記録する。

- current / high-water mark
- producer frames
- consumer frames
- dropped frames
- drain block処理時間の平均、p95、p99、最大
- WAV write、decimator+dither、codec wait、mux callbackの各所要時間

counterだけでなくfill levelの傾向が必要である。fillが右肩上がりなら、buffer容量ではなくsteady-state throughputを
直す。

## 7. P1: 過去に確認・修正済みだが回帰確認が必要な原因

### 7.1 input/output sample rate不一致

以前はmonitor outputが48kHzのまま残り、inputだけ96/192kHzへ切り替わる経路があった。同じ `numFrames` を
直接渡すため、192kHz入力を48kHz出力として再生すれば約4倍、96kHzなら約2倍の速度・pitchになる。
`6b10895` / `56eb688` で次が追加されている。

- engine再start時に旧input/output streamをclose
- outputのgranted sample rate/channel数を検証
- mismatchならmonitorを有効にせずエラーにする

現在テストしているAPKがこのコミットを含むかを最初に確認する。古いAPKなら「早送り」の第一原因になり得る。

### 7.2 monitor output capacity不足

実機では192kHz input callbackが3840 framesだった一方、LowLatency output capacityは1536 framesしかなく、
毎callbackの全量writeが構造的に不可能だった。`monitorWriteShortfallCount` の連続増加とプチプチノイズが一致し、
outputをPerformanceMode::Noneへ変更しbufferを拡大したことで解消確認済み。

ただし§3のclock driftは別問題なので、短時間テスト成功だけで長時間安定とは判断しない。

### 7.3 録音開始前のstale RingBuffer backlog

audio engineはpreview中から動作するが、AudioEncoderは録画中だけRingBufferをdrainする。以前は長時間preview後に
古いbufferとframe positionがずれ、短い録画のほぼ全音声がPTS guardで破棄された。現在は録音開始時に
`flushRingBuffer()` し、timestamp correlationのframe positionからsample countをseedしている。

注意点として、overrun counterはpreview中の値を引き継ぐ。録画中に増えたかを見るには録画開始時のbaselineを
保存し、take内deltaで評価する必要がある。

### 7.4 varying block変換時のstale scratch

過去にはFloat→Int16変換結果を一時配列へ生成しながら、AAC投入時に別の古い `shortScratch` を読んでしまい、
garbled noiseになっていた。現在のin-place APIで修正済み。今後scratch処理を最適化する際に戻さない。

## 8. P2: device hot-swap時にSPSCのproducerが2つになる

RingBufferのproducerは通常input callbackだけだが、device切替後のgap補填ではKotlin側から
`insertSilence()` が同じRingBufferへ書く。

現在の順序は次のとおり。

1. 旧inputをclose
2. 新inputをopenして `requestStart()`
3. Kotlinへ戻る
4. 経過時間を計算
5. `insertSilence()` でRingBufferへ書く

手順2の後は新input callbackが既にproducerとして動き得るため、手順5と同時writeになればSPSC契約違反である。
USB抜き差し、入力設定変更、HDMI capture cardのaudio device再列挙付近だけで発生する候補で、通常の固定device
長時間録音の第一原因ではない。

修正時はsilence挿入を新stream開始前に行う、producer commandとしてaudio callbackへ渡す、またはRingBufferを
複数producer対応にする。ただしRT callbackへlockを持ち込まない。

## 9. P2: stream lifetimeと診断pollのdata race

`inputStream_` はstart/stop/reopenで `streamMutex_` 下から更新される一方、次はmutexなしで参照する。

- `hardwareXRunCount()`
- `getInputTimestamp()`

JNI registryのshared mutexはnative engineオブジェクトの寿命だけを守り、内部 `inputStream_` の同時read/resetを
守らない。品質変更やhot-swapとmeter poll/timestamp取得が重なると、C++の `shared_ptr` オブジェクト自体への
同時read/writeになり得る。

これはノイズよりcrash/未定義動作寄りの潜在問題だが、今回Claudeが追加中の20Hz
`hardwareXRunCount()` log pollingにより窓が広がる点に注意する。診断追加を外す必要はないが、getter側も
streamMutexでsnapshotする、または安全なatomic/lifetime設計へ直す。

## 10. 症状からの即時判定表

| 観測 | 第一候補 |
|---|---|
| モニターだけ壊れ、AAC/WAV正常 | monitor clock drift / monitor short write |
| AACだけ壊れ、WAV正常 | MediaCodec input blockのsilent drop |
| AACとWAVが同じ位置から壊れる | RingBuffer overrun / input hardware xrun / DSP以前 |
| WAV速度も明確に2倍・4倍 | 実capture rateとWAV header rateの不一致 |
| 古いAPKのハイレゾmonitorだけ2倍・4倍 | stale 48kHz output stream |
| USB抜き差し直後に壊れる | hot-swap SPSC複数producer / reopen失敗 |
| segment境界やstorage負荷で悪化 | Muxer backpressure → encoder遅延 → RingBuffer overrun |
| Standardは正常、96/192kHzだけ時間経過で悪化 | consumer throughput不足 / monitor clock drift |
| counterが全て不変なのに全出力が歪む | clipping、rate/header不一致、DSP数値、device/HAL側 |

## 11. 再現試験マトリクス

一度に複数条件を変えず、最低10分、可能なら30分ずつ行う。

| Case | Capture | Monitor | Output | 目的 |
|---|---:|---:|---|---|
| A | 48kHz | OFF | AAC | 基準 |
| B | 48kHz | ON | AAC | monitor分岐の影響 |
| C | 96kHz | OFF | AAC+WAV | hi-res consumer負荷 |
| D | 96kHz | ON | AAC+WAV | monitor clock/負荷追加 |
| E | 192kHz | OFF | AAC+WAV | 最大consumer負荷 |
| F | 192kHz | ON | AAC+WAV | 最悪条件 |
| G | 再現rate | OFF | AAC+WAV、5分segment | rotation相関 |
| H | 再現rate | ON | previewのみ | encoderと無関係なmonitor長時間試験 |

各試験で、スピーカー等から一定の1kHz sineまたはclick trackを入力する。会話や音楽だけではdrop位置と速度を
定量化しにくい。

## 12. 必須診断値

録音開始時、異常検出時、停止時に1行の同一session ID付きsnapshotを出す。

```text
captureRate, inputDeviceId,
inputFramesPerBurst, inputBufferSize, inputBufferCapacity,
outputRate, outputFramesPerBurst, outputBufferSize, outputBufferCapacity,
hardwareXRunDelta, ringOverrunDelta, ringDroppedFrames,
monitorShortfallDelta, monitorDroppedFrames,
ringFillFrames, ringHighWaterFrames,
aacInputRetryCount, aacInputDroppedFrames,
audioEncoderBlockTimeP95/P99/Max,
thermalStatus, batteryTemperature
```

現在Claudeが作業ツリーへ追加中の `ringBufferOverrunCount` / `hardwareXRunCount` のchange logは有用である。
ただし次を補う必要がある。

- counterの絶対値でなくtake開始からのdelta
- overrun callback回数でなく失ったframe数
- monitor shortfall回数でなく短書きframe数
- RingBuffer fill/high-water
- AAC input投入失敗・retry数

## 13. ファイル検証

### AAC/MP4

`ffprobe` でaudio packetのPTS差を抽出し、不自然なjumpを確認する。

```bash
ffprobe -v error -select_streams a:0 \
  -show_entries packet=pts_time,duration_time,size,flags \
  -of csv=p=0 input.mp4
```

確認項目:

- AAC packet PTSが単調か
- 通常packet間隔から大きく飛ぶ地点があるか
- 異常開始時刻がcounter増加時刻と一致するか
- video/audio duration差が時間とともに拡大するか

### WAV

確認項目:

- header sample rateと実際の既知tone周波数
- frame数 ÷ sample rateと実録音時間
- channel interleaveが異常開始点から崩れていないか
- NaN/Inf、連続最大値、急なDC offset
- AACと同一sample時刻に不連続があるか

1kHz入力がWAV上で2kHz/4kHzならrate metadataまたはcapture rate解釈が誤っている。周波数は正しく、一部区間だけ
消えるならdrop/backpressure系を優先する。

## 14. 修正の推奨順

Claudeの現在の診断追加を使い、まず再現ログを1本取る。その後は次の順で直す。

1. RingBufferをframe-atomicにし、overrunでinterleaveを壊さない
2. AAC input blockのsilent dropを廃止し、投入成功までretry/output drainする
3. RingBuffer fill/high-waterと実drop frame数を計測する
4. consumer各工程を計測し、192kHzでもsteady-stateでproducerより速くする
5. monitorをinput callbackから分離し、output callback + FIFO + clock drift補償へ変更する
6. hot-swapのsilence挿入をsingle producer契約内へ戻す
7. `inputStream_` getterとstart/stop/reopenのdata raceを閉じる

短期的な安全策として、原因確定までStandard 48kHzを既定に維持し、ハイレゾ+monitorの組合せに
「長時間安定性検証中」を表示することは妥当。ただしbuffer増量や処理のdisableだけで根本修正完了とはしない。

## 15. 完了条件

- 48/96/192kHzそれぞれ30分、monitor ON/OFFで再現しない
- `hardwareXRunDelta == 0`
- `ringOverrunDelta == 0`
- `aacInputDroppedFrames == 0`
- monitor FIFOが目標fill付近に留まり、長時間一方向へdriftしない
- AAC packet PTSに説明不能なjumpがない
- WAVのframe count、header rate、実時間が一致
- click trackの欠落・重複がない
- segment境界前後に不連続がない
- USB入出力の抜き差し後も、意図したsilence以外の破損がない

以上を満たして初めて「短時間聞いた限り正常」ではなく、長時間安定性が確認できたと判断する。
