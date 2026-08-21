package com.example.motro;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.motro.databinding.ActivityMediaPlayerBinding;
import com.example.motro.databinding.ViewMediaPlayerControlsBinding;
import com.example.motro.ffmpeg.FFmpegNative;
import com.example.motro.ffmpeg.FFmpegPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;

public class MediaPlayerActivity extends AppCompatActivity {

    private static final String TAG = "FFmpegPlayer";
    private static final String TAG_STATS = "FFmpegPlayerStats";
    private static final String TAG_AUDIO_LIFECYCLE = "FFmpegAudioLifecycle";
    private static final String TAG_EVENT = "FFmpegPlayerEvent";
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_SEGMENT_SECONDS = 300;
    private static final float MIN_WINDOW_SPAN = 0.01f;
    private static final String SNAPSHOT_REQUIRES_SURFACE_CAPTURE = "SNAPSHOT_REQUIRES_SURFACE_CAPTURE";
    public static final String EXTRA_URL = "com.example.motro.extra.URL";
    public static final String EXTRA_HARDWARE_DECODE = "com.example.motro.extra.HARDWARE_DECODE";
    public static final String EXTRA_RTSP_TRANSPORT = "com.example.motro.extra.RTSP_TRANSPORT";
    public static final String EXTRA_LATENCY_MODE = "com.example.motro.extra.LATENCY_MODE";
    public static final String EXTRA_RENDER_MODE = "com.example.motro.extra.RENDER_MODE";

    private ActivityMediaPlayerBinding binding;
    private ViewMediaPlayerControlsBinding controlsBinding;

    private final Object handleLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean playbackInfoRequestInFlight = new AtomicBoolean(false);
    private final AtomicInteger surfaceGeneration = new AtomicInteger();
    private final Runnable playbackInfoRunnable = new Runnable() {
        @Override
        public void run() {
            updatePlaybackInfoAsync();
            if (!destroyed) {
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    private EditText segmentDurationEditText;
    private EditText snapshotPathEditText;
    private Switch audioSwitch;
    private Switch reconnectSwitch;
    private Switch hardwareDecodeSwitch;
    private RadioGroup transportRadioGroup;
    private RadioGroup latencyModeRadioGroup;
    private TextView playbackInfoTextView;
    private int statsLogCounter;
    private long latencyStatsSeq;

    private ExecutorService worker;
    private volatile Surface currentSurface;
    private volatile boolean surfaceReady;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;
    private volatile boolean destroyed;
    private FFmpegPlayer player;
    private FFmpegPlayer lastPlaybackInfoPlayer;
    private long lastPlaybackInfoTimeMs;
    private long lastPlaybackInfoRenderedFrames;
    private long lastPlaybackInfoDecodedFrames;
    private long lastPlaybackInfoVideoBytes;
    private long lastPlaybackInfoInputBytes;
    private volatile String lastPlayerEventText = "";

    private int thermalPalette = FFmpegNative.THERMAL_PALETTE_WHITE_HOT;
    private float thermalGamma = 1.0f;
    private float thermalBlackPoint = 0.0f;
    private float thermalWhitePoint = 1.0f;
    private boolean thermalUiUpdating;
    private volatile String currentRenderMode = "";
    private String intentRenderMode = "";

    private final FFmpegPlayer.Listener playerEventListener = (event, eventJson) -> {
        mainHandler.post(() -> {
            FFmpegPlayer current;
            synchronized (handleLock) {
                current = player;
            }
            if (destroyed || current == null) {
                return;
            }
            lastPlayerEventText = event;
            Log.d(TAG_EVENT, "event=" + event + "\n" + eventJson);
        });
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMediaPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        controlsBinding = binding.playerControlPanel;
        worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "FFmpegDemoWorker"));
        bindViews();
        initDefaults();
        bindPreviewCallback();
        bindActions();
        startPlaybackInfoUpdates();
        logDebug("Demo ready. Tap Create/Info/Prepare to load FFmpeg native libraries.");
    }

    private void bindViews() {
        binding.playerPreviewView.setKeepScreenOn(true);


        segmentDurationEditText = findViewById(R.id.segmentDurationEditText);
        snapshotPathEditText = findViewById(R.id.snapshotPathEditText);
        audioSwitch = findViewById(R.id.audioSwitch);
        reconnectSwitch = findViewById(R.id.reconnectSwitch);
        hardwareDecodeSwitch = findViewById(R.id.hardwareDecodeSwitch);
        transportRadioGroup = findViewById(R.id.transportRadioGroup);
        latencyModeRadioGroup = findViewById(R.id.latencyModeRadioGroup);
        playbackInfoTextView = findViewById(R.id.playbackInfoTextView);
    }

    private void initDefaults() {
        String initialUrl = getIntent().getStringExtra(EXTRA_URL);
        controlsBinding.urlEditText.setText(TextUtils.isEmpty(initialUrl) ? "rtsp://192.168.1.101:556/main.mov" : initialUrl);
        controlsBinding.timeoutEditText.setText(String.valueOf(DEFAULT_TIMEOUT_MS));
        audioSwitch.setChecked(false);
        reconnectSwitch.setChecked(true);
        hardwareDecodeSwitch.setChecked(getIntent().getBooleanExtra(EXTRA_HARDWARE_DECODE, false));
        transportRadioGroup.check(R.id.tcpTransportRadio);
        latencyModeRadioGroup.check(R.id.balancedLatencyRadio);
        applyIntentPlaybackDefaults();
        controlsBinding.recordPathEditText.setText(defaultFilePath("record_av_test.mp4"));
        controlsBinding.segmentPatternEditText.setText(defaultFilePath("record_segment_%03d.mp4"));
        controlsBinding.recordFormatEditText.setText("mp4");
        segmentDurationEditText.setText("300");
        snapshotPathEditText.setText(defaultFilePath("snapshot.png"));
        intentRenderMode = getIntent().getStringExtra(EXTRA_RENDER_MODE);
        controlsBinding.thermalEnabledSwitch.setChecked(false);
        controlsBinding.thermalPaletteRadioGroup.check(R.id.thermalWhiteHotRadio);
        controlsBinding.thermalAgcSwitch.setChecked(false);
        controlsBinding.thermalGammaSeekBar.setProgress(50);
        controlsBinding.thermalBlackPointSeekBar.setProgress(0);
        controlsBinding.thermalWhitePointSeekBar.setProgress(100);
        thermalPalette = FFmpegNative.THERMAL_PALETTE_WHITE_HOT;
        thermalGamma = 1.0f;
        thermalBlackPoint = 0.0f;
        thermalWhitePoint = 1.0f;
        currentRenderMode = "";
        updateThermalValueTexts();
        updateThermalControlsEnabledState();
    }

    private void applyIntentPlaybackDefaults() {
        String transport = getIntent().getStringExtra(EXTRA_RTSP_TRANSPORT);
        if ("udp".equalsIgnoreCase(transport)) {
            transportRadioGroup.check(R.id.udpTransportRadio);
        } else if ("auto".equalsIgnoreCase(transport)) {
            transportRadioGroup.check(R.id.autoTransportRadio);
        } else if ("tcp".equalsIgnoreCase(transport)) {
            transportRadioGroup.check(R.id.tcpTransportRadio);
        }

        String latencyMode = getIntent().getStringExtra(EXTRA_LATENCY_MODE);
        if ("ultra_low_latency".equalsIgnoreCase(latencyMode)) {
            latencyModeRadioGroup.check(R.id.lowLatencyUltraRadio);
        } else if ("low_latency".equalsIgnoreCase(latencyMode)) {
            latencyModeRadioGroup.check(R.id.lowLatencyRadio);
        } else if ("stable".equalsIgnoreCase(latencyMode)) {
            latencyModeRadioGroup.check(R.id.stableLatencyRadio);
        } else if ("balanced".equalsIgnoreCase(latencyMode)) {
            latencyModeRadioGroup.check(R.id.balancedLatencyRadio);
        }
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
                surfaceGeneration.incrementAndGet();
                surfaceReady = false;
                surfaceWidth = 0;
                surfaceHeight = 0;
                currentSurface = null;
                FFmpegPlayer p = getPlayer();
                if (p != null && !p.isReleased()) {
                    runNative("Clear Surface", () -> p.clearSurface());
                }
            }
        });
    }

    private void updateSurfaceFromHolder(@NonNull SurfaceHolder holder, int width, int height) {
        Surface surface = holder.getSurface();
        surfaceGeneration.incrementAndGet();
        currentSurface = surface;
        surfaceReady = surface != null && surface.isValid();
        surfaceWidth = width;
        surfaceHeight = height;
    }

    private void bindActions() {
        findViewById(R.id.createButton).setOnClickListener(v -> runNative("Create Player", () -> {
            FFmpegPlayer existing = getPlayer();
            boolean newlyCreated = existing == null || existing.isReleased();
            FFmpegPlayer p = ensurePlayer();
            if (p == null) {
                return jsonError("player is not ready");
            }
            String surfaceResult = bindSurfaceIfReady(p);
            String transportResult = applyRtspTransport(p);
            String latencyResult = applyLatencyMode(p);
            String reconnectResult = applyReconnectOptions(p);
            String audioResult = applyAudioOption(p);
            String decodeResult = newlyCreated
                    ? applyDecodeModeOption(p)
                    : "{\"success\":true,\"message\":\"player already exists, decode mode unchanged until next prepare\"}";
            String thermalResult = newlyCreated
                    ? applyThermalOptionsToPlayer(p)
                    : "{\"success\":true,\"message\":\"player already exists, thermal config unchanged\"}";
            return "{\"success\":true}"
                    + "\nsurface=" + surfaceResult
                    + "\ntransport=" + transportResult
                    + "\nlatency=" + latencyResult
                    + "\nreconnect=" + reconnectResult
                    + "\naudio=" + audioResult
                    + "\ndecode=" + decodeResult
                    + "\nthermal=" + thermalResult;
        }));

        findViewById(R.id.infoButton).setOnClickListener(v -> runNative("FFmpeg Info", () ->
                "version=" + FFmpegNative.getFFmpegVersion()
                        + "\nbuildConfig=" + FFmpegNative.getFFmpegBuildConfig()
                        + "\ndecoders=" + FFmpegNative.getAvailableDecoders()
                        + "\nmediaCodec=" + FFmpegNative.getMediaCodecInfo()));
        findViewById(R.id.infoButton).setOnLongClickListener(v -> {
            runNative("Player Lifetime Stress", () -> FFmpegNative.runDebugCommand(
                    new String[]{"-player-lifetime-stress"}));
            return true;
        });

        findViewById(R.id.probeButton).setOnClickListener(v -> runNative("Probe", () ->
                FFmpegNative.probe(requireUrl(), readTimeoutMs())));

        findViewById(R.id.prepareButton).setOnClickListener(v -> runNative("Prepare", () -> {
            FFmpegPlayer p = ensurePlayer();
            if (p == null) {
                return jsonError("player is not ready");
            }
            String surfaceResult = bindSurfaceIfReady(p);
            String transportResult = applyRtspTransport(p);
            String latencyResult = applyLatencyMode(p);
            String reconnectResult = applyReconnectOptions(p);
            String audioResult = applyAudioOption(p);
            String decodeResult = applyDecodeModeOption(p);
            String thermalResult = applyThermalOptionsToPlayer(p);
            String prepareResult = p.prepare(requireUrl(), readTimeoutMs());
            return "surface=" + surfaceResult
                    + "\ntransport=" + transportResult
                    + "\nlatency=" + latencyResult
                    + "\nreconnect=" + reconnectResult
                    + "\naudio=" + audioResult
                    + "\ndecode=" + decodeResult
                    + "\nthermal=" + thermalResult
                    + "\nprepare=" + prepareResult;
        }));

        findViewById(R.id.startButton).setOnClickListener(v -> runNative("Start", () -> {
            FFmpegPlayer p = ensurePlayer();
            if (p == null) {
                return jsonError("player is not ready");
            }
            String surfaceResult = bindSurfaceIfReady(p);
            String audioResult = applyAudioOption(p);
            return "surface=" + surfaceResult
                    + "\naudio=" + audioResult
                    + "\nstart=" + p.start();
        }));

        findViewById(R.id.pauseButton).setOnClickListener(v -> runNative("Pause", () ->
                requirePlayer().pause()));

        findViewById(R.id.stopButton).setOnClickListener(v -> runNative("Stop", () ->
                requirePlayer().stop()));

        findViewById(R.id.releaseButton).setOnClickListener(v -> runNative("Release", () -> {
            FFmpegPlayer p = takePlayer();
            if (p == null) {
                return jsonError("player is not ready");
            }
            resetPlaybackInfoCounters();
            currentRenderMode = "";
            return p.release();
        }));

        findViewById(R.id.snapshotButton).setOnClickListener(v -> runNative("Snapshot", () ->
                takePlayerSnapshotCompat(requirePlayer(), requireSnapshotPath())));

        findViewById(R.id.startRecordButton).setOnClickListener(v -> runNative("Start Record", () ->
                requirePlayer().startRecord(requireRecordPath())));

        findViewById(R.id.startSegmentRecordButton).setOnClickListener(v -> runNative("Start Segment Record", () ->
                requirePlayer().startRecordWithConfig(requireSegmentPattern(), requireRecordFormat(), requireSegmentDurationSec())));

        findViewById(R.id.stopRecordButton).setOnClickListener(v -> runNative("Stop Record", () ->
                requirePlayer().stopRecord()));

        findViewById(R.id.recordStateButton).setOnClickListener(v -> runNative("Record State", () ->
                requirePlayer().getRecordState()));

        findViewById(R.id.stateButton).setOnClickListener(v -> runNative("Player State", () ->
                requirePlayer().getState()));

        findViewById(R.id.statsButton).setOnClickListener(v -> runNative("Player Stats", () ->
                requirePlayer().getStats()));

        findViewById(R.id.reconnectStateButton).setOnClickListener(v -> runNative("Reconnect/Latency State", () ->
                requirePlayer().getReconnectState()
                        + "\nlatency=" + requirePlayer().getLatencyConfig()));

        findViewById(R.id.clearSurfaceButton).setOnClickListener(v -> runNative("Clear Surface", () ->
                requirePlayer().clearSurface()));

        binding.controlToggleButton.setOnClickListener(v -> toggleControlPanel());
        controlsBinding.controlPanelCloseButton.setOnClickListener(v -> hideControlPanel());

        audioSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FFmpegPlayer p = getPlayer();
            if (p != null && !p.isReleased()) {
                runNative("Audio Option", () -> applyAudioOption(p));
            }
        });
        reconnectSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            FFmpegPlayer p = getPlayer();
            if (p != null && !p.isReleased()) {
                runNative("Reconnect Option", () -> applyReconnectOptions(p));
            }
        });
        hardwareDecodeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (thermalUiUpdating) {
                return;
            }
            updateThermalControlsEnabledState();
        });
        transportRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            FFmpegPlayer p = getPlayer();
            if (p != null && !p.isReleased()) {
                runNative("RTSP Transport", () -> applyRtspTransport(p));
            }
        });
        latencyModeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            FFmpegPlayer p = getPlayer();
            if (p != null && !p.isReleased()) {
                runNative("Latency Mode", () -> applyLatencyMode(p));
            }
        });

        bindThermalControls();
    }

    private void bindThermalControls() {
        controlsBinding.thermalEnabledSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (thermalUiUpdating) {
                return;
            }
            if (isChecked) {
                if (!isThermalSupported()) {
                    thermalUiUpdating = true;
                    controlsBinding.thermalEnabledSwitch.setChecked(false);
                    thermalUiUpdating = false;
                    String current = TextUtils.isEmpty(currentRenderMode)
                            ? (hardwareDecodeSwitch.isChecked() ? "mediacodec_nv12_gl" : "software_yuv_gl")
                            : currentRenderMode;
                    Log.w(TAG, "Thermal blocked: requires software_yuv_gl/mediacodec_nv12_gl, current=" + current);
                    Toast.makeText(this,
                            "Thermal requires software_yuv_gl or mediacodec_nv12_gl. Switch render mode and restart playback.",
                            Toast.LENGTH_LONG).show();
                    updateThermalControlsEnabledState();
                    return;
                }
                updateThermalControlsEnabledState();
                FFmpegPlayer player = getPlayer();
                if (player == null || player.isReleased()) {
                    return;
                }
                callThermalApi("Thermal Enable", () -> applyThermalOptionsToPlayer(player));
            } else {
                updateThermalControlsEnabledState();
                FFmpegPlayer player = getPlayer();
                if (player != null && !player.isReleased()) {
                    callThermalApi("Thermal Disable", () -> player.setThermalEnabled(false));
                }
            }
        });

        controlsBinding.thermalPaletteRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (thermalUiUpdating) {
                return;
            }
            int palette = checkedId == R.id.thermalIronbowRadio
                    ? FFmpegNative.THERMAL_PALETTE_IRONBOW
                    : FFmpegNative.THERMAL_PALETTE_WHITE_HOT;
            if (palette == thermalPalette) {
                return;
            }
            thermalPalette = palette;
            FFmpegPlayer player = getPlayer();
            if (player != null && !player.isReleased() && controlsBinding.thermalEnabledSwitch.isChecked()) {
                callThermalApi("Thermal Palette", () -> player.setThermalPalette(thermalPalette));
            }
        });

        controlsBinding.thermalAgcSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (thermalUiUpdating) {
                return;
            }
            updateThermalControlsEnabledState();
            FFmpegPlayer player = getPlayer();
            if (player == null || player.isReleased() || !controlsBinding.thermalEnabledSwitch.isChecked()) {
                return;
            }
            callThermalApi("Thermal AGC", () -> player.setThermalAgcEnabled(isChecked));
        });

        controlsBinding.thermalGammaSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (thermalUiUpdating) {
                    return;
                }
                float gamma = 0.5f + progress / 100.0f;
                if (Math.abs(gamma - thermalGamma) < 0.001f) {
                    return;
                }
                thermalGamma = gamma;
                controlsBinding.thermalGammaValueText.setText(String.format(Locale.US, "%.2f", gamma));
                FFmpegPlayer player = getPlayer();
                if (player != null && !player.isReleased() && controlsBinding.thermalEnabledSwitch.isChecked()) {
                    callThermalApi("Thermal Gamma", () -> player.setThermalGamma(thermalGamma));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        controlsBinding.thermalBlackPointSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (thermalUiUpdating) {
                    return;
                }
                thermalBlackPoint = clampBlackPoint(progress / 100.0f);
                syncWindowControls();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        controlsBinding.thermalWhitePointSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (thermalUiUpdating) {
                    return;
                }
                thermalWhitePoint = clampWhitePoint(progress / 100.0f);
                syncWindowControls();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private float clampBlackPoint(float black) {
        float maxBlack = Math.max(0.0f, thermalWhitePoint - MIN_WINDOW_SPAN);
        return Math.max(0.0f, Math.min(black, maxBlack));
    }

    private float clampWhitePoint(float white) {
        float minWhite = Math.min(1.0f, thermalBlackPoint + MIN_WINDOW_SPAN);
        return Math.max(minWhite, Math.min(white, 1.0f));
    }

    private void syncWindowControls() {
        thermalUiUpdating = true;
        try {
            controlsBinding.thermalBlackPointSeekBar.setProgress(Math.round(thermalBlackPoint * 100.0f));
            controlsBinding.thermalWhitePointSeekBar.setProgress(Math.round(thermalWhitePoint * 100.0f));
            updateThermalValueTexts();
        } finally {
            thermalUiUpdating = false;
        }
        FFmpegPlayer player = getPlayer();
                if (player != null && !player.isReleased() && controlsBinding.thermalEnabledSwitch.isChecked()) {
                    callThermalApi("Thermal Window", () -> player.setThermalWindow(thermalBlackPoint, thermalWhitePoint));
        }
    }

    private void updateThermalValueTexts() {
        controlsBinding.thermalGammaValueText.setText(String.format(Locale.US, "%.2f", thermalGamma));
        controlsBinding.thermalBlackPointValueText.setText(String.format(Locale.US, "%.2f", thermalBlackPoint));
        controlsBinding.thermalWhitePointValueText.setText(String.format(Locale.US, "%.2f", thermalWhitePoint));
    }

    private boolean isThermalSupported() {
        String mode = !TextUtils.isEmpty(currentRenderMode) ? currentRenderMode : intentRenderMode;
        if (!TextUtils.isEmpty(mode)) {
            // software_yuv_gl / mediacodec_nv12_gl / mediacodec_oes support thermal;
            // software_rgba and mediacodec_surface do not.
            return "software_yuv_gl".equals(mode)
                    || "mediacodec_nv12_gl".equals(mode)
                    || "mediacodec_oes".equals(mode);
        }
        // Pending: hardware ON -> mediacodec_nv12_gl, OFF -> software_yuv_gl (both thermal-capable).
        return true;
    }

    private void updateThermalControlsEnabledState() {
        boolean supported = isThermalSupported();
        boolean thermalOn = supported && controlsBinding.thermalEnabledSwitch.isChecked();
        // AGC applies to software_yuv_gl, mediacodec_oes, and mediacodec_nv12_gl.
        boolean gammaSupported = supported;
        boolean agcSupported = supported;
        boolean agcOn = thermalOn && agcSupported && controlsBinding.thermalAgcSwitch.isChecked();
        // The main Thermal switch stays clickable even when unsupported so the user gets a Toast explanation.
        controlsBinding.thermalPaletteRadioGroup.setEnabled(thermalOn);
        for (int i = 0; i < controlsBinding.thermalPaletteRadioGroup.getChildCount(); i++) {
            controlsBinding.thermalPaletteRadioGroup.getChildAt(i).setEnabled(thermalOn);
        }
        controlsBinding.thermalAgcSwitch.setEnabled(thermalOn && agcSupported);
        controlsBinding.thermalGammaSeekBar.setEnabled(thermalOn && gammaSupported);
        controlsBinding.thermalGammaValueText.setAlpha(thermalOn && gammaSupported ? 1.0f : 0.4f);
        // Manual window disabled while AGC is ON (effective window comes from AGC).
        boolean windowEnabled = thermalOn && !agcOn;
        controlsBinding.thermalBlackPointSeekBar.setEnabled(windowEnabled);
        controlsBinding.thermalWhitePointSeekBar.setEnabled(windowEnabled);
        controlsBinding.thermalBlackPointValueText.setAlpha(windowEnabled ? 1.0f : 0.4f);
        controlsBinding.thermalWhitePointValueText.setAlpha(windowEnabled ? 1.0f : 0.4f);
    }

    private String applyThermalOptionsToPlayer(FFmpegPlayer player) {
        if (player == null) {
            return jsonError("player is not ready");
        }
        String paletteResult = player.setThermalPalette(thermalPalette);
        String gammaResult = player.setThermalGamma(thermalGamma);
        String windowResult = player.setThermalWindow(thermalBlackPoint, thermalWhitePoint);
        String agcResult = player.setThermalAgcEnabled(controlsBinding.thermalAgcSwitch.isChecked());
        String enableResult = player.setThermalEnabled(controlsBinding.thermalEnabledSwitch.isChecked());
        return "palette=" + paletteResult
                + "\ngamma=" + gammaResult
                + "\nwindow=" + windowResult
                + "\nagc=" + agcResult
                + "\nenable=" + enableResult;
    }

    private void callThermalApi(String title, NativeAction action) {
        if (destroyed) {
            return;
        }
        try {
            String result = action.run();
            if (result != null && result.contains("\"success\":false")) {
                Log.w(TAG, title + " failed: " + result);
            } else {
                Log.d(TAG, title + " " + result);
            }
        } catch (Throwable t) {
            Log.e(TAG, title + " failed", t);
        }
    }

    private void bindSurfaceForExistingPlayer(String title) {
        FFmpegPlayer p = getPlayer();
        if (p == null || p.isReleased()) {
            logDebug(title + ": no player yet");
            return;
        }
        runNative(title, () -> bindSurfaceIfReady(p));
    }

    private void startPlaybackInfoUpdates() {
        resetPlaybackInfoCounters();
        playbackInfoTextView.setText("等待播放");
        mainHandler.post(playbackInfoRunnable);
    }

    private void updatePlaybackInfoAsync() {
        final FFmpegPlayer p = getPlayer();
        ExecutorService statsWorker = worker;
        if (destroyed || statsWorker == null) {
            return;
        }
        if (p == null || p.isReleased()) {
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
                    statsJson = p.getStats();
                } catch (Throwable t) {
                    statsJson = jsonError(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                }
                String finalStatsJson = statsJson;
                mainHandler.post(() -> {
                    playbackInfoRequestInFlight.set(false);
                    if (!destroyed && p == getPlayer()) {
                        updatePlaybackInfoFromStats(p, finalStatsJson);
                    }
                });
            });
        } catch (Throwable t) {
            playbackInfoRequestInFlight.set(false);
        }
    }

    private void updatePlaybackInfoFromStats(FFmpegPlayer player, String statsJson) {
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
            if (player != lastPlaybackInfoPlayer || lastPlaybackInfoTimeMs <= 0) {
                lastPlaybackInfoPlayer = player;
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
            String playerState = stats.optString("playerState", state.toUpperCase(Locale.US));
            boolean reconnecting = stats.optBoolean("reconnecting", false);
            boolean waitingSource = stats.optBoolean("waitingSource", false);
            long reconnectAttempt = stats.optLong("reconnectAttempt", stats.optLong("reconnectAttemptCount", 0));
            String reconnectError = stats.optString("reconnectLastError", stats.optString("lastReconnectError", ""));
            String mode = stats.optString("renderMode", "unknown");
            String codec = stats.optString("actualDecoderName", stats.optString("videoCodecName", ""));
            String decodeBackend = stats.optString("decodeBackend", "unknown");
            String frameOutputType = stats.optString("frameOutputType", "unknown");
            String renderer = stats.optString("renderer", "unknown");
            String requestedRenderer = stats.optString("requestedRenderer", "unknown");
            boolean renderFallbackUsed = stats.optBoolean("renderFallbackUsed", false);
            boolean decoderFallbackUsed = stats.optBoolean("hardwareDecodeFallbackUsed", false);
            String renderFallbackReason = stats.optString("renderFallbackReason", "");
            String frameFormat = stats.optString("frameFormat", "");
            long dropped = stats.optLong("droppedVideoFrameCount", 0);
            long videoBitRate = stats.optLong("videoBitRate", 0);
            long streamBitRate = stats.optLong("streamBitRate", 0);
            String nominalBitrate = videoBitRate > 0
                    ? formatKbps(videoBitRate / 1000.0)
                    : (streamBitRate > 0 ? formatKbps(streamBitRate / 1000.0) : "--");
            String stateDisplay = playerState;
            if (waitingSource) {
                stateDisplay += " waiting stream recovery";
            } else if (reconnecting) {
                stateDisplay += " reconnecting";
            }

            int videoWidth = stats.optInt("videoWidth", 0);
            int videoHeight = stats.optInt("videoHeight", 0);
            int frameYStride = stats.optInt("frameYStride", 0);
            String frameColorRange = stats.optString("frameColorRange", "unknown");
            long rendererFrames;
            long rendererFallbackFrames;
            switch (renderer) {
                case "nv12_gl":
                    rendererFrames = stats.optLong("nv12GlRenderedFrameCount", 0);
                    rendererFallbackFrames = stats.optLong("nv12GlFallbackFrameCount", 0);
                    break;
                case "yuv_gl":
                    rendererFrames = stats.optLong("yuvGlRenderedFrameCount", 0);
                    rendererFallbackFrames = stats.optLong("yuvGlFallbackFrameCount", 0);
                    break;
                case "oes_gl":
                    rendererFrames = stats.optLong("oesFrameRenderedCount", 0);
                    rendererFallbackFrames = 0;
                    break;
                case "direct_surface":
                    rendererFrames = stats.optLong("hardwareRenderedFrameCount", 0);
                    rendererFallbackFrames = 0;
                    break;
                case "rgba_nativewindow":
                    rendererFrames = stats.optLong("softwareRenderedFrameCount", 0);
                    rendererFallbackFrames = "nv12_gl".equals(requestedRenderer)
                            ? stats.optLong("nv12GlFallbackFrameCount", 0)
                            : stats.optLong("yuvGlFallbackFrameCount", 0);
                    break;
                default:
                    rendererFrames = 0;
                    rendererFallbackFrames = 0;
                    break;
            }
            String fallbackDisplay = renderFallbackUsed
                    ? "render:" + (TextUtils.isEmpty(renderFallbackReason) ? "yes" : renderFallbackReason)
                    : (decoderFallbackUsed ? "decoder" : "none");
            String resolution = videoWidth > 0 && videoHeight > 0
                    ? videoWidth + "x" + videoHeight
                    : "--";

            boolean thermalEnabled = stats.optBoolean("thermalEnabled", false);
            boolean thermalAgcEnabled = stats.optBoolean("thermalAgcEnabled", false);
            String thermalPalette = stats.optString("thermalPalette", "original");
            float thermalGamma = (float) stats.optDouble("thermalGamma", 1.0);
            String thermalRenderMode = stats.optString("thermalRenderMode", "normal");
            float thermalBlackPoint = (float) stats.optDouble("thermalBlackPoint", 0.0);
            float thermalWhitePoint = (float) stats.optDouble("thermalWhitePoint", 1.0);
            boolean thermalAgcValid = stats.optBoolean("thermalAgcValid", false);
            float thermalAgcBlackPoint = (float) stats.optDouble("thermalAgcBlackPoint", 0.0);
            float thermalAgcWhitePoint = (float) stats.optDouble("thermalAgcWhitePoint", 1.0);
            float windowBlack = thermalAgcValid ? thermalAgcBlackPoint : thermalBlackPoint;
            float windowWhite = thermalAgcValid ? thermalAgcWhitePoint : thermalWhitePoint;

            currentRenderMode = mode;
            updateThermalControlsEnabledState();

            boolean mediaCodecSurfaceMode = "mediacodec_surface".equals(mode);
            boolean oesMode = "mediacodec_oes".equals(mode);
            boolean nv12GlMode = "mediacodec_nv12_gl".equals(mode);
            String thermalInputType = stats.optString("thermalInputType", "none");
            String windowLine;
            if (mediaCodecSurfaceMode) {
                windowLine = "";
            } else if (oesMode) {
                // OES window lives in luminance 0..1 domain; AGC effective window shown when valid.
                windowLine = "\nWindow " + String.format(Locale.US, "%.2f", windowBlack)
                        + " - " + String.format(Locale.US, "%.2f", windowWhite)
                        + " | AGC " + (thermalAgcEnabled ? "ON" : "OFF");
            } else if (nv12GlMode) {
                // NV12 GL window in intensity 0..1 domain; AGC effective window shown when valid.
                windowLine = "\nWindow " + String.format(Locale.US, "%.2f", windowBlack)
                        + " - " + String.format(Locale.US, "%.2f", windowWhite)
                        + " | AGC " + (thermalAgcEnabled ? "ON" : "OFF");
            } else {
                windowLine = "\nWindow " + String.format(Locale.US, "%.2f", windowBlack)
                        + " - " + String.format(Locale.US, "%.2f", windowWhite)
                        + " | Range " + frameColorRange;
            }
            String thermalDisplay;
            if (mediaCodecSurfaceMode) {
                thermalDisplay = "Thermal: UNAVAILABLE | " + mode;
            } else if (oesMode) {
                thermalDisplay = "Thermal " + (thermalEnabled ? "ON" : "OFF")
                        + " | " + thermalPalette
                        + " | gamma " + String.format(Locale.US, "%.2f", thermalGamma)
                        + " | render " + thermalRenderMode.toUpperCase(Locale.US)
                        + "\nInput: " + thermalInputType.toUpperCase(Locale.US);
            } else if (nv12GlMode) {
                // NV12 GL: Original / White Hot / Ironbow with Gamma, Window, and AGC.
                thermalDisplay = "Thermal " + (thermalEnabled ? "ON" : "OFF")
                        + " | " + thermalPalette
                        + " | gamma " + String.format(Locale.US, "%.2f", thermalGamma)
                        + " | render " + thermalRenderMode.toUpperCase(Locale.US)
                        + "\nInput: " + thermalInputType.toUpperCase(Locale.US);
            } else {
                thermalDisplay = "Thermal " + (thermalEnabled ? "ON" : "OFF")
                        + " | " + thermalPalette
                        + " | AGC " + (thermalAgcEnabled ? "ON" : "OFF")
                        + " | gamma " + String.format(Locale.US, "%.2f", thermalGamma)
                        + " | render " + thermalRenderMode.toUpperCase(Locale.US);
            }

            String oesLine = "";
            if ("mediacodec_oes".equals(mode)) {
                long oesAvailable = stats.optLong("oesFrameAvailableCount", 0);
                long oesRendered = stats.optLong("oesFrameRenderedCount", 0);
                oesLine = "\nOES available=" + oesAvailable
                        + " rendered=" + oesRendered;
            }

            playbackInfoTextView.setText(
                    "state=" + stateDisplay
                            + " | decoder=" + codec + " (" + decodeBackend + ")"
                            + "\noutput=" + frameOutputType
                            + " | renderer=" + renderer
                            + " | requested=" + requestedRenderer
                            + "\n" + resolution
                            + " | " + frameFormat
                            + " | Y stride=" + frameYStride
                            + " | range=" + frameColorRange
                            + "\ndecode " + formatFps(decodeFps)
                            + " fps  render " + formatFps(renderFps)
                            + " fps  dropped " + dropped
                            + "\nbitrate " + formatKbps(videoKbps)
                            + "  transfer " + formatKbPerSec(transferKbPerSec)
                            + "  nominal " + nominalBitrate
                            + "\nrenderer frames=" + rendererFrames
                            + " fallback frames=" + rendererFallbackFrames
                            + " | fallback=" + fallbackDisplay
                            + "  packets " + stats.optLong("readPacketCount", 0)
                            + "  frames " + renderedFrames
                            + "\nreconnect attempt=" + reconnectAttempt
                            + " event=" + (TextUtils.isEmpty(lastPlayerEventText) ? "--" : lastPlayerEventText)
                            + " error=" + (TextUtils.isEmpty(reconnectError) ? "--" : reconnectError)
                            + "\n" + thermalDisplay
                            + oesLine
                            + windowLine);
            if (++statsLogCounter % 5 == 0) {
                Log.d(TAG_STATS, statsJson);
                logCompactLatencyStats(stats);
                Log.d(TAG_AUDIO_LIFECYCLE,
                        "player=" + player
                                + " player=" + playerState
                                + " audio=" + stats.optString("audioLifecycleState", "unknown")
                                + " enabled=" + stats.optBoolean("audioEnabled", false)
                                + " source=" + stats.optBoolean("sourceHasAudio", false)
                                + " playable=" + stats.optBoolean("audioPlayable", false)
                                + " worker=" + stats.optBoolean("audioWorkerRunning", false)
                                + " sink=" + stats.optBoolean("audioSinkReady", false)
                                + " clock=" + stats.optBoolean("audioPlaybackClockValid", false)
                                + " clockUs=" + stats.optLong("audioPlaybackClockUs", 0)
                                + " avDiffUs=" + stats.optLong("audioVideoDiffUs", 0)
                                + " ptsRebase=" + stats.optLong("audioClockPtsDiscontinuityCount", 0)
                                + " generation=" + stats.optLong("audioGeneration", 0)
                                + " flush=" + stats.optLong("audioQueueFlushCount", 0)
                                + " start/join=" + stats.optLong("audioWorkerStartCount", 0)
                                + "/" + stats.optLong("audioWorkerJoinCount", 0)
                                + " restart=" + stats.optLong("audioSinkRestartCount", 0)
                                + " reconnect=" + stats.optLong("audioReconnectRecoveryCount", 0)
                                + " stale=" + stats.optLong("audioWorkerStaleBlockCount", 0)
                                + " cancel=" + stats.optLong("audioSinkControlledCancelCount", 0)
                                + " sinkError=" + stats.optLong("audioSinkWriteErrorCount", 0)
                                + " queueUs=" + stats.optLong("audioQueueDurationUs", 0)
                                + " queueHighUs=" + stats.optLong("audioQueueHighWatermarkUs", 0)
                                + " queueDrop=" + stats.optLong("audioQueueDropCount", 0)
                                + " master=" + stats.optString("effectiveSyncMaster", "unknown")
                                + " decoderOpen=" + stats.optLong("videoDecoderOpenCount", 0)
                                + "/" + stats.optLong("hardwareDecoderOpenCount", 0)
                                + " recording=" + stats.optBoolean("recording", false)
                                + " recordAudio=" + stats.optLong("recordAudioPacketCount", 0));
            }
        } catch (Throwable t) {
            playbackInfoTextView.setText("播放信息解析失败");
        }
    }

    /**
     * LAT3 diagnostics log-output fix: emit the compact, Logcat-safe latency
     * lines from the exact same getStats() snapshot that produced the full
     * FFmpegPlayerStats JSON (no second native snapshot). All four lines share
     * one monotonic diagnostics sequence so they can be correlated even when
     * interleaved with other log output. Stats JSON/API contract is untouched.
     */
    private void logCompactLatencyStats(JSONObject stats) {
        final long seq = ++latencyStatsSeq;

        final LatencyStatsFormatter.StateInfo stateInfo = new LatencyStatsFormatter.StateInfo(
                stats.optLong("handle", 0),
                stats.optString("state", "unknown"),
                stats.optLong("videoPtsGeneration", 0),
                stats.optLong("stageTimingGeneration", 0),
                stats.optBoolean("steadyStateValid", false),
                stats.optDouble("measuredDecodeFps", 0),
                stats.optDouble("measuredRenderFps", 0),
                stats.optString("decodeBackend", "unknown"),
                stats.optString("frameOutputType", "unknown"),
                stats.optString("renderer", "unknown"),
                stats.optLong("videoPacketCount", 0),
                stats.optLong("videoFrameCount", 0),
                stats.optLong("renderedFrameCount", 0));

        final LatencyStatsFormatter.MediaInfo mediaInfo = new LatencyStatsFormatter.MediaInfo(
                stats.optBoolean("clientMediaBacklogValid", false),
                stats.optLong("demuxToDecoderBacklogUs", -1),
                stats.optLong("decoderBacklogUs", -1),
                stats.optLong("renderBacklogUs", -1),
                stats.optLong("clientMediaBacklogUs", -1),
                stats.optLong("demuxToDecoderBacklogP50Us", -1),
                stats.optLong("demuxToDecoderBacklogP95Us", -1),
                stats.optLong("demuxToDecoderBacklogP99Us", -1),
                stats.optLong("demuxToDecoderBacklogDistCount", 0),
                stats.optLong("decoderBacklogP50Us", -1),
                stats.optLong("decoderBacklogP95Us", -1),
                stats.optLong("decoderBacklogP99Us", -1),
                stats.optLong("decoderBacklogDistCount", 0),
                stats.optLong("renderBacklogP50Us", -1),
                stats.optLong("renderBacklogP95Us", -1),
                stats.optLong("renderBacklogP99Us", -1),
                stats.optLong("renderBacklogDistCount", 0),
                stats.optLong("clientMediaBacklogP50Us", -1),
                stats.optLong("clientMediaBacklogP95Us", -1),
                stats.optLong("clientMediaBacklogP99Us", -1),
                stats.optLong("clientMediaBacklogDistCount", 0));

        final LatencyStatsFormatter.StageInfo stageInfo = new LatencyStatsFormatter.StageInfo(
                stats.optLong("demuxReturnToDecoderSubmitP50Us", -1),
                stats.optLong("demuxReturnToDecoderSubmitP95Us", -1),
                stats.optLong("demuxReturnToDecoderSubmitP99Us", -1),
                stats.optLong("demuxReturnToDecoderSubmitDistCount", 0),
                stats.optLong("decoderSubmitToOutputP50Us", -1),
                stats.optLong("decoderSubmitToOutputP95Us", -1),
                stats.optLong("decoderSubmitToOutputP99Us", -1),
                stats.optLong("decoderSubmitToOutputDistCount", 0),
                stats.optLong("decodedOutputToRenderBeginP50Us", -1),
                stats.optLong("decodedOutputToRenderBeginP95Us", -1),
                stats.optLong("decodedOutputToRenderBeginP99Us", -1),
                stats.optLong("decodedOutputToRenderBeginDistCount", 0),
                stats.optLong("renderBeginToSubmitP50Us", -1),
                stats.optLong("renderBeginToSubmitP95Us", -1),
                stats.optLong("renderBeginToSubmitP99Us", -1),
                stats.optLong("renderBeginToSubmitDistCount", 0),
                stats.optLong("packetReadyToRenderSubmitP50Us", -1),
                stats.optLong("packetReadyToRenderSubmitP95Us", -1),
                stats.optLong("packetReadyToRenderSubmitP99Us", -1),
                stats.optLong("packetReadyToRenderSubmitDistCount", 0));

        final boolean ptsDeltaReady = stats.optLong("videoPacketPtsDeltaSampleCount", 0) > 0;
        final LatencyStatsFormatter.PreT0Info preT0Info = new LatencyStatsFormatter.PreT0Info(
                stats.optLong("avReadFrameDurationP50Us", -1),
                stats.optLong("avReadFrameDurationP95Us", -1),
                stats.optLong("avReadFrameDurationP99Us", -1),
                stats.optLong("avReadFrameDurationDistCount", 0),
                stats.optLong("videoPacketReturnGapP50Us", -1),
                stats.optLong("videoPacketReturnGapP95Us", -1),
                stats.optLong("videoPacketReturnGapP99Us", -1),
                stats.optLong("videoPacketReturnGapDistCount", 0),
                ptsDeltaReady ? stats.optLong("avgVideoPacketPtsDeltaUs", -1) : -1,
                stats.optLong("fastReturnPacketCount", 0),
                stats.optLong("maxFastReturnBurstLength", 0),
                stats.optLong("readStallGt100MsCount", 0),
                stats.optLong("readStallGt250MsCount", 0),
                stats.optLong("readStallGt500MsCount", 0),
                stats.optLong("readStallGt1000MsCount", 0),
                stats.optLong("readEagainCount", 0),
                stats.optLong("readTimeoutCount", 0),
                stats.optLong("readEofCount", 0),
                stats.optLong("readErrorCount", 0));

        final LatencyStatsFormatter.HealthInfo healthInfo = new LatencyStatsFormatter.HealthInfo(
                stats.optLong("stageTimingSampleCount", 0),
                stats.optLong("packetReadyToRenderSubmitDistCount", 0),
                stats.optLong("clientMediaBacklogDistCount", 0),
                stats.optLong("decoderTimingUnmatchedCount", 0),
                stats.optLong("renderTimingUnmatchedCount", 0),
                stats.optLong("stageTimingForcedEvictionCount", 0),
                stats.optLong("stageTimingResetCount", 0),
                stats.optLong("stageTimingClockAnomalyCount", 0),
                stats.optLong("videoPtsBackwardCount", 0),
                stats.optLong("decoderPtsBackwardCount", 0),
                stats.optLong("decodedPtsBackwardCount", 0),
                stats.optLong("renderedPtsBackwardCount", 0));

        final LatencyStatsFormatter.E2EInfo e2eInfo = new LatencyStatsFormatter.E2EInfo(
                stats.optString("e2eMeasurementMode", "none"),
                receiverClockSyncMethod(),
                stats.optLong("videoRtpClockRate", 0),
                stats.optLong("lastPacketReadyWallNs", -1),
                stats.optLong("e2eGeneration", 0),
                stats.optLong("e2eResetCount", 0));

        Log.d(LatencyStatsFormatter.TAG, LatencyStatsFormatter.stateLine(seq, stateInfo));
        Log.d(LatencyStatsFormatter.TAG, LatencyStatsFormatter.mediaLine(seq, mediaInfo));
        Log.d(LatencyStatsFormatter.TAG, LatencyStatsFormatter.stageLine(seq, stageInfo));
        Log.d(LatencyStatsFormatter.TAG, LatencyStatsFormatter.preT0Line(seq, preT0Info));
        Log.d(LatencyStatsFormatter.TAG, LatencyStatsFormatter.e2eLine(seq, e2eInfo));
        Log.d(LatencyStatsFormatter.TAG, LatencyStatsFormatter.healthLine(seq, healthInfo));
    }

    /**
     * LAT6: receiver clock sync evidence. Android keeps the system wall clock
     * NTP-synced when auto-time is on; the sync ERROR is not observable here
     * and stays UNKNOWN (never reported as 0 ms).
     */
    private String receiverClockSyncMethod() {
        try {
            final int autoTime = android.provider.Settings.Global.getInt(
                    getContentResolver(), android.provider.Settings.Global.AUTO_TIME, -1);
            if (autoTime == 1) {
                return "auto_time";
            }
            if (autoTime == 0) {
                return "manual";
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private void resetPlaybackInfoCounters() {
        lastPlaybackInfoPlayer = null;
        lastPlaybackInfoTimeMs = 0;
        lastPlaybackInfoRenderedFrames = 0;
        lastPlaybackInfoDecodedFrames = 0;
        lastPlaybackInfoVideoBytes = 0;
        lastPlaybackInfoInputBytes = 0;
        lastPlayerEventText = "";
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

    private FFmpegPlayer ensurePlayer() {
        synchronized (handleLock) {
            if (destroyed) {
                return null;
            }
            if (player == null || player.isReleased()) {
                player = new FFmpegPlayer();
                player.setListener(playerEventListener);
            }
            return player;
        }
    }

    private FFmpegPlayer getPlayer() {
        synchronized (handleLock) {
            return player;
        }
    }

    private FFmpegPlayer takePlayer() {
        synchronized (handleLock) {
            FFmpegPlayer p = player;
            player = null;
            return p;
        }
    }

    private FFmpegPlayer requirePlayer() {
        FFmpegPlayer p = getPlayer();
        if (p == null || p.isReleased()) {
            throw new IllegalStateException("player is not ready, tap Create or Prepare first");
        }
        return p;
    }

    private String bindSurfaceIfReady(FFmpegPlayer player) {
        if (player == null) {
            return jsonError("player is not ready");
        }
        Surface surface = currentSurface;
        if (!surfaceReady || surface == null || !surface.isValid()) {
            return jsonError("surface is not ready");
        }
        if (surfaceWidth <= 0 || surfaceHeight <= 0) {
            return jsonError("surface size is not ready");
        }
        Log.d(TAG, "bind surface viewSize=" + surfaceWidth + "x" + surfaceHeight);
        return player.setSurface(surface);
    }

    private String applyAudioOption(FFmpegPlayer player) {
        if (player == null) {
            return jsonError("player is not ready");
        }
        return player.setAudioEnabled(audioSwitch.isChecked());
    }

    private String applyReconnectOptions(FFmpegPlayer player) {
        if (player == null) {
            return jsonError("player is not ready");
        }
        return player.setReconnectOptions(reconnectSwitch.isChecked(), -1, 1000);
    }

    private String applyRtspTransport(FFmpegPlayer player) {
        if (player == null) {
            return jsonError("player is not ready");
        }
        return player.setRtspTransport(selectedRtspTransport());
    }

    private String applyLatencyMode(FFmpegPlayer player) {
        if (player == null) {
            return jsonError("player is not ready");
        }
        return player.setLatencyMode(selectedLatencyMode());
    }

    private String applyDecodeModeOption(FFmpegPlayer player) {
        if (player == null) {
            return jsonError("player is not ready");
        }
        boolean hardwareDecode = "mediacodec_oes".equals(intentRenderMode)
                || "mediacodec_nv12_gl".equals(intentRenderMode)
                || hardwareDecodeSwitch.isChecked();
        String decodeResult = player.setHardwareDecodeEnabled(hardwareDecode);
        // setHardwareDecode(false) may reset software render mode to software_rgba,
        // so apply the explicit render mode afterwards.
        String renderMode;
        if ("mediacodec_oes".equals(intentRenderMode)) {
            renderMode = "mediacodec_oes";
        } else if ("mediacodec_surface".equals(intentRenderMode)) {
            renderMode = "mediacodec_surface";
        } else {
            // Revised Phase 2 main path: Hardware Decode ON -> NV12 GL.
            renderMode = hardwareDecode ? "mediacodec_nv12_gl" : "software_yuv_gl";
        }
        String renderModeResult = player.setHardwareRenderMode(renderMode);
        currentRenderMode = renderMode;
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
        String url = controlsBinding.urlEditText.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            throw new IllegalArgumentException("Please enter RTSP/URL");
        }
        return url;
    }

    private int readTimeoutMs() {
        String value = controlsBinding.timeoutEditText.getText().toString().trim();
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
        String path = controlsBinding.recordPathEditText.getText().toString().trim();
        if (TextUtils.isEmpty(path)) {
            path = defaultFilePath("record_av_test.ts");
            controlsBinding.recordPathEditText.setText(path);
        }
        ensureParentExists(path);
        return path;
    }

    private String requireSegmentPattern() {
        String pattern = controlsBinding.segmentPatternEditText.getText().toString().trim();
        if (TextUtils.isEmpty(pattern)) {
            pattern = defaultFilePath("record_segment_%03d.ts");
            controlsBinding.segmentPatternEditText.setText(pattern);
        }
        ensureParentExists(pattern.replace("%03d", "000"));
        return pattern;
    }

    private String requireRecordFormat() {
        String format = controlsBinding.recordFormatEditText.getText().toString().trim();
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

    private String takePlayerSnapshotCompat(FFmpegPlayer player, String outputPath) throws Exception {
        String nativeResult = player.takeSnapshot( outputPath);
        final JSONObject nativeSnapshot;
        try {
            nativeSnapshot = new JSONObject(nativeResult == null ? "" : nativeResult);
        } catch (Throwable parseError) {
            return snapshotError(
                    "SNAPSHOT_PROTOCOL_ERROR",
                    "Native snapshot returned invalid JSON",
                    nativeResult,
                    null);
        }
        if (nativeSnapshot.optBoolean("success", false)) {
            Log.i(TAG, "snapshot route=native_rgba");
            return nativeResult;
        }
        String errorCode = nativeSnapshot.optString("errorCode", "");
        if (!SNAPSHOT_REQUIRES_SURFACE_CAPTURE.equals(errorCode)) {
            return nativeResult;
        }
        Log.i(TAG, "snapshot route=surface_pixelcopy");
        return takeSurfaceSnapshotWithPixelCopy(player, outputPath, nativeResult);
    }

    private String takeSurfaceSnapshotWithPixelCopy(FFmpegPlayer player,
                                                    String outputPath,
                                                    String nativeResult) throws Exception {
        FFmpegPlayer current = getPlayer();
                if (destroyed || current == null || current.isReleased()) {
            return snapshotError(
                    "SNAPSHOT_PLAYER_RELEASED",
                    "PixelCopy snapshot cancelled because the player was released",
                    nativeResult,
                    null);
        }
        SurfaceHolder holder = binding.playerPreviewView.getHolder();
        Surface surface = holder == null ? null : holder.getSurface();
        if (!surfaceReady || surface == null || !surface.isValid()) {
            return snapshotError(
                    "SNAPSHOT_NO_SURFACE",
                    "PixelCopy snapshot failed: surface is not ready",
                    nativeResult,
                    null);
        }
        int targetSurfaceGeneration = surfaceGeneration.get();
        int width = surfaceWidth;
        int height = surfaceHeight;
        if (width <= 0 || height <= 0) {
            return snapshotError(
                    "SNAPSHOT_NO_SURFACE",
                    "PixelCopy snapshot failed: surface size is not ready",
                    nativeResult,
                    null);
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger copyResult = new AtomicInteger(PixelCopy.ERROR_UNKNOWN);
        mainHandler.post(() -> {
            try {
                PixelCopy.request(surface, bitmap, result -> {
                    copyResult.set(result);
                    latch.countDown();
                }, mainHandler);
            } catch (Throwable requestError) {
                copyResult.set(PixelCopy.ERROR_SOURCE_INVALID);
                latch.countDown();
            }
        });

        if (!latch.await(1500, TimeUnit.MILLISECONDS)) {
            // PixelCopy can still own the destination after this timeout. Do
            // not recycle it while the asynchronous copy may still complete.
            return snapshotError(
                    "SNAPSHOT_PIXELCOPY_ERROR",
                    "PixelCopy snapshot timed out",
                    nativeResult,
                    PixelCopy.ERROR_TIMEOUT);
        }
        int result = copyResult.get();
        if (result != PixelCopy.SUCCESS) {
            bitmap.recycle();
            return snapshotError(
                    pixelCopyErrorCode(result),
                    "PixelCopy snapshot failed result=" + result,
                    nativeResult,
                    result);
        }
        FFmpegPlayer cur = getPlayer();
            if (destroyed || cur == null || cur.isReleased()) {
            bitmap.recycle();
            return snapshotError(
                    "SNAPSHOT_PLAYER_RELEASED",
                    "PixelCopy completed after the player was released",
                    nativeResult,
                    result);
        }
        if (targetSurfaceGeneration != surfaceGeneration.get()
                || currentSurface != surface
                || !surfaceReady
                || !surface.isValid()) {
            bitmap.recycle();
            return snapshotError(
                    "SNAPSHOT_NO_SURFACE",
                    "PixelCopy completed after the video surface changed",
                    nativeResult,
                    result);
        }

        File outputFile = new File(outputPath);
        Bitmap.CompressFormat format = isJpegPath(outputPath)
                ? Bitmap.CompressFormat.JPEG
                : Bitmap.CompressFormat.PNG;
        try (OutputStream output = new FileOutputStream(outputFile)) {
            if (!bitmap.compress(format, 95, output)) {
                return snapshotError(
                        "SNAPSHOT_IO_ERROR",
                        "PixelCopy snapshot encode failed",
                        nativeResult,
                        result);
            }
        } catch (Throwable saveError) {
            return snapshotError(
                    "SNAPSHOT_IO_ERROR",
                    "PixelCopy snapshot save failed: " + saveError.getMessage(),
                    nativeResult,
                    result);
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
                + "\"snapshotCaptureMode\":\"surface_pixelcopy\","
                + "\"nativeSnapshot\":\"" + escapeJson(nativeResult) + "\"}";
    }

    private String pixelCopyErrorCode(int result) {
        if (result == PixelCopy.ERROR_SOURCE_NO_DATA) {
            return "SNAPSHOT_NO_FRAME";
        }
        if (result == PixelCopy.ERROR_SOURCE_INVALID) {
            return "SNAPSHOT_NO_SURFACE";
        }
        return "SNAPSHOT_PIXELCOPY_ERROR";
    }

    private String snapshotError(String errorCode,
                                 String message,
                                 String nativeResult,
                                 Integer pixelCopyResult) {
        StringBuilder out = new StringBuilder();
        out.append("{\"success\":false,")
                .append("\"errorCode\":\"").append(escapeJson(errorCode)).append("\",")
                .append("\"message\":\"").append(escapeJson(message)).append("\",")
                .append("\"errorMessage\":\"").append(escapeJson(message)).append("\",")
                .append("\"snapshotCaptureMode\":\"surface_pixelcopy\"");
        if (pixelCopyResult != null) {
            out.append(",\"pixelCopyResult\":").append(pixelCopyResult);
        }
        if (nativeResult != null) {
            out.append(",\"nativeSnapshot\":\"").append(escapeJson(nativeResult)).append("\"");
        }
        return out.append('}').toString();
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
        surfaceGeneration.incrementAndGet();
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
        logDebug(">>> " + title);
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
                logDebug(title + "\n" + finalResult);
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

    private void logDebug(String message) {
        Log.d(TAG, message);
    }

    private void toggleControlPanel() {
        if (binding.playerControlPanel.getRoot().getVisibility() == View.VISIBLE) {
            hideControlPanel();
        } else {
            showControlPanel();
        }
    }

    private void showControlPanel() {
        View panel = binding.playerControlPanel.getRoot();
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(0f);
        panel.setTranslationX(Math.max(1, panel.getWidth()));
        panel.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(180)
                .start();
    }

    private void hideControlPanel() {
        View panel = binding.playerControlPanel.getRoot();
        if (panel.getVisibility() != View.VISIBLE) {
            return;
        }
        panel.animate()
                .translationX(Math.max(1, panel.getWidth()))
                .alpha(0f)
                .setDuration(150)
                .withEndAction(() -> panel.setVisibility(View.GONE))
                .start();
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
        FFmpegPlayer p = takePlayer();
        ExecutorService releaseWorker = worker;
        worker = null;
        if (releaseWorker != null) {
            releaseWorker.execute(() -> {
                if (p != null && !p.isReleased()) {
                    Log.d(TAG, "onDestroy stop=" + p.stop());
                    Log.d(TAG, "onDestroy clearSurface=" + p.clearSurface());
                    Log.d(TAG, "onDestroy release=" + p.release());
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
