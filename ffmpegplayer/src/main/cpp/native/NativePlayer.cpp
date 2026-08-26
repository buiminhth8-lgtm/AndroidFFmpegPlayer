#include "NativePlayer.h"

#include "SnapshotManager.h"

#include <android/log.h>

#include <algorithm>
#include <chrono>
#include <cctype>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <sstream>
#include <utility>

extern "C" {
#include "libavcodec/avcodec.h"
#include "libavcodec/mediacodec.h"
#include "libavformat/avformat.h"
#include "libavutil/avutil.h"
#include "libavutil/dict.h"
#include "libavutil/error.h"
#include "libavutil/frame.h"
#include "libavutil/imgutils.h"
#include "libavutil/mem.h"
#include "libavutil/pixdesc.h"
#include "libavutil/pixfmt.h"
#include "libavutil/rational.h"
#include "libavutil/samplefmt.h"
#include "libavutil/time.h"
#include "libavutil/channel_layout.h"
#include "libavutil/mathematics.h"
#include "libswresample/swresample.h"
#include "libswscale/swscale.h"
}

#define LOG_TAG "FFmpegNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM *g_native_player_java_vm = nullptr;
constexpr int64_t kStartupKeyFrameWaitTimeoutMs = 4000;

// LAT2: bounded timing correlation capacity. Covers the ~1-2 frames in flight
// plus decoder reorder headroom; never grows unbounded.
constexpr size_t kStageTimingMaxRecords = 256;

// LAT3: warm-up samples excluded from steady-state distribution percentiles.
// ~25fps => ~120 samples ~= 5s, past freshness flush / keyframe wait / EGL
// create / MediaCodec warm-up before steady state converges.
constexpr int64_t kStageTimingWarmupSamples = 120;

// LAT6-FINAL: no provider in this workspace can prove sender/server wall time
// against the receiver wall clock or bound their error. Keep the measurement
// gate closed until such evidence exists; RTP/NTP mapping alone is not clock
// synchronization and must never produce an apparently valid E2E percentile.
constexpr int64_t kE2EClockSyncEstimatedErrorUs = -1;
constexpr bool kE2EClockSyncValid = false;

#if FFMPEGPLAYER_ENABLE_TEST_HOOKS
// A3 debug-only hook (default 0 = normal playback behavior unchanged).
std::atomic<int> g_audio_worker_test_delay_ms{0};
#endif

// A2 frozen PCM output contract: S16 / 48000 Hz / stereo / interleaved.
constexpr int kAudioPcmOutputSampleRate = 48000;
constexpr int kAudioPcmOutputChannels = 2;
constexpr AVSampleFormat kAudioPcmOutputFormat = AV_SAMPLE_FMT_S16;

// Java AudioTrack sink control commands (mirror LiveAudioPcmSink). START only
// opens a new write epoch; onAudioPcm still lazily creates/plays AudioTrack on
// the audio worker thread.
constexpr int kAudioSinkCmdStart = 0;
constexpr int kAudioSinkCmdPauseFlush = 1;
constexpr int kAudioSinkCmdRelease = 2;
constexpr int kAudioSinkWriteCancelled = -10000;

// A5: AudioTrack playback-head clock / A-V sync tuning.
constexpr int64_t kAudioClockStaleMs = 500;         // clock considered stale if not refreshed within this window
constexpr int64_t kAudioMasterMaxWaitUs = 150000;   // bounded max wait for video to catch up to audio (150 ms)
constexpr int kAudioMasterWaitPollMs = 2;           // poll interval while waiting for the audio clock
constexpr int64_t kAudioClockPtsJitterToleranceUs = 20000;

// AGC tuning constants.
constexpr int kAgcUpdateIntervalFrames = 5;
constexpr int kAgcPixelStep = 4;
constexpr int kAgcRowStep = 4;
constexpr float kAgcLowPercentile = 0.02f;
constexpr float kAgcHighPercentile = 0.98f;
constexpr float kAgcSmoothingAlpha = 0.15f;
constexpr float kAgcMinSpan = 0.05f;

// OES AGC downsample analysis interval (thermal frames).
constexpr int kOesAgcUpdateIntervalFrames = 5;

std::string escapeJson(const std::string &value) {
    std::ostringstream out;
    for (char c : value) {
        switch (c) {
            case '\\': out << "\\\\"; break;
            case '"': out << "\\\""; break;
            case '\b': out << "\\b"; break;
            case '\f': out << "\\f"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    const char *hex = "0123456789abcdef";
                    out << "\\u00" << hex[(c >> 4) & 0x0f] << hex[c & 0x0f];
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

std::string jsonSuccess(const std::string &message) {
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"" << escapeJson(message) << "\"}";
    return out.str();
}

std::string jsonError(int errorCode, const std::string &message) {
    std::ostringstream out;
    out << "{\"success\":false,\"errorCode\":" << errorCode
        << ",\"errorMessage\":\"" << escapeJson(message) << "\"}";
    return out.str();
}

std::string ffmpegErrorToString(int errorCode) {
    char buffer[AV_ERROR_MAX_STRING_SIZE] = {0};
    if (av_strerror(errorCode, buffer, sizeof(buffer)) < 0) {
        return "Unknown FFmpeg error";
    }
    return std::string(buffer);
}

const char *stateName(PlayerState state) {
    switch (state) {
        case PlayerState::Idle: return "idle";
        case PlayerState::Preparing: return "preparing";
        case PlayerState::Prepared: return "prepared";
        case PlayerState::Playing: return "playing";
        case PlayerState::Paused: return "paused";
        case PlayerState::Disconnected: return "disconnected";
        case PlayerState::WaitingSource: return "waiting_source";
        case PlayerState::Reconnecting: return "reconnecting";
        case PlayerState::Reconnected: return "reconnected";
        case PlayerState::Stopping: return "stopping";
        case PlayerState::Stopped: return "stopped";
        case PlayerState::Error: return "error";
        case PlayerState::Released: return "released";
    }
    return "unknown";
}

std::string snapshotError(const std::string &errorCode,
                          const std::string &message,
                          const std::string &captureMode) {
    std::ostringstream out;
    out << "{\"success\":false,\"errorCode\":\"" << escapeJson(errorCode) << "\","
        << "\"message\":\"" << escapeJson(message) << "\","
        << "\"errorMessage\":\"" << escapeJson(message) << "\","
        << "\"snapshotCaptureMode\":\"" << escapeJson(captureMode) << "\"}";
    return out.str();
}

const char *playerStateName(PlayerState state) {
    switch (state) {
        case PlayerState::Idle: return "IDLE";
        case PlayerState::Preparing: return "PREPARING";
        case PlayerState::Prepared: return "PREPARED";
        case PlayerState::Playing: return "PLAYING";
        case PlayerState::Paused: return "PAUSED";
        case PlayerState::Disconnected: return "DISCONNECTED";
        case PlayerState::WaitingSource: return "WAITING_SOURCE";
        case PlayerState::Reconnecting: return "RECONNECTING";
        case PlayerState::Reconnected: return "RECONNECTED";
        case PlayerState::Stopping: return "STOPPING";
        case PlayerState::Stopped: return "STOPPED";
        case PlayerState::Error: return "ERROR";
        case PlayerState::Released: return "RELEASED";
    }
    return "UNKNOWN";
}

std::string codecName(AVCodecID codecId) {
    const char *name = avcodec_get_name(codecId);
    return name == nullptr ? "unknown" : name;
}

std::string colorRangeName(AVColorRange range) {
    switch (range) {
        case AVCOL_RANGE_MPEG: return "limited";
        case AVCOL_RANGE_JPEG: return "full";
        case AVCOL_RANGE_UNSPECIFIED: return "unspecified";
        default: return "unknown";
    }
}

// thermal render mode: 0 = normal, 1 = white_hot, 2 = ironbow
const char *thermalRenderModeName(int mode) {
    switch (mode) {
        case 1: return "white_hot";
        case 2: return "ironbow";
        default: return "normal";
    }
}

// frame output type: 1 yuv420p_cpu, 2 nv12_cpu, 3 direct_surface, 4 external_oes
const char *frameOutputTypeName(int type) {
    switch (type) {
        case 1: return "yuv420p_cpu";
        case 2: return "nv12_cpu";
        case 3: return "direct_surface";
        case 4: return "external_oes";
        default: return "unknown";
    }
}

// renderer: 1 rgba_nativewindow, 2 yuv_gl, 3 nv12_gl, 4 oes_gl, 5 direct_surface
const char *rendererTypeName(int type) {
    switch (type) {
        case 1: return "rgba_nativewindow";
        case 2: return "yuv_gl";
        case 3: return "nv12_gl";
        case 4: return "oes_gl";
        case 5: return "direct_surface";
        default: return "unknown";
    }
}

constexpr uint32_t kRendererTypeMask = 0xffU;
constexpr uint32_t kRendererFallbackReasonShift = 8U;

uint32_t makeRendererState(int rendererType, int fallbackReasonCode) {
    return (static_cast<uint32_t>(rendererType) & kRendererTypeMask)
           | ((static_cast<uint32_t>(fallbackReasonCode) & kRendererTypeMask)
              << kRendererFallbackReasonShift);
}

int rendererTypeFromState(uint32_t state) {
    return static_cast<int>(state & kRendererTypeMask);
}

int renderFallbackReasonFromState(uint32_t state) {
    return static_cast<int>((state >> kRendererFallbackReasonShift) & kRendererTypeMask);
}

void commitRendererSuccess(std::atomic<uint32_t> &state, int rendererType,
                           bool preserveFallbackReason) {
    uint32_t current = state.load();
    uint32_t next = 0;
    do {
        const int fallbackReason = preserveFallbackReason
                                   ? renderFallbackReasonFromState(current)
                                   : 0;
        next = makeRendererState(rendererType, fallbackReason);
    } while (!state.compare_exchange_weak(current, next));
}

void setRendererFallbackReason(std::atomic<uint32_t> &state, int fallbackReasonCode) {
    uint32_t current = state.load();
    uint32_t next = 0;
    do {
        next = makeRendererState(rendererTypeFromState(current), fallbackReasonCode);
    } while (!state.compare_exchange_weak(current, next));
}

// Renderer input described from the actual frame output type.
const char *renderInputNameFromOutputType(int outputType) {
    switch (outputType) {
        case 1: return "yuv_planes";
        case 2: return "nv12_cpu";
        case 3: return "direct_surface";
        case 4: return "external_oes";
        default: return "unknown";
    }
}

// Renderer implied by the requested render mode.
const char *rendererNameFromRenderMode(RenderMode renderMode) {
    switch (renderMode) {
        case RenderMode::SOFTWARE_RGBA: return "rgba_nativewindow";
        case RenderMode::SOFTWARE_YUV_GL: return "yuv_gl";
        case RenderMode::MEDIACODEC_NV12_GL: return "nv12_gl";
        case RenderMode::MEDIACODEC_OES: return "oes_gl";
        case RenderMode::MEDIACODEC_SURFACE: return "direct_surface";
        default: return "unknown";
    }
}

// Render-fallback reason code -> readable string.
const char *renderFallbackReasonName(int code) {
    switch (code) {
        case 1: return "nv12_gl render failed";
        case 2: return "yuv_gl render failed";
        default: return "";
    }
}

struct AgcResult {
    bool valid = false;
    float blackPoint = 0.0f;
    float whitePoint = 1.0f;
};

// Percentile-based AGC window from the raw 8-bit Y plane.
// Converts raw percentile values to the same 0.0 ~ 1.0 normalized
// thermal range used by the current YUV and NV12 thermal shaders.
AgcResult computeAgcWindow(const uint8_t *yData, int yStride, int width, int height, AVColorRange colorRange) {
    AgcResult result;
    if (yData == nullptr || yStride <= 0 || width <= 0 || height <= 0) {
        return result;
    }
    uint32_t histogram[256] = {};
    uint64_t sampleCount = 0;
    for (int y = 0; y < height; y += kAgcRowStep) {
        const uint8_t *row = yData + static_cast<size_t>(y) * static_cast<size_t>(yStride);
        for (int x = 0; x < width; x += kAgcPixelStep) {
            ++histogram[row[x]];
            ++sampleCount;
        }
    }
    if (sampleCount == 0) {
        return result;
    }

    const uint64_t targetLow = static_cast<uint64_t>(static_cast<double>(sampleCount) * kAgcLowPercentile);
    const uint64_t targetHigh = static_cast<uint64_t>(static_cast<double>(sampleCount) * kAgcHighPercentile);
    uint64_t cumulative = 0;
    int lowValue = 0;
    int highValue = 255;
    bool lowFound = false;
    for (int i = 0; i < 256; ++i) {
        cumulative += histogram[i];
        if (!lowFound && cumulative >= targetLow) {
            lowValue = i;
            lowFound = true;
        }
        if (cumulative >= targetHigh) {
            highValue = i;
            break;
        }
    }

    const auto normalizeY = [colorRange](int rawValue) {
        const float v = rawValue / 255.0f;
        if (colorRange == AVCOL_RANGE_MPEG) {
            return (v - 16.0f / 255.0f) / (219.0f / 255.0f);
        }
        return v;
    };

    float low = std::clamp(normalizeY(lowValue), 0.0f, 1.0f);
    float high = std::clamp(normalizeY(highValue), 0.0f, 1.0f);
    if (!std::isfinite(low) || !std::isfinite(high) || low >= high || (high - low) < kAgcMinSpan) {
        return result;
    }
    result.blackPoint = low;
    result.whitePoint = high;
    result.valid = true;
    return result;
}

double rationalToDouble(AVRational rational) {
    if (rational.den == 0) {
        return 0.0;
    }
    return av_q2d(rational);
}

int64_t nowMs() {
    return av_gettime_relative() / 1000;
}

int64_t steadyNowUs() {
    return std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

int64_t averageUs(int64_t totalUs, int64_t sampleCount) {
    return sampleCount <= 0 ? 0 : totalUs / sampleCount;
}

void updateMax(std::atomic<int64_t> &maxValue, int64_t value) {
    int64_t current = maxValue.load();
    while (value > current && !maxValue.compare_exchange_weak(current, value)) {
    }
}

void recordCost(std::atomic<int64_t> &last,
                std::atomic<int64_t> &total,
                std::atomic<int64_t> &samples,
                std::atomic<int64_t> &maxValue,
                int64_t costUs) {
    last.store(costUs);
    total.fetch_add(costUs);
    samples.fetch_add(1);
    updateMax(maxValue, costUs);
}

// LAT5: classify an av_read_frame() result for the pre-T0 diagnostics tracker.
// Semantics: OK = packet returned; EAGAIN = would-block; TIMEOUT = protocol
// read timeout; EOF = end of stream; ERROR = other failure. Classification only
// feeds diagnostics; reconnect/error handling below is unchanged.
PreT0TimingTracker::ReadResultClass classifyReadResult(int result) {
    if (result >= 0) {
        return PreT0TimingTracker::ReadResultClass::ReadOk;
    }
    if (result == AVERROR(EAGAIN)) {
        return PreT0TimingTracker::ReadResultClass::ReadEagain;
    }
    if (result == AVERROR(ETIMEDOUT)) {
        return PreT0TimingTracker::ReadResultClass::ReadTimeout;
    }
    if (result == AVERROR_EOF) {
        return PreT0TimingTracker::ReadResultClass::ReadEof;
    }
    return PreT0TimingTracker::ReadResultClass::ReadError;
}

bool shouldPreferUdpTransport(const PlayerOptions &options) {
    if (options.rtspTransport == RtspTransport::UDP || options.rtspTransport == RtspTransport::UDP_MULTICAST) {
        return true;
    }
    return options.rtspTransport == RtspTransport::AUTO
           && options.latencyMode == LatencyMode::ULTRA_LOW_LATENCY;
}

bool isKeyPacket(const AVPacket *packet) {
    return packet != nullptr && (packet->flags & AV_PKT_FLAG_KEY) != 0;
}

bool isValidPts(int64_t ptsUs) {
    return ptsUs != AV_NOPTS_VALUE && ptsUs >= 0;
}

int64_t rescaleToUs(int64_t ts, AVRational timeBase) {
    return av_rescale_q(ts, timeBase, AV_TIME_BASE_Q);
}

bool isAudioPlaybackMasterAvailable(bool sourceHasAudio, bool audioEnabled, bool audioPlayable,
                                    bool audioPlaybackClockValid, bool audioClockStale) {
    return sourceHasAudio && audioEnabled && audioPlayable && audioPlaybackClockValid && !audioClockStale;
}

bool isNetworkUrl(const std::string &url) {
    std::string lower = url;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return lower.rfind("rtsp://", 0) == 0
           || lower.rfind("rtsps://", 0) == 0
           || lower.rfind("http://", 0) == 0
           || lower.rfind("https://", 0) == 0
           || lower.rfind("rtmp://", 0) == 0
           || lower.rfind("tcp://", 0) == 0
           || lower.rfind("udp://", 0) == 0;
}

const char *snapshotCaptureModeName(RenderMode renderMode, int rendererType) {
    // GL and direct-Surface modes use the final displayed Surface as the
    // snapshot source even when their current implementation happens to pass
    // through the RGBA NativeWindow renderer. This preserves display-space
    // transforms and keeps the capability contract stable across fallbacks.
    switch (renderMode) {
        case RenderMode::SOFTWARE_RGBA:
            return rendererType == 2 || rendererType == 3 || rendererType == 4
                    ? "surface_pixelcopy"
                    : "native_rgba";
        case RenderMode::SOFTWARE_YUV_GL:
        case RenderMode::MEDIACODEC_NV12_GL:
        case RenderMode::MEDIACODEC_SURFACE:
        case RenderMode::MEDIACODEC_OES:
            return "surface_pixelcopy";
        default:
            return "unsupported";
    }
}

std::string lowerTrimCopy(std::string value) {
    value.erase(std::remove_if(value.begin(), value.end(), [](unsigned char c) {
        return std::isspace(c) != 0;
    }), value.end());
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

JNIEnv *getJniEnvForCurrentThread(bool &attached) {
    attached = false;
    if (g_native_player_java_vm == nullptr) {
        return nullptr;
    }
    JNIEnv *env = nullptr;
    const jint getEnvResult = g_native_player_java_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (getEnvResult == JNI_OK) {
        return env;
    }
    if (getEnvResult == JNI_EDETACHED) {
        if (g_native_player_java_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached = true;
            return env;
        }
    }
    return nullptr;
}

void detachCurrentThreadIfNeeded(bool attached) {
    if (attached && g_native_player_java_vm != nullptr) {
        g_native_player_java_vm->DetachCurrentThread();
    }
}

const char *preferredHardwareDecoderName(AVCodecID codecId) {
    if (codecId == AV_CODEC_ID_H264) {
        return "h264_mediacodec";
    }
    if (codecId == AV_CODEC_ID_HEVC) {
        return "hevc_mediacodec";
    }
    return nullptr;
}

std::string decoderName(const AVCodec *codec) {
    return codec != nullptr && codec->name != nullptr ? codec->name : "";
}

bool parseBoolOption(const std::string &value, bool &out) {
    const std::string normalized = lowerTrimCopy(value);
    if (normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on") {
        out = true;
        return true;
    }
    if (normalized == "0" || normalized == "false" || normalized == "no" || normalized == "off") {
        out = false;
        return true;
    }
    return false;
}

bool parseIntOption(const std::string &value, int &out) {
    char *end = nullptr;
    const long parsed = std::strtol(value.c_str(), &end, 10);
    if (end == value.c_str() || *end != '\0'
        || parsed < std::numeric_limits<int>::min()
        || parsed > std::numeric_limits<int>::max()) {
        return false;
    }
    out = static_cast<int>(parsed);
    return true;
}

bool containsInsensitive(const std::string &value, const char *needle) {
    std::string lowerValue = value;
    std::string lowerNeedle = needle == nullptr ? "" : needle;
    std::transform(lowerValue.begin(), lowerValue.end(), lowerValue.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    std::transform(lowerNeedle.begin(), lowerNeedle.end(), lowerNeedle.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return !lowerNeedle.empty() && lowerValue.find(lowerNeedle) != std::string::npos;
}

} // namespace

void NativePlayer::setJavaVm(JavaVM *javaVm) {
    g_native_player_java_vm = javaVm;
}

NativePlayer::NativePlayer(int64_t logicalHandle)
        : logicalHandle_(logicalHandle) {
    // A3: bounded low-latency PCM queue — target ~150 ms, hard max ~250 ms.
    audioPcmQueue_.configure(150000, 250000);
    LOGI("createPlayer NativePlayer=%p handle=%lld", this,
         static_cast<long long>(logicalHandle_));
}

NativePlayer::~NativePlayer() {
    release();
}

std::string NativePlayer::setSurface(JNIEnv *env, jobject surface) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    if (env == nullptr) {
        return jsonError(-1, "JNIEnv is null");
    }
    if (surface == nullptr) {
        return jsonError(-1, "Surface is null");
    }

    int width = 0;
    int height = 0;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        width = videoWidth_;
        height = videoHeight_;
    }

    jobject newSurfaceRef = env->NewGlobalRef(surface);
    if (newSurfaceRef == nullptr) {
        return jsonError(-1, "NewGlobalRef Surface failed");
    }

    {
        std::lock_guard<std::mutex> surfaceLock(surfaceMutex_);
        deleteSurfaceGlobalRefLocked(env);
        surfaceGlobalRef_ = newSurfaceRef;
    }

    LOGI("setSurface player=%p width=%d height=%d", this, width, height);
    const std::string rgbaResult = renderer_.setSurface(env, surface, width, height);
    const std::string glResult = yuvGlRenderer_.setSurface(env, surface, width, height);
    const std::string oesResult = oesRenderer_.setSurface(env, surface, width, height);
    const std::string nv12GlResult = nv12GlRenderer_.setSurface(env, surface, width, height);
    if (rgbaResult.find("\"success\":true") == std::string::npos) {
        return rgbaResult;
    }
    if (glResult.find("\"success\":true") == std::string::npos) {
        LOGE("setSurface GL YUV renderer failed: %s", glResult.c_str());
    }
    if (oesResult.find("\"success\":true") == std::string::npos) {
        LOGE("setSurface OES renderer failed: %s", oesResult.c_str());
    }
    if (nv12GlResult.find("\"success\":true") == std::string::npos) {
        LOGE("setSurface NV12 GL renderer failed: %s", nv12GlResult.c_str());
    }
    return rgbaResult;
}

std::string NativePlayer::clearSurface() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    LOGI("clearPlayerSurface player=%p", this);
    renderer_.release();
    yuvGlRenderer_.clearSurface();
    oesRenderer_.clearSurface();
    nv12GlRenderer_.clearSurface();
    setRendererFallbackReason(rendererState_, 0);
    bool attached = false;
    JNIEnv *env = getJniEnvForCurrentThread(attached);
    if (env == nullptr) {
        return jsonError(-1, "JNIEnv is not available for clearing Surface");
    }
    {
        std::lock_guard<std::mutex> surfaceLock(surfaceMutex_);
        deleteSurfaceGlobalRefLocked(env);
    }
    detachCurrentThreadIfNeeded(attached);
    return jsonSuccess("surface cleared");
}


int NativePlayer::openInput(const std::string &url, int timeoutMs, bool resetStreamMetadata, std::string &errorMessage) {
    if (resetStreamMetadata) {
        resetVideoPtsDiagnostics();
        sourceHasVideo_.store(false);
        sourceHasAudio_.store(false);
        audioDecodeOpened_.store(false);
        audioPlayable_.store(false);
        lastDecodedAudioPtsUs_.store(0);
        lastPcmPtsUs_.store(0);
        lastDecodedAudioNbSamples_.store(0);
        lastDecodedAudioSampleRate_.store(0);
        lastDecodedAudioChannels_.store(0);
        lastDecodedAudioSampleFormat_.store(-1);
        streamBitRate_.store(0);
        videoBitRate_.store(0);
        audioBitRate_.store(0);
        swsSourceFormat_ = -1;
        swsSourceWidth_ = 0;
        swsSourceHeight_ = 0;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            videoStreamIndex_ = -1;
            audioStreamIndex_ = -1;
            audioSampleRate_ = 0;
            audioChannels_ = 0;
            audioSampleFormat_ = -1;
            audioSampleFormatName_.clear();
            audioDecodeError_.clear();
            audioPlayError_.clear();
            fps_ = 25.0;
            videoCodec_.clear();
            audioCodec_.clear();
            videoWidth_ = 0;
            videoHeight_ = 0;
            lastFrameFormatName_.clear();
            decodedFrameFormat_ = AV_PIX_FMT_NONE;
            lastFrameYStride_.store(0);
            lastFrameColorRange_.store(AVCOL_RANGE_UNSPECIFIED);
            lastFrameOutputType_.store(0);
            setRendererFallbackReason(rendererState_, 0);
        }
    }

    SourceType sourceType = detectSourceType(url);
    PlayerOptions optionsSnapshot;
    bool preferUdpInAuto = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        sourceType_ = sourceType;
        optionsSnapshot = playerOptions_;
        preferUdpInAuto = preferUdpTransport_.load();
    }

    avformat_network_init();
    formatContext_ = avformat_alloc_context();
    if (formatContext_ == nullptr) {
        errorMessage = "avformat_alloc_context failed";
        return -1;
    }
    formatContext_->interrupt_callback.callback = NativePlayer::interruptCallback;
    formatContext_->interrupt_callback.opaque = this;
    if (isRtspSource(sourceType)) {
        formatContext_->max_delay = static_cast<int>(std::min<int64_t>(optionsSnapshot.maxDelayUs, std::numeric_limits<int>::max()));
        formatContext_->max_probe_packets = optionsSnapshot.maxProbePackets;
        if (optionsSnapshot.fflagsNoBuffer) {
            formatContext_->flags |= AVFMT_FLAG_NOBUFFER;
        }
    }

    AVDictionary *options = nullptr;
    if (isRtspSource(sourceType)) {
        const std::string transport = effectiveRtspTransportName(optionsSnapshot, preferUdpInAuto);
        av_dict_set(&options, "rtsp_transport", transport.c_str(), 0);
        if (transport == "tcp") {
            av_dict_set(&options, "rtsp_flags", "prefer_tcp", 0);
            if (optionsSnapshot.tcpNoDelay) {
                av_dict_set(&options, "tcp_nodelay", "1", 0);
            }
        }
        if (optionsSnapshot.fflagsNoBuffer) {
            av_dict_set(&options, "fflags", "nobuffer", 0);
        }
        if (optionsSnapshot.avioDirect) {
            av_dict_set(&options, "avioflags", "direct", 0);
        }
        av_dict_set(&options, "stimeout", std::to_string(optionsSnapshot.openTimeoutUs).c_str(), 0);
        av_dict_set(&options, "timeout", std::to_string(optionsSnapshot.openTimeoutUs).c_str(), 0);
        av_dict_set(&options, "rw_timeout", std::to_string(optionsSnapshot.readTimeoutUs).c_str(), 0);
        av_dict_set(&options, "max_delay", std::to_string(optionsSnapshot.maxDelayUs).c_str(), 0);
        av_dict_set(&options, "buffer_size", std::to_string(optionsSnapshot.socketBufferSize).c_str(), 0);
        av_dict_set(&options, "probesize", std::to_string(optionsSnapshot.probesize).c_str(), 0);
        av_dict_set(&options, "analyzeduration", std::to_string(optionsSnapshot.analyzeduration).c_str(), 0);
        av_dict_set(&options, "max_probe_packets", std::to_string(optionsSnapshot.maxProbePackets).c_str(), 0);
        if (optionsSnapshot.reorderQueueSize >= 0) {
            av_dict_set(&options, "reorder_queue_size", std::to_string(optionsSnapshot.reorderQueueSize).c_str(), 0);
        }

        LOGI("RTSP options sourceType=%s transport=%s latencyMode=%s maxDelayUs=%lld reorderQueueSize=%d bufferSize=%d probesize=%lld analyzeduration=%lld fflagsNoBuffer=%d avioDirect=%d tcpNoDelay=%d",
             sourceTypeName(sourceType).c_str(), transport.c_str(), latencyModeName(optionsSnapshot.latencyMode).c_str(),
             static_cast<long long>(optionsSnapshot.maxDelayUs), optionsSnapshot.reorderQueueSize,
             optionsSnapshot.socketBufferSize, static_cast<long long>(optionsSnapshot.probesize),
             static_cast<long long>(optionsSnapshot.analyzeduration), optionsSnapshot.fflagsNoBuffer ? 1 : 0,
             optionsSnapshot.avioDirect ? 1 : 0, optionsSnapshot.tcpNoDelay ? 1 : 0);
        LOGI("RTSP fmtCtx max_delay=%d max_probe_packets=%d flags=0x%x",
             formatContext_->max_delay, formatContext_->max_probe_packets, formatContext_->flags);
    } else if (isNetworkUrl(url)) {
        const int64_t timeoutUs = static_cast<int64_t>(std::max(timeoutMs, 1)) * 1000;
        const std::string timeoutValue = std::to_string(timeoutUs);
        av_dict_set(&options, "stimeout", timeoutValue.c_str(), 0);
        av_dict_set(&options, "timeout", timeoutValue.c_str(), 0);
        av_dict_set(&options, "rw_timeout", timeoutValue.c_str(), 0);
    }

    int result = avformat_open_input(&formatContext_, url.c_str(), nullptr, &options);
    AVDictionaryEntry *unusedOption = nullptr;
    while ((unusedOption = av_dict_get(options, "", unusedOption, AV_DICT_IGNORE_SUFFIX)) != nullptr) {
        LOGI("unused FFmpeg open option %s=%s", unusedOption->key, unusedOption->value);
    }
    av_dict_free(&options);
    if (result >= 0 && isRtspSource(sourceType)) {
        // LAT5: read back the effective demuxer buffering value after open.
        // configuredMaxDelayUs comes from PlayerOptions; this is the value the
        // RTSP demuxer actually carries (us).
        effectiveFmtCtxMaxDelayUs_.store(formatContext_->max_delay);
        LOGI("RTSP effective max_delay=%d max_probe_packets=%d",
             formatContext_->max_delay, formatContext_->max_probe_packets);
    }
    if (result < 0) {
        errorMessage = ffmpegErrorToString(result);
        LOGE("RTSP open failed url=%s error=%s", url.c_str(), errorMessage.c_str());
        releaseFfmpegResources();
        return result;
    }
    LOGI("open input success sourceType=%s url=%s", sourceTypeName(sourceType).c_str(), url.c_str());
    const int64_t inputOpenCount = inputOpenCount_.fetch_add(1) + 1;
    LOGI("input session opened count=%lld sourceType=%s",
         static_cast<long long>(inputOpenCount), sourceTypeName(sourceType).c_str());

    result = avformat_find_stream_info(formatContext_, nullptr);
    if (result < 0) {
        errorMessage = ffmpegErrorToString(result);
        LOGE("avformat_find_stream_info failed: %s", errorMessage.c_str());
        releaseFfmpegResources();
        return result;
    }
    streamBitRate_.store(std::max<int64_t>(0, formatContext_->bit_rate));

    int selectedVideoStreamIndex = -1;
    int selectedAudioStreamIndex = -1;
    int selectedVideoWidth = 0;
    int selectedVideoHeight = 0;
    int selectedAudioSampleRate = 0;
    int selectedAudioChannels = 0;
    int selectedAudioSampleFormat = -1;
    double selectedFps = 25.0;
    std::string selectedVideoCodec;
    std::string selectedAudioCodec;
    std::string selectedAudioSampleFormatName;
    int64_t selectedVideoBitRate = 0;
    int64_t selectedAudioBitRate = 0;
    // LAT6: selected video stream time_base (RTP media clock evidence).
    int64_t selectedVideoTimeBaseNum = 0;
    int64_t selectedVideoTimeBaseDen = 0;
    for (unsigned int i = 0; i < formatContext_->nb_streams; ++i) {
        AVStream *stream = formatContext_->streams[i];
        if (stream == nullptr || stream->codecpar == nullptr) {
            continue;
        }
        AVCodecParameters *params = stream->codecpar;
        if (params->codec_type == AVMEDIA_TYPE_VIDEO && selectedVideoStreamIndex < 0) {
            selectedVideoStreamIndex = static_cast<int>(i);
            selectedVideoWidth = params->width;
            selectedVideoHeight = params->height;
            selectedVideoCodec = codecName(params->codec_id);
            selectedVideoBitRate = std::max<int64_t>(0, params->bit_rate);
            // LAT6: RTP media clock rate from the real SDP/stream-derived
            // time_base (RTSP/RTP video streams carry 1/clock_rate here).
            // Never hardcoded; 0/0 until a video stream is selected.
            selectedVideoTimeBaseNum = stream->time_base.num;
            selectedVideoTimeBaseDen = stream->time_base.den;
            selectedFps = rationalToDouble(stream->avg_frame_rate.num != 0
                                           ? stream->avg_frame_rate
                                           : stream->r_frame_rate);
            if (selectedFps <= 1.0 || std::isnan(selectedFps) || std::isinf(selectedFps)) {
                selectedFps = 25.0;
            }
        } else if (params->codec_type == AVMEDIA_TYPE_AUDIO && selectedAudioStreamIndex < 0) {
            selectedAudioStreamIndex = static_cast<int>(i);
            selectedAudioCodec = codecName(params->codec_id);
            selectedAudioBitRate = std::max<int64_t>(0, params->bit_rate);
            selectedAudioSampleRate = params->sample_rate;
            selectedAudioChannels = params->ch_layout.nb_channels;
            selectedAudioSampleFormat = params->format;
            const char *sampleFormatName = params->format >= 0 ? av_get_sample_fmt_name(static_cast<AVSampleFormat>(params->format)) : nullptr;
            selectedAudioSampleFormatName = sampleFormatName == nullptr ? "unknown" : sampleFormatName;
        }
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        videoStreamIndex_ = selectedVideoStreamIndex;
        audioStreamIndex_ = selectedAudioStreamIndex;
        videoWidth_ = selectedVideoWidth;
        videoHeight_ = selectedVideoHeight;
        videoCodec_ = selectedVideoCodec;
        audioCodec_ = selectedAudioCodec;
        fps_ = selectedFps;
        audioSampleRate_ = selectedAudioSampleRate;
        audioChannels_ = selectedAudioChannels;
        audioSampleFormat_ = selectedAudioSampleFormat;
        audioSampleFormatName_ = selectedAudioSampleFormatName;
    }
    // LAT6: publish the selected video stream time_base (RTP clock evidence).
    videoStreamTimeBaseNum_.store(selectedVideoTimeBaseNum);
    videoStreamTimeBaseDen_.store(selectedVideoTimeBaseDen);
    videoBitRate_.store(selectedVideoBitRate);
    audioBitRate_.store(selectedAudioBitRate);
    sourceHasVideo_.store(selectedVideoStreamIndex >= 0);
    sourceHasAudio_.store(selectedAudioStreamIndex >= 0);

    if (videoStreamIndex_ < 0) {
        errorMessage = "video stream not found";
        releaseFfmpegResources();
        return -1;
    }

    AVStream *videoStream = formatContext_->streams[videoStreamIndex_];
    const AVCodecID videoCodecId = videoStream->codecpar->codec_id;
    const char *hardwareDecoderName = preferredHardwareDecoderName(videoCodecId);
    const bool hardwareModeRequested = optionsSnapshot.enableHardwareDecode
                                       && (optionsSnapshot.renderMode == RenderMode::MEDIACODEC_SURFACE
                                           || optionsSnapshot.renderMode == RenderMode::MEDIACODEC_OES
                                           || optionsSnapshot.renderMode == RenderMode::MEDIACODEC_NV12_GL)
                                       && hardwareDecoderName != nullptr;
    const std::string requestedDecoderName = hardwareModeRequested ? hardwareDecoderName : codecName(videoCodecId);

    auto setDecoderState = [&](const std::string &requested,
                               const std::string &actual,
                               bool usingHardware,
                               bool fallbackUsed,
                               const std::string &hardwareError) {
        std::lock_guard<std::mutex> lock(mutex_);
        playerOptions_.requestedDecoderName = requested;
        playerOptions_.actualDecoderName = actual;
        playerOptions_.usingHardwareDecoder = usingHardware;
        playerOptions_.hardwareDecodeFallbackUsed = fallbackUsed;
        playerOptions_.hardwareDecodeError = hardwareError;
    };

    auto configureVideoDecoderContext = [&](AVCodecContext *context) {
        if (optionsSnapshot.lowDelayDecode) {
            context->flags |= AV_CODEC_FLAG_LOW_DELAY;
            context->flags2 |= AV_CODEC_FLAG2_FAST;
        }
        if (optionsSnapshot.decoderThreadCount > 0) {
            context->thread_count = optionsSnapshot.decoderThreadCount;
            if (optionsSnapshot.lowDelayDecode) {
                context->thread_type = FF_THREAD_SLICE;
            }
        } else {
            context->thread_count = 0;
        }
        if (optionsSnapshot.skipNonRef) {
            context->skip_frame = AVDISCARD_NONREF;
        }
    };

    auto closeCurrentVideoDecoder = [&]() {
        if (videoCodecContext_ != nullptr) {
            if (mediaCodecContextInitialized_) {
                av_mediacodec_default_free(videoCodecContext_);
                mediaCodecContextInitialized_ = false;
            }
            avcodec_free_context(&videoCodecContext_);
        }
    };

    auto openDecoder = [&](const AVCodec *decoder, bool useHardware, std::string &openError) -> int {
        if (decoder == nullptr) {
            openError = "decoder not found for codec " + videoCodec_;
            return -1;
        }

        closeCurrentVideoDecoder();
        videoCodecContext_ = avcodec_alloc_context3(decoder);
        if (videoCodecContext_ == nullptr) {
            openError = "avcodec_alloc_context3 failed";
            return -1;
        }

        int openResult = avcodec_parameters_to_context(videoCodecContext_, videoStream->codecpar);
        if (openResult < 0) {
            openError = ffmpegErrorToString(openResult);
            LOGE("avcodec_parameters_to_context failed: %s", openError.c_str());
            closeCurrentVideoDecoder();
            return openResult;
        }

        configureVideoDecoderContext(videoCodecContext_);
        LOGI("video decoder options lowDelay=%d threadCount=%d threadType=%d skipNonRef=%d frameDrop=%d thresholdUs=%lld",
             optionsSnapshot.lowDelayDecode ? 1 : 0, videoCodecContext_->thread_count,
             videoCodecContext_->thread_type, optionsSnapshot.skipNonRef ? 1 : 0,
             optionsSnapshot.enableFrameDrop ? 1 : 0,
             static_cast<long long>(optionsSnapshot.dropLateFrameThresholdUs));

        if (useHardware) {
            LOGI("try hardware decoder name=%s", decoderName(decoder).c_str());
            AVMediaCodecContext *mediaCodecCtx = av_mediacodec_alloc_context();
            if (mediaCodecCtx == nullptr) {
                openError = "av_mediacodec_alloc_context failed";
                closeCurrentVideoDecoder();
                return -1;
            }

            if (optionsSnapshot.renderMode == RenderMode::MEDIACODEC_OES) {
                if (!oesRenderer_.isPrepared()) {
                    bool attached = false;
                    JNIEnv *env = getJniEnvForCurrentThread(attached);
                    std::string oesError;
                    const bool prepared = env != nullptr
                                          && oesRenderer_.prepareForOesDecode(env,
                                                                               logicalHandle_,
                                                                               oesError);
                    detachCurrentThreadIfNeeded(attached);
                    if (!prepared) {
                        openError = "mediacodec_oes prepare failed: " + oesError;
                        LOGE("%s", openError.c_str());
                        av_free(mediaCodecCtx);
                        closeCurrentVideoDecoder();
                        return -1;
                    }
                }
                jobject decoderSurface = oesRenderer_.getDecoderSurfaceGlobalRef();
                if (decoderSurface == nullptr) {
                    openError = "mediacodec_oes decoder Surface is null";
                    LOGE("%s", openError.c_str());
                    av_free(mediaCodecCtx);
                    closeCurrentVideoDecoder();
                    return -1;
                }
                openResult = av_mediacodec_default_init(videoCodecContext_, mediaCodecCtx, decoderSurface);
            } else {
                std::lock_guard<std::mutex> surfaceLock(surfaceMutex_);
                if (surfaceGlobalRef_ == nullptr) {
                    openError = "mediacodec_surface requires valid Surface before prepare";
                    av_free(mediaCodecCtx);
                    closeCurrentVideoDecoder();
                    return -1;
                }
                openResult = av_mediacodec_default_init(videoCodecContext_, mediaCodecCtx, surfaceGlobalRef_);
            }
            if (openResult < 0) {
                openError = "MediaCodec surface init failed: " + ffmpegErrorToString(openResult);
                LOGE("MediaCodec surface init failed ret=%d", openResult);
                av_mediacodec_default_free(videoCodecContext_);
                closeCurrentVideoDecoder();
                return openResult;
            }
            mediaCodecContextInitialized_ = true;
            LOGI("MediaCodec surface init success renderMode=%s", renderModeName(optionsSnapshot.renderMode).c_str());
        }

        openResult = avcodec_open2(videoCodecContext_, decoder, nullptr);
        if (openResult < 0) {
            openError = ffmpegErrorToString(openResult);
            if (useHardware) {
                LOGE("avcodec_open2 hardware failed decoder=%s error=%s", decoderName(decoder).c_str(), openError.c_str());
            } else {
                LOGE("decoder open failed: %s", openError.c_str());
            }
            closeCurrentVideoDecoder();
            return openResult;
        }

        if (useHardware) {
            LOGI("avcodec_open2 hardware success decoder=%s", decoderName(decoder).c_str());
        }
        const int64_t decoderOpenCount = videoDecoderOpenCount_.fetch_add(1) + 1;
        const int64_t hardwareOpenCount = useHardware
                                          ? hardwareDecoderOpenCount_.fetch_add(1) + 1
                                          : hardwareDecoderOpenCount_.load();
        LOGI("video decoder session opened count=%lld hardwareCount=%lld decoder=%s hardware=%d",
             static_cast<long long>(decoderOpenCount),
             static_cast<long long>(hardwareOpenCount),
             decoderName(decoder).c_str(), useHardware ? 1 : 0);
        return 0;
    };

    std::string decoderError;
    int decoderOpenResult = -1;
    bool fallbackUsed = false;
    std::string fallbackError;
    if ((optionsSnapshot.renderMode == RenderMode::MEDIACODEC_SURFACE
         || optionsSnapshot.renderMode == RenderMode::MEDIACODEC_NV12_GL)
        && optionsSnapshot.enableHardwareDecode) {
        bool hasSurfaceRef = false;
        {
            std::lock_guard<std::mutex> surfaceLock(surfaceMutex_);
            hasSurfaceRef = surfaceGlobalRef_ != nullptr;
        }
        if (!hasSurfaceRef) {
            errorMessage = "mediacodec_surface/mediacodec_nv12_gl requires valid Surface before prepare";
            LOGE("%s", errorMessage.c_str());
            releaseFfmpegResources();
            return -1;
        }
    }

    LOGI("enableHardwareDecode=%d renderMode=%s",
         optionsSnapshot.enableHardwareDecode ? 1 : 0,
         renderModeName(optionsSnapshot.renderMode).c_str());
    LOGI("select decoder codecId=%s requested=%s",
         codecName(videoCodecId).c_str(), requestedDecoderName.c_str());

    if (hardwareModeRequested) {
        const AVCodec *hardwareDecoder = avcodec_find_decoder_by_name(hardwareDecoderName);
        if (hardwareDecoder == nullptr) {
            fallbackError = std::string("hardware decoder not found: ") + hardwareDecoderName;
            LOGE("hardware decoder failed fallback software reason=%s", fallbackError.c_str());
        } else {
            decoderOpenResult = openDecoder(hardwareDecoder, true, decoderError);
            if (decoderOpenResult < 0) {
                fallbackError = decoderError;
                LOGE("hardware decoder failed fallback software reason=%s", fallbackError.c_str());
            }
        }
        if (decoderOpenResult < 0) {
            if (!optionsSnapshot.hardwareDecodeAllowFallback) {
                errorMessage = fallbackError.empty() ? "hardware decoder failed" : fallbackError;
                releaseFfmpegResources();
                return decoderOpenResult;
            }
            fallbackUsed = true;
            LOGI("fallback to software decoder");
        }
    }

    if (!hardwareModeRequested || decoderOpenResult < 0) {
        const AVCodec *softwareDecoder = avcodec_find_decoder(videoCodecId);
        decoderError.clear();
        decoderOpenResult = openDecoder(softwareDecoder, false, decoderError);
        if (decoderOpenResult < 0) {
            errorMessage = decoderError.empty() ? ("decoder not found for codec " + videoCodec_) : decoderError;
            LOGE("%s", errorMessage.c_str());
            releaseFfmpegResources();
            return decoderOpenResult;
        }
        setDecoderState(requestedDecoderName, decoderName(softwareDecoder), false, fallbackUsed,
                        fallbackUsed ? fallbackError : "");
    } else {
        setDecoderState(requestedDecoderName, decoderName(videoCodecContext_->codec), true, false, "");
        lastSwsScaleCostUs_.store(-1);
        lastRenderLockCostUs_.store(-1);
        lastRenderCopyCostUs_.store(-1);
        lastRenderPostCostUs_.store(-1);
    }
    packet_ = av_packet_alloc();
    decodedFrame_ = av_frame_alloc();
    latestFrame_ = av_frame_alloc();
    rgbaFrame_ = av_frame_alloc();
    if (packet_ == nullptr || decodedFrame_ == nullptr || latestFrame_ == nullptr || rgbaFrame_ == nullptr) {
        errorMessage = "failed to allocate packet/frame";
        releaseFfmpegResources();
        return -1;
    }

    LOGI("video stream index=%d codec=%s width=%d height=%d fps=%.2f", videoStreamIndex_, videoCodec_.c_str(), videoWidth_, videoHeight_, fps_);
    LOGI("decoder open success codec=%s", videoCodec_.c_str());
    if (audioStreamIndex_ >= 0) {
        AVStream *audioStream = formatContext_->streams[audioStreamIndex_];
        const AVCodec *audioDecoder = audioStream != nullptr && audioStream->codecpar != nullptr
                                      ? avcodec_find_decoder(audioStream->codecpar->codec_id)
                                      : nullptr;
        if (audioDecoder == nullptr) {
            audioDecodeError_ = "audio decoder not found for codec " + audioCodec_;
            audioPlayError_ = audioDecodeError_;
            audioDecodeOpened_.store(false);
            audioPlayable_.store(false);
            LOGE("audio decoder open skipped: %s", audioDecodeError_.c_str());
        } else {
            audioCodecContext_ = avcodec_alloc_context3(audioDecoder);
            if (audioCodecContext_ == nullptr) {
                audioDecodeError_ = "avcodec_alloc_context3 failed for audio";
                audioPlayError_ = audioDecodeError_;
                audioDecodeOpened_.store(false);
                audioPlayable_.store(false);
                LOGE("%s", audioDecodeError_.c_str());
            } else {
                result = avcodec_parameters_to_context(audioCodecContext_, audioStream->codecpar);
                if (result < 0) {
                    audioDecodeError_ = ffmpegErrorToString(result);
                    audioPlayError_ = audioDecodeError_;
                    avcodec_free_context(&audioCodecContext_);
                    audioDecodeOpened_.store(false);
                    audioPlayable_.store(false);
                    LOGE("audio avcodec_parameters_to_context failed: %s", audioDecodeError_.c_str());
                } else {
                    result = avcodec_open2(audioCodecContext_, audioDecoder, nullptr);
                    if (result < 0) {
                        audioDecodeError_ = ffmpegErrorToString(result);
                        audioPlayError_ = audioDecodeError_;
                        avcodec_free_context(&audioCodecContext_);
                        audioDecodeOpened_.store(false);
                        audioPlayable_.store(false);
                        LOGE("audio decoder open failed: %s", audioDecodeError_.c_str());
                    } else {
                        audioDecodeError_.clear();
                        audioDecodedFrame_ = av_frame_alloc();
                        if (audioDecodedFrame_ == nullptr) {
                            audioDecodeError_ = "av_frame_alloc failed for audio";
                            audioPlayError_ = audioDecodeError_;
                            avcodec_free_context(&audioCodecContext_);
                            audioDecodeOpened_.store(false);
                            audioPlayable_.store(false);
                            LOGI("audio decoder open skipped: %s", audioDecodeError_.c_str());
                        } else {
                            audioDecodeOpened_.store(true);
                            audioDecodeFirstFrameLogged_ = false;
                            // A4: the full pipeline (decode -> PCM -> queue ->
                            // worker -> JNI sink -> AudioTrack) is wired.
                            // audioPlayable now reflects real capability.
                            recomputeAudioPlayable();
                            if (audioEnabled_.load() && !audioSinkReady_.load()) {
                                audioPlayError_ = "audio sink is not set";
                            } else {
                                audioPlayError_.clear();
                            }
                            LOGI("audio stream index=%d codec=%s sampleRate=%d channels=%d sampleFormat=%s decoderOpened=1",
                                 audioStreamIndex_, audioCodec_.c_str(), audioSampleRate_, audioChannels_, audioSampleFormatName_.c_str());
                        }
                    }
                }
            }
        }
    } else {
        // A0 freeze: audioEnabled_ is the user's live-monitoring request and is
        // intentionally NOT reset here. Source presence is a separate fact
        // (sourceHasAudio_), and playback capability is separate (audioPlayable_).
        audioPlayable_.store(false);
        audioDecodeOpened_.store(false);
        audioPlayError_.clear();
        audioDecodeError_.clear();
        LOGI("prepare source has no audio stream; video-only playback/recording is allowed");
    }
    return 0;
}


std::string NativePlayer::setAudioCallback(JNIEnv *env, jobject callback) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    if (env == nullptr) {
        return jsonError(-1, "JNIEnv is null");
    }

    // A4: register the Java audio PCM sink (LiveAudioPcmSink) and cache the
    // method IDs for the worker's write path and the lifecycle control path.
    jobject newRef = nullptr;
    jmethodID writeMethod = nullptr;
    jmethodID controlMethod = nullptr;
    jmethodID headMethod = nullptr;
    if (callback != nullptr) {
        newRef = env->NewGlobalRef(callback);
        if (newRef == nullptr) {
            return jsonError(-1, "NewGlobalRef audio sink failed");
        }
        jclass sinkClass = env->GetObjectClass(newRef);
        if (sinkClass != nullptr) {
            writeMethod = env->GetMethodID(sinkClass, "onAudioPcm", "(Ljava/nio/ByteBuffer;IJ)I");
            controlMethod = env->GetMethodID(sinkClass, "onAudioControl", "(I)I");
            headMethod = env->GetMethodID(sinkClass, "getPlaybackHeadFrames", "()I");
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
            env->DeleteLocalRef(sinkClass);
        }
        if (writeMethod == nullptr || controlMethod == nullptr || headMethod == nullptr) {
            env->DeleteGlobalRef(newRef);
            return jsonError(-1, "audio sink methods not found");
        }
    }

    // Replace/clear is a lifecycle boundary. Stop the worker first so the old
    // Java sink has no in-flight native callback, then release its AudioTrack
    // before deleting the GlobalRef. A worker-held local ref would be memory
    // safe, but would otherwise leave the old AudioTrack alive and audible.
    if (audioCallbackSet_.load()) {
        flushAudioPcmForDiscontinuity();
        audioPcmQueue_.requestStop();
        stopAudioOutputWorker();
        sendAudioSinkControl(kAudioSinkCmdRelease, "callback_replace_release");
        audioFlushRequested_.store(true);
    }

    {
        std::lock_guard<std::mutex> lock(audioSinkMutex_);
        if (audioSinkGlobalRef_ != nullptr) {
            env->DeleteGlobalRef(audioSinkGlobalRef_);
        }
        audioSinkGlobalRef_ = newRef;
        audioSinkWriteMethodId_ = writeMethod;
        audioSinkControlMethodId_ = controlMethod;
        audioSinkHeadMethodId_ = headMethod;
    }

    const bool callbackSet = newRef != nullptr;
    audioCallbackSet_.store(callbackSet);
    audioSinkReady_.store(callbackSet);
    if (!callbackSet) {
        audioSinkLastErrorCode_.store(0);
    }
    if (audioEnabled_.load() && sourceHasAudio_.load() && !callbackSet) {
        audioPlayError_ = "audio sink is not set";
    } else if (callbackSet) {
        audioPlayError_.clear();
    }
    recomputeAudioPlayable();

    if (callbackSet && audioEnabled_.load() && !pauseRequested_.load()) {
        PlayerState stateSnapshot;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stateSnapshot = state_;
        }
        if (stateSnapshot == PlayerState::Playing || stateSnapshot == PlayerState::Reconnected) {
            startAudioSinkForCurrentGeneration();
            startAudioOutputWorker();
        }
    }

    LOGI("setAudioCallback callbackSet=%d sourceHasAudio=%d audioEnabled=%d audioSinkReady=%d audioPlayable=%d",
         callbackSet ? 1 : 0, sourceHasAudio_.load() ? 1 : 0,
         audioEnabled_.load() ? 1 : 0, audioSinkReady_.load() ? 1 : 0, audioPlayable_.load() ? 1 : 0);
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"audio callback updated\","
        << "\"audioCallbackSet\":" << (audioCallbackSet_.load() ? "true" : "false") << ","
        << "\"audioSinkReady\":" << (audioSinkReady_.load() ? "true" : "false") << ","
        << "\"sourceHasAudio\":" << (sourceHasAudio_.load() ? "true" : "false") << ","
        << "\"audioPlayable\":" << (audioPlayable_.load() ? "true" : "false") << "}";
    return out.str();
}

std::string NativePlayer::setPlayerEventListener(JNIEnv *env, jobject listener) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    if (env == nullptr) {
        return jsonError(-1, "JNIEnv is null");
    }

    jobject newListenerRef = nullptr;
    if (listener != nullptr) {
        newListenerRef = env->NewGlobalRef(listener);
        if (newListenerRef == nullptr) {
            return jsonError(-1, "NewGlobalRef PlayerEventListener failed");
        }
    }

    {
        std::lock_guard<std::mutex> lock(eventListenerMutex_);
        if (playerEventListenerGlobalRef_ != nullptr) {
            env->DeleteGlobalRef(playerEventListenerGlobalRef_);
        }
        playerEventListenerGlobalRef_ = newListenerRef;
    }

    LOGI("setPlayerEventListener listenerSet=%d", newListenerRef != nullptr ? 1 : 0);
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"player event listener updated\","
        << "\"listenerSet\":" << (newListenerRef != nullptr ? "true" : "false") << "}";
    return out.str();
}

std::string NativePlayer::enableAudio(bool enabled) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    // A0 freeze: audioEnabled_ records only the user's live-monitoring request.
    // It does NOT mean source presence, decoder state, recorder state, or sink
    // state. A repeated request is a no-op: Activity Start reapplies the option,
    // and must not create a new generation or restart a healthy sink.
    const bool wasEnabled = audioEnabled_.load();
    if (wasEnabled != enabled) {
        if (!enabled) {
            // Close production first, then invalidate both queued and
            // AudioTrack-buffered data before joining the worker. The playback
            // thread flushes decoder/SWR state at its next safe boundary.
            audioEnabled_.store(false);
            flushAudioPcmForDiscontinuity();
            audioPcmQueue_.requestStop();
            stopAudioOutputWorker();
            audioFlushRequested_.store(true);
            audioPlayError_.clear();
        } else {
            // Build a fresh generation before allowing production. Only start
            // worker/sink while playback is active; enabling in IDLE/PREPARED
            // records policy but leaves no background worker behind.
            flushAudioPcmForDiscontinuity();
            audioFlushRequested_.store(true);
            audioEnabled_.store(true);
            PlayerState stateSnapshot;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                stateSnapshot = state_;
            }
            const bool playbackActive = stateSnapshot == PlayerState::Playing
                                        || stateSnapshot == PlayerState::Reconnected;
            if (playbackActive && !pauseRequested_.load()) {
                startAudioSinkForCurrentGeneration();
                startAudioOutputWorker();
            }
            if (!sourceHasAudio_.load()) {
                audioPlayError_.clear();
            } else if (!audioDecodeOpened_.load()) {
                audioPlayError_ = audioDecodeError_.empty() ? "audio decoder not opened" : audioDecodeError_;
            } else if (!audioSinkReady_.load()) {
                audioPlayError_ = "audio sink is not set";
            } else {
                audioPlayError_.clear();
            }
        }
    }
    recomputeAudioPlayable();
    // Recorder mapping is fixed from the compressed input streams at record
    // start, but its diagnostic snapshot should still reflect the current
    // live-monitoring request after an ON/OFF toggle.
    remuxRecorder_.setAudioPlaybackState(audioEnabled_.load());

    LOGI("enableAudio requested=%d sourceHasAudio=%d audioEnabled=%d audioPlayable=%d error=%s",
         enabled ? 1 : 0, sourceHasAudio_.load() ? 1 : 0,
         audioEnabled_.load() ? 1 : 0, audioPlayable_.load() ? 1 : 0, audioPlayError_.c_str());
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"audio option updated\","
        << "\"sourceHasAudio\":" << (sourceHasAudio_.load() ? "true" : "false") << ","
        << "\"audioEnabled\":" << (audioEnabled_.load() ? "true" : "false") << ","
        << "\"audioPlayable\":" << (audioPlayable_.load() ? "true" : "false") << ","
        << "\"audioDecodeOpened\":" << (audioDecodeOpened_.load() ? "true" : "false") << ","
        << "\"audioCallbackSet\":" << (audioCallbackSet_.load() ? "true" : "false") << ","
        << "\"audioRecordingIndependentOfPlayback\":true,"
        << "\"audioPlayError\":\"" << escapeJson(audioPlayError_) << "\"}";
    return out.str();
}
std::string NativePlayer::prepare(const std::string &url, int timeoutMs) {
    const int64_t prepareStartUs = steadyNowUs();
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    if (url.empty()) {
        return jsonError(-1, "url is empty");
    }

    stop();
    stopRequested_.store(false);
    pauseRequested_.store(false);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        preferUdpTransport_.store(shouldPreferUdpTransport(playerOptions_));
        syncReconnectPolicyFromOptionsLocked();
    }
    transportSwitchRequested_.store(false);
    resetStats();
    clearLastFrame();

    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_ = PlayerState::Preparing;
        url_ = url;
        timeoutMs_ = std::max(timeoutMs, 1);
        isRealtimeInput_ = isNetworkUrl(url);
        sourceType_ = detectSourceType(url);
        errorMessage_.clear();
        lastReconnectError_.clear();
        playerOptions_.requestedDecoderName.clear();
        playerOptions_.actualDecoderName.clear();
        playerOptions_.usingHardwareDecoder = false;
        playerOptions_.hardwareDecodeFallbackUsed = false;
        playerOptions_.hardwareDecodeError.clear();
    }

    LOGI("prepare url=%s timeoutMs=%d realtimeInput=%d", url.c_str(), timeoutMs, isNetworkUrl(url) ? 1 : 0);

    std::string error;
    const int result = openInput(url, timeoutMs_, true, error);
    lastPrepareCostUs_.store(std::max<int64_t>(0, steadyNowUs() - prepareStartUs));
    if (result < 0) {
        preparedAtTimeMs_.store(0);
        setState(PlayerState::Error, error);
        return jsonError(result, error);
    }

    preparedAtTimeMs_.store(nowMs());
    setState(PlayerState::Prepared);
    LOGI("prepare completed costUs=%lld inputOpenCount=%lld decoderOpenCount=%lld hardwareDecoderOpenCount=%lld",
         static_cast<long long>(lastPrepareCostUs_.load()),
         static_cast<long long>(inputOpenCount_.load()),
         static_cast<long long>(videoDecoderOpenCount_.load()),
         static_cast<long long>(hardwareDecoderOpenCount_.load()));

    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"player prepared\","
        << "\"videoStreamIndex\":" << videoStreamIndex_ << ","
        << "\"videoCodec\":\"" << escapeJson(videoCodec_) << "\","
        << "\"enableHardwareDecode\":" << (playerOptions_.enableHardwareDecode ? "true" : "false") << ","
        << "\"renderMode\":\"" << renderModeName(playerOptions_.renderMode) << "\","
        << "\"requestedDecoderName\":\"" << escapeJson(playerOptions_.requestedDecoderName) << "\","
        << "\"actualDecoderName\":\"" << escapeJson(playerOptions_.actualDecoderName) << "\","
        << "\"usingHardwareDecoder\":" << (playerOptions_.usingHardwareDecoder ? "true" : "false") << ","
        << "\"hardwareDecodeFallbackUsed\":" << (playerOptions_.hardwareDecodeFallbackUsed ? "true" : "false") << ","
        << "\"hardwareDecodeError\":\"" << escapeJson(playerOptions_.hardwareDecodeError) << "\","
        << "\"sourceHasVideo\":" << (sourceHasVideo_.load() ? "true" : "false") << ","
        << "\"sourceHasAudio\":" << (sourceHasAudio_.load() ? "true" : "false") << ","
        << "\"videoStreamIndex\":" << videoStreamIndex_ << ","
        << "\"audioStreamIndex\":" << audioStreamIndex_ << ","
        << "\"videoWidth\":" << videoWidth_ << ","
        << "\"videoHeight\":" << videoHeight_ << ","
        << "\"fps\":" << fps_ << ","
        << "\"audioStreamIndex\":" << audioStreamIndex_ << ","
        << "\"audioCodec\":\"" << escapeJson(audioCodec_) << "\","
        << "\"reconnectEnabled\":" << (reconnectEnabled_.load() ? "true" : "false") << ","
        << "\"reconnectMaxRetryCount\":" << reconnectMaxRetryCount_.load() << ","
        << "\"reconnectRetryDelayMs\":" << reconnectRetryDelayMs_.load() << ","
        << "\"infiniteReconnect\":" << (infiniteReconnect_.load() ? "true" : "false") << ","
        << "\"reconnectOnEof\":" << (reconnectOnEof_.load() ? "true" : "false") << ","
        << "\"reconnectOn404\":" << (reconnectOn404_.load() ? "true" : "false") << ","
        << "\"keepWaitingWhenSourceMissing\":" << (keepWaitingWhenSourceMissing_.load() ? "true" : "false") << ","
        << "\"reconnectMaxDelayMs\":" << reconnectMaxDelayMs_.load() << ","
        << "\"sourceType\":\"" << sourceTypeName(sourceType_) << "\","
        << "\"latencyMode\":\"" << latencyModeName(playerOptions_.latencyMode) << "\","
        << "\"rtspTransport\":\"" << rtspTransportName(playerOptions_.rtspTransport) << "\"}";
    return out.str();
}

std::string NativePlayer::start() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    if (!renderer_.hasSurface()) {
        return jsonError(-1, "Surface is not set");
    }

    bool shouldPrepareRealtimeInput = false;
    bool resumeFromPause = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ == PlayerState::Playing) {
            return jsonSuccess("player already playing");
        }
        if (state_ == PlayerState::Paused && playbackThread_.joinable()) {
            audioResumeDiscontinuityRequested_.store(true);
            pauseRequested_.store(false);
            state_ = PlayerState::Playing;
            resumeFromPause = true;
        } else {
            if (state_ != PlayerState::Prepared) {
                return jsonError(-1, "player is not prepared");
            }
            if (playbackThread_.joinable()) {
                return jsonError(-1, "playback thread is already running");
            }
            shouldPrepareRealtimeInput = isRealtimeInput_;
        }
    }

    if (resumeFromPause) {
        // Pause already established a new audio generation and stopped output.
        // The playback thread performs codec flush/keyframe catch-up at its
        // safe boundary; sink and worker resume only for the current generation.
        if (audioEnabled_.load()) {
            startAudioSinkForCurrentGeneration();
            startAudioOutputWorker();
        }
        LOGI("startPlayer resume player=%p", this);
        return jsonSuccess("player resumed");
    }

    if (shouldPrepareRealtimeInput && !prepareRealtimeInputForStart()) {
        std::lock_guard<std::mutex> lock(mutex_);
        return jsonError(-1, errorMessage_.empty() ? "prepared realtime input is invalid" : errorMessage_);
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != PlayerState::Prepared) {
            return jsonError(-1, "player is not prepared");
        }
        stopRequested_.store(false);
        pauseRequested_.store(false);
        resetRealtimeClock();
        beginStartupKeyFrameWait("start");
        startPlayTimeMs_.store(nowMs());
        startToFirstFrameMs_.store(-1);
        state_ = PlayerState::Playing;
    }
    // Restart sink/worker when monitoring is still requested. Prepare stops the
    // worker but preserves the user's audioEnabled policy.
    if (audioEnabled_.load()) {
        startAudioSinkForCurrentGeneration();
        startAudioOutputWorker();
    }
    playbackThread_ = std::thread(&NativePlayer::playbackLoop, this);
    LOGI("startPlayer player=%p inputOpenCount=%lld decoderOpenCount=%lld hardwareDecoderOpenCount=%lld reused=%d",
         this,
         static_cast<long long>(inputOpenCount_.load()),
         static_cast<long long>(videoDecoderOpenCount_.load()),
         static_cast<long long>(hardwareDecoderOpenCount_.load()),
         shouldPrepareRealtimeInput ? 1 : 0);
    return jsonSuccess("player started");
}

std::string NativePlayer::pause() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != PlayerState::Playing && state_ != PlayerState::Reconnected) {
            return jsonError(-1, "player is not playing");
        }
        pauseRequested_.store(true);
        state_ = PlayerState::Paused;
    }
    // Stop future PCM production, flush queue + AudioTrack, invalidate clock,
    // and deterministically join the worker. Realtime input remains open and
    // its playback thread continues draining packets at the live edge.
    flushAudioPcmForDiscontinuity();
    audioPcmQueue_.requestStop();
    stopAudioOutputWorker();
    audioFlushRequested_.store(true);
    LOGI("pausePlayer player=%p", this);
    return jsonSuccess("player paused");
}

std::string NativePlayer::stop() {
    if (isReleased()) {
        LOGI("stopPlayer ignored: player already released");
        return jsonError(-1, "player is released");
    }

    const bool shouldJoin = playbackThread_.joinable();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != PlayerState::Released) {
            state_ = PlayerState::Stopping;
        }
        stopRequested_.store(true);
        pauseRequested_.store(false);
    }

    // Block/flush output before waiting for the producer thread. Java writes
    // are non-blocking, so worker join is deterministic and never depends on
    // AudioTrack buffer drain. This also covers IDLE/PREPARED Release paths.
    flushAudioPcmForDiscontinuity();
    audioPcmQueue_.requestStop();
    stopAudioOutputWorker();

    if (shouldJoin) {
        playbackThread_.join();
    }
    // A producer already inside conversion may observe stop after the first
    // flush. Clear once more after join; no producer remains at this point.
    audioPcmQueue_.flush();
    reconnecting_.store(false);
    waitingSource_.store(false);
    setRendererFallbackReason(rendererState_, 0);
    audioResumeDiscontinuityRequested_.store(false);
    audioFlushRequested_.store(false);

    if (remuxRecorder_.isRecording()) {
        LOGI("stopPlayer auto stop active recorder");
    }
    remuxRecorder_.stop();

    releaseFfmpegResources();
    oesRenderer_.release();
    oesFramePending_.store(false);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != PlayerState::Released) {
            state_ = PlayerState::Stopped;
        }
    }
    LOGI("stopPlayer player=%p", this);
    return jsonSuccess("player stopped");
}

std::string NativePlayer::getState() {
    std::lock_guard<std::mutex> lock(mutex_);
    return buildStateJsonLocked();
}

std::string NativePlayer::getStats() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    const DiagnosticsMode diagnosticsMode = diagnostics_.mode();
    const bool basicDiagnostics = diagnostics_.basicEnabled();
    const bool latencyDiagnostics = diagnostics_.latencyEnabled();

    PlayerState state;
    std::string url;
    std::string lastError;
    std::string reconnectError;
    std::string rtspTransportMode;
    std::string frameFormatName;
    PlayerOptions optionsSnapshot;
    SourceType sourceType;
    bool preferUdpInAuto = false;
    int decodedVideoWidth = 0;
    int decodedVideoHeight = 0;
    int decodedFrameYStride = 0;
    int decodedFrameColorRange = 0;
    int frameOutputType = 0;
    uint64_t decodedFormatGeneration = 0;
    uint32_t rendererState = 0;
    int videoStreamIndex = -1;
    int audioStreamIndex = -1;
    int audioSampleRate = 0;
    int audioChannels = 0;
    double fps = 0.0;
    std::string videoCodec;
    std::string audioCodec;
    std::string audioSampleFormatName;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state = state_;
        url = url_;
        lastError = errorMessage_;
        reconnectError = lastReconnectError_;
        rtspTransportMode = rtspTransportMode_;
        optionsSnapshot = playerOptions_;
        frameFormatName = lastFrameFormatName_;
        sourceType = sourceType_;
        preferUdpInAuto = preferUdpTransport_.load();
        decodedVideoWidth = videoWidth_;
        decodedVideoHeight = videoHeight_;
        decodedFrameYStride = lastFrameYStride_.load();
        decodedFrameColorRange = lastFrameColorRange_.load();
        frameOutputType = lastFrameOutputType_.load();
        decodedFormatGeneration = decodedFormatGeneration_;
        rendererState = rendererState_.load();
        videoStreamIndex = videoStreamIndex_;
        audioStreamIndex = audioStreamIndex_;
        audioSampleRate = audioSampleRate_;
        audioChannels = audioChannels_;
        fps = fps_;
        videoCodec = videoCodec_;
        audioCodec = audioCodec_;
        audioSampleFormatName = audioSampleFormatName_;
    }

    ThermalConfig thermalConfig;
    {
        std::lock_guard<std::mutex> lock(thermalConfigMutex_);
        thermalConfig = thermalConfig_;
    }

    int frameWidth = 0;
    int frameHeight = 0;
    bool hasFrame = false;
    {
        std::lock_guard<std::mutex> lock(lastFrameMutex_);
        hasFrame = hasLastFrame_;
        frameWidth = lastFrameWidth_;
        frameHeight = lastFrameHeight_;
    }

    wallClockUs_.store(steadyNowUs());
    const std::string effectiveSyncMasterValue = effectiveSyncMasterName(optionsSnapshot);
    // LAT0 measured FPS (monotonic wall-time delta between getStats snapshots).
    {
        const int64_t nowMsValue = nowMs();
        const int64_t renderedCount = videoFrameCount_.load();
        const int64_t decodedCount = hardwareDecodedFrameCount_.load() + softwareDecodedFrameCount_.load();
        const int64_t prevTime = prevStatsTimeMs_.load();
        if (prevTime > 0 && nowMsValue > prevTime) {
            const int64_t dtMs = nowMsValue - prevTime;
            if (dtMs >= 1000) {
                if (renderedCount >= prevStatsRenderCount_.load()) {
                    measuredRenderFps_.store((renderedCount - prevStatsRenderCount_.load()) * 1000.0 / dtMs);
                }
                if (decodedCount >= prevStatsDecodeCount_.load()) {
                    measuredDecodeFps_.store((decodedCount - prevStatsDecodeCount_.load()) * 1000.0 / dtMs);
                }
            }
        }
        prevStatsRenderCount_.store(renderedCount);
        prevStatsDecodeCount_.store(decodedCount);
        prevStatsTimeMs_.store(nowMsValue);
    }
    // LAT1 media-timeline PTS backlog (all us on the same video media timeline).
    // Computed from max-seen watermarks (robust to B-frame reorder) within the
    // current generation. -1 means invalid (no PTS / reset / new generation).
    const int64_t maxVideoPkt = basicDiagnostics ? maxVideoPacketPtsUs_.load() : -1;
    const int64_t maxDecIn = basicDiagnostics ? maxDecoderInputPtsUs_.load() : -1;
    const int64_t maxDecOut = basicDiagnostics ? maxDecodedFramePtsUs_.load() : -1;
    const int64_t maxRend = basicDiagnostics ? maxRenderedFramePtsUs_.load() : -1;
    if (basicDiagnostics && maxVideoPkt >= 0 && maxDecIn >= 0 && maxDecOut >= 0 && maxRend >= 0) {
        demuxToDecoderBacklogUs_.store(maxVideoPkt - maxDecIn);
        decoderBacklogUs_.store(maxDecIn - maxDecOut);
        renderBacklogUs_.store(maxDecOut - maxRend);
        clientMediaBacklogUs_.store(maxVideoPkt - maxRend);
        clientMediaBacklogValid_.store(true);
    } else {
        demuxToDecoderBacklogUs_.store(-1);
        decoderBacklogUs_.store(-1);
        renderBacklogUs_.store(-1);
        clientMediaBacklogUs_.store(-1);
        clientMediaBacklogValid_.store(false);
    }
    // LAT3: sample LAT1 media backlog into steady-state distributions (polling
    // cadence; only after warm-up so startup transients don't skew percentiles).
    if (latencyDiagnostics && clientMediaBacklogValid_.load() && steadyStateValid_.load()) {
        diagnostics_.onMediaBacklog(demuxToDecoderBacklogUs_.load(),
                                    decoderBacklogUs_.load(),
                                    renderBacklogUs_.load(),
                                    clientMediaBacklogUs_.load());
    }
    // LAT3: distribution snapshots (bounded rolling window; steady-state only).
    const PlaybackDiagnostics::LatencySnapshot latencySnapshot = diagnostics_.latencySnapshot();
    const LatencyDistribution::Snapshot &demuxBacklogDist = latencySnapshot.demuxBacklog;
    const LatencyDistribution::Snapshot &decoderBacklogDist = latencySnapshot.decoderBacklog;
    const LatencyDistribution::Snapshot &renderBacklogDist = latencySnapshot.renderBacklog;
    const LatencyDistribution::Snapshot &clientMediaBacklogDist = latencySnapshot.clientMediaBacklog;
    const LatencyDistribution::Snapshot &demuxSubmitDist = latencySnapshot.demuxSubmit;
    const LatencyDistribution::Snapshot &decoderResidenceDist = latencySnapshot.decoderResidence;
    const LatencyDistribution::Snapshot &decodeRenderDist = latencySnapshot.decodeRender;
    const LatencyDistribution::Snapshot &renderSubmitDist = latencySnapshot.renderSubmit;
    const LatencyDistribution::Snapshot &packetRenderDist = latencySnapshot.packetRender;
    // LAT5: pre-T0 read/demux return diagnostics (same stats tick).
    const PreT0TimingTracker::Snapshot preT0Timing = diagnostics_.preT0Snapshot();
    // LAT6-FINAL: RTCP SR mapping + srSendToT0 distribution (same stats tick).
    const PlaybackDiagnostics::E2ESnapshot e2eSnapshot = diagnostics_.e2eSnapshot();
    const RtcpSrTracker::Snapshot &e2eSr = e2eSnapshot.senderReport;
    const SendToT0Distribution::Snapshot &e2eDist = e2eSnapshot.sendToT0;
    const int64_t e2eSameFrameMappedCount = e2eSnapshot.sameFrameMappedCount;
    const int64_t e2eSameFrameUnmatchedCount = e2eSnapshot.sameFrameUnmatchedCount;
    const bool e2eRtcpMappingAvailable = e2eSr.hasAnchor
                                         && e2eSr.srMappingValid
                                         && e2eSameFrameMappedCount > 0;
    const bool e2eValid = e2eRtcpMappingAvailable
                          && kE2EClockSyncValid
                          && e2eDist.validCount > 0;
    const int lastDecodedAudioSampleFormat = lastDecodedAudioSampleFormat_.load();
    const char *decodedAudioSampleFormatName = lastDecodedAudioSampleFormat >= 0
            ? av_get_sample_fmt_name(static_cast<AVSampleFormat>(lastDecodedAudioSampleFormat))
            : nullptr;
    const std::string decodedAudioSampleFormatNameStr = decodedAudioSampleFormatName == nullptr
            ? "unknown" : decodedAudioSampleFormatName;
    std::string audioLifecycleState;
    if (!audioEnabled_.load()) {
        audioLifecycleState = "disabled";
    } else if (state == PlayerState::Stopping || state == PlayerState::Stopped
               || state == PlayerState::Released) {
        audioLifecycleState = "stopped";
    } else if (state == PlayerState::Paused || pauseRequested_.load()) {
        audioLifecycleState = "paused";
    } else if (state == PlayerState::Disconnected || state == PlayerState::Reconnecting
               || state == PlayerState::WaitingSource || reconnecting_.load()) {
        audioLifecycleState = "reconnecting";
    } else if (!sourceHasAudio_.load()) {
        audioLifecycleState = "no_audio";
    } else if (!audioDecodeOpened_.load()) {
        audioLifecycleState = "decoder_unavailable";
    } else if (!audioCallbackSet_.load()) {
        audioLifecycleState = "sink_unavailable";
    } else if (!audioWorkerRunning_.load()) {
        audioLifecycleState = state == PlayerState::Prepared ? "prepared" : "worker_stopped";
    } else if (!audioSinkReady_.load()) {
        audioLifecycleState = "sink_error";
    } else if (!audioPlaybackClockValid_.load()) {
        audioLifecycleState = "starting";
    } else {
        audioLifecycleState = "playing";
    }
    const int64_t avgReadFrameCostUs = averageUs(totalReadFrameCostUs_.load(), readFrameCostSampleCount_.load());
    const int64_t avgDecodeCostUs = averageUs(totalDecodeCostUs_.load(), decodeCostSampleCount_.load());
    const int64_t avgSwsScaleCostUs = averageUs(totalSwsScaleCostUs_.load(), swsScaleCostSampleCount_.load());
    const int64_t avgRenderCostUs = averageUs(totalRenderCostUs_.load(), renderCostSampleCount_.load());
    const int64_t avgFrameProcessCostUs = averageUs(totalFrameProcessCostUs_.load(), frameProcessCostSampleCount_.load());
    const int64_t avgVideoDelayUs = averageUs(totalVideoDelayUs_.load(), videoDelaySampleCount_.load());
    const int rendererType = rendererTypeFromState(rendererState);
    const int renderFallbackReasonCode = renderFallbackReasonFromState(rendererState);
    // sws_scale -> RGBA is the renderer actually in use for rgba_nativewindow frames.
    const bool rendererRuntimeActive = state == PlayerState::Playing
                                       || state == PlayerState::Paused
                                       || state == PlayerState::Reconnected;
    const bool surfaceAttached = renderer_.hasSurface();
    const bool swsScaleEnabled = rendererRuntimeActive && surfaceAttached && rendererType == 1;
    const char *snapshotCaptureMode = snapshotCaptureModeName(optionsSnapshot.renderMode, rendererType);
    const bool nativeSnapshotSupported = std::strcmp(snapshotCaptureMode, "native_rgba") == 0;
    const bool snapshotSupported = std::strcmp(snapshotCaptureMode, "unsupported") != 0;
    const std::string decodeBackend = optionsSnapshot.actualDecoderName.empty()
                                      ? "unknown"
                                      : (optionsSnapshot.usingHardwareDecoder ? "mediacodec" : "software");
    const char *frameOutputTypeValue = frameOutputTypeName(frameOutputType);
    const char *rendererValue = rendererTypeName(rendererType);
    const char *renderInputValue = renderInputNameFromOutputType(frameOutputType);
    const char *requestedRendererValue = rendererNameFromRenderMode(optionsSnapshot.renderMode);
    const bool fallbackCapableRendererRequested = optionsSnapshot.renderMode == RenderMode::MEDIACODEC_NV12_GL
                                                   || optionsSnapshot.renderMode == RenderMode::SOFTWARE_YUV_GL;
    const bool renderFallbackUsed = fallbackCapableRendererRequested
                                    && surfaceAttached
                                    && renderFallbackReasonCode != 0
                                    && std::string(requestedRendererValue) != std::string(rendererValue);
    const char *renderFallbackReasonValue = renderFallbackUsed
                                            ? renderFallbackReasonName(renderFallbackReasonCode)
                                            : "";

    std::string effectiveThermalRenderMode;
    std::string thermalInputType;
    if (rendererType == 4) {
        switch (lastOesThermalRenderMode_.load()) {
            case 1: effectiveThermalRenderMode = "white_hot"; break;
            case 2: effectiveThermalRenderMode = "ironbow"; break;
            default: effectiveThermalRenderMode = "normal"; break;
        }
        thermalInputType = "oes_luminance";
    } else if (rendererType == 3) {
        switch (lastNv12ThermalRenderMode_.load()) {
            case 1: effectiveThermalRenderMode = "white_hot"; break;
            case 2: effectiveThermalRenderMode = "ironbow"; break;
            default: effectiveThermalRenderMode = "normal"; break;
        }
        thermalInputType = "nv12_y";
    } else if (rendererType == 2) {
        effectiveThermalRenderMode = thermalRenderModeName(lastThermalRenderMode_.load());
        thermalInputType = "yuv_planes";
    } else {
        effectiveThermalRenderMode = thermalConfig.enabled ? "unavailable" : "normal";
        thermalInputType = "none";
    }
    const bool thermalModeActive = effectiveThermalRenderMode == "white_hot"
                                   || effectiveThermalRenderMode == "ironbow";
    const bool agcRuntimeValid = rendererType == 4 ? oesRenderer_.isAgcValid()
                                 : (rendererType == 3 ? nv12AgcValid_.load()
                                    : (rendererType == 2 ? agcValid_.load() : false));
    const bool agcValidEffective = thermalConfig.enabled
                                   && thermalConfig.agcEnabled
                                   && thermalModeActive
                                   && agcRuntimeValid;
    const float agcBlackEffective = agcValidEffective
                                    ? (rendererType == 4 ? oesRenderer_.getAgcBlackPoint()
                                       : (rendererType == 3 ? nv12AgcBlackPoint_.load() : agcBlackPoint_.load()))
                                    : 0.0f;
    const float agcWhiteEffective = agcValidEffective
                                    ? (rendererType == 4 ? oesRenderer_.getAgcWhitePoint()
                                       : (rendererType == 3 ? nv12AgcWhitePoint_.load() : agcWhitePoint_.load()))
                                    : 1.0f;
    const bool thermalWindowApplied = thermalModeActive;

    std::ostringstream out;
    out << "{\"success\":true,"
        << "\"handle\":" << static_cast<long long>(logicalHandle_) << ","
        << "\"state\":\"" << stateName(state) << "\","
        << "\"playerState\":\"" << playerStateName(state) << "\","
        << "\"diagnosticsMode\":\"" << diagnosticsModeName(diagnosticsMode) << "\","
        << "\"url\":\"" << escapeJson(url) << "\","
        << "\"sourceHasVideo\":" << (sourceHasVideo_.load() ? "true" : "false") << ","
        << "\"sourceHasAudio\":" << (sourceHasAudio_.load() ? "true" : "false") << ","
        << "\"videoStreamIndex\":" << videoStreamIndex << ","
        << "\"audioStreamIndex\":" << audioStreamIndex << ","
        << "\"videoCodec\":\"" << escapeJson(videoCodec) << "\","
        << "\"videoCodecName\":\"" << escapeJson(videoCodec) << "\","
        << "\"decoderName\":\"" << escapeJson(optionsSnapshot.actualDecoderName) << "\","
        << "\"frameFormat\":\"" << escapeJson(frameFormatName.empty() ? "unknown" : frameFormatName) << "\","
        << "\"enableHardwareDecode\":" << (optionsSnapshot.enableHardwareDecode ? "true" : "false") << ","
        << "\"renderMode\":\"" << renderModeName(optionsSnapshot.renderMode) << "\","
        << "\"decodeBackend\":\"" << decodeBackend << "\","
        << "\"frameOutputType\":\"" << frameOutputTypeValue << "\","
        << "\"renderer\":\"" << rendererValue << "\","
        << "\"requestedRenderer\":\"" << requestedRendererValue << "\","
        << "\"renderFallbackUsed\":" << (renderFallbackUsed ? "true" : "false") << ","
        << "\"renderFallbackReason\":\"" << renderFallbackReasonValue << "\","
        << "\"hardwareDecodeAllowFallback\":" << (optionsSnapshot.hardwareDecodeAllowFallback ? "true" : "false") << ","
        << "\"requestedDecoderName\":\"" << escapeJson(optionsSnapshot.requestedDecoderName) << "\","
        << "\"actualDecoderName\":\"" << escapeJson(optionsSnapshot.actualDecoderName) << "\","
        << "\"usingHardwareDecoder\":" << (optionsSnapshot.usingHardwareDecoder ? "true" : "false") << ","
        << "\"hardwareDecodeFallbackUsed\":" << (optionsSnapshot.hardwareDecodeFallbackUsed ? "true" : "false") << ","
        << "\"hardwareDecodeError\":\"" << escapeJson(optionsSnapshot.hardwareDecodeError) << "\","
        << "\"hardwareDecodedFrameCount\":" << hardwareDecodedFrameCount_.load() << ","
        << "\"hardwareRenderedFrameCount\":" << hardwareRenderedFrameCount_.load() << ","
        << "\"hardwareDroppedFrameCount\":" << hardwareDroppedFrameCount_.load() << ","
        << "\"softwareDecodedFrameCount\":" << softwareDecodedFrameCount_.load() << ","
        << "\"softwareRenderedFrameCount\":" << softwareRenderedFrameCount_.load() << ","
        << "\"yuvGlRenderedFrameCount\":" << yuvGlRenderedFrameCount_.load() << ","
        << "\"yuvGlFallbackFrameCount\":" << yuvGlFallbackFrameCount_.load() << ","
        << "\"yuvGlNoSurfaceFrameCount\":" << yuvGlNoSurfaceFrameCount_.load() << ","
        << "\"nv12GlRenderedFrameCount\":" << nv12GlRenderedFrameCount_.load() << ","
        << "\"nv12GlFallbackFrameCount\":" << nv12GlFallbackFrameCount_.load() << ","
        << "\"nv12GlNoSurfaceFrameCount\":" << nv12GlNoSurfaceFrameCount_.load() << ","
        << "\"nv12ThermalRenderedFrameCount\":" << nv12ThermalRenderedCount_.load() << ","
        << "\"lastNv12GlRenderCostUs\":" << nv12GlLastRenderCostUs_.load() << ","
        << "\"avgNv12GlRenderCostUs\":" << averageUs(nv12GlTotalRenderCostUs_.load(), nv12GlRenderCostSampleCount_.load()) << ","
        << "\"maxNv12GlRenderCostUs\":" << nv12GlMaxRenderCostUs_.load() << ","
        << "\"lastNv12GlUploadCostUs\":" << nv12GlLastUploadCostUs_.load() << ","
        << "\"avgNv12GlUploadCostUs\":" << averageUs(nv12GlTotalUploadCostUs_.load(), nv12GlUploadCostSampleCount_.load()) << ","
        << "\"maxNv12GlUploadCostUs\":" << nv12GlMaxUploadCostUs_.load() << ","
        << "\"nv12EglContextCreateCount\":" << nv12GlRenderer_.getEglContextCreateCount() << ","
        << "\"nv12EglSurfaceCreateCount\":" << nv12GlRenderer_.getEglSurfaceCreateCount() << ","
        << "\"nv12EglOwnerThreadId\":" << nv12GlRenderer_.getEglOwnerThreadId() << ","
        << "\"nv12SurfaceGeneration\":" << nv12GlRenderer_.getSurfaceGeneration() << ","
        << "\"nv12AppliedSurfaceGeneration\":" << nv12GlRenderer_.getAppliedSurfaceGeneration() << ","
        << "\"yuvEglContextCreateCount\":" << yuvGlRenderer_.getEglContextCreateCount() << ","
        << "\"yuvEglSurfaceCreateCount\":" << yuvGlRenderer_.getEglSurfaceCreateCount() << ","
        << "\"yuvEglOwnerThreadId\":" << yuvGlRenderer_.getEglOwnerThreadId() << ","
        << "\"yuvSurfaceGeneration\":" << yuvGlRenderer_.getSurfaceGeneration() << ","
        << "\"yuvAppliedSurfaceGeneration\":" << yuvGlRenderer_.getAppliedSurfaceGeneration() << ","
        << "\"renderInputType\":\"" << renderInputValue << "\","
        << "\"oesFrameAvailableCount\":" << oesFrameAvailableCount_.load() << ","
        << "\"oesFrameRenderedCount\":" << oesFrameRenderedCount_.load() << ","
        << "\"oesUpdateTexImageErrorCount\":" << oesRenderer_.getUpdateTexImageErrorCount() << ","
        << "\"oesSurfaceRecreateCount\":" << oesRenderer_.getSurfaceRecreateCount() << ","
        << "\"oesContextRecreateCount\":" << oesRenderer_.getContextRecreateCount() << ","
        << "\"swsScaleEnabled\":" << (swsScaleEnabled ? "true" : "false") << ","
        << "\"swsScaleInvocationCount\":" << swsScaleCostSampleCount_.load() << ","
        << "\"snapshotSupported\":" << (snapshotSupported ? "true" : "false") << ","
        << "\"nativeSnapshotSupported\":" << (nativeSnapshotSupported ? "true" : "false") << ","
        << "\"snapshotCaptureMode\":\"" << snapshotCaptureMode << "\","
        << "\"audioCodec\":\"" << escapeJson(audioCodec) << "\","
        << "\"audioSampleRate\":" << audioSampleRate << ","
        << "\"audioChannels\":" << audioChannels << ","
        << "\"audioSampleFormat\":\"" << escapeJson(audioSampleFormatName) << "\","
        << "\"audioDecodeOpened\":" << (audioDecodeOpened_.load() ? "true" : "false") << ","
        << "\"audioCallbackSet\":" << (audioCallbackSet_.load() ? "true" : "false") << ","
        << "\"readPacketCount\":" << readPacketCount_.load() << ","
        << "\"videoPacketCount\":" << videoPacketCount_.load() << ","
        << "\"audioPacketCount\":" << audioPacketCount_.load() << ","
        << "\"inputPacketBytes\":" << inputPacketBytes_.load() << ","
        << "\"videoPacketBytes\":" << videoPacketBytes_.load() << ","
        << "\"audioPacketBytes\":" << audioPacketBytes_.load() << ","
        << "\"streamBitRate\":" << streamBitRate_.load() << ","
        << "\"videoBitRate\":" << videoBitRate_.load() << ","
        << "\"audioBitRate\":" << audioBitRate_.load() << ","
<< "\"fps\":" << fps << ","
        << "\"metadataFps\":" << fps << ","
        << "\"measuredDecodeFps\":" << measuredDecodeFps_.load() << ","
        << "\"measuredRenderFps\":" << measuredRenderFps_.load() << ","
        << "\"videoPtsGeneration\":" << videoPtsGeneration_.load() << ","
        << "\"latestVideoPacketPtsUs\":" << latestVideoPacketPtsUs_.load() << ","
        << "\"videoPacketPtsValid\":" << (videoPacketPtsValid_.load() ? "true" : "false") << ","
        << "\"latestDecoderInputPtsUs\":" << latestDecoderInputPtsUs_.load() << ","
        << "\"decoderInputPtsValid\":" << (decoderInputPtsValid_.load() ? "true" : "false") << ","
        << "\"latestDecodedFramePtsUs\":" << latestDecodedFramePtsUs_.load() << ","
        << "\"decodedFramePtsValid\":" << (decodedFramePtsValid_.load() ? "true" : "false") << ","
        << "\"latestRenderedFramePtsUs\":" << latestRenderedFramePtsUs_.load() << ","
        << "\"renderedFramePtsValid\":" << (renderedFramePtsValid_.load() ? "true" : "false") << ","
        << "\"maxVideoPacketPtsUs\":" << maxVideoPkt << ","
        << "\"maxDecoderInputPtsUs\":" << maxDecIn << ","
        << "\"maxDecodedFramePtsUs\":" << maxDecOut << ","
        << "\"maxRenderedFramePtsUs\":" << maxRend << ","
        << "\"demuxToDecoderBacklogUs\":" << demuxToDecoderBacklogUs_.load() << ","
        << "\"decoderBacklogUs\":" << decoderBacklogUs_.load() << ","
        << "\"renderBacklogUs\":" << renderBacklogUs_.load() << ","
        << "\"clientMediaBacklogUs\":" << clientMediaBacklogUs_.load() << ","
        << "\"clientMediaBacklogValid\":" << (clientMediaBacklogValid_.load() ? "true" : "false") << ","
        << "\"videoPtsBackwardCount\":" << videoPtsBackwardCount_.load() << ","
        << "\"decoderPtsBackwardCount\":" << decoderPtsBackwardCount_.load() << ","
        << "\"decodedPtsBackwardCount\":" << decodedPtsBackwardCount_.load() << ","
        << "\"renderedPtsBackwardCount\":" << renderedPtsBackwardCount_.load() << ","
        << "\"latencyPtsResetCount\":" << latencyPtsResetCount_.load() << ","
        << "\"stageTimingGeneration\":" << videoPtsGeneration_.load() << ","
        << "\"stageTimingSampleCount\":" << stageTimingSampleCount_.load() << ","
        << "\"steadyStateValid\":" << (steadyStateValid_.load() ? "true" : "false") << ","
        << "\"lastDemuxReturnToDecoderSubmitUs\":" << demuxSubmitTiming_.last.load() << ","
        << "\"avgDemuxReturnToDecoderSubmitUs\":" << averageUs(demuxSubmitTiming_.total.load(), demuxSubmitTiming_.count.load()) << ","
        << "\"maxDemuxReturnToDecoderSubmitUs\":" << demuxSubmitTiming_.max.load() << ","
        << "\"demuxReturnToDecoderSubmitP50Us\":" << demuxSubmitDist.p50 << ","
        << "\"demuxReturnToDecoderSubmitP95Us\":" << demuxSubmitDist.p95 << ","
        << "\"demuxReturnToDecoderSubmitP99Us\":" << demuxSubmitDist.p99 << ","
        << "\"demuxReturnToDecoderSubmitDistCount\":" << demuxSubmitDist.count << ","
        << "\"lastDecoderSubmitToOutputUs\":" << decoderTiming_.last.load() << ","
        << "\"avgDecoderSubmitToOutputUs\":" << averageUs(decoderTiming_.total.load(), decoderTiming_.count.load()) << ","
        << "\"maxDecoderSubmitToOutputUs\":" << decoderTiming_.max.load() << ","
        << "\"decoderSubmitToOutputP50Us\":" << decoderResidenceDist.p50 << ","
        << "\"decoderSubmitToOutputP95Us\":" << decoderResidenceDist.p95 << ","
        << "\"decoderSubmitToOutputP99Us\":" << decoderResidenceDist.p99 << ","
        << "\"decoderSubmitToOutputDistCount\":" << decoderResidenceDist.count << ","
        << "\"lastDecodedOutputToRenderBeginUs\":" << decodeRenderTiming_.last.load() << ","
        << "\"avgDecodedOutputToRenderBeginUs\":" << averageUs(decodeRenderTiming_.total.load(), decodeRenderTiming_.count.load()) << ","
        << "\"maxDecodedOutputToRenderBeginUs\":" << decodeRenderTiming_.max.load() << ","
        << "\"decodedOutputToRenderBeginP50Us\":" << decodeRenderDist.p50 << ","
        << "\"decodedOutputToRenderBeginP95Us\":" << decodeRenderDist.p95 << ","
        << "\"decodedOutputToRenderBeginP99Us\":" << decodeRenderDist.p99 << ","
        << "\"decodedOutputToRenderBeginDistCount\":" << decodeRenderDist.count << ","
        << "\"lastRenderBeginToSubmitUs\":" << renderTiming_.last.load() << ","
        << "\"avgRenderBeginToSubmitUs\":" << averageUs(renderTiming_.total.load(), renderTiming_.count.load()) << ","
        << "\"maxRenderBeginToSubmitUs\":" << renderTiming_.max.load() << ","
        << "\"renderBeginToSubmitP50Us\":" << renderSubmitDist.p50 << ","
        << "\"renderBeginToSubmitP95Us\":" << renderSubmitDist.p95 << ","
        << "\"renderBeginToSubmitP99Us\":" << renderSubmitDist.p99 << ","
        << "\"renderBeginToSubmitDistCount\":" << renderSubmitDist.count << ","
        << "\"lastPacketReadyToRenderSubmitUs\":" << packetRenderTiming_.last.load() << ","
        << "\"avgPacketReadyToRenderSubmitUs\":" << averageUs(packetRenderTiming_.total.load(), packetRenderTiming_.count.load()) << ","
        << "\"maxPacketReadyToRenderSubmitUs\":" << packetRenderTiming_.max.load() << ","
        << "\"packetReadyToRenderSubmitP50Us\":" << packetRenderDist.p50 << ","
        << "\"packetReadyToRenderSubmitP95Us\":" << packetRenderDist.p95 << ","
        << "\"packetReadyToRenderSubmitP99Us\":" << packetRenderDist.p99 << ","
        << "\"packetReadyToRenderSubmitDistCount\":" << packetRenderDist.count << ","
        << "\"demuxToDecoderBacklogP50Us\":" << demuxBacklogDist.p50 << ","
        << "\"demuxToDecoderBacklogP95Us\":" << demuxBacklogDist.p95 << ","
        << "\"demuxToDecoderBacklogP99Us\":" << demuxBacklogDist.p99 << ","
        << "\"demuxToDecoderBacklogAvgUs\":" << demuxBacklogDist.avg << ","
        << "\"demuxToDecoderBacklogMaxUs\":" << demuxBacklogDist.max << ","
        << "\"demuxToDecoderBacklogDistCount\":" << demuxBacklogDist.count << ","
        << "\"decoderBacklogP50Us\":" << decoderBacklogDist.p50 << ","
        << "\"decoderBacklogP95Us\":" << decoderBacklogDist.p95 << ","
        << "\"decoderBacklogP99Us\":" << decoderBacklogDist.p99 << ","
        << "\"decoderBacklogAvgUs\":" << decoderBacklogDist.avg << ","
        << "\"decoderBacklogMaxUs\":" << decoderBacklogDist.max << ","
        << "\"decoderBacklogDistCount\":" << decoderBacklogDist.count << ","
        << "\"renderBacklogP50Us\":" << renderBacklogDist.p50 << ","
        << "\"renderBacklogP95Us\":" << renderBacklogDist.p95 << ","
        << "\"renderBacklogP99Us\":" << renderBacklogDist.p99 << ","
        << "\"renderBacklogAvgUs\":" << renderBacklogDist.avg << ","
        << "\"renderBacklogMaxUs\":" << renderBacklogDist.max << ","
        << "\"renderBacklogDistCount\":" << renderBacklogDist.count << ","
        << "\"clientMediaBacklogP50Us\":" << clientMediaBacklogDist.p50 << ","
        << "\"clientMediaBacklogP95Us\":" << clientMediaBacklogDist.p95 << ","
        << "\"clientMediaBacklogP99Us\":" << clientMediaBacklogDist.p99 << ","
        << "\"clientMediaBacklogAvgUs\":" << clientMediaBacklogDist.avg << ","
        << "\"clientMediaBacklogMaxUs\":" << clientMediaBacklogDist.max << ","
        << "\"clientMediaBacklogDistCount\":" << clientMediaBacklogDist.count << ","
        << "\"decoderTimingUnmatchedCount\":" << decoderTimingUnmatchedCount_.load() << ","
        << "\"renderTimingUnmatchedCount\":" << renderTimingUnmatchedCount_.load() << ","
        << "\"stageTimingForcedEvictionCount\":" << stageTimingForcedEvictionCount_.load() << ","
        << "\"stageTimingResetCount\":" << stageTimingResetCount_.load() << ","
        << "\"stageTimingClockAnomalyCount\":" << stageTimingClockAnomalyCount_.load() << ","
        << "\"videoWidth\":" << decodedVideoWidth << ","
        << "\"videoHeight\":" << decodedVideoHeight << ","
        << "\"decodedFormatGeneration\":" << decodedFormatGeneration << ","
        << "\"videoFormatGeneration\":" << decodedFormatGeneration << ","
        << "\"decodedFormatChangeCount\":" << decodedFormatChangeCount_.load() << ","
        << "\"realtimeClockFormatResetCount\":" << realtimeClockFormatResetCount_.load() << ","
        << "\"frameColorRange\":\"" << colorRangeName(static_cast<AVColorRange>(decodedFrameColorRange)) << "\","
        << "\"frameColorRangeValue\":" << decodedFrameColorRange << ","
        << "\"frameYStride\":" << decodedFrameYStride << ","
        << "\"videoFrameCount\":" << videoFrameCount_.load() << ","
        << "\"audioFrameCount\":" << audioFrameCount_.load() << ","
        << "\"audioDecodedFrameCount\":" << audioFrameCount_.load() << ","
        << "\"audioDecodedSampleCount\":" << audioDecodedSampleCount_.load() << ","
        << "\"audioDecodeErrorCount\":" << audioDecodeErrorCount_.load() << ","
        << "\"lastDecodedAudioPtsUs\":" << lastDecodedAudioPtsUs_.load() << ","
        << "\"lastDecodedAudioNbSamples\":" << lastDecodedAudioNbSamples_.load() << ","
        << "\"lastDecodedAudioSampleRate\":" << lastDecodedAudioSampleRate_.load() << ","
        << "\"lastDecodedAudioChannels\":" << lastDecodedAudioChannels_.load() << ","
        << "\"lastDecodedAudioSampleFormat\":\"" << escapeJson(decodedAudioSampleFormatNameStr) << "\","
        << "\"lastAudioDecodeCostUs\":" << lastAudioDecodeCostUs_.load() << ","
        << "\"avgAudioDecodeCostUs\":" << averageUs(totalAudioDecodeCostUs_.load(), audioDecodeCostSampleCount_.load()) << ","
        << "\"maxAudioDecodeCostUs\":" << maxAudioDecodeCostUs_.load() << ","
        << "\"audioOutputSampleRate\":" << kAudioPcmOutputSampleRate << ","
        << "\"audioOutputChannels\":" << kAudioPcmOutputChannels << ","
        << "\"audioOutputSampleFormat\":\"s16\","
        << "\"audioOutputInterleaved\":true,"
        << "\"audioSwrReconfigureCount\":" << audioSwrReconfigureCount_.load() << ","
        << "\"audioPcmBlockCount\":" << audioPcmBlockCount_.load() << ","
        << "\"audioPcmSampleCount\":" << audioPcmSampleCount_.load() << ","
        << "\"audioPcmByteCount\":" << audioPcmByteCount_.load() << ","
        << "\"audioResampleErrorCount\":" << audioResampleErrorCount_.load() << ","
        << "\"lastPcmPtsUs\":" << lastPcmPtsUs_.load() << ","
        << "\"lastAudioResampleCostUs\":" << lastAudioResampleCostUs_.load() << ","
        << "\"avgAudioResampleCostUs\":" << averageUs(totalAudioResampleCostUs_.load(), audioResampleCostSampleCount_.load()) << ","
        << "\"maxAudioResampleCostUs\":" << maxAudioResampleCostUs_.load() << ","
        << "\"audioQueueDurationUs\":" << audioPcmQueue_.durationUs() << ","
        << "\"audioQueueBlockCount\":" << audioPcmQueue_.blockCount() << ","
        << "\"audioQueueBytes\":" << audioPcmQueue_.byteCount() << ","
        << "\"audioQueueHighWatermarkUs\":" << audioPcmQueue_.highWatermarkUs() << ","
        << "\"audioQueueDropCount\":" << audioPcmQueue_.dropCount() << ","
        << "\"audioQueueDroppedSampleCount\":" << audioPcmQueue_.droppedSampleCount() << ","
        << "\"audioQueueFlushCount\":" << audioPcmQueue_.flushCount() << ","
        << "\"audioQueueGeneration\":" << audioQueueGeneration_.load() << ","
        << "\"audioGeneration\":" << audioQueueGeneration_.load() << ","
        << "\"audioLifecycleState\":\"" << audioLifecycleState << "\","
        << "\"audioWorkerRunning\":" << (audioWorkerRunning_.load() ? "true" : "false") << ","
        << "\"audioWorkerStartCount\":" << audioWorkerStartCount_.load() << ","
        << "\"audioWorkerJoinCount\":" << audioWorkerJoinCount_.load() << ","
        << "\"audioWorkerStaleBlockCount\":" << audioWorkerStaleBlockCount_.load() << ","
        << "\"audioWorkerConsumedBlockCount\":" << audioWorkerConsumedBlockCount_.load() << ","
        << "\"audioWorkerConsumedSampleCount\":" << audioWorkerConsumedSampleCount_.load() << ","
        << "\"audioWorkerConsumedByteCount\":" << audioWorkerConsumedByteCount_.load() << ","
        << "\"lastConsumedPcmPtsUs\":" << lastConsumedPcmPtsUs_.load() << ","
        << "\"audioSinkReady\":" << (audioSinkReady_.load() ? "true" : "false") << ","
        << "\"audioSinkWriteCount\":" << audioSinkWriteCount_.load() << ","
        << "\"audioSinkWrittenByteCount\":" << audioSinkWrittenByteCount_.load() << ","
        << "\"audioSinkWriteErrorCount\":" << audioSinkWriteErrorCount_.load() << ","
        << "\"audioSinkControlledCancelCount\":" << audioSinkControlledCancelCount_.load() << ","
        << "\"audioSinkRestartCount\":" << audioSinkRestartCount_.load() << ","
        << "\"audioReconnectRecoveryCount\":" << audioReconnectRecoveryCount_.load() << ","
        << "\"audioSinkLastErrorCode\":" << audioSinkLastErrorCode_.load() << ","
        << "\"lastAudioSinkWriteCostUs\":" << lastAudioSinkWriteCostUs_.load() << ","
        << "\"avgAudioSinkWriteCostUs\":" << averageUs(totalAudioSinkWriteCostUs_.load(), audioSinkWriteCostSampleCount_.load()) << ","
        << "\"maxAudioSinkWriteCostUs\":" << maxAudioSinkWriteCostUs_.load() << ","
        << "\"renderedFrameCount\":" << renderedFrameCount_.load() << ","
        << "\"droppedVideoFrameCount\":" << droppedVideoFrameCount_.load() << ","
        << "\"recording\":" << (remuxRecorder_.isRecording() ? "true" : "false") << ","
        << "\"recordVideoPacketCount\":" << remuxRecorder_.getVideoPacketCount() << ","
        << "\"recordAudioPacketCount\":" << remuxRecorder_.getAudioPacketCount() << ","
        << "\"recordCompletedSegmentCount\":" << remuxRecorder_.getCompletedSegmentCount() << ","
        << "\"surfaceAttached\":" << (surfaceAttached ? "true" : "false") << ","
        << "\"hasLastFrame\":" << (hasFrame ? "true" : "false") << ","
        << "\"lastFrameWidth\":" << frameWidth << ","
        << "\"lastFrameHeight\":" << frameHeight << ","
        << "\"audioEnabled\":" << (audioEnabled_.load() ? "true" : "false") << ","
        << "\"audioPlayable\":" << (audioPlayable_.load() ? "true" : "false") << ","
        // audioClockUs is LEGACY / PRE-PLAYBACK: it mirrors the last compressed
        // audio packet PTS and is NOT a speaker playback clock. The real audible
        // clock is audioPlaybackClockUs (AudioTrack playback head).
        << "\"audioClockUs\":" << audioClockUs_.load() << ","
        << "\"audioPlaybackClockValid\":" << (audioPlaybackClockValid_.load() ? "true" : "false") << ","
        << "\"audioPlaybackClockUs\":" << audioPlaybackClockUs_.load() << ","
        << "\"audioPlaybackHeadFrames\":" << audioPlaybackHeadFrames_.load() << ","
        << "\"audioClockGeneration\":" << audioClockGeneration_.load() << ","
        << "\"audioClockResetCount\":" << audioClockResetCount_.load() << ","
        << "\"audioClockStaleCount\":" << audioClockStaleCount_.load() << ","
        << "\"audioClockPtsDiscontinuityCount\":" << audioClockPtsDiscontinuityCount_.load() << ","
        << "\"audioVideoDiffUs\":" << audioVideoDiffUs_.load() << ","
        << "\"videoClockUs\":" << videoClockUs_.load() << ","
        << "\"wallClockUs\":" << wallClockUs_.load() << ","
        << "\"lastReadPacketTimeMs\":" << lastReadPacketTimeMs_.load() << ","
        << "\"lastVideoFrameTimeMs\":" << lastVideoFrameTimeMs_.load() << ","
        << "\"lastAudioFrameTimeMs\":" << lastAudioFrameTimeMs_.load() << ","
        << "\"lastRenderTimeMs\":" << lastRenderTimeMs_.load() << ","
        << "\"lastSnapshotTimeMs\":" << lastSnapshotTimeMs_.load() << ","
        << "\"startPlayTimeMs\":" << startPlayTimeMs_.load() << ","
        << "\"preparedAtTimeMs\":" << preparedAtTimeMs_.load() << ","
        << "\"lastPrepareCostUs\":" << lastPrepareCostUs_.load() << ","
        << "\"lastPrepareToStartDelayMs\":" << lastPrepareToStartDelayMs_.load() << ","
        << "\"startToFirstFrameMs\":" << startToFirstFrameMs_.load() << ","
        << "\"inputOpenCount\":" << inputOpenCount_.load() << ","
        << "\"videoDecoderOpenCount\":" << videoDecoderOpenCount_.load() << ","
        << "\"hardwareDecoderOpenCount\":" << hardwareDecoderOpenCount_.load() << ","
        << "\"realtimeStartInputReuseCount\":" << realtimeStartInputReuseCount_.load() << ","
        << "\"startupFreshnessFlushCount\":" << startupFreshnessFlushCount_.load() << ","
        << "\"startupFreshnessFlushErrorCount\":" << startupFreshnessFlushErrorCount_.load() << ","
        << "\"lastError\":\"" << escapeJson(lastError) << "\"," 
        << "\"sourceType\":\"" << sourceTypeName(sourceType) << "\","
        << "\"latencyMode\":\"" << latencyModeName(optionsSnapshot.latencyMode) << "\","
        << "\"rtspTransport\":\"" << rtspTransportName(optionsSnapshot.rtspTransport) << "\","
        << "\"effectiveRtspTransport\":\"" << effectiveRtspTransportName(optionsSnapshot, preferUdpInAuto) << "\","
        << "\"syncMaster\":\"" << syncMasterName(optionsSnapshot.syncMaster) << "\","
        << "\"effectiveSyncMaster\":\"" << effectiveSyncMasterValue << "\","
        << "\"maxDelayUs\":" << optionsSnapshot.maxDelayUs << ","
        << "\"reorderQueueSize\":" << optionsSnapshot.reorderQueueSize << ","
        << "\"socketBufferSize\":" << optionsSnapshot.socketBufferSize << ","
        << "\"probesize\":" << optionsSnapshot.probesize << ","
        << "\"analyzeduration\":" << optionsSnapshot.analyzeduration << ","
        << "\"maxProbePackets\":" << optionsSnapshot.maxProbePackets << ","
        << "\"fflagsNoBuffer\":" << (optionsSnapshot.fflagsNoBuffer ? "true" : "false") << ","
        << "\"avioDirect\":" << (optionsSnapshot.avioDirect ? "true" : "false") << ","
        << "\"tcpNoDelay\":" << (optionsSnapshot.tcpNoDelay ? "true" : "false") << ","
        << "\"lowDelayDecode\":" << (optionsSnapshot.lowDelayDecode ? "true" : "false") << ","
        << "\"decoderThreadCount\":" << optionsSnapshot.decoderThreadCount << ","
        << "\"enableFrameDrop\":" << (optionsSnapshot.enableFrameDrop ? "true" : "false") << ","
        << "\"dropLateFrameThresholdUs\":" << optionsSnapshot.dropLateFrameThresholdUs << ","
        << "\"enablePacketDrop\":" << (optionsSnapshot.enablePacketDrop ? "true" : "false") << ","
        << "\"dropLatePacketThresholdUs\":" << optionsSnapshot.dropLatePacketThresholdUs << ","
        << "\"enableLatestFrameOnly\":" << (optionsSnapshot.enableLatestFrameOnly ? "true" : "false") << ","
        << "\"skipNonRef\":" << (optionsSnapshot.skipNonRef ? "true" : "false") << ","
        << "\"cacheLastFrameEveryN\":" << optionsSnapshot.cacheLastFrameEveryN << ","
        << "\"lastReadFrameCostUs\":" << lastReadFrameCostUs_.load() << ","
        << "\"avgReadFrameCostUs\":" << avgReadFrameCostUs << ","
        << "\"maxReadFrameCostUs\":" << maxReadFrameCostUs_.load() << ","
        // LAT5: pre-T0 RTSP/RTP isolation diagnostics. read duration is
        // readWaitAndDemux (R1-R0), NOT network latency. All us, monotonic
        // clock for durations/gaps; PTS delta is media-timeline.
        << "\"readCallCount\":" << preT0Timing.readCallCount << ","
        << "\"videoReadCallCount\":" << preT0Timing.videoReadCallCount << ","
        << "\"lastAvReadFrameDurationUs\":" << preT0Timing.lastReadDurationUs << ","
        << "\"avgAvReadFrameDurationUs\":" << preT0Timing.avgReadDurationUs << ","
        << "\"maxAvReadFrameDurationUs\":" << preT0Timing.maxReadDurationUs << ","
        << "\"avReadFrameDurationP50Us\":" << preT0Timing.readDurationP50Us << ","
        << "\"avReadFrameDurationP95Us\":" << preT0Timing.readDurationP95Us << ","
        << "\"avReadFrameDurationP99Us\":" << preT0Timing.readDurationP99Us << ","
        << "\"avReadFrameDurationDistCount\":" << preT0Timing.readDurationDistCount << ","
        << "\"lastVideoPacketReturnGapUs\":" << preT0Timing.lastVideoReturnGapUs << ","
        << "\"avgVideoPacketReturnGapUs\":" << preT0Timing.avgVideoReturnGapUs << ","
        << "\"maxVideoPacketReturnGapUs\":" << preT0Timing.maxVideoReturnGapUs << ","
        << "\"videoPacketReturnGapP50Us\":" << preT0Timing.videoReturnGapP50Us << ","
        << "\"videoPacketReturnGapP95Us\":" << preT0Timing.videoReturnGapP95Us << ","
        << "\"videoPacketReturnGapP99Us\":" << preT0Timing.videoReturnGapP99Us << ","
        << "\"videoPacketReturnGapDistCount\":" << preT0Timing.videoReturnGapDistCount << ","
        << "\"lastVideoPacketPtsDeltaUs\":" << preT0Timing.lastVideoPtsDeltaUs << ","
        << "\"avgVideoPacketPtsDeltaUs\":" << preT0Timing.avgVideoPtsDeltaUs << ","
        << "\"maxVideoPacketPtsDeltaUs\":" << preT0Timing.maxVideoPtsDeltaUs << ","
        << "\"videoPacketPtsDeltaSampleCount\":" << preT0Timing.videoPtsDeltaSampleCount << ","
        << "\"fastVideoReturnThresholdUs\":" << static_cast<long long>(PreT0TimingTracker::kFastVideoReturnThresholdUs) << ","
        << "\"fastReturnPacketCount\":" << preT0Timing.fastReturnPacketCount << ","
        << "\"currentFastReturnBurstLength\":" << preT0Timing.currentFastReturnBurstLength << ","
        << "\"maxFastReturnBurstLength\":" << preT0Timing.maxFastReturnBurstLength << ","
        << "\"readStallGt100MsCount\":" << preT0Timing.readStallGt100MsCount << ","
        << "\"readStallGt250MsCount\":" << preT0Timing.readStallGt250MsCount << ","
        << "\"readStallGt500MsCount\":" << preT0Timing.readStallGt500MsCount << ","
        << "\"readStallGt1000MsCount\":" << preT0Timing.readStallGt1000MsCount << ","
        << "\"maxReadStallUs\":" << preT0Timing.maxReadStallUs << ","
        << "\"readEagainCount\":" << preT0Timing.readEagainCount << ","
        << "\"readTimeoutCount\":" << preT0Timing.readTimeoutCount << ","
        << "\"readEofCount\":" << preT0Timing.readEofCount << ","
        << "\"readErrorCount\":" << preT0Timing.readErrorCount << ","
        // LAT6-FINAL route B. AV_PKT_DATA_RTCP_SR supplies the RTP/NTP anchor;
        // AV_PKT_DATA_PRFT supplies a same-AVPacket RTP-mapped wall time. Mode
        // changes only after that same-frame path is observed. E2E validity is
        // a separate gate and stays false while cross-device clock error is
        // UNKNOWN. No candidate value is promoted to a percentile in that
        // state. The RTP-mapped time is not proven capture or socket-send time.
        << "\"e2eMeasurementMode\":\"" << (e2eRtcpMappingAvailable ? "rtcp_sr" : "none") << "\","
        << "\"rtcpSrAccess\":\"available\","
        << "\"senderTimestampMode\":\"none\","
        << "\"senderClockSyncMethod\":\"not_available\","
        << "\"receiverClockSyncMethod\":\"system_auto_time_unverified\","
        << "\"clockSyncMethod\":\"unknown\","
        << "\"clockSyncEstimatedErrorUs\":" << kE2EClockSyncEstimatedErrorUs << ","
        << "\"e2eValid\":" << (e2eValid ? "true" : "false") << ","
        << "\"videoStreamTimeBase\":\"" << videoStreamTimeBaseNum_.load() << "/"
        << videoStreamTimeBaseDen_.load() << "\","
        << "\"videoRtpClockRate\":" << (videoStreamTimeBaseNum_.load() > 0
                                                && videoStreamTimeBaseDen_.load() >= videoStreamTimeBaseNum_.load()
                                        ? videoStreamTimeBaseDen_.load() / videoStreamTimeBaseNum_.load()
                                        : 0) << ","
        << "\"lastPacketReadyWallNs\":" << lastPacketReadyWallNs_.load() << ","
        << "\"e2eGeneration\":" << e2eGeneration_.load() << ","
        << "\"e2eResetCount\":" << e2eResetCount_.load() << ","
        // LAT6-FINAL RTCP SR mapping evidence + srSendToT0 distribution.
        << "\"rtcpSrReceivedCount\":" << e2eSr.srReceivedCount << ","
        << "\"rtcpSrDuplicateCount\":" << e2eSr.duplicateCount << ","
        << "\"rtcpSrInvalidReportCount\":" << e2eSr.invalidReportCount << ","
        << "\"videoSsrc\":\"" << std::hex << e2eSr.ssrc << std::dec << "\","
        << "\"lastSrNtpNs\":" << e2eSr.lastSrNtpNs << ","
        << "\"lastSrRtpTimestamp\":" << static_cast<uint32_t>(e2eSr.lastSrRtpTimestamp) << ","
        << "\"srMappingValid\":" << (e2eSr.srMappingValid && e2eSr.hasAnchor ? "true" : "false") << ","
        << "\"srDriftPpm\":" << e2eSr.driftPpm << ","
        << "\"srSsrcMismatchCount\":" << e2eSr.ssrcMismatchCount << ","
        << "\"rtpClockGeneration\":" << e2eGeneration_.load() << ","
        << "\"srMappingResetCount\":" << (e2eResetCount_.load() + e2eSr.mappingResetCount) << ","
        << "\"sameFrameMappedCount\":" << e2eSameFrameMappedCount << ","
        << "\"sameFrameUnmatchedCount\":" << e2eSameFrameUnmatchedCount << ","
        << "\"clockMappingAnomalyCount\":" << e2eDist.anomalyCount << ","
        << "\"senderSendToReceiverT0UsLast\":" << e2eDist.lastUs << ","
        << "\"senderSendToReceiverT0UsP50\":" << e2eDist.p50Us << ","
        << "\"senderSendToReceiverT0UsP95\":" << e2eDist.p95Us << ","
        << "\"senderSendToReceiverT0UsP99\":" << e2eDist.p99Us << ","
        << "\"senderSendToReceiverT0UsValidCount\":" << e2eDist.validCount << ","
        << "\"senderSendToReceiverT0UsInvalidCount\":" << e2eDist.invalidCount << ","
        // Backward-compatible LAT6.0.1 aliases for the demo parser.
        << "\"srSendToReceiverT0P50Us\":" << e2eDist.p50Us << ","
        << "\"srSendToReceiverT0P95Us\":" << e2eDist.p95Us << ","
        << "\"srSendToReceiverT0P99Us\":" << e2eDist.p99Us << ","
        << "\"srSendToReceiverT0ValidCount\":" << e2eDist.validCount << ","
        << "\"effectiveFmtCtxMaxDelayUs\":" << effectiveFmtCtxMaxDelayUs_.load() << ","
        << "\"lastSendPacketCostUs\":" << lastSendPacketCostUs_.load() << ","
        << "\"lastReceiveFrameCostUs\":" << lastReceiveFrameCostUs_.load() << ","
        << "\"avgDecodeCostUs\":" << avgDecodeCostUs << ","
        << "\"maxDecodeCostUs\":" << maxDecodeCostUs_.load() << ","
        << "\"lastSwsScaleCostUs\":" << lastSwsScaleCostUs_.load() << ","
        << "\"avgSwsScaleCostUs\":" << avgSwsScaleCostUs << ","
        << "\"maxSwsScaleCostUs\":" << maxSwsScaleCostUs_.load() << ","
        << "\"lastRenderCostUs\":" << lastRenderCostUs_.load() << ","
        << "\"lastRenderLockCostUs\":" << lastRenderLockCostUs_.load() << ","
        << "\"lastRenderCopyCostUs\":" << lastRenderCopyCostUs_.load() << ","
        << "\"lastRenderPostCostUs\":" << lastRenderPostCostUs_.load() << ","
        << "\"avgRenderCostUs\":" << avgRenderCostUs << ","
        << "\"maxRenderCostUs\":" << maxRenderCostUs_.load() << ","
        << "\"lastFrameProcessCostUs\":" << lastFrameProcessCostUs_.load() << ","
        << "\"avgFrameProcessCostUs\":" << avgFrameProcessCostUs << ","
        << "\"maxFrameProcessCostUs\":" << maxFrameProcessCostUs_.load() << ","
        << "\"lastVideoDelayUs\":" << lastVideoDelayUs_.load() << ","
        << "\"avgVideoDelayUs\":" << avgVideoDelayUs << ","
        << "\"maxVideoDelayUs\":" << maxVideoDelayUs_.load() << ","
        << "\"droppedVideoPacketCount\":" << droppedVideoPacketCount_.load() << ","
        << "\"packetDropBeforeDecodeCount\":" << packetDropBeforeDecodeCount_.load() << ","
        << "\"frameDropBeforeRenderCount\":" << frameDropBeforeRenderCount_.load() << ","
        << "\"startupKeyFrameWaitActive\":" << (startupKeyFrameWaitActive_.load() ? "true" : "false") << ","
        << "\"startupKeyFrameDroppedPacketCount\":" << startupKeyFrameDroppedPacketCount_.load() << ","
        << "\"lastFrameCacheUpdateCount\":" << lastFrameCacheUpdateCount_.load() << ","
        << "\"lastFrameCacheSkippedCount\":" << lastFrameCacheSkippedCount_.load() << ","
        << "\"readPacketQueueSize\":0,"
        << "\"videoFrameQueueSize\":0,"
        << "\"rtspTransportMode\":\"" << escapeJson(rtspTransportMode) << "\","
        << "\"currentRtspTransport\":\"" << (preferUdpTransport_.load() ? "udp" : "tcp") << "\","
        << "\"rtspTransportSwitchPending\":" << (transportSwitchRequested_.load() ? "true" : "false") << ","
        << "\"reconnectEnabled\":" << (reconnectEnabled_.load() ? "true" : "false") << ","
        << "\"reconnecting\":" << (reconnecting_.load() ? "true" : "false") << ","
        << "\"waitingSource\":" << (waitingSource_.load() ? "true" : "false") << ","
        << "\"reconnectAttempt\":" << reconnectAttemptCount_.load() << ","
        << "\"reconnectMaxRetryCount\":" << reconnectMaxRetryCount_.load() << ","
        << "\"reconnectMaxRetry\":" << reconnectMaxRetryCount_.load() << ","
        << "\"reconnectRetryDelayMs\":" << reconnectRetryDelayMs_.load() << ","
        << "\"reconnectInitialDelayMs\":" << reconnectRetryDelayMs_.load() << ","
        << "\"reconnectMaxDelayMs\":" << reconnectMaxDelayMs_.load() << ","
        << "\"infiniteReconnect\":" << (infiniteReconnect_.load() ? "true" : "false") << ","
        << "\"reconnectOnEof\":" << (reconnectOnEof_.load() ? "true" : "false") << ","
        << "\"reconnectOn404\":" << (reconnectOn404_.load() ? "true" : "false") << ","
        << "\"keepWaitingWhenSourceMissing\":" << (keepWaitingWhenSourceMissing_.load() ? "true" : "false") << ","
        << "\"reconnectAttemptCount\":" << reconnectAttemptCount_.load() << ","
        << "\"reconnectSuccessCount\":" << reconnectSuccessCount_.load() << ","
        << "\"lastReconnectTimeMs\":" << lastReconnectTimeMs_.load() << ","
        << "\"lastDisconnectTimeMs\":" << lastDisconnectTimeMs_.load() << ","
        << "\"lastReconnectSuccessTimeMs\":" << lastReconnectSuccessTimeMs_.load() << ","
        << "\"reconnectLastErrorCode\":" << lastReconnectErrorCode_.load() << ","
        << "\"reconnectExhausted\":" << (reconnectExhausted_.load() ? "true" : "false") << ","
        << "\"reconnectLastError\":\"" << escapeJson(reconnectError) << "\","
        << "\"lastReconnectError\":\"" << escapeJson(reconnectError) << "\","
        << "\"thermalEnabled\":" << (thermalConfig.enabled ? "true" : "false") << ","
        << "\"thermalPalette\":\"" << thermalPaletteName(thermalConfig.palette) << "\","
        << "\"thermalPaletteValue\":" << static_cast<int>(thermalConfig.palette) << ","
        << "\"thermalAgcEnabled\":" << (thermalConfig.agcEnabled ? "true" : "false") << ","
        << "\"thermalAgcValid\":" << (agcValidEffective ? "true" : "false") << ","
        << "\"thermalAgcBlackPoint\":" << agcBlackEffective << ","
        << "\"thermalAgcWhitePoint\":" << agcWhiteEffective << ","
        << "\"thermalAgcUpdateCount\":" << agcUpdateCount_.load() << ","
        << "\"nv12AgcUpdateCount\":" << nv12AgcUpdateCount_.load() << ","
        << "\"nv12AgcInvalidCount\":" << nv12AgcInvalidCount_.load() << ","
        << "\"oesAgcUpdateCount\":" << oesRenderer_.getAgcUpdateCount() << ","
        << "\"oesAgcReadbackErrorCount\":" << oesRenderer_.getAgcReadbackErrorCount() << ","
        << "\"thermalGamma\":" << thermalConfig.gamma << ","
        << "\"thermalBlackPoint\":" << thermalConfig.blackPoint << ","
        << "\"thermalWhitePoint\":" << thermalConfig.whitePoint << ","
        << "\"thermalWindowApplied\":" << (thermalWindowApplied ? "true" : "false") << ","
        << "\"thermalRenderMode\":\"" << effectiveThermalRenderMode << "\","
        << "\"thermalActiveRenderMode\":\"" << effectiveThermalRenderMode << "\","
        << "\"thermalInputType\":\"" << thermalInputType << "\","
        << "\"oesThermalRenderedCount\":" << oesThermalRenderedCount_.load() << ","
        << "\"whiteHotRenderedFrameCount\":" << whiteHotRenderedFrameCount_.load() << ","
        << "\"ironbowRenderedFrameCount\":" << ironbowRenderedFrameCount_.load() << "}";
    return out.str();
}


std::string NativePlayer::setReconnectOptions(bool enabled, int maxRetryCount, int retryDelayMs) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    const int safeMaxRetryCount = maxRetryCount < 0 ? -1 : std::clamp(maxRetryCount, 0, 100000);
    const int safeRetryDelayMs = std::clamp(retryDelayMs, 100, 60000);
    reconnectEnabled_.store(enabled);
    reconnectMaxRetryCount_.store(safeMaxRetryCount);
    reconnectRetryDelayMs_.store(safeRetryDelayMs);
    infiniteReconnect_.store(safeMaxRetryCount < 0);
    reconnectExhausted_.store(false);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        playerOptions_.infiniteReconnect = safeMaxRetryCount < 0;
        playerOptions_.reconnectMaxRetry = safeMaxRetryCount;
        playerOptions_.reconnectInitialDelayMs = safeRetryDelayMs;
        if (playerOptions_.reconnectMaxDelayMs < safeRetryDelayMs) {
            playerOptions_.reconnectMaxDelayMs = safeRetryDelayMs;
        }
        syncReconnectPolicyFromOptionsLocked();
    }
    LOGI("setPlayerReconnectOptions enabled=%d maxRetry=%d retryDelayMs=%d", enabled ? 1 : 0, safeMaxRetryCount, safeRetryDelayMs);

    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"reconnect options updated\","
        << "\"enabled\":" << (enabled ? "true" : "false") << ","
        << "\"maxRetryCount\":" << safeMaxRetryCount << ","
        << "\"retryDelayMs\":" << safeRetryDelayMs << ","
        << "\"infiniteReconnect\":" << (safeMaxRetryCount < 0 ? "true" : "false") << ","
        << "\"reconnectMaxDelayMs\":" << reconnectMaxDelayMs_.load() << "}";
    return out.str();
}

std::string NativePlayer::getReconnectState() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    return buildReconnectJson();
}

std::string NativePlayer::setRtspTransport(const std::string &transport) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    RtspTransport parsedTransport;
    if (!parseRtspTransport(transport, parsedTransport)) {
        return jsonError(-1, "transport must be tcp, udp, udp_multicast, or auto");
    }

    bool requestSwitch = false;
    PlayerOptions optionsSnapshot;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        const bool active = (state_ == PlayerState::Playing
                             || state_ == PlayerState::Paused
                             || state_ == PlayerState::Reconnecting)
                            && formatContext_ != nullptr;
        const bool sourceIsRtsp = sourceType_ == SourceType::RTSP;
        if (state_ == PlayerState::Prepared && formatContext_ != nullptr) {
            return jsonError(-1, "player already prepared, option will not take effect until next prepare");
        }
        if (active && remuxRecorder_.isRecording()) {
            return jsonError(-1, "cannot switch RTSP transport while recording");
        }

        playerOptions_.rtspTransport = parsedTransport;
        applyLatencyProfile(playerOptions_);
        rtspTransportMode_ = rtspTransportName(parsedTransport);
        preferUdpTransport_.store(shouldPreferUdpTransport(playerOptions_));
        syncReconnectPolicyFromOptionsLocked();
        optionsSnapshot = playerOptions_;
        requestSwitch = active && sourceIsRtsp;
    }

    if (requestSwitch) {
        transportSwitchRequested_.store(true);
    }

    LOGI("setPlayerRtspTransport mode=%s switchRequested=%d", rtspTransportName(parsedTransport).c_str(), requestSwitch ? 1 : 0);
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"rtsp transport updated\","
        << "\"mode\":\"" << rtspTransportName(parsedTransport) << "\","
        << "\"latencyMode\":\"" << latencyModeName(optionsSnapshot.latencyMode) << "\","
        << "\"currentTransport\":\"" << effectiveRtspTransportName(optionsSnapshot, preferUdpTransport_.load()) << "\","
        << "\"switchRequested\":" << (requestSwitch ? "true" : "false") << "}";
    return out.str();
}

std::string NativePlayer::getRtspTransportState() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    PlayerState state;
    PlayerOptions optionsSnapshot;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state = state_;
        optionsSnapshot = playerOptions_;
    }

    std::ostringstream out;
    out << "{\"success\":true,"
        << "\"mode\":\"" << rtspTransportName(optionsSnapshot.rtspTransport) << "\","
        << "\"latencyMode\":\"" << latencyModeName(optionsSnapshot.latencyMode) << "\","
        << "\"currentTransport\":\"" << effectiveRtspTransportName(optionsSnapshot, preferUdpTransport_.load()) << "\","
        << "\"switchPending\":" << (transportSwitchRequested_.load() ? "true" : "false") << ","
        << "\"state\":\"" << stateName(state) << "\"}";
    return out.str();
}

std::string NativePlayer::setLatencyMode(const std::string &mode) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    LatencyMode parsedMode;
    if (!parseLatencyMode(mode, parsedMode)) {
        return jsonError(-1, "invalid latency mode: " + mode);
    }

    PlayerOptions optionsSnapshot;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ == PlayerState::Prepared || state_ == PlayerState::Playing
            || state_ == PlayerState::Paused || state_ == PlayerState::Reconnecting) {
            return jsonError(-1, "player already prepared, option will not take effect until next prepare");
        }
        playerOptions_.latencyMode = parsedMode;
        applyLatencyProfile(playerOptions_);
        rtspTransportMode_ = rtspTransportName(playerOptions_.rtspTransport);
        preferUdpTransport_.store(shouldPreferUdpTransport(playerOptions_));
        syncReconnectPolicyFromOptionsLocked();
        optionsSnapshot = playerOptions_;
    }

    LOGI("setPlayerLatencyMode mode=%s transport=%s", latencyModeName(parsedMode).c_str(), rtspTransportName(optionsSnapshot.rtspTransport).c_str());
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"latency mode updated\","
        << "\"latencyMode\":\"" << latencyModeName(parsedMode) << "\","
        << "\"rtspTransport\":\"" << rtspTransportName(optionsSnapshot.rtspTransport) << "\"}";
    return out.str();
}

std::string NativePlayer::setOption(const std::string &key, const std::string &value) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    const std::string normalizedKey = lowerTrimCopy(key);
    if (normalizedKey == "rtsp_transport") {
        return setRtspTransport(value);
    }
    if (normalizedKey == "latency_mode") {
        return setLatencyMode(value);
    }
    if (normalizedKey == "diagnostics_mode" || normalizedKey == "diagnosticsmode") {
        DiagnosticsMode parsedMode;
        if (!parseDiagnosticsMode(value, parsedMode)) {
            return jsonError(-1, "diagnostics_mode must be off, basic, or latency");
        }
        diagnostics_.setMode(parsedMode);
        LOGI("setPlayerDiagnosticsMode mode=%s", diagnosticsModeName(parsedMode));
        std::ostringstream out;
        out << "{\"success\":true,\"message\":\"diagnostics mode updated\","
            << "\"diagnosticsMode\":\"" << diagnosticsModeName(parsedMode) << "\"}";
        return out.str();
    }
    if (normalizedKey == "enable_hardware_decode") {
        bool enabled = false;
        const std::string normalizedValue = lowerTrimCopy(value);
        if (normalizedValue == "1" || normalizedValue == "true" || normalizedValue == "yes" || normalizedValue == "on") {
            enabled = true;
        } else if (normalizedValue == "0" || normalizedValue == "false" || normalizedValue == "no" || normalizedValue == "off") {
            enabled = false;
        } else {
            return jsonError(-1, "enable_hardware_decode must be boolean");
        }
        return setHardwareDecode(enabled);
    }
    if (normalizedKey == "hardware_render_mode" || normalizedKey == "render_mode") {
        return setHardwareRenderMode(value);
    }
    if (normalizedKey == "infinite_reconnect"
        || normalizedKey == "reconnect_on_eof"
        || normalizedKey == "reconnect_on_404"
        || normalizedKey == "keep_waiting_when_source_missing") {
        bool parsed = false;
        if (!parseBoolOption(value, parsed)) {
            return jsonError(-1, normalizedKey + " must be boolean");
        }
        PlayerOptions optionsSnapshot;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (normalizedKey == "infinite_reconnect") {
                playerOptions_.infiniteReconnect = parsed;
                if (parsed) {
                    playerOptions_.reconnectMaxRetry = -1;
                } else if (playerOptions_.reconnectMaxRetry < 0) {
                    playerOptions_.reconnectMaxRetry = 3;
                }
            } else if (normalizedKey == "reconnect_on_eof") {
                playerOptions_.reconnectOnEof = parsed;
            } else if (normalizedKey == "reconnect_on_404") {
                playerOptions_.reconnectOn404 = parsed;
            } else {
                playerOptions_.keepWaitingWhenSourceMissing = parsed;
            }
            syncReconnectPolicyFromOptionsLocked();
            optionsSnapshot = playerOptions_;
        }
        LOGI("setPlayerOption key=%s value=%s", key.c_str(), value.c_str());
        std::ostringstream out;
        out << "{\"success\":true,\"message\":\"reconnect option updated\","
            << "\"key\":\"" << escapeJson(key) << "\","
            << "\"value\":\"" << escapeJson(value) << "\","
            << "\"infiniteReconnect\":" << (optionsSnapshot.infiniteReconnect ? "true" : "false") << ","
            << "\"reconnectOnEof\":" << (optionsSnapshot.reconnectOnEof ? "true" : "false") << ","
            << "\"reconnectOn404\":" << (optionsSnapshot.reconnectOn404 ? "true" : "false") << ","
            << "\"keepWaitingWhenSourceMissing\":" << (optionsSnapshot.keepWaitingWhenSourceMissing ? "true" : "false") << ","
            << "\"reconnectMaxRetry\":" << optionsSnapshot.reconnectMaxRetry << ","
            << "\"reconnectInitialDelayMs\":" << optionsSnapshot.reconnectInitialDelayMs << ","
            << "\"reconnectMaxDelayMs\":" << optionsSnapshot.reconnectMaxDelayMs << "}";
        return out.str();
    }
    if (normalizedKey == "reconnect_initial_delay_ms"
        || normalizedKey == "reconnect_max_delay_ms"
        || normalizedKey == "reconnect_max_retry") {
        int parsed = 0;
        if (!parseIntOption(value, parsed)) {
            return jsonError(-1, normalizedKey + " must be an integer");
        }
        if ((normalizedKey == "reconnect_initial_delay_ms" || normalizedKey == "reconnect_max_delay_ms") && parsed < 100) {
            return jsonError(-1, normalizedKey + " must be >= 100");
        }
        if (normalizedKey == "reconnect_max_retry" && parsed < -1) {
            return jsonError(-1, "reconnect_max_retry must be -1 or a non-negative integer");
        }
        PlayerOptions optionsSnapshot;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (normalizedKey == "reconnect_initial_delay_ms") {
                playerOptions_.reconnectInitialDelayMs = parsed;
            } else if (normalizedKey == "reconnect_max_delay_ms") {
                playerOptions_.reconnectMaxDelayMs = parsed;
            } else {
                playerOptions_.reconnectMaxRetry = parsed;
                playerOptions_.infiniteReconnect = parsed < 0;
            }
            syncReconnectPolicyFromOptionsLocked();
            optionsSnapshot = playerOptions_;
        }
        LOGI("setPlayerOption key=%s value=%s", key.c_str(), value.c_str());
        std::ostringstream out;
        out << "{\"success\":true,\"message\":\"reconnect option updated\","
            << "\"key\":\"" << escapeJson(key) << "\","
            << "\"value\":\"" << escapeJson(value) << "\","
            << "\"infiniteReconnect\":" << (optionsSnapshot.infiniteReconnect ? "true" : "false") << ","
            << "\"reconnectMaxRetry\":" << optionsSnapshot.reconnectMaxRetry << ","
            << "\"reconnectInitialDelayMs\":" << optionsSnapshot.reconnectInitialDelayMs << ","
            << "\"reconnectMaxDelayMs\":" << optionsSnapshot.reconnectMaxDelayMs << "}";
        return out.str();
    }

    PlayerOptions optionsSnapshot;
    std::string error;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ == PlayerState::Prepared || state_ == PlayerState::Playing
            || state_ == PlayerState::Paused || state_ == PlayerState::Reconnecting) {
            return jsonError(-1, "player already prepared, option will not take effect until next prepare");
        }
        if (!setPlayerOptionValue(playerOptions_, key, value, error)) {
            return jsonError(-1, error);
        }
        rtspTransportMode_ = rtspTransportName(playerOptions_.rtspTransport);
        preferUdpTransport_.store(shouldPreferUdpTransport(playerOptions_));
        syncReconnectPolicyFromOptionsLocked();
        optionsSnapshot = playerOptions_;
    }

    LOGI("setPlayerOption key=%s value=%s", key.c_str(), value.c_str());
    std::ostringstream out;
    out << "{\"success\":true,\"message\":\"player option updated\","
        << "\"key\":\"" << escapeJson(key) << "\","
        << "\"value\":\"" << escapeJson(value) << "\","
        << "\"latencyMode\":\"" << latencyModeName(optionsSnapshot.latencyMode) << "\","
        << "\"rtspTransport\":\"" << rtspTransportName(optionsSnapshot.rtspTransport) << "\"}";
    return out.str();
}

std::string NativePlayer::setHardwareDecode(bool enabled) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    PlayerOptions optionsSnapshot;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ == PlayerState::Preparing || state_ == PlayerState::Prepared
            || state_ == PlayerState::Playing || state_ == PlayerState::Paused
            || state_ == PlayerState::Reconnecting) {
            return jsonError(-1, "hardware decode mode can only be changed before prepare");
        }
        playerOptions_.enableHardwareDecode = enabled;
        if (!enabled) {
            playerOptions_.renderMode = RenderMode::SOFTWARE_RGBA;
            playerOptions_.usingHardwareDecoder = false;
        }
        playerOptions_.requestedDecoderName.clear();
        playerOptions_.actualDecoderName.clear();
        playerOptions_.hardwareDecodeFallbackUsed = false;
        playerOptions_.hardwareDecodeError.clear();
        optionsSnapshot = playerOptions_;
    }

    LOGI("setHardwareDecode enableHardwareDecode=%d renderMode=%s",
         enabled ? 1 : 0, renderModeName(optionsSnapshot.renderMode).c_str());
    std::ostringstream out;
    out << "{\"success\":true,\"enableHardwareDecode\":" << (enabled ? "true" : "false") << "}";
    return out.str();
}

std::string NativePlayer::setHardwareRenderMode(const std::string &mode) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    RenderMode parsedMode;
    if (!parseRenderMode(mode, parsedMode)) {
        return jsonError(-1, "hardware_render_mode must be software_rgba, software_yuv_gl, mediacodec_surface, mediacodec_oes, or mediacodec_nv12_gl");
    }

    PlayerOptions optionsSnapshot;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ == PlayerState::Preparing || state_ == PlayerState::Prepared
            || state_ == PlayerState::Playing || state_ == PlayerState::Paused
            || state_ == PlayerState::Reconnecting) {
            return jsonError(-1, "hardware decode mode can only be changed before prepare");
        }
        playerOptions_.renderMode = parsedMode;
        if (parsedMode != RenderMode::MEDIACODEC_SURFACE) {
            playerOptions_.usingHardwareDecoder = false;
        }
        playerOptions_.requestedDecoderName.clear();
        playerOptions_.actualDecoderName.clear();
        playerOptions_.hardwareDecodeFallbackUsed = false;
        playerOptions_.hardwareDecodeError.clear();
        optionsSnapshot = playerOptions_;
    }

    LOGI("setHardwareRenderMode enableHardwareDecode=%d renderMode=%s",
         optionsSnapshot.enableHardwareDecode ? 1 : 0, renderModeName(optionsSnapshot.renderMode).c_str());
    std::ostringstream out;
    out << "{\"success\":true,\"renderMode\":\"" << renderModeName(optionsSnapshot.renderMode) << "\"}";
    return out.str();
}

std::string NativePlayer::setThermalEnabled(bool enabled) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    {
        std::lock_guard<std::mutex> lock(thermalConfigMutex_);
        thermalConfig_.enabled = enabled;
    }
    LOGI("setThermalEnabled enabled=%d", enabled ? 1 : 0);
    std::ostringstream out;
    out << "{\"success\":true,\"thermalEnabled\":" << (enabled ? "true" : "false") << "}";
    return out.str();
}

std::string NativePlayer::setThermalPalette(int palette) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    ThermalPaletteMode parsedMode;
    if (!parseThermalPalette(palette, parsedMode)) {
        return jsonError(-1, "thermal palette must be 0 (original), 1 (white_hot), or 2 (ironbow)");
    }
    {
        std::lock_guard<std::mutex> lock(thermalConfigMutex_);
        thermalConfig_.palette = parsedMode;
    }
    LOGI("setThermalPalette palette=%s", thermalPaletteName(parsedMode).c_str());
    std::ostringstream out;
    out << "{\"success\":true,\"thermalPalette\":\"" << thermalPaletteName(parsedMode)
        << "\",\"thermalPaletteValue\":" << static_cast<int>(parsedMode) << "}";
    return out.str();
}

std::string NativePlayer::setThermalAgcEnabled(bool enabled) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    {
        std::lock_guard<std::mutex> lock(thermalConfigMutex_);
        thermalConfig_.agcEnabled = enabled;
    }
    // Reset AGC state so a re-enable re-initializes quickly from the new scene.
    agcValid_.store(false);
    agcFrameCounter_.store(0);
    oesRenderer_.resetAgc();
    oesAgcFrameCounter_.store(0);
    nv12AgcValid_.store(false);
    nv12AgcFrameCounter_.store(0);
    LOGI("setThermalAgcEnabled agc=%d", enabled ? 1 : 0);
    std::ostringstream out;
    out << "{\"success\":true,\"thermalAgcEnabled\":" << (enabled ? "true" : "false") << "}";
    return out.str();
}

std::string NativePlayer::setThermalGamma(float gamma) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    if (!isValidThermalGamma(gamma)) {
        return jsonError(-1, "thermal gamma must be finite and in range 0.5 ~ 2.0");
    }
    {
        std::lock_guard<std::mutex> lock(thermalConfigMutex_);
        thermalConfig_.gamma = gamma;
    }
    LOGI("setThermalGamma gamma=%.3f", gamma);
    std::ostringstream out;
    out << "{\"success\":true,\"thermalGamma\":" << gamma << "}";
    return out.str();
}

ThermalConfig NativePlayer::getThermalConfig() const {
    std::lock_guard<std::mutex> lock(thermalConfigMutex_);
    return thermalConfig_;
}

std::string NativePlayer::setThermalWindow(float blackPoint, float whitePoint) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    if (!isValidThermalWindow(blackPoint, whitePoint)) {
        return jsonError(-1, "thermal window must satisfy finite, 0.0 <= blackPoint < whitePoint <= 1.0, min span 0.01");
    }
    {
        std::lock_guard<std::mutex> lock(thermalConfigMutex_);
        thermalConfig_.blackPoint = blackPoint;
        thermalConfig_.whitePoint = whitePoint;
    }
    LOGI("setThermalWindow blackPoint=%.3f whitePoint=%.3f", blackPoint, whitePoint);
    std::ostringstream out;
    out << "{\"success\":true,\"thermalBlackPoint\":" << blackPoint
        << ",\"thermalWhitePoint\":" << whitePoint << "}";
    return out.str();
}

std::string NativePlayer::getLatencyConfig() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    PlayerOptions optionsSnapshot;
    SourceType sourceType;
    bool preferUdp = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        optionsSnapshot = playerOptions_;
        sourceType = sourceType_;
        preferUdp = preferUdpTransport_.load();
    }
    return playerOptionsToJson(optionsSnapshot, sourceType, preferUdp, effectiveSyncMasterName(optionsSnapshot));
}

std::string NativePlayer::takeSnapshot(const std::string &outputPath) {
    if (isReleased()) {
        return snapshotError("SNAPSHOT_PLAYER_RELEASED", "player is released", "unsupported");
    }

    RenderMode renderMode = RenderMode::SOFTWARE_RGBA;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        renderMode = playerOptions_.renderMode;
    }

    const int rendererType = rendererTypeFromState(rendererState_.load());
    const std::string captureMode = snapshotCaptureModeName(renderMode, rendererType);
    LOGI("snapshot route=%s requestedRenderer=%s actualRenderer=%s",
         captureMode.c_str(), rendererNameFromRenderMode(renderMode), rendererTypeName(rendererType));
    if (captureMode == "surface_pixelcopy") {
        return snapshotError(
                "SNAPSHOT_REQUIRES_SURFACE_CAPTURE",
                "Snapshot is not supported by the native RGBA frame cache; use surface capture",
                captureMode);
    }
    if (captureMode != "native_rgba") {
        return snapshotError("SNAPSHOT_UNSUPPORTED", "Snapshot is not supported", captureMode);
    }

    std::vector<uint8_t> frameCopy;
    int width = 0;
    int height = 0;
    int stride = 0;
    int64_t ptsUs = 0;
    {
        std::lock_guard<std::mutex> lock(lastFrameMutex_);
        if (!hasLastFrame_ || lastRgbaFrame_.empty()) {
            LOGE("takePlayerSnapshot failed: no video frame available");
            return snapshotError("SNAPSHOT_NO_FRAME", "no video frame available", captureMode);
        }
        frameCopy = lastRgbaFrame_;
        width = lastFrameWidth_;
        height = lastFrameHeight_;
        stride = lastFrameStride_;
        ptsUs = lastFramePtsUs_;
    }

    LOGI("takePlayerSnapshot outputPath=%s hasFrame=1 width=%d height=%d ptsUs=%lld",
         outputPath.c_str(), width, height, static_cast<long long>(ptsUs));
    const std::string result = SnapshotManager::saveRgba(outputPath, frameCopy, width, height, stride, ptsUs);
    if (result.find("\"success\":true") != std::string::npos) {
        lastSnapshotTimeMs_.store(nowMs());
    }
    return result;
}

std::string NativePlayer::startRecord(const std::string &outputPath) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    AVFormatContext *input = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != PlayerState::Prepared && state_ != PlayerState::Playing && state_ != PlayerState::Paused) {
            return jsonError(-1, "player is not prepared");
        }
        input = formatContext_;
    }

    remuxRecorder_.setAudioPlaybackState(audioEnabled_.load());
    if (sourceHasAudio_.load() && !audioEnabled_.load()) {
        LOGI("AudioTrack disabled but audio remux recording remains enabled by source audio stream");
    }
    LOGI("startPlayerRecord outputPath=%s sourceHasAudio=%d audioPlaybackEnabled=%d", outputPath.c_str(), sourceHasAudio_.load() ? 1 : 0, audioEnabled_.load() ? 1 : 0);
    return remuxRecorder_.start(input, outputPath);
}


std::string NativePlayer::startSegmentRecord(const std::string &outputPattern, int segmentDurationSec) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    AVFormatContext *input = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != PlayerState::Prepared && state_ != PlayerState::Playing && state_ != PlayerState::Paused) {
            return jsonError(-1, "player is not prepared");
        }
        input = formatContext_;
    }

    remuxRecorder_.setAudioPlaybackState(audioEnabled_.load());
    if (sourceHasAudio_.load() && !audioEnabled_.load()) {
        LOGI("AudioTrack disabled but segmented audio remux recording remains enabled by source audio stream");
    }
    LOGI("startPlayerSegmentRecord outputPattern=%s segmentDurationSec=%d sourceHasAudio=%d audioPlaybackEnabled=%d", outputPattern.c_str(), segmentDurationSec, sourceHasAudio_.load() ? 1 : 0, audioEnabled_.load() ? 1 : 0);
    return remuxRecorder_.startSegmented(input, outputPattern, segmentDurationSec);
}

std::string NativePlayer::startRecordWithConfig(const std::string &outputPathOrPattern,
                                                const std::string &formatName,
                                                int segmentDurationSec) {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }

    AVFormatContext *input = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != PlayerState::Prepared && state_ != PlayerState::Playing && state_ != PlayerState::Paused) {
            return jsonError(-1, "player is not prepared");
        }
        input = formatContext_;
    }

    RemuxRecordConfig config;
    config.outputPathOrPattern = outputPathOrPattern;
    config.formatName = formatName;
    config.segmentMode = segmentDurationSec > 0;
    config.segmentDurationSec = segmentDurationSec;
    config.fragmentedMp4 = true;

    remuxRecorder_.setAudioPlaybackState(audioEnabled_.load());
    if (sourceHasAudio_.load() && !audioEnabled_.load()) {
        LOGI("AudioTrack disabled but configured remux recording remains enabled by source audio stream");
    }
    LOGI("startPlayerRecordWithConfig output=%s format=%s segmentDurationSec=%d segmentMode=%d sourceHasAudio=%d audioPlaybackEnabled=%d",
         outputPathOrPattern.c_str(), formatName.c_str(), segmentDurationSec, config.segmentMode ? 1 : 0,
         sourceHasAudio_.load() ? 1 : 0, audioEnabled_.load() ? 1 : 0);
    return remuxRecorder_.startWithConfig(input, config);
}

std::string NativePlayer::stopRecord() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    LOGI("stopPlayerRecord requested");
    return remuxRecorder_.stop();
}

std::string NativePlayer::getRecordState() {
    if (isReleased()) {
        return jsonError(-1, "player is released");
    }
    return remuxRecorder_.getState();
}

std::string NativePlayer::release() {
    if (released_.load()) {
        LOGI("releasePlayer ignored: already released player=%p", this);
        return jsonSuccess("player already released");
    }

    stop();
    remuxRecorder_.release();
    renderer_.release();
    yuvGlRenderer_.release();
    bool attached = false;
    JNIEnv *env = getJniEnvForCurrentThread(attached);
    if (env != nullptr) {
        // Release the Java AudioTrack sink, then delete its global reference.
        // The worker has already been joined by stop(), so no concurrent write.
        sendAudioSinkControl(kAudioSinkCmdRelease, "release");
        deleteAudioSinkGlobalRef(env);
        recomputeAudioPlayable();
        {
            std::lock_guard<std::mutex> surfaceLock(surfaceMutex_);
            deleteSurfaceGlobalRefLocked(env);
        }
        {
            std::lock_guard<std::mutex> listenerLock(eventListenerMutex_);
            if (playerEventListenerGlobalRef_ != nullptr) {
                env->DeleteGlobalRef(playerEventListenerGlobalRef_);
                playerEventListenerGlobalRef_ = nullptr;
            }
        }
    } else {
        LOGE("releasePlayer could not get JNIEnv to delete Java global refs");
    }
    detachCurrentThreadIfNeeded(attached);
    clearLastFrame();
    releaseFfmpegResources();
    oesRenderer_.release();
    nv12GlRenderer_.release();

    {
        std::lock_guard<std::mutex> lock(mutex_);
        state_ = PlayerState::Released;
    }
    released_.store(true);
    LOGI("releasePlayer player=%p", this);
    return jsonSuccess("player released");
}

int NativePlayer::interruptCallback(void *opaque) {
    auto *player = static_cast<NativePlayer *>(opaque);
    if (player == nullptr) {
        return 0;
    }
    return (player->stopRequested_.load() || player->transportSwitchRequested_.load()) ? 1 : 0;
}

bool NativePlayer::prepareRealtimeInputForStart() {
    PlayerOptions optionsSnapshot;
    SourceType sourceType = SourceType::OTHER;
    std::string currentUrl;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        currentUrl = url_;
        optionsSnapshot = playerOptions_;
        sourceType = sourceType_;
        errorMessage_.clear();
    }

    if (formatContext_ == nullptr || videoCodecContext_ == nullptr || packet_ == nullptr
        || decodedFrame_ == nullptr || latestFrame_ == nullptr || rgbaFrame_ == nullptr) {
        const std::string error = "prepared realtime input resources are invalid";
        setState(PlayerState::Error, error);
        LOGE("realtime start reuse failed url=%s error=%s", currentUrl.c_str(), error.c_str());
        return false;
    }

    const int64_t preparedAtMs = preparedAtTimeMs_.load();
    const int64_t prepareToStartDelayMs = preparedAtMs > 0
                                          ? std::max<int64_t>(0, nowMs() - preparedAtMs)
                                          : 0;
    lastPrepareToStartDelayMs_.store(prepareToStartDelayMs);
    realtimeStartInputReuseCount_.fetch_add(1);

    // Recording used to suppress the old close/reopen path. Preserve that
    // behavior: reuse the prepared input without discarding probe buffers that
    // may be needed by the remux session.
    const bool recording = remuxRecorder_.isRecording();
    const int64_t staleThresholdUs = std::max<int64_t>(0, optionsSnapshot.dropLateFrameThresholdUs);
    const bool stalePreparedInput = isRtspSource(sourceType) && !recording
                                    && prepareToStartDelayMs * 1000 > staleThresholdUs;
    if (stalePreparedInput) {
        // Drop only FFmpeg/AVIO read-side buffers. This keeps the RTSP socket,
        // stream discovery, AVCodecContext and MediaCodec session intact. The
        // existing startup keyframe gate then resumes decode on a valid GOP.
        if (formatContext_->pb != nullptr) {
            avio_flush(formatContext_->pb);
        }
        const int flushResult = avformat_flush(formatContext_);
        if (flushResult < 0) {
            startupFreshnessFlushErrorCount_.fetch_add(1);
            LOGE("realtime start freshness flush failed delayMs=%lld error=%s",
                 static_cast<long long>(prepareToStartDelayMs),
                 ffmpegErrorToString(flushResult).c_str());
        } else {
            startupFreshnessFlushCount_.fetch_add(1);
        }
    }
    if (!recording) {
        clearLastFrame();
    }
    LOGI("realtime start reuses prepared input sourceType=%s delayMs=%lld staleThresholdUs=%lld freshnessFlush=%d recording=%d inputOpenCount=%lld decoderOpenCount=%lld hardwareDecoderOpenCount=%lld",
         sourceTypeName(sourceType).c_str(),
         static_cast<long long>(prepareToStartDelayMs),
         static_cast<long long>(staleThresholdUs),
         stalePreparedInput ? 1 : 0,
         recording ? 1 : 0,
         static_cast<long long>(inputOpenCount_.load()),
         static_cast<long long>(videoDecoderOpenCount_.load()),
         static_cast<long long>(hardwareDecoderOpenCount_.load()));
    return true;
}

void NativePlayer::resetRealtimeClock() {
    realtimeClockInitialized_ = false;
    realtimeFirstPtsUs_ = 0;
    realtimeStartWallUs_ = 0;
    lastRealtimeDropLogMs_ = 0;
    dropUntilKeyFrame_ = false;
    startupKeyFrameWait_ = false;
    startupKeyFrameWaitStartMs_ = 0;
    startupKeyFrameWaitActive_.store(false);
}

void NativePlayer::beginStartupKeyFrameWait(const char *reason) {
    if (!isRealtimeInput_ || videoStreamIndex_ < 0) {
        return;
    }
    dropUntilKeyFrame_ = true;
    startupKeyFrameWait_ = true;
    startupKeyFrameWaitStartMs_ = nowMs();
    startupKeyFrameWaitActive_.store(true);
    LOGI("wait first keyframe reason=%s timeoutMs=%lld",
         reason == nullptr ? "unknown" : reason,
         static_cast<long long>(kStartupKeyFrameWaitTimeoutMs));
}

void NativePlayer::finishStartupKeyFrameWait(const char *reason) {
    dropUntilKeyFrame_ = false;
    startupKeyFrameWait_ = false;
    startupKeyFrameWaitStartMs_ = 0;
    startupKeyFrameWaitActive_.store(false);
    resetRealtimeClock();
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ == PlayerState::Reconnected && !pauseRequested_.load()) {
            state_ = PlayerState::Playing;
        }
    }
    LOGI("finish first video keyframe wait reason=%s",
         reason == nullptr ? "unknown" : reason);
}

bool NativePlayer::commitDecodedVideoFormatIfChanged(int frameWidth, int frameHeight, int frameFormat,
                                                     int yStride, int colorRange) {
    if (frameWidth <= 0 || frameHeight <= 0 || frameFormat == AV_PIX_FMT_NONE) {
        return false;
    }

    const char *formatNameValue = av_get_pix_fmt_name(static_cast<AVPixelFormat>(frameFormat));
    const std::string formatName = formatNameValue == nullptr ? "unknown" : formatNameValue;
    int frameOutputType = 0;
    if (frameFormat == AV_PIX_FMT_NV12) {
        frameOutputType = 2;
    } else if (frameFormat == AV_PIX_FMT_YUV420P || frameFormat == AV_PIX_FMT_YUVJ420P) {
        frameOutputType = 1;
    } else if (frameFormat == AV_PIX_FMT_MEDIACODEC) {
        frameOutputType = 3;
    }

    bool firstCommit = false;
    bool formatDiscontinuity = false;
    bool metadataChanged = false;
    int oldWidth = 0;
    int oldHeight = 0;
    int oldFormat = AV_PIX_FMT_NONE;
    uint64_t generation = 0;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (frameFormat == AV_PIX_FMT_MEDIACODEC
            && playerOptions_.renderMode == RenderMode::MEDIACODEC_OES) {
            frameOutputType = 4;
        }
        firstCommit = decodedFrameFormat_ == AV_PIX_FMT_NONE;
        oldWidth = videoWidth_;
        oldHeight = videoHeight_;
        oldFormat = decodedFrameFormat_;
        formatDiscontinuity = !firstCommit
                              && (oldWidth != frameWidth || oldHeight != frameHeight
                                  || oldFormat != frameFormat);
        metadataChanged = firstCommit || formatDiscontinuity
                          || lastFrameYStride_.load() != std::max(yStride, 0)
                          || lastFrameColorRange_.load() != colorRange
                          || lastFrameOutputType_.load() != frameOutputType;
        if (metadataChanged) {
            videoWidth_ = frameWidth;
            videoHeight_ = frameHeight;
            decodedFrameFormat_ = frameFormat;
            lastFrameFormatName_ = formatName;
            lastFrameYStride_.store(std::max(yStride, 0));
            lastFrameColorRange_.store(colorRange);
            lastFrameOutputType_.store(frameOutputType);
            generation = ++decodedFormatGeneration_;
        }
    }

    if (!metadataChanged) {
        return false;
    }
    if (firstCommit) {
        LOGI("decoded video format commit generation=%llu size=%dx%d format=%s stride=%d range=%d",
             static_cast<unsigned long long>(generation), frameWidth, frameHeight,
             formatName.c_str(), std::max(yStride, 0), colorRange);
        return false;
    }
    if (!formatDiscontinuity) {
        return false;
    }

    decodedFormatChangeCount_.fetch_add(1);
    agcValid_.store(false);
    agcFrameCounter_.store(0);
    nv12AgcValid_.store(false);
    nv12AgcFrameCounter_.store(0);
    nv12AgcLastFrameWidth_.store(frameWidth);
    nv12AgcLastFrameHeight_.store(frameHeight);
    oesRenderer_.resetAgc();
    LOGI("decoded video format change generation=%llu %dx%d/%d -> %dx%d/%d stride=%d",
         static_cast<unsigned long long>(generation), oldWidth, oldHeight, oldFormat,
         frameWidth, frameHeight, frameFormat, std::max(yStride, 0));
    return true;
}

void NativePlayer::resetRealtimeClockForFormatDiscontinuity() {
    resetVideoPtsDiagnostics();
    PlayerState state;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state = state_;
    }
    if (!isRealtimeInput_ || state != PlayerState::Playing || startupKeyFrameWait_) {
        return;
    }

    const bool clearedCatchupGate = dropUntilKeyFrame_;
    resetRealtimeClock();
    lastVideoDelayUs_.store(0);
    realtimeClockFormatResetCount_.fetch_add(1);
    LOGI("realtime clock reset for decoded format discontinuity clearedCatchupGate=%d count=%lld",
         clearedCatchupGate ? 1 : 0,
         static_cast<long long>(realtimeClockFormatResetCount_.load()));
}

void NativePlayer::resetVideoPtsDiagnostics() {
    // LAT1: invalidate all media-timeline PTS diagnostics for this generation and
    // advance the generation. Call on new input session, reconnect, format
    // discontinuity, and stats reset so stale PTS never leaks across timelines.
    videoPtsGeneration_.fetch_add(1);
    latestVideoPacketPtsUs_.store(-1);
    videoPacketPtsValid_.store(false);
    latestDecoderInputPtsUs_.store(-1);
    decoderInputPtsValid_.store(false);
    latestDecodedFramePtsUs_.store(-1);
    decodedFramePtsValid_.store(false);
    latestRenderedFramePtsUs_.store(-1);
    renderedFramePtsValid_.store(false);
    maxVideoPacketPtsUs_.store(-1);
    maxDecoderInputPtsUs_.store(-1);
    maxDecodedFramePtsUs_.store(-1);
    maxRenderedFramePtsUs_.store(-1);
    demuxToDecoderBacklogUs_.store(-1);
    decoderBacklogUs_.store(-1);
    renderBacklogUs_.store(-1);
    clientMediaBacklogUs_.store(-1);
    clientMediaBacklogValid_.store(false);
    latencyPtsResetCount_.fetch_add(1);
    videoPtsBackwardCount_.store(0);
    decoderPtsBackwardCount_.store(0);
    decodedPtsBackwardCount_.store(0);
    renderedPtsBackwardCount_.store(0);
    // LAT2: clear timing correlation records so old/new sessions never cross.
    resetStageTimingCorrelation();
    // LAT5: clear pre-T0 read/gap/burst/stall diagnostics and invalidate the
    // previous video return timestamp so no fake gap spans a new session.
    diagnostics_.resetPreT0();
    effectiveFmtCtxMaxDelayUs_.store(0);
    // LAT6: invalidate the wall-clock bridge and advance the E2E generation so
    // stale sender/receiver wall samples can never span sessions. The stream
    // time_base evidence is cleared with the input it belonged to.
    lastPacketReadyWallNs_.store(-1);
    videoStreamTimeBaseNum_.store(0);
    videoStreamTimeBaseDen_.store(0);
    e2eGeneration_.fetch_add(1);
    e2eResetCount_.fetch_add(1);
    // LAT6-FINAL: drop the SR anchor and srSendToT0 distribution with the old
    // session so no stale sender wall sample can map into a new one.
    diagnostics_.resetE2E();
}

void NativePlayer::processRtcpTimebase(int64_t t0WallNs) {
    const int64_t tbNum = videoStreamTimeBaseNum_.load();
    const int64_t tbDen = videoStreamTimeBaseDen_.load();
    if (tbNum > 0 && tbDen > 0) {
        diagnostics_.setRtpClockRate(tbDen / tbNum);
    }
    // FFmpeg 8 exports a received SR exactly once, on the next packet.
    size_t srSize = 0;
    const uint8_t *srData = av_packet_get_side_data(packet_, AV_PKT_DATA_RTCP_SR, &srSize);
    if (srData != nullptr && srSize >= sizeof(AVRTCPSenderReport)) {
        AVRTCPSenderReport sr;
        std::memcpy(&sr, srData, sizeof(sr));
        diagnostics_.onSenderReport(sr.ssrc, sr.ntp_timestamp, sr.rtp_timestamp);
    }

    const RtcpSrTracker::Snapshot srSnap = diagnostics_.senderReportSnapshot();
    if (!srSnap.hasAnchor || !srSnap.srMappingValid || srSnap.lastSrNtpNs <= 0) {
        return;
    }

    // PRFT is computed by libavformat from THIS packet's raw RTP timestamp and
    // the latest SR RTP/NTP anchor. Co-location on AVPacket is the same-frame
    // correlation key; a latest-SR/latest-T0 pairing is explicitly forbidden.
    size_t prftSize = 0;
    const uint8_t *prftData = av_packet_get_side_data(packet_, AV_PKT_DATA_PRFT, &prftSize);
    if (prftData == nullptr || prftSize < sizeof(AVProducerReferenceTime)) {
        diagnostics_.onE2ESameFrameUnmatched();
        return;
    }
    AVProducerReferenceTime prft;
    std::memcpy(&prft, prftData, sizeof(prft));
    if (prft.wallclock < 0
            || prft.wallclock > std::numeric_limits<int64_t>::max() / 1000LL) {
        diagnostics_.onE2ESameFrameUnmatched();
        return;
    }
    diagnostics_.onE2ESameFrameMapped();

    const E2ESampleResult sample =
            measureSenderSendToReceiverT0Us(prft.wallclock * 1000LL, t0WallNs);
    if (steadyStateValid_.load()) {
        diagnostics_.onE2ESample(sample, kE2EClockSyncValid);
    }
}

void NativePlayer::resetStageTimingCorrelation() {
    stageTimingRecords_.clear();
    demuxSubmitTiming_.last.store(-1);
    demuxSubmitTiming_.total.store(0);
    demuxSubmitTiming_.count.store(0);
    demuxSubmitTiming_.max.store(0);
    decoderTiming_.last.store(-1);
    decoderTiming_.total.store(0);
    decoderTiming_.count.store(0);
    decoderTiming_.max.store(0);
    decodeRenderTiming_.last.store(-1);
    decodeRenderTiming_.total.store(0);
    decodeRenderTiming_.count.store(0);
    decodeRenderTiming_.max.store(0);
    renderTiming_.last.store(-1);
    renderTiming_.total.store(0);
    renderTiming_.count.store(0);
    renderTiming_.max.store(0);
    packetRenderTiming_.last.store(-1);
    packetRenderTiming_.total.store(0);
    packetRenderTiming_.count.store(0);
    packetRenderTiming_.max.store(0);
    stageTimingSampleCount_.store(0);
    decoderTimingUnmatchedCount_.store(0);
    renderTimingUnmatchedCount_.store(0);
    stageTimingForcedEvictionCount_.store(0);
    stageTimingClockAnomalyCount_.store(0);
    stageTimingResetCount_.fetch_add(1);
    // LAT3: clear steady-state distribution windows and warm-up gate.
    diagnostics_.resetLatency();
    steadyStateValid_.store(false);
}

bool NativePlayer::finalizeStageTiming(VideoStageTiming &record) {
    const int64_t t0 = record.packetReadyMonoUs;
    const int64_t t1 = record.decoderSubmitMonoUs;
    const int64_t t2 = record.decodedOutputMonoUs;
    const int64_t t3 = record.renderBeginMonoUs;
    const int64_t t4 = record.renderSubmitMonoUs;
    if (!diagnostics_.latencyEnabled()) {
        // BASIC keeps only the useful packet-ready -> render-submit health
        // value. Intermediate stage metrics and distributions stay disabled.
        if (t0 < 0 || t4 < t0) {
            if (t0 >= 0 && t4 >= 0) {
                stageTimingClockAnomalyCount_.fetch_add(1);
            }
            return false;
        }
        recordCost(packetRenderTiming_.last, packetRenderTiming_.total,
                   packetRenderTiming_.count, packetRenderTiming_.max, t4 - t0);
        return true;
    }
    if (t0 < 0 || t1 < 0 || t2 < 0 || t3 < 0 || t4 < 0) {
        return false;
    }
    // All timestamps share one monotonic clock, so a completed frame must be
    // non-decreasing. Violations indicate a correlation bug, not clamp-worthy.
    if (t1 < t0 || t2 < t1 || t3 < t2 || t4 < t3) {
        stageTimingClockAnomalyCount_.fetch_add(1);
        return false;
    }
    const int64_t demuxSubmitUs = t1 - t0;
    const int64_t decoderResidenceUs = t2 - t1;
    const int64_t decodeRenderUs = t3 - t2;
    const int64_t renderUs = t4 - t3;
    const int64_t packetRenderUs = t4 - t0;
    recordCost(demuxSubmitTiming_.last, demuxSubmitTiming_.total, demuxSubmitTiming_.count,
               demuxSubmitTiming_.max, demuxSubmitUs);
    recordCost(decoderTiming_.last, decoderTiming_.total, decoderTiming_.count,
               decoderTiming_.max, decoderResidenceUs);
    recordCost(decodeRenderTiming_.last, decodeRenderTiming_.total, decodeRenderTiming_.count,
               decodeRenderTiming_.max, decodeRenderUs);
    recordCost(renderTiming_.last, renderTiming_.total, renderTiming_.count,
               renderTiming_.max, renderUs);
    recordCost(packetRenderTiming_.last, packetRenderTiming_.total, packetRenderTiming_.count,
               packetRenderTiming_.max, packetRenderUs);
    // LAT3: exclude warm-up samples from the steady-state distribution window;
    // ALL_TIME last/avg/max above always keeps the full history.
    const int64_t newCount = stageTimingSampleCount_.fetch_add(1) + 1;
    if (diagnostics_.latencyEnabled() && newCount > kStageTimingWarmupSamples) {
        steadyStateValid_.store(true);
        diagnostics_.onStageSample(demuxSubmitUs, decoderResidenceUs,
                                   decodeRenderUs, renderUs, packetRenderUs);
    }
    return true;
}

void NativePlayer::recordVideoStageTiming(int64_t generation, int64_t ptsUs, StageTimingPoint stage, int64_t monoUs) {
    if (!diagnostics_.basicEnabled() || generation < 0 || ptsUs < 0 || monoUs < 0) {
        return;
    }
    if (!diagnostics_.latencyEnabled()
            && stage != StageTimingPoint::PacketReady
            && stage != StageTimingPoint::RenderSubmit) {
        return;
    }

    auto recordIt = stageTimingRecords_.end();
    for (auto it = stageTimingRecords_.begin(); it != stageTimingRecords_.end(); ++it) {
        if (it->generation == generation && it->ptsUs == ptsUs) {
            recordIt = it;
            break;
        }
    }

    if (recordIt == stageTimingRecords_.end() && stage != StageTimingPoint::PacketReady) {
        // Output-side event without a matching packet record is a correlation
        // miss, never a fabricated record.
        if (stage == StageTimingPoint::DecodedOutput) {
            decoderTimingUnmatchedCount_.fetch_add(1);
        } else if (stage == StageTimingPoint::RenderBegin) {
            renderTimingUnmatchedCount_.fetch_add(1);
        }
        return;
    }

    if (recordIt == stageTimingRecords_.end()) {
        if (stageTimingRecords_.size() >= kStageTimingMaxRecords) {
            // Forced eviction of an unresolved record (completed records are
            // retired on RenderSubmit, so this only happens on correlation stalls).
            stageTimingRecords_.pop_front();
            stageTimingForcedEvictionCount_.fetch_add(1);
        }
        stageTimingRecords_.push_back(VideoStageTiming{});
        recordIt = stageTimingRecords_.end();
        --recordIt;
        recordIt->generation = generation;
        recordIt->ptsUs = ptsUs;
    }

    switch (stage) {
        case StageTimingPoint::PacketReady:
            recordIt->packetReadyMonoUs = monoUs;
            break;
        case StageTimingPoint::DecoderSubmit:
            recordIt->decoderSubmitMonoUs = monoUs;
            break;
        case StageTimingPoint::DecodedOutput:
            recordIt->decodedOutputMonoUs = monoUs;
            break;
        case StageTimingPoint::RenderBegin:
            recordIt->renderBeginMonoUs = monoUs;
            break;
        case StageTimingPoint::RenderSubmit:
            recordIt->renderSubmitMonoUs = monoUs;
            // Retire the completed (or anomalous) record so only in-flight records
            // remain bounded; keeps stageTimingForcedEvictionCount meaningful.
            finalizeStageTiming(*recordIt);
            stageTimingRecords_.erase(recordIt);
            break;
    }
}

void NativePlayer::recordStageTimingRenderSubmit(int64_t ptsUs) {
    if (!diagnostics_.basicEnabled()) {
        return;
    }
    recordVideoStageTiming(videoPtsGeneration_.load(), ptsUs, StageTimingPoint::RenderSubmit, steadyNowUs());
}

SyncMaster NativePlayer::effectiveSyncMaster(const PlayerOptions &options) const {
    if (options.syncMaster == SyncMaster::AUDIO) {
        const int64_t lastUpdate = audioClockLastUpdateMs_.load();
        const bool stale = lastUpdate > 0 && (nowMs() - lastUpdate) > kAudioClockStaleMs;
        if (isAudioPlaybackMasterAvailable(sourceHasAudio_.load(), audioEnabled_.load(), audioPlayable_.load(),
                                           audioPlaybackClockValid_.load(), stale)) {
            return SyncMaster::AUDIO;
        }
        return SyncMaster::VIDEO;
    }
    return options.syncMaster;
}

std::string NativePlayer::effectiveSyncMasterName(const PlayerOptions &options) const {
    return syncMasterName(effectiveSyncMaster(options));
}

bool NativePlayer::resolveMasterClockUs(const PlayerOptions &options, int64_t videoPtsUs, int64_t &masterClockUs, SyncMaster &effectiveMaster) {
    const int64_t nowUs = steadyNowUs();
    wallClockUs_.store(nowUs);
    effectiveMaster = effectiveSyncMaster(options);

    if (effectiveMaster == SyncMaster::AUDIO) {
        const int64_t audioClockUs = audioPlaybackClockUs_.load();
        if (!audioPlaybackClockValid_.load() || audioClockUs <= 0) {
            return false;
        }
        masterClockUs = audioClockUs;
        if (isValidPts(videoPtsUs)) {
            audioVideoDiffUs_.store(videoPtsUs - audioClockUs);
        }
        return true;
    }

    if (!realtimeClockInitialized_) {
        if (!isValidPts(videoPtsUs)) {
            return false;
        }
        realtimeClockInitialized_ = true;
        realtimeFirstPtsUs_ = videoPtsUs;
        realtimeStartWallUs_ = nowUs;
        masterClockUs = videoPtsUs;
        return true;
    }

    const int64_t wallElapsedUs = nowUs - realtimeStartWallUs_;
    if (wallElapsedUs < 0) {
        resetRealtimeClock();
        return false;
    }

    masterClockUs = realtimeFirstPtsUs_ + wallElapsedUs;
    return masterClockUs >= 0;
}

void NativePlayer::updateVideoDelayStats(int64_t delayUs) {
    const int64_t safeDelayUs = std::max<int64_t>(0, delayUs);
    lastVideoDelayUs_.store(safeDelayUs);
    totalVideoDelayUs_.fetch_add(safeDelayUs);
    videoDelaySampleCount_.fetch_add(1);
    updateMax(maxVideoDelayUs_, safeDelayUs);
}

void NativePlayer::decodeAudioPacket(const AVPacket *packet) {
    if (packet == nullptr || audioCodecContext_ == nullptr || !audioDecodeOpened_.load()) {
        return;
    }
    if (!audioEnabled_.load()) {
        // A1 policy: audioEnabled=false skips playback AAC decode. The recorder
        // still received the original compressed packet earlier in the loop.
        return;
    }

    const int64_t decodeStartUs = steadyNowUs();
    int sendResult = avcodec_send_packet(audioCodecContext_, packet);
    if (sendResult == AVERROR(EAGAIN)) {
        // Decoder output has not been drained yet. Drain pending frames, then
        // retry the send once per the FFmpeg send/receive contract.
        drainAudioDecodedFrames();
        sendResult = avcodec_send_packet(audioCodecContext_, packet);
    }
    if (sendResult == AVERROR(EAGAIN)) {
        // Still not accepted after drain+retry: drop this packet this cycle.
        // No packet queue exists in A1 (per scope), and a subsequent packet's
        // drain keeps the decoder moving, so this is not a permanent loss.
        drainAudioDecodedFrames();
    } else if (sendResult < 0 && sendResult != AVERROR_EOF) {
        ++audioDecodeErrorCount_;
        degradeAudioPlayback();
        logRateLimitedAudioDecodeError(sendResult);
    } else {
        drainAudioDecodedFrames();
    }
    recordCost(lastAudioDecodeCostUs_, totalAudioDecodeCostUs_, audioDecodeCostSampleCount_, maxAudioDecodeCostUs_,
               steadyNowUs() - decodeStartUs);
}

void NativePlayer::drainAudioDecodedFrames() {
    while (!stopRequested_.load()) {
        const int ret = avcodec_receive_frame(audioCodecContext_, audioDecodedFrame_);
        if (ret == 0) {
            audioFrameCount_.fetch_add(1);
            const int nbSamples = std::max(0, audioDecodedFrame_->nb_samples);
            audioDecodedSampleCount_.fetch_add(nbSamples);
            lastDecodedAudioNbSamples_.store(nbSamples);
            lastDecodedAudioSampleRate_.store(audioDecodedFrame_->sample_rate);
            lastDecodedAudioChannels_.store(audioDecodedFrame_->ch_layout.nb_channels);
            lastDecodedAudioSampleFormat_.store(audioDecodedFrame_->format);
            int64_t framePts = audioDecodedFrame_->best_effort_timestamp;
            if (framePts == AV_NOPTS_VALUE) {
                framePts = audioDecodedFrame_->pts;
            }
            if (framePts != AV_NOPTS_VALUE && formatContext_ != nullptr
                && audioStreamIndex_ >= 0 && audioStreamIndex_ < static_cast<int>(formatContext_->nb_streams)) {
                lastDecodedAudioPtsUs_.store(av_rescale_q(framePts, formatContext_->streams[audioStreamIndex_]->time_base, AV_TIME_BASE_Q));
            }
            if (!audioDecodeFirstFrameLogged_) {
                audioDecodeFirstFrameLogged_ = true;
                const char *fmtName = av_get_sample_fmt_name(static_cast<AVSampleFormat>(audioDecodedFrame_->format));
                LOGI("audio decoded frame: codec=%s sampleRate=%d channels=%d sampleFormat=%s nbSamples=%d ptsUs=%lld",
                     audioCodec_.c_str(), audioDecodedFrame_->sample_rate, audioDecodedFrame_->ch_layout.nb_channels,
                     fmtName == nullptr ? "unknown" : fmtName, nbSamples,
                     static_cast<long long>(lastDecodedAudioPtsUs_.load()));
            }
            // A2: decode -> PCM (S16/48k/stereo interleaved) -> stats -> discard.
            convertAudioFrameToPcm(audioDecodedFrame_);
            av_frame_unref(audioDecodedFrame_);
        } else if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            break;
        } else {
            ++audioDecodeErrorCount_;
            degradeAudioPlayback();
            logRateLimitedAudioDecodeError(ret);
            break;
        }
    }
}

void NativePlayer::logRateLimitedAudioDecodeError(int errorCode) {
    const int64_t now = nowMs();
    const int64_t last = lastAudioDecodeErrorLogMs_.load();
    if (last == 0 || now - last >= 2000) {
        lastAudioDecodeErrorLogMs_.store(now);
        const std::string error = ffmpegErrorToString(errorCode);
        LOGE("audio decode error code=%d message=%s", errorCode, error.c_str());
    }
}

bool NativePlayer::convertAudioFrameToPcm(AVFrame *frame) {
    if (frame == nullptr) {
        return false;
    }

    // Input format identity comes from the real decoded frame; fall back to the
    // codec context only when the frame lacks the fields.
    int inputSampleRate = frame->sample_rate > 0 ? frame->sample_rate
                         : (audioCodecContext_ != nullptr ? audioCodecContext_->sample_rate : 0);
    int inputChannels = frame->ch_layout.nb_channels > 0 ? frame->ch_layout.nb_channels
                        : (audioCodecContext_ != nullptr ? audioCodecContext_->ch_layout.nb_channels : 0);
    const AVSampleFormat inputFormat = frame->format >= 0
            ? static_cast<AVSampleFormat>(frame->format)
            : (audioCodecContext_ != nullptr ? audioCodecContext_->sample_fmt : AV_SAMPLE_FMT_NONE);

    if (inputSampleRate <= 0 || inputChannels <= 0 || inputFormat == AV_SAMPLE_FMT_NONE) {
        ++audioResampleErrorCount_;
        degradeAudioPlayback();
        logRateLimitedAudioResampleError("invalid input audio format");
        return false;
    }

    // Derive a usable input channel layout. Decoder frames may carry an
    // unspecified layout; fall back to the FFmpeg default layout for the
    // channel count (1 -> mono, 2 -> stereo, ...) when the mask is unknown.
    uint64_t inputLayoutMask = 0;
    if (frame->ch_layout.order == AV_CHANNEL_ORDER_NATIVE && frame->ch_layout.nb_channels > 0) {
        inputLayoutMask = frame->ch_layout.u.mask;
    }

    const bool hadConfiguredSwr = audioSwrContext_ != nullptr;
    const bool identityChanged = audioSwrContext_ == nullptr
            || audioSwrInputSampleFormat_ != static_cast<int>(inputFormat)
            || audioSwrInputSampleRate_ != inputSampleRate
            || audioSwrInputChannels_ != inputChannels
            || audioSwrInputLayoutMask_ != inputLayoutMask;
    if (identityChanged) {
        if (hadConfiguredSwr) {
            // A mid-session decoded format change is a real audio
            // discontinuity. Discard old-format queued/AudioTrack bytes and
            // force the next output block to establish a new clock base.
            flushAudioPcmForDiscontinuity();
            if (audioEnabled_.load() && !pauseRequested_.load()) {
                startAudioSinkForCurrentGeneration();
            }
        }
        if (!configureAudioSwrContext(static_cast<int>(inputFormat), inputSampleRate,
                                      inputLayoutMask, inputChannels)) {
            ++audioResampleErrorCount_;
            degradeAudioPlayback();
            logRateLimitedAudioResampleError("swr_alloc_set_opts2/swr_init failed");
            return false;
        }
        audioSwrReconfigureCount_.fetch_add(1);
        const char *fmtName = av_get_sample_fmt_name(inputFormat);
        LOGI("audio swr reconfigured input=%dHz/%dch/%s output=%dHz/2/s16 reconfigureCount=%lld",
             inputSampleRate, inputChannels, fmtName == nullptr ? "unknown" : fmtName,
             kAudioPcmOutputSampleRate, static_cast<long long>(audioSwrReconfigureCount_.load()));
    }

    // Output sample capacity must account for the swr internal resampling delay
    // plus this frame's input samples, rounded up, per the FFmpeg convention.
    const int64_t delaySamples = swr_get_delay(audioSwrContext_, inputSampleRate);
    const int64_t requestedOutSamples = av_rescale_rnd(delaySamples + std::max(0, frame->nb_samples),
                                                       kAudioPcmOutputSampleRate, inputSampleRate, AV_ROUND_UP);
    if (requestedOutSamples <= 0
        || requestedOutSamples > static_cast<int64_t>(std::numeric_limits<int>::max())) {
        ++audioResampleErrorCount_;
        degradeAudioPlayback();
        logRateLimitedAudioResampleError("invalid output sample count");
        return false;
    }
    const int64_t requiredBytes = requestedOutSamples
            * static_cast<int64_t>(kAudioPcmOutputChannels)
            * static_cast<int64_t>(sizeof(int16_t));
    if (requiredBytes <= 0
        || static_cast<uint64_t>(requiredBytes) > std::numeric_limits<size_t>::max()) {
        ++audioResampleErrorCount_;
        degradeAudioPlayback();
        logRateLimitedAudioResampleError("PCM buffer size overflow");
        return false;
    }
    if (audioPcmBuffer_.size() < static_cast<size_t>(requiredBytes)) {
        audioPcmBuffer_.resize(static_cast<size_t>(requiredBytes));
    }

    const int64_t resampleStartUs = steadyNowUs();
    uint8_t *outData[] = { audioPcmBuffer_.data() };
    const uint8_t *const *inData = frame->extended_data != nullptr
            ? frame->extended_data
            : const_cast<const uint8_t **>(frame->data);
    const int convertedSamples = swr_convert(audioSwrContext_, outData, static_cast<int>(requestedOutSamples),
                                             inData, std::max(0, frame->nb_samples));
    recordCost(lastAudioResampleCostUs_, totalAudioResampleCostUs_, audioResampleCostSampleCount_,
               maxAudioResampleCostUs_, steadyNowUs() - resampleStartUs);

    if (convertedSamples < 0) {
        ++audioResampleErrorCount_;
        degradeAudioPlayback();
        logRateLimitedAudioResampleError("swr_convert failed");
        return false;
    }
    if (convertedSamples == 0) {
        return true;
    }

    audioPcmBlockCount_.fetch_add(1);
    const int64_t pcmBytes = static_cast<int64_t>(convertedSamples)
            * kAudioPcmOutputChannels * static_cast<int64_t>(sizeof(int16_t));
    audioPcmSampleCount_.fetch_add(convertedSamples);
    audioPcmByteCount_.fetch_add(pcmBytes);
    if (!audioPcmFirstConvertLogged_) {
        audioPcmFirstConvertLogged_ = true;
        const char *fmtName = av_get_sample_fmt_name(inputFormat);
        LOGI("audio pcm ready input=%dHz/%dch/%s output=%dHz/2/s16 convertedSamples=%d bytes=%lld",
             inputSampleRate, inputChannels, fmtName == nullptr ? "unknown" : fmtName,
             kAudioPcmOutputSampleRate, convertedSamples, static_cast<long long>(pcmBytes));
    }

    // PCM media PTS association: use the decoded frame's media start time
    // (best-effort timestamp, fallback pts), rescaled to microseconds. This is
    // the block start PTS for the A3 queue; resampling does not move the
    // media timeline.
    int64_t framePts = frame->best_effort_timestamp;
    if (framePts == AV_NOPTS_VALUE) {
        framePts = frame->pts;
    }
    int64_t pcmStartPtsUs = 0;
    if (framePts != AV_NOPTS_VALUE && formatContext_ != nullptr
        && audioStreamIndex_ >= 0 && audioStreamIndex_ < static_cast<int>(formatContext_->nb_streams)) {
        pcmStartPtsUs = av_rescale_q(framePts, formatContext_->streams[audioStreamIndex_]->time_base, AV_TIME_BASE_Q);
        lastPcmPtsUs_.store(pcmStartPtsUs);
    }

    // A3: hand the PCM block to the bounded output queue. The block owns a copy
    // of the PCM bytes (the scratch buffer is reused on the next frame). The
    // producer never waits for queue space; overflow drops the oldest blocks.
    if (audioEnabled_.load() && !pauseRequested_.load() && !stopRequested_.load()) {
        AudioPcmQueue::Block block;
        block.data.assign(audioPcmBuffer_.data(), audioPcmBuffer_.data() + static_cast<size_t>(pcmBytes));
        block.startPtsUs = pcmStartPtsUs;
        block.sampleCount = convertedSamples;
        block.generation = audioQueueGeneration_.load();
        audioPcmQueue_.enqueue(std::move(block));
    }
    // A valid conversion recovers decoder/SWR-only degradation. Sink failures
    // remain non-playable until a full AudioTrack write succeeds.
    recomputeAudioPlayable();
    return true;
}

bool NativePlayer::configureAudioSwrContext(int inputFormat, int inputSampleRate,
                                            uint64_t inputLayoutMask, int inputChannels) {
    if (audioSwrContext_ != nullptr) {
        swr_free(&audioSwrContext_);
    }
    audioSwrInputSampleFormat_ = -1;
    audioSwrInputSampleRate_ = 0;
    audioSwrInputChannels_ = 0;
    audioSwrInputLayoutMask_ = 0;

    AVChannelLayout inLayout{};
    if (inputLayoutMask != 0 && av_channel_layout_from_mask(&inLayout, inputLayoutMask) < 0) {
        av_channel_layout_uninit(&inLayout);
        inLayout = AVChannelLayout{};
    }
    if (inLayout.nb_channels <= 0) {
        av_channel_layout_default(&inLayout, inputChannels);
    }
    AVChannelLayout outLayout{};
    av_channel_layout_default(&outLayout, kAudioPcmOutputChannels);
    const int result = swr_alloc_set_opts2(&audioSwrContext_, &outLayout, kAudioPcmOutputFormat,
                                           kAudioPcmOutputSampleRate, &inLayout,
                                           static_cast<AVSampleFormat>(inputFormat),
                                           inputSampleRate, 0, nullptr);
    av_channel_layout_uninit(&inLayout);
    av_channel_layout_uninit(&outLayout);
    if (result < 0 || audioSwrContext_ == nullptr) {
        audioSwrContext_ = nullptr;
        return false;
    }
    if (swr_init(audioSwrContext_) < 0) {
        swr_free(&audioSwrContext_);
        return false;
    }

    audioSwrInputSampleFormat_ = inputFormat;
    audioSwrInputSampleRate_ = inputSampleRate;
    audioSwrInputChannels_ = inputChannels;
    audioSwrInputLayoutMask_ = inputLayoutMask;
    return true;
}

void NativePlayer::logRateLimitedAudioResampleError(const char *message) {
    const int64_t now = nowMs();
    const int64_t last = lastAudioResampleErrorLogMs_.load();
    if (last == 0 || now - last >= 2000) {
        lastAudioResampleErrorLogMs_.store(now);
        LOGE("audio resample error: %s", message);
    }
}

#if FFMPEGPLAYER_ENABLE_TEST_HOOKS
void setAudioWorkerBackpressureTestDelayMs(int delayMs) {
    g_audio_worker_test_delay_ms.store(delayMs < 0 ? 0 : delayMs);
}
#endif

int64_t AudioPcmQueue::blockDurationUs(const Block &block) {
    if (block.sampleCount <= 0) {
        return 0;
    }
    return block.sampleCount * 1000000 / kAudioPcmOutputSampleRate;
}

void AudioPcmQueue::configure(int64_t targetDurationUs, int64_t maxDurationUs) {
    std::lock_guard<std::mutex> lock(mutex_);
    targetDurationUs_ = std::max<int64_t>(1, targetDurationUs);
    maxDurationUs_ = std::max<int64_t>(targetDurationUs_, maxDurationUs);
}

void AudioPcmQueue::enqueue(Block block) {
    std::lock_guard<std::mutex> lock(mutex_);
    const int64_t blockUs = blockDurationUs(block);
    // Bounded: drop oldest blocks until the new block fits within the hard max.
    // Producer never blocks; this keeps the live edge.
    while (!blocks_.empty() && bufferedDurationUs_ + blockUs > maxDurationUs_) {
        droppedSampleCount_ += blocks_.front().sampleCount;
        bufferedDurationUs_ -= blockDurationUs(blocks_.front());
        ++dropCount_;
        blocks_.pop_front();
    }
    blocks_.push_back(std::move(block));
    bufferedDurationUs_ += blockUs;
    highWatermarkUs_ = std::max(highWatermarkUs_, bufferedDurationUs_);
    cv_.notify_one();
}

bool AudioPcmQueue::waitAndDequeue(Block &out) {
    std::unique_lock<std::mutex> lock(mutex_);
    cv_.wait(lock, [this] { return stopRequested_ || !blocks_.empty(); });
    if (stopRequested_) {
        return false;  // stopped: never consume residual/stale blocks
    }
    if (blocks_.empty()) {
        return false;
    }
    out = std::move(blocks_.front());
    blocks_.pop_front();
    bufferedDurationUs_ -= blockDurationUs(out);
    return true;
}

void AudioPcmQueue::flush() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!blocks_.empty()) {
        ++flushCount_;
    }
    blocks_.clear();
    bufferedDurationUs_ = 0;
    cv_.notify_all();
}

void AudioPcmQueue::requestStop() {
    std::lock_guard<std::mutex> lock(mutex_);
    stopRequested_ = true;
    cv_.notify_all();
}

void AudioPcmQueue::resetForRestart() {
    std::lock_guard<std::mutex> lock(mutex_);
    blocks_.clear();
    bufferedDurationUs_ = 0;
    stopRequested_ = false;
}

void AudioPcmQueue::clearStats() {
    std::lock_guard<std::mutex> lock(mutex_);
    dropCount_ = 0;
    droppedSampleCount_ = 0;
    flushCount_ = 0;
    highWatermarkUs_ = 0;
}

int64_t AudioPcmQueue::durationUs() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return bufferedDurationUs_;
}

int64_t AudioPcmQueue::blockCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return static_cast<int64_t>(blocks_.size());
}

int64_t AudioPcmQueue::byteCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    int64_t bytes = 0;
    for (const auto &block : blocks_) {
        bytes += static_cast<int64_t>(block.data.size());
    }
    return bytes;
}

int64_t AudioPcmQueue::dropCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return dropCount_;
}

int64_t AudioPcmQueue::droppedSampleCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return droppedSampleCount_;
}

int64_t AudioPcmQueue::flushCount() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return flushCount_;
}

int64_t AudioPcmQueue::highWatermarkUs() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return highWatermarkUs_;
}

void NativePlayer::audioOutputWorkerLoop() {
    LOGI("audio output worker started");
    // Attach this native worker thread to the JVM once for its whole lifetime;
    // detach once on exit (no cross-thread JNIEnv reuse, no per-block attach).
    bool attached = false;
    JNIEnv *env = getJniEnvForCurrentThread(attached);
    if (env == nullptr) {
        LOGE("audio output worker: JNIEnv unavailable; PCM will be discarded");
        ++audioSinkWriteErrorCount_;
        audioSinkLastErrorCode_.store(-1);
        audioSinkReady_.store(false);
        degradeAudioPlayback();
    }
    AudioPcmQueue::Block block;
    while (true) {
        const bool got = audioPcmQueue_.waitAndDequeue(block);
        if (!got) {
            break;
        }
#if FFMPEGPLAYER_ENABLE_TEST_HOOKS
        const int testDelayMs = g_audio_worker_test_delay_ms.load();
        if (testDelayMs > 0) {
            // Test-only backpressure hook: simulate a slow consumer to prove the
            // playback thread never blocks. Never enabled in production.
            std::this_thread::sleep_for(std::chrono::milliseconds(testDelayMs));
        }
#endif
        if (!audioEnabled_.load()
            || pauseRequested_.load()
            || stopRequested_.load()
            || block.generation != audioQueueGeneration_.load()) {
            audioWorkerStaleBlockCount_.fetch_add(1);
            block.data.clear();
            continue;
        }
        audioWorkerConsumedBlockCount_.fetch_add(1);
        audioWorkerConsumedSampleCount_.fetch_add(block.sampleCount);
        audioWorkerConsumedByteCount_.fetch_add(static_cast<int64_t>(block.data.size()));
        lastConsumedPcmPtsUs_.store(block.startPtsUs);
        // Publish the AudioTrack playback-head based clock (rebase on a new
        // generation), then hand the block to the JNI sink. The Java sink uses
        // a non-blocking write and its own lifecycle epoch.
        if (env != nullptr && !block.data.empty()) {
            updateAudioPlaybackClock(env, block);
            writeAudioPcmToSink(env, block);
        }
        block.data.clear();
    }
    audioWorkerRunning_.store(false);
    detachCurrentThreadIfNeeded(attached);
    LOGI("audio output worker ended");
}

void NativePlayer::startAudioOutputWorker() {
    std::lock_guard<std::mutex> lock(audioWorkerMutex_);
    if (audioOutputWorkerThread_.joinable()) {
        return;
    }
    audioPcmQueue_.resetForRestart();
    audioWorkerRunning_.store(true);
    audioWorkerStartCount_.fetch_add(1);
    audioOutputWorkerThread_ = std::thread(&NativePlayer::audioOutputWorkerLoop, this);
}

void NativePlayer::stopAudioOutputWorker() {
    std::thread worker;
    {
        std::lock_guard<std::mutex> lock(audioWorkerMutex_);
        worker = std::move(audioOutputWorkerThread_);
    }
    if (worker.joinable()) {
        audioPcmQueue_.requestStop();
        worker.join();
        audioWorkerRunning_.store(false);
        audioWorkerJoinCount_.fetch_add(1);
    }
}

void NativePlayer::flushAudioPcmForDiscontinuity() {
    audioPcmQueue_.flush();
    const int64_t generation = audioQueueGeneration_.fetch_add(1) + 1;
    // The AudioTrack playback-head base and media timeline are now invalid; the
    // new source generation must re-anchor before audio becomes the master.
    invalidateAudioClock();
    audioClockGeneration_.store(generation);
    audioClockBaseMediaPtsUs_.store(0);
    audioClockExpectedNextPtsUs_.store(0);
    audioPlaybackHeadRaw32_.store(0);
    audioPlaybackHeadExtended64_.store(0);
    audioPlaybackHeadFrames_.store(0);
    audioPlaybackClockUs_.store(0);
    audioClockLastUpdateMs_.store(0);
    audioVideoDiffUs_.store(0);
    // Clear stale AudioTrack-buffered data too, so the new source generation
    // never plays old audio. onAudioPcm lazily resumes playback on next write.
    sendAudioSinkControl(kAudioSinkCmdPauseFlush, "pause_flush");
}

void NativePlayer::resetAudioDecoderForDiscontinuity(const char *reason) {
    // Called only by the playback thread: decoder/SWR state is never touched
    // concurrently from a JNI lifecycle thread.
    if (audioCodecContext_ != nullptr) {
        avcodec_flush_buffers(audioCodecContext_);
    }
    if (audioSwrContext_ != nullptr) {
        swr_free(&audioSwrContext_);
    }
    audioSwrInputLayoutMask_ = 0;
    audioSwrInputSampleFormat_ = -1;
    audioSwrInputSampleRate_ = 0;
    audioSwrInputChannels_ = 0;
    audioPcmBuffer_.clear();
    LOGI("audio decoder/SWR reset reason=%s generation=%lld",
         reason == nullptr ? "unknown" : reason,
         static_cast<long long>(audioQueueGeneration_.load()));
}

void NativePlayer::startAudioSinkForCurrentGeneration() {
    if (!audioEnabled_.load() || pauseRequested_.load() || !audioCallbackSet_.load()) {
        return;
    }
    if (sendAudioSinkControl(kAudioSinkCmdStart, "start")) {
        audioSinkReady_.store(true);
        audioSinkLastErrorCode_.store(0);
        audioSinkRestartCount_.fetch_add(1);
        recomputeAudioPlayable();
    }
}

void NativePlayer::degradeAudioPlayback() {
    audioPlayable_.store(false);
    invalidateAudioClock();
}

void NativePlayer::recomputeAudioPlayable() {
    // A0 frozen semantics: audioPlayable means the full playback pipeline
    // (decode -> PCM -> queue -> worker -> sink -> AudioTrack) is wired and
    // capable. A4 completes that pipeline, so it now reflects real capability.
    const bool playable = audioEnabled_.load()
                          && sourceHasAudio_.load()
                          && audioDecodeOpened_.load()
                          && audioSinkReady_.load();
    audioPlayable_.store(playable);
}

int32_t NativePlayer::queryAudioPlaybackHead(JNIEnv *env) {
    if (env == nullptr) {
        return -1;
    }
    jobject sinkLocal = nullptr;
    jmethodID headMethod = nullptr;
    {
        std::lock_guard<std::mutex> lock(audioSinkMutex_);
        if (audioSinkGlobalRef_ == nullptr || audioSinkHeadMethodId_ == nullptr) {
            return -1;
        }
        sinkLocal = env->NewLocalRef(audioSinkGlobalRef_);
        headMethod = audioSinkHeadMethodId_;
    }
    if (sinkLocal == nullptr) {
        return -1;
    }
    const jint head = env->CallIntMethod(sinkLocal, headMethod);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(sinkLocal);
        return -1;
    }
    env->DeleteLocalRef(sinkLocal);
    return static_cast<int32_t>(head);
}

void NativePlayer::invalidateAudioClock() {
    audioPlaybackClockValid_.store(false);
}

void NativePlayer::updateAudioPlaybackClock(JNIEnv *env, const AudioPcmQueue::Block &block) {
    const int32_t rawHead = queryAudioPlaybackHead(env);
    if (rawHead < 0) {
        if (audioPlaybackClockValid_.load()) {
            audioPlaybackClockValid_.store(false);
            audioClockStaleCount_.fetch_add(1);
        }
        return;
    }
    // A realtime catch-up gate and the bounded PCM queue can both discard old
    // audio without changing generation. Detect that media-PTS hole here so
    // the AudioTrack playback-head mapping cannot accumulate permanent drift.
    const int64_t expectedNextPtsUs = audioClockExpectedNextPtsUs_.load();
    const int64_t blockDurationUs = block.sampleCount > 0
                                    ? block.sampleCount * 1000000 / kAudioPcmOutputSampleRate
                                    : 0;
    const int64_t ptsGapUs = expectedNextPtsUs > 0 && block.startPtsUs > 0
                             ? block.startPtsUs - expectedNextPtsUs
                             : 0;
    const bool ptsDiscontinuity = expectedNextPtsUs > 0
                                  && block.startPtsUs > 0
                                  && std::llabs(ptsGapUs) > kAudioClockPtsJitterToleranceUs;
    // Rebase on a new generation, an invalid clock, or a same-generation PTS
    // discontinuity. This changes only the clock mapping; the sink, worker,
    // decoder, queue, recorder, and MediaCodec lifetime remain untouched.
    const bool rebase = !audioPlaybackClockValid_.load()
                        || block.generation != audioClockGeneration_.load()
                        || ptsDiscontinuity;
    if (rebase) {
        if (block.startPtsUs <= 0) {
            // No valid media PTS yet; cannot anchor the media timeline.
            return;
        }
        audioClockGeneration_.store(block.generation);
        audioClockBaseMediaPtsUs_.store(block.startPtsUs);
        audioPlaybackHeadRaw32_.store(rawHead);
        audioPlaybackHeadExtended64_.store(0);
        audioClockResetCount_.fetch_add(1);
        if (ptsDiscontinuity) {
            audioClockPtsDiscontinuityCount_.fetch_add(1);
            const int64_t nowMsValue = nowMs();
            const int64_t lastLogMs = lastAudioClockPtsDiscontinuityLogMs_.load();
            if (lastLogMs == 0 || nowMsValue - lastLogMs >= 2000) {
                lastAudioClockPtsDiscontinuityLogMs_.store(nowMsValue);
                LOGI("audio playback clock PTS rebase gapUs=%lld blockPtsUs=%lld expectedPtsUs=%lld generation=%lld count=%lld",
                     static_cast<long long>(ptsGapUs),
                     static_cast<long long>(block.startPtsUs),
                     static_cast<long long>(expectedNextPtsUs),
                     static_cast<long long>(block.generation),
                     static_cast<long long>(audioClockPtsDiscontinuityCount_.load()));
            }
        }
    }
    if (block.startPtsUs > 0 && blockDurationUs > 0) {
        audioClockExpectedNextPtsUs_.store(block.startPtsUs + blockDurationUs);
    }
    // Convert the raw 32-bit playback head into a monotonic 64-bit played-frame
    // count. The uint32 subtraction handles the 32-bit wraparound.
    const int32_t lastRaw = audioPlaybackHeadRaw32_.load();
    const int64_t delta = static_cast<int32_t>(static_cast<uint32_t>(rawHead) - static_cast<uint32_t>(lastRaw));
    audioPlaybackHeadExtended64_.fetch_add(delta);
    audioPlaybackHeadRaw32_.store(rawHead);

    const int64_t played = audioPlaybackHeadExtended64_.load();
    const int64_t clockUs = audioClockBaseMediaPtsUs_.load() + played * 1000000 / kAudioPcmOutputSampleRate;
    audioPlaybackHeadFrames_.store(played);
    audioPlaybackClockUs_.store(clockUs);
    audioPlaybackClockValid_.store(true);
    audioClockLastUpdateMs_.store(nowMs());
}

void NativePlayer::waitForAudioMasterIfEarly(int64_t ptsUs) {
    if (!isValidPts(ptsUs)) {
        return;
    }
    PlayerOptions optionsSnapshot;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        optionsSnapshot = playerOptions_;
    }
    if (effectiveSyncMaster(optionsSnapshot) != SyncMaster::AUDIO) {
        return;
    }
    // Bounded wait: video is ahead of the audible audio clock; wait for it to
    // catch up. Never block on a stale/invalid clock and always bound the wait.
    const int64_t waitStartUs = steadyNowUs();
    while (!stopRequested_.load()) {
        if (!audioPlaybackClockValid_.load()) {
            return;
        }
        const int64_t clockUs = audioPlaybackClockUs_.load();
        if (ptsUs - clockUs <= 0) {
            return;
        }
        if (steadyNowUs() - waitStartUs >= kAudioMasterMaxWaitUs) {
            return;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(kAudioMasterWaitPollMs));
    }
}

bool NativePlayer::writeAudioPcmToSink(JNIEnv *env, const AudioPcmQueue::Block &block) {
    if (!audioEnabled_.load()
        || pauseRequested_.load()
        || stopRequested_.load()
        || block.generation != audioQueueGeneration_.load()) {
        audioSinkControlledCancelCount_.fetch_add(1);
        invalidateAudioClock();
        return false;
    }
    if (env == nullptr) {
        ++audioSinkWriteErrorCount_;
        audioSinkLastErrorCode_.store(-1);
        audioSinkReady_.store(false);
        degradeAudioPlayback();
        return false;
    }
    jobject sinkLocal = nullptr;
    jmethodID writeMethod = nullptr;
    {
        std::lock_guard<std::mutex> lock(audioSinkMutex_);
        if (audioSinkGlobalRef_ == nullptr || audioSinkWriteMethodId_ == nullptr) {
            audioSinkReady_.store(false);
            degradeAudioPlayback();
            return false;
        }
        sinkLocal = env->NewLocalRef(audioSinkGlobalRef_);
        writeMethod = audioSinkWriteMethodId_;
    }
    if (sinkLocal == nullptr) {
        ++audioSinkWriteErrorCount_;
        audioSinkLastErrorCode_.store(-1);
        audioSinkReady_.store(false);
        degradeAudioPlayback();
        return false;
    }

    jobject buffer = env->NewDirectByteBuffer(const_cast<void *>(static_cast<const void *>(block.data.data())),
                                              static_cast<jlong>(block.data.size()));
    if (buffer == nullptr) {
        ++audioSinkWriteErrorCount_;
        audioSinkLastErrorCode_.store(-1);
        audioSinkReady_.store(false);
        degradeAudioPlayback();
        env->DeleteLocalRef(sinkLocal);
        return false;
    }

    const int64_t writeStartUs = steadyNowUs();
    const jint written = env->CallIntMethod(sinkLocal, writeMethod, buffer,
                                            static_cast<jint>(block.data.size()),
                                            static_cast<jlong>(block.startPtsUs));
    const bool exception = env->ExceptionCheck();
    if (exception) {
        env->ExceptionClear();
    }
    recordCost(lastAudioSinkWriteCostUs_, totalAudioSinkWriteCostUs_, audioSinkWriteCostSampleCount_,
               maxAudioSinkWriteCostUs_, steadyNowUs() - writeStartUs);

    env->DeleteLocalRef(buffer);
    env->DeleteLocalRef(sinkLocal);

    if (written == kAudioSinkWriteCancelled
        || !audioEnabled_.load()
        || pauseRequested_.load()
        || stopRequested_.load()
        || block.generation != audioQueueGeneration_.load()) {
        audioSinkControlledCancelCount_.fetch_add(1);
        invalidateAudioClock();
        return false;
    }
    if (exception || written < 0 || written != static_cast<jint>(block.data.size())) {
        ++audioSinkWriteErrorCount_;
        audioSinkLastErrorCode_.store(exception || written >= 0 ? AVERROR(EAGAIN) : written);
        if (written > 0) {
            audioSinkWrittenByteCount_.fetch_add(written);
        }
        audioSinkReady_.store(false);
        // AudioTrack write failure invalidates the playback clock; video must
        // fall back to its own master until a later write re-anchors the clock.
        degradeAudioPlayback();
        return false;
    }
    audioSinkWriteCount_.fetch_add(1);
    audioSinkWrittenByteCount_.fetch_add(written);
    audioSinkLastErrorCode_.store(0);
    audioSinkReady_.store(true);
    recomputeAudioPlayable();
    return true;
}

bool NativePlayer::sendAudioSinkControl(int command, const char *commandName) {
    bool attached = false;
    JNIEnv *env = getJniEnvForCurrentThread(attached);
    if (env == nullptr) {
        LOGE("audio sink control %s: JNIEnv unavailable", commandName);
        return false;
    }
    jobject sinkLocal = nullptr;
    jmethodID controlMethod = nullptr;
    {
        std::lock_guard<std::mutex> lock(audioSinkMutex_);
        if (audioSinkGlobalRef_ == nullptr || audioSinkControlMethodId_ == nullptr) {
            detachCurrentThreadIfNeeded(attached);
            return false;
        }
        sinkLocal = env->NewLocalRef(audioSinkGlobalRef_);
        controlMethod = audioSinkControlMethodId_;
    }
    bool success = false;
    if (sinkLocal != nullptr) {
        const jint result = env->CallIntMethod(sinkLocal, controlMethod, static_cast<jint>(command));
        if (env->ExceptionCheck()) {
            LOGE("audio sink control %s threw", commandName);
            env->ExceptionClear();
        } else {
            success = result >= 0;
        }
        env->DeleteLocalRef(sinkLocal);
    }
    detachCurrentThreadIfNeeded(attached);
    return success;
}

void NativePlayer::deleteAudioSinkGlobalRef(JNIEnv *env) {
    if (env == nullptr) {
        return;
    }
    std::lock_guard<std::mutex> lock(audioSinkMutex_);
    if (audioSinkGlobalRef_ != nullptr) {
        env->DeleteGlobalRef(audioSinkGlobalRef_);
    }
    audioSinkGlobalRef_ = nullptr;
    audioSinkWriteMethodId_ = nullptr;
    audioSinkControlMethodId_ = nullptr;
    audioSinkHeadMethodId_ = nullptr;
    audioSinkReady_.store(false);
}

bool NativePlayer::shouldDropRealtimePacket(const AVPacket *packet) {
    if (packet == nullptr || packet->stream_index != videoStreamIndex_) {
        return false;
    }

    PlayerOptions optionsSnapshot;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        optionsSnapshot = playerOptions_;
    }

    if (!isRealtimeInput_ || !optionsSnapshot.enablePacketDrop || packet->pts == AV_NOPTS_VALUE) {
        return false;
    }
    if (isKeyPacket(packet)) {
        return false;
    }
    if (formatContext_ == nullptr || videoStreamIndex_ < 0 || formatContext_->streams[videoStreamIndex_] == nullptr) {
        return false;
    }

    const int64_t packetPtsUs = av_rescale_q(packet->pts, formatContext_->streams[videoStreamIndex_]->time_base, AV_TIME_BASE_Q);
    if (!isValidPts(packetPtsUs)) {
        return false;
    }

    int64_t masterClockUs = 0;
    SyncMaster effectiveMaster = SyncMaster::VIDEO;
    if (!resolveMasterClockUs(optionsSnapshot, packetPtsUs, masterClockUs, effectiveMaster)) {
        return false;
    }

    const int64_t delayUs = masterClockUs - packetPtsUs;
    if (delayUs <= 0) {
        return false;
    }
    updateVideoDelayStats(delayUs);
    if (delayUs <= optionsSnapshot.dropLatePacketThresholdUs) {
        return false;
    }

    droppedVideoPacketCount_.fetch_add(1);
    packetDropBeforeDecodeCount_.fetch_add(1);
    const int64_t nowMsValue = nowMs();
    if (nowMsValue - lastRealtimeDropLogMs_ > 1000) {
        LOGE("drop realtime video packet before decode delayUs=%lld ptsUs=%lld masterClockUs=%lld thresholdUs=%lld master=%s",
             static_cast<long long>(delayUs), static_cast<long long>(packetPtsUs),
             static_cast<long long>(masterClockUs),
             static_cast<long long>(optionsSnapshot.dropLatePacketThresholdUs),
             syncMasterName(effectiveMaster).c_str());
        lastRealtimeDropLogMs_ = nowMsValue;
    }
    return true;
}

bool NativePlayer::shouldDropRealtimeFrame(int64_t ptsUs) {
    PlayerOptions optionsSnapshot;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        optionsSnapshot = playerOptions_;
    }

    if (!isRealtimeInput_ || !optionsSnapshot.enableFrameDrop || !isValidPts(ptsUs)) {
        return false;
    }

    int64_t masterClockUs = 0;
    SyncMaster effectiveMaster = SyncMaster::VIDEO;
    if (!resolveMasterClockUs(optionsSnapshot, ptsUs, masterClockUs, effectiveMaster)) {
        return false;
    }

    const int64_t delayUs = masterClockUs - ptsUs;
    if (delayUs <= 0) {
        updateVideoDelayStats(0);
        return false;
    }
    updateVideoDelayStats(delayUs);
    if (delayUs <= optionsSnapshot.dropLateFrameThresholdUs) {
        return false;
    }

    droppedVideoFrameCount_.fetch_add(1);
    frameDropBeforeRenderCount_.fetch_add(1);
    const int64_t nowMsValue = nowMs();
    if (nowMsValue - lastRealtimeDropLogMs_ > 1000) {
        LOGE("drop realtime frame before render delayUs=%lld ptsUs=%lld masterClockUs=%lld thresholdUs=%lld master=%s",
             static_cast<long long>(delayUs), static_cast<long long>(ptsUs),
             static_cast<long long>(masterClockUs),
             static_cast<long long>(optionsSnapshot.dropLateFrameThresholdUs),
             syncMasterName(effectiveMaster).c_str());
        lastRealtimeDropLogMs_ = nowMsValue;
    }

    if (delayUs > keyFrameCatchupLatencyUs_) {
        dropUntilKeyFrame_ = true;
        if (videoCodecContext_ != nullptr && !optionsSnapshot.usingHardwareDecoder) {
            avcodec_flush_buffers(videoCodecContext_);
        }
        LOGE("realtime latency too high, skip packets until next keyframe delayUs=%lld",
             static_cast<long long>(delayUs));
    }
    return true;
}


bool NativePlayer::waitForReconnectDelay(int delayMs) {
    int remainingMs = std::max(delayMs, 0);
    while (remainingMs > 0 && !stopRequested_.load()) {
        const int stepMs = std::min(remainingMs, 100);
        std::this_thread::sleep_for(std::chrono::milliseconds(stepMs));
        remainingMs -= stepMs;
    }
    return !stopRequested_.load();
}

int NativePlayer::reconnectDelayForAttempt(int attempt) const {
    const int initialDelayMs = std::max(100, reconnectRetryDelayMs_.load());
    const int maxDelayMs = std::max(initialDelayMs, reconnectMaxDelayMs_.load());
    int64_t delayMs = initialDelayMs;
    for (int i = 1; i < attempt && delayMs < maxDelayMs; ++i) {
        delayMs = std::min<int64_t>(delayMs * 2, maxDelayMs);
    }
    return static_cast<int>(std::clamp<int64_t>(delayMs, 100, maxDelayMs));
}

bool NativePlayer::shouldTreatOpenErrorAsSourceMissing(const std::string &errorMessage) const {
    const bool is404 = containsInsensitive(errorMessage, "404")
                       || containsInsensitive(errorMessage, "not found");
    if (is404 && !reconnectOn404_.load()) {
        return false;
    }
    if (containsInsensitive(errorMessage, "end of file") && !reconnectOnEof_.load()) {
        return false;
    }
    return is404
           || containsInsensitive(errorMessage, "end of file")
           || containsInsensitive(errorMessage, "connection refused")
           || containsInsensitive(errorMessage, "timed out")
           || containsInsensitive(errorMessage, "network is unreachable");
}

void NativePlayer::syncReconnectPolicyFromOptionsLocked() {
    infiniteReconnect_.store(playerOptions_.infiniteReconnect);
    reconnectOnEof_.store(playerOptions_.reconnectOnEof);
    reconnectOn404_.store(playerOptions_.reconnectOn404);
    keepWaitingWhenSourceMissing_.store(playerOptions_.keepWaitingWhenSourceMissing);
    reconnectRetryDelayMs_.store(std::clamp(playerOptions_.reconnectInitialDelayMs, 100, 60000));
    reconnectMaxDelayMs_.store(std::clamp(playerOptions_.reconnectMaxDelayMs,
                                         reconnectRetryDelayMs_.load(),
                                         60000));
    reconnectMaxRetryCount_.store(playerOptions_.infiniteReconnect ? -1 : std::max(0, playerOptions_.reconnectMaxRetry));
}

void NativePlayer::notifyPlayerEvent(const std::string &eventName,
                                     PlayerState state,
                                     int64_t attempt,
                                     int maxRetry,
                                     int delayMs,
                                     int errorCode,
                                     const std::string &errorMessage) {
    bool attached = false;
    JNIEnv *env = getJniEnvForCurrentThread(attached);
    if (env == nullptr) {
        LOGE("notifyPlayerEvent failed: JNIEnv unavailable event=%s", eventName.c_str());
        return;
    }

    jobject listenerLocalRef = nullptr;
    {
        std::lock_guard<std::mutex> lock(eventListenerMutex_);
        if (playerEventListenerGlobalRef_ != nullptr) {
            listenerLocalRef = env->NewLocalRef(playerEventListenerGlobalRef_);
        }
    }
    if (listenerLocalRef == nullptr) {
        detachCurrentThreadIfNeeded(attached);
        return;
    }

    jclass listenerClass = env->GetObjectClass(listenerLocalRef);
    jmethodID method = listenerClass == nullptr
                       ? nullptr
                       : env->GetMethodID(listenerClass, "onPlayerEvent", "(JLjava/lang/String;Ljava/lang/String;)V");
    if (method == nullptr) {
        LOGE("notifyPlayerEvent failed: onPlayerEvent method not found");
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
        if (listenerClass != nullptr) {
            env->DeleteLocalRef(listenerClass);
        }
        env->DeleteLocalRef(listenerLocalRef);
        detachCurrentThreadIfNeeded(attached);
        return;
    }

    std::string url;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        url = url_;
    }
    std::ostringstream payload;
    payload << "{\"success\":true,"
            << "\"event\":\"" << escapeJson(eventName) << "\","
            << "\"playerState\":\"" << playerStateName(state) << "\","
            << "\"state\":\"" << stateName(state) << "\","
            << "\"handle\":" << static_cast<long long>(logicalHandle_) << ","
            << "\"url\":\"" << escapeJson(url) << "\","
            << "\"reconnecting\":" << (reconnecting_.load() ? "true" : "false") << ","
            << "\"waitingSource\":" << (waitingSource_.load() ? "true" : "false") << ","
            << "\"attempt\":" << attempt << ","
            << "\"maxRetry\":" << maxRetry << ","
            << "\"delayMs\":" << delayMs << ","
            << "\"errorCode\":" << errorCode << ","
            << "\"errorMessage\":\"" << escapeJson(errorMessage) << "\","
            << "\"lastDisconnectTimeMs\":" << lastDisconnectTimeMs_.load() << ","
            << "\"lastReconnectSuccessTimeMs\":" << lastReconnectSuccessTimeMs_.load()
            << "}";

    jstring eventString = env->NewStringUTF(eventName.c_str());
    jstring payloadString = env->NewStringUTF(payload.str().c_str());
    if (eventString != nullptr && payloadString != nullptr) {
        env->CallVoidMethod(listenerLocalRef,
                            method,
                            static_cast<jlong>(logicalHandle_),
                            eventString,
                            payloadString);
    }
    if (env->ExceptionCheck()) {
        LOGE("notifyPlayerEvent Java callback threw event=%s", eventName.c_str());
        env->ExceptionClear();
    }
    if (eventString != nullptr) {
        env->DeleteLocalRef(eventString);
    }
    if (payloadString != nullptr) {
        env->DeleteLocalRef(payloadString);
    }
    env->DeleteLocalRef(listenerClass);
    env->DeleteLocalRef(listenerLocalRef);
    detachCurrentThreadIfNeeded(attached);
}

bool NativePlayer::reconnectInput(int readErrorCode) {
    if (!reconnectEnabled_.load() || !isNetworkUrl(url_)) {
        return false;
    }

    if (readErrorCode == AVERROR_EOF && !reconnectOnEof_.load()) {
        return false;
    }

    const bool infiniteRetry = infiniteReconnect_.load() || reconnectMaxRetryCount_.load() < 0;
    const int maxRetryCount = reconnectMaxRetryCount_.load();
    if (!infiniteRetry && maxRetryCount <= 0) {
        return false;
    }

    reconnectExhausted_.store(false);
    const std::string readError = ffmpegErrorToString(readErrorCode);
    const int64_t disconnectTimeMs = nowMs();
    lastDisconnectTimeMs_.store(disconnectTimeMs);
    lastReconnectErrorCode_.store(readErrorCode);
    reconnecting_.store(true);
    waitingSource_.store(false);
    setRendererFallbackReason(rendererState_, 0);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
            state_ = PlayerState::Disconnected;
            errorMessage_ = readError;
            lastReconnectError_ = readError;
        }
    }
    LOGE("playback disconnected url=%s error=%s, start reconnect", url_.c_str(), readError.c_str());
    notifyPlayerEvent("reconnect_disconnected",
                      PlayerState::Disconnected,
                      0,
                      infiniteRetry ? -1 : maxRetryCount,
                      0,
                      readErrorCode,
                      readError);
    if (remuxRecorder_.isRecording()) {
        LOGI("reconnect while recorder active; remux recorder keeps output context and resumes when packets return");
    }

    // Isolate the old audio generation immediately at disconnect. Do not let
    // queued/AudioTrack PCM play throughout reconnect delay/open retries.
    flushAudioPcmForDiscontinuity();
    releaseFfmpegResources();
    resetRealtimeClock();

    int localAttempt = 0;
    while (!stopRequested_.load()) {
        ++localAttempt;
        const bool finiteRetryExhaustedBeforeAttempt = !infiniteRetry && localAttempt > maxRetryCount;
        if (finiteRetryExhaustedBeforeAttempt) {
            break;
        }

        const int retryDelayMs = reconnectDelayForAttempt(localAttempt);
        lastReconnectTimeMs_.store(nowMs());
        reconnectAttemptCount_.fetch_add(1);
        if (!waitingSource_.load()) {
            std::lock_guard<std::mutex> lock(mutex_);
            if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
                state_ = PlayerState::Reconnecting;
                errorMessage_ = readError;
            }
        }

        LOGI("reconnect attempt %d/%d delayMs=%d url=%s",
             localAttempt, infiniteRetry ? -1 : maxRetryCount, retryDelayMs, url_.c_str());
        notifyPlayerEvent("reconnecting",
                          PlayerState::Reconnecting,
                          localAttempt,
                          infiniteRetry ? -1 : maxRetryCount,
                          retryDelayMs,
                          readErrorCode,
                          readError);
        if (!waitForReconnectDelay(retryDelayMs)) {
            break;
        }

        waitingSource_.store(false);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
                state_ = PlayerState::Reconnecting;
            }
        }

        std::string error;
        const int result = openInput(url_, timeoutMs_, true, error);
        if (result >= 0) {
            reconnectSuccessCount_.fetch_add(1);
            reconnecting_.store(false);
            waitingSource_.store(false);
            reconnectExhausted_.store(false);
            lastReconnectErrorCode_.store(0);
            const int64_t successTimeMs = nowMs();
            lastReconnectSuccessTimeMs_.store(successTimeMs);
            {
                std::lock_guard<std::mutex> lock(mutex_);
                if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
                    state_ = pauseRequested_.load() ? PlayerState::Paused : PlayerState::Reconnected;
                    errorMessage_.clear();
                    lastReconnectError_.clear();
                }
            }
            resetRealtimeClock();
            if (audioEnabled_.load() && !pauseRequested_.load()) {
                startAudioSinkForCurrentGeneration();
                startAudioOutputWorker();
            }
            if (audioEnabled_.load() && sourceHasAudio_.load() && audioDecodeOpened_.load()) {
                audioReconnectRecoveryCount_.fetch_add(1);
            }
            recomputeAudioPlayable();
            beginStartupKeyFrameWait("reconnect");
            if (!startupKeyFrameWaitActive_.load()) {
                std::lock_guard<std::mutex> lock(mutex_);
                if (state_ == PlayerState::Reconnected && !pauseRequested_.load()) {
                    state_ = PlayerState::Playing;
                }
            }
            LOGI("RTSP open success url=%s", url_.c_str());
            LOGI("reconnect success attempt=%d url=%s", localAttempt, url_.c_str());
            notifyPlayerEvent("reconnect_success",
                              PlayerState::Reconnected,
                              localAttempt,
                              infiniteRetry ? -1 : maxRetryCount,
                              0,
                              0,
                              "");
            return true;
        }

        lastReconnectErrorCode_.store(result);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            lastReconnectError_ = error;
            errorMessage_ = error;
        }
        LOGE("reconnect failed attempt=%d/%d url=%s error=%s",
             localAttempt, infiniteRetry ? -1 : maxRetryCount, url_.c_str(), error.c_str());

        const bool sourceMissing = shouldTreatOpenErrorAsSourceMissing(error);
        if (sourceMissing && keepWaitingWhenSourceMissing_.load()) {
            waitingSource_.store(true);
            {
                std::lock_guard<std::mutex> lock(mutex_);
                if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
                    state_ = PlayerState::WaitingSource;
                    lastReconnectError_ = error;
                    errorMessage_ = error;
                }
            }
            LOGI("playerState=WAITING_SOURCE reconnect attempt=%d lastError=%s", localAttempt, error.c_str());
            LOGI("keep waiting for source url=%s", url_.c_str());
            notifyPlayerEvent("waiting_source",
                              PlayerState::WaitingSource,
                              localAttempt,
                              infiniteRetry ? -1 : maxRetryCount,
                              retryDelayMs,
                              result,
                              error);
            continue;
        }

        if (!infiniteRetry && localAttempt >= maxRetryCount) {
            break;
        }
    }

    reconnecting_.store(false);
    waitingSource_.store(false);
    std::string finalError;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        finalError = lastReconnectError_.empty() ? readError : lastReconnectError_;
    }
    if (stopRequested_.load()) {
        return false;
    }
    reconnectExhausted_.store(true);
    setState(PlayerState::Error, finalError);
    LOGE("reconnect exhausted url=%s error=%s", url_.c_str(), finalError.c_str());
    notifyPlayerEvent("reconnect_exhausted",
                      PlayerState::Error,
                      reconnectAttemptCount_.load(),
                      infiniteRetry ? -1 : maxRetryCount,
                      0,
                      lastReconnectErrorCode_.load(),
                      finalError);
    return false;
}

bool NativePlayer::switchTransportInput() {
    std::string currentUrl;
    int currentTimeoutMs = 0;
    const bool paused = pauseRequested_.load();
    std::string mode;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        currentUrl = url_;
        currentTimeoutMs = timeoutMs_;
        mode = rtspTransportMode_;
        if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
            state_ = PlayerState::Reconnecting;
            errorMessage_.clear();
        }
    }

    LOGI("switch RTSP transport mode=%s url=%s", mode.c_str(), currentUrl.c_str());
    // Transport switch is also a source discontinuity: isolate audio before
    // closing the old input rather than after the new input has opened.
    flushAudioPcmForDiscontinuity();
    releaseFfmpegResources();
    clearLastFrame();

    std::string error;
    const int result = openInput(currentUrl, currentTimeoutMs, true, error);
    if (result < 0) {
        setState(PlayerState::Error, error);
        LOGE("switch RTSP transport failed mode=%s error=%s", mode.c_str(), error.c_str());
        return false;
    }

    resetRealtimeClock();
    if (audioEnabled_.load() && !paused) {
        startAudioSinkForCurrentGeneration();
        startAudioOutputWorker();
    }
    recomputeAudioPlayable();
    beginStartupKeyFrameWait("transport_switch");
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (state_ != PlayerState::Released && state_ != PlayerState::Stopping) {
            state_ = paused ? PlayerState::Paused : PlayerState::Playing;
            errorMessage_.clear();
        }
    }
    LOGI("switch RTSP transport success mode=%s current=%s", mode.c_str(), preferUdpTransport_.load() ? "udp" : "tcp");
    return true;
}

void NativePlayer::playbackLoop() {
    LOGI("playback thread started player=%p", this);
    const bool realtimeInput = isRealtimeInput_;
    const int frameDelayMs = realtimeInput ? 0 : static_cast<int>(std::clamp(1000.0 / std::max(fps_, 1.0), 5.0, 100.0));
    LOGI("playback pacing realtimeInput=%d fps=%.2f frameDelayMs=%d", realtimeInput ? 1 : 0, fps_, frameDelayMs);
    int64_t sessionReadPacketCount = 0;

    while (!stopRequested_.load()) {
        if (transportSwitchRequested_.exchange(false)) {
            if (!switchTransportInput()) {
                break;
            }
            sessionReadPacketCount = 0;
            continue;
        }

        if (audioResumeDiscontinuityRequested_.exchange(false)) {
            // Realtime Pause keeps draining compressed packets. Resume flushes
            // decoder reference state and waits for a fresh video keyframe;
            // audio starts from the current packet edge with a new generation.
            resetAudioDecoderForDiscontinuity("resume");
            audioFlushRequested_.store(false);
            resetRealtimeClock();
            if (isRealtimeInput_) {
                if (videoCodecContext_ != nullptr) {
                    avcodec_flush_buffers(videoCodecContext_);
                }
                beginStartupKeyFrameWait("resume");
            }
        }

        if (audioFlushRequested_.exchange(false)) {
            resetAudioDecoderForDiscontinuity("audio_toggle");
        }

        if (pauseRequested_.load() && !realtimeInput) {
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
            continue;
        }

        const int64_t readStartUs = steadyNowUs();
        const int readResult = av_read_frame(formatContext_, packet_);
        const int64_t readCostUs = steadyNowUs() - readStartUs;
        recordCost(lastReadFrameCostUs_, totalReadFrameCostUs_, readFrameCostSampleCount_, maxReadFrameCostUs_,
                   readCostUs);
        const PreT0TimingTracker::ReadResultClass readClass = classifyReadResult(readResult);
        // LATENCY aggregates duration; BASIC keeps outcome-only health; OFF
        // is a no-op. The facade owns all PRET0 policy and bounded storage.
        diagnostics_.onRead(readCostUs, readClass);
        lastReadPacketTimeMs_.store(nowMs());
        if (readResult < 0) {
            if (transportSwitchRequested_.exchange(false)) {
                if (!switchTransportInput()) {
                    break;
                }
                sessionReadPacketCount = 0;
                continue;
            }

            const bool shouldReconnectEof = readResult == AVERROR_EOF
                                            && reconnectEnabled_.load()
                                            && reconnectOnEof_.load()
                                            && isNetworkUrl(url_);
            if (stopRequested_.load() || (readResult == AVERROR_EOF && !shouldReconnectEof)) {
                break;
            }
            const std::string error = ffmpegErrorToString(readResult);
            LOGE("av_read_frame error: %s", error.c_str());
            std::string transportMode;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                transportMode = rtspTransportMode_;
            }
            if (transportMode == "auto" && readResult == AVERROR_EOF
                && sessionReadPacketCount <= 1 && !preferUdpTransport_.load()) {
                preferUdpTransport_.store(true);
                LOGE("RTSP TCP ended after %lld packets; fallback to UDP transport",
                     static_cast<long long>(sessionReadPacketCount));
            }
            if (reconnectInput(readResult)) {
                sessionReadPacketCount = 0;
                continue;
            }
            if (!stopRequested_.load()) {
                setState(PlayerState::Error, error);
            }
            break;
        }

        readPacketCount_.fetch_add(1);
        const int packetSize = std::max(packet_->size, 0);
        inputPacketBytes_.fetch_add(packetSize);
        ++sessionReadPacketCount;
        if (packet_->stream_index == videoStreamIndex_) {
            videoPacketCount_.fetch_add(1);
            videoPacketBytes_.fetch_add(packetSize);
            const bool basicDiagnostics = diagnostics_.basicEnabled();
            const bool latencyDiagnostics = diagnostics_.latencyEnabled();
            const int64_t packetReadyMonoUs = basicDiagnostics ? steadyNowUs() : -1;
            if (latencyDiagnostics) {
                // LAT6 bridge and RTCP-SR mapping are diagnostic-only and are
                // deliberately absent from the production BASIC hot path.
                const int64_t packetReadyWallNs = wallClockNs();
                lastPacketReadyWallNs_.store(packetReadyWallNs);
                processRtcpTimebase(packetReadyWallNs);
            }
            int64_t packetPtsUsForPreT0 = -1;
            // LAT1 P0: video packet demuxed and identified (media timeline us).
            if (basicDiagnostics && formatContext_ != nullptr && videoStreamIndex_ >= 0) {
                bool packetPtsValid = false;
                if (packet_->pts != AV_NOPTS_VALUE) {
                    const int64_t packetPtsUs = rescaleToUs(packet_->pts, formatContext_->streams[videoStreamIndex_]->time_base);
                    if (isValidPts(packetPtsUs)) {
                        latestVideoPacketPtsUs_.store(packetPtsUs);
                        packetPtsValid = true;
                        packetPtsUsForPreT0 = packetPtsUs;
                        updateMax(maxVideoPacketPtsUs_, packetPtsUs);
                        if (packetPtsUs < maxVideoPacketPtsUs_.load()) {
                            videoPtsBackwardCount_.fetch_add(1);
                        }
                        // LAT2 T0: demux return for this video packet (monotonic).
                        recordVideoStageTiming(videoPtsGeneration_.load(), packetPtsUs,
                                               StageTimingPoint::PacketReady, packetReadyMonoUs);
                    }
                }
                videoPacketPtsValid_.store(packetPtsValid);
                if (!packetPtsValid) {
                    latestVideoPacketPtsUs_.store(-1);
                }
            }
            // LAT5: video packet return gap / PTS delta / burst detection.
            // monoUs is T0 (R1); invalid PTS never fabricates a delta.
            if (latencyDiagnostics) {
                diagnostics_.onVideoPacketReturn(packetReadyMonoUs, packetPtsUsForPreT0);
            }
        } else if (packet_->stream_index == audioStreamIndex_) {
            audioPacketCount_.fetch_add(1);
            audioPacketBytes_.fetch_add(packetSize);
            lastAudioFrameTimeMs_.store(nowMs());
            if (formatContext_ != nullptr && audioStreamIndex_ >= 0 && packet_->pts != AV_NOPTS_VALUE) {
                audioClockUs_.store(av_rescale_q(packet_->pts, formatContext_->streams[audioStreamIndex_]->time_base, AV_TIME_BASE_Q));
            }
        }

        if (remuxRecorder_.isRecording()) {
            remuxRecorder_.onPacket(packet_, formatContext_);
        }

        if (pauseRequested_.load() || audioResumeDiscontinuityRequested_.load()) {
            // Realtime Pause deliberately keeps the demux/socket at live edge
            // and preserves the recorder's compressed packet path, but neither
            // decoder nor renderer receives paused packets.
            av_packet_unref(packet_);
            continue;
        }

        if (shouldDropRealtimePacket(packet_)) {
            av_packet_unref(packet_);
            continue;
        }

        if (isRealtimeInput_ && dropUntilKeyFrame_) {
            const int64_t waitElapsedMs = startupKeyFrameWait_ && startupKeyFrameWaitStartMs_ > 0
                                          ? nowMs() - startupKeyFrameWaitStartMs_
                                          : 0;
            if (startupKeyFrameWait_ && waitElapsedMs > kStartupKeyFrameWaitTimeoutMs) {
                LOGE("first video keyframe wait timeout elapsedMs=%lld, allow decode from stream=%d key=%d",
                     static_cast<long long>(waitElapsedMs), packet_->stream_index,
                     (packet_->flags & AV_PKT_FLAG_KEY) ? 1 : 0);
                finishStartupKeyFrameWait("timeout");
            }
        }

        if (isRealtimeInput_ && dropUntilKeyFrame_) {
            if (packet_->stream_index == videoStreamIndex_) {
                if ((packet_->flags & AV_PKT_FLAG_KEY) == 0) {
                    droppedVideoPacketCount_.fetch_add(1);
                    packetDropBeforeDecodeCount_.fetch_add(1);
                    if (startupKeyFrameWait_) {
                        startupKeyFrameDroppedPacketCount_.fetch_add(1);
                    }
                    av_packet_unref(packet_);
                    continue;
                }
                LOGI("realtime keyframe received, resume decode pts=%lld startupWait=%d",
                     static_cast<long long>(packet_->pts), startupKeyFrameWait_ ? 1 : 0);
                LOGI("first keyframe received pts=%lld", static_cast<long long>(packet_->pts));
                finishStartupKeyFrameWait(startupKeyFrameWait_ ? "keyframe" : "catchup");
            } else {
                av_packet_unref(packet_);
                continue;
            }
        }

        if (packet_->stream_index == videoStreamIndex_) {
            const int64_t sendStartUs = steadyNowUs();
            int result = avcodec_send_packet(videoCodecContext_, packet_);
            lastSendPacketCostUs_.store(steadyNowUs() - sendStartUs);
            if (result < 0) {
                const std::string error = ffmpegErrorToString(result);
                LOGE("avcodec_send_packet error: %s", error.c_str());
                av_packet_unref(packet_);
                continue;
            }
            // LAT1 P1: packet accepted by the decoder (same packet PTS, media timeline us).
            if (diagnostics_.basicEnabled() && videoPacketPtsValid_.load()) {
                const int64_t inputPtsUs = latestVideoPacketPtsUs_.load();
                latestDecoderInputPtsUs_.store(inputPtsUs);
                decoderInputPtsValid_.store(true);
                updateMax(maxDecoderInputPtsUs_, inputPtsUs);
                if (inputPtsUs < maxDecoderInputPtsUs_.load()) {
                    decoderPtsBackwardCount_.fetch_add(1);
                }
                // LAT2 T1: packet submitted to decoder (monotonic, same packet PTS).
                if (diagnostics_.latencyEnabled()) {
                    recordVideoStageTiming(videoPtsGeneration_.load(), inputPtsUs,
                                           StageTimingPoint::DecoderSubmit, sendStartUs);
                }
            } else if (diagnostics_.basicEnabled()) {
                latestDecoderInputPtsUs_.store(-1);
                decoderInputPtsValid_.store(false);
            }

            PlayerOptions optionsSnapshot;
            {
                std::lock_guard<std::mutex> lock(mutex_);
                optionsSnapshot = playerOptions_;
            }
            const bool latestFrameOnly = isRealtimeInput_ && optionsSnapshot.enableLatestFrameOnly && latestFrame_ != nullptr;
            bool hasLatestFrame = false;

            auto processDecodedVideoFrame = [&](AVFrame *frame, int64_t frameProcessStartUs) {
                if (renderFrame(frame)) {
                    recordCost(lastFrameProcessCostUs_, totalFrameProcessCostUs_, frameProcessCostSampleCount_, maxFrameProcessCostUs_,
                               steadyNowUs() - frameProcessStartUs);
                    videoFrameCount_.fetch_add(1);
                }
            };

            while (!stopRequested_.load()) {
                const int64_t receiveStartUs = steadyNowUs();
                result = avcodec_receive_frame(videoCodecContext_, decodedFrame_);
                const int64_t receiveCostUs = steadyNowUs() - receiveStartUs;
                lastReceiveFrameCostUs_.store(receiveCostUs);
                if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) {
                    break;
                }
                if (result < 0) {
                    const std::string error = ffmpegErrorToString(result);
                    LOGE("avcodec_receive_frame error: %s", error.c_str());
                    break;
                }
                recordCost(lastReceiveFrameCostUs_, totalDecodeCostUs_, decodeCostSampleCount_, maxDecodeCostUs_, receiveCostUs);

                // LAT1 P2: decoded frame output from the decoder (media timeline us).
                if (diagnostics_.basicEnabled() && formatContext_ != nullptr && videoStreamIndex_ >= 0) {
                    bool framePtsValid = false;
                    if (decodedFrame_->best_effort_timestamp != AV_NOPTS_VALUE) {
                        const int64_t framePtsUs = rescaleToUs(decodedFrame_->best_effort_timestamp, formatContext_->streams[videoStreamIndex_]->time_base);
                        if (isValidPts(framePtsUs)) {
                            latestDecodedFramePtsUs_.store(framePtsUs);
                            framePtsValid = true;
                            updateMax(maxDecodedFramePtsUs_, framePtsUs);
                            if (framePtsUs < maxDecodedFramePtsUs_.load()) {
                                decodedPtsBackwardCount_.fetch_add(1);
                            }
                            // LAT2 T2: decoded frame output (monotonic).
                            if (diagnostics_.latencyEnabled()) {
                                recordVideoStageTiming(videoPtsGeneration_.load(), framePtsUs,
                                                       StageTimingPoint::DecodedOutput, steadyNowUs());
                            }
                        }
                    }
                    decodedFramePtsValid_.store(framePtsValid);
                    if (!framePtsValid) {
                        latestDecodedFramePtsUs_.store(-1);
                    }
                }

                if (latestFrameOnly && decodedFrame_->format != AV_PIX_FMT_MEDIACODEC) {
                    if (hasLatestFrame) {
                        droppedVideoFrameCount_.fetch_add(1);
                        frameDropBeforeRenderCount_.fetch_add(1);
                    }
                    av_frame_unref(latestFrame_);
                    av_frame_move_ref(latestFrame_, decodedFrame_);
                    hasLatestFrame = true;
                    continue;
                }

                processDecodedVideoFrame(decodedFrame_, receiveStartUs);
                av_frame_unref(decodedFrame_);
                if (frameDelayMs > 0) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(frameDelayMs));
                }
            }

            if (latestFrameOnly) {
                if (hasLatestFrame && !stopRequested_.load()) {
                    processDecodedVideoFrame(latestFrame_, steadyNowUs());
                }
                av_frame_unref(latestFrame_);
            }
        } else if (packet_->stream_index == audioStreamIndex_) {
            // A1: decode compressed audio packets into decoded Audio AVFrames
            // on the playback thread, then discard them (no PCM yet). The
            // recorder already received the original packet earlier in the loop.
            decodeAudioPacket(packet_);
        }

        renderOesPendingFrameIfReady();

        av_packet_unref(packet_);
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (state_ != PlayerState::Error && state_ != PlayerState::Released) {
        state_ = PlayerState::Stopped;
    }
    LOGI("playback thread ended player=%p", this);
}

void NativePlayer::notifyOesFrameAvailable() {
    oesFramePending_.store(true);
    oesFrameAvailableCount_.fetch_add(1);
}

void NativePlayer::renderOesPendingFrameIfReady() {
    if (!oesRenderer_.isPrepared() || !oesFramePending_.load()) {
        return;
    }
    PlayerOptions snapshot;
    int frameWidth = 0;
    int frameHeight = 0;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        snapshot = playerOptions_;
        frameWidth = videoWidth_;
        frameHeight = videoHeight_;
    }
    if (snapshot.renderMode != RenderMode::MEDIACODEC_OES) {
        oesFramePending_.store(false);
        return;
    }
    if (!oesRenderer_.hasSurface()) {
        oesFramePending_.store(false);
        return;
    }
    if (!oesFramePending_.exchange(false)) {
        return;
    }
    bool attached = false;
    JNIEnv *env = getJniEnvForCurrentThread(attached);
    if (env == nullptr) {
        oesFramePending_.store(true);
        return;
    }

    const ThermalConfig thermal = getThermalConfig();
    int oesThermalMode = 0;  // original
    if (thermal.enabled) {
        if (thermal.palette == ThermalPaletteMode::WHITE_HOT) {
            oesThermalMode = 1;
        } else if (thermal.palette == ThermalPaletteMode::IRONBOW) {
            oesThermalMode = 2;
        }
    }
    const bool agcEnabled = thermal.enabled && thermal.agcEnabled && oesThermalMode != 0;
    bool runAgc = false;
    if (agcEnabled) {
        const int frame = oesAgcFrameCounter_.fetch_add(1) + 1;
        if (frame >= kOesAgcUpdateIntervalFrames) {
            oesAgcFrameCounter_.store(0);
            runAgc = true;
        }
    }

    if (oesRenderer_.renderOesFrame(env, frameWidth, frameHeight, oesThermalMode,
                                    thermal.gamma, thermal.blackPoint, thermal.whitePoint,
                                    agcEnabled, runAgc)) {
        oesFrameRenderedCount_.fetch_add(1);
        renderedFrameCount_.fetch_add(1);
        lastOesThermalRenderMode_.store(oesThermalMode);
        commitRendererSuccess(rendererState_, 4, false);  // oes_gl
        if (oesThermalMode != 0) {
            oesThermalRenderedCount_.fetch_add(1);
        }
        markFrameRendered();
    } else {
        const int64_t fails = oesRenderFailCount_.fetch_add(1) + 1;
        if (fails == 1 || fails % 100 == 0) {
            LOGE("OES render frame failed count=%lld updateTexImageErrors=%lld contextRecreates=%lld",
                 static_cast<long long>(fails),
                 static_cast<long long>(oesRenderer_.getUpdateTexImageErrorCount()),
                 static_cast<long long>(oesRenderer_.getContextRecreateCount()));
        }
    }
    detachCurrentThreadIfNeeded(attached);
}

bool NativePlayer::renderMediaCodecFrame(AVFrame *frame, int64_t ptsUs) {
    if (frame == nullptr) {
        return false;
    }

    hardwareDecodedFrameCount_.fetch_add(1);
    lastSwsScaleCostUs_.store(-1);
    lastRenderLockCostUs_.store(-1);
    lastRenderCopyCostUs_.store(-1);
    lastRenderPostCostUs_.store(-1);
    {
        std::lock_guard<std::mutex> lock(mutex_);
        lastFrameFormatName_ = "mediacodec";
    }

    AVMediaCodecBuffer *buffer = reinterpret_cast<AVMediaCodecBuffer *>(frame->data[3]);
    if (buffer == nullptr) {
        const std::string error = "AV_PIX_FMT_MEDIACODEC frame has null buffer";
        LOGE("%s", error.c_str());
        std::lock_guard<std::mutex> lock(mutex_);
        playerOptions_.hardwareDecodeError = error;
        return true;
    }

    const bool drop = shouldDropRealtimeFrame(ptsUs);
    if (!drop) {
        // Video follows audio: wait (bounded) if this frame is ahead of the
        // audible clock before releasing it to the surface.
        waitForAudioMasterIfEarly(ptsUs);
    }
    const int64_t releaseStartUs = steadyNowUs();
    const int releaseResult = av_mediacodec_release_buffer(buffer, drop ? 0 : 1);
    const int64_t releaseCostUs = steadyNowUs() - releaseStartUs;
    lastRenderCostUs_.store(drop ? -1 : releaseCostUs);
    if (!drop && releaseCostUs > 0) {
        totalRenderCostUs_.fetch_add(releaseCostUs);
        renderCostSampleCount_.fetch_add(1);
        updateMax(maxRenderCostUs_, releaseCostUs);
    }
    if (releaseResult < 0) {
        const std::string error = "av_mediacodec_release_buffer failed: " + ffmpegErrorToString(releaseResult);
        LOGE("%s", error.c_str());
        if (!drop) {
            droppedVideoFrameCount_.fetch_add(1);
        }
        hardwareDroppedFrameCount_.fetch_add(1);
        std::lock_guard<std::mutex> lock(mutex_);
        playerOptions_.hardwareDecodeError = error;
        return true;
    }

    if (drop) {
        const int64_t dropped = hardwareDroppedFrameCount_.fetch_add(1) + 1;
        if (dropped == 1 || dropped % 100 == 0) {
            LOGI("release mediacodec buffer render=0 drop reason=late count=%lld",
                 static_cast<long long>(dropped));
        }
        return true;
    }

    bool oesRenderMode = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        oesRenderMode = playerOptions_.renderMode == RenderMode::MEDIACODEC_OES;
    }
    if (oesRenderMode) {
        // Releasing the decoder buffer only publishes it to SurfaceTexture;
        // the user-visible output is committed by renderOesPendingFrameIfReady().
        return true;
    }

    renderedFrameCount_.fetch_add(1);
    hardwareRenderedFrameCount_.fetch_add(1);
    commitRendererSuccess(rendererState_, 5, false);  // direct_surface
    markFrameRendered();
    recordStageTimingRenderSubmit(ptsUs);
    return true;
}

bool NativePlayer::isSoftwareYuvGlFrameSupported(int frameFormat) const {
    return frameFormat == AV_PIX_FMT_YUV420P || frameFormat == AV_PIX_FMT_YUVJ420P;
}

void NativePlayer::updateAgcState(AVFrame *frame, const ThermalConfig &thermal) {
    if (!thermal.agcEnabled || frame == nullptr) {
        return;
    }
    const int frameCount = agcFrameCounter_.fetch_add(1) + 1;
    if (frameCount < kAgcUpdateIntervalFrames) {
        return;
    }
    agcFrameCounter_.store(0);
    const AgcResult detected = computeAgcWindow(frame->data[0], frame->linesize[0],
                                                frame->width, frame->height,
                                                static_cast<AVColorRange>(frame->color_range));
    if (!detected.valid) {
        return;
    }
    if (!agcValid_.load()) {
        agcBlackPoint_.store(detected.blackPoint);
        agcWhitePoint_.store(detected.whitePoint);
        agcValid_.store(true);
        LOGI("AGC initialized blackPoint=%.3f whitePoint=%.3f", detected.blackPoint, detected.whitePoint);
    } else {
        const float oldBlack = agcBlackPoint_.load();
        const float oldWhite = agcWhitePoint_.load();
        agcBlackPoint_.store(oldBlack * (1.0f - kAgcSmoothingAlpha) + detected.blackPoint * kAgcSmoothingAlpha);
        agcWhitePoint_.store(oldWhite * (1.0f - kAgcSmoothingAlpha) + detected.whitePoint * kAgcSmoothingAlpha);
    }
    agcUpdateCount_.fetch_add(1);
}

bool NativePlayer::renderNv12GlFrame(AVFrame *frame, int frameWidth, int frameHeight, int64_t ptsUs) {
    if (frame == nullptr || frame->data[0] == nullptr || frame->data[1] == nullptr
        || frame->linesize[0] <= 0 || frame->linesize[1] <= 0) {
        return false;
    }

    const ThermalConfig thermal = getThermalConfig();
    int nv12ThermalMode = 0;  // original
    if (thermal.enabled) {
        if (thermal.palette == ThermalPaletteMode::WHITE_HOT) {
            nv12ThermalMode = 1;
        } else if (thermal.palette == ThermalPaletteMode::IRONBOW) {
            nv12ThermalMode = 2;
        }
    }
    // NV12 AGC: analyze the CPU-visible Y plane (data[0]) with the shared Phase 1
    // luma8 helper (4x4 sampling, 256-bin histogram, P2/P98, range normalize).
    if (frameWidth != nv12AgcLastFrameWidth_.load() || frameHeight != nv12AgcLastFrameHeight_.load()) {
        // Resolution change / new stream: drop stale AGC validity for a fresh scene.
        nv12AgcValid_.store(false);
        nv12AgcFrameCounter_.store(0);
        nv12AgcLastFrameWidth_.store(frameWidth);
        nv12AgcLastFrameHeight_.store(frameHeight);
    }
    float effectiveBlack = thermal.blackPoint;
    float effectiveWhite = thermal.whitePoint;
    if (nv12ThermalMode != 0 && thermal.agcEnabled) {
        const int frameCount = nv12AgcFrameCounter_.fetch_add(1) + 1;
        if (frameCount >= kAgcUpdateIntervalFrames) {
            nv12AgcFrameCounter_.store(0);
            const AgcResult detected = computeAgcWindow(frame->data[0], frame->linesize[0],
                                                        frameWidth, frameHeight,
                                                        static_cast<AVColorRange>(frame->color_range));
            if (detected.valid) {
                if (!nv12AgcValid_.load()) {
                    nv12AgcBlackPoint_.store(detected.blackPoint);
                    nv12AgcWhitePoint_.store(detected.whitePoint);
                    nv12AgcValid_.store(true);
                } else {
                    const float oldBlack = nv12AgcBlackPoint_.load();
                    const float oldWhite = nv12AgcWhitePoint_.load();
                    nv12AgcBlackPoint_.store(oldBlack * (1.0f - kAgcSmoothingAlpha) + detected.blackPoint * kAgcSmoothingAlpha);
                    nv12AgcWhitePoint_.store(oldWhite * (1.0f - kAgcSmoothingAlpha) + detected.whitePoint * kAgcSmoothingAlpha);
                }
                nv12AgcUpdateCount_.fetch_add(1);
            } else {
                nv12AgcInvalidCount_.fetch_add(1);
            }
        }
        if (nv12AgcValid_.load()) {
            effectiveBlack = nv12AgcBlackPoint_.load();
            effectiveWhite = nv12AgcWhitePoint_.load();
        }
    }

    const RenderResult result = nv12GlRenderer_.renderNv12(frame->data[0], frame->linesize[0],
                                                           frame->data[1], frame->linesize[1],
                                                           frameWidth, frameHeight,
                                                           static_cast<int>(frame->color_range),
                                                           static_cast<int>(frame->colorspace),
                                                           nv12ThermalMode, thermal.gamma,
                                                           effectiveBlack, effectiveWhite);
    if (result.success) {
        commitRendererSuccess(rendererState_, 3, false);  // nv12_gl
        // sws_scale is not used on the NV12 GL path: explicit disabled sentinel.
        lastSwsScaleCostUs_.store(-1);
        // Report the mode actually applied by the renderer (e.g. ironbow -> white hot fallback).
        lastNv12ThermalRenderMode_.store(nv12GlRenderer_.getLastAppliedThermalMode());
        recordCost(nv12GlLastUploadCostUs_, nv12GlTotalUploadCostUs_, nv12GlUploadCostSampleCount_,
                   nv12GlMaxUploadCostUs_, std::max<int64_t>(0, result.stats.copyCostUs));
        recordCost(nv12GlLastRenderCostUs_, nv12GlTotalRenderCostUs_, nv12GlRenderCostSampleCount_,
                   nv12GlMaxRenderCostUs_, std::max<int64_t>(0, result.stats.totalCostUs));
        nv12GlRenderedFrameCount_.fetch_add(1);
        renderedFrameCount_.fetch_add(1);
        if (nv12ThermalMode != 0) {
            nv12ThermalRenderedCount_.fetch_add(1);
        }
        recordCost(lastRenderCostUs_, totalRenderCostUs_, renderCostSampleCount_, maxRenderCostUs_,
                   std::max<int64_t>(0, result.stats.totalCostUs));
        markFrameRendered();
        return true;
    }
    if (result.errorCode == kRenderErrorNoSurface) {
        nv12GlNoSurfaceFrameCount_.fetch_add(1);
        setRendererFallbackReason(rendererState_, 0);
        lastSwsScaleCostUs_.store(-1);
        lastRenderCostUs_.store(-1);
        return true;
    }
    setRendererFallbackReason(rendererState_, 1);
    return false;
}

bool NativePlayer::renderSoftwareYuvGlFrame(AVFrame *frame, int frameWidth, int frameHeight) {
    if (frame == nullptr || !isSoftwareYuvGlFrameSupported(frame->format)) {
        return false;
    }

    const ThermalConfig thermal = getThermalConfig();
    int thermalMode = 0;  // normal
    if (thermal.enabled) {
        if (thermal.palette == ThermalPaletteMode::WHITE_HOT) {
            thermalMode = 1;
        } else if (thermal.palette == ThermalPaletteMode::IRONBOW) {
            thermalMode = 2;
        }
    }
    ThermalRenderParams renderParams;
    renderParams.gamma = thermal.gamma;
    renderParams.blackPoint = thermal.blackPoint;
    renderParams.whitePoint = thermal.whitePoint;
    if (frame->color_range == AVCOL_RANGE_MPEG) {
        renderParams.yMin = 16.0f / 255.0f;
        renderParams.yScale = 255.0f / 219.0f;
    }
    if (thermalMode == 1 || thermalMode == 2) {
        updateAgcState(frame, thermal);
        if (thermal.agcEnabled && agcValid_.load()) {
            renderParams.blackPoint = agcBlackPoint_.load();
            renderParams.whitePoint = agcWhitePoint_.load();
        }
    }

    const RenderResult result = yuvGlRenderer_.renderI420(frame->data[0], frame->linesize[0],
                                                          frame->data[1], frame->linesize[1],
                                                          frame->data[2], frame->linesize[2],
                                                          frameWidth, frameHeight, thermalMode, renderParams);
    lastSwsScaleCostUs_.store(-1);
    lastRenderCostUs_.store(result.stats.totalCostUs);
    lastRenderLockCostUs_.store(-1);
    lastRenderCopyCostUs_.store(result.stats.copyCostUs);
    lastRenderPostCostUs_.store(result.stats.postCostUs);
    if (result.stats.totalCostUs > 0) {
        totalRenderCostUs_.fetch_add(result.stats.totalCostUs);
        renderCostSampleCount_.fetch_add(1);
        updateMax(maxRenderCostUs_, result.stats.totalCostUs);
    }
    if (!result.success) {
        if (result.errorCode == kRenderErrorNoSurface) {
            yuvGlNoSurfaceFrameCount_.fetch_add(1);
            setRendererFallbackReason(rendererState_, 0);
            lastRenderCostUs_.store(-1);
            return true;
        }
        return false;
    }

    renderedFrameCount_.fetch_add(1);
    softwareRenderedFrameCount_.fetch_add(1);
    yuvGlRenderedFrameCount_.fetch_add(1);
    lastThermalRenderMode_.store(thermalMode);
    commitRendererSuccess(rendererState_, 2, false);  // yuv_gl
    if (thermalMode == 1) {
        whiteHotRenderedFrameCount_.fetch_add(1);
    } else if (thermalMode == 2) {
        ironbowRenderedFrameCount_.fetch_add(1);
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        lastFrameFormatName_ = "yuv420p_gl";
    }
    markFrameRendered();
    return true;
}

bool NativePlayer::renderFrame(AVFrame *frame) {
    if (frame == nullptr || videoCodecContext_ == nullptr) {
        return false;
    }

    const int frameWidth = frame->width > 0 ? frame->width : videoCodecContext_->width;
    const int frameHeight = frame->height > 0 ? frame->height : videoCodecContext_->height;
    const auto sourceFormat = static_cast<AVPixelFormat>(frame->format);

    if (frameWidth <= 0 || frameHeight <= 0 || sourceFormat == AV_PIX_FMT_NONE) {
        LOGE("invalid decoded frame width=%d height=%d format=%d", frameWidth, frameHeight, frame->format);
        return false;
    }

    const bool formatDiscontinuity = commitDecodedVideoFormatIfChanged(
            frameWidth, frameHeight, frame->format,
            frame->linesize[0] > 0 ? frame->linesize[0] : 0,
            frame->color_range);
    if (formatDiscontinuity) {
        // A valid new-generation frame is explicit evidence of a source-format
        // discontinuity. Re-anchor before late-frame/drop decisions so the old
        // source timeline cannot force the new frame into catch-up mode.
        resetRealtimeClockForFormatDiscontinuity();
    }

    int64_t ptsUs = AV_NOPTS_VALUE;
    if (formatContext_ != nullptr && videoStreamIndex_ >= 0 && frame->best_effort_timestamp != AV_NOPTS_VALUE) {
        ptsUs = av_rescale_q(frame->best_effort_timestamp, formatContext_->streams[videoStreamIndex_]->time_base, AV_TIME_BASE_Q);
    }
    if (isValidPts(ptsUs)) {
        // Playback clock is correctness state and is never gated by diagnostics.
        videoClockUs_.store(ptsUs);
        if (diagnostics_.basicEnabled()) {
            // LAT1 P3: frame committed to the render path (media timeline us).
            latestRenderedFramePtsUs_.store(ptsUs);
            renderedFramePtsValid_.store(true);
            updateMax(maxRenderedFramePtsUs_, ptsUs);
            if (ptsUs < maxRenderedFramePtsUs_.load()) {
                renderedPtsBackwardCount_.fetch_add(1);
            }
        }
    } else if (diagnostics_.basicEnabled()) {
        // LAT1 P3: no valid media PTS for this rendered frame.
        latestRenderedFramePtsUs_.store(-1);
        renderedFramePtsValid_.store(false);
    }
    // LAT2 T3: render begin (frame enters the render mainline, monotonic).
    if (diagnostics_.latencyEnabled() && isValidPts(ptsUs)) {
        recordVideoStageTiming(videoPtsGeneration_.load(), ptsUs,
                               StageTimingPoint::RenderBegin, steadyNowUs());
    }
    lastVideoFrameTimeMs_.store(nowMs());
    if (sourceFormat == AV_PIX_FMT_MEDIACODEC) {
        return renderMediaCodecFrame(frame, ptsUs);
    }

    bool hardwareBackend = false;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        // The decoder backend, not the requested render mode, decides the counter.
        hardwareBackend = playerOptions_.usingHardwareDecoder;
    }
    if (hardwareBackend) {
        hardwareDecodedFrameCount_.fetch_add(1);
    } else {
        softwareDecodedFrameCount_.fetch_add(1);
    }
    if (shouldDropRealtimeFrame(ptsUs)) {
        return true;
    }

    // Video follows audio: wait (bounded) if this frame is ahead of the audible
    // clock before rendering it.
    waitForAudioMasterIfEarly(ptsUs);

    PlayerOptions optionsSnapshot;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        optionsSnapshot = playerOptions_;
    }
    if (optionsSnapshot.renderMode == RenderMode::MEDIACODEC_NV12_GL
        && !nv12GlRenderer_.syncSurface()) {
        nv12GlNoSurfaceFrameCount_.fetch_add(1);
        setRendererFallbackReason(rendererState_, 0);
        lastSwsScaleCostUs_.store(-1);
        lastRenderCostUs_.store(-1);
        return true;
    }
    if (optionsSnapshot.renderMode == RenderMode::MEDIACODEC_NV12_GL
        && sourceFormat == AV_PIX_FMT_NV12
        && optionsSnapshot.usingHardwareDecoder) {
        // NV12 GL success bypasses sws_scale / RGBA / ANativeWindow entirely.
        if (renderNv12GlFrame(frame, frameWidth, frameHeight, ptsUs)) {
            recordStageTimingRenderSubmit(ptsUs);
            return true;
        }
        // render failure falls through to the safe sws/RGBA path (counted + logged in renderNv12GlFrame).
    }
    if (optionsSnapshot.renderMode == RenderMode::SOFTWARE_YUV_GL) {
        if (!yuvGlRenderer_.syncSurface()) {
            yuvGlNoSurfaceFrameCount_.fetch_add(1);
            setRendererFallbackReason(rendererState_, 0);
            lastSwsScaleCostUs_.store(-1);
            lastRenderCostUs_.store(-1);
            return true;
        }
        if (renderSoftwareYuvGlFrame(frame, frameWidth, frameHeight)) {
            recordStageTimingRenderSubmit(ptsUs);
            return true;
        }
        setRendererFallbackReason(rendererState_, 2);
    }

    if (swsContext_ == nullptr || swsSourceFormat_ != frame->format
        || swsSourceWidth_ != frameWidth || swsSourceHeight_ != frameHeight) {
        if (swsContext_ != nullptr) {
            sws_freeContext(swsContext_);
            swsContext_ = nullptr;
        }

        swsContext_ = sws_getContext(frameWidth, frameHeight, sourceFormat,
                                     frameWidth, frameHeight, AV_PIX_FMT_RGBA,
                                     SWS_BILINEAR, nullptr, nullptr, nullptr);
        if (swsContext_ == nullptr) {
            LOGE("sws_getContext failed");
            return false;
        }

        const int bufferSize = av_image_get_buffer_size(AV_PIX_FMT_RGBA, frameWidth, frameHeight, 1);
        if (bufferSize <= 0) {
            LOGE("av_image_get_buffer_size failed: %d", bufferSize);
            return false;
        }
        rgbaBuffer_.assign(static_cast<size_t>(bufferSize), 0);
        const int fillResult = av_image_fill_arrays(rgbaFrame_->data, rgbaFrame_->linesize,
                                                    rgbaBuffer_.data(), AV_PIX_FMT_RGBA,
                                                    frameWidth, frameHeight, 1);
        if (fillResult < 0) {
            LOGE("av_image_fill_arrays failed: %s", ffmpegErrorToString(fillResult).c_str());
            return false;
        }

        swsSourceFormat_ = frame->format;
        swsSourceWidth_ = frameWidth;
        swsSourceHeight_ = frameHeight;
        LOGI("sws context ready width=%d height=%d srcFormat=%d srcLineSize0=%d rgbaLineSize=%d",
             frameWidth, frameHeight, frame->format, frame->linesize[0], rgbaFrame_->linesize[0]);
    }

    const int64_t swsStartUs = steadyNowUs();
    sws_scale(swsContext_, frame->data, frame->linesize, 0, frameHeight,
              rgbaFrame_->data, rgbaFrame_->linesize);
    recordCost(lastSwsScaleCostUs_, totalSwsScaleCostUs_, swsScaleCostSampleCount_, maxSwsScaleCostUs_,
               steadyNowUs() - swsStartUs);

    saveLastFrame(rgbaFrame_->data[0], rgbaFrame_->linesize[0], frameWidth, frameHeight, isValidPts(ptsUs) ? ptsUs : 0);

    if (!renderer_.hasSurface()) {
        if (renderedFrameCount_.load() == 0 && droppedVideoFrameCount_.load() == 0) {
            LOGE("render skipped: surface not attached, frame width=%d height=%d", frameWidth, frameHeight);
        }
        return true;
    }

    const RenderResult result = renderer_.renderRgba(rgbaFrame_->data[0], rgbaFrame_->linesize[0], frameWidth, frameHeight);
    lastRenderCostUs_.store(result.stats.totalCostUs);
    lastRenderLockCostUs_.store(result.stats.lockCostUs);
    lastRenderCopyCostUs_.store(result.stats.copyCostUs);
    lastRenderPostCostUs_.store(result.stats.postCostUs);
    if (result.stats.totalCostUs > 0) {
        totalRenderCostUs_.fetch_add(result.stats.totalCostUs);
        renderCostSampleCount_.fetch_add(1);
        updateMax(maxRenderCostUs_, result.stats.totalCostUs);
    }
    if (!result.success) {
        LOGE("renderRgba failed: %s (%d)", result.errorMessage.c_str(), result.errorCode);
        droppedVideoFrameCount_.fetch_add(1);
        return true;
    }
    const int fallbackReasonCode = renderFallbackReasonFromState(rendererState_.load());
    if (fallbackReasonCode == 1) {
        const int64_t fallbackCount = nv12GlFallbackFrameCount_.fetch_add(1) + 1;
        if (fallbackCount == 1 || fallbackCount % 100 == 0) {
            LOGE("NV12 GL render failed, RGBA fallback succeeded count=%lld",
                 static_cast<long long>(fallbackCount));
        }
    } else if (fallbackReasonCode == 2) {
        const int64_t fallbackCount = yuvGlFallbackFrameCount_.fetch_add(1) + 1;
        if (fallbackCount == 1 || fallbackCount % 100 == 0) {
            LOGE("YUV GL render failed or unsupported, RGBA fallback succeeded count=%lld",
                 static_cast<long long>(fallbackCount));
        }
    }
    renderedFrameCount_.fetch_add(1);
    softwareRenderedFrameCount_.fetch_add(1);
    commitRendererSuccess(rendererState_, 1, true);  // rgba_nativewindow
    markFrameRendered();
    recordStageTimingRenderSubmit(ptsUs);
    return true;
}

void NativePlayer::saveLastFrame(const uint8_t *rgbaData, int lineSize, int width, int height, int64_t ptsUs) {
    if (rgbaData == nullptr || lineSize <= 0 || width <= 0 || height <= 0) {
        return;
    }

    PlayerOptions optionsSnapshot;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        optionsSnapshot = playerOptions_;
    }
    const int cacheEveryN = std::max(1, optionsSnapshot.cacheLastFrameEveryN);
    const int64_t candidate = lastFrameCacheCandidateCount_.fetch_add(1) + 1;
    if ((candidate - 1) % cacheEveryN != 0) {
        lastFrameCacheSkippedCount_.fetch_add(1);
        return;
    }

    const int targetStride = width * 4;
    std::lock_guard<std::mutex> lock(lastFrameMutex_);
    lastRgbaFrame_.assign(static_cast<size_t>(targetStride) * static_cast<size_t>(height), 0);
    for (int y = 0; y < height; ++y) {
        std::memcpy(lastRgbaFrame_.data() + static_cast<size_t>(y) * static_cast<size_t>(targetStride),
                    rgbaData + static_cast<size_t>(y) * static_cast<size_t>(lineSize),
                    static_cast<size_t>(targetStride));
    }
    lastFrameWidth_ = width;
    lastFrameHeight_ = height;
    lastFrameStride_ = targetStride;
    lastFramePtsUs_ = ptsUs;
    hasLastFrame_ = true;
    lastFrameCacheUpdateCount_.fetch_add(1);
}

void NativePlayer::clearLastFrame() {
    std::lock_guard<std::mutex> lock(lastFrameMutex_);
    lastRgbaFrame_.clear();
    lastFrameWidth_ = 0;
    lastFrameHeight_ = 0;
    lastFrameStride_ = 0;
    lastFramePtsUs_ = 0;
    hasLastFrame_ = false;
}

void NativePlayer::markFrameRendered() {
    const int64_t renderTimeMs = nowMs();
    lastRenderTimeMs_.store(renderTimeMs);
    const int64_t startTimeMs = startPlayTimeMs_.load();
    if (startTimeMs <= 0) {
        return;
    }
    int64_t unset = -1;
    const int64_t startupCostMs = std::max<int64_t>(0, renderTimeMs - startTimeMs);
    if (startToFirstFrameMs_.compare_exchange_strong(unset, startupCostMs)) {
        LOGI("first frame rendered startToFirstFrameMs=%lld inputOpenCount=%lld decoderOpenCount=%lld hardwareDecoderOpenCount=%lld",
             static_cast<long long>(startupCostMs),
             static_cast<long long>(inputOpenCount_.load()),
             static_cast<long long>(videoDecoderOpenCount_.load()),
             static_cast<long long>(hardwareDecoderOpenCount_.load()));
    }
}

void NativePlayer::deleteSurfaceGlobalRefLocked(JNIEnv *env) {
    if (surfaceGlobalRef_ != nullptr && env != nullptr) {
        env->DeleteGlobalRef(surfaceGlobalRef_);
        surfaceGlobalRef_ = nullptr;
    }
}

void NativePlayer::resetStats() {
    readPacketCount_.store(0);
    videoPacketCount_.store(0);
    audioPacketCount_.store(0);
    inputPacketBytes_.store(0);
    videoPacketBytes_.store(0);
    audioPacketBytes_.store(0);
    videoFrameCount_.store(0);
    audioFrameCount_.store(0);
    audioDecodedSampleCount_.store(0);
    audioDecodeErrorCount_.store(0);
    lastDecodedAudioPtsUs_.store(0);
    lastDecodedAudioNbSamples_.store(0);
    lastDecodedAudioSampleRate_.store(0);
    lastDecodedAudioChannels_.store(0);
    lastDecodedAudioSampleFormat_.store(-1);
    lastAudioDecodeCostUs_.store(-1);
    totalAudioDecodeCostUs_.store(0);
    audioDecodeCostSampleCount_.store(0);
    maxAudioDecodeCostUs_.store(0);
    lastAudioDecodeErrorLogMs_.store(0);
    audioSwrReconfigureCount_.store(0);
    audioPcmBlockCount_.store(0);
    audioPcmSampleCount_.store(0);
    audioPcmByteCount_.store(0);
    audioResampleErrorCount_.store(0);
    lastPcmPtsUs_.store(0);
    lastAudioResampleCostUs_.store(-1);
    totalAudioResampleCostUs_.store(0);
    audioResampleCostSampleCount_.store(0);
    maxAudioResampleCostUs_.store(0);
    lastAudioResampleErrorLogMs_.store(0);
    audioPcmQueue_.clearStats();
    audioQueueGeneration_.store(0);
    audioWorkerConsumedBlockCount_.store(0);
    audioWorkerConsumedSampleCount_.store(0);
    audioWorkerConsumedByteCount_.store(0);
    lastConsumedPcmPtsUs_.store(0);
    audioWorkerStartCount_.store(0);
    audioWorkerJoinCount_.store(0);
    audioWorkerStaleBlockCount_.store(0);
    audioSinkWriteCount_.store(0);
    audioSinkWrittenByteCount_.store(0);
    audioSinkWriteErrorCount_.store(0);
    audioSinkControlledCancelCount_.store(0);
    audioSinkRestartCount_.store(0);
    audioReconnectRecoveryCount_.store(0);
    audioSinkLastErrorCode_.store(0);
    lastAudioSinkWriteCostUs_.store(-1);
    totalAudioSinkWriteCostUs_.store(0);
    audioSinkWriteCostSampleCount_.store(0);
    maxAudioSinkWriteCostUs_.store(0);
    audioPlaybackClockUs_.store(0);
    audioPlaybackClockValid_.store(false);
    audioPlaybackHeadFrames_.store(0);
    audioClockGeneration_.store(0);
    audioClockBaseMediaPtsUs_.store(0);
    audioClockExpectedNextPtsUs_.store(0);
    audioPlaybackHeadRaw32_.store(0);
    audioPlaybackHeadExtended64_.store(0);
    audioClockLastUpdateMs_.store(0);
    audioClockResetCount_.store(0);
    audioClockStaleCount_.store(0);
    audioClockPtsDiscontinuityCount_.store(0);
    lastAudioClockPtsDiscontinuityLogMs_.store(0);
    audioVideoDiffUs_.store(0);
    audioFlushRequested_.store(false);
    audioResumeDiscontinuityRequested_.store(false);
    renderedFrameCount_.store(0);
    droppedVideoFrameCount_.store(0);
    hardwareDecodedFrameCount_.store(0);
    hardwareRenderedFrameCount_.store(0);
    hardwareDroppedFrameCount_.store(0);
    softwareDecodedFrameCount_.store(0);
    softwareRenderedFrameCount_.store(0);
    yuvGlRenderedFrameCount_.store(0);
    yuvGlFallbackFrameCount_.store(0);
    yuvGlNoSurfaceFrameCount_.store(0);
    nv12GlRenderedFrameCount_.store(0);
    nv12GlFallbackFrameCount_.store(0);
    nv12GlNoSurfaceFrameCount_.store(0);
    nv12ThermalRenderedCount_.store(0);
    lastNv12ThermalRenderMode_.store(0);
    nv12AgcValid_.store(false);
    nv12AgcBlackPoint_.store(0.0f);
    nv12AgcWhitePoint_.store(1.0f);
    nv12AgcUpdateCount_.store(0);
    nv12AgcInvalidCount_.store(0);
    nv12AgcFrameCounter_.store(0);
    nv12AgcLastFrameWidth_.store(0);
    nv12AgcLastFrameHeight_.store(0);
    nv12GlLastRenderCostUs_.store(-1);
    nv12GlTotalRenderCostUs_.store(0);
    nv12GlRenderCostSampleCount_.store(0);
    nv12GlMaxRenderCostUs_.store(0);
    nv12GlLastUploadCostUs_.store(-1);
    nv12GlTotalUploadCostUs_.store(0);
    nv12GlUploadCostSampleCount_.store(0);
    nv12GlMaxUploadCostUs_.store(0);
    lastFrameYStride_.store(0);
    lastFrameColorRange_.store(0);
    lastFrameOutputType_.store(0);
    rendererState_.store(0);
    oesFrameAvailableCount_.store(0);
    oesFrameRenderedCount_.store(0);
    oesRenderFailCount_.store(0);
    oesThermalRenderedCount_.store(0);
    lastOesThermalRenderMode_.store(0);
    oesAgcFrameCounter_.store(0);
    oesRenderer_.resetDiagnostics();
    oesRenderer_.resetAgc();
    droppedVideoPacketCount_.store(0);
    packetDropBeforeDecodeCount_.store(0);
    frameDropBeforeRenderCount_.store(0);
    startupKeyFrameWaitActive_.store(false);
    startupKeyFrameDroppedPacketCount_.store(0);
    lastFrameCacheUpdateCount_.store(0);
    lastFrameCacheSkippedCount_.store(0);
    lastFrameCacheCandidateCount_.store(0);
    lastReadPacketTimeMs_.store(0);
    lastVideoFrameTimeMs_.store(0);
    measuredDecodeFps_.store(0.0);
    measuredRenderFps_.store(0.0);
    prevStatsDecodeCount_.store(0);
    prevStatsRenderCount_.store(0);
    prevStatsTimeMs_.store(0);
    lastAudioFrameTimeMs_.store(0);
    lastRenderTimeMs_.store(0);
    lastSnapshotTimeMs_.store(0);
    startPlayTimeMs_.store(0);
    audioClockUs_.store(0);
    videoClockUs_.store(0);
    wallClockUs_.store(0);
    lastVideoDelayUs_.store(0);
    totalVideoDelayUs_.store(0);
    videoDelaySampleCount_.store(0);
    maxVideoDelayUs_.store(0);
    decodedFormatChangeCount_.store(0);
    realtimeClockFormatResetCount_.store(0);
    inputOpenCount_.store(0);
    videoDecoderOpenCount_.store(0);
    hardwareDecoderOpenCount_.store(0);
    realtimeStartInputReuseCount_.store(0);
    startupFreshnessFlushCount_.store(0);
    startupFreshnessFlushErrorCount_.store(0);
    preparedAtTimeMs_.store(0);
    lastPrepareCostUs_.store(-1);
    lastPrepareToStartDelayMs_.store(-1);
    startToFirstFrameMs_.store(-1);
    lastReadFrameCostUs_.store(-1);
    totalReadFrameCostUs_.store(0);
    readFrameCostSampleCount_.store(0);
    maxReadFrameCostUs_.store(0);
    lastSendPacketCostUs_.store(-1);
    lastReceiveFrameCostUs_.store(-1);
    totalDecodeCostUs_.store(0);
    decodeCostSampleCount_.store(0);
    maxDecodeCostUs_.store(0);
    lastSwsScaleCostUs_.store(-1);
    totalSwsScaleCostUs_.store(0);
    swsScaleCostSampleCount_.store(0);
    maxSwsScaleCostUs_.store(0);
    lastRenderCostUs_.store(-1);
    lastRenderLockCostUs_.store(-1);
    lastRenderCopyCostUs_.store(-1);
    lastRenderPostCostUs_.store(-1);
    totalRenderCostUs_.store(0);
    renderCostSampleCount_.store(0);
    maxRenderCostUs_.store(0);
    lastFrameProcessCostUs_.store(-1);
    totalFrameProcessCostUs_.store(0);
    frameProcessCostSampleCount_.store(0);
    maxFrameProcessCostUs_.store(0);
    reconnecting_.store(false);
    waitingSource_.store(false);
    reconnectExhausted_.store(false);
    reconnectAttemptCount_.store(0);
    reconnectSuccessCount_.store(0);
    lastReconnectTimeMs_.store(0);
    lastDisconnectTimeMs_.store(0);
    lastReconnectSuccessTimeMs_.store(0);
    lastReconnectErrorCode_.store(0);
    lastReconnectError_.clear();
    lastFrameFormatName_.clear();
    resetVideoPtsDiagnostics();
    videoPtsBackwardCount_.store(0);
    decoderPtsBackwardCount_.store(0);
    decodedPtsBackwardCount_.store(0);
    renderedPtsBackwardCount_.store(0);
    latencyPtsResetCount_.store(0);
    stageTimingResetCount_.store(0);
}

void NativePlayer::releaseFfmpegResources() {
    if (swsContext_ != nullptr) {
        sws_freeContext(swsContext_);
        swsContext_ = nullptr;
    }
    if (packet_ != nullptr) {
        av_packet_free(&packet_);
    }
    if (decodedFrame_ != nullptr) {
        av_frame_free(&decodedFrame_);
    }
    if (latestFrame_ != nullptr) {
        av_frame_free(&latestFrame_);
    }
    if (rgbaFrame_ != nullptr) {
        av_frame_free(&rgbaFrame_);
    }
    rgbaBuffer_.clear();
    if (videoCodecContext_ != nullptr) {
        if (mediaCodecContextInitialized_) {
            av_mediacodec_default_free(videoCodecContext_);
            mediaCodecContextInitialized_ = false;
        }
        avcodec_free_context(&videoCodecContext_);
    }
    mediaCodecContextInitialized_ = false;
    if (audioDecodedFrame_ != nullptr) {
        av_frame_free(&audioDecodedFrame_);
    }
    if (audioSwrContext_ != nullptr) {
        swr_free(&audioSwrContext_);
    }
    audioSwrInputLayoutMask_ = 0;
    audioSwrInputSampleFormat_ = -1;
    audioSwrInputSampleRate_ = 0;
    audioSwrInputChannels_ = 0;
    audioPcmBuffer_.clear();
    if (audioCodecContext_ != nullptr) {
        avcodec_free_context(&audioCodecContext_);
    }
    if (formatContext_ != nullptr) {
        avformat_close_input(&formatContext_);
    }
    {
        std::lock_guard<std::mutex> lock(mutex_);
        videoStreamIndex_ = -1;
        audioStreamIndex_ = -1;
        audioSampleRate_ = 0;
        audioChannels_ = 0;
        audioSampleFormat_ = -1;
        audioSampleFormatName_.clear();
    }
    audioDecodeOpened_.store(false);
    audioPlayable_.store(false);
    sourceHasVideo_.store(false);
    sourceHasAudio_.store(false);
    swsSourceFormat_ = -1;
    swsSourceWidth_ = 0;
    swsSourceHeight_ = 0;
}

void NativePlayer::setState(PlayerState state, const std::string &errorMessage) {
    std::lock_guard<std::mutex> lock(mutex_);
    state_ = state;
    if (!errorMessage.empty()) {
        errorMessage_ = errorMessage;
    }
}

std::string NativePlayer::buildStateJsonLocked() const {
    std::ostringstream out;
    out << "{\"success\":true,"
        << "\"state\":\"" << stateName(state_) << "\","
        << "\"playerState\":\"" << playerStateName(state_) << "\","
        << "\"url\":\"" << escapeJson(url_) << "\","
        << "\"sourceHasVideo\":" << (sourceHasVideo_.load() ? "true" : "false") << ","
        << "\"sourceHasAudio\":" << (sourceHasAudio_.load() ? "true" : "false") << ","
        << "\"videoStreamIndex\":" << videoStreamIndex_ << ","
        << "\"audioStreamIndex\":" << audioStreamIndex_ << ","
        << "\"videoWidth\":" << videoWidth_ << ","
        << "\"videoHeight\":" << videoHeight_ << ","
        << "\"videoCodec\":\"" << escapeJson(videoCodec_) << "\","
        << "\"videoFrameCount\":" << videoFrameCount_.load() << ","
        << "\"audioCodec\":\"" << escapeJson(audioCodec_) << "\","
        << "\"audioSampleRate\":" << audioSampleRate_ << ","
        << "\"audioChannels\":" << audioChannels_ << ","
        << "\"fps\":" << fps_ << ","
        << "\"errorMessage\":\"" << escapeJson(errorMessage_) << "\"," 
        << "\"rtspTransportMode\":\"" << escapeJson(rtspTransportMode_) << "\","
        << "\"currentRtspTransport\":\"" << (preferUdpTransport_.load() ? "udp" : "tcp") << "\","
        << "\"rtspTransportSwitchPending\":" << (transportSwitchRequested_.load() ? "true" : "false") << ","
        << "\"reconnectEnabled\":" << (reconnectEnabled_.load() ? "true" : "false") << ","
        << "\"reconnecting\":" << (reconnecting_.load() ? "true" : "false") << ","
        << "\"waitingSource\":" << (waitingSource_.load() ? "true" : "false") << ","
        << "\"reconnectAttempt\":" << reconnectAttemptCount_.load() << ","
        << "\"reconnectLastError\":\"" << escapeJson(lastReconnectError_) << "\","
        << "\"reconnectLastErrorCode\":" << lastReconnectErrorCode_.load() << ","
        << "\"reconnectExhausted\":" << (reconnectExhausted_.load() ? "true" : "false") << ","
        << "\"lastDisconnectTimeMs\":" << lastDisconnectTimeMs_.load() << ","
        << "\"lastReconnectSuccessTimeMs\":" << lastReconnectSuccessTimeMs_.load() << ","
        << "\"reconnectAttemptCount\":" << reconnectAttemptCount_.load() << ","
        << "\"reconnectSuccessCount\":" << reconnectSuccessCount_.load() << ","
        << "\"lastReconnectError\":\"" << escapeJson(lastReconnectError_) << "\"}";
    return out.str();
}


std::string NativePlayer::buildReconnectJson() const {
    PlayerState state;
    std::string lastError;
    std::string rtspTransportMode;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        state = state_;
        lastError = lastReconnectError_;
        rtspTransportMode = rtspTransportMode_;
    }

    std::ostringstream out;
    out << "{\"success\":true,"
        << "\"state\":\"" << stateName(state) << "\"," 
        << "\"playerState\":\"" << playerStateName(state) << "\","
        << "\"rtspTransportMode\":\"" << escapeJson(rtspTransportMode) << "\","
        << "\"currentRtspTransport\":\"" << (preferUdpTransport_.load() ? "udp" : "tcp") << "\","
        << "\"rtspTransportSwitchPending\":" << (transportSwitchRequested_.load() ? "true" : "false") << ","
        << "\"enabled\":" << (reconnectEnabled_.load() ? "true" : "false") << ","
        << "\"reconnecting\":" << (reconnecting_.load() ? "true" : "false") << ","
        << "\"waitingSource\":" << (waitingSource_.load() ? "true" : "false") << ","
        << "\"maxRetryCount\":" << reconnectMaxRetryCount_.load() << ","
        << "\"maxRetry\":" << reconnectMaxRetryCount_.load() << ","
        << "\"retryDelayMs\":" << reconnectRetryDelayMs_.load() << ","
        << "\"initialDelayMs\":" << reconnectRetryDelayMs_.load() << ","
        << "\"maxDelayMs\":" << reconnectMaxDelayMs_.load() << ","
        << "\"infiniteReconnect\":" << (infiniteReconnect_.load() ? "true" : "false") << ","
        << "\"reconnectOnEof\":" << (reconnectOnEof_.load() ? "true" : "false") << ","
        << "\"reconnectOn404\":" << (reconnectOn404_.load() ? "true" : "false") << ","
        << "\"keepWaitingWhenSourceMissing\":" << (keepWaitingWhenSourceMissing_.load() ? "true" : "false") << ","
        << "\"reconnectAttempt\":" << reconnectAttemptCount_.load() << ","
        << "\"attemptCount\":" << reconnectAttemptCount_.load() << ","
        << "\"successCount\":" << reconnectSuccessCount_.load() << ","
        << "\"lastReconnectTimeMs\":" << lastReconnectTimeMs_.load() << ","
        << "\"lastDisconnectTimeMs\":" << lastDisconnectTimeMs_.load() << ","
        << "\"lastReconnectSuccessTimeMs\":" << lastReconnectSuccessTimeMs_.load() << ","
        << "\"lastErrorCode\":" << lastReconnectErrorCode_.load() << ","
        << "\"reconnectLastErrorCode\":" << lastReconnectErrorCode_.load() << ","
        << "\"reconnectExhausted\":" << (reconnectExhausted_.load() ? "true" : "false") << ","
        << "\"reconnectLastError\":\"" << escapeJson(lastError) << "\","
        << "\"lastError\":\"" << escapeJson(lastError) << "\"}";
    return out.str();
}
bool NativePlayer::isReleased() const {
    return released_.load();
}
