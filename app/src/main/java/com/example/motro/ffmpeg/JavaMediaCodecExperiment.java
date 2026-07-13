package com.example.motro.ffmpeg;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class JavaMediaCodecExperiment {
    private static final String TAG = "JavaMediaCodecExperimen";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong queuedInputCount = new AtomicLong();
    private final AtomicLong decodedOutputCount = new AtomicLong();
    private final AtomicLong renderedOutputCount = new AtomicLong();
    private final AtomicLong droppedOutputCount = new AtomicLong();
    private final AtomicLong startTimeMs = new AtomicLong();
    private final AtomicLong firstRenderTimeMs = new AtomicLong();

    private Thread decodeThread;
    private String source = "";
    private String mime = "";
    private String decoderName = "";
    private String lastError = "";
    private int width;
    private int height;
    private volatile boolean inputEos;
    private volatile boolean outputEos;

    public synchronized String start(Context context, String sourceUrl, Surface surface) {
        if (running.get()) {
            return jsonError("Java MediaCodec experiment is already running");
        }
        if (context == null) {
            return jsonError("context is null");
        }
        if (TextUtils.isEmpty(sourceUrl)) {
            return jsonError("source is empty");
        }
        if (surface == null || !surface.isValid()) {
            return jsonError("surface is invalid");
        }

        resetStats(sourceUrl);
        running.set(true);

        decodeThread = new Thread(() -> decodeLoop(context.getApplicationContext(), sourceUrl, surface),
                "JavaMediaCodecExperiment");
        decodeThread.start();
        return "{\"success\":true,\"message\":\"Java MediaCodec experiment started\",\"source\":\""
                + escapeJson(sourceUrl) + "\"}";
    }

    public synchronized String stop() {
        if (!running.get()) {
            return "{\"success\":true,\"message\":\"Java MediaCodec experiment already stopped\"}";
        }
        running.set(false);
        Thread thread = decodeThread;
        if (thread != null) {
            try {
                thread.join(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return jsonError("interrupted while stopping Java MediaCodec experiment");
            }
        }
        decodeThread = null;
        return "{\"success\":true,\"message\":\"Java MediaCodec experiment stopped\"}";
    }

    public String getStats() {
        return "{\"success\":true,"
                + "\"running\":" + running.get() + ","
                + "\"source\":\"" + escapeJson(source) + "\","
                + "\"mime\":\"" + escapeJson(mime) + "\","
                + "\"decoderName\":\"" + escapeJson(decoderName) + "\","
                + "\"width\":" + width + ","
                + "\"height\":" + height + ","
                + "\"queuedInputCount\":" + queuedInputCount.get() + ","
                + "\"decodedOutputCount\":" + decodedOutputCount.get() + ","
                + "\"renderedOutputCount\":" + renderedOutputCount.get() + ","
                + "\"droppedOutputCount\":" + droppedOutputCount.get() + ","
                + "\"startTimeMs\":" + startTimeMs.get() + ","
                + "\"firstRenderTimeMs\":" + firstRenderTimeMs.get() + ","
                + "\"firstFrameRenderMs\":" + firstFrameRenderMs() + ","
                + "\"inputEos\":" + inputEos + ","
                + "\"outputEos\":" + outputEos + ","
                + "\"lastError\":\"" + escapeJson(lastError) + "\"}";
    }

    private void decodeLoop(Context context, String sourceUrl, Surface surface) {

        Log.d(TAG, "start   decodeLoop : "+sourceUrl);

        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(context, Uri.parse(sourceUrl), null);
            int videoTrackIndex = selectVideoTrack(extractor);
            if (videoTrackIndex < 0) {
                lastError = "video track not found";
                return;
            }

            extractor.selectTrack(videoTrackIndex);
            MediaFormat format = extractor.getTrackFormat(videoTrackIndex);
            mime = format.getString(MediaFormat.KEY_MIME);
            width = format.containsKey(MediaFormat.KEY_WIDTH) ? format.getInteger(MediaFormat.KEY_WIDTH) : 0;
            height = format.containsKey(MediaFormat.KEY_HEIGHT) ? format.getInteger(MediaFormat.KEY_HEIGHT) : 0;
            if (TextUtils.isEmpty(mime)) {
                lastError = "video mime is empty";
                return;
            }

            codec = MediaCodec.createDecoderByType(mime);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                decoderName = codec.getName();
            }
            codec.configure(format, surface, null, 0);
            codec.start();
            startTimeMs.set(System.currentTimeMillis());
            drainCodec(extractor, codec);
        } catch (Exception e) {

            lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            e.printStackTrace();
        } finally {
            running.set(false);
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                try {
                    codec.release();
                } catch (Throwable ignored) {
                }
            }
            try {
                extractor.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private int selectVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String trackMime = format.getString(MediaFormat.KEY_MIME);
            if (trackMime != null && trackMime.toLowerCase(Locale.US).startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private void drainCodec(MediaExtractor extractor, MediaCodec codec) throws IOException {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (running.get() && !outputEos) {
            if (!inputEos) {
                int inputIndex = codec.dequeueInputBuffer(5000);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                    if (inputBuffer == null) {
                        lastError = "MediaCodec input buffer is null";
                        break;
                    }
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEos = true;
                    } else {
                        long sampleTimeUs = extractor.getSampleTime();
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0);
                        queuedInputCount.incrementAndGet();
                        extractor.advance();
                    }
                }
            }

            int outputIndex = codec.dequeueOutputBuffer(bufferInfo, 5000);
            if (outputIndex >= 0) {
                boolean render = bufferInfo.size > 0 && running.get();
                codec.releaseOutputBuffer(outputIndex, render);
                decodedOutputCount.incrementAndGet();
                if (render) {
                    if (firstRenderTimeMs.get() == 0) {
                        firstRenderTimeMs.set(System.currentTimeMillis());
                    }
                    renderedOutputCount.incrementAndGet();
                } else {
                    droppedOutputCount.incrementAndGet();
                }
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputEos = true;
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outputFormat = codec.getOutputFormat();
                if (outputFormat.containsKey(MediaFormat.KEY_WIDTH)) {
                    width = outputFormat.getInteger(MediaFormat.KEY_WIDTH);
                }
                if (outputFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                    height = outputFormat.getInteger(MediaFormat.KEY_HEIGHT);
                }
            }
        }
    }

    private void resetStats(String sourceUrl) {
        source = sourceUrl;
        mime = "";
        decoderName = "";
        lastError = "";
        width = 0;
        height = 0;
        inputEos = false;
        outputEos = false;
        queuedInputCount.set(0);
        decodedOutputCount.set(0);
        renderedOutputCount.set(0);
        droppedOutputCount.set(0);
        startTimeMs.set(0);
        firstRenderTimeMs.set(0);
    }

    private long firstFrameRenderMs() {
        long start = startTimeMs.get();
        long firstRender = firstRenderTimeMs.get();
        if (start <= 0 || firstRender < start) {
            return -1;
        }
        return firstRender - start;
    }

    private String jsonError(String message) {
        return "{\"success\":false,\"errorMessage\":\"" + escapeJson(message) + "\"}";
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
