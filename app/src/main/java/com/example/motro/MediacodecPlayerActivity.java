package com.example.motro;

import android.app.Activity;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class MediacodecPlayerActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "JavaMediaCodecDemo";
    private static final String DEFAULT_SOURCE = "rtsp://192.168.1.101:554/main.mov";
    private static final long CODEC_TIMEOUT_US = 10000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService decodeWorker = Executors.newSingleThreadExecutor(r -> new Thread(r, "JavaMediaCodecDecode"));
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    private final AtomicLong queuedInputCount = new AtomicLong(0);
    private final AtomicLong decodedOutputCount = new AtomicLong(0);
    private final AtomicLong renderedOutputCount = new AtomicLong(0);
    private final AtomicLong droppedOutputCount = new AtomicLong(0);
    private final AtomicLong inputBytes = new AtomicLong(0);
    private final AtomicLong startTimeMs = new AtomicLong(0);
    private final AtomicLong firstRenderTimeMs = new AtomicLong(0);
    private final AtomicLong lastRenderTimeMs = new AtomicLong(0);

    private SurfaceView surfaceView;
    private EditText sourceEditText;
    private Switch preferHardwareSwitch;
    private Switch lowLatencySwitch;
    private Switch realtimePaceSwitch;
    private TextView statsTextView;
    private TextView logTextView;

    private volatile Surface surface;
    private volatile boolean surfaceReady;
    private volatile String source = "";
    private volatile String mime = "";
    private volatile String decoderName = "";
    private volatile String selectedDecoderName = "";
    private volatile String outputFormatText = "";
    private volatile String lastError = "";
    private volatile String modeText = "idle";
    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile long durationUs;
    private volatile long sourceBitRate;
    private volatile boolean inputEos;
    private volatile boolean outputEos;

    private long lastStatsTimeMs;
    private long lastStatsDecoded;
    private long lastStatsRendered;
    private long lastStatsBytes;

    private final Runnable statsRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatsText();
            if (!destroyed.get()) {
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mediacodec_player);

        bindViews();
        initDefaults();
        bindActions();
        surfaceView.getHolder().addCallback(this);
        mainHandler.post(statsRunnable);
        appendLog("Java MediaCodec demo ready");
    }

    private void bindViews() {
        surfaceView = findViewById(R.id.mediacodec_surface_view);
        sourceEditText = findViewById(R.id.mediacodec_source_edit);
        preferHardwareSwitch = findViewById(R.id.mediacodec_prefer_hardware_switch);
        lowLatencySwitch = findViewById(R.id.mediacodec_low_latency_switch);
        realtimePaceSwitch = findViewById(R.id.mediacodec_realtime_pace_switch);
        statsTextView = findViewById(R.id.mediacodec_stats_text);
        logTextView = findViewById(R.id.mediacodec_log_text);
    }

    private void initDefaults() {
        sourceEditText.setText(DEFAULT_SOURCE);
        preferHardwareSwitch.setChecked(true);
        lowLatencySwitch.setChecked(true);
        realtimePaceSwitch.setChecked(true);
        surfaceView.setKeepScreenOn(true);
    }

    private void bindActions() {
        Button startButton = findViewById(R.id.mediacodec_start_button);
        Button stopButton = findViewById(R.id.mediacodec_stop_button);
        Button codecInfoButton = findViewById(R.id.mediacodec_codec_info_button);
        Button clearLogButton = findViewById(R.id.mediacodec_clear_log_button);

        startButton.setOnClickListener(v -> startDecode());
        stopButton.setOnClickListener(v -> stopDecode("user"));
        codecInfoButton.setOnClickListener(v -> appendLog(buildCodecCompatibilityReport()));
        clearLogButton.setOnClickListener(v -> logTextView.setText(""));
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        surface = holder.getSurface();
        surfaceReady = surface != null && surface.isValid();
        appendLog("surface created ready=" + surfaceReady);
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        surface = holder.getSurface();
        surfaceReady = surface != null && surface.isValid();
        appendLog("surface changed " + width + "x" + height);
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        surfaceReady = false;
        surface = null;
        stopDecode("surface destroyed");
    }

    private void startDecode() {
        hideKeyboard();
        if (running.get()) {
            appendLog("decoder already running");
            return;
        }
        Surface decodeSurface = surface;
        if (!surfaceReady || decodeSurface == null || !decodeSurface.isValid()) {
            toast("Surface is not ready");
            appendLog("start failed: surface is not ready");
            return;
        }
        String inputSource = sourceEditText.getText().toString().trim();
        if (TextUtils.isEmpty(inputSource)) {
            toast("Please enter a source URL or local path");
            return;
        }

        boolean preferHardware = preferHardwareSwitch.isChecked();
        boolean lowLatency = lowLatencySwitch.isChecked();
        boolean realtimePace = realtimePaceSwitch.isChecked();
        resetStats(inputSource, realtimePace);
        running.set(true);
        appendLog("start source=" + inputSource
                + " preferHardware=" + preferHardware
                + " lowLatency=" + lowLatency
                + " realtimePace=" + realtimePace);

        decodeWorker.execute(() -> decodeLoop(inputSource, decodeSurface, preferHardware, lowLatency, realtimePace));
    }

    private void stopDecode(String reason) {
        if (running.getAndSet(false)) {
            appendLog("stop requested reason=" + reason);
        }
    }

    private void decodeLoop(String inputSource,
                            Surface decodeSurface,
                            boolean preferHardware,
                            boolean lowLatency,
                            boolean realtimePace) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            openExtractor(extractor, inputSource);
            int videoTrack = selectVideoTrack(extractor);
            if (videoTrack < 0) {
                throw new IOException("video track not found");
            }

            extractor.selectTrack(videoTrack);
            MediaFormat format = extractor.getTrackFormat(videoTrack);
            mime = safeString(format, MediaFormat.KEY_MIME);
            if (TextUtils.isEmpty(mime)) {
                throw new IOException("video mime is empty");
            }
            videoWidth = safeInteger(format, MediaFormat.KEY_WIDTH);
            videoHeight = safeInteger(format, MediaFormat.KEY_HEIGHT);
            durationUs = safeLong(format, MediaFormat.KEY_DURATION);
            sourceBitRate = safeLong(format, MediaFormat.KEY_BIT_RATE);

            selectedDecoderName = selectDecoderName(mime, preferHardware);
            codec = TextUtils.isEmpty(selectedDecoderName)
                    ? MediaCodec.createDecoderByType(mime)
                    : MediaCodec.createByCodecName(selectedDecoderName);
            decoderName = codec.getName();
            configureLowLatency(format, lowLatency);
            codec.configure(format, decodeSurface, null, 0);
            codec.start();
            startTimeMs.set(System.currentTimeMillis());
            appendLog("decoder started name=" + decoderName
                    + " mime=" + mime
                    + " size=" + videoWidth + "x" + videoHeight);

            drainCodec(extractor, codec, realtimePace);
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            appendLog("decode error: " + lastError);
            mainHandler.post(() -> toast("MediaCodec failed: " + lastError));
        } finally {
            running.set(false);
            releaseCodec(codec);
            releaseExtractor(extractor);
            modeText = "stopped";
            updateStatsOnUi();
            appendLog("decode loop ended");
        }
    }

    private void drainCodec(MediaExtractor extractor, MediaCodec codec, boolean realtimePace) throws IOException {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        long firstPtsUs = Long.MIN_VALUE;
        long playbackStartNs = 0;

        while (running.get() && !outputEos) {
            if (!inputEos) {
                int inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                if (inputIndex >= 0) {
                    ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                    if (inputBuffer == null) {
                        throw new IOException("input buffer is null");
                    }
                    inputBuffer.clear();
                    int sampleSize = extractor.readSampleData(inputBuffer, 0);
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputEos = true;
                    } else {
                        long sampleTimeUs = extractor.getSampleTime();
                        if (sampleTimeUs < 0) {
                            sampleTimeUs = 0;
                        }
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0);
                        queuedInputCount.incrementAndGet();
                        inputBytes.addAndGet(sampleSize);
                        extractor.advance();
                    }
                }
            }

            int outputIndex = codec.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US);
            if (outputIndex >= 0) {
                boolean render = bufferInfo.size > 0 && running.get();
                if (render && realtimePace) {
                    if (firstPtsUs == Long.MIN_VALUE) {
                        firstPtsUs = bufferInfo.presentationTimeUs;
                        playbackStartNs = System.nanoTime();
                    }
                    waitForPresentationTime(bufferInfo.presentationTimeUs, firstPtsUs, playbackStartNs);
                }
                codec.releaseOutputBuffer(outputIndex, render);
                decodedOutputCount.incrementAndGet();
                if (render) {
                    long nowMs = System.currentTimeMillis();
                    if (firstRenderTimeMs.compareAndSet(0, nowMs)) {
                        appendLog("first frame rendered in " + firstFrameMs() + " ms");
                    }
                    lastRenderTimeMs.set(nowMs);
                    renderedOutputCount.incrementAndGet();
                } else {
                    droppedOutputCount.incrementAndGet();
                }
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputEos = true;
                }
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat outputFormat = codec.getOutputFormat();
                outputFormatText = outputFormat.toString();
                videoWidth = safeInteger(outputFormat, MediaFormat.KEY_WIDTH, videoWidth);
                videoHeight = safeInteger(outputFormat, MediaFormat.KEY_HEIGHT, videoHeight);
                appendLog("output format changed " + outputFormatText);
            }
        }
    }

    private void waitForPresentationTime(long ptsUs, long firstPtsUs, long playbackStartNs) {
        long frameDelayNs = Math.max(0, ptsUs - firstPtsUs) * 1000L;
        long dueNs = playbackStartNs + frameDelayNs;
        while (running.get()) {
            long sleepNs = dueNs - System.nanoTime();
            if (sleepNs <= 0) {
                return;
            }
            long sleepMs = Math.min(20, Math.max(1, sleepNs / 1000000L));
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void openExtractor(MediaExtractor extractor, String inputSource) throws IOException {
        Uri uri = Uri.parse(inputSource);
        try {
            if (uri.getScheme() != null) {
                extractor.setDataSource(this, uri, null);
                return;
            }
        } catch (Throwable firstError) {
            appendLog("setDataSource(Context,Uri) failed: " + firstError.getMessage());
        }
        extractor.setDataSource(inputSource);
    }

    private int selectVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String trackMime = safeString(format, MediaFormat.KEY_MIME);
            if (trackMime != null && trackMime.toLowerCase(Locale.US).startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private String selectDecoderName(String targetMime, boolean preferHardware) {
        MediaCodecInfo[] codecInfos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        String firstAny = "";
        String firstPreferred = "";
        for (MediaCodecInfo info : codecInfos) {
            if (info.isEncoder() || !supportsMime(info, targetMime)) {
                continue;
            }
            if (TextUtils.isEmpty(firstAny)) {
                firstAny = info.getName();
            }
            boolean hardware = isHardwareDecoder(info);
            if ((preferHardware && hardware) || (!preferHardware && !hardware)) {
                firstPreferred = info.getName();
                break;
            }
        }
        return TextUtils.isEmpty(firstPreferred) ? firstAny : firstPreferred;
    }

    private boolean supportsMime(MediaCodecInfo info, String targetMime) {
        for (String type : info.getSupportedTypes()) {
            if (targetMime.equalsIgnoreCase(type)) {
                return true;
            }
        }
        return false;
    }

    private boolean isHardwareDecoder(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated();
        }
        return !isSoftwareDecoderName(info.getName());
    }

    private boolean isSoftwareDecoderName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.US);
        return lower.contains("google")
                || lower.contains("software")
                || lower.contains(".sw.")
                || lower.contains("ffmpeg")
                || lower.startsWith("c2.android");
    }

    private void configureLowLatency(MediaFormat format, boolean lowLatency) {
        if (!lowLatency) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        }
    }

    private String buildCodecCompatibilityReport() {
        StringBuilder report = new StringBuilder();
        report.append("MediaCodec decoders\n");
        appendCodecReport(report, "video/avc");
        appendCodecReport(report, "video/hevc");
        return report.toString();
    }

    private void appendCodecReport(StringBuilder report, String targetMime) {
        report.append(targetMime).append('\n');
        MediaCodecInfo[] codecInfos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        int count = 0;
        for (MediaCodecInfo info : codecInfos) {
            if (info.isEncoder() || !supportsMime(info, targetMime)) {
                continue;
            }
            count++;
            report.append("  ")
                    .append(info.getName())
                    .append(isHardwareDecoder(info) ? " [hw]" : " [sw]");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isVendor()) {
                report.append(" [vendor]");
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(targetMime);
                    if (caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
                        report.append(" [low-latency]");
                    }
                } catch (Throwable ignored) {
                }
            }
            report.append('\n');
        }
        if (count == 0) {
            report.append("  none\n");
        }
    }

    private void resetStats(String inputSource, boolean realtimePace) {
        source = inputSource;
        mime = "";
        decoderName = "";
        selectedDecoderName = "";
        outputFormatText = "";
        lastError = "";
        modeText = realtimePace ? "realtime" : "benchmark";
        videoWidth = 0;
        videoHeight = 0;
        durationUs = 0;
        sourceBitRate = 0;
        inputEos = false;
        outputEos = false;
        queuedInputCount.set(0);
        decodedOutputCount.set(0);
        renderedOutputCount.set(0);
        droppedOutputCount.set(0);
        inputBytes.set(0);
        startTimeMs.set(0);
        firstRenderTimeMs.set(0);
        lastRenderTimeMs.set(0);
        lastStatsTimeMs = 0;
        lastStatsDecoded = 0;
        lastStatsRendered = 0;
        lastStatsBytes = 0;
    }

    private void updateStatsOnUi() {
        mainHandler.post(this::updateStatsText);
    }

    private void updateStatsText() {
        long nowMs = System.currentTimeMillis();
        long decoded = decodedOutputCount.get();
        long rendered = renderedOutputCount.get();
        long bytes = inputBytes.get();
        if (lastStatsTimeMs <= 0) {
            lastStatsTimeMs = nowMs;
            lastStatsDecoded = decoded;
            lastStatsRendered = rendered;
            lastStatsBytes = bytes;
        }

        long deltaMs = Math.max(1, nowMs - lastStatsTimeMs);
        double currentDecodeFps = perSecond(decoded - lastStatsDecoded, deltaMs);
        double currentRenderFps = perSecond(rendered - lastStatsRendered, deltaMs);
        double currentTransferKb = bytesPerSecondKb(bytes - lastStatsBytes, deltaMs);
        double currentBitrateKbps = bitrateKbps(bytes - lastStatsBytes, deltaMs);
        double elapsedSec = elapsedSeconds(nowMs);
        double avgRenderFps = elapsedSec > 0 ? rendered / elapsedSec : 0;

        lastStatsTimeMs = nowMs;
        lastStatsDecoded = decoded;
        lastStatsRendered = rendered;
        lastStatsBytes = bytes;

        String state = running.get() ? "running" : modeText;
        statsTextView.setText("state=" + state
                + " mode=" + modeText
                + "\nsource=" + source
                + "\nmime=" + emptyAsDash(mime)
                + " decoder=" + emptyAsDash(decoderName)
                + "\nsize=" + videoWidth + "x" + videoHeight
                + " firstFrame=" + firstFrameMs() + "ms"
                + " elapsed=" + formatSeconds(elapsedSec)
                + "\nrenderFps=" + formatOne(currentRenderFps)
                + " avg=" + formatOne(avgRenderFps)
                + " decodeFps=" + formatOne(currentDecodeFps)
                + "\nbitrate=" + formatKbps(currentBitrateKbps)
                + " transfer=" + formatKbPerSec(currentTransferKb)
                + " total=" + formatBytes(bytes)
                + "\nqueued=" + queuedInputCount.get()
                + " decoded=" + decoded
                + " rendered=" + rendered
                + " dropped=" + droppedOutputCount.get()
                + "\ninputEos=" + inputEos
                + " outputEos=" + outputEos
                + " sourceBitrate=" + formatSourceBitrate(sourceBitRate)
                + "\nerror=" + emptyAsDash(lastError));
    }

    private double elapsedSeconds(long nowMs) {
        long start = startTimeMs.get();
        if (start <= 0 || nowMs < start) {
            return 0;
        }
        return (nowMs - start) / 1000.0;
    }

    private long firstFrameMs() {
        long start = startTimeMs.get();
        long first = firstRenderTimeMs.get();
        if (start <= 0 || first < start) {
            return -1;
        }
        return first - start;
    }

    private double perSecond(long delta, long elapsedMs) {
        return Math.max(0, delta) * 1000.0 / Math.max(1, elapsedMs);
    }

    private double bitrateKbps(long deltaBytes, long elapsedMs) {
        return Math.max(0, deltaBytes) * 8.0 / Math.max(1, elapsedMs);
    }

    private double bytesPerSecondKb(long deltaBytes, long elapsedMs) {
        return Math.max(0, deltaBytes) * 1000.0 / Math.max(1, elapsedMs) / 1024.0;
    }

    private String formatOne(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private String formatSeconds(double value) {
        return String.format(Locale.US, "%.1fs", value);
    }

    private String formatKbps(double value) {
        if (value >= 1000.0) {
            return String.format(Locale.US, "%.2fMbps", value / 1000.0);
        }
        return String.format(Locale.US, "%.0fkbps", value);
    }

    private String formatKbPerSec(double value) {
        if (value >= 1024.0) {
            return String.format(Locale.US, "%.2fMB/s", value / 1024.0);
        }
        return String.format(Locale.US, "%.0fKB/s", value);
    }

    private String formatSourceBitrate(long bitRate) {
        if (bitRate <= 0) {
            return "--";
        }
        return formatKbps(bitRate / 1000.0);
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.2fMB", bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.1fKB", bytes / 1024.0);
        }
        return bytes + "B";
    }

    private String emptyAsDash(String value) {
        return TextUtils.isEmpty(value) ? "--" : value;
    }

    private String safeString(MediaFormat format, String key) {
        try {
            return format.containsKey(key) ? format.getString(key) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private int safeInteger(MediaFormat format, String key) {
        return safeInteger(format, key, 0);
    }

    private int safeInteger(MediaFormat format, String key, int fallback) {
        try {
            return format.containsKey(key) ? format.getInteger(key) : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private long safeLong(MediaFormat format, String key) {
        try {
            return format.containsKey(key) ? format.getLong(key) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void releaseCodec(MediaCodec codec) {
        if (codec == null) {
            return;
        }
        try {
            codec.stop();
        } catch (Throwable ignored) {
        }
        try {
            codec.release();
        } catch (Throwable ignored) {
        }
    }

    private void releaseExtractor(MediaExtractor extractor) {
        try {
            extractor.release();
        } catch (Throwable ignored) {
        }
    }

    private void appendLog(String message) {
        Log.d(TAG, message);
        mainHandler.post(() -> {
            String old = logTextView.getText().toString();
            String next = old + "\n" + message;
            if (next.length() > 16000) {
                next = next.substring(next.length() - 16000);
            }
            logTextView.setText(next.trim());
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed.set(true);
        mainHandler.removeCallbacks(statsRunnable);
        stopDecode("activity destroy");
        decodeWorker.shutdownNow();
        super.onDestroy();
    }
}
