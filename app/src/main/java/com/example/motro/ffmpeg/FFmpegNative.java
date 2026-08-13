package com.example.motro.ffmpeg;

import android.util.Log;
import android.view.Surface;

public final class FFmpegNative {

    private static final String TAG = "FFmpegNative";

    static {
        loadRequired("avutil");
        loadOptional("swresample");
        loadRequired("swscale");
        loadRequired("avcodec");
        loadRequired("avformat");
        loadRequired("native-ffmpeg");
    }

    private FFmpegNative() {
    }

    public static final String EVENT_RECONNECT_DISCONNECTED = "reconnect_disconnected";
    public static final String EVENT_RECONNECTING = "reconnecting";
    public static final String EVENT_WAITING_SOURCE = "waiting_source";
    public static final String EVENT_RECONNECT_SUCCESS = "reconnect_success";
    public static final String EVENT_RECONNECT_EXHAUSTED = "reconnect_exhausted";

    public interface PlayerEventListener {
        void onPlayerEvent(long handle, String event, String eventJson);
    }

    private static void loadRequired(String name) {
        System.loadLibrary(name);
        Log.i(TAG, "loaded " + name);
    }

    private static boolean loadOptional(String name) {
        try {
            System.loadLibrary(name);
            Log.i(TAG, "loaded optional " + name);
            return true;
        } catch (UnsatisfiedLinkError error) {
            Log.w(TAG, "optional library not loaded: " + name + ", " + error.getMessage());
            return false;
        }
    }

    public static native String getFFmpegVersion();

    public static native String getFFmpegBuildConfig();

    public static native String getAvailableDecoders();

    public static native String getMediaCodecInfo();

    public static native String probe(String url, int timeoutMs);

    public static native String runDebugCommand(String[] args);

    public static native long createPlayer();

    public static native String setPlayerSurface(long handle, Surface surface);

    public static native String preparePlayer(long handle, String url, int timeoutMs);

    public static native String startPlayer(long handle);

    public static native String pausePlayer(long handle);

    public static native String stopPlayer(long handle);

    public static native String getPlayerState(long handle);

    public static native String releasePlayer(long handle);

    public static native String takePlayerSnapshot(long handle, String outputPath);

    public static native String getPlayerStats(long handle);

    public static native String clearPlayerSurface(long handle);

    public static native String setAudioCallback(long handle, Object audioCallback);

    public static native String setPlayerEventListener(long handle, PlayerEventListener listener);

    public static native String enableAudio(long handle, boolean enabled);

    public static native String setPlayerReconnectOptions(long handle, boolean enabled, int maxRetryCount, int retryDelayMs);

    public static native String getPlayerReconnectState(long handle);

    public static native String setPlayerRtspTransport(long handle, String transport);

    public static native String getPlayerRtspTransportState(long handle);

    /**
     * Low latency RTSP example:
     * long handle = FFmpegNative.createPlayer();
     * FFmpegNative.setRtspTransport(handle, "udp");
     * FFmpegNative.setPlayerLatencyMode(handle, "low_latency");
     * FFmpegNative.setPlayerOption(handle, "drop_late_frame_threshold_us", "150000");
     * FFmpegNative.preparePlayer(handle, rtspUrl, 3000000);
     * FFmpegNative.startPlayer(handle);
     *
     * FFmpeg MediaCodec Surface low latency example:
     * long handle = FFmpegNative.createPlayer();
     * FFmpegNative.setRtspTransport(handle, "udp");
     * FFmpegNative.setPlayerLatencyMode(handle, "ultra_low_latency");
     * FFmpegNative.setPlayerOption(handle, "ultra_latency_level", "normal");
     * FFmpegNative.setHardwareDecode(handle, true);
     * FFmpegNative.setHardwareRenderMode(handle, "mediacodec_surface");
     * FFmpegNative.enableAudio(handle, false);
     * FFmpegNative.setPlayerEventListener(handle, (playerHandle, event, eventJson) -> {
     *     // EVENT_RECONNECTING / EVENT_WAITING_SOURCE: show reconnect animation.
     *     // EVENT_RECONNECT_SUCCESS: hide reconnect animation.
     * });
     * FFmpegNative.setPlayerSurface(handle, holder.getSurface());
     * FFmpegNative.preparePlayer(handle, rtspUrl, 5000);
     * FFmpegNative.startPlayer(handle);
     * String stats = FFmpegNative.getPlayerStats(handle);
     *
     * Software decode + OpenGL ES YUV render:
     * FFmpegNative.setHardwareDecode(handle, false);
     * FFmpegNative.setHardwareRenderMode(handle, "software_yuv_gl");
     */
    public static native String setRtspTransport(long handle, String transport);

    public static native String setPlayerLatencyMode(long handle, String mode);

    public static native String setPlayerOption(long handle, String key, String value);

    public static native String setHardwareDecode(long handle, boolean enabled);

    public static native String setHardwareRenderMode(long handle, String mode);

    public static native String getPlayerLatencyConfig(long handle);

    public static native String startPlayerRecord(long handle, String outputPath);

    public static native String startPlayerSegmentRecord(long handle, String outputPattern, int segmentDurationSec);

    /**
     *
     * @param handle
     * @param outputPathOrPattern
     * @param format
     * @param segmentDurationSec
     * @return
     *
     *
     * // 单文件 fragmented MP4
     * FFmpegNative.startPlayerRecordWithConfig(handle, "/path/record.mp4", "mp4", 0);
     * // 每 5 分钟一个 MKV 文件
     * FFmpegNative.startPlayerRecordWithConfig(handle, "/path/record_%03d.mkv", "mkv", 300);
     */
    public static native String startPlayerRecordWithConfig(long handle, String outputPathOrPattern, String format, int segmentDurationSec);

    public static native String stopPlayerRecord(long handle);

    public static native String getPlayerRecordState(long handle);
}
