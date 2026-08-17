# Problem

The JNI layer exposed a `NativePlayer *` as a Java `long`. Handle validation only proved that the pointer was present in a global active-player set at the instant of lookup. It did not keep the object alive after the lookup lock was released. A concurrent Release could therefore remove and delete the player while another JNI call was still using the returned raw pointer.

This was a player-object lifetime race, independent of the internal method-level mutexes in `NativePlayer`.

# Original Handle Model

Before Fix 4, `nativeCreatePlayer()` allocated a `NativePlayer` and returned:

```cpp
static_cast<jlong>(reinterpret_cast<intptr_t>(player))
```

The original handle model was therefore **POINTER**. The Java-visible value was the native address, not a logical identifier.

`getPlayer()` converted the `jlong` back to `NativePlayer *`, checked `g_active_players` while holding `g_player_mutex`, and returned the raw pointer after unlocking.

Answer 1: Yes, before Fix 4 the `jlong` was a `NativePlayer *` address.

Answer 2: Yes, before Fix 4 `getPlayer()` returned a raw pointer whose registry lock had already been released.

# Original Race

A representative failing interleaving was:

1. JNI thread A called Stats, Thermal, Surface, Prepare, Start, Stop, Recording, or another handle-based API.
2. A locked `g_player_mutex`, found the pointer in `g_active_players`, unlocked, and retained a raw `NativePlayer *`.
3. JNI thread B called Release, removed the pointer from `g_active_players`, unlocked, called `NativePlayer::release()`, and deleted the object.
4. A called a method on the freed pointer or returned through code that still referenced it.

The released-address set made duplicate Release recognizable but did not protect a player already borrowed by another thread.

Answer 3: Yes, Release could delete a player that another JNI call had already validated and was still using.

# Root Cause

Registry membership and object lifetime were separate facts. The original lookup performed a check but did not acquire ownership, a reference, or an active-operation lease. Unlocking the registry mutex ended the only synchronization relationship with Release.

The pointer-valued handle also allowed allocator address reuse: an old Java handle could become numerically equal to a later player address.

# New Handle Model

The final handle model is **OPAQUE_ID**. A process-local `std::atomic<int64_t>` allocates positive, monotonically increasing logical IDs. The ID is the only value returned to Java, emitted in event payloads, or installed in the OES frame listener.

`NativePlayer` receives the logical ID in its constructor and stores it as an immutable `int64_t`. No player address is converted to or from a `jlong`.

Answer 4: The final handle is an opaque logical ID, not a pointer.

# Player Registry

The registry is:

```cpp
std::unordered_map<jlong, std::shared_ptr<PlayerEntry>>
```

It is protected by `g_player_registry_mutex`. Each `PlayerEntry` contains:

- the immutable opaque handle;
- `std::unique_ptr<NativePlayer>` as the sole player owner;
- an entry-local mutex and condition variable;
- `closing`;
- `activeOperations`.

The map owns a shared entry while the player is discoverable. Each accepted JNI operation holds another `shared_ptr<PlayerEntry>`, so erasing the map entry cannot invalidate the operation's bookkeeping object.

Answer 5: The registry is a map from opaque `jlong` ID to a shared `PlayerEntry`; the entry uniquely owns the `NativePlayer` and tracks closing/in-flight state.

# Player Entry Lifetime

The `NativePlayer` remains owned by its `PlayerEntry` until all accepted operations have drained. The entry can outlive registry removal because operation guards and Release hold `shared_ptr` references.

Only Release resets the `unique_ptr`, and only after `activeOperations == 0` and `NativePlayer::release()` completes. There is no timeout path that deletes a still-active player.

# Operation Guard

Every ordinary JNI entry that accepts a player handle now calls `acquirePlayer()` and retains a move-only `PlayerOperationGuard` for the complete native call. This includes player state, prepare/start/pause/stop, surface, callbacks, reconnect, RTSP options, latency/options, hardware mode, stats, snapshot, recording, thermal controls, and the OES frame-available callback. The compatibility RTSP setter delegates to the guarded setter.

Acquisition locks the registry and then the entry, rejects a missing or closing entry, increments `activeOperations`, and returns a guard holding the shared entry. The locks are released before any `NativePlayer` API is called. The guard destructor decrements the entry counter and notifies Release when it reaches zero.

Answer 6: The guard prevents UAF by taking a shared entry reference and incrementing the in-flight count before exposing the player; Release cannot destroy the player until that count returns to zero.

# Release Sequence

Release is intentionally not an ordinary guarded operation. Its sequence is:

1. Lock the global registry.
2. Find the opaque handle.
3. While following registry-to-entry lock order, lock the entry and set `closing = true`.
4. Erase the handle from the registry so no later lookup can succeed.
5. Release the global registry lock.
6. Wait on the entry condition variable for `activeOperations == 0`.
7. Call `NativePlayer::release()`.
8. `NativePlayer::release()` stops and joins playback, stops recording, releases decoder/renderers/callback references, and completes.
9. Reset the entry's `unique_ptr<NativePlayer>`.

Answer 7: Release prevents new operations by setting `closing` while protected by the registry/entry locks and removing the ID from the registry before it waits.

Answer 10: `NativePlayer::release()` runs only after the entry is undiscoverable and all accepted JNI operations have drained.

# Active Operation Drain

Release uses an unbounded predicate wait on the entry-local condition variable. Guard destruction decrements the counter under the entry mutex and notifies when the counter reaches zero. There is no timeout, force-delete, or active-player deletion fallback.

The direct stress test held an operation guard, started Release on another thread, confirmed Release had not completed, confirmed new acquisitions were rejected, then dropped the guard. Device logs showed Release waiting on positive counts and completing only after the guard drain.

Answer 8: Release waits on the entry-local condition variable until `activeOperations` becomes zero.

# Lock Ordering

The only nested order is:

```text
global registry mutex -> entry lifetime mutex
```

Operation acquisition releases both locks before invoking a Player API. Release releases the global registry mutex before its condition-variable wait and holds neither registry nor entry lifetime lock while invoking `NativePlayer::release()`.

No global registry mutex is held across Player API calls, Java callbacks, playback-thread join, recorder shutdown, renderer teardown, or the active-operation wait.

Answer 9: **NO**, Release does not hold the global registry mutex while waiting.

# Duplicate Release

The first Release marks closing and erases the ID. A duplicate Release sees an ID that was previously issued but is no longer in the registry and returns a successful `player already released` result. If two Release calls race, the second may return while the first is draining, but it cannot access or destroy the player and therefore remains memory-safe.

Direct test result: **PASS**.

Answer 12: Duplicate Release is idempotent and safe; it does not delete twice or dereference stale memory.

# Stale Handle

A previously issued but erased ID is reported as already released and cannot acquire a guard. Zero is rejected as a zero handle. A positive ID outside the issued range is rejected as invalid.

Direct stale-acquire test result: **PASS**.

Answer 13: A stale released handle is safely rejected and never returns a player pointer.

# ABA Risk

Player addresses are no longer handles. IDs monotonically increase and are not reused during the process lifetime. Allocator reuse of a `NativePlayer` address therefore cannot make an old handle refer to a new player. The 100-cycle test also explicitly acquired a new player after retaining the first released ID and confirmed that the old ID failed while the new ID succeeded.

The practical ABA/pointer-reuse risk is **ELIMINATED** for the process lifetime. Exhausting the positive 63-bit handle space is treated as creation failure and is not a realistic runtime condition.

Answer 14: **NO**, an old handle cannot target a newly allocated player.

# Java Handle Invalidation

`MediaPlayerActivity` already centralizes handle access through `handleLock`. `takePlayerHandle()` copies the current value and sets `playerHandle = 0` inside the synchronized block. Both the Release button and `onDestroy()` call `takePlayerHandle()` before scheduling/calling native Release. All reads and writes use the same monitor, providing visibility across UI and worker threads.

This means Java stops publishing the active handle before native release begins. It also permits a later Create to receive a distinct opaque ID without aliasing the old player.

Answer 15: **YES**, Java invalidates the active handle at the beginning of the release flow, before the native Release call.

# Callback Lifetime Audit

Player event callback payloads and callback arguments now use `logicalHandle_`, never `this`. The OES `SurfaceTexture` frame listener is also created with the 64-bit logical ID; a late frame callback re-enters the guarded registry lookup and is ignored after registry removal.

The player event listener path creates a JNI local reference from the stored GlobalRef under its callback mutex, releases that mutex before `CallVoidMethod`, checks/clears Java exceptions, and deletes the local reference. Release first stops and joins playback, then deletes stored callback GlobalRefs as part of teardown. The Activity callback posts UI work to the main thread and rechecks that the callback handle still equals the synchronized active handle before applying it.

No registry or entry lifetime lock is held across a Java callback.

Answer 16: **YES** for the audited project callback paths: callback identity is logical, playback is joined before GlobalRef teardown, and stale posted callbacks are filtered by the Java active handle.

# Playback Thread Lifetime

The playback loop is owned by `NativePlayer`. `NativePlayer::release()` calls `stop()`, which signals stop and joins the playback thread before the object is destroyed. The active hardware, software, reconnect, and recording Release runs all logged `playback thread ended` before `release completed`.

Answer 11: **YES**, the playback thread is stopped and joined before destruction.

# Surface / Fix 2 Interaction

Fix 4 does not redesign the Surface/EGL lifecycle. Surface attach, clear, and OES frame notifications now obtain operation guards before using the player. Release removes the handle first, drains an in-flight Surface call, and only then tears down player renderers.

Fresh device coverage included:

- 20 Create -> real Surface bind -> Release UI cycles;
- hardware NV12 GL active-render Release with a Surface attached;
- software YUV GL active-render Release with a Surface attached;
- 1280x720 -> 192x256 decoded-format transitions on both hardware and software paths.

No `EGL_BAD_ACCESS`, guard imbalance, native crash, or registry residue was observed. Fix 2 regression status: **PASS** for the exercised dynamic-format and Surface-bound release paths.

# Prepare-Start / Fix 3 Interaction

Fix 4 adds only the outer JNI lifetime lease; it does not change prepare/start ownership of FFmpeg input or decoder sessions.

Fresh hardware evidence showed prepare counts `inputOpenCount=1`, `decoderOpenCount=1`, `hardwareDecoderOpenCount=1`; Start reported `reused=1` and rendered the first frame without reopening. Software stats also reported `realtimeStartInputReuseCount=1`.

Fix 3 regression status: **PASS** for the exercised HTTP realtime source on both hardware and software paths.

# Thermal / Fix 1 Interaction

Thermal JNI setters are guarded exactly like Stats and Surface. Twenty concurrent-release cycles ran two Stats and two Thermal operations per player while Release waited on all four guards. The native lifecycle stress passed without crash or counter imbalance.

Fix 4 does not change thermal shaders, palettes, AGC, gamma, or windowing. However, the full Fix 1 Ironbow/AGC true-stream visual/runtime matrix was not replayed in this session. Fix 1 full regression status is therefore **NOT_TESTED**, even though Thermal + Release lifetime stress is **PASS**.

# Recording Interaction

Recording start/stop/state APIs all use operation guards. A fresh software playback run started fragmented MP4 remux recording, crossed reconnect while keeping the recorder active, then invoked Release while recording. Release joined playback, automatically stopped the recorder, wrote the trailer, closed the segment, released recorder resources, and only then destroyed the player.

Device evidence: one completed video-only segment with 60 packets; no native crash. Recording + Release status: **PASS** for the exercised MP4 remux path.

# Reconnect Interaction

Reconnect configuration/state APIs use guards. Reconnect itself runs in the owned playback thread, so Release signals stop and joins that thread before destruction. Both hardware and software sessions were released during the EOF reconnect sequence; event callbacks carried the opaque ID and stopped after release.

Reconnect + Release status: **PASS** for the exercised HTTP EOF/reconnect path. A real RTSP server was not available, so protocol-specific RTSP disconnect behavior was not freshly verified.

# Stress Tests

Device: connected arm64 Android target `Bengal_for_arm64`.

Native direct test command: `-player-lifetime-stress`.

Exact result:

```json
{"success":true,"createReleaseCycles":100,"concurrentReleaseCycles":20,"uniqueHandles":true,"duplicateReleaseSafe":true,"staleHandleSafe":true,"oldHandleCannotTargetNewPlayer":true,"releaseWaitedForActiveOperation":true,"closingRejectedNewOperations":true,"concurrentStatsThermalReleaseSafe":true,"minimumReleaseWaitMs":20,"activePlayerCount":0}
```

Coverage and results:

| Scenario | Result | Evidence |
| --- | --- | --- |
| Create/Release | PASS | 100 native direct cycles plus 20 Surface-bound UI cycles |
| Duplicate Release | PASS | second Release returned success; no double delete |
| Stale Handle | PASS | post-release acquisition rejected |
| Old Handle vs New Player | PASS | new ID differed; old acquisition failed |
| Held Operation vs Release | PASS | Release remained blocked until guard drop |
| Closing vs New Operation | PASS | acquisition rejected after registry removal |
| Stats + Release | PASS | 20 cycles, two Stats guards per cycle |
| Thermal + Release | PASS | 20 cycles, two Thermal guards per cycle |
| Surface + Release | PASS | bound lifecycle cycles and active-render Release on both renderers |
| Reconnect + Release | PASS | hardware and software EOF reconnect release |
| Recording + Release | PASS | active fragmented MP4 recorder finalized during Release |
| Registry leak | NOT_OBSERVED | final `activePlayerCount=0`; release logs also reported zero |
| Native crash | NOT_OBSERVED | no Fatal signal, SIGSEGV, SIGABRT, FORTIFY, or JNI DETECTED ERROR |

# Build

Command:

```text
.\gradlew.bat :app:assembleDebug
```

Result: **PASSED** after the registry/guard implementation and again after the direct stress hook. CMake built both configured Android ABIs. The only output of note was the existing KAPT unrecognized-options warning.

# Runtime Verification

Runtime verification was **EXECUTED** on the connected device.

Fresh hardware path:

- decoder: `hevc_mediacodec`;
- render mode: `mediacodec_nv12_gl`;
- effective renderer: `nv12_gl`;
- prepare/start session reuse: observed;
- dynamic resolution: 1280x720 -> 192x256 observed;
- Release during reconnect: clean playback-thread exit and registry count zero.

Fresh software path:

- decoder: `hevc`;
- render mode: `software_yuv_gl`;
- effective renderer: `yuv_gl`;
- dynamic resolution: 1280x720 -> 192x256 observed;
- Release during reconnect: clean playback-thread exit and registry count zero.

Fresh recording path:

- fragmented MP4 remux started;
- reconnect occurred while recorder remained active;
- Release stopped recorder, wrote trailer, closed segment, and destroyed player cleanly.

No native/JNI/EGL failure signature was observed in the filtered device logs.

# Remaining Issues

- Full Fix 1 Ironbow/AGC/gamma/window visual and runtime regression was not executed in this session.
- A true RTSP endpoint was unavailable; reconnect evidence used an HTTP MPEG-TS source reaching EOF and reconnecting.
- The stress hook exercises deterministic native JNI-boundary lifetime behavior but is a debug command in the sample Activity rather than an instrumentation-test target; this project currently has no Android instrumentation-test dependency setup.
- Opaque IDs are process-local. This is intentional; Java handles from a dead process cannot be reused in a new native process.

Answer 17: Fix 2 and Fix 3 have fresh passing runtime evidence, and code review shows Fix 1 algorithms were not changed. Full Fix 1 runtime preservation was not established, so the combined Fix 1/2/3 hard gate is not fully satisfied.

# Fix Runtime Verified: YES/NO

**NO**.

The lifetime fix itself passed deterministic direct stress and the exercised device paths. The hard gate requires all Fix 1/2/3 regressions and real playback interactions to be fully covered; the full Fix 1 runtime matrix and true RTSP-specific run were not executed, so `YES` would overstate the evidence.
