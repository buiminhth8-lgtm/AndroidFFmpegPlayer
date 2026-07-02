package com.example.motro;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.motro.ffmpeg.FFmpegNative;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MediaPlayerActivity extends AppCompatActivity {

    private static final String TAG = "FFmpegPlayerDemo";
    private static final int DEFAULT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_SEGMENT_SECONDS = 60;

    private final Object handleLock = new Object();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private TextureView previewView;
    private EditText urlEditText;
    private EditText timeoutEditText;
    private EditText recordPathEditText;
    private EditText segmentPatternEditText;
    private EditText snapshotPathEditText;
    private Switch audioSwitch;
    private Switch reconnectSwitch;
    private RadioGroup transportRadioGroup;
    private TextView handleTextView;
    private TextView logTextView;

    private ExecutorService worker;
    private volatile Surface currentSurface;
    private volatile Surface textureSurface;
    private volatile boolean surfaceReady;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;
    private volatile boolean destroyed;
    private long playerHandle;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_player);

        worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "FFmpegDemoWorker"));
        bindViews();
        initDefaults();
        bindPreviewCallback();
        bindActions();
        appendLog("Demo ready. Tap Create/Info/Prepare to load FFmpeg native libraries.");
    }

    private void bindViews() {
        previewView = findViewById(R.id.playerPreviewView);
        previewView.setKeepScreenOn(true);
        urlEditText = findViewById(R.id.urlEditText);
        timeoutEditText = findViewById(R.id.timeoutEditText);
        recordPathEditText = findViewById(R.id.recordPathEditText);
        segmentPatternEditText = findViewById(R.id.segmentPatternEditText);
        snapshotPathEditText = findViewById(R.id.snapshotPathEditText);
        audioSwitch = findViewById(R.id.audioSwitch);
        reconnectSwitch = findViewById(R.id.reconnectSwitch);
        transportRadioGroup = findViewById(R.id.transportRadioGroup);
        handleTextView = findViewById(R.id.handleTextView);
        logTextView = findViewById(R.id.logTextView);
    }

    private void initDefaults() {
        urlEditText.setText("rtsp://192.168.1.101:554/main.mov");
        timeoutEditText.setText(String.valueOf(DEFAULT_TIMEOUT_MS));
        audioSwitch.setChecked(false);
        reconnectSwitch.setChecked(true);
        transportRadioGroup.check(R.id.tcpTransportRadio);
        recordPathEditText.setText(defaultFilePath("record_av_test.ts"));
        segmentPatternEditText.setText(defaultFilePath("record_segment_%03d.ts"));
        snapshotPathEditText.setText(defaultFilePath("snapshot.png"));
        updateHandleLabel();
    }

    private void bindPreviewCallback() {
        previewView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                releaseTextureSurfaceOnly();
                textureSurface = new Surface(surfaceTexture);
                currentSurface = textureSurface;
                surfaceReady = true;
                surfaceWidth = width;
                surfaceHeight = height;
                bindSurfaceForExistingPlayer("Texture Available");
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
                surfaceReady = true;
                surfaceWidth = width;
                surfaceHeight = height;
                bindSurfaceForExistingPlayer("Texture Size Changed");
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                surfaceReady = false;
                surfaceWidth = 0;
                surfaceHeight = 0;
                currentSurface = null;
                long handle = getPlayerHandle();
                if (handle != 0) {
                    runNative("Clear Surface", () -> FFmpegNative.clearPlayerSurface(handle));
                }
                releaseTextureSurfaceOnly();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
            }
        });
    }

    private void bindActions() {
        findViewById(R.id.createButton).setOnClickListener(v -> runNative("Create Player", () -> {
            long handle = ensurePlayer();
            String surfaceResult = bindSurfaceIfReady(handle);
            String transportResult = applyRtspTransport(handle);
            String reconnectResult = applyReconnectOptions(handle);
            String audioResult = applyAudioOption(handle);
            return "{\"success\":true,\"handle\":" + handle + "}"
                    + "\nsurface=" + surfaceResult
                    + "\ntransport=" + transportResult
                    + "\nreconnect=" + reconnectResult
                    + "\naudio=" + audioResult;
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
            String reconnectResult = applyReconnectOptions(handle);
            String audioResult = applyAudioOption(handle);
            String prepareResult = FFmpegNative.preparePlayer(handle, requireUrl(), readTimeoutMs());
            return "surface=" + surfaceResult
                    + "\ntransport=" + transportResult
                    + "\nreconnect=" + reconnectResult
                    + "\naudio=" + audioResult
                    + "\nprepare=" + prepareResult;
        }));

        findViewById(R.id.startButton).setOnClickListener(v -> runNative("Start", () -> {
            long handle = ensurePlayer();
            String surfaceResult = bindSurfaceIfReady(handle);
            String transportResult = applyRtspTransport(handle);
            String audioResult = applyAudioOption(handle);
            return "surface=" + surfaceResult
                    + "\ntransport=" + transportResult
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
            return FFmpegNative.releasePlayer(handle);
        }));

        findViewById(R.id.snapshotButton).setOnClickListener(v -> runNative("Snapshot", () ->
                FFmpegNative.takePlayerSnapshot(requireHandle(), requireSnapshotPath())));

        findViewById(R.id.startRecordButton).setOnClickListener(v -> runNative("Start Record", () ->
                FFmpegNative.startPlayerRecord(requireHandle(), requireRecordPath())));

        findViewById(R.id.startSegmentRecordButton).setOnClickListener(v -> runNative("Start Segment Record", () ->
                FFmpegNative.startPlayerSegmentRecord(requireHandle(), requireSegmentPattern(), DEFAULT_SEGMENT_SECONDS)));

        findViewById(R.id.stopRecordButton).setOnClickListener(v -> runNative("Stop Record", () ->
                FFmpegNative.stopPlayerRecord(requireHandle())));

        findViewById(R.id.recordStateButton).setOnClickListener(v -> runNative("Record State", () ->
                FFmpegNative.getPlayerRecordState(requireHandle())));

        findViewById(R.id.stateButton).setOnClickListener(v -> runNative("Player State", () ->
                FFmpegNative.getPlayerState(requireHandle())));

        findViewById(R.id.statsButton).setOnClickListener(v -> runNative("Player Stats", () ->
                FFmpegNative.getPlayerStats(requireHandle())));

        findViewById(R.id.reconnectStateButton).setOnClickListener(v -> runNative("Reconnect State", () ->
                FFmpegNative.getPlayerReconnectState(requireHandle())));

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
    }

    private void bindSurfaceForExistingPlayer(String title) {
        long handle = getPlayerHandle();
        if (handle != 0) {
            runNative(title, () -> bindSurfaceIfReady(handle));
        } else {
            appendLog(title + ": no player yet");
        }
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
        return FFmpegNative.setPlayerRtspTransport(handle, selectedRtspTransport());
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

    private String requireSnapshotPath() {
        String path = snapshotPathEditText.getText().toString().trim();
        if (TextUtils.isEmpty(path)) {
            path = defaultFilePath("snapshot.png");
            snapshotPathEditText.setText(path);
        }
        ensureParentExists(path);
        return path;
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

    private void releaseTextureSurfaceOnly() {
        Surface oldSurface = textureSurface;
        textureSurface = null;
        if (oldSurface != null) {
            oldSurface.release();
        }
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
                releaseTextureSurfaceOnly();
            });
            releaseWorker.shutdown();
        } else {
            releaseTextureSurfaceOnly();
        }
        super.onDestroy();
    }

    private interface NativeAction {
        String run() throws Exception;
    }
}
