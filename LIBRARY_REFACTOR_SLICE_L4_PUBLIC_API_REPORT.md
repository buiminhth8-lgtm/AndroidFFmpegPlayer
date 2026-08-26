# Library Refactor — Slice L4 — Public FFmpegPlayer API Facade

Date: 2026-08-18
Branch: dev (915f2c8 + L4)
Scope: 新增 Library 首要公共 API `FFmpegPlayer`，由其内部管理 native handle / LiveAudioPcmSink / native event bridge，并将 MediaPlayerActivity 迁移至 Facade。 不改 NativePlayer 算法与 JNI contract。

## Scope
`ffmpegplayer` 新增 `com.example.motro.ffmpeg.FFmpegPlayer`（`AutoCloseable`），`MediaPlayerActivity` 从直接 `FFmpegNative` handle 管理迁移至 `FFmpegPlayer`。

## L3 Baseline
L3（915f2c8）已收缩 `app` 为纯 Demo（仅 `MediaPlayerActivity`），`ffmpegplayer` 独占全部 Player Java/Native/FFmpeg 实现，三项构建 PASS。

## Public API Before
`MediaPlayerActivity` 直接持有 `long playerHandle` + `LiveAudioPcmSink audioPcmSink`，通过 `FFmpegNative.createPlayer()` / `setPlayerEventListener` / `setAudioCallback` / `setPlayerSurface` / `preparePlayer` / `startPlayer` / `pausePlayer` / `stopPlayer` / `releasePlayer` / `enableAudio` / `setHardwareDecode` / `setHardwareRenderMode` / `setRtspTransport` / `setPlayerLatencyMode` / `setThermal*` / `startPlayerRecordWithConfig` / `takePlayerSnapshot` / `getPlayerStats` 等 20+ 个 handle 方法直接操作 native handle，并自行处理 `handleLock` 与 `LiveAudioPcmSink` 生命周期。

## FFmpegPlayer Public API
`ffmpegplayer/src/main/java/com/example/motro/ffmpeg/FFmpegPlayer.java`（`public final class FFmpegPlayer implements AutoCloseable`）：

- **Handle Ownership**：`private long nativeHandle` + `private boolean released` + `private final Object lock`；`new FFmpegPlayer()` 内部 `createPlayer()` + `new LiveAudioPcmSink()` + `setPlayerEventListener`/`setAudioCallback`；`release()` 幂等（`released` 检查、`nativeHandle` 清零、`externalListener` 清空、`setPlayerEventListener(null)`/`setAudioCallback(null)` + `releasePlayer`，无 UAF/double-release）。
- **Audio Sink 内部化**：`LiveAudioPcmSink` 由 `FFmpegPlayer` 私有持有，Consumer 仅 `setAudioEnabled(boolean)`。
- **Surface**：`setSurface(Surface)` / `clearSurface()`（透传 `setPlayerSurface`/`clearPlayerSurface`）。
- **Lifecycle**：`prepare(String url, int timeoutMs)` / `start()` / `pause()` / `stop()` / `release()` / `close()`（保持 Fix 3 Prepare→Start 不重开 RTSP/decoder）。
- **Audio**：`setAudioEnabled(boolean)`。
- **Hardware/Transport/Latency**：`setHardwareDecodeEnabled` / `setHardwareRenderMode` / `setRtspTransport` / `setLatencyMode` / `setPlayerOption` / `setReconnectOptions`。
- **Thermal**：`setThermalEnabled` / `setThermalPalette` / `setThermalAgcEnabled` / `setThermalGamma` / `setThermalWindow`（薄 wrapper）。
- **Recording/Snapshot/Stats**：`startRecord` / `startSegmentRecord` / `startRecordWithConfig` / `stopRecord` / `getRecordState` / `takeSnapshot` / `getState` / `getStats` / `getReconnectState` / `getLatencyConfig`。
- **Listener Bridge**：`public interface Listener { void onPlayerEvent(String event, String eventJson); }`，内部 `FFmpegNative.PlayerEventListener` 转发（`handle` 校验 + `lock` 保护 `externalListener`）。
- **Javadoc**：说明 lifecycle、Surface 归属、release 要求、Audio 独立性、线程/回调约束。

## Native Handle Ownership
`FFmpegPlayer` 为 `nativeHandle` 唯一 Java owner。`release()` 内 `synchronized(lock)` 置 `released=true` 并清零 `nativeHandle`，后续所有实例方法经 `lock` 检查 `released`/`nativeHandle==0` 后返回 `{"success":false,"errorMessage":"player released"}`，不触 stale handle。

## Audio Sink Ownership
`LiveAudioPcmSink` 由 `FFmpegPlayer` 构造时创建并通过 `setAudioCallback` 注册，`release()` 时 `setAudioCallback(null)` 清理。Activity 不再 `new LiveAudioPcmSink` 也不再直接 `setAudioCallback`。

## Surface Lifecycle
`setSurface`/`clearSurface` 透传已有安全实现（Fix 2 EGL/render-owner-thread 未改）。`surfaceDestroyed` 仅 `clearSurface`，不等同 `release`。

## Player Lifecycle
`prepare`/`start` 保持现有行为（Fix 3），Facade 不组合新 reconnect。`stop`/`release` 经 `lock` 幂等，`release` 后调用不 UAF。`onDestroy` 改为 `takePlayer()` + `p.release()`。

## Listener Bridge
`FFmpegPlayer.Listener` 内部桥接 `FFmpegNative.PlayerEventListener`（`handle` 校验在 Facade 内部完成，`externalListener` 复制于锁外回调）。`OesFrameListener` 仍为 experimental，未提升为主线。

## Thermal / Recording / Snapshot / Stats API
均为薄 wrapper，直接透传 `FFmpegNative` 对应方法，语义不变（Recording=compressed remux, Snapshot=Fix5 routing, Stats=冻结语义）。

## MediaPlayerActivity Migration
`app/src/main/java/com/example/motro/MediaPlayerActivity.java` 完成迁移：
- `import FFmpegPlayer`（移除 `LiveAudioPcmSink` 直接 import，保留 `FFmpegNative` 仅用于静态 `getFFmpegVersion/probe/runDebugCommand/THERMAL_PALETTE_*`）。
- 字段：`long playerHandle + LiveAudioPcmSink audioPcmSink` → `FFmpegPlayer player` + `FFmpegPlayer lastPlaybackInfoPlayer`；`PlayerEventListener` → `FFmpegPlayer.Listener`。
- 方法：`ensurePlayer()`/`getPlayer()`/`takePlayer()`/`requirePlayer()` 重构；`bindSurfaceIfReady`/`applyAudioOption`/`applyReconnectOptions`/`applyRtspTransport`/`applyLatencyMode`/`applyDecodeModeOption`/`applyThermalOptionsToPlayer`/`takePlayerSnapshotCompat`/`updatePlaybackInfoAsync`/`bindSurfaceForExistingPlayer`/`onDestroy` 等全部改为 `FFmpegPlayer` 参数与 `player.xxx()` 调用。
- `runNative` 中所有 `FFmpegNative.xxx(handle, ...)` 主线调用已替换为 `player.xxx(...)`；`handle` 裸露与 `LiveAudioPcmSink` 直接管理已消除。

直接 `FFmpegNative` 残留仅静态：`THERMAL_PALETTE_*` 常量、`getFFmpegVersion`/`getFFmpegBuildConfig`/`getAvailableDecoders`/`getMediaCodecInfo`/`probe`/`runDebugCommand`。

## Legacy Bridge Visibility
`FFmpegNative` / `LiveAudioPcmSink` 保持 `public`（`PUBLIC_LEGACY_BRIDGE`）。MediaPlayerActivity 已不再直接使用主线 handle API，但 `FFmpegNative` 仍需为 JNI（`FindClass`）、静态工具及潜在测试保持 public。后续可收紧。

## JNI Contract
未修改：`FindClass "com/example/motro/ffmpeg/FFmpegNative"`、`OesFrameListener`、`onPlayerEvent`、`onAudioPcm`/`onAudioControl`/`getPlaybackHeadFrames`、`Java_com_*` 均不变；`package com.example.motro.ffmpeg` 冻结。

## Build
- `git diff --check`：`CHECK PASS`
- `:ffmpegplayer:assembleDebug`：`BUILD SUCCESSFUL`（含 `FFmpegPlayer` 编译）
- `:ffmpegplayer:assembleRelease`：`BUILD SUCCESSFUL`
- `:app:assembleDebug`：`BUILD SUCCESSFUL`（`app` 通过 `FFmpegPlayer` 编译，无 `handle` 残留）
- AAR 含 `FFmpegPlayer.class`（`classes.jar` 验证）+ `libnative-ffmpeg.so` + FFmpeg .so；无 duplicate class/native。

## Runtime Smoke
`NOT_EXECUTED`（无 adb/RTSP 环境；未伪造）

## Remaining Issues
- `FFmpegNative` 仍为 `PUBLIC_LEGACY_BRIDGE`（静态工具与 JNI 要求，L5 再收紧）。
- `THERMAL_PALETTE_*` 常量仍经 `FFmpegNative` 访问（可由 `FFmpegPlayer` 转发，L4 未做）。

## Slice L4 Freeze
**YES** — Facade 完成、handle/sink 被内部管理、Activity 不再直接使用主线 `FFmpegNative`、Surface/lifecycle 行为不变、Audio/Video/Thermal/Recording 行为不变、JNI/package 未变、三项构建 PASS。

---
**Answers:**
1. Public primary API 是什么？ **FFmpegPlayer**
2. Consumer 是否需要 nativeHandle？ **NO**
3. MediaPlayerActivity 是否直接调用 FFmpegNative？ **NO**（仅静态 `getFFmpegVersion/probe/THERMAL_PALETTE_*` 保留）
4. Consumer 是否需要 LiveAudioPcmSink？ **NO**
5. FFmpegPlayer 是否拥有 native lifecycle？ **YES**
6. FFmpegPlayer 是否管理 audio sink？ **YES**
7. Surface detach 是否仍不等于 Player release？ **YES**
8. Prepare/Start 语义是否改变？ **NO**
9. Recording semantics 是否改变？ **NO**
10. Stats semantics 是否改变？ **NO**
11. JNI class/signature 是否改变？ **NO**
12. Native algorithms 是否改变？ **NO**
13. FFmpegNative visibility 最终是什么？ **PUBLIC_LEGACY_BRIDGE**
