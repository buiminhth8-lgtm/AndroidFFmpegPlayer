package com.example.motro.ffmpeg;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the Android AudioTrack used for live PCM monitoring. All methods are
 * invoked from the native audio output worker thread (onAudioPcm) or from the
 * native lifecycle control path (onAudioControl). It does not depend on any
 * Activity or View.
 *
 * Fixed contract (frozen by Audio Phase 1): S16 / 48000 Hz / stereo / interleaved.
 */
public final class LiveAudioPcmSink {

    private static final String TAG = "LiveAudioPcmSink";

    public static final int CMD_START = 0;
    public static final int CMD_PAUSE_FLUSH = 1;
    public static final int CMD_RELEASE = 2;

    // Native treats this as an expected lifecycle cancellation rather than an
    // AudioTrack failure. It is intentionally outside Android's error range.
    public static final int WRITE_CANCELLED = -10000;

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    // 单个 PCM 块允许的重试时间窗口（纳秒），避免设备背压导致生命周期操作长期等待。
    private static final long MAX_WRITE_WAIT_NANOS = 250_000_000L;
    private static final long WRITE_RETRY_NANOS = 2_000_000L;

    // Accessed from the audio worker thread and the lifecycle control thread.
    // Lifecycle commands never wait for a blocking write: writes are
    // non-blocking and an epoch invalidates any in-flight old-generation block.
    private volatile AudioTrack audioTrack;
    private volatile boolean acceptingWrites;
    // 生命周期代次；暂停、重新开始或释放后，在途写入据此取消旧块。
    private final AtomicLong lifecycleEpoch = new AtomicLong();
    private int minBufferBytes = -1;

    public LiveAudioPcmSink() {
    }

    /**
     * Called from the native audio output worker thread. Writes the PCM block
     * without an unbounded AudioTrack wait and returns the number of bytes
     * written, or a negative AudioTrack error code. Creates/starts AudioTrack
     * lazily. A partial write is returned to native as an audio-only failure so
     * the live pipeline can drop forward rather than block Stop/Release.
     * The caller must keep {@code pcm} alive until this method returns.
     */
    // 消费原生提供的 S16/48kHz/双声道交错 PCM；返回写入字节数或负错误码，调用期间缓冲区必须有效。
    public int onAudioPcm(ByteBuffer pcm, int sizeBytes, long ptsUs) {
        final long epoch = lifecycleEpoch.get();
        if (!acceptingWrites) {
            return WRITE_CANCELLED;
        }
        AudioTrack track = ensureStarted();
        if (track == null) {
            return AudioTrack.ERROR_INVALID_OPERATION;
        }
        int total = 0;
        final long deadlineNanos = System.nanoTime() + MAX_WRITE_WAIT_NANOS;
        while (total < sizeBytes) {
            if (!acceptingWrites || lifecycleEpoch.get() != epoch) {
                pauseFlushTrack(track);
                return WRITE_CANCELLED;
            }
            int written = track.write(pcm, sizeBytes - total, AudioTrack.WRITE_NON_BLOCKING);
            if (written < 0) {
                return written;
            }
            if (written == 0) {
                if (System.nanoTime() >= deadlineNanos) {
                    break;
                }
                // WRITE_NON_BLOCKING keeps lifecycle cancellation observable.
                // A short bounded retry window lets AudioTrack drain normally
                // without ever entering the platform's indefinite write wait.
                java.util.concurrent.locks.LockSupport.parkNanos(WRITE_RETRY_NANOS);
                continue;
            }
            total += written;
        }
        if (!acceptingWrites || lifecycleEpoch.get() != epoch) {
            // A lifecycle reset may race immediately after the non-blocking
            // write. Flush once more before returning so bytes from the old
            // epoch cannot become audible after CMD_START.
            pauseFlushTrack(track);
            return WRITE_CANCELLED;
        }
        return total;
    }

    /**
     * Called from the native lifecycle control path. Returns 0 on success or a
     * negative error code.
     */
    // 处理原生生命周期指令；开始仅开放写入，AudioTrack 在工作线程首次写入时延迟创建。
    public int onAudioControl(int command) {
        switch (command) {
            case CMD_START:
                lifecycleEpoch.incrementAndGet();
                acceptingWrites = true;
                return 0; // Track remains lazy-created on the worker thread.
            case CMD_PAUSE_FLUSH:
                acceptingWrites = false;
                lifecycleEpoch.incrementAndGet();
                pauseFlush();
                return 0;
            case CMD_RELEASE:
                acceptingWrites = false;
                lifecycleEpoch.incrementAndGet();
                release();
                return 0;
            default:
                return 0;
        }
    }

    /**
     * Called from the native audio output worker thread. Returns the raw 32-bit
     * AudioTrack playback-head frame position (0 when the track is not created).
     * The native side converts this into a monotonic 64-bit played-frame count.
     */
    // 返回设备已播放采样帧数的原始 32 位值，原生层据此维护扩展计数和音频主时钟。
    public int getPlaybackHeadFrames() {
        AudioTrack track = audioTrack;
        if (track == null) {
            return 0;
        }
        return track.getPlaybackHeadPosition();
    }

    private AudioTrack ensureStarted() {
        if (audioTrack == null) {
            audioTrack = createTrack();
            if (audioTrack == null) {
                return null;
            }
        }
        AudioTrack track = audioTrack;
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            try {
                track.play();
            } catch (IllegalStateException e) {
                Log.e(TAG, "AudioTrack.play failed", e);
                return null;
            }
        }
        return track;
    }

    private AudioTrack createTrack() {
        int minBuf = minBufferBytes > 0 ? minBufferBytes : AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING);
        minBufferBytes = minBuf;
        if (minBuf <= 0) {
            Log.e(TAG, "invalid AudioTrack min buffer size");
            return null;
        }
        AudioTrack track = null;
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, CHANNEL_OUT, ENCODING, minBuf, AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize");
                track.release();
                return null;
            }
        } catch (Throwable t) {
            Log.e(TAG, "AudioTrack create failed", t);
            if (track != null) {
                track.release();
            }
            return null;
        }
        return track;
    }

    private void pauseFlush() {
        AudioTrack track = audioTrack;
        if (track == null) {
            return;
        }
        pauseFlushTrack(track);
    }

    private void pauseFlushTrack(AudioTrack track) {
        try {
            track.pause();
            track.flush();
        } catch (IllegalStateException e) {
            Log.e(TAG, "AudioTrack pause/flush failed", e);
        }
    }

    private void release() {
        AudioTrack track = audioTrack;
        audioTrack = null;
        if (track == null) {
            return;
        }
        try {
            track.pause();
            track.flush();
        } catch (IllegalStateException ignored) {
        }
        track.release();
    }
}
