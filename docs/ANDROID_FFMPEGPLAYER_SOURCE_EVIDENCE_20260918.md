# AndroidFFmpegPlayer 源码证据索引

对应源码：用户本次上传 ZIP，Git HEAD `049e39b729d70e993846fea79c4d489891d72508`。
下文路径相对于源码根目录；`L` 后编号为上传版本的物理行号。片段只用于支持审阅结论，不代表该模块的完整实现。
测试观测日志另见交付包 validation/。本索引没有对生产源码做修改。

## E01｜Module / Gradle / Native build

### `settings.gradle:1–36`

```text
L1: pluginManagement {
L2:     repositories {
L3:         maven { url 'https://maven.aliyun.com/repository/public' }
L4:         maven { url 'https://maven.aliyun.com/repository/google' }
L5:         maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
L6:         gradlePluginPortal()
L7:         google()
L8:         mavenCentral()
L9:         maven { url 'https://jitpack.io' }      //增加 jitPack Maven 仓库
L10:     }
L11: }
L12: plugins {
L13:     id 'org.gradle.toolchains.foojay-resolver-convention' version '1.0.0'
L14: }
L15: dependencyResolutionManagement {
L16:     repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
L17:     repositories {
L18:         maven { url 'https://maven.aliyun.com/repository/public' }
L19:         maven { url 'https://maven.aliyun.com/repository/google' }
L20:         maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
L21:         google()
L22:         mavenCentral()
L23:         maven { url "https://jitpack.io" }
L24:         maven {
L25:             allowInsecureProtocol = true
L26:             url 'http://maven.aliyun.com/nexus/content/repositories/releases/'
L27:         }
L28: 
L29:         maven {
L30:             url "https://maven.mozilla.org/maven2/"
L31:         }
L32:     }
L33: }
L34: rootProject.name = "AndroidFFmpegPlayer"
L35: include ':app'
L36: include ':ffmpegplayer'
```

### `app/build.gradle:1–67`

```text
L1: plugins {
L2:     id 'com.android.application'
L3: }
L4: 
L5: android {
L6:     namespace 'com.example.motro'
L7:     compileSdk 36
L8:     defaultConfig {
L9:         applicationId "com.example.motro"
L10:         minSdk 24
L11:         targetSdk 35
L12:         versionCode 6
L13:         versionName "1.0.0.6"
L14:         ndk {
L15:             abiFilters "armeabi-v7a", "arm64-v8a"
L16:         }
L17:     }
L18:     packagingOptions {
L19:         jniLibs {
L20:             useLegacyPackaging true
L21:         }
L22:     }
L23: 
L24:     buildTypes {
L25:         release {
L26:             debuggable false
L27:             minifyEnabled false
L28:             proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
L29: 
L30:         }
L31:         debug {
L32:             debuggable true
L33:             minifyEnabled false
L34:             proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
L35: 
L36:         }
L37:     }
L38: 
L39:     compileOptions {
L40:         sourceCompatibility = JavaVersion.VERSION_17
L41:         targetCompatibility = JavaVersion.VERSION_17
L42:     }
L43: 
L44:     buildFeatures {
L45:         viewBinding true
L46:     }
L47: 
L48:     applicationVariants.all { variant ->
L49:         variant.outputs.all {
L50:             if (variant.buildType.name == "release") {
L51:                 def versionName = variant.versionName ?: "1.0"
L52:                 def timestamp = new Date().format("yyyyMMdd_HHmmss", TimeZone.getTimeZone("GMT+8"))
L53:                 def fileName = "app_release_v${versionName}_${timestamp}.apk"
L54:                 outputFileName = fileName
L55:             }
L56:         }
L57:     }
L58: 
L59: }
L60: 
L61: 
L62: dependencies {
L63:     implementation 'androidx.appcompat:appcompat:1.4.1'
L64:     implementation 'androidx.constraintlayout:constraintlayout:2.1.3'
L65:     implementation project(':ffmpegplayer')
L66:     testImplementation 'junit:junit:4.13.2'
L67: }
```

### `ffmpegplayer/build.gradle:1–41`

```text
L1: plugins {
L2:     id 'com.android.library'
L3: }
L4: 
L5: android {
L6:     namespace 'com.example.motro.ffmpeg'
L7:     compileSdk 35
L8: 
L9:     defaultConfig {
L10:         minSdk 24
L11:         targetSdk 35
L12:         versionCode 1
L13:         versionName "1.0"
L14:         consumerProguardFiles 'consumer-rules.pro'
L15: 
L16: 
L17:         ndk {
L18:             abiFilters "armeabi-v7a", "arm64-v8a"
L19:         }
L20:         testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
L21:     }
L22: 
L23:     buildTypes {
L24:         release {
L25:             minifyEnabled false
L26:             proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
L27:         }
L28: 
L29:         debug {
L30:             minifyEnabled false
L31:             proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
L32:         }
L33:     }
L34: 
L35:     externalNativeBuild {
L36:         cmake {
L37:             path file('src/main/cpp/CMakeLists.txt')
L38:         }
L39:     }
L40: }
L41: 
```

### `gradle/wrapper/gradle-wrapper.properties:1–6`

```text
L1: #Tue Oct 14 16:23:48 CST 2025
L2: distributionBase=GRADLE_USER_HOME
L3: distributionPath=wrapper/dists
L4: distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.14.3-bin.zip
L5: zipStoreBase=GRADLE_USER_HOME
L6: zipStorePath=wrapper/dists
```

### `ffmpegplayer/src/main/cpp/CMakeLists.txt:1–72`

```text
L1: cmake_minimum_required(VERSION 3.22.1)
L2: 
L3: project(native_ffmpeg)
L4: 
L5: set(CMAKE_CXX_STANDARD 17)
L6: set(CMAKE_CXX_STANDARD_REQUIRED ON)
L7: 
L8: set(FFMPEG_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/ffmpeg/include)
L9: set(FFMPEG_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
L10: 
L11: include_directories(${FFMPEG_INCLUDE_DIR})
L12: 
L13: add_library(native-ffmpeg SHARED
L14:         native-ffmpeg-jni.cpp
L15:         native/NativePlayer.cpp
L16:         native/VideoRenderer.cpp
L17:         native/NativeYuvGlRenderer.cpp
L18:         native/NativeOesRenderer.cpp
L19:         native/NativeNv12GlRenderer.cpp
L20:         native/PlayerOptions.cpp
L21:         native/PlayerRemuxRecorder.cpp
L22:         native/SnapshotManager.cpp
L23:         native/ThermalConfig.cpp
L24:         native/ThermalPaletteLut.cpp)
L25: 
L26: # Test-only native commands and their implementations are compiled exclusively
L27: # for the Debug variant. Every other build type fails closed as production.
L28: if(CMAKE_BUILD_TYPE STREQUAL "Debug")
L29:     set(FFMPEGPLAYER_TEST_HOOKS_VALUE 1)
L30: else()
L31:     set(FFMPEGPLAYER_TEST_HOOKS_VALUE 0)
L32: endif()
L33: target_compile_definitions(native-ffmpeg PRIVATE
L34:         FFMPEGPLAYER_ENABLE_TEST_HOOKS=${FFMPEGPLAYER_TEST_HOOKS_VALUE})
L35: message(STATUS
L36:         "FFMPEGPLAYER_ENABLE_TEST_HOOKS=${FFMPEGPLAYER_TEST_HOOKS_VALUE} (CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE})")
L37: 
L38: add_library(avutil SHARED IMPORTED)
L39: set_target_properties(avutil PROPERTIES
L40:         IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavutil.so)
L41: 
L42: add_library(swscale SHARED IMPORTED)
L43: set_target_properties(swscale PROPERTIES
L44:         IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswscale.so)
L45: 
L46: add_library(swresample SHARED IMPORTED)
L47: set_target_properties(swresample PROPERTIES
L48:         IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libswresample.so)
L49: 
L50: add_library(avcodec SHARED IMPORTED)
L51: set_target_properties(avcodec PROPERTIES
L52:         IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavcodec.so)
L53: 
L54: add_library(avformat SHARED IMPORTED)
L55: set_target_properties(avformat PROPERTIES
L56:         IMPORTED_LOCATION ${FFMPEG_LIB_DIR}/libavformat.so)
L57: 
L58: find_library(log-lib log)
L59: find_library(android-lib android)
L60: find_library(egl-lib EGL)
L61: find_library(glesv2-lib GLESv2)
L62: 
L63: target_link_libraries(native-ffmpeg
L64:         avformat
L65:         avcodec
L66:         avutil
L67:         swresample
L68:         swscale
L69:         ${log-lib}
L70:         ${android-lib}
L71:         ${egl-lib}
L72:         ${glesv2-lib})
```

## E02｜Java facade lock, callback, record, release

### `ffmpegplayer/src/main/java/com/example/motro/ffmpeg/FFmpegPlayer.java:37–76`

```text
L37:     public interface Listener {
L38:         void onPlayerEvent(String event, String eventJson);
L39:     }
L40: 
L41:     // 串行保护句柄与释放状态；原生释放在锁外执行，避免等待原生线程时阻塞事件回调。
L42:     private final Object lock = new Object();
L43:     // JNI 注册表中的逻辑句柄；置零后禁止继续向原生层发送播放器操作。
L44:     private long nativeHandle;
L45:     private boolean released;
L46:     private final LiveAudioPcmSink audioSink;
L47:     private Listener externalListener;
L48: 
L49:     // 过滤旧句柄事件，并在退出锁后通知业务监听器；业务侧更新界面时需要切回主线程。
L50:     private final FFmpegNative.PlayerEventListener internalListener = new FFmpegNative.PlayerEventListener() {
L51:         @Override
L52:         public void onPlayerEvent(long handle, String event, String eventJson) {
L53:             Listener l;
L54:             synchronized (lock) {
L55:                 if (released || nativeHandle == 0 || handle != nativeHandle) {
L56:                     return;
L57:                 }
L58:                 l = externalListener;
L59:             }
L60:             if (l != null) {
L61:                 l.onPlayerEvent(event, eventJson);
L62:             }
L63:         }
L64:     };
L65: 
L66:     public FFmpegPlayer() {
L67:         long handle = FFmpegNative.createPlayer();
L68:         LiveAudioPcmSink sink = new LiveAudioPcmSink();
L69:         synchronized (lock) {
L70:             nativeHandle = handle;
L71:             audioSink = sink;
L72:             if (handle != 0) {
L73:                 FFmpegNative.setPlayerEventListener(handle, internalListener);
L74:                 FFmpegNative.setAudioCallback(handle, sink);
L75:             }
L76:         }
```

### `ffmpegplayer/src/main/java/com/example/motro/ffmpeg/FFmpegPlayer.java:112–146`

```text
L112:     // 打开输入并准备解码资源，timeoutMs 单位为毫秒；该调用可能阻塞，应放到工作线程。
L113:     public String prepare(String url, int timeoutMs) {
L114:         synchronized (lock) {
L115:             if (released) return errorReleased();
L116:             if (nativeHandle == 0) return errorNoHandle();
L117:             return FFmpegNative.preparePlayer(nativeHandle, url, timeoutMs);
L118:         }
L119:     }
L120: 
L121:     public String start() {
L122:         synchronized (lock) {
L123:             if (released) return errorReleased();
L124:             if (nativeHandle == 0) return errorNoHandle();
L125:             return FFmpegNative.startPlayer(nativeHandle);
L126:         }
L127:     }
L128: 
L129:     public String pause() {
L130:         synchronized (lock) {
L131:             if (released) return errorReleased();
L132:             if (nativeHandle == 0) return errorNoHandle();
L133:             return FFmpegNative.pausePlayer(nativeHandle);
L134:         }
L135:     }
L136: 
L137:     public String stop() {
L138:         final long handle;
L139:         synchronized (lock) {
L140:             if (released) return errorReleased();
L141:             if (nativeHandle == 0) return errorNoHandle();
L142:             handle = nativeHandle;
L143:         }
L144:         // 原生 stop 会 join 播放线程；锁外调用，避免播放线程投递事件时反向等待 Java 锁。
L145:         return FFmpegNative.stopPlayer(handle);
L146:     }
```

### `ffmpegplayer/src/main/java/com/example/motro/ffmpeg/FFmpegPlayer.java:244–284`

```text
L244:     // 录制启停可能等待磁盘，不得持有事件回调使用的 Java 锁。
L245:     // JNI 的 PlayerOperationGuard 负责调用期间的原生对象寿命。
L246:     public String startRecord(String outputPath) {
L247:         final long handle;
L248:         synchronized (lock) {
L249:             if (released) return errorReleased();
L250:             if (nativeHandle == 0) return errorNoHandle();
L251:             handle = nativeHandle;
L252:         }
L253:         return FFmpegNative.startPlayerRecord(handle, outputPath);
L254:     }
L255: 
L256:     public String startSegmentRecord(String outputPattern, int segmentDurationSec) {
L257:         final long handle;
L258:         synchronized (lock) {
L259:             if (released) return errorReleased();
L260:             if (nativeHandle == 0) return errorNoHandle();
L261:             handle = nativeHandle;
L262:         }
L263:         return FFmpegNative.startPlayerSegmentRecord(handle, outputPattern, segmentDurationSec);
L264:     }
L265: 
L266:     public String startRecordWithConfig(String outputPathOrPattern, String format, int segmentDurationSec) {
L267:         final long handle;
L268:         synchronized (lock) {
L269:             if (released) return errorReleased();
L270:             if (nativeHandle == 0) return errorNoHandle();
L271:             handle = nativeHandle;
L272:         }
L273:         return FFmpegNative.startPlayerRecordWithConfig(handle, outputPathOrPattern, format, segmentDurationSec);
L274:     }
L275: 
L276:     public String stopRecord() {
L277:         final long handle;
L278:         synchronized (lock) {
L279:             if (released) return errorReleased();
L280:             if (nativeHandle == 0) return errorNoHandle();
L281:             handle = nativeHandle;
L282:         }
L283:         return FFmpegNative.stopPlayerRecord(handle);
L284:     }
```

### `ffmpegplayer/src/main/java/com/example/motro/ffmpeg/FFmpegPlayer.java:341–370`

```text
L341:     // 幂等释放：先在 Java 侧作废句柄，再解绑回调并释放原生资源。
L342:     public String release() {
L343:         long handleToRelease;
L344:         synchronized (lock) {
L345:             if (released) {
L346:                 return "{\"success\":true,\"message\":\"player already released\"}";
L347:             }
L348:             released = true;
L349:             handleToRelease = nativeHandle;
L350:             nativeHandle = 0;
L351:             externalListener = null;
L352:         }
L353:         if (handleToRelease != 0) {
L354:             try {
L355:                 FFmpegNative.setPlayerEventListener(handleToRelease, null);
L356:             } catch (Throwable ignored) {
L357:             }
L358:             try {
L359:                 FFmpegNative.setAudioCallback(handleToRelease, null);
L360:             } catch (Throwable ignored) {
L361:             }
L362:             return FFmpegNative.releasePlayer(handleToRelease);
L363:         }
L364:         return "{\"success\":true,\"message\":\"player released\"}";
L365:     }
L366: 
L367:     @Override
L368:     public void close() {
L369:         release();
L370:     }
```

## E03｜JNI active-operation lifetime guard and release

### `ffmpegplayer/src/main/cpp/native-ffmpeg-jni.cpp:43–109`

```text
L43: 
L44: struct PlayerEntry {
L45:     PlayerEntry(jlong handleValue, std::unique_ptr<NativePlayer> playerValue)
L46:             : handle(handleValue), player(std::move(playerValue)) {
L47:     }
L48: 
L49:     const jlong handle;
L50:     std::unique_ptr<NativePlayer> player;
L51:     std::mutex lifetimeMutex;
L52:     // 释放流程通过此条件变量等待 activeOperations 在途调用计数归零，再销毁播放器。
L53:     std::condition_variable lifetimeCv;
L54:     bool closing = false;
L55:     uint32_t activeOperations = 0;
L56: };
L57: 
L58: // Lock order: registry mutex -> entry lifetime mutex. Player API calls and
L59: // release drain waits must hold neither lock.
L60: std::mutex g_player_registry_mutex;
L61: std::unordered_map<jlong, std::shared_ptr<PlayerEntry>> g_player_registry;
L62: 
L63: // 每次 JNI 操作持有的生命周期守卫，析构时归还在途计数并唤醒等待释放的线程。
L64: class PlayerOperationGuard {
L65: public:
L66:     PlayerOperationGuard() = default;
L67:     explicit PlayerOperationGuard(std::shared_ptr<PlayerEntry> entry)
L68:             : entry_(std::move(entry)) {
L69:     }
L70: 
L71:     PlayerOperationGuard(const PlayerOperationGuard &) = delete;
L72:     PlayerOperationGuard &operator=(const PlayerOperationGuard &) = delete;
L73:     PlayerOperationGuard(PlayerOperationGuard &&other) noexcept
L74:             : entry_(std::move(other.entry_)) {
L75:     }
L76:     PlayerOperationGuard &operator=(PlayerOperationGuard &&) = delete;
L77: 
L78:     ~PlayerOperationGuard() {
L79:         if (entry_ == nullptr) {
L80:             return;
L81:         }
L82:         std::shared_ptr<PlayerEntry> entry = std::move(entry_);
L83:         bool drained = false;
L84:         {
L85:             std::lock_guard<std::mutex> lock(entry->lifetimeMutex);
L86:             if (entry->activeOperations == 0) {
L87:                 LOGE("player operation guard imbalance handle=%lld",
L88:                      static_cast<long long>(entry->handle));
L89:                 return;
L90:             }
L91:             --entry->activeOperations;
L92:             drained = entry->activeOperations == 0;
L93:         }
L94:         if (drained) {
L95:             entry->lifetimeCv.notify_all();
L96:         }
L97:     }
L98: 
L99:     explicit operator bool() const {
L100:         return entry_ != nullptr;
L101:     }
L102: 
L103:     NativePlayer *player() const {
L104:         return entry_ == nullptr ? nullptr : entry_->player.get();
L105:     }
L106: 
L107: private:
L108:     std::shared_ptr<PlayerEntry> entry_;
L109: };
```

### `ffmpegplayer/src/main/cpp/native-ffmpeg-jni.cpp:939–987`

```text
L939: std::string releasePlayerEntry(jlong handle) {
L940:     if (handle == 0) {
L941:         return jsonError(-1, "player handle is 0");
L942:     }
L943: 
L944:     std::shared_ptr<PlayerEntry> entry;
L945:     uint32_t activeOperations = 0;
L946:     size_t remainingPlayerCount = 0;
L947:     {
L948:         std::unique_lock<std::mutex> registryLock(g_player_registry_mutex);
L949:         const auto iterator = g_player_registry.find(handle);
L950:         if (iterator == g_player_registry.end()) {
L951:             return wasPlayerHandleIssued(handle)
L952:                    ? jsonSuccess("player already released")
L953:                    : jsonError(-1, "invalid player handle");
L954:         }
L955:         entry = iterator->second;
L956:         {
L957:             std::lock_guard<std::mutex> entryLock(entry->lifetimeMutex);
L958:             entry->closing = true;
L959:             activeOperations = entry->activeOperations;
L960:         }
L961:         g_player_registry.erase(iterator);
L962:         remainingPlayerCount = g_player_registry.size();
L963:     }
L964: 
L965:     LOGI("release begin handle=%lld activeOperations=%u remainingPlayerCount=%zu",
L966:          static_cast<long long>(handle), activeOperations, remainingPlayerCount);
L967:     {
L968:         std::unique_lock<std::mutex> entryLock(entry->lifetimeMutex);
L969:         if (entry->activeOperations > 0) {
L970:             LOGI("release waiting handle=%lld activeOperations=%u",
L971:                  static_cast<long long>(handle), entry->activeOperations);
L972:         }
L973:         entry->lifetimeCv.wait(entryLock, [&entry] {
L974:             return entry->activeOperations == 0;
L975:         });
L976:     }
L977:     LOGI("release operations drained handle=%lld", static_cast<long long>(handle));
L978: 
L979:     const std::string result = entry->player->release();
L980:     entry->player.reset();
L981:     LOGI("release completed handle=%lld remainingPlayerCount=%zu",
L982:          static_cast<long long>(handle), remainingPlayerCount);
L983:     return result;
L984: }
L985: 
L986: jstring nativeReleasePlayer(JNIEnv *env, jclass, jlong handle) {
L987:     return toJString(env, releasePlayerEntry(handle));
```

## E04｜Native prepare implicit stop; stop joins thread

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:1416–1516`

```text
L1416: std::string NativePlayer::prepare(const std::string &url, int timeoutMs) {
L1417:     const int64_t prepareStartUs = steadyNowUs();
L1418:     if (isReleased()) {
L1419:         return jsonError(-1, "player is released");
L1420:     }
L1421:     if (url.empty()) {
L1422:         return jsonError(-1, "url is empty");
L1423:     }
L1424: 
L1425:     stop();
L1426:     stopRequested_.store(false);
L1427:     pauseRequested_.store(false);
L1428:     {
L1429:         std::lock_guard<std::mutex> lock(mutex_);
L1430:         preferUdpTransport_.store(shouldPreferUdpTransport(playerOptions_));
L1431:         syncReconnectPolicyFromOptionsLocked();
L1432:     }
L1433:     transportSwitchRequested_.store(false);
L1434:     resetStats();
L1435:     initialOpenError_.store(0);
L1436:     clearLastFrame();
L1437: 
L1438:     {
L1439:         std::lock_guard<std::mutex> lock(mutex_);
L1440:         state_ = PlayerState::Preparing;
L1441:         url_ = url;
L1442:         timeoutMs_ = std::max(timeoutMs, 1);
L1443:         isRealtimeInput_ = isNetworkUrl(url);
L1444:         sourceType_ = detectSourceType(url);
L1445:         errorMessage_.clear();
L1446:         lastReconnectError_.clear();
L1447:         playerOptions_.requestedDecoderName.clear();
L1448:         playerOptions_.actualDecoderName.clear();
L1449:         playerOptions_.usingHardwareDecoder = false;
L1450:         playerOptions_.hardwareDecodeFallbackUsed = false;
L1451:         playerOptions_.hardwareDecodeError.clear();
L1452:     }
L1453: 
L1454:     LOGI("prepare url=%s timeoutMs=%d realtimeInput=%d", url.c_str(), timeoutMs, isNetworkUrl(url) ? 1 : 0);
L1455: 
L1456:     std::string error;
L1457:     const int result = openInput(url, timeoutMs_, true, error);
L1458:     lastPrepareCostUs_.store(std::max<int64_t>(0, steadyNowUs() - prepareStartUs));
L1459:     if (result < 0) {
L1460:         preparedAtTimeMs_.store(0);
L1461:         if (waitForInitialRtsp404(isRtspSource(sourceType_), result == AVERROR_HTTP_NOT_FOUND
L1462:                                  || containsInsensitive(error, "404") || containsInsensitive(error, "not found"),
L1463:                                  reconnectEnabled_.load(), reconnectOn404_.load(),
L1464:                                  keepWaitingWhenSourceMissing_.load(),
L1465:                                  infiniteReconnect_.load() || reconnectMaxRetryCount_.load() != 0)) {
L1466:             initialOpenError_.store(result);
L1467:             waitingSource_.store(true);
L1468:             setState(PlayerState::WaitingSource, error);
L1469:             notifyPlayerEvent("waiting_source", PlayerState::WaitingSource, 0,
L1470:                               reconnectMaxRetryCount_.load(), 0, result, error);
L1471:             return "{\"success\":true,\"message\":\"source missing; start will retry\","
L1472:                    "\"state\":\"waiting_source\",\"pendingReconnect\":true}";
L1473:         }
L1474:         setState(PlayerState::Error, error);
L1475:         return jsonError(result, error);
L1476:     }
L1477: 
L1478:     preparedAtTimeMs_.store(nowMs());
L1479:     setState(PlayerState::Prepared);
L1480:     LOGI("prepare completed costUs=%lld inputOpenCount=%lld decoderOpenCount=%lld hardwareDecoderOpenCount=%lld",
L1481:          static_cast<long long>(lastPrepareCostUs_.load()),
L1482:          static_cast<long long>(inputOpenCount_.load()),
L1483:          static_cast<long long>(videoDecoderOpenCount_.load()),
L1484:          static_cast<long long>(hardwareDecoderOpenCount_.load()));
L1485: 
L1486:     std::ostringstream out;
L1487:     out << "{\"success\":true,\"message\":\"player prepared\","
L1488:         << "\"videoStreamIndex\":" << videoStreamIndex_ << ","
L1489:         << "\"videoCodec\":\"" << escapeJson(videoCodec_) << "\","
L1490:         << "\"enableHardwareDecode\":" << (playerOptions_.enableHardwareDecode ? "true" : "false") << ","
L1491:         << "\"renderMode\":\"" << renderModeName(playerOptions_.renderMode) << "\","
L1492:         << "\"requestedDecoderName\":\"" << escapeJson(playerOptions_.requestedDecoderName) << "\","
L1493:         << "\"actualDecoderName\":\"" << escapeJson(playerOptions_.actualDecoderName) << "\","
L1494:         << "\"usingHardwareDecoder\":" << (playerOptions_.usingHardwareDecoder ? "true" : "false") << ","
L1495:         << "\"hardwareDecodeFallbackUsed\":" << (playerOptions_.hardwareDecodeFallbackUsed ? "true" : "false") << ","
L1496:         << "\"hardwareDecodeError\":\"" << escapeJson(playerOptions_.hardwareDecodeError) << "\","
L1497:         << "\"sourceHasVideo\":" << (sourceHasVideo_.load() ? "true" : "false") << ","
L1498:         << "\"sourceHasAudio\":" << (sourceHasAudio_.load() ? "true" : "false") << ","
L1499:         << "\"videoStreamIndex\":" << videoStreamIndex_ << ","
L1500:         << "\"audioStreamIndex\":" << audioStreamIndex_ << ","
L1501:         << "\"videoWidth\":" << videoWidth_ << ","
L1502:         << "\"videoHeight\":" << videoHeight_ << ","
L1503:         << "\"fps\":" << fps_ << ","
L1504:         << "\"audioStreamIndex\":" << audioStreamIndex_ << ","
L1505:         << "\"audioCodec\":\"" << escapeJson(audioCodec_) << "\","
L1506:         << "\"reconnectEnabled\":" << (reconnectEnabled_.load() ? "true" : "false") << ","
L1507:         << "\"reconnectMaxRetryCount\":" << reconnectMaxRetryCount_.load() << ","
L1508:         << "\"reconnectRetryDelayMs\":" << reconnectRetryDelayMs_.load() << ","
L1509:         << "\"infiniteReconnect\":" << (infiniteReconnect_.load() ? "true" : "false") << ","
L1510:         << "\"reconnectOnEof\":" << (reconnectOnEof_.load() ? "true" : "false") << ","
L1511:         << "\"reconnectOn404\":" << (reconnectOn404_.load() ? "true" : "false") << ","
L1512:         << "\"keepWaitingWhenSourceMissing\":" << (keepWaitingWhenSourceMissing_.load() ? "true" : "false") << ","
L1513:         << "\"reconnectMaxDelayMs\":" << reconnectMaxDelayMs_.load() << ","
L1514:         << "\"sourceType\":\"" << sourceTypeName(sourceType_) << "\","
L1515:         << "\"latencyMode\":\"" << latencyModeName(playerOptions_.latencyMode) << "\","
L1516:         << "\"rtspTransport\":\"" << rtspTransportName(playerOptions_.rtspTransport) << "\"}";
```

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:1623–1678`

```text
L1623: std::string NativePlayer::stop() {
L1624:     if (isReleased()) {
L1625:         LOGI("stopPlayer ignored: player already released");
L1626:         return jsonError(-1, "player is released");
L1627:     }
L1628: 
L1629:     const bool shouldJoin = playbackThread_.joinable();
L1630:     {
L1631:         std::lock_guard<std::mutex> lock(mutex_);
L1632:         if (state_ != PlayerState::Released) {
L1633:             state_ = PlayerState::Stopping;
L1634:         }
L1635:         stopRequested_.store(true);
L1636:         pauseRequested_.store(false);
L1637:     }
L1638: 
L1639:     // Block/flush output before waiting for the producer thread. Java writes
L1640:     // are non-blocking, so worker join is deterministic and never depends on
L1641:     // AudioTrack buffer drain. This also covers IDLE/PREPARED Release paths.
L1642:     flushAudioPcmForDiscontinuity();
L1643:     audioPcmQueue_.requestStop();
L1644:     stopAudioOutputWorker();
L1645: 
L1646:     if (shouldJoin) {
L1647:         playbackThread_.join();
L1648:     }
L1649:     // 重连可能在首次 audio join 与 playback join 之间完成启动；再次回收，避免遗留线程。
L1650:     audioPcmQueue_.requestStop();
L1651:     stopAudioOutputWorker();
L1652:     // A producer already inside conversion may observe stop after the first
L1653:     // flush. Clear once more after join; no producer remains at this point.
L1654:     audioPcmQueue_.flush();
L1655:     reconnecting_.store(false);
L1656:     waitingSource_.store(false);
L1657:     setRendererFallbackReason(rendererState_, 0);
L1658:     audioResumeDiscontinuityRequested_.store(false);
L1659:     audioFlushRequested_.store(false);
L1660: 
L1661:     if (remuxRecorder_.isRecording()) {
L1662:         LOGI("stopPlayer auto stop active recorder");
L1663:     }
L1664:     remuxRecorder_.clearInput();
L1665:     remuxRecorder_.stop();
L1666: 
L1667:     releaseFfmpegResources();
L1668:     oesRenderer_.release();
L1669:     oesFramePending_.store(false);
L1670:     {
L1671:         std::lock_guard<std::mutex> lock(mutex_);
L1672:         if (state_ != PlayerState::Released) {
L1673:             state_ = PlayerState::Stopped;
L1674:         }
L1675:     }
L1676:     LOGI("stopPlayer player=%p", this);
L1677:     return jsonSuccess("player stopped");
L1678: }
```

## E05｜Synchronous native event and raw URL exposure

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:4598–4685`

```text
L4598: void NativePlayer::notifyPlayerEvent(const std::string &eventName,
L4599:                                      PlayerState state,
L4600:                                      int64_t attempt,
L4601:                                      int maxRetry,
L4602:                                      int delayMs,
L4603:                                      int errorCode,
L4604:                                      const std::string &errorMessage) {
L4605:     bool attached = false;
L4606:     JNIEnv *env = getJniEnvForCurrentThread(attached);
L4607:     if (env == nullptr) {
L4608:         LOGE("notifyPlayerEvent failed: JNIEnv unavailable event=%s", eventName.c_str());
L4609:         return;
L4610:     }
L4611: 
L4612:     jobject listenerLocalRef = nullptr;
L4613:     {
L4614:         std::lock_guard<std::mutex> lock(eventListenerMutex_);
L4615:         if (playerEventListenerGlobalRef_ != nullptr) {
L4616:             listenerLocalRef = env->NewLocalRef(playerEventListenerGlobalRef_);
L4617:         }
L4618:     }
L4619:     if (listenerLocalRef == nullptr) {
L4620:         detachCurrentThreadIfNeeded(attached);
L4621:         return;
L4622:     }
L4623: 
L4624:     jclass listenerClass = env->GetObjectClass(listenerLocalRef);
L4625:     jmethodID method = listenerClass == nullptr
L4626:                        ? nullptr
L4627:                        : env->GetMethodID(listenerClass, "onPlayerEvent", "(JLjava/lang/String;Ljava/lang/String;)V");
L4628:     if (method == nullptr) {
L4629:         LOGE("notifyPlayerEvent failed: onPlayerEvent method not found");
L4630:         if (env->ExceptionCheck()) {
L4631:             env->ExceptionClear();
L4632:         }
L4633:         if (listenerClass != nullptr) {
L4634:             env->DeleteLocalRef(listenerClass);
L4635:         }
L4636:         env->DeleteLocalRef(listenerLocalRef);
L4637:         detachCurrentThreadIfNeeded(attached);
L4638:         return;
L4639:     }
L4640: 
L4641:     std::string url;
L4642:     {
L4643:         std::lock_guard<std::mutex> lock(mutex_);
L4644:         url = url_;
L4645:     }
L4646:     std::ostringstream payload;
L4647:     payload << "{\"success\":true,"
L4648:             << "\"event\":\"" << escapeJson(eventName) << "\","
L4649:             << "\"playerState\":\"" << playerStateName(state) << "\","
L4650:             << "\"state\":\"" << stateName(state) << "\","
L4651:             << "\"handle\":" << static_cast<long long>(logicalHandle_) << ","
L4652:             << "\"url\":\"" << escapeJson(url) << "\","
L4653:             << "\"reconnecting\":" << (reconnecting_.load() ? "true" : "false") << ","
L4654:             << "\"waitingSource\":" << (waitingSource_.load() ? "true" : "false") << ","
L4655:             << "\"attempt\":" << attempt << ","
L4656:             << "\"maxRetry\":" << maxRetry << ","
L4657:             << "\"delayMs\":" << delayMs << ","
L4658:             << "\"errorCode\":" << errorCode << ","
L4659:             << "\"errorMessage\":\"" << escapeJson(errorMessage) << "\","
L4660:             << "\"lastDisconnectTimeMs\":" << lastDisconnectTimeMs_.load() << ","
L4661:             << "\"lastReconnectSuccessTimeMs\":" << lastReconnectSuccessTimeMs_.load()
L4662:             << "}";
L4663: 
L4664:     jstring eventString = env->NewStringUTF(eventName.c_str());
L4665:     jstring payloadString = env->NewStringUTF(payload.str().c_str());
L4666:     if (eventString != nullptr && payloadString != nullptr) {
L4667:         env->CallVoidMethod(listenerLocalRef,
L4668:                             method,
L4669:                             static_cast<jlong>(logicalHandle_),
L4670:                             eventString,
L4671:                             payloadString);
L4672:     }
L4673:     if (env->ExceptionCheck()) {
L4674:         LOGE("notifyPlayerEvent Java callback threw event=%s", eventName.c_str());
L4675:         env->ExceptionClear();
L4676:     }
L4677:     if (eventString != nullptr) {
L4678:         env->DeleteLocalRef(eventString);
L4679:     }
L4680:     if (payloadString != nullptr) {
L4681:         env->DeleteLocalRef(payloadString);
L4682:     }
L4683:     env->DeleteLocalRef(listenerClass);
L4684:     env->DeleteLocalRef(listenerLocalRef);
L4685:     detachCurrentThreadIfNeeded(attached);
```

### `app/src/main/java/com/example/motro/MediaPlayerActivity.java:110–128`

```text
L110:     private boolean thermalUiUpdating;
L111:     private volatile String currentRenderMode = "";
L112:     private String intentRenderMode = "";
L113: 
L114:     private final FFmpegPlayer.Listener playerEventListener = (event, eventJson) -> {
L115:         mainHandler.post(() -> {
L116:             FFmpegPlayer current;
L117:             synchronized (handleLock) {
L118:                 current = player;
L119:             }
L120:             if (destroyed || current == null) {
L121:                 return;
L122:             }
L123:             lastPlayerEventText = event;
L124:             Log.d(TAG_EVENT, "event=" + event + "\n" + eventJson);
L125:         });
L126:     };
L127: 
L128:     @Override
```

## E06｜Recorder owned input snapshot and queue

### `ffmpegplayer/src/main/cpp/native/PlayerRemuxRecorder.h:45–82`

```text
L45: 
L46:     std::string start(const std::string &outputPath);
L47:     std::string startSegmented(const std::string &outputPattern, int segmentDurationSec);
L48:     std::string startWithConfig(const RemuxRecordConfig &config);
L49:     // 在输入所属线程复制流参数；快照不含 AVIO，也不借用输入上下文的内存。
L50:     void setInput(AVFormatContext *inputFmtCtx);
L51:     void clearInput();
L52:     // 只引用压缩包并入队；不得获取生命周期锁或执行任何输出文件操作。
L53:     void onPacket(const AVPacket *packet);
L54:     static constexpr size_t kMaxQueuePackets = 512;
L55:     static constexpr size_t kMaxQueueBytes = 16 * 1024 * 1024;
L56:     std::string stop();
L57:     std::string getState();
L58:     void setAudioPlaybackState(bool enabled);
L59:     bool isRecording() const;
L60:     int64_t getVideoPacketCount() const;
L61:     int64_t getAudioPacketCount() const;
L62:     int64_t getCompletedSegmentCount() const;
L63:     void release();
L64: 
L65: private:
L66:     struct PacketDeleter { void operator()(AVPacket *packet) const; };
L67:     using InputSnapshot = std::shared_ptr<AVFormatContext>;
L68:     struct QueuedPacket {
L69:         std::unique_ptr<AVPacket, PacketDeleter> packet;
L70:         InputSnapshot input;
L71:         size_t bytes;
L72:     };
L73:     enum class QueueFailure { None, Overflow, Allocation };
L74:     void workerLoop(InputSnapshot input, RemuxRecordConfig config);
L75:     void finishWorker();
L76:     void stopAndJoin();
L77:     void publishSnapshot();
L78:     bool adoptInput(const InputSnapshot &input);
L79:     // 保留原 Locked 命名；这些封装方法现在只由唯一的 Remux Worker 调用。
L80:     // mutex_ 只保护队列和已发布快照，绝不能跨磁盘 I/O 持有。
L81:     std::string startLocked(AVFormatContext *inputFmtCtx,
L82:                             const RemuxRecordConfig &config);
```

### `ffmpegplayer/src/main/cpp/native/PlayerRemuxRecorder.cpp:264–385`

```text
L264: void PlayerRemuxRecorder::setInput(AVFormatContext *inputFmtCtx) {
L265:     InputSnapshot snapshot(avformat_alloc_context(), [](AVFormatContext *ctx) {
L266:         avformat_free_context(ctx);
L267:     });
L268:     bool valid = snapshot != nullptr && inputFmtCtx != nullptr;
L269:     if (valid) {
L270:         for (unsigned i = 0; i < inputFmtCtx->nb_streams; ++i) {
L271:             AVStream *source = inputFmtCtx->streams[i];
L272:             AVStream *copy = avformat_new_stream(snapshot.get(), nullptr);
L273:             if (copy == nullptr || source == nullptr || source->codecpar == nullptr
L274:                 || avcodec_parameters_copy(copy->codecpar, source->codecpar) < 0) {
L275:                 valid = false;
L276:                 break;
L277:             }
L278:             copy->time_base = source->time_base;
L279:         }
L280:     }
L281:     std::lock_guard<std::mutex> lock(mutex_);
L282:     input_ = valid ? std::move(snapshot) : InputSnapshot{};
L283:     if (!valid && accepting_.exchange(false)) {
L284:         queueFailure_ = QueueFailure::Allocation;
L285:         publishedState_ = RecorderState::Error;
L286:         queueCv_.notify_one();
L287:     }
L288: }
L289: 
L290: void PlayerRemuxRecorder::clearInput() {
L291:     std::lock_guard<std::mutex> lock(mutex_);
L292:     input_.reset();
L293: }
L294: 
L295: std::string PlayerRemuxRecorder::start(const std::string &outputPath) {
L296:     RemuxRecordConfig config;
L297:     config.outputPathOrPattern = outputPath;
L298:     return startWithConfig(config);
L299: }
L300: 
L301: std::string PlayerRemuxRecorder::startSegmented(const std::string &outputPattern, int segmentDurationSec) {
L302:     RemuxRecordConfig config;
L303:     config.outputPathOrPattern = outputPattern;
L304:     config.segmentMode = true;
L305:     config.segmentDurationSec = segmentDurationSec;
L306:     return startWithConfig(config);
L307: }
L308: 
L309: std::string PlayerRemuxRecorder::startWithConfig(const RemuxRecordConfig &config) {
L310:     std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
L311:     if (released_) return jsonError(-1, "recorder is released");
L312:     if (accepting_.load()) return jsonError(-1, "recorder is already recording");
L313:     // 上一次错误退出也可能留下 joinable 线程；禁止跨会话复用旧队列。
L314:     stopAndJoin();
L315:     std::unique_lock<std::mutex> lock(mutex_);
L316:     if (!input_) return jsonError(-1, "input format context is null; player is not prepared");
L317:     queue_.clear();
L318:     queueBytes_ = 0;
L319:     queueDrops_ = 0;
L320:     queueHighWatermark_ = 0;
L321:     queueBytesHighWatermark_ = 0;
L322:     queueFailure_ = QueueFailure::None;
L323:     stopRequested_ = false;
L324:     startCompleted_ = false;
L325:     packetsWritten_.store(0);
L326:     writeErrors_.store(0);
L327:     publishedState_ = RecorderState::Starting;
L328:     try {
L329:         worker_ = std::thread(&PlayerRemuxRecorder::workerLoop, this, input_, config);
L330:     } catch (const std::system_error &error) {
L331:         publishedState_ = RecorderState::Error;
L332:         return jsonError(-1, error.what());
L333:     }
L334:     // 维持同步 start API：只有调用者等待文件打开；播放线程不获取 lifecycleMutex_。
L335:     startCv_.wait(lock, [this] { return startCompleted_; });
L336:     return startResult_;
L337: }
L338: 
L339: void PlayerRemuxRecorder::onPacket(const AVPacket *packet) {
L340:     if (packet == nullptr || !accepting_.load()) return;
L341:     std::lock_guard<std::mutex> lock(mutex_);
L342:     if (!accepting_.load() || !input_) return;
L343:     if (packet->stream_index < 0 || packet->stream_index >= static_cast<int>(input_->nb_streams)) return;
L344:     const auto type = input_->streams[packet->stream_index]->codecpar->codec_type;
L345:     if (type != AVMEDIA_TYPE_VIDEO && type != AVMEDIA_TYPE_AUDIO) return;
L346: 
L347:     // 计算实际持有的缓冲区和 side data；单个超大包也不得突破队列预算。
L348:     size_t bytes = packet->buf != nullptr ? packet->buf->size : static_cast<size_t>(std::max(packet->size, 0));
L349:     for (int i = 0; i < packet->side_data_elems; ++i) {
L350:         const size_t sideBytes = packet->side_data[i].size;
L351:         if (sideBytes > kMaxQueueBytes || bytes > kMaxQueueBytes - sideBytes) {
L352:             bytes = kMaxQueueBytes + 1;
L353:             break;
L354:         }
L355:         bytes += sideBytes;
L356:     }
L357:     if (queue_.size() >= kMaxQueuePackets || bytes > kMaxQueueBytes
L358:         || queueBytes_ > kMaxQueueBytes - bytes) {
L359:         ++queueDrops_;
L360:         queueFailure_ = QueueFailure::Overflow;
L361:         accepting_.store(false);
L362:         publishedState_ = RecorderState::Error;
L363:         LOGE("recorder queue overflow packets=%zu bytes=%zu; stop recording, keep playback running",
L364:              queue_.size(), queueBytes_);
L365:         queueCv_.notify_one();
L366:         return;
L367:     }
L368:     std::unique_ptr<AVPacket, PacketDeleter> copy(av_packet_clone(packet));
L369:     if (copy) {
L370:         try {
L371:             queue_.push_back({std::move(copy), input_, bytes});
L372:             queueBytes_ += bytes;
L373:             queueHighWatermark_ = std::max(queueHighWatermark_, queue_.size());
L374:             queueBytesHighWatermark_ = std::max(queueBytesHighWatermark_, queueBytes_);
L375:             queueCv_.notify_one();
L376:             return;
L377:         } catch (const std::bad_alloc &) {
L378:             // RAII 释放尚未进入队列的引用。
L379:         }
L380:     }
L381:     ++queueDrops_;
L382:     queueFailure_ = QueueFailure::Allocation;
L383:     accepting_.store(false);
L384:     publishedState_ = RecorderState::Error;
L385:     queueCv_.notify_one();
```

## E07｜Recorder worker, reconnect adoption, stop/join

### `ffmpegplayer/src/main/cpp/native/PlayerRemuxRecorder.cpp:388–526`

```text
L388: bool PlayerRemuxRecorder::adoptInput(const InputSnapshot &input) {
L389:     if (input == workerInput_) return true;
L390:     // 不可将不同编码参数的包写入旧容器；变化仅终止录制，不影响重连播放。
L391:     if (input->nb_streams != workerInput_->nb_streams) {
L392:         setErrorLocked("record input stream layout changed after reconnect");
L393:         return false;
L394:     }
L395:     for (unsigned i = 0; i < input->nb_streams; ++i) {
L396:         const AVCodecParameters *a = workerInput_->streams[i]->codecpar;
L397:         const AVCodecParameters *b = input->streams[i]->codecpar;
L398:         if (a->codec_type != b->codec_type || a->codec_id != b->codec_id
L399:             || a->width != b->width || a->height != b->height || a->sample_rate != b->sample_rate
L400:             || (a->codec_type == AVMEDIA_TYPE_AUDIO
L401:                 && (a->ch_layout.nb_channels != b->ch_layout.nb_channels
L402:                     || (a->ch_layout.nb_channels > 0
L403:                         && av_channel_layout_compare(&a->ch_layout, &b->ch_layout) != 0)))
L404:             || a->extradata_size != b->extradata_size
L405:             || (a->extradata_size > 0 && std::memcmp(a->extradata, b->extradata, a->extradata_size) != 0)) {
L406:             setErrorLocked("record codec parameters changed after reconnect");
L407:             return false;
L408:         }
L409:         if (i < streamMapping_.size() && streamMapping_[i] >= 0) {
L410:             const int outputIndex = streamMapping_[i];
L411:             timestampOffset_[outputIndex] = lastDts_[outputIndex] == AV_NOPTS_VALUE ? 0 : lastDts_[outputIndex] + 1;
L412:         }
L413:     }
L414:     workerInput_ = input;
L415:     std::fill(firstPts_.begin(), firstPts_.end(), AV_NOPTS_VALUE);
L416:     std::fill(firstDts_.begin(), firstDts_.end(), AV_NOPTS_VALUE);
L417:     if (audioBitstreamFilter_ != nullptr) {
L418:         av_bsf_flush(audioBitstreamFilter_);
L419:         audioBitstreamFilter_->time_base_in = input->streams[audioInputStreamIndex_]->time_base;
L420:         audioBitstreamFilter_->time_base_out = audioBitstreamFilter_->time_base_in;
L421:     }
L422:     waitingForKeyFrame_ = hasVideo_;
L423:     state_ = hasVideo_ ? RecorderState::WaitingKeyFrame : RecorderState::Recording;
L424:     segmentStartPtsUs_ = AV_NOPTS_VALUE;
L425:     LOGI("recorder adopted reconnect input snapshot; waitKeyFrame=%d", waitingForKeyFrame_ ? 1 : 0);
L426:     return true;
L427: }
L428: 
L429: void PlayerRemuxRecorder::workerLoop(InputSnapshot input, RemuxRecordConfig config) {
L430:     workerInput_ = std::move(input);
L431:     const std::string result = startLocked(workerInput_.get(), config);
L432:     const bool started = result.find("\"success\":true") != std::string::npos;
L433:     publishSnapshot();
L434:     {
L435:         std::lock_guard<std::mutex> lock(mutex_);
L436:         startResult_ = result;
L437:         startCompleted_ = true;
L438:         accepting_.store(started && queueFailure_ == QueueFailure::None);
L439:     }
L440:     startCv_.notify_all();
L441:     if (started) {
L442:         for (;;) {
L443:             QueuedPacket next;
L444:             {
L445:                 std::unique_lock<std::mutex> lock(mutex_);
L446:                 queueCv_.wait(lock, [this] {
L447:                     return stopRequested_ || queueFailure_ != QueueFailure::None || !queue_.empty();
L448:                 });
L449:                 if (queueFailure_ != QueueFailure::None) {
L450:                     const QueueFailure failure = queueFailure_;
L451:                     lock.unlock();
L452:                     setErrorLocked(failure == QueueFailure::Overflow
L453:                                    ? "record packet queue overflow; recording stopped"
L454:                                    : "record packet/input allocation failed; recording stopped");
L455:                     break;
L456:                 }
L457:                 if (queue_.empty()) break; // 正常 stop 在所有已接受包排空后退出。
L458:                 next = std::move(queue_.front());
L459:                 queueBytes_ -= next.bytes;
L460:                 queue_.pop_front();
L461:             }
L462:             // 此区间无队列锁/生命周期锁，写盘阻塞不会传递给生产者或诊断查询。
L463:             if (!adoptInput(next.input)) break;
L464:             if (shouldWritePacketLocked(next.packet.get(), workerInput_.get())
L465:                 && rotateSegmentIfNeededLocked(next.packet.get(), workerInput_.get())) {
L466:                 writePacketLocked(next.packet.get(), workerInput_.get());
L467:             }
L468:             publishSnapshot();
L469:             if (state_ == RecorderState::Error) break;
L470:         }
L471:     }
L472:     {
L473:         std::lock_guard<std::mutex> lock(mutex_);
L474:         accepting_.store(false);
L475:         queueDrops_ += queue_.size();
L476:         queue_.clear();
L477:         queueBytes_ = 0;
L478:     }
L479:     finishWorker();
L480:     workerInput_.reset();
L481:     publishSnapshot();
L482: }
L483: 
L484: void PlayerRemuxRecorder::finishWorker() {
L485:     stopTimeUs_ = av_gettime_relative();
L486:     const bool sourceVideo = sourceHasVideo_;
L487:     const bool sourceAudio = sourceHasAudio_;
L488:     const bool recordedVideo = videoStreamRecorded_;
L489:     const bool recordedAudio = audioStreamRecorded_;
L490:     const int result = closeOutputLocked(true);
L491:     sourceHasVideo_ = sourceVideo;
L492:     sourceHasAudio_ = sourceAudio;
L493:     videoStreamRecorded_ = recordedVideo;
L494:     audioStreamRecorded_ = recordedAudio;
L495:     if (result < 0) setErrorLocked(ffmpegErrorToString(result), result);
L496:     if (state_ != RecorderState::Error) state_ = RecorderState::Stopped;
L497: }
L498: 
L499: void PlayerRemuxRecorder::stopAndJoin() {
L500:     {
L501:         std::lock_guard<std::mutex> lock(mutex_);
L502:         accepting_.store(false);
L503:         stopRequested_ = true;
L504:         if (isRecorderActive(publishedState_)) publishedState_ = RecorderState::Stopping;
L505:     }
L506:     queueCv_.notify_one();
L507:     if (worker_.joinable()) worker_.join();
L508: }
L509: 
L510: std::string PlayerRemuxRecorder::stop() {
L511:     std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
L512:     stopAndJoin();
L513:     std::string result = getState();
L514:     result.insert(1, "\"message\":\"player remux recording stopped\",");
L515:     return result;
L516: }
L517: 
L518: void PlayerRemuxRecorder::release() {
L519:     std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
L520:     if (released_) return;
L521:     stopAndJoin();
L522:     released_ = true;
L523:     std::lock_guard<std::mutex> lock(mutex_);
L524:     input_.reset();
L525:     publishedState_ = RecorderState::Released;
L526: }
```

## E08｜Recorder timestamp rewriting

### `ffmpegplayer/src/main/cpp/native/PlayerRemuxRecorder.cpp:1054–1146`

```text
L1054: bool PlayerRemuxRecorder::writePacketLocked(const AVPacket *packet, AVFormatContext *inputFmtCtx) {
L1055:     AVStream *inputStream = inputFmtCtx->streams[packet->stream_index];
L1056:     const int outputIndex = streamMapping_[packet->stream_index];
L1057:     AVStream *outputStream = outputFmtCtx_->streams[outputIndex];
L1058: 
L1059:     AVPacket *recordPacket = av_packet_alloc();
L1060:     if (recordPacket == nullptr) {
L1061:         setErrorLocked("av_packet_alloc failed", -1);
L1062:         return false;
L1063:     }
L1064: 
L1065:     int result = av_packet_ref(recordPacket, packet);
L1066:     if (result < 0) {
L1067:         const std::string error = ffmpegErrorToString(result);
L1068:         av_packet_free(&recordPacket);
L1069:         setErrorLocked("packet reference: " + error, result);
L1070:         return false;
L1071:     }
L1072: 
L1073:     if (firstPts_[packet->stream_index] == AV_NOPTS_VALUE && recordPacket->pts != AV_NOPTS_VALUE) {
L1074:         firstPts_[packet->stream_index] = recordPacket->pts;
L1075:     }
L1076:     if (firstDts_[packet->stream_index] == AV_NOPTS_VALUE && recordPacket->dts != AV_NOPTS_VALUE) {
L1077:         firstDts_[packet->stream_index] = recordPacket->dts;
L1078:     }
L1079: 
L1080:     if (recordPacket->pts != AV_NOPTS_VALUE && firstPts_[packet->stream_index] != AV_NOPTS_VALUE) {
L1081:         recordPacket->pts -= firstPts_[packet->stream_index];
L1082:     }
L1083:     if (recordPacket->dts != AV_NOPTS_VALUE && firstDts_[packet->stream_index] != AV_NOPTS_VALUE) {
L1084:         recordPacket->dts -= firstDts_[packet->stream_index];
L1085:     }
L1086:     if (recordPacket->pts != AV_NOPTS_VALUE && recordPacket->pts < 0) {
L1087:         recordPacket->pts = 0;
L1088:     }
L1089:     if (recordPacket->dts != AV_NOPTS_VALUE && recordPacket->dts < 0) {
L1090:         recordPacket->dts = 0;
L1091:     }
L1092:     if (recordPacket->pts != AV_NOPTS_VALUE && recordPacket->dts != AV_NOPTS_VALUE && recordPacket->pts < recordPacket->dts) {
L1093:         LOGE("record packet pts < dts, adjust pts stream=%d pts=%lld dts=%lld",
L1094:              packet->stream_index, static_cast<long long>(recordPacket->pts), static_cast<long long>(recordPacket->dts));
L1095:         recordPacket->pts = recordPacket->dts;
L1096:     }
L1097: 
L1098:     auto writeOutputPacket = [&](AVPacket *outputPacket, AVRational sourceTimeBase) -> bool {
L1099:         av_packet_rescale_ts(outputPacket, sourceTimeBase, outputStream->time_base);
L1100:         // 重连后以输出时间基衔接已写 DTS，输入 PTS 可从零重新开始。
L1101:         if (outputPacket->pts != AV_NOPTS_VALUE) outputPacket->pts += timestampOffset_[outputIndex];
L1102:         if (outputPacket->dts != AV_NOPTS_VALUE) outputPacket->dts += timestampOffset_[outputIndex];
L1103:         outputPacket->stream_index = outputIndex;
L1104:         outputPacket->pos = -1;
L1105: 
L1106:         // RTSP 重连首包可能没有 PTS/DTS。不能让 muxer 隐式补值，否则本地
L1107:         // lastDts 与容器时间线分离，随后恢复有效时间戳的包会被判为倒退。
L1108:         const int64_t step = std::max<int64_t>(1, outputPacket->duration);
L1109:         const int64_t previousDts = lastDts_[outputIndex];
L1110:         if (outputPacket->dts == AV_NOPTS_VALUE) {
L1111:             outputPacket->dts = outputPacket->pts != AV_NOPTS_VALUE ? outputPacket->pts
L1112:                                : previousDts == AV_NOPTS_VALUE ? timestampOffset_[outputIndex]
L1113:                                : previousDts + step;
L1114:         }
L1115:         if (previousDts != AV_NOPTS_VALUE && outputPacket->dts <= previousDts) {
L1116:             const int64_t correction = previousDts + step - outputPacket->dts;
L1117:             outputPacket->dts += correction;
L1118:             if (outputPacket->pts != AV_NOPTS_VALUE) outputPacket->pts += correction;
L1119:             // 把同一修正应用于后续包，保留输入时间间隔，而非逐包丢弃。
L1120:             timestampOffset_[outputIndex] += correction;
L1121:             LOGI("record timestamp discontinuity stream=%d correction=%lld",
L1122:                  outputIndex, static_cast<long long>(correction));
L1123:         }
L1124:         if (outputPacket->pts == AV_NOPTS_VALUE || outputPacket->pts < outputPacket->dts) {
L1125:             outputPacket->pts = outputPacket->dts;
L1126:         }
L1127:         const int64_t writtenDts = outputPacket->dts;
L1128: 
L1129:         result = av_interleaved_write_frame(outputFmtCtx_, outputPacket);
L1130:         if (result < 0) {
L1131:             const std::string error = "mux write: " + ffmpegErrorToString(result);
L1132:             LOGE("av_interleaved_write_frame failed stream=%d error=%s", outputIndex, error.c_str());
L1133:             setErrorLocked(error, result);
L1134:             return false;
L1135:         }
L1136:         if (isMp4LikeFormat(formatName_) && fragmentedMp4_ && outputFmtCtx_ != nullptr
L1137:             && outputFmtCtx_->pb != nullptr) {
L1138:             avio_flush(outputFmtCtx_->pb);
L1139:             if (outputFmtCtx_->pb->error < 0) {
L1140:                 setErrorLocked("mux flush: " + ffmpegErrorToString(outputFmtCtx_->pb->error),
L1141:                                outputFmtCtx_->pb->error);
L1142:                 return false;
L1143:             }
L1144:         }
L1145:         lastDts_[outputIndex] = writtenDts;
L1146:         packetsWritten_.fetch_add(1);
```

## E09｜Multi-stream AAC BSF ownership

### `ffmpegplayer/src/main/cpp/native/PlayerRemuxRecorder.h:112–133`

```text
L112:     std::string publishedDetails_;
L113:     RecorderState publishedState_ = RecorderState::Idle;
L114:     std::atomic<int64_t> packetsWritten_{0};
L115:     std::atomic<int64_t> writeErrors_{0};
L116:     std::atomic<int64_t> publishedVideoPackets_{0};
L117:     std::atomic<int64_t> publishedAudioPackets_{0};
L118:     std::atomic<int64_t> publishedSegments_{0};
L119:     // 以下资源和非原子状态只由 worker 访问，队列中的输入快照延长流参数寿命。
L120:     AVFormatContext *outputFmtCtx_ = nullptr;
L121:     AVBSFContext *audioBitstreamFilter_ = nullptr;
L122:     // 输入流索引到输出流索引的映射，未录制的流不写入输出文件。
L123:     std::vector<int> streamMapping_;
L124:     // 各流的初始时间戳与最近 DTS，用于输出时间戳归一化及单调性处理。
L125:     std::vector<int64_t> firstPts_;
L126:     std::vector<int64_t> firstDts_;
L127:     std::vector<int64_t> lastDts_;
L128:     std::vector<int64_t> timestampOffset_;
L129: 
L130:     RecorderState state_ = RecorderState::Idle;
L131:     std::string outputPath_;
L132:     std::string outputPattern_;
L133:     std::string currentSegmentPath_;
```

### `ffmpegplayer/src/main/cpp/native/PlayerRemuxRecorder.cpp:680–772`

```text
L680:         if (inputStream == nullptr || inputStream->codecpar == nullptr) {
L681:             continue;
L682:         }
L683: 
L684:         AVCodecParameters *codecpar = inputStream->codecpar;
L685:         const AVMediaType type = codecpar->codec_type;
L686:         if (type != AVMEDIA_TYPE_VIDEO && type != AVMEDIA_TYPE_AUDIO) {
L687:             continue;
L688:         }
L689: 
L690:         const bool isAudio = type == AVMEDIA_TYPE_AUDIO;
L691:         const bool isVideo = type == AVMEDIA_TYPE_VIDEO;
L692:         if (isVideo) {
L693:             sourceHasVideo_ = true;
L694:         } else if (isAudio) {
L695:             sourceHasAudio_ = true;
L696:             if (codecpar->codec_id == AV_CODEC_ID_NONE || codecpar->sample_rate <= 0 || codecpar->ch_layout.nb_channels <= 0) {
L697:                 LOGE("skip invalid audio stream input=%u codec=%s sampleRate=%d channels=%d",
L698:                      i, avcodec_get_name(codecpar->codec_id), codecpar->sample_rate, codecpar->ch_layout.nb_channels);
L699:                 lastError_ = "audio stream codec parameters are incomplete; audio stream skipped";
L700:                 continue;
L701:             }
L702:         }
L703: 
L704:         AVStream *outputStream = avformat_new_stream(outputFmtCtx_, nullptr);
L705:         if (outputStream == nullptr) {
L706:             if (isAudio) {
L707:                 LOGE("avformat_new_stream failed for audio stream input=%u; continue video recording", i);
L708:                 lastError_ = "avformat_new_stream failed for audio stream; audio stream skipped";
L709:                 continue;
L710:             }
L711:             closeOutputLocked(false);
L712:             setErrorLocked("avformat_new_stream failed", -1);
L713:             return -1;
L714:         }
L715: 
L716:         const int copyResult = avcodec_parameters_copy(outputStream->codecpar, codecpar);
L717:         if (copyResult < 0) {
L718:             const std::string error = ffmpegErrorToString(copyResult);
L719:             if (isAudio) {
L720:                 LOGE("avcodec_parameters_copy failed for audio input=%u error=%s; continue video recording", i, error.c_str());
L721:                 lastError_ = error;
L722:                 continue;
L723:             }
L724:             closeOutputLocked(false);
L725:             setErrorLocked(error, copyResult);
L726:             return copyResult;
L727:         }
L728: 
L729:         if (isAudio && codecpar->codec_id == AV_CODEC_ID_AAC && isMp4LikeFormat(formatName_)) {
L730:             const AVBitStreamFilter *filter = av_bsf_get_by_name("aac_adtstoasc");
L731:             if (filter == nullptr) {
L732:                 closeOutputLocked(false);
L733:                 setErrorLocked("aac_adtstoasc bitstream filter unavailable", AVERROR_BSF_NOT_FOUND);
L734:                 return lastErrorCode_;
L735:             }
L736:             result = av_bsf_alloc(filter, &audioBitstreamFilter_);
L737:             if (result >= 0) {
L738:                 result = avcodec_parameters_copy(audioBitstreamFilter_->par_in, codecpar);
L739:             }
L740:             if (result >= 0) {
L741:                 audioBitstreamFilter_->time_base_in = inputStream->time_base;
L742:                 result = av_bsf_init(audioBitstreamFilter_);
L743:             }
L744:             if (result >= 0) {
L745:                 result = avcodec_parameters_copy(outputStream->codecpar, audioBitstreamFilter_->par_out);
L746:             }
L747:             if (result < 0) {
L748:                 const std::string error = ffmpegErrorToString(result);
L749:                 closeOutputLocked(false);
L750:                 setErrorLocked(error, result);
L751:                 return result;
L752:             }
L753:             LOGI("record AAC bitstream filter enabled name=aac_adtstoasc input=%u output=%d", i,
L754:                  outputStream->index);
L755:         }
L756:         outputStream->codecpar->codec_tag = 0;
L757:         outputStream->time_base = inputStream->time_base;
L758:         streamMapping_[i] = outputStream->index;
L759: 
L760:         if (isVideo) {
L761:             hasVideo_ = true;
L762:             videoStreamRecorded_ = true;
L763:             videoInputStreamIndex_ = static_cast<int>(i);
L764:             LOGI("record stream map video input=%u output=%d codec=%s", i, outputStream->index,
L765:                  avcodec_get_name(codecpar->codec_id));
L766:         } else if (isAudio) {
L767:             audioStreamRecorded_ = true;
L768:             audioInputStreamIndex_ = static_cast<int>(i);
L769:             LOGI("record stream map audio input=%u output=%d codec=%s sampleRate=%d channels=%d playbackEnabled=%d recordingIndependent=1",
L770:                  i, outputStream->index, avcodec_get_name(codecpar->codec_id), codecpar->sample_rate,
L771:                  codecpar->ch_layout.nb_channels, audioPlaybackEnabled_ ? 1 : 0);
L772:         }
```

### `ffmpegplayer/src/main/cpp/native/PlayerRemuxRecorder.cpp:836–852`

```text
L836: 
L837:     if (outputFmtCtx_ != nullptr && writeTrailer && headerWritten_) {
L838:         trailerResult = av_write_trailer(outputFmtCtx_);
L839:         if (trailerResult < 0) {
L840:             LOGE("av_write_trailer failed path=%s error=%s", closedPath.c_str(), ffmpegErrorToString(trailerResult).c_str());
L841:         } else {
L842:             LOGI("write trailer success outputPath=%s", closedPath.c_str());
L843:         }
L844:     }
L845: 
L846:     av_bsf_free(&audioBitstreamFilter_);
L847: 
L848:     if (outputFmtCtx_ != nullptr) {
L849:         if (!(outputFmtCtx_->oformat->flags & AVFMT_NOFILE) && outputFmtCtx_->pb != nullptr) {
L850:             const int closeResult = avio_closep(&outputFmtCtx_->pb);
L851:             if (trailerResult >= 0 && closeResult < 0) {
L852:                 trailerResult = closeResult;
```

### `ffmpegplayer/src/main/cpp/native/PlayerRemuxRecorder.cpp:1151–1178`

```text
L1151:         } else if (packet->stream_index == audioInputStreamIndex_) {
L1152:             ++audioPacketCount_;
L1153:             ++currentSegmentAudioPacketCount_;
L1154:         }
L1155:         return true;
L1156:     };
L1157: 
L1158:     if (packet->stream_index == audioInputStreamIndex_ && audioBitstreamFilter_ != nullptr) {
L1159:         result = av_bsf_send_packet(audioBitstreamFilter_, recordPacket);
L1160:         av_packet_free(&recordPacket);
L1161:         if (result < 0) {
L1162:             const std::string error = ffmpegErrorToString(result);
L1163:             setErrorLocked(error, result);
L1164:             return false;
L1165:         }
L1166: 
L1167:         AVPacket *filteredPacket = av_packet_alloc();
L1168:         if (filteredPacket == nullptr) {
L1169:             setErrorLocked("av_packet_alloc failed", -1);
L1170:             return false;
L1171:         }
L1172:         bool success = true;
L1173:         while (true) {
L1174:             result = av_bsf_receive_packet(audioBitstreamFilter_, filteredPacket);
L1175:             if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
L1176:                 break;
L1177:             }
L1178:             if (result < 0) {
```

## E10｜Segment filename printf contract

### `ffmpegplayer/src/main/cpp/native/PlayerRemuxRecorder.cpp:204–222`

```text
L204: bool hasPrintfIntegerPlaceholder(const std::string &pattern) {
L205:     for (size_t pos = pattern.find('%'); pos != std::string::npos; pos = pattern.find('%', pos + 1)) {
L206:         if (pos + 1 < pattern.size() && pattern[pos + 1] == '%') {
L207:             ++pos;
L208:             continue;
L209:         }
L210:         size_t i = pos + 1;
L211:         while (i < pattern.size() && std::string("-+ #0").find(pattern[i]) != std::string::npos) {
L212:             ++i;
L213:         }
L214:         while (i < pattern.size() && std::isdigit(static_cast<unsigned char>(pattern[i]))) {
L215:             ++i;
L216:         }
L217:         if (i < pattern.size() && (pattern[i] == 'd' || pattern[i] == 'i' || pattern[i] == 'u')) {
L218:             return true;
L219:         }
L220:     }
L221:     return false;
L222: }
```

### `ffmpegplayer/src/main/cpp/native/PlayerRemuxRecorder.cpp:1198–1211`

```text
L1198: std::string PlayerRemuxRecorder::makeSegmentPathLocked(int segmentIndex) const {
L1199:     const int displayIndex = segmentIndex + 1;
L1200:     if (!segmentMode_) {
L1201:         return outputPattern_;
L1202:     }
L1203:     if (hasPrintfIntegerPlaceholder(outputPattern_)) {
L1204:         char buffer[4096] = {0};
L1205:         const int written = std::snprintf(buffer, sizeof(buffer), outputPattern_.c_str(), displayIndex);
L1206:         if (written > 0 && written < static_cast<int>(sizeof(buffer))) {
L1207:             return std::string(buffer);
L1208:         }
L1209:         LOGE("segment output pattern is too long, fallback to indexed suffix pattern=%s", outputPattern_.c_str());
L1210:     }
L1211:     return insertSegmentIndex(outputPattern_, displayIndex);
```

## E11｜NV12/OES aspect-fit; YUV full viewport

### `ffmpegplayer/src/main/cpp/native/NativeNv12GlRenderer.cpp:376–420`

```text
L376:         glUniform4f(coeffsLocation_, cR_V, cG_U, cG_V, cB_U);
L377:     }
L378:     lastAppliedThermalMode_.store(useIronbow ? 2 : (program == whiteHotProgram_ ? 1 : 0));
L379: 
L380:     GLint viewportWidth = surfaceWidth_ > 0 ? surfaceWidth_ : width;
L381:     GLint viewportHeight = surfaceHeight_ > 0 ? surfaceHeight_ : height;
L382:     const float contentAspect = static_cast<float>(width) / static_cast<float>(height);
L383:     const float surfaceAspect = viewportHeight > 0
L384:                                 ? static_cast<float>(viewportWidth) / static_cast<float>(viewportHeight)
L385:                                 : 1.0f;
L386:     GLint viewportX = 0;
L387:     GLint viewportY = 0;
L388:     if (contentAspect > surfaceAspect) {
L389:         viewportHeight = static_cast<GLint>(static_cast<float>(viewportWidth) / contentAspect);
L390:         if (viewportHeight > (surfaceHeight_ > 0 ? surfaceHeight_ : 1)) {
L391:             viewportHeight = surfaceHeight_;
L392:         }
L393:         viewportY = (surfaceHeight_ - viewportHeight) / 2;
L394:     } else {
L395:         viewportWidth = static_cast<GLint>(static_cast<float>(viewportHeight) * contentAspect);
L396:         if (viewportWidth > (surfaceWidth_ > 0 ? surfaceWidth_ : 1)) {
L397:             viewportWidth = surfaceWidth_;
L398:         }
L399:         viewportX = (surfaceWidth_ - viewportWidth) / 2;
L400:     }
L401:     if (viewportWidth <= 0 || viewportHeight <= 0) {
L402:         viewportWidth = surfaceWidth_ > 0 ? surfaceWidth_ : width;
L403:         viewportHeight = surfaceHeight_ > 0 ? surfaceHeight_ : height;
L404:     }
L405:     glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
L406:     glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
L407:     glClear(GL_COLOR_BUFFER_BIT);
L408: 
L409:     static const GLfloat vertices[] = {
L410:             -1.0f, -1.0f,
L411:              1.0f, -1.0f,
L412:             -1.0f,  1.0f,
L413:              1.0f,  1.0f
L414:     };
L415:     static const GLfloat texCoords[] = {
L416:             0.0f, 1.0f,
L417:             1.0f, 1.0f,
L418:             0.0f, 0.0f,
L419:             1.0f, 0.0f
L420:     };
```

### `ffmpegplayer/src/main/cpp/native/NativeOesRenderer.cpp:447–488`

```text
L447:     }
L448:     const float effBlack = useAgcWindow ? agcBlackPoint_.load() : blackPoint;
L449:     const float effWhite = useAgcWindow ? agcWhitePoint_.load() : whitePoint;
L450: 
L451:     // Aspect-fit letterbox viewport (no forced stretch), honoring 90-degree
L452:     // rotation from the SurfaceTexture transform matrix.
L453:     GLint viewportX = 0;
L454:     GLint viewportY = 0;
L455:     GLint viewportWidth = surfaceWidth_ > 0 ? surfaceWidth_ : 1;
L456:     GLint viewportHeight = surfaceHeight_ > 0 ? surfaceHeight_ : 1;
L457:     if (frameWidth > 0 && frameHeight > 0) {
L458:         const bool transposed = std::abs(transform[0]) < 0.0001f && std::abs(transform[5]) < 0.0001f;
L459:         const float contentAspect = transposed
L460:                                     ? static_cast<float>(frameHeight) / static_cast<float>(frameWidth)
L461:                                     : static_cast<float>(frameWidth) / static_cast<float>(frameHeight);
L462:         const float surfaceAspect = viewportHeight > 0
L463:                                     ? static_cast<float>(viewportWidth) / static_cast<float>(viewportHeight)
L464:                                     : 1.0f;
L465:         if (contentAspect > surfaceAspect) {
L466:             viewportWidth = static_cast<GLint>(viewportWidth);
L467:             viewportHeight = static_cast<GLint>(static_cast<float>(viewportWidth) / contentAspect);
L468:             if (viewportHeight > (surfaceHeight_ > 0 ? surfaceHeight_ : 1)) {
L469:                 viewportHeight = surfaceHeight_;
L470:             }
L471:         } else {
L472:             viewportHeight = static_cast<GLint>(viewportHeight);
L473:             viewportWidth = static_cast<GLint>(static_cast<float>(viewportHeight) * contentAspect);
L474:             if (viewportWidth > (surfaceWidth_ > 0 ? surfaceWidth_ : 1)) {
L475:                 viewportWidth = surfaceWidth_;
L476:             }
L477:         }
L478:         if (viewportWidth <= 0 || viewportHeight <= 0) {
L479:             viewportWidth = surfaceWidth_ > 0 ? surfaceWidth_ : 1;
L480:             viewportHeight = surfaceHeight_ > 0 ? surfaceHeight_ : 1;
L481:         }
L482:         viewportX = (surfaceWidth_ - viewportWidth) / 2;
L483:         viewportY = (surfaceHeight_ - viewportHeight) / 2;
L484:     }
L485:     glViewport(viewportX, viewportY, viewportWidth, viewportHeight);
L486:     glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
L487:     glClear(GL_COLOR_BUFFER_BIT);
L488: 
```

### `ffmpegplayer/src/main/cpp/native/NativeYuvGlRenderer.cpp:261–294`

```text
L261:             glBindTexture(GL_TEXTURE_2D, ironbowTexture_);
L262:             glActiveTexture(GL_TEXTURE0);
L263:         }
L264:     } else if (program == whiteHotProgram_) {
L265:         setThermalUniforms(whiteHotUniforms_, params);
L266:     }
L267: 
L268:     const int viewportWidth = surfaceWidth_ > 0 ? surfaceWidth_ : width;
L269:     const int viewportHeight = surfaceHeight_ > 0 ? surfaceHeight_ : height;
L270:     glViewport(0, 0, viewportWidth, viewportHeight);
L271:     glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
L272:     glClear(GL_COLOR_BUFFER_BIT);
L273: 
L274:     static const GLfloat vertices[] = {
L275:             -1.0f, -1.0f,
L276:              1.0f, -1.0f,
L277:             -1.0f,  1.0f,
L278:              1.0f,  1.0f
L279:     };
L280:     static const GLfloat texCoords[] = {
L281:             0.0f, 1.0f,
L282:             1.0f, 1.0f,
L283:             0.0f, 0.0f,
L284:             1.0f, 0.0f
L285:     };
L286: 
L287:     const GLint positionLocation = glGetAttribLocation(program, "aPosition");
L288:     const GLint texCoordLocation = glGetAttribLocation(program, "aTexCoord");
L289:     if (positionLocation < 0 || texCoordLocation < 0) {
L290:         stats.totalCostUs = steadyNowUs() - renderStartUs;
L291:         return {false, -1, "GL YUV shader attribute not found", stats};
L292:     }
L293:     glEnableVertexAttribArray(static_cast<GLuint>(positionLocation));
L294:     glVertexAttribPointer(static_cast<GLuint>(positionLocation), 2, GL_FLOAT, GL_FALSE, 0, vertices);
```

## E12｜Surface result and RGBA buffer rendering

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:591–638`

```text
L591: std::string NativePlayer::setSurface(JNIEnv *env, jobject surface) {
L592:     if (isReleased()) {
L593:         return jsonError(-1, "player is released");
L594:     }
L595:     if (env == nullptr) {
L596:         return jsonError(-1, "JNIEnv is null");
L597:     }
L598:     if (surface == nullptr) {
L599:         return jsonError(-1, "Surface is null");
L600:     }
L601: 
L602:     int width = 0;
L603:     int height = 0;
L604:     {
L605:         std::lock_guard<std::mutex> lock(mutex_);
L606:         width = videoWidth_;
L607:         height = videoHeight_;
L608:     }
L609: 
L610:     jobject newSurfaceRef = env->NewGlobalRef(surface);
L611:     if (newSurfaceRef == nullptr) {
L612:         return jsonError(-1, "NewGlobalRef Surface failed");
L613:     }
L614: 
L615:     {
L616:         std::lock_guard<std::mutex> surfaceLock(surfaceMutex_);
L617:         deleteSurfaceGlobalRefLocked(env);
L618:         surfaceGlobalRef_ = newSurfaceRef;
L619:     }
L620: 
L621:     LOGI("setSurface player=%p width=%d height=%d", this, width, height);
L622:     const std::string rgbaResult = renderer_.setSurface(env, surface, width, height);
L623:     const std::string glResult = yuvGlRenderer_.setSurface(env, surface, width, height);
L624:     const std::string oesResult = oesRenderer_.setSurface(env, surface, width, height);
L625:     const std::string nv12GlResult = nv12GlRenderer_.setSurface(env, surface, width, height);
L626:     if (rgbaResult.find("\"success\":true") == std::string::npos) {
L627:         return rgbaResult;
L628:     }
L629:     if (glResult.find("\"success\":true") == std::string::npos) {
L630:         LOGE("setSurface GL YUV renderer failed: %s", glResult.c_str());
L631:     }
L632:     if (oesResult.find("\"success\":true") == std::string::npos) {
L633:         LOGE("setSurface OES renderer failed: %s", oesResult.c_str());
L634:     }
L635:     if (nv12GlResult.find("\"success\":true") == std::string::npos) {
L636:         LOGE("setSurface NV12 GL renderer failed: %s", nv12GlResult.c_str());
L637:     }
L638:     return rgbaResult;
```

### `ffmpegplayer/src/main/cpp/native/VideoRenderer.cpp:77–125`

```text
L77: RenderResult VideoRenderer::renderRgba(const uint8_t *rgbaData, int lineSize, int width, int height) {
L78:     if (rgbaData == nullptr || lineSize <= 0 || width <= 0 || height <= 0) {
L79:         return {false, -1, "invalid RGBA frame", {}};
L80:     }
L81: 
L82:     std::lock_guard<std::mutex> lock(mutex_);
L83:     if (window_ == nullptr) {
L84:         return {false, -1, "Surface is not set", {}};
L85:     }
L86: 
L87:     RenderStats stats;
L88:     const int64_t renderStartUs = steadyNowUs();
L89: 
L90:     if (width_ != width || height_ != height) {
L91:         const int geometryResult = ANativeWindow_setBuffersGeometry(window_, width, height, WINDOW_FORMAT_RGBA_8888);
L92:         if (geometryResult < 0) {
L93:             LOGE("ANativeWindow_setBuffersGeometry failed: %d", geometryResult);
L94:             stats.totalCostUs = steadyNowUs() - renderStartUs;
L95:             return {false, geometryResult, "ANativeWindow_setBuffersGeometry failed", stats};
L96:         }
L97:         width_ = width;
L98:         height_ = height;
L99:     }
L100: 
L101:     ANativeWindow_Buffer buffer;
L102:     const int64_t lockStartUs = steadyNowUs();
L103:     const int lockResult = ANativeWindow_lock(window_, &buffer, nullptr);
L104:     stats.lockCostUs = steadyNowUs() - lockStartUs;
L105:     if (lockResult < 0) {
L106:         LOGE("ANativeWindow_lock failed: %d", lockResult);
L107:         stats.totalCostUs = steadyNowUs() - renderStartUs;
L108:         return {false, lockResult, "ANativeWindow_lock failed", stats};
L109:     }
L110: 
L111:     auto *dst = static_cast<uint8_t *>(buffer.bits);
L112:     const int dstStride = buffer.stride * 4;
L113:     const int copyWidth = std::min(width, buffer.width) * 4;
L114:     const int copyHeight = std::min(height, buffer.height);
L115: 
L116:     const int64_t copyStartUs = steadyNowUs();
L117:     for (int y = 0; y < copyHeight; ++y) {
L118:         std::memcpy(dst + y * dstStride, rgbaData + y * lineSize, copyWidth);
L119:     }
L120:     stats.copyCostUs = steadyNowUs() - copyStartUs;
L121: 
L122:     const int64_t postStartUs = steadyNowUs();
L123:     const int unlockResult = ANativeWindow_unlockAndPost(window_);
L124:     stats.postCostUs = steadyNowUs() - postStartUs;
L125:     stats.totalCostUs = steadyNowUs() - renderStartUs;
```

## E13｜Native snapshot mode contract

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:2895–2945`

```text
L2895: std::string NativePlayer::takeSnapshot(const std::string &outputPath) {
L2896:     if (isReleased()) {
L2897:         return snapshotError("SNAPSHOT_PLAYER_RELEASED", "player is released", "unsupported");
L2898:     }
L2899: 
L2900:     RenderMode renderMode = RenderMode::SOFTWARE_RGBA;
L2901:     {
L2902:         std::lock_guard<std::mutex> lock(mutex_);
L2903:         renderMode = playerOptions_.renderMode;
L2904:     }
L2905: 
L2906:     const int rendererType = rendererTypeFromState(rendererState_.load());
L2907:     const std::string captureMode = snapshotCaptureModeName(renderMode, rendererType);
L2908:     LOGI("snapshot route=%s requestedRenderer=%s actualRenderer=%s",
L2909:          captureMode.c_str(), rendererNameFromRenderMode(renderMode), rendererTypeName(rendererType));
L2910:     if (captureMode == "surface_pixelcopy") {
L2911:         return snapshotError(
L2912:                 "SNAPSHOT_REQUIRES_SURFACE_CAPTURE",
L2913:                 "Snapshot is not supported by the native RGBA frame cache; use surface capture",
L2914:                 captureMode);
L2915:     }
L2916:     if (captureMode != "native_rgba") {
L2917:         return snapshotError("SNAPSHOT_UNSUPPORTED", "Snapshot is not supported", captureMode);
L2918:     }
L2919: 
L2920:     std::vector<uint8_t> frameCopy;
L2921:     int width = 0;
L2922:     int height = 0;
L2923:     int stride = 0;
L2924:     int64_t ptsUs = 0;
L2925:     {
L2926:         std::lock_guard<std::mutex> lock(lastFrameMutex_);
L2927:         if (!hasLastFrame_ || lastRgbaFrame_.empty()) {
L2928:             LOGE("takePlayerSnapshot failed: no video frame available");
L2929:             return snapshotError("SNAPSHOT_NO_FRAME", "no video frame available", captureMode);
L2930:         }
L2931:         frameCopy = lastRgbaFrame_;
L2932:         width = lastFrameWidth_;
L2933:         height = lastFrameHeight_;
L2934:         stride = lastFrameStride_;
L2935:         ptsUs = lastFramePtsUs_;
L2936:     }
L2937: 
L2938:     LOGI("takePlayerSnapshot outputPath=%s hasFrame=1 width=%d height=%d ptsUs=%lld",
L2939:          outputPath.c_str(), width, height, static_cast<long long>(ptsUs));
L2940:     const std::string result = SnapshotManager::saveRgba(outputPath, frameCopy, width, height, stride, ptsUs);
L2941:     if (result.find("\"success\":true") != std::string::npos) {
L2942:         lastSnapshotTimeMs_.store(nowMs());
L2943:     }
L2944:     return result;
L2945: }
```

### `ffmpegplayer/src/main/cpp/native/SnapshotManager.cpp:300–326`

```text
L300: std::string SnapshotManager::saveRgba(const std::string &outputPath,
L301:                                       const std::vector<uint8_t> &rgba,
L302:                                       int width,
L303:                                       int height,
L304:                                       int stride,
L305:                                       int64_t ptsUs) {
L306:     LOGI("takePlayerSnapshot outputPath=%s", outputPath.c_str());
L307: 
L308:     if (outputPath.empty()) {
L309:         return jsonError("SNAPSHOT_IO_ERROR", "outputPath is empty");
L310:     }
L311:     const std::string parent = parentDirectory(outputPath);
L312:     if (!directoryExists(parent)) {
L313:         return jsonError("SNAPSHOT_IO_ERROR", "outputPath parent directory does not exist: " + parent);
L314:     }
L315:     if (width <= 0 || height <= 0 || stride < width * 4 || rgba.empty()) {
L316:         return jsonError("SNAPSHOT_NO_FRAME", "invalid snapshot frame");
L317:     }
L318: 
L319:     if (endsWith(outputPath, ".png")) {
L320:         return savePng(outputPath, rgba, width, height, stride, ptsUs);
L321:     }
L322:     if (endsWith(outputPath, ".jpg") || endsWith(outputPath, ".jpeg")) {
L323:         return saveJpeg(outputPath, rgba, width, height, stride, ptsUs);
L324:     }
L325:     return jsonError("SNAPSHOT_UNSUPPORTED", "unsupported snapshot format; use .png or .jpg");
L326: }
```

## E14｜Demo PixelCopy and timeout ownership

### `app/src/main/java/com/example/motro/MediaPlayerActivity.java:1384–1530`

```text
L1384:     // 先尝试原生 RGBA 截图；仅在明确要求 Surface 捕获时转到 PixelCopy。
L1385:     private String takePlayerSnapshotCompat(FFmpegPlayer player, String outputPath) throws Exception {
L1386:         String nativeResult = player.takeSnapshot( outputPath);
L1387:         final JSONObject nativeSnapshot;
L1388:         try {
L1389:             nativeSnapshot = new JSONObject(nativeResult == null ? "" : nativeResult);
L1390:         } catch (Throwable parseError) {
L1391:             return snapshotError(
L1392:                     "SNAPSHOT_PROTOCOL_ERROR",
L1393:                     "Native snapshot returned invalid JSON",
L1394:                     nativeResult,
L1395:                     null);
L1396:         }
L1397:         if (nativeSnapshot.optBoolean("success", false)) {
L1398:             Log.i(TAG, "snapshot route=native_rgba");
L1399:             return nativeResult;
L1400:         }
L1401:         String errorCode = nativeSnapshot.optString("errorCode", "");
L1402:         if (!SNAPSHOT_REQUIRES_SURFACE_CAPTURE.equals(errorCode)) {
L1403:             return nativeResult;
L1404:         }
L1405:         Log.i(TAG, "snapshot route=surface_pixelcopy");
L1406:         return takeSurfaceSnapshotWithPixelCopy(player, outputPath, nativeResult);
L1407:     }
L1408: 
L1409:     // 工作线程等待主线程发起的异步复制；保存前再次确认 Surface 有效且代次未改变。
L1410:     private String takeSurfaceSnapshotWithPixelCopy(FFmpegPlayer player,
L1411:                                                     String outputPath,
L1412:                                                     String nativeResult) throws Exception {
L1413:         FFmpegPlayer current = getPlayer();
L1414:                 if (destroyed || current == null || current.isReleased()) {
L1415:             return snapshotError(
L1416:                     "SNAPSHOT_PLAYER_RELEASED",
L1417:                     "PixelCopy snapshot cancelled because the player was released",
L1418:                     nativeResult,
L1419:                     null);
L1420:         }
L1421:         SurfaceHolder holder = binding.playerPreviewView.getHolder();
L1422:         Surface surface = holder == null ? null : holder.getSurface();
L1423:         if (!surfaceReady || surface == null || !surface.isValid()) {
L1424:             return snapshotError(
L1425:                     "SNAPSHOT_NO_SURFACE",
L1426:                     "PixelCopy snapshot failed: surface is not ready",
L1427:                     nativeResult,
L1428:                     null);
L1429:         }
L1430:         int targetSurfaceGeneration = surfaceGeneration.get();
L1431:         int width = surfaceWidth;
L1432:         int height = surfaceHeight;
L1433:         if (width <= 0 || height <= 0) {
L1434:             return snapshotError(
L1435:                     "SNAPSHOT_NO_SURFACE",
L1436:                     "PixelCopy snapshot failed: surface size is not ready",
L1437:                     nativeResult,
L1438:                     null);
L1439:         }
L1440: 
L1441:         Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
L1442:         CountDownLatch latch = new CountDownLatch(1);
L1443:         AtomicInteger copyResult = new AtomicInteger(PixelCopy.ERROR_UNKNOWN);
L1444:         mainHandler.post(() -> {
L1445:             try {
L1446:                 PixelCopy.request(surface, bitmap, result -> {
L1447:                     copyResult.set(result);
L1448:                     latch.countDown();
L1449:                 }, mainHandler);
L1450:             } catch (Throwable requestError) {
L1451:                 copyResult.set(PixelCopy.ERROR_SOURCE_INVALID);
L1452:                 latch.countDown();
L1453:             }
L1454:         });
L1455: 
L1456:         if (!latch.await(1500, TimeUnit.MILLISECONDS)) {
L1457:             // PixelCopy can still own the destination after this timeout. Do
L1458:             // not recycle it while the asynchronous copy may still complete.
L1459:             return snapshotError(
L1460:                     "SNAPSHOT_PIXELCOPY_ERROR",
L1461:                     "PixelCopy snapshot timed out",
L1462:                     nativeResult,
L1463:                     PixelCopy.ERROR_TIMEOUT);
L1464:         }
L1465:         int result = copyResult.get();
L1466:         if (result != PixelCopy.SUCCESS) {
L1467:             bitmap.recycle();
L1468:             return snapshotError(
L1469:                     pixelCopyErrorCode(result),
L1470:                     "PixelCopy snapshot failed result=" + result,
L1471:                     nativeResult,
L1472:                     result);
L1473:         }
L1474:         FFmpegPlayer cur = getPlayer();
L1475:             if (destroyed || cur == null || cur.isReleased()) {
L1476:             bitmap.recycle();
L1477:             return snapshotError(
L1478:                     "SNAPSHOT_PLAYER_RELEASED",
L1479:                     "PixelCopy completed after the player was released",
L1480:                     nativeResult,
L1481:                     result);
L1482:         }
L1483:         if (targetSurfaceGeneration != surfaceGeneration.get()
L1484:                 || currentSurface != surface
L1485:                 || !surfaceReady
L1486:                 || !surface.isValid()) {
L1487:             bitmap.recycle();
L1488:             return snapshotError(
L1489:                     "SNAPSHOT_NO_SURFACE",
L1490:                     "PixelCopy completed after the video surface changed",
L1491:                     nativeResult,
L1492:                     result);
L1493:         }
L1494: 
L1495:         File outputFile = new File(outputPath);
L1496:         Bitmap.CompressFormat format = isJpegPath(outputPath)
L1497:                 ? Bitmap.CompressFormat.JPEG
L1498:                 : Bitmap.CompressFormat.PNG;
L1499:         try (OutputStream output = new FileOutputStream(outputFile)) {
L1500:             if (!bitmap.compress(format, 95, output)) {
L1501:                 return snapshotError(
L1502:                         "SNAPSHOT_IO_ERROR",
L1503:                         "PixelCopy snapshot encode failed",
L1504:                         nativeResult,
L1505:                         result);
L1506:             }
L1507:         } catch (Throwable saveError) {
L1508:             return snapshotError(
L1509:                     "SNAPSHOT_IO_ERROR",
L1510:                     "PixelCopy snapshot save failed: " + saveError.getMessage(),
L1511:                     nativeResult,
L1512:                     result);
L1513:         } finally {
L1514:             bitmap.recycle();
L1515:         }
L1516: 
L1517:         return "{\"success\":true,"
L1518:                 + "\"message\":\"snapshot saved by PixelCopy\","
L1519:                 + "\"outputPath\":\"" + escapeJson(outputFile.getAbsolutePath()) + "\","
L1520:                 + "\"width\":" + width + ","
L1521:                 + "\"height\":" + height + ","
L1522:                 + "\"format\":\"" + (format == Bitmap.CompressFormat.JPEG ? "jpg" : "png") + "\","
L1523:                 + "\"source\":\"pixelcopy\","
L1524:                 + "\"snapshotCaptureMode\":\"surface_pixelcopy\","
L1525:                 + "\"nativeSnapshot\":\"" + escapeJson(nativeResult) + "\"}";
L1526:     }
L1527: 
L1528:     private String pixelCopyErrorCode(int result) {
L1529:         if (result == PixelCopy.ERROR_SOURCE_NO_DATA) {
L1530:             return "SNAPSHOT_NO_FRAME";
```

## E15｜PNG buffering and JPEG encoding

### `ffmpegplayer/src/main/cpp/native/SnapshotManager.cpp:153–217`

```text
L153: std::string savePng(const std::string &outputPath,
L154:                     const std::vector<uint8_t> &rgba,
L155:                     int width,
L156:                     int height,
L157:                     int stride,
L158:                     int64_t ptsUs) {
L159:     const size_t rowBytes = static_cast<size_t>(width) * 4u;
L160:     std::vector<uint8_t> raw;
L161:     raw.reserve((rowBytes + 1u) * static_cast<size_t>(height));
L162:     for (int y = 0; y < height; ++y) {
L163:         raw.push_back(0); // filter: none
L164:         const uint8_t *row = rgba.data() + static_cast<size_t>(y) * static_cast<size_t>(stride);
L165:         raw.insert(raw.end(), row, row + rowBytes);
L166:     }
L167: 
L168:     std::vector<uint8_t> zlib;
L169:     zlib.reserve(raw.size() + raw.size() / 65535u * 5u + 16u);
L170:     zlib.push_back(0x78);
L171:     zlib.push_back(0x01);
L172:     size_t offset = 0;
L173:     while (offset < raw.size()) {
L174:         const uint16_t blockSize = static_cast<uint16_t>(std::min<size_t>(65535, raw.size() - offset));
L175:         const bool finalBlock = offset + blockSize >= raw.size();
L176:         zlib.push_back(finalBlock ? 0x01 : 0x00);
L177:         zlib.push_back(static_cast<uint8_t>(blockSize & 0xff));
L178:         zlib.push_back(static_cast<uint8_t>((blockSize >> 8) & 0xff));
L179:         const uint16_t nlen = static_cast<uint16_t>(~blockSize);
L180:         zlib.push_back(static_cast<uint8_t>(nlen & 0xff));
L181:         zlib.push_back(static_cast<uint8_t>((nlen >> 8) & 0xff));
L182:         zlib.insert(zlib.end(), raw.begin() + static_cast<std::ptrdiff_t>(offset), raw.begin() + static_cast<std::ptrdiff_t>(offset + blockSize));
L183:         offset += blockSize;
L184:     }
L185:     appendU32(zlib, adler32(raw));
L186: 
L187:     std::vector<uint8_t> png;
L188:     const uint8_t signature[] = {0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
L189:     png.insert(png.end(), std::begin(signature), std::end(signature));
L190: 
L191:     std::vector<uint8_t> ihdr;
L192:     appendU32(ihdr, static_cast<uint32_t>(width));
L193:     appendU32(ihdr, static_cast<uint32_t>(height));
L194:     ihdr.push_back(8); // bit depth
L195:     ihdr.push_back(6); // RGBA
L196:     ihdr.push_back(0); // compression
L197:     ihdr.push_back(0); // filter
L198:     ihdr.push_back(0); // interlace
L199:     appendChunk(png, "IHDR", ihdr);
L200:     appendChunk(png, "IDAT", zlib);
L201:     appendChunk(png, "IEND", {});
L202: 
L203:     const std::string writeError = writeFile(outputPath, png);
L204:     if (!writeError.empty()) {
L205:         return writeError;
L206:     }
L207:     return jsonSuccess(outputPath, width, height, ptsUs, "png");
L208: }
L209: 
L210: std::string saveJpeg(const std::string &outputPath,
L211:                      const std::vector<uint8_t> &rgba,
L212:                      int width,
L213:                      int height,
L214:                      int stride,
L215:                      int64_t ptsUs) {
L216:     const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_MJPEG);
L217:     if (codec == nullptr) {
```

### `ffmpegplayer/src/main/cpp/native/SnapshotManager.cpp:257–296`

```text
L257:     SwsContext *sws = sws_getContext(width, height, AV_PIX_FMT_RGBA,
L258:                                      width, height, codecContext->pix_fmt,
L259:                                      SWS_BILINEAR, nullptr, nullptr, nullptr);
L260:     if (sws == nullptr) {
L261:         av_frame_free(&frame);
L262:         av_packet_free(&packet);
L263:         avcodec_free_context(&codecContext);
L264:         return jsonError("SNAPSHOT_IO_ERROR", "sws_getContext failed for jpg snapshot");
L265:     }
L266: 
L267:     const uint8_t *srcData[] = {rgba.data(), nullptr, nullptr, nullptr};
L268:     const int srcLinesize[] = {stride, 0, 0, 0};
L269:     sws_scale(sws, srcData, srcLinesize, 0, height, frame->data, frame->linesize);
L270:     frame->pts = 0;
L271: 
L272:     result = avcodec_send_frame(codecContext, frame);
L273:     if (result >= 0) {
L274:         result = avcodec_receive_packet(codecContext, packet);
L275:     }
L276:     if (result < 0) {
L277:         sws_freeContext(sws);
L278:         av_frame_free(&frame);
L279:         av_packet_free(&packet);
L280:         avcodec_free_context(&codecContext);
L281:         return jsonError("SNAPSHOT_IO_ERROR", "failed to encode jpg snapshot");
L282:     }
L283: 
L284:     std::vector<uint8_t> bytes(packet->data, packet->data + packet->size);
L285:     const std::string writeError = writeFile(outputPath, bytes);
L286: 
L287:     sws_freeContext(sws);
L288:     av_frame_free(&frame);
L289:     av_packet_free(&packet);
L290:     avcodec_free_context(&codecContext);
L291: 
L292:     if (!writeError.empty()) {
L293:         return writeError;
L294:     }
L295:     return jsonSuccess(outputPath, width, height, ptsUs, "jpg");
L296: }
```

## E16｜PCM queue and Android audio contract

### `ffmpegplayer/src/main/cpp/native/NativePlayer.h:33–79`

```text
L33: // A3: bounded low-latency PCM queue decoupling the playback thread (producer)
L34: // from the audio output worker (consumer). PCM contract is fixed:
L35: // S16 / 48000 Hz / stereo / interleaved. The producer never blocks; overflow
L36: // drops the oldest blocks to keep the live edge.
L37: // 音频解码与设备输出之间的队列；积压时淘汰旧块，避免音频消费速度拖慢视频播放。
L38: class AudioPcmQueue {
L39: public:
L40:     struct Block {
L41:         std::vector<uint8_t> data;   // owned PCM bytes (S16/48k/stereo interleaved)
L42:         int64_t startPtsUs = 0;      // media start PTS of the block
L43:         int64_t sampleCount = 0;     // samples per channel in this block
L44:         // 所属音频时间线代次；重连或时间线重置后，消费者据此识别已取出的旧块。
L45:         int64_t generation = 0;      // discontinuity identity (reconnect/source change)
L46:     };
L47: 
L48:     void configure(int64_t targetDurationUs, int64_t maxDurationUs);
L49:     void enqueue(Block block);
L50:     bool waitAndDequeue(Block &out);
L51:     void flush();
L52:     void requestStop();
L53:     void resetForRestart();
L54:     void clearStats();
L55: 
L56:     int64_t durationUs() const;
L57:     int64_t blockCount() const;
L58:     int64_t byteCount() const;
L59:     int64_t dropCount() const;
L60:     int64_t droppedSampleCount() const;
L61:     int64_t flushCount() const;
L62:     int64_t highWatermarkUs() const;
L63: 
L64: private:
L65:     static int64_t blockDurationUs(const Block &block);
L66: 
L67:     mutable std::mutex mutex_;
L68:     std::condition_variable cv_;
L69:     std::deque<Block> blocks_;
L70:     // 队列内 PCM 的累计时长，单位微秒；仅在持有 mutex_ 时访问。
L71:     int64_t bufferedDurationUs_ = 0;
L72:     int64_t targetDurationUs_ = 150000;
L73:     int64_t maxDurationUs_ = 250000;
L74:     bool stopRequested_ = false;
L75:     int64_t dropCount_ = 0;
L76:     int64_t droppedSampleCount_ = 0;
L77:     int64_t flushCount_ = 0;
L78:     int64_t highWatermarkUs_ = 0;
L79: };
```

### `ffmpegplayer/src/main/java/com/example/motro/ffmpeg/LiveAudioPcmSink.java:11–125`

```text
L11: /**
L12:  * Owns the Android AudioTrack used for live PCM monitoring. All methods are
L13:  * invoked from the native audio output worker thread (onAudioPcm) or from the
L14:  * native lifecycle control path (onAudioControl). It does not depend on any
L15:  * Activity or View.
L16:  *
L17:  * Fixed contract (frozen by Audio Phase 1): S16 / 48000 Hz / stereo / interleaved.
L18:  */
L19: public final class LiveAudioPcmSink {
L20: 
L21:     private static final String TAG = "LiveAudioPcmSink";
L22: 
L23:     public static final int CMD_START = 0;
L24:     public static final int CMD_PAUSE_FLUSH = 1;
L25:     public static final int CMD_RELEASE = 2;
L26: 
L27:     // Native treats this as an expected lifecycle cancellation rather than an
L28:     // AudioTrack failure. It is intentionally outside Android's error range.
L29:     public static final int WRITE_CANCELLED = -10000;
L30: 
L31:     private static final int SAMPLE_RATE = 48000;
L32:     private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO;
L33:     private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
L34:     // 单个 PCM 块允许的重试时间窗口（纳秒），避免设备背压导致生命周期操作长期等待。
L35:     private static final long MAX_WRITE_WAIT_NANOS = 250_000_000L;
L36:     private static final long WRITE_RETRY_NANOS = 2_000_000L;
L37: 
L38:     // Accessed from the audio worker thread and the lifecycle control thread.
L39:     // Lifecycle commands never wait for a blocking write: writes are
L40:     // non-blocking and an epoch invalidates any in-flight old-generation block.
L41:     private volatile AudioTrack audioTrack;
L42:     private volatile boolean acceptingWrites;
L43:     // 生命周期代次；暂停、重新开始或释放后，在途写入据此取消旧块。
L44:     private final AtomicLong lifecycleEpoch = new AtomicLong();
L45:     private int minBufferBytes = -1;
L46: 
L47:     public LiveAudioPcmSink() {
L48:     }
L49: 
L50:     /**
L51:      * Called from the native audio output worker thread. Writes the PCM block
L52:      * without an unbounded AudioTrack wait and returns the number of bytes
L53:      * written, or a negative AudioTrack error code. Creates/starts AudioTrack
L54:      * lazily. A partial write is returned to native as an audio-only failure so
L55:      * the live pipeline can drop forward rather than block Stop/Release.
L56:      * The caller must keep {@code pcm} alive until this method returns.
L57:      */
L58:     // 消费原生提供的 S16/48kHz/双声道交错 PCM；返回写入字节数或负错误码，调用期间缓冲区必须有效。
L59:     public int onAudioPcm(ByteBuffer pcm, int sizeBytes, long ptsUs) {
L60:         final long epoch = lifecycleEpoch.get();
L61:         if (!acceptingWrites) {
L62:             return WRITE_CANCELLED;
L63:         }
L64:         AudioTrack track = ensureStarted();
L65:         if (track == null) {
L66:             return AudioTrack.ERROR_INVALID_OPERATION;
L67:         }
L68:         int total = 0;
L69:         final long deadlineNanos = System.nanoTime() + MAX_WRITE_WAIT_NANOS;
L70:         while (total < sizeBytes) {
L71:             if (!acceptingWrites || lifecycleEpoch.get() != epoch) {
L72:                 pauseFlushTrack(track);
L73:                 return WRITE_CANCELLED;
L74:             }
L75:             int written = track.write(pcm, sizeBytes - total, AudioTrack.WRITE_NON_BLOCKING);
L76:             if (written < 0) {
L77:                 return written;
L78:             }
L79:             if (written == 0) {
L80:                 if (System.nanoTime() >= deadlineNanos) {
L81:                     break;
L82:                 }
L83:                 // WRITE_NON_BLOCKING keeps lifecycle cancellation observable.
L84:                 // A short bounded retry window lets AudioTrack drain normally
L85:                 // without ever entering the platform's indefinite write wait.
L86:                 java.util.concurrent.locks.LockSupport.parkNanos(WRITE_RETRY_NANOS);
L87:                 continue;
L88:             }
L89:             total += written;
L90:         }
L91:         if (!acceptingWrites || lifecycleEpoch.get() != epoch) {
L92:             // A lifecycle reset may race immediately after the non-blocking
L93:             // write. Flush once more before returning so bytes from the old
L94:             // epoch cannot become audible after CMD_START.
L95:             pauseFlushTrack(track);
L96:             return WRITE_CANCELLED;
L97:         }
L98:         return total;
L99:     }
L100: 
L101:     /**
L102:      * Called from the native lifecycle control path. Returns 0 on success or a
L103:      * negative error code.
L104:      */
L105:     // 处理原生生命周期指令；开始仅开放写入，AudioTrack 在工作线程首次写入时延迟创建。
L106:     public int onAudioControl(int command) {
L107:         switch (command) {
L108:             case CMD_START:
L109:                 lifecycleEpoch.incrementAndGet();
L110:                 acceptingWrites = true;
L111:                 return 0; // Track remains lazy-created on the worker thread.
L112:             case CMD_PAUSE_FLUSH:
L113:                 acceptingWrites = false;
L114:                 lifecycleEpoch.incrementAndGet();
L115:                 pauseFlush();
L116:                 return 0;
L117:             case CMD_RELEASE:
L118:                 acceptingWrites = false;
L119:                 lifecycleEpoch.incrementAndGet();
L120:                 release();
L121:                 return 0;
L122:             default:
L123:                 return 0;
L124:         }
L125:     }
```

## E17｜Reconnect cleanup / recovery boundary

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:4689–4758`

```text
L4689: bool NativePlayer::reconnectInput(int readErrorCode) {
L4690:     if (!reconnectEnabled_.load() || !isNetworkUrl(url_)) {
L4691:         return false;
L4692:     }
L4693: 
L4694:     if (readErrorCode == AVERROR_EOF && !reconnectOnEof_.load()) {
L4695:         return false;
L4696:     }
L4697: 
L4698:     const bool infiniteRetry = infiniteReconnect_.load() || reconnectMaxRetryCount_.load() < 0;
L4699:     const int maxRetryCount = reconnectMaxRetryCount_.load();
L4700:     if (!infiniteRetry && maxRetryCount <= 0) {
L4701:         return false;
L4702:     }
L4703: 
L4704:     reconnectExhausted_.store(false);
L4705:     const std::string readError = ffmpegErrorToString(readErrorCode);
L4706:     const int64_t disconnectTimeMs = nowMs();
L4707:     lastDisconnectTimeMs_.store(disconnectTimeMs);
L4708:     lastReconnectErrorCode_.store(readErrorCode);
L4709:     reconnecting_.store(true);
L4710:     waitingSource_.store(false);
L4711:     setRendererFallbackReason(rendererState_, 0);
L4712:     {
L4713:         std::lock_guard<std::mutex> lock(mutex_);
L4714:         if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
L4715:             state_ = PlayerState::Disconnected;
L4716:             errorMessage_ = readError;
L4717:             lastReconnectError_ = readError;
L4718:         }
L4719:     }
L4720:     LOGE("playback disconnected url=%s error=%s, start reconnect", url_.c_str(), readError.c_str());
L4721:     notifyPlayerEvent("reconnect_disconnected",
L4722:                       PlayerState::Disconnected,
L4723:                       0,
L4724:                       infiniteRetry ? -1 : maxRetryCount,
L4725:                       0,
L4726:                       readErrorCode,
L4727:                       readError);
L4728:     if (remuxRecorder_.isRecording()) {
L4729:         LOGI("reconnect while recorder active; remux recorder keeps output context and resumes when packets return");
L4730:     }
L4731: 
L4732:     // Isolate the old audio generation immediately at disconnect. Do not let
L4733:     // queued/AudioTrack PCM play throughout reconnect delay/open retries.
L4734:     flushAudioPcmForDiscontinuity();
L4735:     // 等待有界的音频写入退出，禁止旧代次在新 AudioTrack 启动后再次 flush/更新时间线。
L4736:     audioPcmQueue_.requestStop();
L4737:     stopAudioOutputWorker();
L4738:     invalidateAudioClock();
L4739:     releaseFfmpegResources();
L4740:     resetRealtimeClock();
L4741: 
L4742:     int localAttempt = 0;
L4743:     while (!stopRequested_.load()) {
L4744:         ++localAttempt;
L4745:         const bool finiteRetryExhaustedBeforeAttempt = !infiniteRetry && localAttempt > maxRetryCount;
L4746:         if (finiteRetryExhaustedBeforeAttempt) {
L4747:             break;
L4748:         }
L4749: 
L4750:         const int retryDelayMs = reconnectDelayForAttempt(localAttempt);
L4751:         lastReconnectTimeMs_.store(nowMs());
L4752:         reconnectAttemptCount_.fetch_add(1);
L4753:         if (!waitingSource_.load()) {
L4754:             std::lock_guard<std::mutex> lock(mutex_);
L4755:             if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
L4756:                 state_ = PlayerState::Reconnecting;
L4757:                 errorMessage_ = readError;
L4758:             }
```

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:4794–4889`

```text
L4794:             lastReconnectSuccessTimeMs_.store(successTimeMs);
L4795:             {
L4796:                 std::lock_guard<std::mutex> lock(mutex_);
L4797:                 if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
L4798:                     state_ = pauseRequested_.load() ? PlayerState::Paused : PlayerState::Reconnected;
L4799:                     errorMessage_.clear();
L4800:                     lastReconnectError_.clear();
L4801:                 }
L4802:             }
L4803:             resetRealtimeClock();
L4804:             if (audioEnabled_.load() && !pauseRequested_.load()) {
L4805:                 startAudioSinkForCurrentGeneration();
L4806:                 startAudioOutputWorker();
L4807:             }
L4808:             if (audioEnabled_.load() && sourceHasAudio_.load() && audioDecodeOpened_.load()) {
L4809:                 audioReconnectRecoveryCount_.fetch_add(1);
L4810:             }
L4811:             recomputeAudioPlayable();
L4812:             beginStartupKeyFrameWait("reconnect");
L4813:             if (!startupKeyFrameWaitActive_.load()) {
L4814:                 std::lock_guard<std::mutex> lock(mutex_);
L4815:                 if (state_ == PlayerState::Reconnected && !pauseRequested_.load()) {
L4816:                     state_ = PlayerState::Playing;
L4817:                 }
L4818:             }
L4819:             LOGI("RTSP open success url=%s", url_.c_str());
L4820:             LOGI("reconnect success attempt=%d url=%s", localAttempt, url_.c_str());
L4821:             notifyPlayerEvent("reconnect_success",
L4822:                               PlayerState::Reconnected,
L4823:                               localAttempt,
L4824:                               infiniteRetry ? -1 : maxRetryCount,
L4825:                               0,
L4826:                               0,
L4827:                               "");
L4828:             return true;
L4829:         }
L4830: 
L4831:         lastReconnectErrorCode_.store(result);
L4832:         {
L4833:             std::lock_guard<std::mutex> lock(mutex_);
L4834:             lastReconnectError_ = error;
L4835:             errorMessage_ = error;
L4836:         }
L4837:         LOGE("reconnect failed attempt=%d/%d url=%s error=%s",
L4838:              localAttempt, infiniteRetry ? -1 : maxRetryCount, url_.c_str(), error.c_str());
L4839: 
L4840:         const bool sourceMissing = shouldTreatOpenErrorAsSourceMissing(error);
L4841:         if (sourceMissing && keepWaitingWhenSourceMissing_.load()) {
L4842:             waitingSource_.store(true);
L4843:             {
L4844:                 std::lock_guard<std::mutex> lock(mutex_);
L4845:                 if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
L4846:                     state_ = PlayerState::WaitingSource;
L4847:                     lastReconnectError_ = error;
L4848:                     errorMessage_ = error;
L4849:                 }
L4850:             }
L4851:             LOGI("playerState=WAITING_SOURCE reconnect attempt=%d lastError=%s", localAttempt, error.c_str());
L4852:             LOGI("keep waiting for source url=%s", url_.c_str());
L4853:             notifyPlayerEvent("waiting_source",
L4854:                               PlayerState::WaitingSource,
L4855:                               localAttempt,
L4856:                               infiniteRetry ? -1 : maxRetryCount,
L4857:                               retryDelayMs,
L4858:                               result,
L4859:                               error);
L4860:             continue;
L4861:         }
L4862: 
L4863:         if (!infiniteRetry && localAttempt >= maxRetryCount) {
L4864:             break;
L4865:         }
L4866:     }
L4867: 
L4868:     reconnecting_.store(false);
L4869:     waitingSource_.store(false);
L4870:     std::string finalError;
L4871:     {
L4872:         std::lock_guard<std::mutex> lock(mutex_);
L4873:         finalError = lastReconnectError_.empty() ? readError : lastReconnectError_;
L4874:     }
L4875:     if (stopRequested_.load()) {
L4876:         return false;
L4877:     }
L4878:     reconnectExhausted_.store(true);
L4879:     setState(PlayerState::Error, finalError);
L4880:     LOGE("reconnect exhausted url=%s error=%s", url_.c_str(), finalError.c_str());
L4881:     notifyPlayerEvent("reconnect_exhausted",
L4882:                       PlayerState::Error,
L4883:                       reconnectAttemptCount_.load(),
L4884:                       infiniteRetry ? -1 : maxRetryCount,
L4885:                       0,
L4886:                       lastReconnectErrorCode_.load(),
L4887:                       finalError);
L4888:     return false;
L4889: }
```

## E18｜Main demux-read, recording, pause and decode

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:4960–5012`

```text
L4960:         }
L4961: 
L4962:         if (audioResumeDiscontinuityRequested_.exchange(false)) {
L4963:             // Realtime Pause keeps draining compressed packets. Resume flushes
L4964:             // decoder reference state and waits for a fresh video keyframe;
L4965:             // audio starts from the current packet edge with a new generation.
L4966:             resetAudioDecoderForDiscontinuity("resume");
L4967:             audioFlushRequested_.store(false);
L4968:             resetRealtimeClock();
L4969:             if (isRealtimeInput_) {
L4970:                 if (videoCodecContext_ != nullptr) {
L4971:                     avcodec_flush_buffers(videoCodecContext_);
L4972:                 }
L4973:                 beginStartupKeyFrameWait("resume");
L4974:             }
L4975:         }
L4976: 
L4977:         if (audioFlushRequested_.exchange(false)) {
L4978:             resetAudioDecoderForDiscontinuity("audio_toggle");
L4979:         }
L4980: 
L4981:         if (pauseRequested_.load() && !realtimeInput) {
L4982:             std::this_thread::sleep_for(std::chrono::milliseconds(20));
L4983:             continue;
L4984:         }
L4985: 
L4986:         const int64_t readStartUs = steadyNowUs();
L4987:         if (realtimeInput) networkIoDeadline_.arm(readStartUs, readIoTimeoutUs_.load());
L4988:         const int readResult = finishNetworkIo(av_read_frame(formatContext_, packet_));
L4989:         const int64_t readCostUs = steadyNowUs() - readStartUs;
L4990:         recordCost(lastReadFrameCostUs_, totalReadFrameCostUs_, readFrameCostSampleCount_, maxReadFrameCostUs_,
L4991:                    readCostUs);
L4992:         const PreT0TimingTracker::ReadResultClass readClass = classifyReadResult(readResult);
L4993:         // LATENCY aggregates duration; BASIC keeps outcome-only health; OFF
L4994:         // is a no-op. The facade owns all PRET0 policy and bounded storage.
L4995:         diagnostics_.onRead(readCostUs, readClass);
L4996:         lastReadPacketTimeMs_.store(nowMs());
L4997:         if (readResult < 0) {
L4998:             if (transportSwitchRequested_.exchange(false)) {
L4999:                 if (!switchTransportInput()) {
L5000:                     break;
L5001:                 }
L5002:                 sessionReadPacketCount = 0;
L5003:                 continue;
L5004:             }
L5005: 
L5006:             const bool shouldReconnectEof = readResult == AVERROR_EOF
L5007:                                             && reconnectEnabled_.load()
L5008:                                             && reconnectOnEof_.load()
L5009:                                             && isNetworkUrl(url_);
L5010:             if (stopRequested_.load() || (readResult == AVERROR_EOF && !shouldReconnectEof)) {
L5011:                 break;
L5012:             }
```

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:5077–5177`

```text
L5077:             // LAT5: video packet return gap / PTS delta / burst detection.
L5078:             // monoUs is T0 (R1); invalid PTS never fabricates a delta.
L5079:             if (latencyDiagnostics) {
L5080:                 diagnostics_.onVideoPacketReturn(packetReadyMonoUs, packetPtsUsForPreT0);
L5081:             }
L5082:         } else if (packet_->stream_index == audioStreamIndex_) {
L5083:             audioPacketCount_.fetch_add(1);
L5084:             audioPacketBytes_.fetch_add(packetSize);
L5085:             lastAudioFrameTimeMs_.store(nowMs());
L5086:             if (formatContext_ != nullptr && audioStreamIndex_ >= 0 && packet_->pts != AV_NOPTS_VALUE) {
L5087:                 audioClockUs_.store(av_rescale_q(packet_->pts, formatContext_->streams[audioStreamIndex_]->time_base, AV_TIME_BASE_Q));
L5088:             }
L5089:         }
L5090: 
L5091:         // 先把压缩包交给录制器，再执行暂停和播放丢包策略，保留独立的录制路径。
L5092:         if (remuxRecorder_.isRecording()) {
L5093:             remuxRecorder_.onPacket(packet_);
L5094:         }
L5095: 
L5096:         if (pauseRequested_.load() || audioResumeDiscontinuityRequested_.load()) {
L5097:             // Realtime Pause deliberately keeps the demux/socket at live edge
L5098:             // and preserves the recorder's compressed packet path, but neither
L5099:             // decoder nor renderer receives paused packets.
L5100:             av_packet_unref(packet_);
L5101:             continue;
L5102:         }
L5103: 
L5104:         if (shouldDropRealtimePacket(packet_)) {
L5105:             av_packet_unref(packet_);
L5106:             continue;
L5107:         }
L5108: 
L5109:         if (isRealtimeInput_ && dropUntilKeyFrame_) {
L5110:             const int64_t waitElapsedMs = startupKeyFrameWait_ && startupKeyFrameWaitStartMs_ > 0
L5111:                                           ? nowMs() - startupKeyFrameWaitStartMs_
L5112:                                           : 0;
L5113:             if (startupKeyFrameWait_ && waitElapsedMs > kStartupKeyFrameWaitTimeoutMs) {
L5114:                 LOGE("first video keyframe wait timeout elapsedMs=%lld, allow decode from stream=%d key=%d",
L5115:                      static_cast<long long>(waitElapsedMs), packet_->stream_index,
L5116:                      (packet_->flags & AV_PKT_FLAG_KEY) ? 1 : 0);
L5117:                 finishStartupKeyFrameWait("timeout");
L5118:             }
L5119:         }
L5120: 
L5121:         if (isRealtimeInput_ && dropUntilKeyFrame_) {
L5122:             if (packet_->stream_index == videoStreamIndex_) {
L5123:                 if ((packet_->flags & AV_PKT_FLAG_KEY) == 0) {
L5124:                     droppedVideoPacketCount_.fetch_add(1);
L5125:                     packetDropBeforeDecodeCount_.fetch_add(1);
L5126:                     if (startupKeyFrameWait_) {
L5127:                         startupKeyFrameDroppedPacketCount_.fetch_add(1);
L5128:                     }
L5129:                     av_packet_unref(packet_);
L5130:                     continue;
L5131:                 }
L5132:                 LOGI("realtime keyframe received, resume decode pts=%lld startupWait=%d",
L5133:                      static_cast<long long>(packet_->pts), startupKeyFrameWait_ ? 1 : 0);
L5134:                 LOGI("first keyframe received pts=%lld", static_cast<long long>(packet_->pts));
L5135:                 finishStartupKeyFrameWait(startupKeyFrameWait_ ? "keyframe" : "catchup");
L5136:             } else {
L5137:                 av_packet_unref(packet_);
L5138:                 continue;
L5139:             }
L5140:         }
L5141: 
L5142:         if (packet_->stream_index == videoStreamIndex_) {
L5143:             const int64_t sendStartUs = steadyNowUs();
L5144:             int result = avcodec_send_packet(videoCodecContext_, packet_);
L5145:             lastSendPacketCostUs_.store(steadyNowUs() - sendStartUs);
L5146:             if (result < 0) {
L5147:                 const std::string error = ffmpegErrorToString(result);
L5148:                 LOGE("avcodec_send_packet error: %s", error.c_str());
L5149:                 av_packet_unref(packet_);
L5150:                 continue;
L5151:             }
L5152:             // LAT1 P1: packet accepted by the decoder (same packet PTS, media timeline us).
L5153:             if (diagnostics_.basicEnabled() && videoPacketPtsValid_.load()) {
L5154:                 const int64_t inputPtsUs = latestVideoPacketPtsUs_.load();
L5155:                 latestDecoderInputPtsUs_.store(inputPtsUs);
L5156:                 decoderInputPtsValid_.store(true);
L5157:                 updateMax(maxDecoderInputPtsUs_, inputPtsUs);
L5158:                 if (inputPtsUs < maxDecoderInputPtsUs_.load()) {
L5159:                     decoderPtsBackwardCount_.fetch_add(1);
L5160:                 }
L5161:                 // LAT2 T1: packet submitted to decoder (monotonic, same packet PTS).
L5162:                 if (diagnostics_.latencyEnabled()) {
L5163:                     recordVideoStageTiming(videoPtsGeneration_.load(), inputPtsUs,
L5164:                                            StageTimingPoint::DecoderSubmit, sendStartUs);
L5165:                 }
L5166:             } else if (diagnostics_.basicEnabled()) {
L5167:                 latestDecoderInputPtsUs_.store(-1);
L5168:                 decoderInputPtsValid_.store(false);
L5169:             }
L5170: 
L5171:             PlayerOptions optionsSnapshot;
L5172:             {
L5173:                 std::lock_guard<std::mutex> lock(mutex_);
L5174:                 optionsSnapshot = playerOptions_;
L5175:             }
L5176:             const bool latestFrameOnly = isRealtimeInput_ && optionsSnapshot.enableLatestFrameOnly && latestFrame_ != nullptr;
L5177:             bool hasLatestFrame = false;
```

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:5202–5268`

```text
L5202:                 // LAT1 P2: decoded frame output from the decoder (media timeline us).
L5203:                 if (diagnostics_.basicEnabled() && formatContext_ != nullptr && videoStreamIndex_ >= 0) {
L5204:                     bool framePtsValid = false;
L5205:                     if (decodedFrame_->best_effort_timestamp != AV_NOPTS_VALUE) {
L5206:                         const int64_t framePtsUs = rescaleToUs(decodedFrame_->best_effort_timestamp, formatContext_->streams[videoStreamIndex_]->time_base);
L5207:                         if (isValidPts(framePtsUs)) {
L5208:                             latestDecodedFramePtsUs_.store(framePtsUs);
L5209:                             framePtsValid = true;
L5210:                             updateMax(maxDecodedFramePtsUs_, framePtsUs);
L5211:                             if (framePtsUs < maxDecodedFramePtsUs_.load()) {
L5212:                                 decodedPtsBackwardCount_.fetch_add(1);
L5213:                             }
L5214:                             // LAT2 T2: decoded frame output (monotonic).
L5215:                             if (diagnostics_.latencyEnabled()) {
L5216:                                 recordVideoStageTiming(videoPtsGeneration_.load(), framePtsUs,
L5217:                                                        StageTimingPoint::DecodedOutput, steadyNowUs());
L5218:                             }
L5219:                         }
L5220:                     }
L5221:                     decodedFramePtsValid_.store(framePtsValid);
L5222:                     if (!framePtsValid) {
L5223:                         latestDecodedFramePtsUs_.store(-1);
L5224:                     }
L5225:                 }
L5226: 
L5227:                 if (latestFrameOnly && decodedFrame_->format != AV_PIX_FMT_MEDIACODEC) {
L5228:                     if (hasLatestFrame) {
L5229:                         droppedVideoFrameCount_.fetch_add(1);
L5230:                         frameDropBeforeRenderCount_.fetch_add(1);
L5231:                     }
L5232:                     av_frame_unref(latestFrame_);
L5233:                     av_frame_move_ref(latestFrame_, decodedFrame_);
L5234:                     hasLatestFrame = true;
L5235:                     continue;
L5236:                 }
L5237: 
L5238:                 processDecodedVideoFrame(decodedFrame_, receiveStartUs);
L5239:                 av_frame_unref(decodedFrame_);
L5240:                 if (frameDelayMs > 0) {
L5241:                     std::this_thread::sleep_for(std::chrono::milliseconds(frameDelayMs));
L5242:                 }
L5243:             }
L5244: 
L5245:             if (latestFrameOnly) {
L5246:                 if (hasLatestFrame && !stopRequested_.load()) {
L5247:                     processDecodedVideoFrame(latestFrame_, steadyNowUs());
L5248:                 }
L5249:                 av_frame_unref(latestFrame_);
L5250:             }
L5251:         } else if (packet_->stream_index == audioStreamIndex_) {
L5252:             // A1: decode compressed audio packets into decoded Audio AVFrames
L5253:             // on the playback thread, then discard them (no PCM yet). The
L5254:             // recorder already received the original packet earlier in the loop.
L5255:             decodeAudioPacket(packet_);
L5256:         }
L5257: 
L5258:         renderOesPendingFrameIfReady();
L5259: 
L5260:         av_packet_unref(packet_);
L5261:     }
L5262: 
L5263:     std::lock_guard<std::mutex> lock(mutex_);
L5264:     if (state_ != PlayerState::Error && state_ != PlayerState::Released) {
L5265:         state_ = PlayerState::Stopped;
L5266:     }
L5267:     LOGI("playback thread ended player=%p", this);
L5268: }
```

## E19｜IO deadline in player; probe path lacks equivalent guard

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:713–727`

```text
L713:         std::lock_guard<std::mutex> lock(mutex_);
L714:         sourceType_ = sourceType;
L715:         optionsSnapshot = playerOptions_;
L716:         preferUdpInAuto = preferUdpTransport_.load();
L717:     }
L718: 
L719:     avformat_network_init();
L720:     formatContext_ = avformat_alloc_context();
L721:     if (formatContext_ == nullptr) {
L722:         errorMessage = "avformat_alloc_context failed";
L723:         return -1;
L724:     }
L725:     formatContext_->interrupt_callback.callback = NativePlayer::interruptCallback;
L726:     formatContext_->interrupt_callback.opaque = this;
L727:     if (isRtspSource(sourceType)) {
```

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:770–829`

```text
L770:              formatContext_->max_delay, formatContext_->max_probe_packets, formatContext_->flags);
L771:     } else if (isNetworkUrl(url)) {
L772:         const int64_t timeoutUs = static_cast<int64_t>(std::max(timeoutMs, 1)) * 1000;
L773:         const std::string timeoutValue = std::to_string(timeoutUs);
L774:         av_dict_set(&options, "stimeout", timeoutValue.c_str(), 0);
L775:         av_dict_set(&options, "timeout", timeoutValue.c_str(), 0);
L776:         av_dict_set(&options, "rw_timeout", timeoutValue.c_str(), 0);
L777:     }
L778: 
L779:     const int64_t openTimeoutUs = isRtspSource(sourceType) ? optionsSnapshot.openTimeoutUs
L780:                                   : static_cast<int64_t>(std::max(timeoutMs, 1)) * 1000;
L781:     readIoTimeoutUs_.store(isRtspSource(sourceType) ? optionsSnapshot.readTimeoutUs : openTimeoutUs);
L782:     if (isNetworkUrl(url)) networkIoDeadline_.arm(steadyNowUs(), openTimeoutUs);
L783:     int result = finishNetworkIo(avformat_open_input(&formatContext_, url.c_str(), nullptr, &options));
L784:     AVDictionaryEntry *unusedOption = nullptr;
L785:     while ((unusedOption = av_dict_get(options, "", unusedOption, AV_DICT_IGNORE_SUFFIX)) != nullptr) {
L786:         LOGI("unused FFmpeg open option %s=%s", unusedOption->key, unusedOption->value);
L787:     }
L788:     av_dict_free(&options);
L789:     if (result >= 0 && isRtspSource(sourceType)) {
L790:         // LAT5: read back the effective demuxer buffering value after open.
L791:         // configuredMaxDelayUs comes from PlayerOptions; this is the value the
L792:         // RTSP demuxer actually carries (us).
L793:         effectiveFmtCtxMaxDelayUs_.store(formatContext_->max_delay);
L794:         LOGI("RTSP effective max_delay=%d max_probe_packets=%d",
L795:              formatContext_->max_delay, formatContext_->max_probe_packets);
L796:     }
L797:     if (result < 0) {
L798:         errorMessage = ffmpegErrorToString(result);
L799:         LOGE("RTSP open failed url=%s error=%s", url.c_str(), errorMessage.c_str());
L800:         releaseFfmpegResources();
L801:         return result;
L802:     }
L803:     LOGI("open input success sourceType=%s url=%s", sourceTypeName(sourceType).c_str(), url.c_str());
L804:     const int64_t inputOpenCount = inputOpenCount_.fetch_add(1) + 1;
L805:     LOGI("input session opened count=%lld sourceType=%s",
L806:          static_cast<long long>(inputOpenCount), sourceTypeName(sourceType).c_str());
L807: 
L808:     if (isNetworkUrl(url)) networkIoDeadline_.arm(steadyNowUs(), openTimeoutUs);
L809:     result = finishNetworkIo(avformat_find_stream_info(formatContext_, nullptr));
L810:     if (result < 0) {
L811:         errorMessage = ffmpegErrorToString(result);
L812:         LOGE("avformat_find_stream_info failed: %s", errorMessage.c_str());
L813:         releaseFfmpegResources();
L814:         return result;
L815:     }
L816:     streamBitRate_.store(std::max<int64_t>(0, formatContext_->bit_rate));
L817: 
L818:     int selectedVideoStreamIndex = -1;
L819:     int selectedAudioStreamIndex = -1;
L820:     int selectedVideoWidth = 0;
L821:     int selectedVideoHeight = 0;
L822:     int selectedAudioSampleRate = 0;
L823:     int selectedAudioChannels = 0;
L824:     int selectedAudioSampleFormat = -1;
L825:     double selectedFps = 25.0;
L826:     std::string selectedVideoCodec;
L827:     std::string selectedAudioCodec;
L828:     std::string selectedAudioSampleFormatName;
L829:     int64_t selectedVideoBitRate = 0;
```

### `ffmpegplayer/src/main/cpp/native/NetworkIoDeadline.h:1–36`

```text
L1: #pragma once
L2: 
L3: #include <algorithm>
L4: #include <atomic>
L5: #include <cstdint>
L6: #include <limits>
L7: 
L8: // 单调时钟截止时间。由输入所属线程 arm/clear，FFmpeg 回调只读取原子值。
L9: class NetworkIoDeadline {
L10: public:
L11:     void arm(int64_t nowUs, int64_t timeoutUs) {
L12:         const int64_t duration = std::max<int64_t>(1, timeoutUs);
L13:         deadlineUs_.store(nowUs > std::numeric_limits<int64_t>::max() - duration
L14:                           ? std::numeric_limits<int64_t>::max() : nowUs + duration);
L15:     }
L16:     void clear() { deadlineUs_.store(0); }
L17:     bool expired(int64_t nowUs) const {
L18:         const int64_t deadline = deadlineUs_.load();
L19:         return deadline > 0 && nowUs >= deadline;
L20:     }
L21: private:
L22:     std::atomic<int64_t> deadlineUs_{0};
L23: };
L24: 
L25: inline int reconnectBackoffMs(int attempt, int initialMs, int maximumMs) {
L26:     const int64_t initial = std::max(100, initialMs);
L27:     const int64_t maximum = std::max<int64_t>(initial, maximumMs);
L28:     int64_t delay = initial;
L29:     for (int i = 1; i < attempt && delay < maximum; ++i) delay = std::min(delay * 2, maximum);
L30:     return static_cast<int>(delay);
L31: }
L32: 
L33: inline bool waitForInitialRtsp404(bool rtsp, bool notFound, bool enabled,
L34:                                  bool reconnect404, bool keepWaiting, bool retryAllowed) {
L35:     return rtsp && notFound && enabled && reconnect404 && keepWaiting && retryAllowed;
L36: }
```

### `ffmpegplayer/src/main/cpp/native-ffmpeg-jni.cpp:203–230`

```text
L203: std::string probeUrl(const std::string &url, int timeoutMs) {
L204:     if (url.empty()) {
L205:         return jsonError(-1, "url is empty");
L206:     }
L207: 
L208:     AVFormatContext *formatContext = nullptr;
L209:     AVDictionary *options = nullptr;
L210:     const int64_t timeoutUs = static_cast<int64_t>(std::max(timeoutMs, 1)) * 1000;
L211:     const std::string timeoutValue = std::to_string(timeoutUs);
L212: 
L213:     avformat_network_init();
L214:     av_dict_set(&options, "rtsp_transport", "tcp", 0);
L215:     av_dict_set(&options, "stimeout", timeoutValue.c_str(), 0);
L216:     av_dict_set(&options, "timeout", timeoutValue.c_str(), 0);
L217: 
L218:     int result = avformat_open_input(&formatContext, url.c_str(), nullptr, &options);
L219:     av_dict_free(&options);
L220:     if (result < 0) {
L221:         const std::string error = ffmpegErrorToString(result);
L222:         LOGE("probe open failed url=%s error=%s", url.c_str(), error.c_str());
L223:         return jsonError(result, error);
L224:     }
L225: 
L226:     result = avformat_find_stream_info(formatContext, nullptr);
L227:     if (result < 0) {
L228:         const std::string error = ffmpegErrorToString(result);
L229:         avformat_close_input(&formatContext);
L230:         return jsonError(result, error);
```

## E20｜Defaults, profiles and supported options

### `ffmpegplayer/src/main/cpp/native/PlayerOptions.h:44–97`

```text
L44: // 播放器配置与实际解码结果；带 Us 的时长为微秒，带 Ms 的重连间隔为毫秒。
L45: struct PlayerOptions {
L46:     RtspTransport rtspTransport = RtspTransport::TCP;
L47:     LatencyMode latencyMode = LatencyMode::BALANCED;
L48:     bool enableHardwareDecode = false;
L49:     RenderMode renderMode = RenderMode::SOFTWARE_RGBA;
L50:     // 硬件解码初始化失败时是否允许改用软件解码。
L51:     bool hardwareDecodeAllowFallback = true;
L52: 
L53:     bool infiniteReconnect = true;
L54:     bool reconnectOnEof = true;
L55:     bool reconnectOn404 = true;
L56:     bool keepWaitingWhenSourceMissing = true;
L57:     int reconnectInitialDelayMs = 1000;
L58:     int reconnectMaxDelayMs = 5000;
L59:     // 重试次数上限；负值配合无限重连策略表示持续等待源恢复。
L60:     int reconnectMaxRetry = -1;
L61: 
L62:     std::string requestedDecoderName;
L63:     std::string actualDecoderName;
L64:     bool usingHardwareDecoder = false;
L65:     bool hardwareDecodeFallbackUsed = false;
L66:     std::string hardwareDecodeError;
L67: 
L68:     int64_t openTimeoutUs = 5000000;
L69:     int64_t readTimeoutUs = 5000000;
L70: 
L71:     // 探测输入格式使用的字节预算，减小可缩短探测但可能影响流信息识别。
L72:     int64_t probesize = 131072;
L73:     // 流分析时长预算，单位微秒。
L74:     int64_t analyzeduration = 200000;
L75:     int maxProbePackets = 128;
L76: 
L77:     int64_t maxDelayUs = 200000;
L78:     // RTP 重排序队列大小；负值时不显式传递该选项，沿用 FFmpeg 默认行为。
L79:     int reorderQueueSize = -1;
L80:     int socketBufferSize = 262144;
L81: 
L82:     bool fflagsNoBuffer = false;
L83:     bool avioDirect = false;
L84:     bool lowDelayDecode = false;
L85:     bool tcpNoDelay = true;
L86:     bool enableFrameDrop = true;
L87:     bool enablePacketDrop = false;
L88:     bool enableLatestFrameOnly = false;
L89:     bool skipNonRef = false;
L90: 
L91:     int decoderThreadCount = 1;
L92:     int64_t dropLateFrameThresholdUs = 500000;
L93:     int64_t dropLatePacketThresholdUs = 500000;
L94:     // RGBA 截图缓存的更新采样间隔，用于平衡缓存新鲜度和复制开销。
L95:     int cacheLastFrameEveryN = 1;
L96:     SyncMaster syncMaster = SyncMaster::AUDIO;
L97: };
```

### `ffmpegplayer/src/main/cpp/native/PlayerOptions.cpp:295–411`

```text
L295: void applyLatencyProfile(PlayerOptions &options) {
L296:     const RtspTransport transport = options.rtspTransport;
L297:     const LatencyMode mode = options.latencyMode;
L298:     const bool enableHardwareDecode = options.enableHardwareDecode;
L299:     const RenderMode renderMode = options.renderMode;
L300:     const bool hardwareDecodeAllowFallback = options.hardwareDecodeAllowFallback;
L301:     const std::string requestedDecoderName = options.requestedDecoderName;
L302:     const std::string actualDecoderName = options.actualDecoderName;
L303:     const bool usingHardwareDecoder = options.usingHardwareDecoder;
L304:     const bool hardwareDecodeFallbackUsed = options.hardwareDecodeFallbackUsed;
L305:     const std::string hardwareDecodeError = options.hardwareDecodeError;
L306:     const bool infiniteReconnect = options.infiniteReconnect;
L307:     const bool reconnectOnEof = options.reconnectOnEof;
L308:     const bool reconnectOn404 = options.reconnectOn404;
L309:     const bool keepWaitingWhenSourceMissing = options.keepWaitingWhenSourceMissing;
L310:     const int reconnectInitialDelayMs = options.reconnectInitialDelayMs;
L311:     const int reconnectMaxDelayMs = options.reconnectMaxDelayMs;
L312:     const int reconnectMaxRetry = options.reconnectMaxRetry;
L313:     options = PlayerOptions{};
L314:     options.rtspTransport = transport;
L315:     options.latencyMode = mode;
L316:     options.enableHardwareDecode = enableHardwareDecode;
L317:     options.renderMode = renderMode;
L318:     options.hardwareDecodeAllowFallback = hardwareDecodeAllowFallback;
L319:     options.requestedDecoderName = requestedDecoderName;
L320:     options.actualDecoderName = actualDecoderName;
L321:     options.usingHardwareDecoder = usingHardwareDecoder;
L322:     options.hardwareDecodeFallbackUsed = hardwareDecodeFallbackUsed;
L323:     options.hardwareDecodeError = hardwareDecodeError;
L324:     options.infiniteReconnect = infiniteReconnect;
L325:     options.reconnectOnEof = reconnectOnEof;
L326:     options.reconnectOn404 = reconnectOn404;
L327:     options.keepWaitingWhenSourceMissing = keepWaitingWhenSourceMissing;
L328:     options.reconnectInitialDelayMs = reconnectInitialDelayMs;
L329:     options.reconnectMaxDelayMs = reconnectMaxDelayMs;
L330:     options.reconnectMaxRetry = reconnectMaxRetry;
L331: 
L332:     const bool udp = transport == RtspTransport::UDP || transport == RtspTransport::UDP_MULTICAST;
L333: 
L334:     if (mode == LatencyMode::ULTRA_LOW_LATENCY) {
L335:         options.openTimeoutUs = 3000000;
L336:         options.readTimeoutUs = 3000000;
L337:         options.probesize = 32768;
L338:         options.analyzeduration = 0;
L339:         options.maxProbePackets = 32;
L340:         options.maxDelayUs = 0;
L341:         options.reorderQueueSize = udp ? 0 : -1;
L342:         options.socketBufferSize = 262144;
L343:         options.fflagsNoBuffer = true;
L344:         options.avioDirect = true;
L345:         options.lowDelayDecode = true;
L346:         options.tcpNoDelay = true;
L347:         options.enableFrameDrop = true;
L348:         options.enablePacketDrop = true;
L349:         options.enableLatestFrameOnly = true;
L350:         options.decoderThreadCount = 1;
L351:         options.dropLateFrameThresholdUs = 80000;
L352:         options.dropLatePacketThresholdUs = 80000;
L353:         options.syncMaster = SyncMaster::VIDEO;
L354:         options.skipNonRef = false;
L355:         options.cacheLastFrameEveryN = 1;
L356:         return;
L357:     }
L358: 
L359:     if (mode == LatencyMode::LOW_LATENCY) {
L360:         options.openTimeoutUs = 3000000;
L361:         options.readTimeoutUs = 3000000;
L362:         options.probesize = 32768;
L363:         options.analyzeduration = 0;
L364:         options.maxProbePackets = 32;
L365:         options.maxDelayUs = 0;
L366:         options.reorderQueueSize = udp ? 0 : -1;
L367:         options.socketBufferSize = 102400;
L368:         options.fflagsNoBuffer = true;
L369:         options.avioDirect = true;
L370:         options.lowDelayDecode = true;
L371:         options.tcpNoDelay = true;
L372:         options.enableFrameDrop = true;
L373:         options.decoderThreadCount = 1;
L374:         options.dropLateFrameThresholdUs = udp ? 150000 : 200000;
L375:         return;
L376:     }
L377: 
L378:     if (mode == LatencyMode::BALANCED) {
L379:         options.openTimeoutUs = 5000000;
L380:         options.readTimeoutUs = 5000000;
L381:         options.probesize = 131072;
L382:         options.analyzeduration = udp ? 100000 : 200000;
L383:         options.maxProbePackets = 128;
L384:         options.maxDelayUs = udp ? 100000 : 200000;
L385:         options.reorderQueueSize = udp ? 4 : -1;
L386:         options.socketBufferSize = 262144;
L387:         options.fflagsNoBuffer = udp;
L388:         options.avioDirect = false;
L389:         options.lowDelayDecode = true;
L390:         options.tcpNoDelay = true;
L391:         options.enableFrameDrop = true;
L392:         options.decoderThreadCount = 1;
L393:         options.dropLateFrameThresholdUs = udp ? 300000 : 500000;
L394:         return;
L395:     }
L396: 
L397:     options.openTimeoutUs = 8000000;
L398:     options.readTimeoutUs = 8000000;
L399:     options.probesize = 500000;
L400:     options.analyzeduration = 500000;
L401:     options.maxProbePackets = 512;
L402:     options.maxDelayUs = 500000;
L403:     options.reorderQueueSize = udp ? 16 : -1;
L404:     options.socketBufferSize = 1048576;
L405:     options.fflagsNoBuffer = false;
L406:     options.avioDirect = false;
L407:     options.lowDelayDecode = false;
L408:     options.tcpNoDelay = false;
L409:     options.enableFrameDrop = false;
L410:     options.decoderThreadCount = 0;
L411:     options.dropLateFrameThresholdUs = 1000000;
```

### `ffmpegplayer/src/main/cpp/native/PlayerOptions.cpp:682–708`

```text
L682:     if (normalizedKey == "fflags_nobuffer") {
L683:         if (!parseBool(value, parsedBool)) {
L684:             errorMessage = "fflags_nobuffer must be boolean";
L685:             return false;
L686:         }
L687:         options.fflagsNoBuffer = parsedBool;
L688:         return true;
L689:     }
L690:     if (normalizedKey == "avio_direct") {
L691:         if (!parseBool(value, parsedBool)) {
L692:             errorMessage = "avio_direct must be boolean";
L693:             return false;
L694:         }
L695:         options.avioDirect = parsedBool;
L696:         return true;
L697:     }
L698:     if (normalizedKey == "skip_non_ref") {
L699:         if (!parseBool(value, parsedBool)) {
L700:             errorMessage = "skip_non_ref must be boolean";
L701:             return false;
L702:         }
L703:         options.skipNonRef = parsedBool;
L704:         return true;
L705:     }
L706: 
L707:     errorMessage = "unknown player option: " + key;
L708:     return false;
```

## E21｜Demo worker action reads/writes View controls

### `app/src/main/java/com/example/motro/MediaPlayerActivity.java:281–316`

```text
L281: 
L282:         findViewById(R.id.infoButton).setOnClickListener(v -> runNative("FFmpeg Info", () ->
L283:                 "version=" + FFmpegNative.getFFmpegVersion()
L284:                         + "\nbuildConfig=" + FFmpegNative.getFFmpegBuildConfig()
L285:                         + "\ndecoders=" + FFmpegNative.getAvailableDecoders()
L286:                         + "\nmediaCodec=" + FFmpegNative.getMediaCodecInfo()));
L287:         findViewById(R.id.infoButton).setOnLongClickListener(v -> {
L288:             runNative("Player Lifetime Stress", () -> FFmpegNative.runDebugCommand(
L289:                     new String[]{"-player-lifetime-stress"}));
L290:             return true;
L291:         });
L292: 
L293:         findViewById(R.id.prepareButton).setOnClickListener(v -> runNative("Prepare", () -> {
L294:             FFmpegPlayer p = ensurePlayer();
L295:             if (p == null) {
L296:                 return jsonError("player is not ready");
L297:             }
L298:             String surfaceResult = bindSurfaceIfReady(p);
L299:             String transportResult = applyRtspTransport(p);
L300:             String latencyResult = applyLatencyMode(p);
L301:             String diagnosticsResult = applyDiagnosticsMode(p);
L302:             String reconnectResult = applyReconnectOptions(p);
L303:             String audioResult = applyAudioOption(p);
L304:             String decodeResult = applyDecodeModeOption(p);
L305:             String thermalResult = applyThermalOptionsToPlayer(p);
L306:             String prepareResult = p.prepare(requireUrl(), readTimeoutMs());
L307:             return "surface=" + surfaceResult
L308:                     + "\ntransport=" + transportResult
L309:                     + "\nlatency=" + latencyResult
L310:                     + "\ndiagnostics=" + diagnosticsResult
L311:                     + "\nreconnect=" + reconnectResult
L312:                     + "\naudio=" + audioResult
L313:                     + "\ndecode=" + decodeResult
L314:                     + "\nthermal=" + thermalResult
L315:                     + "\nprepare=" + prepareResult;
L316:         }));
```

### `app/src/main/java/com/example/motro/MediaPlayerActivity.java:346–360`

```text
L346:         findViewById(R.id.snapshotButton).setOnClickListener(v -> runNative("Snapshot", () ->
L347:                 takePlayerSnapshotCompat(requirePlayer(), requireSnapshotPath())));
L348: 
L349:         findViewById(R.id.startRecordButton).setOnClickListener(v -> runNative("Start Record", () ->
L350:                 requirePlayer().startRecord(requireRecordPath())));
L351: 
L352:         findViewById(R.id.startSegmentRecordButton).setOnClickListener(v -> runNative("Start Segment Record", () ->
L353:                 requirePlayer().startRecordWithConfig(requireSegmentPattern(), requireRecordFormat(), requireSegmentDurationSec())));
L354: 
L355:         findViewById(R.id.stopRecordButton).setOnClickListener(v -> runNative("Stop Record", () ->
L356:                 requirePlayer().stopRecord()));
L357: 
L358:         findViewById(R.id.recordStateButton).setOnClickListener(v -> runNative("Record State", () ->
L359:                 requirePlayer().getRecordState()));
L360: 
```

### `app/src/main/java/com/example/motro/MediaPlayerActivity.java:1218–1275`

```text
L1218:     private String applyAudioOption(FFmpegPlayer player) {
L1219:         if (player == null) {
L1220:             return jsonError("player is not ready");
L1221:         }
L1222:         return player.setAudioEnabled(audioSwitch.isChecked());
L1223:     }
L1224: 
L1225:     private String applyReconnectOptions(FFmpegPlayer player) {
L1226:         if (player == null) {
L1227:             return jsonError("player is not ready");
L1228:         }
L1229:         return player.setReconnectOptions(reconnectSwitch.isChecked(), -1, 1000);
L1230:     }
L1231: 
L1232:     private String applyRtspTransport(FFmpegPlayer player) {
L1233:         if (player == null) {
L1234:             return jsonError("player is not ready");
L1235:         }
L1236:         return player.setRtspTransport(selectedRtspTransport());
L1237:     }
L1238: 
L1239:     private String applyLatencyMode(FFmpegPlayer player) {
L1240:         if (player == null) {
L1241:             return jsonError("player is not ready");
L1242:         }
L1243:         return player.setLatencyMode(selectedLatencyMode());
L1244:     }
L1245: 
L1246:     private String applyDiagnosticsMode(FFmpegPlayer player) {
L1247:         if (player == null) {
L1248:             return jsonError("player is not ready");
L1249:         }
L1250:         return player.setPlayerOption("diagnostics_mode", selectedDiagnosticsMode());
L1251:     }
L1252: 
L1253:     private String applyDecodeModeOption(FFmpegPlayer player) {
L1254:         if (player == null) {
L1255:             return jsonError("player is not ready");
L1256:         }
L1257:         boolean hardwareDecode = "mediacodec_oes".equals(intentRenderMode)
L1258:                 || "mediacodec_nv12_gl".equals(intentRenderMode)
L1259:                 || hardwareDecodeSwitch.isChecked();
L1260:         String decodeResult = player.setHardwareDecodeEnabled(hardwareDecode);
L1261:         // setHardwareDecode(false) may reset software render mode to software_rgba,
L1262:         // so apply the explicit render mode afterwards.
L1263:         String renderMode;
L1264:         if ("mediacodec_oes".equals(intentRenderMode)) {
L1265:             renderMode = "mediacodec_oes";
L1266:         } else if ("mediacodec_surface".equals(intentRenderMode)) {
L1267:             renderMode = "mediacodec_surface";
L1268:         } else {
L1269:             // Revised Phase 2 main path: Hardware Decode ON -> NV12 GL.
L1270:             renderMode = hardwareDecode ? "mediacodec_nv12_gl" : "software_yuv_gl";
L1271:         }
L1272:         String renderModeResult = player.setHardwareRenderMode(renderMode);
L1273:         currentRenderMode = renderMode;
L1274:         return "hardwareDecode=" + decodeResult
L1275:                 + "\nrenderMode=" + renderModeResult;
```

### `app/src/main/java/com/example/motro/MediaPlayerActivity.java:1314–1382`

```text
L1314:     private String requireUrl() {
L1315:         String url = controlsBinding.urlEditText.getText().toString().trim();
L1316:         if (TextUtils.isEmpty(url)) {
L1317:             throw new IllegalArgumentException("Please enter RTSP/URL");
L1318:         }
L1319:         return url;
L1320:     }
L1321: 
L1322:     private int readTimeoutMs() {
L1323:         String value = controlsBinding.timeoutEditText.getText().toString().trim();
L1324:         if (TextUtils.isEmpty(value)) {
L1325:             return DEFAULT_TIMEOUT_MS;
L1326:         }
L1327:         try {
L1328:             return Math.max(1, Integer.parseInt(value));
L1329:         } catch (NumberFormatException e) {
L1330:             throw new IllegalArgumentException("timeoutMs must be a number");
L1331:         }
L1332:     }
L1333: 
L1334:     private String requireRecordPath() {
L1335:         String path = controlsBinding.recordPathEditText.getText().toString().trim();
L1336:         if (TextUtils.isEmpty(path)) {
L1337:             path = defaultFilePath("record_av_test.ts");
L1338:             controlsBinding.recordPathEditText.setText(path);
L1339:         }
L1340:         ensureParentExists(path);
L1341:         return path;
L1342:     }
L1343: 
L1344:     private String requireSegmentPattern() {
L1345:         String pattern = controlsBinding.segmentPatternEditText.getText().toString().trim();
L1346:         if (TextUtils.isEmpty(pattern)) {
L1347:             pattern = defaultFilePath("record_segment_%03d.ts");
L1348:             controlsBinding.segmentPatternEditText.setText(pattern);
L1349:         }
L1350:         ensureParentExists(pattern.replace("%03d", "000"));
L1351:         return pattern;
L1352:     }
L1353: 
L1354:     private String requireRecordFormat() {
L1355:         String format = controlsBinding.recordFormatEditText.getText().toString().trim();
L1356:         if (TextUtils.isEmpty(format)) {
L1357:             return "auto";
L1358:         }
L1359:         return format;
L1360:     }
L1361: 
L1362:     private int requireSegmentDurationSec() {
L1363:         String value = segmentDurationEditText.getText().toString().trim();
L1364:         if (TextUtils.isEmpty(value)) {
L1365:             return DEFAULT_SEGMENT_SECONDS;
L1366:         }
L1367:         try {
L1368:             return Math.max(1, Integer.parseInt(value));
L1369:         } catch (NumberFormatException e) {
L1370:             throw new IllegalArgumentException("segment duration must be a number");
L1371:         }
L1372:     }
L1373: 
L1374:     private String requireSnapshotPath() {
L1375:         String path = snapshotPathEditText.getText().toString().trim();
L1376:         if (TextUtils.isEmpty(path)) {
L1377:             path = defaultFilePath("snapshot.png");
L1378:             snapshotPathEditText.setText(path);
L1379:         }
L1380:         ensureParentExists(path);
L1381:         return path;
L1382:     }
```

### `app/src/main/java/com/example/motro/MediaPlayerActivity.java:1585–1607`

```text
L1585:     // 将可能阻塞的播放器操作提交给工作线程，再把结果投递回界面线程。
L1586:     private void runNative(String title, NativeAction action) {
L1587:         if (destroyed || worker == null) {
L1588:             return;
L1589:         }
L1590:         hideKeyboard();
L1591:         logDebug(">>> " + title);
L1592:         worker.execute(() -> {
L1593:             String result;
L1594:             try {
L1595:                 result = action.run();
L1596:             } catch (Throwable t) {
L1597:                 Log.e(TAG, title + " failed", t);
L1598:                 result = jsonError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
L1599:             }
L1600:             String finalResult = result;
L1601:             mainHandler.post(() -> {
L1602:                 logDebug(title + "\n" + finalResult);
L1603:                 if (!destroyed && finalResult.contains("\"success\":false")) {
L1604:                     Toast.makeText(this, title + " failed", Toast.LENGTH_SHORT).show();
L1605:                 }
L1606:             });
L1607:         });
```

## E22｜Demo single worker, stats and teardown

### `app/src/main/java/com/example/motro/MediaPlayerActivity.java:128–151`

```text
L128:     @Override
L129:     protected void onCreate(@Nullable Bundle savedInstanceState) {
L130:         super.onCreate(savedInstanceState);
L131:         binding = ActivityMediaPlayerBinding.inflate(getLayoutInflater());
L132:         setContentView(binding.getRoot());
L133:         controlsBinding = binding.playerControlPanel;
L134:         worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "FFmpegDemoWorker"));
L135:         bindViews();
L136:         initDefaults();
L137:         bindPreviewCallback();
L138:         bindActions();
L139:         startPlaybackInfoUpdates();
L140:         logDebug("Demo ready. Tap Create/Info/Prepare to load FFmpeg native libraries.");
L141:     }
L142: 
L143:     private void bindViews() {
L144:         binding.playerPreviewView.setKeepScreenOn(true);
L145: 
L146: 
L147:         segmentDurationEditText = findViewById(R.id.segmentDurationEditText);
L148:         snapshotPathEditText = findViewById(R.id.snapshotPathEditText);
L149:         audioSwitch = findViewById(R.id.audioSwitch);
L150:         reconnectSwitch = findViewById(R.id.reconnectSwitch);
L151:         hardwareDecodeSwitch = findViewById(R.id.hardwareDecodeSwitch);
```

### `app/src/main/java/com/example/motro/MediaPlayerActivity.java:1660–1692`

```text
L1660:             return "";
L1661:         }
L1662:         return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
L1663:     }
L1664: 
L1665:     @Override
L1666:     protected void onDestroy() {
L1667:         destroyed = true;
L1668:         mainHandler.removeCallbacks(playbackInfoRunnable);
L1669:         playbackInfoRequestInFlight.set(false);
L1670:         FFmpegPlayer p = takePlayer();
L1671:         ExecutorService releaseWorker = worker;
L1672:         worker = null;
L1673:         if (releaseWorker != null) {
L1674:             releaseWorker.execute(() -> {
L1675:                 if (p != null && !p.isReleased()) {
L1676:                     Log.d(TAG, "onDestroy stop=" + p.stop());
L1677:                     Log.d(TAG, "onDestroy clearSurface=" + p.clearSurface());
L1678:                     Log.d(TAG, "onDestroy release=" + p.release());
L1679:                 }
L1680:                 clearSurfaceReferenceOnly();
L1681:             });
L1682:             releaseWorker.shutdown();
L1683:         } else {
L1684:             clearSurfaceReferenceOnly();
L1685:         }
L1686:         super.onDestroy();
L1687:     }
L1688: 
L1689:     private interface NativeAction {
L1690:         String run() throws Exception;
L1691:     }
L1692: }
```

## E23｜Diagnostics gate and time semantics

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:45–94`

```text
L45: constexpr int64_t kStartupKeyFrameWaitTimeoutMs = 4000;
L46: 
L47: // LAT2: bounded timing correlation capacity. Covers the ~1-2 frames in flight
L48: // plus decoder reorder headroom; never grows unbounded.
L49: constexpr size_t kStageTimingMaxRecords = 256;
L50: 
L51: // LAT3: warm-up samples excluded from steady-state distribution percentiles.
L52: // ~25fps => ~120 samples ~= 5s, past freshness flush / keyframe wait / EGL
L53: // create / MediaCodec warm-up before steady state converges.
L54: constexpr int64_t kStageTimingWarmupSamples = 120;
L55: 
L56: // LAT6-FINAL: no provider in this workspace can prove sender/server wall time
L57: // against the receiver wall clock or bound their error. Keep the measurement
L58: // gate closed until such evidence exists; RTP/NTP mapping alone is not clock
L59: // synchronization and must never produce an apparently valid E2E percentile.
L60: constexpr int64_t kE2EClockSyncEstimatedErrorUs = -1;
L61: constexpr bool kE2EClockSyncValid = false;
L62: 
L63: #if FFMPEGPLAYER_ENABLE_TEST_HOOKS
L64: // A3 debug-only hook (default 0 = normal playback behavior unchanged).
L65: std::atomic<int> g_audio_worker_test_delay_ms{0};
L66: #endif
L67: 
L68: // A2 frozen PCM output contract: S16 / 48000 Hz / stereo / interleaved.
L69: constexpr int kAudioPcmOutputSampleRate = 48000;
L70: constexpr int kAudioPcmOutputChannels = 2;
L71: constexpr AVSampleFormat kAudioPcmOutputFormat = AV_SAMPLE_FMT_S16;
L72: 
L73: // Java AudioTrack sink control commands (mirror LiveAudioPcmSink). START only
L74: // opens a new write epoch; onAudioPcm still lazily creates/plays AudioTrack on
L75: // the audio worker thread.
L76: constexpr int kAudioSinkCmdStart = 0;
L77: constexpr int kAudioSinkCmdPauseFlush = 1;
L78: constexpr int kAudioSinkCmdRelease = 2;
L79: constexpr int kAudioSinkWriteCancelled = -10000;
L80: 
L81: // A5: AudioTrack playback-head clock / A-V sync tuning.
L82: constexpr int64_t kAudioClockStaleMs = 500;         // clock considered stale if not refreshed within this window
L83: constexpr int64_t kAudioMasterMaxWaitUs = 150000;   // bounded max wait for video to catch up to audio (150 ms)
L84: constexpr int kAudioMasterWaitPollMs = 2;           // poll interval while waiting for the audio clock
L85: constexpr int64_t kAudioClockPtsJitterToleranceUs = 20000;
L86: 
L87: // AGC tuning constants.
L88: constexpr int kAgcUpdateIntervalFrames = 5;
L89: constexpr int kAgcPixelStep = 4;
L90: constexpr int kAgcRowStep = 4;
L91: constexpr float kAgcLowPercentile = 0.02f;
L92: constexpr float kAgcHighPercentile = 0.98f;
L93: constexpr float kAgcSmoothingAlpha = 0.15f;
L94: constexpr float kAgcMinSpan = 0.05f;
```

### `ffmpegplayer/src/main/cpp/native/diagnostics/E2ETimebase.h:1–80`

```text
L1: #ifndef MOTRO_E2E_TIMEBASE_H
L2: #define MOTRO_E2E_TIMEBASE_H
L3: 
L4: // LAT6: Sender / Server / Network end-to-end timebase mapping helpers.
L5: //
L6: // Clock domains (frozen; never subtract across domains without an explicit
L7: // mapping):
L8: //   A. Sender monotonic      - sender-internal durations only.
L9: //   B. Sender wall clock     - NTP/PTP synchronized Unix time.
L10: //   C. RTP media clock       - e.g. 90 kHz video RTP timestamps (wrap at 2^32).
L11: //   D. Android monotonic     - LAT2/LAT3 T0..T4 (steady_clock).
L12: //   E. Android wall clock    - receiver Unix wall time (this header's bridge).
L13: //
L14: // This header is self-contained (no FFmpeg / android dependencies) so the math
L15: // is host-testable. All absolute timestamps are int64 nanoseconds; float is
L16: // never used for absolute time. Diagnostics only: SIDE_CHANNEL_DIAGNOSTICS.
L17: 
L18: #include "LatencyDistribution.h"
L19: 
L20: #include <chrono>
L21: #include <cstdint>
L22: #include <limits>
L23: #include <mutex>
L24: 
L25: // NTP era offset: seconds between 1900-01-01 and 1970-01-01.
L26: constexpr uint64_t kNtpUnixEpochOffsetSeconds = 2208988800ULL;
L27: 
L28: // Receiver WALL clock now, in Unix nanoseconds (clock domain E). Used ONLY as
L29: // the T0 bridge value for cross-device comparison against an independently
L30: // synchronized sender/server wall clock; never for local stage durations.
L31: inline int64_t wallClockNs() {
L32:     return std::chrono::duration_cast<std::chrono::nanoseconds>(
L33:                    std::chrono::system_clock::now().time_since_epoch())
L34:             .count();
L35: }
L36: 
L37: // NTP 64-bit timestamp (seconds + fraction of a second) -> Unix ns.
L38: // The fraction is scaled by 1e9 / 2^32 in integer math (no float, no overflow:
L39: // max intermediate = (2^32-1) * 1e9 < 2^63).
L40: inline bool ntpToUnixNsChecked(uint32_t seconds, uint32_t fraction,
L41:                                int64_t &outUnixNs) {
L42:     if (seconds < kNtpUnixEpochOffsetSeconds) {
L43:         return false;
L44:     }
L45:     const uint64_t unixSecs = static_cast<uint64_t>(seconds) - kNtpUnixEpochOffsetSeconds;
L46:     if (unixSecs > static_cast<uint64_t>(std::numeric_limits<int64_t>::max())
L47:                            / 1000000000ULL) {
L48:         return false;
L49:     }
L50:     const int64_t fracNs = static_cast<int64_t>(
L51:             (static_cast<uint64_t>(fraction) * 1000000000ULL) >> 32);
L52:     outUnixNs = static_cast<int64_t>(unixSecs * 1000000000ULL) + fracNs;
L53:     return true;
L54: }
L55: 
L56: inline int64_t ntpToUnixNs(uint32_t seconds, uint32_t fraction) {
L57:     int64_t unixNs = -1;
L58:     return ntpToUnixNsChecked(seconds, fraction, unixNs) ? unixNs : -1;
L59: }
L60: 
L61: // Extend a wrapping 32-bit RTP timestamp into a 64-bit continuous timeline,
L62: // choosing the 32-bit epoch closest to lastExtended. lastExtended == 0 means
L63: // "uninitialized" and returns newRtp verbatim (first sample initializes).
L64: inline uint64_t extendRtpTimestamp(uint64_t lastExtended, uint32_t newRtp) {
L65:     constexpr uint64_t kRtpRange = 1ULL << 32;
L66:     if (lastExtended == 0) {
L67:         return static_cast<uint64_t>(newRtp);
L68:     }
L69:     const uint64_t base = (lastExtended / kRtpRange) * kRtpRange;
L70:     const auto diffFromLast = [lastExtended](uint64_t value) -> uint64_t {
L71:         return value > lastExtended ? value - lastExtended : lastExtended - value;
L72:     };
L73:     uint64_t best = base | newRtp;
L74:     uint64_t bestDiff = diffFromLast(best);
L75:     const uint64_t nextEpoch = (base + kRtpRange) | newRtp;
L76:     const uint64_t nextDiff = diffFromLast(nextEpoch);
L77:     if (nextDiff < bestDiff) {
L78:         bestDiff = nextDiff;
L79:         best = nextEpoch;
L80:     }
```

### `ffmpegplayer/src/main/cpp/native/diagnostics/DiagnosticsMode.h:1–45`

```text
L1: #ifndef MOTRO_DIAGNOSTICS_MODE_H
L2: #define MOTRO_DIAGNOSTICS_MODE_H
L3: 
L4: #include <cctype>
L5: #include <string>
L6: 
L7: enum class DiagnosticsMode {
L8:     Off,
L9:     Basic,
L10:     Latency
L11: };
L12: 
L13: inline const char *diagnosticsModeName(DiagnosticsMode mode) {
L14:     switch (mode) {
L15:         case DiagnosticsMode::Off: return "off";
L16:         case DiagnosticsMode::Basic: return "basic";
L17:         case DiagnosticsMode::Latency: return "latency";
L18:     }
L19:     return "basic";
L20: }
L21: 
L22: inline bool parseDiagnosticsMode(const std::string &value, DiagnosticsMode &out) {
L23:     std::string normalized;
L24:     normalized.reserve(value.size());
L25:     for (char c : value) {
L26:         if (!std::isspace(static_cast<unsigned char>(c))) {
L27:             normalized.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(c))));
L28:         }
L29:     }
L30:     if (normalized == "off") {
L31:         out = DiagnosticsMode::Off;
L32:         return true;
L33:     }
L34:     if (normalized == "basic") {
L35:         out = DiagnosticsMode::Basic;
L36:         return true;
L37:     }
L38:     if (normalized == "latency") {
L39:         out = DiagnosticsMode::Latency;
L40:         return true;
L41:     }
L42:     return false;
L43: }
L44: 
L45: #endif  // MOTRO_DIAGNOSTICS_MODE_H
```

## E24｜Thermal processing scope

### `ffmpegplayer/src/main/cpp/native/ThermalConfig.h:1–29`

```text
L1: #ifndef MOTRO_THERMAL_CONFIG_H
L2: #define MOTRO_THERMAL_CONFIG_H
L3: 
L4: #include <string>
L5: 
L6: enum class ThermalPaletteMode {
L7:     ORIGINAL = 0,
L8:     WHITE_HOT = 1,
L9:     IRONBOW = 2
L10: };
L11: 
L12: // 热成像显示参数；仅控制画面强度映射与伪彩色，不代表温度标定结果。
L13: struct ThermalConfig {
L14:     bool enabled = false;
L15:     ThermalPaletteMode palette = ThermalPaletteMode::ORIGINAL;
L16:     // 自动增益控制：根据图像亮度分布估计显示窗口。
L17:     bool agcEnabled = false;
L18:     float gamma = 1.0f;
L19:     // 归一化强度窗口的下限与上限，用于拉伸暗部到亮部的显示范围。
L20:     float blackPoint = 0.0f;
L21:     float whitePoint = 1.0f;
L22: };
L23: 
L24: std::string thermalPaletteName(ThermalPaletteMode palette);
L25: bool parseThermalPalette(int value, ThermalPaletteMode &palette);
L26: bool isValidThermalGamma(float gamma);
L27: bool isValidThermalWindow(float blackPoint, float whitePoint);
L28: 
L29: #endif // MOTRO_THERMAL_CONFIG_H
```

### `ffmpegplayer/src/main/cpp/native/ThermalPaletteLut.cpp:1–50`

```text
L1: #include "ThermalPaletteLut.h"
L2: 
L3: namespace {
L4: 
L5: struct RgbPoint {
L6:     float t;
L7:     uint8_t r;
L8:     uint8_t g;
L9:     uint8_t b;
L10: };
L11: 
L12: // Ironbow color control points, piecewise-linear interpolated to 256 entries.
L13: // index is monotonically increasing with intensity/brightness.
L14: // Byte-for-byte equivalent to the Phase 1 software YUV LUT.
L15: const RgbPoint kIronbowPoints[] = {
L16:         {0.00f, 10, 0, 30},     // near-black dark blue
L17:         {0.15f, 0, 0, 120},     // dark blue
L18:         {0.30f, 120, 0, 200},   // violet
L19:         {0.45f, 200, 0, 200},   // magenta
L20:         {0.60f, 230, 40, 60},   // red
L21:         {0.75f, 250, 140, 20},  // orange
L22:         {0.90f, 250, 220, 60},  // yellow
L23:         {1.00f, 255, 245, 235}  // white
L24: };
L25: 
L26: } // namespace
L27: 
L28: std::array<uint8_t, kIronbowLutSize> createIronbowLut() {
L29:     constexpr int pointCount = static_cast<int>(sizeof(kIronbowPoints) / sizeof(kIronbowPoints[0]));
L30:     std::array<uint8_t, kIronbowLutSize> lut{};
L31:     for (int i = 0; i < 256; ++i) {
L32:         const float t = i / 255.0f;
L33:         int seg = 0;
L34:         for (int s = 0; s < pointCount - 1; ++s) {
L35:             if (t <= kIronbowPoints[s + 1].t) {
L36:                 seg = s;
L37:                 break;
L38:             }
L39:             seg = s;
L40:         }
L41:         const RgbPoint &a = kIronbowPoints[seg];
L42:         const RgbPoint &b = kIronbowPoints[seg + 1];
L43:         const float span = (b.t - a.t) > 0.0f ? (b.t - a.t) : 1.0f;
L44:         const float f = (t - a.t) / span;
L45:         lut[i * 3 + 0] = static_cast<uint8_t>(a.r + (b.r - a.r) * f);
L46:         lut[i * 3 + 1] = static_cast<uint8_t>(a.g + (b.g - a.g) * f);
L47:         lut[i * 3 + 2] = static_cast<uint8_t>(a.b + (b.b - a.b) * f);
L48:     }
L49:     return lut;
L50: }
```

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:5428–5518`

```text
L5428: bool NativePlayer::isSoftwareYuvGlFrameSupported(int frameFormat) const {
L5429:     return frameFormat == AV_PIX_FMT_YUV420P || frameFormat == AV_PIX_FMT_YUVJ420P;
L5430: }
L5431: 
L5432: void NativePlayer::updateAgcState(AVFrame *frame, const ThermalConfig &thermal) {
L5433:     if (!thermal.agcEnabled || frame == nullptr) {
L5434:         return;
L5435:     }
L5436:     const int frameCount = agcFrameCounter_.fetch_add(1) + 1;
L5437:     if (frameCount < kAgcUpdateIntervalFrames) {
L5438:         return;
L5439:     }
L5440:     agcFrameCounter_.store(0);
L5441:     const AgcResult detected = computeAgcWindow(frame->data[0], frame->linesize[0],
L5442:                                                 frame->width, frame->height,
L5443:                                                 static_cast<AVColorRange>(frame->color_range));
L5444:     if (!detected.valid) {
L5445:         return;
L5446:     }
L5447:     if (!agcValid_.load()) {
L5448:         agcBlackPoint_.store(detected.blackPoint);
L5449:         agcWhitePoint_.store(detected.whitePoint);
L5450:         agcValid_.store(true);
L5451:         LOGI("AGC initialized blackPoint=%.3f whitePoint=%.3f", detected.blackPoint, detected.whitePoint);
L5452:     } else {
L5453:         const float oldBlack = agcBlackPoint_.load();
L5454:         const float oldWhite = agcWhitePoint_.load();
L5455:         agcBlackPoint_.store(oldBlack * (1.0f - kAgcSmoothingAlpha) + detected.blackPoint * kAgcSmoothingAlpha);
L5456:         agcWhitePoint_.store(oldWhite * (1.0f - kAgcSmoothingAlpha) + detected.whitePoint * kAgcSmoothingAlpha);
L5457:     }
L5458:     agcUpdateCount_.fetch_add(1);
L5459: }
L5460: 
L5461: bool NativePlayer::renderNv12GlFrame(AVFrame *frame, int frameWidth, int frameHeight, int64_t ptsUs) {
L5462:     if (frame == nullptr || frame->data[0] == nullptr || frame->data[1] == nullptr
L5463:         || frame->linesize[0] <= 0 || frame->linesize[1] <= 0) {
L5464:         return false;
L5465:     }
L5466: 
L5467:     const ThermalConfig thermal = getThermalConfig();
L5468:     int nv12ThermalMode = 0;  // original
L5469:     if (thermal.enabled) {
L5470:         if (thermal.palette == ThermalPaletteMode::WHITE_HOT) {
L5471:             nv12ThermalMode = 1;
L5472:         } else if (thermal.palette == ThermalPaletteMode::IRONBOW) {
L5473:             nv12ThermalMode = 2;
L5474:         }
L5475:     }
L5476:     // NV12 AGC: analyze the CPU-visible Y plane (data[0]) with the shared Phase 1
L5477:     // luma8 helper (4x4 sampling, 256-bin histogram, P2/P98, range normalize).
L5478:     if (frameWidth != nv12AgcLastFrameWidth_.load() || frameHeight != nv12AgcLastFrameHeight_.load()) {
L5479:         // Resolution change / new stream: drop stale AGC validity for a fresh scene.
L5480:         nv12AgcValid_.store(false);
L5481:         nv12AgcFrameCounter_.store(0);
L5482:         nv12AgcLastFrameWidth_.store(frameWidth);
L5483:         nv12AgcLastFrameHeight_.store(frameHeight);
L5484:     }
L5485:     float effectiveBlack = thermal.blackPoint;
L5486:     float effectiveWhite = thermal.whitePoint;
L5487:     if (nv12ThermalMode != 0 && thermal.agcEnabled) {
L5488:         const int frameCount = nv12AgcFrameCounter_.fetch_add(1) + 1;
L5489:         if (frameCount >= kAgcUpdateIntervalFrames) {
L5490:             nv12AgcFrameCounter_.store(0);
L5491:             const AgcResult detected = computeAgcWindow(frame->data[0], frame->linesize[0],
L5492:                                                         frameWidth, frameHeight,
L5493:                                                         static_cast<AVColorRange>(frame->color_range));
L5494:             if (detected.valid) {
L5495:                 if (!nv12AgcValid_.load()) {
L5496:                     nv12AgcBlackPoint_.store(detected.blackPoint);
L5497:                     nv12AgcWhitePoint_.store(detected.whitePoint);
L5498:                     nv12AgcValid_.store(true);
L5499:                 } else {
L5500:                     const float oldBlack = nv12AgcBlackPoint_.load();
L5501:                     const float oldWhite = nv12AgcWhitePoint_.load();
L5502:                     nv12AgcBlackPoint_.store(oldBlack * (1.0f - kAgcSmoothingAlpha) + detected.blackPoint * kAgcSmoothingAlpha);
L5503:                     nv12AgcWhitePoint_.store(oldWhite * (1.0f - kAgcSmoothingAlpha) + detected.whitePoint * kAgcSmoothingAlpha);
L5504:                 }
L5505:                 nv12AgcUpdateCount_.fetch_add(1);
L5506:             } else {
L5507:                 nv12AgcInvalidCount_.fetch_add(1);
L5508:             }
L5509:         }
L5510:         if (nv12AgcValid_.load()) {
L5511:             effectiveBlack = nv12AgcBlackPoint_.load();
L5512:             effectiveWhite = nv12AgcWhitePoint_.load();
L5513:         }
L5514:     }
L5515: 
L5516:     const RenderResult result = nv12GlRenderer_.renderNv12(frame->data[0], frame->linesize[0],
L5517:                                                            frame->data[1], frame->linesize[1],
L5518:                                                            frameWidth, frameHeight,
```

## E25｜RTCP side-data header and ABI dependency

### `ffmpegplayer/src/main/cpp/ffmpeg/include/libavutil/ffversion.h:1–5`

```text
L1: /* Automatically generated by version.sh, do not manually edit! */
L2: #ifndef AVUTIL_FFVERSION_H
L3: #define AVUTIL_FFVERSION_H
L4: #define FFMPEG_VERSION "8.0.1"
L5: #endif /* AVUTIL_FFVERSION_H */
```

### `ffmpegplayer/src/main/cpp/ffmpeg/include/libavcodec/packet.h:354–368`

```text
L354:      * The payload is the AV3DReferenceDisplaysInfo struct defined in
L355:      * libavutil/tdrdi.h.
L356:      */
L357:     AV_PKT_DATA_3D_REFERENCE_DISPLAYS,
L358: 
L359:     /**
L360:      * Contains the last received RTCP SR (Sender Report) information
L361:      * in the form of the AVRTCPSenderReport struct.
L362:      */
L363:     AV_PKT_DATA_RTCP_SR,
L364: 
L365:     /**
L366:      * The number of side data types.
L367:      * This is not part of the public API/ABI in the sense that it may
L368:      * change when new side data types are added.
```

### `ffmpegplayer/src/main/cpp/ffmpeg/include/libavcodec/defs.h:337–351`

```text
L337: } AVProducerReferenceTime;
L338: 
L339: /**
L340:  * RTCP SR (Sender Report) information
L341:  *
L342:  * The received sender report information for an RTSP
L343:  * stream, exposed as AV_PKT_DATA_RTCP_SR side data.
L344:  */
L345: typedef struct AVRTCPSenderReport {
L346:     uint32_t ssrc; ///< Synchronization source identifier
L347:     uint64_t ntp_timestamp; ///< NTP time when the report was sent
L348:     uint32_t rtp_timestamp; ///< RTP time when the report was sent
L349:     uint32_t sender_nb_packets; ///< Total number of packets sent
L350:     uint32_t sender_nb_bytes; ///< Total number of bytes sent (excluding headers or padding)
L351: } AVRTCPSenderReport;
```

### `ffmpegplayer/src/main/cpp/native/NativePlayer.cpp:3350–3415`

```text
L3350:     diagnostics_.resetE2E();
L3351: }
L3352: 
L3353: void NativePlayer::processRtcpTimebase(int64_t t0WallNs) {
L3354:     const int64_t tbNum = videoStreamTimeBaseNum_.load();
L3355:     const int64_t tbDen = videoStreamTimeBaseDen_.load();
L3356:     if (tbNum > 0 && tbDen > 0) {
L3357:         diagnostics_.setRtpClockRate(tbDen / tbNum);
L3358:     }
L3359:     // FFmpeg 8 exports a received SR exactly once, on the next packet.
L3360:     size_t srSize = 0;
L3361:     const uint8_t *srData = av_packet_get_side_data(packet_, AV_PKT_DATA_RTCP_SR, &srSize);
L3362:     if (srData != nullptr && srSize >= sizeof(AVRTCPSenderReport)) {
L3363:         AVRTCPSenderReport sr;
L3364:         std::memcpy(&sr, srData, sizeof(sr));
L3365:         diagnostics_.onSenderReport(sr.ssrc, sr.ntp_timestamp, sr.rtp_timestamp);
L3366:     }
L3367: 
L3368:     const RtcpSrTracker::Snapshot srSnap = diagnostics_.senderReportSnapshot();
L3369:     if (!srSnap.hasAnchor || !srSnap.srMappingValid || srSnap.lastSrNtpNs <= 0) {
L3370:         return;
L3371:     }
L3372: 
L3373:     // PRFT is computed by libavformat from THIS packet's raw RTP timestamp and
L3374:     // the latest SR RTP/NTP anchor. Co-location on AVPacket is the same-frame
L3375:     // correlation key; a latest-SR/latest-T0 pairing is explicitly forbidden.
L3376:     size_t prftSize = 0;
L3377:     const uint8_t *prftData = av_packet_get_side_data(packet_, AV_PKT_DATA_PRFT, &prftSize);
L3378:     if (prftData == nullptr || prftSize < sizeof(AVProducerReferenceTime)) {
L3379:         diagnostics_.onE2ESameFrameUnmatched();
L3380:         return;
L3381:     }
L3382:     AVProducerReferenceTime prft;
L3383:     std::memcpy(&prft, prftData, sizeof(prft));
L3384:     if (prft.wallclock < 0
L3385:             || prft.wallclock > std::numeric_limits<int64_t>::max() / 1000LL) {
L3386:         diagnostics_.onE2ESameFrameUnmatched();
L3387:         return;
L3388:     }
L3389:     diagnostics_.onE2ESameFrameMapped();
L3390: 
L3391:     const E2ESampleResult sample =
L3392:             measureSenderSendToReceiverT0Us(prft.wallclock * 1000LL, t0WallNs);
L3393:     if (steadyStateValid_.load()) {
L3394:         diagnostics_.onE2ESample(sample, kE2EClockSyncValid);
L3395:     }
L3396: }
L3397: 
L3398: void NativePlayer::resetStageTimingCorrelation() {
L3399:     stageTimingRecords_.clear();
L3400:     demuxSubmitTiming_.last.store(-1);
L3401:     demuxSubmitTiming_.total.store(0);
L3402:     demuxSubmitTiming_.count.store(0);
L3403:     demuxSubmitTiming_.max.store(0);
L3404:     decoderTiming_.last.store(-1);
L3405:     decoderTiming_.total.store(0);
L3406:     decoderTiming_.count.store(0);
L3407:     decoderTiming_.max.store(0);
L3408:     decodeRenderTiming_.last.store(-1);
L3409:     decodeRenderTiming_.total.store(0);
L3410:     decodeRenderTiming_.count.store(0);
L3411:     decodeRenderTiming_.max.store(0);
L3412:     renderTiming_.last.store(-1);
L3413:     renderTiming_.total.store(0);
L3414:     renderTiming_.count.store(0);
L3415:     renderTiming_.max.store(0);
```

## E26｜Actual matrix result and documented caveats

### `RTSP_RECONNECT_MATRIX.md:1–81`

```text
L1: # RTSP_RECONNECT_MATRIX
L2: 
L3: 执行日期：2026-09-16～2026-09-17
L4: 
L5: 真实设备矩阵结果：**PASS 0 / FAIL 0 / NOT_RUN 32**。
L6: 
L7: 所有 Case 均保持 `NOT_RUN`，因为当前源虽然在 SDP/stream info 中声明 H265 + AAC，但实际采样的 `audioPacketCount` 始终为 0；同时没有可自动控制的源网络、物理链路、RTSP 服务和 404 资源开关。基线或自然 EOF 重连不等同于指定故障注入，因此没有伪造 PASS，也没有把未执行的 Case 标为 FAIL。
L8: 
L9: ## Case 列表
L10: 
L11: | Case | Transport | Audio | Recording | Fault | Result | 原因 |
L12: |---|---|---:|---:|---|---|---|
L13: | R01 | TCP | OFF | OFF | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包 |
L14: | R02 | TCP | OFF | ON | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包 |
L15: | R03 | TCP | ON | OFF | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包，无法验证 PCM/AudioClock |
L16: | R04 | TCP | ON | ON | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包，无法验证音频播放/录制 |
L17: | R05 | UDP | OFF | OFF | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包 |
L18: | R06 | UDP | OFF | ON | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包 |
L19: | R07 | UDP | ON | OFF | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包，无法验证 PCM/AudioClock |
L20: | R08 | UDP | ON | ON | 断网/恢复 | NOT_RUN | 无可控故障；源未发送 AAC 包，无法验证音频播放/录制 |
L21: | R09 | TCP | OFF | OFF | 物理链路 | NOT_RUN | 未执行人工拔线；源未发送 AAC 包 |
L22: | R10 | TCP | OFF | ON | 物理链路 | NOT_RUN | 未执行人工拔线；源未发送 AAC 包 |
L23: | R11 | TCP | ON | OFF | 物理链路 | NOT_RUN | 未执行人工拔线；无法验证音频恢复 |
L24: | R12 | TCP | ON | ON | 物理链路 | NOT_RUN | 未执行人工拔线；无法验证音频播放/录制 |
L25: | R13 | UDP | OFF | OFF | 物理链路 | NOT_RUN | 未执行人工拔线；源未发送 AAC 包 |
L26: | R14 | UDP | OFF | ON | 物理链路 | NOT_RUN | 未执行人工拔线；源未发送 AAC 包 |
L27: | R15 | UDP | ON | OFF | 物理链路 | NOT_RUN | 未执行人工拔线；无法验证音频恢复 |
L28: | R16 | UDP | ON | ON | 物理链路 | NOT_RUN | 未执行人工拔线；无法验证音频播放/录制 |
L29: | R17 | TCP | OFF | OFF | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；源未发送 AAC 包 |
L30: | R18 | TCP | OFF | ON | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；源未发送 AAC 包 |
L31: | R19 | TCP | ON | OFF | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；无法验证音频恢复 |
L32: | R20 | TCP | ON | ON | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；无法验证音频播放/录制 |
L33: | R21 | UDP | OFF | OFF | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；源未发送 AAC 包 |
L34: | R22 | UDP | OFF | ON | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；源未发送 AAC 包 |
L35: | R23 | UDP | ON | OFF | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；无法验证音频恢复 |
L36: | R24 | UDP | ON | ON | 服务停止/重启 | NOT_RUN | 无 RTSP 服务控制入口；无法验证音频播放/录制 |
L37: | R25 | TCP | OFF | OFF | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404 |
L38: | R26 | TCP | OFF | ON | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404 |
L39: | R27 | TCP | ON | OFF | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404；无法验证音频恢复 |
L40: | R28 | TCP | ON | ON | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404；无法验证音频播放/录制 |
L41: | R29 | UDP | OFF | OFF | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404 |
L42: | R30 | UDP | OFF | ON | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404 |
L43: | R31 | UDP | ON | OFF | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404；无法验证音频恢复 |
L44: | R32 | UDP | ON | ON | 404/恢复 | NOT_RUN | 无资源上下线控制入口，未观察到真实 404；无法验证音频播放/录制 |
L45: 
L46: ## 已执行的真实设备检查
L47: 
L48: 设备通过 USB adb 连接，RTSP 源可达时完成了 TCP/UDP、Audio ON/OFF、Recording ON/OFF 的基线采样。当前源能解码和渲染 H265：最终 TCP 基线得到 138 帧、UDP 基线得到 141 帧，`effectiveRtspTransport` 分别为 tcp/udp；但所有采样均为 `audioCodec=aac`、`sourceHasAudio=true`、`audioPacketCount=0`，Audio ON 时没有 PCM、AudioTrack write 或有效 AudioClock，录制文件也没有 AAC packet。
L49: 
L50: 还对同一服务器请求了随机不存在的资源路径；服务器仍返回并播放现有视频，没有返回 404。因此当前环境无法产生真实 RTSP 404，首次 404 的代码路径只完成了 host policy 测试，未记为设备 PASS。
L51: 
L52: 该源还会自然返回 EOF。修复前，一次 TCP + Recording 基线在自然 EOF 后成功恢复视频，但 Recorder 在重连后第 3 个包报 `EINVAL` 并停止。修复后，同一设备上观察到连续 15 次自然 EOF/重连，Recorder 保持运行，`writeErrors=0`、`queueDrops=0`；另一次采样成功写入 153 个视频包并完成 2 个 MP4 分片，正常 stop 后队列为 0，ffprobe 可解析视频流。因为这些是源自身 EOF，且无 AAC 数据，所以只作为修复证据，不计入 32 个故障 Case 的 PASS。
L53: 
L54: ## 修复的问题
L55: 
L56: - 为 `avformat_open_input`、`avformat_find_stream_info` 和 `av_read_frame` 增加独立单调时钟 deadline。UDP 无数据时最多等待配置的 `readTimeoutUs`，interrupt callback 返回后统一按 `ETIMEDOUT` 进入重连，不依靠扩大 timeout。
L57: - 首次 RTSP 404 在启用无限/允许重连策略时进入 `WAITING_SOURCE`，`prepare()` 返回可继续启动的 pending reconnect 状态；后续按既有 1/2/4/5 秒退避重试，资源恢复后继续播放。
L58: - 重连时先推进音频代次、清空 PCM、停止并 join 旧 Audio Worker、使 AudioClock 失效；输入重开成功后才启动新代次，避免旧 PCM、旧时钟和永久静音。
L59: - stop 在播放线程 join 后再次停止 Audio Worker，覆盖 stop 与重连成功并发启动 worker 的窗口；Java `stop()` 只在锁内取得句柄，在锁外执行原生 join，避免播放线程投递事件时反向等待同一 Java 锁，同时保留原有同步事件过滤语义。
L60: - Recorder 重连后的首关键帧可能同时缺失 PTS/DTS。现由 Recorder 显式生成单调 DTS/PTS，并把校正量延续到后续包；只有 mux 成功后才更新 `lastDts`，避免 FFmpeg 隐式补时间戳后本地状态失配导致 `EINVAL`。
L61: 
L62: ## 状态流和判定
L63: 
L64: 正常断线目标流：
L65: 
L66: ```text
L67: PLAYING → DISCONNECTED → RECONNECTING
L68:                          ├─ open 404 → WAITING_SOURCE → retry/backoff
L69:                          └─ open success → RECONNECTED → 首关键帧 → PLAYING
L70: ```
L71: 
L72: 首次打开 404：
L73: 
L74: ```text
L75: prepare: 404 → WAITING_SOURCE (pendingReconnect)
L76: start → retry/backoff → source available → RECONNECTED → PLAYING
L77: ```
L78: 
L79: 运行器按设备 `elapsedRealtime` 记录 `disconnectDetectedMs` 和 `reconnectElapsedMs`，并输出题目要求的 transport/audio/recording/fault、尝试次数、最终状态、视频/音频/录制恢复、PASS/FAIL/NOT_RUN 和失败原因。Recording Case 还要求分片数增长、AAC 与 H264/H265 均有 packet、零 queue drop/write error、stop 排空以及 ffprobe 通过。
L80: 
L81: 统一入口、配置格式和人工拔线步骤见 `tools/RTSP_RECONNECT_TESTING.md`。原始 URL/凭据不会写入结果；native log 会脱敏。
```

## E27｜Matrix gate/evaluator and release test-hook isolation

### `tools/rtsp_reconnect_matrix.py:26–56`

```text
L26: def redact(text):
L27:     return re.sub(r"rtsps?://[^\s\"<>]+", "rtsp://[REDACTED]", str(text), flags=re.I)
L28: 
L29: 
L30: def initial_result(case, reason):
L31:     return dict(case, disconnectDetectedMs=None, reconnectAttempts=None, reconnectElapsedMs=None,
L32:                 finalState=None, videoRecovered=None, audioRecovered=None, recordingRecovered=None,
L33:                 status="NOT_RUN", failureReason=reason, rounds=[])
L34: 
L35: 
L36: def baseline_errors(case, stats):
L37:     errors = []
L38:     if stats.get('playerState') != 'PLAYING' or stats.get('renderedFrameCount', 0) <= 10:
L39:         errors.append('video baseline not playing')
L40:     if stats.get('effectiveRtspTransport') != case['transport']:
L41:         errors.append('effective RTSP transport differs from requested case')
L42:     if stats.get('audioPacketCount', 0) <= 0:
L43:         errors.append('no source AAC packets observed; SDP codec declaration alone is insufficient')
L44:     if stats.get('reconnectAttemptCount', 0) != 0:
L45:         errors.append('source disconnected during baseline stability window')
L46:     if case['audio']:
L47:         if not stats.get('audioPlaybackClockValid') or stats.get('audioSinkWriteCount', 0) <= 0:
L48:             errors.append('audio baseline has no PCM playback/valid clock')
L49:     elif stats.get('audioWorkerRunning') or stats.get('audioSinkWriteCount', 0):
L50:         errors.append('audio OFF unexpectedly started output')
L51:     recorder = stats.get('recorder', {})
L52:     if case['recording'] and (recorder.get('writeErrors', 0) or recorder.get('audioPacketCount', 0) <= 0):
L53:         errors.append('recording baseline error or missing AAC packets: '+recorder.get('lastError', ''))
L54:     return errors
L55: 
L56: 
```

### `tools/rtsp_reconnect_matrix.py:57–134`

```text
L57: def evaluate_round(case, events, before, limits=None):
L58:     """Evaluate only observed device samples, using device elapsedRealtime throughout."""
L59:     limits = limits or {}
L60:     reasons = []
L61:     fault = next((e for e in events if e['kind'] == 'fault'), None)
L62:     restored = next((e for e in events if e['kind'] == 'restored'), None)
L63:     ev = [e for e in events if e['kind'] == 'event']
L64:     disconnected = next((e for e in ev if e['data'].get('event') == 'reconnect_disconnected'), None)
L65:     success = next((e for e in ev if e['data'].get('event') == 'reconnect_success'
L66:                     and restored and e['t'] >= restored['t']), None)
L67:     retries = [e for e in ev if e['data'].get('event') == 'reconnecting']
L68:     stats = [e for e in events if e['kind'] == 'stats']
L69:     after = [e for e in stats if success and e['t'] >= success['t'] and e['data'].get('playerState') == 'PLAYING']
L70:     if not fault or not disconnected or disconnected['t'] < fault['t']: reasons.append('disconnect not detected after fault')
L71:     if not restored or not success: reasons.append('no reconnect success after restoration')
L72:     if not retries: reasons.append('no reconnect/backoff event')
L73:     for e in retries:
L74:         data = e['data']; attempt = data.get('attempt', 0)
L75:         expected = min(1000 * 2 ** min(max(attempt - 1, 0), 8), 5000)
L76:         if attempt < 1 or data.get('delayMs') != expected: reasons.append('unexpected retry backoff'); break
L77:     for first, second in zip(retries, retries[1:]):
L78:         if second['t'] - first['t'] < first['data'].get('delayMs', 0) - 100:
L79:             reasons.append('retry loop is faster than backoff'); break
L80:     if case['faultType'] == 'source_404' and not any(e['data'].get('event') == 'waiting_source'
L81:                                                    and e['data'].get('source404') for e in ev):
L82:         reasons.append('no actual 404 WAITING_SOURCE evidence')
L83:     if any(e['kind'] == 'error' for e in events): reasons.append('harness/player operation error')
L84:     if any(e['data'].get('playerState') == 'ERROR' for e in stats): reasons.append('player entered ERROR')
L85:     end = after[-1]['data'] if after else (stats[-1]['data'] if stats else {})
L86:     if any(e['data'].get('effectiveRtspTransport') != case['transport'] for e in after):
L87:         reasons.append('effective transport changed')
L88:     video = len(after) >= 2 and end.get('videoFrameCount', 0) > before.get('videoFrameCount', 0) \
L89:             and end.get('renderedFrameCount', 0) > after[0]['data'].get('renderedFrameCount', 0)
L90:     if not video: reasons.append('video decode/render did not resume')
L91:     if end.get('videoCodec', '').lower() not in ('h264', 'hevc', 'h265') or end.get('audioCodec', '').lower() != 'aac':
L92:         reasons.append('source is not H264/H265 + AAC')
L93:     if case['audio']:
L94:         audio = len(after) >= 2 and end.get('audioPlaybackClockValid') is True \
L95:                 and end.get('audioSinkWriteCount', 0) > before.get('audioSinkWriteCount', 0) \
L96:                 and end.get('audioPlaybackClockUs', 0) > after[0]['data'].get('audioPlaybackClockUs', 0) \
L97:                 and end.get('audioQueueGeneration', 0) > before.get('audioQueueGeneration', 0) \
L98:                 and end.get('audioClockGeneration') == end.get('audioQueueGeneration') \
L99:                 and end.get('audioWorkerRunning') is True and end.get('audioPlayable') is True
L100:     else:
L101:         audio = bool(after) and all(not e['data'].get('audioWorkerRunning', True)
L102:                     and e['data'].get('audioSinkWriteCount', -1) == before.get('audioSinkWriteCount', 0)
L103:                     and not e['data'].get('audioEnabled', True) for e in stats)
L104:     if not audio: reasons.append('audio lifecycle/clock recovery mismatch')
L105:     recorder = end.get('recorder', {})
L106:     recording = not case['recording'] or (recorder.get('recording') is True
L107:                 and recorder.get('packetsWritten', 0) > before.get('recorder', {}).get('packetsWritten', 0)
L108:                 and recorder.get('audioPacketCount', 0) > before.get('recorder', {}).get('audioPacketCount', 0)
L109:                 and recorder.get('completedSegmentCount', 0) > before.get('recorder', {}).get('completedSegmentCount', 0)
L110:                 and recorder.get('writeErrors', -1) == 0 and recorder.get('queueDrops', -1) == 0)
L111:     if not recording: reasons.append('recorder did not recover cleanly')
L112:     for key, allowance in [('fdCount', 4), ('threadCount', 4), ('nativeHeapBytes', 16 * 1024 * 1024)]:
L113:         if key not in before or key not in end or before.get(key, -1) < 0 or end.get(key, -1) < 0:
L114:             reasons.append('resource metric unavailable: ' + key)
L115:         elif end[key] - before[key] > limits.get(key, allowance): reasons.append('resource growth: ' + key)
L116:     beats = [e['t'] for e in events if e['kind'] == 'heartbeat']
L117:     if len(beats) < 2 or max((b-a for a, b in zip(beats, beats[1:])), default=99999) > 5000:
L118:         reasons.append('main-thread heartbeat missing/ANR suspected')
L119:     return dict(disconnectDetectedMs=disconnected['t']-fault['t'] if disconnected and fault else None,
L120:                 reconnectAttempts=len(retries), reconnectElapsedMs=success['t']-restored['t'] if success and restored else None,
L121:                 finalState=end.get('playerState'), videoRecovered=video, audioRecovered=audio,
L122:                 recordingRecovered=recording, status='FAIL' if reasons else 'PASS', failureReason='; '.join(dict.fromkeys(reasons)))
L123: 
L124: 
L125: def write_report(directory, results):
L126:     directory.mkdir(parents=True, exist_ok=True)
L127:     normalized = [dict(result, passFail=result['status']) for result in results]
L128:     (directory/'results.json').write_text(json.dumps(normalized, ensure_ascii=False, indent=2), encoding='utf-8')
L129:     fields = ['caseId', 'transport', 'audio', 'recording', 'faultType', 'disconnectDetectedMs', 'reconnectAttempts',
L130:               'reconnectElapsedMs', 'finalState', 'videoRecovered', 'audioRecovered', 'recordingRecovered',
L131:               'passFail', 'failureReason']
L132:     with (directory/'results.csv').open('w', encoding='utf-8-sig', newline='') as output:
L133:         writer = csv.DictWriter(output, fields, extrasaction='ignore'); writer.writeheader(); writer.writerows(normalized)
L134:     counts = {s: sum(r['status'] == s for r in normalized) for s in ('PASS', 'FAIL', 'NOT_RUN')}
```

### `app/src/debug/AndroidManifest.xml:1–7`

```text
L1: <manifest xmlns:android="http://schemas.android.com/apk/res/android">
L2:     <application>
L3:         <!-- Debug source set only; never present in the production APK. -->
L4:         <activity android:name=".RtspMatrixActivity" android:exported="true"
L5:             android:launchMode="singleTop" />
L6:     </application>
L7: </manifest>
```

### `ffmpegplayer/src/main/cpp/CMakeLists.txt:26–36`

```text
L26: # Test-only native commands and their implementations are compiled exclusively
L27: # for the Debug variant. Every other build type fails closed as production.
L28: if(CMAKE_BUILD_TYPE STREQUAL "Debug")
L29:     set(FFMPEGPLAYER_TEST_HOOKS_VALUE 1)
L30: else()
L31:     set(FFMPEGPLAYER_TEST_HOOKS_VALUE 0)
L32: endif()
L33: target_compile_definitions(native-ffmpeg PRIVATE
L34:         FFMPEGPLAYER_ENABLE_TEST_HOOKS=${FFMPEGPLAYER_TEST_HOOKS_VALUE})
L35: message(STATUS
L36:         "FFMPEGPLAYER_ENABLE_TEST_HOOKS=${FFMPEGPLAYER_TEST_HOOKS_VALUE} (CMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE})")
```

### `ffmpegplayer/src/main/cpp/native/TestHookPolicy.h:1–40`

```text
L1: #pragma once
L2: 
L3: #include <string_view>
L4: 
L5: #ifndef FFMPEGPLAYER_ENABLE_TEST_HOOKS
L6: #error "FFMPEGPLAYER_ENABLE_TEST_HOOKS must be defined by the build"
L7: #endif
L8: 
L9: #if FFMPEGPLAYER_ENABLE_TEST_HOOKS != 0 && FFMPEGPLAYER_ENABLE_TEST_HOOKS != 1
L10: #error "FFMPEGPLAYER_ENABLE_TEST_HOOKS must be 0 or 1"
L11: #endif
L12: 
L13: namespace ffmpegplayer {
L14: 
L15: enum class TestHookCommandPolicy {
L16:     NotTestHook,
L17:     EnabledInDebug,
L18:     UnsupportedInRelease,
L19: };
L20: 
L21: inline constexpr std::string_view kTestHookUnsupportedJson =
L22:         R"({"success":false,"errorCode":"unsupported_in_release","message":"test hook is unsupported in release builds"})";
L23: 
L24: constexpr bool isTestOnlyDebugCommand(std::string_view command) {
L25:     return command == "-player-lifetime-stress"
L26:            || command == "-audio-backpressure-test";
L27: }
L28: 
L29: constexpr TestHookCommandPolicy testHookCommandPolicy(std::string_view command) {
L30:     if (!isTestOnlyDebugCommand(command)) {
L31:         return TestHookCommandPolicy::NotTestHook;
L32:     }
L33: #if FFMPEGPLAYER_ENABLE_TEST_HOOKS
L34:     return TestHookCommandPolicy::EnabledInDebug;
L35: #else
L36:     return TestHookCommandPolicy::UnsupportedInRelease;
L37: #endif
L38: }
L39: 
L40: }  // namespace ffmpegplayer
```

## E28｜Host testing target boundaries

### `ffmpegplayer/src/test/cpp/CMakeLists.txt:1–44`

```text
L1: cmake_minimum_required(VERSION 3.22)
L2: project(recorder_host_tests LANGUAGES CXX)
L3: set(CMAKE_CXX_STANDARD 17)
L4: set(CMAKE_CXX_STANDARD_REQUIRED ON)
L5: if(MSVC)
L6:     add_compile_options(/utf-8)
L7: endif()
L8: enable_testing()
L9: find_package(Threads REQUIRED)
L10: set(PLAYER_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp" CACHE PATH "Player native source directory")
L11: add_executable(RecorderAsyncTest RecorderAsyncTest.cpp recorder_fakes/FakeFFmpeg.cpp
L12:     "${PLAYER_SOURCE_DIR}/native/PlayerRemuxRecorder.cpp")
L13: target_include_directories(RecorderAsyncTest PRIVATE recorder_fakes "${PLAYER_SOURCE_DIR}" "${PLAYER_SOURCE_DIR}/ffmpeg/include")
L14: target_link_libraries(RecorderAsyncTest PRIVATE Threads::Threads)
L15: option(RECORDER_ASAN "Enable AddressSanitizer for recorder ownership tests" OFF)
L16: if(RECORDER_ASAN)
L17:     if(MSVC)
L18:         target_compile_options(RecorderAsyncTest PRIVATE /fsanitize=address /Zi)
L19:         target_link_options(RecorderAsyncTest PRIVATE /INCREMENTAL:NO)
L20:     else()
L21:         target_compile_options(RecorderAsyncTest PRIVATE -fsanitize=address -fno-omit-frame-pointer)
L22:         target_link_options(RecorderAsyncTest PRIVATE -fsanitize=address)
L23:     endif()
L24: endif()
L25: if(MSVC)
L26:     target_compile_options(RecorderAsyncTest PRIVATE /utf-8 /W4)
L27:     target_compile_definitions(RecorderAsyncTest PRIVATE _CRT_SECURE_NO_WARNINGS)
L28: endif()
L29: add_test(NAME RecorderAsyncTest COMMAND RecorderAsyncTest)
L30: set_tests_properties(RecorderAsyncTest PROPERTIES TIMEOUT 45)
L31: foreach(test NetworkIoDeadlineTest DiagnosticsModeTest E2ETimebaseTest PreT0TimingTrackerTest)
L32:     if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/${test}.cpp")
L33:         add_executable(${test} "${test}.cpp")
L34:         target_link_libraries(${test} PRIVATE Threads::Threads)
L35:         add_test(NAME ${test} COMMAND ${test})
L36:     endif()
L37: endforeach()
L38: foreach(enabled 0 1)
L39:     if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/TestHookPolicyTest.cpp")
L40:         add_executable(TestHookPolicyTest${enabled} TestHookPolicyTest.cpp)
L41:         target_compile_definitions(TestHookPolicyTest${enabled} PRIVATE FFMPEGPLAYER_ENABLE_TEST_HOOKS=${enabled} FFMPEGPLAYER_EXPECT_TEST_HOOKS=${enabled})
L42:         add_test(NAME TestHookPolicyTest${enabled} COMMAND TestHookPolicyTest${enabled})
L43:     endif()
L44: endforeach()
```

### `ffmpegplayer/src/test/cpp/RECORDER_TESTS.md:1–40`

```text
L1: # Recorder 异步录制测试
L2: 
L3: 线程模型：`onPacket()` 只在短时队列锁下克隆 AVPacket 引用；唯一 Remux Worker 负责打开、写 header、AAC BSF、关键帧门控、时间戳重写、分片、flush、trailer 和 close。Worker 做 I/O 时不持队列锁。独立生命周期锁只串行化 start/stop/release，播放线程不获取它。Java 录制启停也不跨原生等待持有事件回调锁。
L4: 
L5: 输入打开后复制 codec parameters（含 extradata）和 time base；队列项持有对应快照，重连关闭输入不会让 Worker 悬空访问。兼容的重连会重新等待关键帧，并将新时间戳衔接到已写 DTS；流布局或编码参数改变则停止录制，播放继续。
L6: 
L7: 队列上限：512 packets 且 16 MiB（引用缓冲区大小加 side data）。最多另有一个已出队的包正在处理。溢出不等待、不任意丢弃 GOP 中间帧后继续写；关闭入队，记录错误，Worker 丢弃剩余队列并完成文件关闭。正常 stop/release 则关闭入队、排空队列、写 trailer/close 并 join。写入错误会停止录制、丢弃剩余包，保留错误统计。所有 packet 引用用 RAII 管理。
L8: 
L9: `getRecordState()` 返回队列大小、字节数、丢弃数、包数/字节高水位、上限、写入数及错误数。现有播放器 stats 的 `recorder` 对象提供同一快照，原有录制统计字段保留。
L10: 
L11: ## Host tests
L12: 
L13: 在仓库根目录执行（Windows 可在 Visual Studio Developer Command Prompt 下使用 Ninja）：
L14: 
L15: ```sh
L16: cmake -S ffmpegplayer/src/test/cpp -B build/recorder-tests -DCMAKE_BUILD_TYPE=RelWithDebInfo
L17: cmake --build build/recorder-tests --config RelWithDebInfo
L18: ctest --test-dir build/recorder-tests -C RelWithDebInfo --output-on-failure
L19: ```
L20: 
L21: 可添加 `-DRECORDER_ASAN=ON` 检查 packet/快照内存访问。Windows ASan 测试进程需要 Visual Studio 编译器运行库所在目录位于 PATH 中。
L22: 
L23: `RecorderAsyncTest` 编译真正的 PlayerRemuxRecorder.cpp，链接可控制 I/O 阻塞/失败的 FFmpeg 替身；不往用户磁盘写媒体文件。测试使用条件变量建立阻塞点，而非靠 sleep 猜测线程顺序，覆盖：
L24: 
L25: - packet 引用寿命、首关键帧、音频监听独立性、四种格式路径、AAC BSF、反复启停与活动 release；
L26: - 包数、字节数、side data 和单个超大包的边界；
L27: - 写盘/flush/分片 trailer/close 阻塞期间生产者与统计查询仍完成；
L28: - 同时 stop/release、正常排空、溢出丢弃、写失败后回收；
L29: - 重连后旧流参数寿命、时间戳衔接、编码参数变化；
L30: - open/write/flush/trailer/close/packet clone 失败。
L31: 
L32: 每个用例结束都断言 packet、底层引用缓冲区、格式上下文、BSF 和文件句柄计数归零。测试同时验证输出 I/O 不在生产者线程运行。
L33: 
L34: ## Android / 真实媒体验证
L35: 
L36: ```sh
L37: ./gradlew :ffmpegplayer:assembleDebug :ffmpegplayer:assembleRelease :app:testDebugUnitTest
L38: ```
L39: 
L40: Host 替身不验证真实容器字节和 AAC 转换结果；仍需在 Android 设备用实际音视频源录制 MP4/MOV/MKV/TS、分片和重连，使用 ffprobe/播放器校验输出。同步 stop/release 会等待当前底层磁盘调用返回；若操作系统 I/O 永久挂起，C++ 无法安全强杀该调用，但播放线程和队列上限不依赖它返回。
```

## E29｜JNI keep rules and package native names

### `ffmpegplayer/consumer-rules.pro:1–28`

```text
L1: # JNI_OnLoad finds this class and registers its native methods by exact name
L2: # and descriptor.
L3: -keep,allowoptimization class com.example.motro.ffmpeg.FFmpegNative {
L4:     native <methods>;
L5: }
L6: 
L7: # JNI_OnLoad finds this nested class by binary name and native code invokes its
L8: # long constructor.
L9: -keep,allowoptimization class com.example.motro.ffmpeg.FFmpegNative$OesFrameListener {
L10:     <init>(long);
L11: }
L12: 
L13: # The registered setPlayerEventListener descriptor and the native callback
L14: # lookup both use this exact interface/method contract.
L15: -keep,allowoptimization interface com.example.motro.ffmpeg.FFmpegNative$PlayerEventListener {
L16:     void onPlayerEvent(long,java.lang.String,java.lang.String);
L17: }
L18: -keepclassmembers,allowoptimization class * implements com.example.motro.ffmpeg.FFmpegNative$PlayerEventListener {
L19:     void onPlayerEvent(long,java.lang.String,java.lang.String);
L20: }
L21: 
L22: # Native code obtains the runtime sink class from the callback object and looks
L23: # up only these methods by exact name and descriptor.
L24: -keepclassmembers,allowoptimization class com.example.motro.ffmpeg.LiveAudioPcmSink {
L25:     int onAudioPcm(java.nio.ByteBuffer,int,long);
L26:     int onAudioControl(int);
L27:     int getPlaybackHeadFrames();
L28: }
```

### `ffmpegplayer/src/main/java/com/example/motro/ffmpeg/FFmpegNative.java:1–82`

```text
L1: package com.example.motro.ffmpeg;
L2: 
L3: import android.graphics.SurfaceTexture;
L4: import android.util.Log;
L5: import android.view.Surface;
L6: 
L7: public final class FFmpegNative {
L8: 
L9:     private static final String TAG = "FFmpegNative";
L10: 
L11:     static {
L12:         loadRequired("avutil");
L13:         loadRequired("swresample");
L14:         loadRequired("swscale");
L15:         loadRequired("avcodec");
L16:         loadRequired("avformat");
L17:         loadRequired("native-ffmpeg");
L18:     }
L19: 
L20:     private FFmpegNative() {
L21:     }
L22: 
L23:     public static final String EVENT_RECONNECT_DISCONNECTED = "reconnect_disconnected";
L24:     public static final String EVENT_RECONNECTING = "reconnecting";
L25:     public static final String EVENT_WAITING_SOURCE = "waiting_source";
L26:     public static final String EVENT_RECONNECT_SUCCESS = "reconnect_success";
L27:     public static final String EVENT_RECONNECT_EXHAUSTED = "reconnect_exhausted";
L28: 
L29:     public static final int THERMAL_PALETTE_ORIGINAL = 0;
L30:     public static final int THERMAL_PALETTE_WHITE_HOT = 1;
L31:     public static final int THERMAL_PALETTE_IRONBOW = 2;
L32: 
L33:     public interface PlayerEventListener {
L34:         void onPlayerEvent(long handle, String event, String eventJson);
L35:     }
L36: 
L37:     /**
L38:      * Receives MediaCodec SurfaceTexture frame-available callbacks and only
L39:      * signals the native side (sets an atomic pending flag). No GL calls here.
L40:      */
L41:     public static final class OesFrameListener implements SurfaceTexture.OnFrameAvailableListener {
L42:         private final long handle;
L43: 
L44:         public OesFrameListener(long handle) {
L45:             this.handle = handle;
L46:         }
L47: 
L48:         @Override
L49:         public void onFrameAvailable(SurfaceTexture surfaceTexture) {
L50:             FFmpegNative.nativeNotifyOesFrameAvailable(handle);
L51:         }
L52:     }
L53: 
L54:     private static native void nativeNotifyOesFrameAvailable(long handle);
L55: 
L56:     private static void loadRequired(String name) {
L57:         System.loadLibrary(name);
L58:         Log.i(TAG, "loaded " + name);
L59:     }
L60: 
L61:     public static native String getFFmpegVersion();
L62: 
L63:     public static native String getFFmpegBuildConfig();
L64: 
L65:     public static native String getAvailableDecoders();
L66: 
L67:     public static native String getMediaCodecInfo();
L68: 
L69:     public static native String probe(String url, int timeoutMs);
L70: 
L71:     public static native String runDebugCommand(String[] args);
L72: 
L73:     public static native long createPlayer();
L74: 
L75:     public static native String setPlayerSurface(long handle, Surface surface);
L76: 
L77:     public static native String preparePlayer(long handle, String url, int timeoutMs);
L78: 
L79:     public static native String startPlayer(long handle);
L80: 
L81:     public static native String pausePlayer(long handle);
L82: 
```
