package com.example.motro.ffmpeg;

import android.view.Surface;

/**
 * Public facade for the Motro live player. Owns the native handle,
 * {@link LiveAudioPcmSink} and the native event bridge. Consumer code
 * (e.g. {@code MediaPlayerActivity}) interacts only with this class.
 *
 * <p>Lifecycle (mirrors {@code FFmpegNative}):</p>
 * <pre>
 *   FFmpegPlayer player = new FFmpegPlayer();
 *   player.setListener(...);
 *   player.setSurface(surface);
 *   player.prepare(url, timeoutMs);
 *   player.start();
 *   player.setAudioEnabled(true);
 *   // ...
 *   player.stop();
 *   player.release(); // or try-with-resources
 * </pre>
 * <p>{@code release()} is idempotent; after release every instance method
 * returns a JSON error string and never touches a stale native handle.</p>
 */
public final class FFmpegPlayer implements AutoCloseable {

    public interface Listener {
        void onPlayerEvent(String event, String eventJson);
    }

    private final Object lock = new Object();
    private long nativeHandle;
    private boolean released;
    private final LiveAudioPcmSink audioSink;
    private Listener externalListener;

    private final FFmpegNative.PlayerEventListener internalListener = new FFmpegNative.PlayerEventListener() {
        @Override
        public void onPlayerEvent(long handle, String event, String eventJson) {
            Listener l;
            synchronized (lock) {
                if (released || nativeHandle == 0 || handle != nativeHandle) {
                    return;
                }
                l = externalListener;
            }
            if (l != null) {
                l.onPlayerEvent(event, eventJson);
            }
        }
    };

    public FFmpegPlayer() {
        long handle = FFmpegNative.createPlayer();
        LiveAudioPcmSink sink = new LiveAudioPcmSink();
        synchronized (lock) {
            nativeHandle = handle;
            audioSink = sink;
            if (handle != 0) {
                FFmpegNative.setPlayerEventListener(handle, internalListener);
                FFmpegNative.setAudioCallback(handle, sink);
            }
        }
    }

    private String errorReleased() {
        return "{\"success\":false,\"errorCode\":-1,\"errorMessage\":\"player released\"}";
    }

    private String errorNoHandle() {
        return "{\"success\":false,\"errorCode\":-1,\"errorMessage\":\"player handle is 0\"}";
    }

    public void setListener(Listener listener) {
        synchronized (lock) {
            if (released) {
                return;
            }
            externalListener = listener;
        }
    }

    public String setSurface(Surface surface) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.setPlayerSurface(nativeHandle, surface);
        }
    }

    public String clearSurface() {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.clearPlayerSurface(nativeHandle);
        }
    }

    public String prepare(String url, int timeoutMs) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.preparePlayer(nativeHandle, url, timeoutMs);
        }
    }

    public String start() {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.startPlayer(nativeHandle);
        }
    }

    public String pause() {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.pausePlayer(nativeHandle);
        }
    }

    public String stop() {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.stopPlayer(nativeHandle);
        }
    }

    public String setAudioEnabled(boolean enabled) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.enableAudio(nativeHandle, enabled);
        }
    }

    public String setHardwareDecodeEnabled(boolean enabled) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.setHardwareDecode(nativeHandle, enabled);
        }
    }

    public String setHardwareRenderMode(String mode) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.setHardwareRenderMode(nativeHandle, mode);
        }
    }

    public String setRtspTransport(String transport) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.setRtspTransport(nativeHandle, transport);
        }
    }

    public String setLatencyMode(String mode) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.setPlayerLatencyMode(nativeHandle, mode);
        }
    }

    public String setPlayerOption(String key, String value) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.setPlayerOption(nativeHandle, key, value);
        }
    }

    public String setReconnectOptions(boolean enabled, int maxRetryCount, int retryDelayMs) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.setPlayerReconnectOptions(nativeHandle, enabled, maxRetryCount, retryDelayMs);
        }
    }

    public String setThermalEnabled(boolean enabled) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.setThermalEnabled(nativeHandle, enabled);
        }
    }

    public String setThermalPalette(int palette) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.setThermalPalette(nativeHandle, palette);
        }
    }

    public String setThermalAgcEnabled(boolean enabled) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.setThermalAgcEnabled(nativeHandle, enabled);
        }
    }

    public String setThermalGamma(float gamma) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.setThermalGamma(nativeHandle, gamma);
        }
    }

    public String setThermalWindow(float blackPoint, float whitePoint) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.setThermalWindow(nativeHandle, blackPoint, whitePoint);
        }
    }

    public String startRecord(String outputPath) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.startPlayerRecord(nativeHandle, outputPath);
        }
    }

    public String startSegmentRecord(String outputPattern, int segmentDurationSec) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.startPlayerSegmentRecord(nativeHandle, outputPattern, segmentDurationSec);
        }
    }

    public String startRecordWithConfig(String outputPathOrPattern, String format, int segmentDurationSec) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.startPlayerRecordWithConfig(nativeHandle, outputPathOrPattern, format, segmentDurationSec);
        }
    }

    public String stopRecord() {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.stopPlayerRecord(nativeHandle);
        }
    }

    public String takeSnapshot(String outputPath) {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.takePlayerSnapshot(nativeHandle, outputPath);
        }
    }

    public String getState() {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.getPlayerState(nativeHandle);
        }
    }

    public String getStats() {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.getPlayerStats(nativeHandle);
        }
    }

    public String getReconnectState() {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.getPlayerReconnectState(nativeHandle);
        }
    }

    public String getLatencyConfig() {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.getPlayerLatencyConfig(nativeHandle);
        }
    }

    public String getRecordState() {
        synchronized (lock) {
            if (released) return errorReleased();
            if (nativeHandle == 0) return errorNoHandle();
            return FFmpegNative.getPlayerRecordState(nativeHandle);
        }
    }

    public boolean isReleased() {
        synchronized (lock) {
            return released;
        }
    }

    public String release() {
        long handleToRelease;
        synchronized (lock) {
            if (released) {
                return "{\"success\":true,\"message\":\"player already released\"}";
            }
            released = true;
            handleToRelease = nativeHandle;
            nativeHandle = 0;
            externalListener = null;
        }
        if (handleToRelease != 0) {
            try {
                FFmpegNative.setPlayerEventListener(handleToRelease, null);
            } catch (Throwable ignored) {
            }
            try {
                FFmpegNative.setAudioCallback(handleToRelease, null);
            } catch (Throwable ignored) {
            }
            return FFmpegNative.releasePlayer(handleToRelease);
        }
        return "{\"success\":true,\"message\":\"player released\"}";
    }

    @Override
    public void close() {
        release();
    }
}
