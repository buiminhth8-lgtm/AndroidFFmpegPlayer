# MediaPlayerActivity UI Refactor Report

## Summary

Refactored `MediaPlayerActivity` into a fullscreen video player page:
`Video Surface + playbackInfoTextView + floating control button + hideable control panel`.

`playbackInfoTextView` retained: **YES**

---

## 修改文件

| 文件 | 内容 |
| --- | --- |
| `app/src/main/java/com/example/motro/MediaPlayerActivity.java` | 删除控制台 UI、日志改为 Logcat、悬浮按钮与面板显隐、TAG 常量调整 |
| `app/src/main/res/layout/activity_media_player.xml` | 重写为 ConstraintLayout：Surface + playbackInfoTextView + 悬浮按钮 + include 控制面板 |

## 新增文件

| 文件 | 内容 |
| --- | --- |
| `app/src/main/res/layout/view_media_player_controls.xml` | 独立悬浮控制面板（SOURCE/RTSP/LATENCY/PLAYER/CONTROL/DEBUG/RECORDING/SNAPSHOT 分区） |
| `app/src/main/res/drawable/bg_playback_info.xml` | 播放信息半透明圆角背景 `#88000000` |
| `app/src/main/res/drawable/bg_control_panel.xml` | 控制面板半透明深色圆角背景 `#D91A1A1A` |
| `app/src/main/res/drawable/bg_control_toggle.xml` | 悬浮按钮/关闭按钮圆形半透明背景 |
| `app/src/main/res/drawable/bg_edit_text.xml` | 控制面板输入框半透明背景 |
| `app/src/main/res/drawable/ic_player_controls.xml` | 设置(齿轮)矢量图标 |
| `app/src/main/res/drawable/ic_close.xml` | 关闭(×)矢量图标 |
| `app/src/main/res/values/styles.xml` | 控制面板 Section 标题 / EditText / RadioButton / Switch / Button 样式 |
| `MEDIA_PLAYER_UI_REFACTOR_REPORT.md` | 本报告 |

## 删除 UI

- `handleTextView`
- `logTextView`
- `clearLogButton`
- 原页面底部控制台 `ScrollView`（含 Debug console TextView）

## 保留 UI / View ID

- `playerPreviewView`（SurfaceView，全屏填充，ID 不变）
- `playbackInfoTextView`（常驻左下角，**不进入可隐藏面板**）
- `controlToggleButton`（新增悬浮按钮，52dp，右侧居中）
- 控制面板内全部原 ID 不变：`urlEditText`、`timeoutEditText`、`transportRadioGroup`（tcp/udp/auto）、`latencyModeRadioGroup`（ultra/low/balanced/stable）、`audioSwitch`、`reconnectSwitch`、`hardwareDecodeSwitch`、`createButton`、`infoButton`、`probeButton`、`prepareButton`、`startButton`、`pauseButton`、`stopButton`、`releaseButton`、`clearSurfaceButton`、`recordPathEditText`、`segmentPatternEditText`、`recordFormatEditText`、`segmentDurationEditText`、`startRecordButton`、`startSegmentRecordButton`、`stopRecordButton`、`snapshotPathEditText`、`snapshotButton`、`recordStateButton`、`stateButton`、`statsButton`、`reconnectStateButton`
- 新增 `controlPanelCloseButton`（面板右上角 ×）

## 控制面板结构

面板为右侧悬浮 `LinearLayout`（宽度 340dp，高度约 84%，内部 ScrollView），按分区：

```
SOURCE          → urlEditText / timeoutEditText
RTSP TRANSPORT  → TCP / UDP / AUTO
LATENCY         → Ultra Low / Low / Balanced / Stable
PLAYER OPTIONS  → Audio / Reconnect / Hardware Decode
CONTROL         → Create / Prepare / Start / Pause / Stop / Release / Clear Surface
DEBUG           → Info / Probe / State / Stats / Reconnect / Rec State
RECORDING       → record path / pattern / format / segment seconds / Record / Segment / Stop Rec
SNAPSHOT        → snapshot path / Snapshot
```

默认 `GONE`。点击 `controlToggleButton` 显示/隐藏（右侧滑入滑出 + alpha，180/150ms）；面板右上角 `×` 关闭。

## 播放信息结构

`playbackInfoTextView`：左下角常驻，半透明黑底白字、`monospace`、12sp、最大约 8~10 行摘要。

显示内容（沿用 `getPlayerStats()` + `updatePlaybackInfoAsync()` + `updatePlaybackInfoFromStats()` + `playbackInfoRunnable`，1s 刷新，不显示整段 JSON）：

```
state=<state> | <renderMode> | <decoder>
decode x fps  render y fps  dropped n
bitrate ...  transfer ...  nominal ...
format ...  packets n  frames n
reconnect attempt=n event=... error=...
```

## Logcat TAG

- `FFmpegPlayer`（TAG）：普通播放器操作 / API 返回 JSON / 错误 `Log.e` / 警告 `Log.w`
- `FFmpegPlayerStats`（TAG_STATS）：周期统计 JSON（每约 5 秒一次，避免过量）
- `FFmpegPlayerEvent`（TAG_EVENT）：Native 事件回调

原 `appendLog` 改为 `logDebug`（`Log.d(TAG, message)`），`handleTextView` 相关信息改为 `Log.d(TAG, "playerHandle=" + handle)`。

## 编译结果

`.\gradlew.bat :app:assembleDebug`

```
BUILD SUCCESSFUL in 5s
```

## Native 是否修改

NO（`app/src/main/cpp/**` 未改动；JNI / NativePlayer / MediaCodec 未改动）

## 播放器行为是否修改

NO（Create/Prepare/Start/Pause/Stop/Release、Surface 生命周期 `surfaceCreated/surfaceChanged/surfaceDestroyed`、`bindPreviewCallback/updateSurfaceFromHolder/bindSurfaceForExistingPlayer`、Intent 自动播放参数 EXTRA_URL/HARDWARE_DECODE/RTSP_TRANSPORT/LATENCY_MODE 均保持）

## 待真机验证内容

- Activity 打开：Video 可见、playbackInfoTextView 可见、悬浮按钮可见、控制面板隐藏
- 点击悬浮按钮：面板显示；再次点击/点 ×：面板隐藏
- 播放期间 playbackInfoTextView 持续刷新（不因面板隐藏停止）
- Create/Prepare/Start/Pause/Stop/Release 正常
- Info/Probe/State/Stats/Record State/Reconnect State 点击后 Logcat 有输出、页面无 Debug Console
- onPause/onStop/onDestroy/Surface destroyed/Activity recreate 无 Crash、无 Surface/Runnable/Handler 泄漏（`playbackInfoRunnable` 生命周期未变）
