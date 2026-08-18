package com.example.motro.ffmpeg;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import java.nio.ByteBuffer;

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

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_OUT = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    // Accessed from the audio worker thread and the lifecycle control thread;
    // volatile gives safe reference publication. Release is always invoked after
    // the worker has been joined, so there is no write-vs-release race.
    private volatile AudioTrack audioTrack;
    private int minBufferBytes = -1;

    public LiveAudioPcmSink() {
    }

    /**
     * Called from the native audio output worker thread. Writes the PCM block
     * synchronously (WRITE_BLOCKING) and returns the number of bytes written, or
     * a negative AudioTrack error code. Creates/starts the AudioTrack lazily.
     * The caller must keep {@code pcm} alive until this method returns.
     */
    public int onAudioPcm(ByteBuffer pcm, int sizeBytes, long ptsUs) {
        AudioTrack track = ensureStarted();
        if (track == null) {
            return AudioTrack.ERROR_INVALID_OPERATION;
        }
        int total = 0;
        while (total < sizeBytes) {
            int written = track.write(pcm, sizeBytes - total, AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                return written;
            }
            if (written == 0) {
                break;
            }
            total += written;
        }
        return total;
    }

    /**
     * Called from the native lifecycle control path. Returns 0 on success or a
     * negative error code.
     */
    public int onAudioControl(int command) {
        switch (command) {
            case CMD_START:
                return ensureStarted() != null ? 0 : AudioTrack.ERROR_INVALID_OPERATION;
            case CMD_PAUSE_FLUSH:
                pauseFlush();
                return 0;
            case CMD_RELEASE:
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
