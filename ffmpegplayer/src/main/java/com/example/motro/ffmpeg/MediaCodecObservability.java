package com.example.motro.ffmpeg;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

/**
 * 将 Android capability 与 NativePlayer 的真实控制结果合并到播放器 Stats。
 * 此 observer 自身不会创建 codec 或下发参数。
 */
final class MediaCodecObservability {

    private static final String TAG = "MediaCodecLowLatency";

    private final MediaCodecLowLatencyState state = new MediaCodecLowLatencyState();
    private String decoderIdentity = "";

    void resetForPrepare() {
        decoderIdentity = "";
        state.onDecoderReleased("prepare reset");
    }

    void release() {
        decoderIdentity = "";
        state.onDecoderReleased("player released");
    }

    void resetForStop() {
        decoderIdentity = "";
        state.onDecoderReleased("player stopped");
    }

    String augment(String rawStats) {
        try {
            JSONObject root = new JSONObject(rawStats);
            if (!root.optBoolean("success", false)) {
                return rawStats;
            }
            final String playerState = root.optString("playerState", root.optString("state", ""));
            final String actualDecoder = root.optString("actualDecoderName", "");
            final long generation = root.optLong("videoDecoderOpenCount", 0);
            final boolean requested = root.optBoolean("mediaCodecLowLatencyRequested", false);
            final String controlStatus = root.optString("mediaCodecLowLatencyControlStatus",
                    requested ? MediaCodecLowLatencyState.UNKNOWN : MediaCodecLowLatencyState.OFF);
            final String nativeReason = root.optString("mediaCodecLowLatencyReason",
                    requested ? "control result unavailable" : "disabled; no parameter submitted");
            final boolean decoderAlive = generation > 0 && !actualDecoder.isEmpty()
                    && !"STOPPED".equalsIgnoreCase(playerState)
                    && !"IDLE".equalsIgnoreCase(playerState);
            if (!decoderAlive) {
                decoderIdentity = "";
                state.onDecoderUnavailable(requested, controlStatus, nativeReason);
            } else {
                final String identity = generation + "|" + actualDecoder + "|"
                        + root.optString("decodeBackend", "unknown") + "|"
                        + root.optBoolean("usingHardwareDecoder", false) + "|"
                        + root.optBoolean("hardwareDecodeFallbackUsed", false) + "|"
                        + root.optInt("videoWidth", 0) + "x" + root.optInt("videoHeight", 0);
                if (!identity.equals(decoderIdentity)) {
                    decoderIdentity = identity;
                    observeDecoder(root, generation);
                    logDecoderSession(state.snapshot());
                }
            }
            putState(root, state.snapshot());
            return root.toString();
        } catch (Throwable ignored) {
            // Stats 观测失败不得改变播放行为或覆盖原始 native JSON。
            return rawStats;
        }
    }

    private void observeDecoder(JSONObject root, long generation) {
        final String requestedDecoder = root.optString("requestedDecoderName", "unknown");
        final String actualDecoder = root.optString("actualDecoderName", "unknown");
        final String backend = root.optString("decodeBackend", "unknown");
        final boolean hardwareDecode = root.optBoolean("usingHardwareDecoder", false);
        final boolean hardwareFallback = root.optBoolean("hardwareDecodeFallbackUsed", false);
        final String mime = mimeForCodec(root.optString("videoCodec", ""));
        Capability capability;
        if (!hardwareDecode || !"mediacodec".equalsIgnoreCase(backend)) {
            capability = Capability.unavailable(mime,
                    hardwareFallback ? "hardware fallback" : "not a MediaCodec decoder");
        } else {
            capability = probe(mime, root.optInt("videoWidth", 0), root.optInt("videoHeight", 0));
        }

        final boolean requested = root.optBoolean("mediaCodecLowLatencyRequested", false);
        final boolean configured = root.optBoolean("mediaCodecLowLatencyConfigured", false);
        final String runtimeApplied = root.optString("mediaCodecLowLatencyRuntimeApplied",
                MediaCodecLowLatencyState.UNKNOWN);
        final String controlStatus = root.optString("mediaCodecLowLatencyControlStatus",
                requested ? MediaCodecLowLatencyState.UNKNOWN : MediaCodecLowLatencyState.OFF);
        final String nativeReason = root.optString("mediaCodecLowLatencyReason", capability.reason);
        String reason = nativeReason;
        if (!requested) {
            reason = "disabled; no MediaCodec parameter submitted";
        } else if (hardwareFallback || !hardwareDecode) {
            reason = nativeReason;
        } else if (MediaCodecLowLatencyState.UNSUPPORTED.equals(capability.supported)) {
            reason = "UNSUPPORTED; " + nativeReason;
        } else if (MediaCodecLowLatencyState.UNAVAILABLE_API_LEVEL.equals(capability.supported)) {
            reason = "API_UNAVAILABLE; " + nativeReason;
        } else if (configured && MediaCodecLowLatencyState.UNKNOWN.equals(runtimeApplied)) {
            reason = "configure parameter accepted; hardware runtime readback unavailable";
        }
        state.onDecoderCreated(generation, requestedDecoder, actualDecoder, backend,
                hardwareDecode, hardwareFallback,
                root.optString("frameOutputType", "unknown"),
                root.optString("renderer", "unknown"),
                capability.component, capability.componentSource, capability.mime,
                capability.hardware, capability.vendor, requested, capability.supported,
                configured, runtimeApplied, controlStatus, reason);
    }

    private static Capability probe(String mime, int width, int height) {
        if (MediaCodecLowLatencyState.UNKNOWN.equals(mime)) {
            return Capability.unknown(mime, "unsupported mime mapping");
        }
        try {
            MediaFormat format = MediaFormat.createVideoFormat(mime,
                    Math.max(width, 1), Math.max(height, 1));
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            String component = list.findDecoderForFormat(format);
            if (component == null || component.isEmpty()) {
                return Capability.unknown(mime, "decoder selection unavailable");
            }
            MediaCodecInfo selected = null;
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (!info.isEncoder() && component.equals(info.getName())) {
                    selected = info;
                    break;
                }
            }
            if (selected == null) {
                return Capability.unknown(mime, "selected decoder metadata unavailable");
            }
            final String hardware;
            final String vendor;
            if (Build.VERSION.SDK_INT >= 29) {
                hardware = selected.isHardwareAccelerated()
                        ? MediaCodecLowLatencyState.YES : MediaCodecLowLatencyState.NO;
                vendor = selected.isVendor()
                        ? MediaCodecLowLatencyState.YES : MediaCodecLowLatencyState.NO;
            } else {
                hardware = MediaCodecLowLatencyState.UNKNOWN;
                vendor = MediaCodecLowLatencyState.UNKNOWN;
            }
            final String supported;
            if (Build.VERSION.SDK_INT < 30) {
                supported = MediaCodecLowLatencyState.UNAVAILABLE_API_LEVEL;
            } else {
                MediaCodecInfo.CodecCapabilities caps = selected.getCapabilitiesForType(mime);
                supported = MediaCodecLowLatencyState.capabilityState(true,
                        caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency));
            }
            return new Capability(component, "MediaCodecList.findDecoderForFormat", mime,
                    hardware, vendor, supported, "capability observed");
        } catch (Throwable error) {
            return Capability.unknown(mime, "capability query failed: "
                    + error.getClass().getSimpleName());
        }
    }

    private static void putState(JSONObject root, MediaCodecLowLatencyState.Snapshot snapshot)
            throws Exception {
        // 与旧 *Name 字段并存的明确别名，供后续 A/B 汇总直接读取。
        root.put("requestedDecoder", snapshot.requestedDecoder);
        root.put("actualDecoder", snapshot.actualDecoder);
        root.put("hardwareDecode", snapshot.hardwareDecode);
        root.put("hardwareFallback", snapshot.hardwareFallback);
        root.put("mediaCodecComponentName", snapshot.component);
        root.put("mediaCodecComponentSource", snapshot.componentSource);
        root.put("mediaCodecMime", snapshot.mime);
        root.put("mediaCodecHardwareAccelerated", stateValue(snapshot.hardwareAccelerated));
        root.put("mediaCodecVendor", stateValue(snapshot.vendor));
        root.put("mediaCodecLowLatencyRequested", snapshot.lowLatencyRequested);
        root.put("mediaCodecLowLatencySupported", snapshot.lowLatencySupported);
        root.put("mediaCodecLowLatencyConfigured", snapshot.lowLatencyConfigured);
        root.put("mediaCodecLowLatencyRuntimeApplied", snapshot.runtimeApplied);
        root.put("mediaCodecLowLatencyControlStatus", snapshot.controlStatus);
        root.put("mediaCodecLowLatencyReason", snapshot.reason);

        JSONObject lowLatency = new JSONObject();
        lowLatency.put("requested", snapshot.lowLatencyRequested);
        lowLatency.put("supported", snapshot.lowLatencySupported);
        lowLatency.put("configured", snapshot.lowLatencyConfigured);
        lowLatency.put("runtimeApplied", snapshot.runtimeApplied);
        lowLatency.put("controlStatus", snapshot.controlStatus);
        lowLatency.put("reason", snapshot.reason);
        JSONObject mediaCodec = new JSONObject();
        mediaCodec.put("component", snapshot.component);
        mediaCodec.put("componentSource", snapshot.componentSource);
        mediaCodec.put("mime", snapshot.mime);
        mediaCodec.put("hardware", stateValue(snapshot.hardwareAccelerated));
        mediaCodec.put("vendor", stateValue(snapshot.vendor));
        mediaCodec.put("lowLatency", lowLatency);
        root.put("mediaCodec", mediaCodec);
    }

    private static Object stateValue(String state) {
        if (MediaCodecLowLatencyState.YES.equals(state)) return Boolean.TRUE;
        if (MediaCodecLowLatencyState.NO.equals(state)) return Boolean.FALSE;
        return state;
    }

    private static String mimeForCodec(String codec) {
        if ("hevc".equalsIgnoreCase(codec) || "h265".equalsIgnoreCase(codec)) {
            return MediaFormat.MIMETYPE_VIDEO_HEVC;
        }
        if ("h264".equalsIgnoreCase(codec) || "avc".equalsIgnoreCase(codec)) {
            return MediaFormat.MIMETYPE_VIDEO_AVC;
        }
        return MediaCodecLowLatencyState.UNKNOWN;
    }

    private static void logDecoderSession(MediaCodecLowLatencyState.Snapshot snapshot) {
        Log.i(TAG, "[MediaCodecLowLatency]"
                + " component=" + snapshot.component
                + " mime=" + snapshot.mime
                + " requested=" + snapshot.lowLatencyRequested
                + " supported=" + snapshot.lowLatencySupported
                + " configured=" + snapshot.lowLatencyConfigured
                + " runtimeApplied=" + snapshot.runtimeApplied
                + " controlStatus=" + snapshot.controlStatus
                + " reason=" + snapshot.reason);
    }

    private static final class Capability {
        final String component;
        final String componentSource;
        final String mime;
        final String hardware;
        final String vendor;
        final String supported;
        final String reason;

        Capability(String component, String componentSource, String mime, String hardware,
                   String vendor, String supported, String reason) {
            this.component = component;
            this.componentSource = componentSource;
            this.mime = mime;
            this.hardware = hardware;
            this.vendor = vendor;
            this.supported = supported;
            this.reason = reason;
        }

        static Capability unavailable(String mime, String reason) {
            return new Capability(MediaCodecLowLatencyState.UNAVAILABLE,
                    MediaCodecLowLatencyState.UNAVAILABLE, mime,
                    MediaCodecLowLatencyState.UNKNOWN, MediaCodecLowLatencyState.UNKNOWN,
                    MediaCodecLowLatencyState.UNKNOWN, reason);
        }

        static Capability unknown(String mime, String reason) {
            return new Capability(MediaCodecLowLatencyState.UNKNOWN,
                    MediaCodecLowLatencyState.UNKNOWN, mime,
                    MediaCodecLowLatencyState.UNKNOWN, MediaCodecLowLatencyState.UNKNOWN,
                    MediaCodecLowLatencyState.UNKNOWN, reason);
        }
    }
}
