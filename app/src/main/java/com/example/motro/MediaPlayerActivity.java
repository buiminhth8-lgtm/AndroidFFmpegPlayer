package com.example.motro;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.motro.databinding.ActivityMediaPlayerBinding;
import com.example.motro.ffmpeg.FFmpegNative;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;

public class MediaPlayerActivity extends AppCompatActivity {

    private static final String TAG = "FFmpegPlayerDemo";
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_SEGMENT_SECONDS = 300;

    private ActivityMediaPlayerBinding binding;

    private final Object handleLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean playbackInfoRequestInFlight = new AtomicBoolean(false);
    private final Runnable playbackInfoRunnable = new Runnable() {
        @Override
        public void run() {
            updatePlaybackInfoAsync();
            if (!destroyed) {
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    private EditText urlEditText;
    private EditText timeoutEditText;
    private EditText recordPathEditText;
    private EditText segmentPatternEditText;
    private EditText recordFormatEditText;
    private EditText segmentDurationEditText;
    private EditText snapshotPathEditText;
    private Switch audioSwitch;
    private Switch reconnectSwitch;
    private Switch hardwareDecodeSwitch;
    private RadioGroup transportRadioGroup;
    private RadioGroup latencyModeRadioGroup;
    private TextView handleTextView;
    private TextView playbackInfoTextView;
    private TextView logTextView;

    private ExecutorService worker;
    private volatile Surface currentSurface;
    private volatile boolean surfaceReady;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;
    private volatile boolean destroyed;
    private long playerHandle;
    private long lastPlaybackInfoHandle;
    private long lastPlaybackInfoTimeMs;
    private long lastPlaybackInfoRenderedFrames;
    private long lastPlaybackInfoDecodedFrames;
    private long lastPlaybackInfoVideoBytes;
    private long lastPlaybackInfoInputBytes;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMediaPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "FFmpegDemoWorker"));
        bindViews();
        initDefaults();
        bindPreviewCallback();
        bindActions();
        startPlaybackInfoUpdates();
        appendLog("Demo ready. Tap Create/Info/Prepare to load FFmpeg native libraries.");
    }

    private void bindViews() {
        binding.playerPreviewView.setKeepScreenOn(true);
        urlEditText = findViewById(R.id.urlEditText);
        timeoutEditText = findViewById(R.id.timeoutEditText);
        recordPathEditText = findViewById(R.id.recordPathEditText);
        segmentPatternEditText = findViewById(R.id.segmentPatternEditText);
        recordFormatEditText = findViewById(R.id.recordFormatEditText);
        segmentDurationEditText = findViewById(R.id.segmentDurationEditText);
        snapshotPathEditText = findViewById(R.id.snapshotPathEditText);
        audioSwitch = findViewById(R.id.audioSwitch);
        reconnectSwitch = findViewById(R.id.reconnectSwitch);
        hardwareDecodeSwitch = findViewById(R.id.hardwareDecodeSwitch);
        transportRadioGroup = findViewById(R.id.transportRadioGroup);
        latencyModeRadioGroup = findViewById(R.id.latencyModeRadioGroup);
        handleTextView = findViewById(R.id.handleTextView);
        playbackInfoTextView = findViewById(R.id.playbackInfoTextView);
        logTextView = findViewById(R.id.logTextView);
    }

    private void initDefaults() {
        urlEditText.setText("rtsp://192.168.1.101:554/main.mov");
        timeoutEditText.setText(String.valueOf(DEFAULT_TIMEOUT_MS));
        audioSwitch.setChecked(false);
        reconnectSwitch.setChecked(true);
        hardwareDecodeSwitch.setChecked(false);
        transportRadioGroup.check(R.id.tcpTransportRadio);
        latencyModeRadioGroup.check(R.id.balancedLatencyRadio);
        recordPathEditText.setText(defaultFilePath("record_av_test.mp4"));
        segmentPatternEditText.setText(defaultFilePath("record_segment_%03d.mp4"));
        recordFormatEditText.setText("mp4");
        segmentDurationEditText.setText("300");
        snapshotPathEditText.setText(defaultFilePath("snapshot.png"));
        updateHandleLabel();
    }

    private void bindPreviewCallback() {
        binding.playerPreviewView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                updateSurfaceFromHolder(holder, binding.playerPreviewView.getWidth(), binding.playerPreviewView.getHeight());
                bindSurfaceForExistingPlayer("Surface Created");
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                updateSurfaceFromHolder(holder, width, height);
                bindSurfaceForExistingPlayer("Surface Changed");
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                surfaceReady = false;
                surfaceWidth = 0;
                surfaceHeight = 0;
                currentSurface = null;
                long handle = getPlayerHandle();
                if (handle != 0) {
                    runNative("Clear Surface", () -> FFmpegNative.clearPlayerSurface(handle));
                }
            }
        });
    }

    private void updateSurfaceFromHolder(@NonNull SurfaceHolder holder, int width, int height) {
        Surface surface = holder.getSurface();
        currentSurface = surface;
        surfaceReady = surface != null && surface.isValid();
        surfaceWidth = width;
        surfaceHeight = height;
    }

    private void bindActions() {
        findViewById(R.id.createButton).setOnClickListener(v -> runNative("Create Player", () -> {
            boolean newlyCreated = getPlayerHandle() == 0;
            long handle = ensurePlayer();
            String surfaceResult = bindSurfaceIfReady(handle);
            String transportResult = applyRtspTransport(handle);
            String latencyResult = applyLatencyMode(handle);
            String reconnectResult = applyReconnectOptions(handle);
            String audioResult = applyAudioOption(handle);
            String decodeResult = newlyCreated
                    ? applyDecodeModeOption(handle)
                    : "{\"success\":true,\"message\":\"player already exists, decode mode unchanged until next prepare\"}";
            return "{\"success\":true,\"handle\":" + handle + "}"
                    + "\nsurface=" + surfaceResult
                    + "\ntransport=" + transportResult
                    + "\nlatency=" + latencyResult
                    + "\nreconnect=" + reconnectResult
                    + "\naudio=" + audioResult
                    + "\ndecode=" + decodeResult;
        }));

        findViewById(R.id.infoButton).setOnClickListener(v -> runNative("FFmpeg Info", () ->
                "version=" + FFmpegNative.getFFmpegVersion()
                        + "\nbuildConfig=" + FFmpegNative.getFFmpegBuildConfig()
                        + "\ndecoders=" + FFmpegNative.getAvailableDecoders()
                        + "\nmediaCodec=" + FFmpegNative.getMediaCodecInfo()));

        findViewById(R.id.probeButton).setOnClickListener(v -> runNative("Probe", () ->
                FFmpegNative.probe(requireUrl(), readTimeoutMs())));

        findViewById(R.id.prepareButton).setOnClickListener(v -> runNative("Prepare", () -> {
            long handle = ensurePlayer();
            String surfaceResult = bindSurfaceIfReady(handle);
            String transportResult = applyRtspTransport(handle);
            String latencyResult = applyLatencyMode(handle);
            String reconnectResult = applyReconnectOptions(handle);
            String audioResult = applyAudioOption(handle);
            String decodeResult = applyDecodeModeOption(handle);
            String prepareResult = FFmpegNative.preparePlayer(handle, requireUrl(), readTimeoutMs());
            return "surface=" + surfaceResult
                    + "\ntransport=" + transportResult
                    + "\nlatency=" + latencyResult
                    + "\nreconnect=" + reconnectResult
                    + "\naudio=" + audioResult
                    + "\ndecode=" + decodeResult
                    + "\nprepare=" + prepareResult;
        }));

        findViewById(R.id.startButton).setOnClickListener(v -> runNative("Start", () -> {
            long handle = ensurePlayer();
            String surfaceResult = bindSurfaceIfReady(handle);
            String audioResult = applyAudioOption(handle);
            return "surface=" + surfaceResult
                    + "\naudio=" + audioResult
                    + "\nstart=" + FFmpegNative.startPlayer(handle);
        }));

        findViewById(R.id.pauseButton).setOnClickListener(v -> runNative("Pause", () ->
                FFmpegNative.pausePlayer(requireHandle())));

        findViewById(R.id.stopButton).setOnClickListener(v -> runNative("Stop", () ->
                FFmpegNative.stopPlayer(requireHandle())));

        findViewById(R.id.releaseButton).setOnClickListener(v -> runNative("Release", () -> {
            long handle = takePlayerHandle();
            if (handle == 0) {
                return jsonError("player handle is 0");
            }
            resetPlaybackInfoCounters();
            return FFmpegNative.releasePlayer(handle);
        }));

        findViewById(R.id.snapshotButton).setOnClickListener(v -> runNative("Snapshot", () ->
                takePlayerSnapshotCompat(requireHandle(), requireSnapshotPath())));

        findViewById(R.id.startRecordButton).setOnClickListener(v -> runNative("Start Record", () ->
                FFmpegNative.startPlayerRecordWithConfig(requireHandle(), requireRecordPath(), requireRecordFormat(), 0)));

        findViewById(R.id.startSegmentRecordButton).setOnClickListener(v -> runNative("Start Segment Record", () ->
                FFmpegNative.startPlayerRecordWithConfig(requireHandle(), requireSegmentPattern(), requireRecordFormat(), requireSegmentDurationSec())));

        findViewById(R.id.stopRecordButton).setOnClickListener(v -> runNative("Stop Record", () ->
                FFmpegNative.stopPlayerRecord(requireHandle())));

        findViewById(R.id.recordStateButton).setOnClickListener(v -> runNative("Record State", () ->
                FFmpegNative.getPlayerRecordState(requireHandle())));

        findViewById(R.id.stateButton).setOnClickListener(v -> runNative("Player State", () ->
                FFmpegNative.getPlayerState(requireHandle())));

        findViewById(R.id.statsButton).setOnClickListener(v -> runNative("Player Stats", () ->
                FFmpegNative.getPlayerStats(requireHandle())));

        findViewById(R.id.reconnectStateButton).setOnClickListener(v -> runNative("Reconnect/Latency State", () ->
                FFmpegNative.getPlayerReconnectState(requireHandle())
                        + "\nlatency=" + FFmpegNative.getPlayerLatencyConfig(requireHandle())));

        findViewById(R.id.clearSurfaceButton).setOnClickListener(v -> runNative("Clear Surface", () ->
                FFmpegNative.clearPlayerSurface(requireHandle())));

        findViewById(R.id.clearLogButton).setOnClickListener(v -> logTextView.setText(""));

        audioSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            long handle = getPlayerHandle();
            if (handle != 0) {
                runNative("Audio Option", () -> applyAudioOption(handle));
            }
        });
        reconnectSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            long handle = getPlayerHandle();
            if (handle != 0) {
                runNative("Reconnect Option", () -> applyReconnectOptions(handle));
            }
        });
        transportRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            long handle = getPlayerHandle();
            if (handle != 0) {
                runNative("RTSP Transport", () -> applyRtspTransport(handle));
            }
        });
        latencyModeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            long handle = getPlayerHandle();
            if (handle != 0) {
                runNative("Latency Mode", () -> applyLatencyMode(handle));
            }
        });
    }

    private void bindSurfaceForExistingPlayer(String title) {
        long handle = getPlayerHandle();
        if (handle != 0) {
            runNative(title, () -> bindSurfaceIfReady(handle));
        } else {
            appendLog(title + ": no player yet");
        }
    }

    private void startPlaybackInfoUpdates() {
        resetPlaybackInfoCounters();
        playbackInfoTextView.setText("等待播放");
        mainHandler.post(playbackInfoRunnable);
    }

    private void updatePlaybackInfoAsync() {
        long handle = getPlayerHandle();
        ExecutorService statsWorker = worker;
        if (destroyed || statsWorker == null) {
            return;
        }
        if (handle == 0) {
            resetPlaybackInfoCounters();
            playbackInfoTextView.setText("等待播放");
            return;
        }
        if (!playbackInfoRequestInFlight.compareAndSet(false, true)) {
            return;
        }
        try {
            statsWorker.execute(() -> {
                String statsJson;
                try {
                    statsJson = FFmpegNative.getPlayerStats(handle);
                } catch (Throwable t) {
                    statsJson = jsonError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                }
                String finalStatsJson = statsJson;
                mainHandler.post(() -> {
                    playbackInfoRequestInFlight.set(false);
                    if (!destroyed && handle == getPlayerHandle()) {
                        updatePlaybackInfoFromStats(handle, finalStatsJson);
                    }
                });
            });
        } catch (Throwable t) {
            playbackInfoRequestInFlight.set(false);
        }
    }

    private void updatePlaybackInfoFromStats(long handle, String statsJson) {
        try {
            JSONObject stats = new JSONObject(statsJson);
            if (!stats.optBoolean("success", false)) {
                playbackInfoTextView.setText("播放信息不可用");
                return;
            }

            long nowMs = System.currentTimeMillis();
            long renderedFrames = stats.optLong("renderedFrameCount", 0);
            long decodedFrames = stats.optLong("hardwareDecodedFrameCount", 0)
                    + stats.optLong("softwareDecodedFrameCount", 0);
            if (decodedFrames <= 0) {
                decodedFrames = stats.optLong("videoFrameCount", 0);
            }
            long videoBytes = stats.optLong("videoPacketBytes", 0);
            long inputBytes = stats.optLong("inputPacketBytes", 0);
            if (handle != lastPlaybackInfoHandle || lastPlaybackInfoTimeMs <= 0) {
                lastPlaybackInfoHandle = handle;
                lastPlaybackInfoTimeMs = nowMs;
                lastPlaybackInfoRenderedFrames = renderedFrames;
                lastPlaybackInfoDecodedFrames = decodedFrames;
                lastPlaybackInfoVideoBytes = videoBytes;
                lastPlaybackInfoInputBytes = inputBytes;
            }

            long elapsedMs = Math.max(1, nowMs - lastPlaybackInfoTimeMs);
            double renderFps = ratePerSecond(renderedFrames - lastPlaybackInfoRenderedFrames, elapsedMs);
            double decodeFps = ratePerSecond(decodedFrames - lastPlaybackInfoDecodedFrames, elapsedMs);
            double videoKbps = bitrateKbps(videoBytes - lastPlaybackInfoVideoBytes, elapsedMs);
            double transferKbPerSec = bytesPerSecondKb(inputBytes - lastPlaybackInfoInputBytes, elapsedMs);

            lastPlaybackInfoTimeMs = nowMs;
            lastPlaybackInfoRenderedFrames = renderedFrames;
            lastPlaybackInfoDecodedFrames = decodedFrames;
            lastPlaybackInfoVideoBytes = videoBytes;
            lastPlaybackInfoInputBytes = inputBytes;

            String state = stats.optString("state", "unknown");
            String mode = stats.optString("renderMode", "unknown");
            String codec = stats.optString("actualDecoderName", stats.optString("videoCodecName", ""));
            String frameFormat = stats.optString("frameFormat", "");
            long dropped = stats.optLong("droppedVideoFrameCount", 0);
            long videoBitRate = stats.optLong("videoBitRate", 0);
            long streamBitRate = stats.optLong("streamBitRate", 0);
            String nominalBitrate = videoBitRate > 0
                    ? formatKbps(videoBitRate / 1000.0)
                    : (streamBitRate > 0 ? formatKbps(streamBitRate / 1000.0) : "--");

            playbackInfoTextView.setText(
                    "状态 " + state
                            + " | " + mode
                            + " | " + codec
                            + "\n解码 " + formatFps(decodeFps)
                            + " fps  渲染 " + formatFps(renderFps)
                            + " fps  丢帧 " + dropped
                            + "\n码率 " + formatKbps(videoKbps)
                            + "  传输 " + formatKbPerSec(transferKbPerSec)
                            + "  标称 " + nominalBitrate
                            + "\n格式 " + frameFormat
                            + "  包 " + stats.optLong("readPacketCount", 0)
                            + "  帧 " + renderedFrames);
        } catch (Throwable t) {
            playbackInfoTextView.setText("播放信息解析失败");
        }
    }

    private void resetPlaybackInfoCounters() {
        lastPlaybackInfoHandle = 0;
        lastPlaybackInfoTimeMs = 0;
        lastPlaybackInfoRenderedFrames = 0;
        lastPlaybackInfoDecodedFrames = 0;
        lastPlaybackInfoVideoBytes = 0;
        lastPlaybackInfoInputBytes = 0;
    }

    private double ratePerSecond(long deltaCount, long elapsedMs) {
        return Math.max(0, deltaCount) * 1000.0 / Math.max(1, elapsedMs);
    }

    private double bitrateKbps(long deltaBytes, long elapsedMs) {
        return Math.max(0, deltaBytes) * 8.0 / Math.max(1, elapsedMs);
    }

    private double bytesPerSecondKb(long deltaBytes, long elapsedMs) {
        return Math.max(0, deltaBytes) * 1000.0 / Math.max(1, elapsedMs) / 1024.0;
    }

    private String formatFps(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private String formatKbps(double value) {
        if (value >= 1000.0) {
            return String.format(Locale.US, "%.2f Mbps", value / 1000.0);
        }
        return String.format(Locale.US, "%.0f kbps", value);
    }

    private String formatKbPerSec(double value) {
        if (value >= 1024.0) {
            return String.format(Locale.US, "%.2f MB/s", value / 1024.0);
        }
        return String.format(Locale.US, "%.0f KB/s", value);
    }

    private long ensurePlayer() {
        synchronized (handleLock) {
            if (destroyed) {
                return 0;
            }
            if (playerHandle == 0) {
                playerHandle = FFmpegNative.createPlayer();
                Log.d(TAG, "createPlayer handle=" + playerHandle);
                postHandleLabel();
            }
            return playerHandle;
        }
    }

    private long getPlayerHandle() {
        synchronized (handleLock) {
            return playerHandle;
        }
    }

    private long takePlayerHandle() {
        synchronized (handleLock) {
            long handle = playerHandle;
            playerHandle = 0;
            postHandleLabel();
            return handle;
        }
    }

    private long requireHandle() {
        long handle = getPlayerHandle();
        if (handle == 0) {
            throw new IllegalStateException("player handle is 0, tap Create or Prepare first");
        }
        return handle;
    }

    private String bindSurfaceIfReady(long handle) {
        if (handle == 0) {
            return jsonError("player handle is 0");
        }
        Surface surface = currentSurface;
        if (!surfaceReady || surface == null || !surface.isValid()) {
            return jsonError("surface is not ready");
        }
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            return jsonError("surface size is not ready");
        }
        Log.d(TAG, "bind surface viewSize=" + surfaceWidth + "x" + surfaceHeight);
        return FFmpegNative.setPlayerSurface(handle, surface);
    }

    private String applyAudioOption(long handle) {
        if (handle == 0) {
            return jsonError("player handle is 0");
        }
        return FFmpegNative.enableAudio(handle, audioSwitch.isChecked());
    }

    private String applyReconnectOptions(long handle) {
        if (handle == 0) {
            return jsonError("player handle is 0");
        }
        return FFmpegNative.setPlayerReconnectOptions(handle, reconnectSwitch.isChecked(), 3, 1000);
    }

    private String applyRtspTransport(long handle) {
        if (handle == 0) {
            return jsonError("player handle is 0");
        }
        return FFmpegNative.setRtspTransport(handle, selectedRtspTransport());
    }

    private String applyLatencyMode(long handle) {
        if (handle == 0) {
            return jsonError("player handle is 0");
        }
        return FFmpegNative.setPlayerLatencyMode(handle, selectedLatencyMode());
    }

    private String applyDecodeModeOption(long handle) {
        if (handle == 0) {
            return jsonError("player handle is 0");
        }
        boolean hardwareDecode = hardwareDecodeSwitch.isChecked();
        String decodeResult = FFmpegNative.setHardwareDecode(handle, hardwareDecode);
        String renderMode = hardwareDecode ? "mediacodec_surface" : "software_rgba";
        String renderModeResult = FFmpegNative.setHardwareRenderMode(handle, renderMode);
        return "hardwareDecode=" + decodeResult
                + "\nrenderMode=" + renderModeResult;
    }

    private String selectedRtspTransport() {
        int checkedId = transportRadioGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.udpTransportRadio) {
            return "udp";
        }
        if (checkedId == R.id.autoTransportRadio) {
            return "auto";
        }
        return "tcp";
    }

    private String selectedLatencyMode() {
        int checkedId = latencyModeRadioGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.lowLatencyUltraRadio) {
            return "ultra_low_latency";
        }
        if (checkedId == R.id.lowLatencyRadio) {
            return "low_latency";
        }
        if (checkedId == R.id.stableLatencyRadio) {
            return "stable";
        }
        return "balanced";
    }

    private String requireUrl() {
        String url = urlEditText.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            throw new IllegalArgumentException("Please enter RTSP/URL");
        }
        return url;
    }

    private int readTimeoutMs() {
        String value = timeoutEditText.getText().toString().trim();
        if (TextUtils.isEmpty(value)) {
            return DEFAULT_TIMEOUT_MS;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("timeoutMs must be a number");
        }
    }

    private String requireRecordPath() {
        String path = recordPathEditText.getText().toString().trim();
        if (TextUtils.isEmpty(path)) {
            path = defaultFilePath("record_av_test.ts");
            recordPathEditText.setText(path);
        }
        ensureParentExists(path);
        return path;
    }

    private String requireSegmentPattern() {
        String pattern = segmentPatternEditText.getText().toString().trim();
        if (TextUtils.isEmpty(pattern)) {
            pattern = defaultFilePath("record_segment_%03d.ts");
            segmentPatternEditText.setText(pattern);
        }
        ensureParentExists(pattern.replace("%03d", "000"));
        return pattern;
    }

    private String requireRecordFormat() {
        String format = recordFormatEditText.getText().toString().trim();
        if (TextUtils.isEmpty(format)) {
            return "auto";
        }
        return format;
    }

    private int requireSegmentDurationSec() {
        String value = segmentDurationEditText.getText().toString().trim();
        if (TextUtils.isEmpty(value)) {
            return DEFAULT_SEGMENT_SECONDS;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("segment duration must be a number");
        }
    }

    private String requireSnapshotPath() {
        String path = snapshotPathEditText.getText().toString().trim();
        if (TextUtils.isEmpty(path)) {
            path = defaultFilePath("snapshot.png");
            snapshotPathEditText.setText(path);
        }
        ensureParentExists(path);
        return path;
    }

    private String takePlayerSnapshotCompat(long handle, String outputPath) throws Exception {
        String nativeResult = FFmpegNative.takePlayerSnapshot(handle, outputPath);
        if (nativeResult != null && nativeResult.contains("\"success\":true")) {
            return nativeResult;
        }
        if (nativeResult == null
                || !nativeResult.contains("Snapshot is not supported")) {
            return nativeResult;
        }
        return takeSurfaceSnapshotWithPixelCopy(outputPath, nativeResult);
    }

    private String takeSurfaceSnapshotWithPixelCopy(String outputPath, String nativeResult) throws Exception {
        Surface surface = currentSurface;
        if (!surfaceReady || surface == null || !surface.isValid()) {
            return jsonError("PixelCopy snapshot failed: surface is not ready")
                    + "\nnativeSnapshot=" + nativeResult;
        }
        int width = surfaceWidth;
        int height = surfaceHeight;
        if (width <= 0 || height <= 0) {
            return jsonError("PixelCopy snapshot failed: surface size is not ready")
                    + "\nnativeSnapshot=" + nativeResult;
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger copyResult = new AtomicInteger(PixelCopy.ERROR_UNKNOWN);
        mainHandler.post(() -> PixelCopy.request(surface, bitmap, result -> {
            copyResult.set(result);
            latch.countDown();
        }, mainHandler));

        if (!latch.await(1500, TimeUnit.MILLISECONDS)) {
            bitmap.recycle();
            return jsonError("PixelCopy snapshot timed out")
                    + "\nnativeSnapshot=" + nativeResult;
        }
        if (copyResult.get() != PixelCopy.SUCCESS) {
            bitmap.recycle();
            return jsonError("PixelCopy snapshot failed result=" + copyResult.get())
                    + "\nnativeSnapshot=" + nativeResult;
        }

        File outputFile = new File(outputPath);
        Bitmap.CompressFormat format = isJpegPath(outputPath)
                ? Bitmap.CompressFormat.JPEG
                : Bitmap.CompressFormat.PNG;
        try (OutputStream output = new FileOutputStream(outputFile)) {
            if (!bitmap.compress(format, 95, output)) {
                bitmap.recycle();
                return jsonError("PixelCopy snapshot encode failed")
                        + "\nnativeSnapshot=" + nativeResult;
            }
        } finally {
            bitmap.recycle();
        }

        return "{\"success\":true,"
                + "\"message\":\"snapshot saved by PixelCopy\","
                + "\"outputPath\":\"" + escapeJson(outputFile.getAbsolutePath()) + "\","
                + "\"width\":" + width + ","
                + "\"height\":" + height + ","
                + "\"format\":\"" + (format == Bitmap.CompressFormat.JPEG ? "jpg" : "png") + "\","
                + "\"source\":\"pixelcopy\","
                + "\"nativeSnapshot\":\"" + escapeJson(nativeResult) + "\"}";
    }

    private boolean isJpegPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg");
    }

    private void ensureParentExists(String path) {
        File parent = new File(path).getParentFile();
        if (parent == null || !parent.exists()) {
            throw new IllegalArgumentException("Parent directory does not exist: " + path);
        }
    }

    private String defaultFilePath(String fileName) {
        File dir = getExternalFilesDir(null);
        if (dir == null) {
            dir = getFilesDir();
        }
        return new File(dir, fileName).getAbsolutePath();
    }

    private void clearSurfaceReferenceOnly() {
        currentSurface = null;
        surfaceReady = false;
        surfaceWidth = 0;
        surfaceHeight = 0;
    }

    private void runNative(String title, NativeAction action) {
        if (destroyed || worker == null) {
            return;
        }
        hideKeyboard();
        appendLog(">>> " + title);
        worker.execute(() -> {
            String result;
            try {
                result = action.run();
            } catch (Throwable t) {
                Log.e(TAG, title + " failed", t);
                result = jsonError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
            String finalResult = result;
            mainHandler.post(() -> {
                appendLog(title + "\n" + finalResult);
                if (!destroyed && finalResult.contains("\"success\":false")) {
                    Toast.makeText(this, title + " failed", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    private void appendLog(String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        String old = logTextView.getText().toString();
        String next = old + "\n[" + time + "] " + message + "\n";
        if (next.length() > 20000) {
            next = next.substring(next.length() - 20000);
        }
        logTextView.setText(next.trim());
    }

    private void postHandleLabel() {
        mainHandler.post(this::updateHandleLabel);
    }

    private void updateHandleLabel() {
        handleTextView.setText("handle: " + getPlayerHandle());
    }

    private String jsonError(String message) {
        return "{\"success\":false,\"errorCode\":-1,\"errorMessage\":\"" + escapeJson(message) + "\"}";
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(playbackInfoRunnable);
        playbackInfoRequestInFlight.set(false);
        long handle = takePlayerHandle();
        ExecutorService releaseWorker = worker;
        worker = null;
        if (releaseWorker != null) {
            releaseWorker.execute(() -> {
                if (handle != 0) {
                    Log.d(TAG, "onDestroy stop=" + FFmpegNative.stopPlayer(handle));
                    Log.d(TAG, "onDestroy clearSurface=" + FFmpegNative.clearPlayerSurface(handle));
                    Log.d(TAG, "onDestroy release=" + FFmpegNative.releasePlayer(handle));
                }
                clearSurfaceReferenceOnly();
            });
            releaseWorker.shutdown();
        } else {
            clearSurfaceReferenceOnly();
        }
        super.onDestroy();
    }

    private interface NativeAction {
        String run() throws Exception;
    }
}
