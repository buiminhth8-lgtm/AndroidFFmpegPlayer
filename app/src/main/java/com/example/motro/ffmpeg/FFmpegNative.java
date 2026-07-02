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

    public static native String enableAudio(long handle, boolean enabled);

    public static native String setPlayerReconnectOptions(long handle, boolean enabled, int maxRetryCount, int retryDelayMs);

    public static native String getPlayerReconnectState(long handle);

    public static native String setPlayerRtspTransport(long handle, String transport);

    public static native String getPlayerRtspTransportState(long handle);

    /*
     * Low latency RTSP example:
     * long handle = FFmpegNative.createPlayer();
     * FFmpegNative.setRtspTransport(handle, "udp");
     * FFmpegNative.setPlayerLatencyMode(handle, "low_latency");
     * FFmpegNative.setPlayerOption(handle, "drop_late_frame_threshold_us", "150000");
     * FFmpegNative.preparePlayer(handle, rtspUrl, 3000000);
     * FFmpegNative.startPlayer(handle);
     */
    public static native String setRtspTransport(long handle, String transport);

    public static native String setPlayerLatencyMode(long handle, String mode);

    public static native String setPlayerOption(long handle, String key, String value);

    public static native String getPlayerLatencyConfig(long handle);

    public static native String startPlayerRecord(long handle, String outputPath);

    public static native String startPlayerSegmentRecord(long handle, String outputPattern, int segmentDurationSec);

    public static native String stopPlayerRecord(long handle);

    public static native String getPlayerRecordState(long handle);
}
