package com.example.motro;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.example.motro.ffmpeg.FFmpegPlayer;

import org.json.JSONObject;

import java.io.File;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Debug profiling entry point used by tools/collect_ffmpeg_latency_5min.py. */
public final class LatencyProfileActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "LatencyProfile";
    private static final String EXTRA_PREFIX = "com.example.motro.profile.";

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "LatencyProfileWorker"));
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean finishing = new AtomicBoolean(false);
    private final AtomicBoolean reconnectSeen = new AtomicBoolean(false);

    private SurfaceView surfaceView;
    private TextView statusView;
    private FFmpegPlayer player;
    private File outputDir;
    private BufferedWriter samplesWriter;
    private long playbackStartMs;
    private long latencyModeStartMs = -1;
    private long formalStartMs = -1;
    private long steadyRenderedBaseline = -1;
    private long resetCountBaseline = -1;
    private int warmupSec;
    private int durationSec;
    private int sampleSeq;
    private int formalSampleCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        warmupSec = Math.max(15, getIntent().getIntExtra(EXTRA_PREFIX + "WARMUP_SEC", 20));
        durationSec = Math.max(300, getIntent().getIntExtra(EXTRA_PREFIX + "DURATION_SEC", 300));

        FrameLayout root = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setBackgroundColor(0x88000000);
        statusView.setPadding(24, 16, 24, 16);
        statusView.setText("Latency profile: waiting for Surface");
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        textParams.gravity = Gravity.TOP;
        root.addView(statusView, textParams);
        setContentView(root);
        surfaceView.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (started.compareAndSet(false, true)) {
            worker.execute(() -> startProfile(holder.getSurface()));
        }
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        FFmpegPlayer current = player;
        if (current != null && !current.isReleased()) {
            current.clearSurface();
        }
    }

    private void startProfile(Surface surface) {
        try {
            String url = getIntent().getStringExtra(EXTRA_PREFIX + "URL");
            if (url == null || url.trim().isEmpty()) {
                throw new IllegalArgumentException("missing profile URL extra");
            }
            String transport = getIntent().getStringExtra(EXTRA_PREFIX + "TRANSPORT");
            if (transport == null || transport.isEmpty()) transport = "tcp";
            String latencyMode = getIntent().getStringExtra(EXTRA_PREFIX + "LATENCY_MODE");
            if (latencyMode == null || latencyMode.isEmpty()) latencyMode = "balanced";
            String renderMode = getIntent().getStringExtra(EXTRA_PREFIX + "RENDER_MODE");
            if (renderMode == null || renderMode.isEmpty()) renderMode = "mediacodec_nv12_gl";
            boolean hardware = getIntent().getBooleanExtra(EXTRA_PREFIX + "HARDWARE", true);
            boolean audio = getIntent().getBooleanExtra(EXTRA_PREFIX + "AUDIO", false);

            outputDir = new File(getExternalFilesDir(null), "latency_5min/device");
            deleteChildren(outputDir);
            if (!outputDir.mkdirs() && !outputDir.isDirectory()) {
                throw new IllegalStateException("cannot create " + outputDir);
            }
            samplesWriter = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(outputDir, "samples.jsonl")), StandardCharsets.UTF_8));

            player = new FFmpegPlayer();
            player.setListener((event, eventJson) -> {
                appendEvent(event, eventJson);
                if (formalStartMs >= 0 && ("disconnected".equalsIgnoreCase(event)
                        || "reconnecting".equalsIgnoreCase(event)
                        || "waiting_source".equalsIgnoreCase(event))) {
                    reconnectSeen.set(true);
                }
            });
            requireSuccess("surface", player.setSurface(surface));
            requireSuccess("transport", player.setRtspTransport(transport));
            requireSuccess("latencyMode", player.setLatencyMode(latencyMode));
            requireSuccess("diagnostics", player.setPlayerOption("diagnostics_mode", "basic"));
            requireSuccess("reconnect", player.setReconnectOptions(true, -1, 1000));
            requireSuccess("audio", player.setAudioEnabled(audio));
            requireSuccess("hardware", player.setHardwareDecodeEnabled(hardware));
            requireSuccess("renderMode", player.setHardwareRenderMode(renderMode));
            requireSuccess("prepare", player.prepare(url, 5000));
            requireSuccess("start", player.start());
            playbackStartMs = SystemClock.elapsedRealtime();
            writeConfig(transport, latencyMode, renderMode, hardware, audio);
            Log.i(TAG, "PROFILE_STARTED warmupSec=" + warmupSec + " durationSec=" + durationSec);
            worker.scheduleWithFixedDelay(this::sampleOnce, 0, 1, TimeUnit.SECONDS);
        } catch (Throwable t) {
            fail("START_FAILED", t);
        }
    }

    private void sampleOnce() {
        if (finishing.get()) return;
        try {
            long now = SystemClock.elapsedRealtime();
            String rawStats = player.getStats();
            JSONObject stats = new JSONObject(rawStats);
            String state = stats.optString("playerState", stats.optString("state", "UNKNOWN"));
            boolean playing = "PLAYING".equalsIgnoreCase(state);

            String phase = "WARMUP";
            if (now - playbackStartMs >= warmupSec * 1000L && latencyModeStartMs < 0) {
                requireSuccess("enable latency diagnostics",
                        player.setPlayerOption("diagnostics_mode", "latency"));
                latencyModeStartMs = now;
                phase = "LATENCY_SETTLE";
                Log.i(TAG, "LATENCY_DIAGNOSTICS_ENABLED");
            } else if (latencyModeStartMs >= 0 && formalStartMs < 0) {
                phase = "LATENCY_SETTLE";
                if (playing && stats.optBoolean("steadyStateValid", false)) {
                    long rendered = stats.optLong("nv12GlRenderedFrameCount",
                            stats.optLong("videoFrameCount", 0));
                    if (steadyRenderedBaseline < 0) {
                        steadyRenderedBaseline = rendered;
                        Log.i(TAG, "STEADY_STATE_CONFIRMED rendered=" + rendered);
                    } else if (rendered - steadyRenderedBaseline >= 30) {
                        resetCountBaseline = stats.optLong("formalDiagnosticsResetCount", 0);
                        requireSuccess("reset formal diagnostics",
                                player.setPlayerOption("reset_formal_diagnostics", "true"));
                        formalStartMs = now;
                        phase = "FORMAL";
                        Log.i(TAG, "FORMAL_STARTED afterExtraFrames="
                                + (rendered - steadyRenderedBaseline));
                    }
                }
            } else if (formalStartMs >= 0) {
                phase = "FORMAL";
            }

            JSONObject sample = new JSONObject();
            sample.put("seq", ++sampleSeq);
            sample.put("epochMs", System.currentTimeMillis());
            sample.put("elapsedRealtimeMs", now);
            sample.put("phase", phase);
            sample.put("formalElapsedMs", formalStartMs < 0 ? -1 : now - formalStartMs);
            sample.put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize());
            Runtime runtime = Runtime.getRuntime();
            sample.put("javaHeapUsedBytes", runtime.totalMemory() - runtime.freeMemory());
            File[] tasks = new File("/proc/self/task").listFiles();
            sample.put("threadCount", tasks == null ? -1 : tasks.length);
            sample.put("stats", stats);
            samplesWriter.write(sample.toString());
            samplesWriter.write('\n');
            samplesWriter.flush();
            if ("FORMAL".equals(phase)) formalSampleCount++;
            writeStatus(phase, state, now, null);
            showStatus(phase + "  " + (formalStartMs < 0 ? 0 : (now - formalStartMs) / 1000)
                    + "/" + durationSec + " s\n" + state + "  "
                    + stats.optInt("width") + "x" + stats.optInt("height") + "  "
                    + stats.optString("actualDecoderName"));

            if (formalStartMs >= 0) {
                if (now - formalStartMs >= 5000L
                        && stats.optLong("formalDiagnosticsResetCount", 0) <= resetCountBaseline) {
                    fail("DIAGNOSTICS_RESET_NOT_APPLIED", new IllegalStateException(
                            "resetCount=" + stats.optLong("formalDiagnosticsResetCount", 0)
                                    + ", baseline=" + resetCountBaseline));
                    return;
                }
                if (!playing || reconnectSeen.get()
                        || stats.optLong("reconnectAttemptCount", 0) > 0
                        || stats.optLong("readTimeoutCount", 0) > 0
                        || stats.optLong("readErrorCount", 0) > 0) {
                    fail("FORMAL_INTERRUPTED", new IllegalStateException(
                            "state=" + state + ", reconnect=" + reconnectSeen.get()
                                    + ", attempts=" + stats.optLong("reconnectAttemptCount", 0)
                                    + ", readTimeout=" + stats.optLong("readTimeoutCount", 0)
                                    + ", readError=" + stats.optLong("readErrorCount", 0)));
                    return;
                }
                if (now - formalStartMs >= durationSec * 1000L) {
                    complete(stats, now);
                }
            }
        } catch (Throwable t) {
            fail("SAMPLE_FAILED", t);
        }
    }

    private void complete(JSONObject stats, long now) {
        if (!finishing.compareAndSet(false, true)) return;
        try {
            JSONObject result = new JSONObject();
            result.put("status", "DONE");
            result.put("formalElapsedMs", now - formalStartMs);
            result.put("formalSamples", formalSampleCount);
            result.put("finalState", stats.optString("playerState"));
            atomicWrite(new File(outputDir, "result.json"), result.toString(2));
            Log.i(TAG, "PROFILE_DONE formalElapsedMs=" + (now - formalStartMs));
        } catch (Throwable t) {
            Log.e(TAG, "result write failed", t);
        } finally {
            closePlayer();
            mainHandler.post(this::finish);
        }
    }

    private void fail(String code, Throwable error) {
        if (!finishing.compareAndSet(false, true)) return;
        Log.e(TAG, code, error);
        try {
            if (outputDir != null) {
                JSONObject result = new JSONObject();
                result.put("status", "FAILED");
                result.put("failureCode", code);
                result.put("failureReason", error == null ? "unknown" : String.valueOf(error));
                result.put("formalElapsedMs", formalStartMs < 0 ? -1
                        : SystemClock.elapsedRealtime() - formalStartMs);
                atomicWrite(new File(outputDir, "result.json"), result.toString(2));
            }
        } catch (Throwable ignored) {
        } finally {
            closePlayer();
        }
    }

    private void closePlayer() {
        try { if (samplesWriter != null) samplesWriter.close(); } catch (Throwable ignored) {}
        try { if (player != null && !player.isReleased()) player.stop(); } catch (Throwable ignored) {}
        try { if (player != null && !player.isReleased()) player.release(); } catch (Throwable ignored) {}
    }

    private void writeConfig(String transport, String latencyMode, String renderMode,
                             boolean hardware, boolean audio) throws Exception {
        JSONObject config = new JSONObject();
        config.put("transport", transport);
        config.put("latencyMode", latencyMode);
        config.put("renderMode", renderMode);
        config.put("hardwareDecode", hardware);
        config.put("audio", audio);
        config.put("warmupSec", warmupSec);
        config.put("durationSec", durationSec);
        atomicWrite(new File(outputDir, "config.json"), config.toString(2));
    }

    private synchronized void appendEvent(String event, String eventJson) {
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(new File(outputDir, "events.jsonl"), true), StandardCharsets.UTF_8))) {
            JSONObject line = new JSONObject();
            line.put("epochMs", System.currentTimeMillis());
            line.put("event", event);
            line.put("data", new JSONObject(eventJson));
            out.write(line.toString());
            out.write('\n');
        } catch (Throwable t) {
            Log.w(TAG, "event write failed", t);
        }
    }

    private void writeStatus(String phase, String state, long now, String failure) throws Exception {
        JSONObject status = new JSONObject();
        status.put("phase", phase);
        status.put("state", state);
        status.put("formalElapsedMs", formalStartMs < 0 ? -1 : now - formalStartMs);
        status.put("failure", failure == null ? JSONObject.NULL : failure);
        atomicWrite(new File(outputDir, "status.json"), status.toString());
    }

    private static void requireSuccess(String operation, String json) throws Exception {
        JSONObject result = new JSONObject(json);
        if (!result.optBoolean("success", false)) {
            throw new IllegalStateException(operation + " failed: " + json);
        }
    }

    private static void atomicWrite(File target, String text) throws Exception {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(temp), StandardCharsets.UTF_8))) {
            out.write(text);
        }
        if (!temp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            if (!temp.renameTo(target)) {
                throw new IllegalStateException("cannot replace " + target);
            }
        }
    }

    private static void deleteChildren(File dir) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) deleteChildren(child);
            //noinspection ResultOfMethodCallIgnored
            child.delete();
        }
    }

    private void showStatus(String text) {
        mainHandler.post(() -> statusView.setText(text));
    }

    @Override
    protected void onDestroy() {
        if (finishing.compareAndSet(false, true)) closePlayer();
        worker.shutdownNow();
        super.onDestroy();
    }
}
