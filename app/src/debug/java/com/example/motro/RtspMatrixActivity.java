package com.example.motro;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.example.motro.ffmpeg.FFmpegPlayer;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Debug-only hardware harness; credentials are read from app-private config, never emitted. */
public final class RtspMatrixActivity extends Activity {
    private final ExecutorService control = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService samples = Executors.newSingleThreadScheduledExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile FFmpegPlayer player;
    private JSONObject config;
    private String caseId = "invalid";
    private File recordDir;
    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            emit("heartbeat", new JSONObject());
            if (!stopped.get()) main.postDelayed(this, 1000);
        }
    };
    private static final String[] STATS = {
        "playerState", "state", "effectiveRtspTransport", "videoCodec", "audioCodec", "sourceHasAudio", "audioEnabled",
        "videoFrameCount", "renderedFrameCount", "audioPacketCount", "audioDecodedFrameCount", "audioPcmBlockCount", "audioSinkWriteCount",
        "audioSinkWriteErrorCount", "audioSinkControlledCancelCount", "audioWorkerRunning",
        "audioWorkerStartCount", "audioWorkerJoinCount", "audioWorkerStaleBlockCount",
        "audioQueueGeneration", "audioQueueBlockCount", "audioPlaybackClockValid",
        "audioPlaybackClockUs", "audioPlaybackHeadFrames", "audioClockGeneration",
        "audioSinkReady", "audioPlayable", "audioReconnectRecoveryCount", "reconnectAttemptCount",
        "reconnectSuccessCount", "reconnectInitialDelayMs", "reconnectMaxDelayMs",
        "ioDeadlineTimeoutCount", "readTimeoutCount", "surfaceAttached", "inputOpenCount",
        "videoDecoderOpenCount", "lastFrameOutputType", "startupKeyFrameWaitActive"
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            File file = new File(getFilesDir(), "rtsp-matrix.json");
            config = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            caseId = config.getString("caseId");
            if (!caseId.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("caseId");
            recordDir = new File(getExternalFilesDir(null), "rtsp-matrix/" + caseId);
            if (!recordDir.mkdirs() && !recordDir.isDirectory()) throw new IllegalStateException("record directory");
            SurfaceView view = new SurfaceView(this);
            view.setKeepScreenOn(true);
            setContentView(view);
            view.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {
                    if (started.compareAndSet(false, true)) control.execute(() -> startPlayer(holder));
                }
                @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int height) { }
                @Override public void surfaceDestroyed(SurfaceHolder h) { stopPlayer(); }
            });
            main.post(heartbeat);
        } catch (Exception error) { emitError("configuration", error); }
    }

    private void startPlayer(SurfaceHolder holder) {
        try {
            FFmpegPlayer p = new FFmpegPlayer(); player = p;
            p.setListener((event, json) -> {
                try {
                    JSONObject raw = new JSONObject(json);
                    JSONObject data = select(raw, new String[]{"event", "playerState", "attempt", "delayMs", "errorCode"});
                    // Numeric code and a boolean retain 404 evidence without copying source URLs.
                    data.put("source404", raw.optString("errorMessage").contains("404")
                            || raw.optString("errorMessage").toLowerCase().contains("not found"));
                    emit("event", data);
                } catch (Exception error) { emitError("event", error); }
            });
            require(p.setSurface(holder.getSurface()));
            require(p.setRtspTransport(config.getString("transport")));
            require(p.setPlayerOption("diagnostics_mode", "basic"));
            require(p.setReconnectOptions(true, -1, 1000));
            require(p.setAudioEnabled(config.getBoolean("audio")));
            require(p.prepare(config.getString("url"), 5000));
            require(p.start());
            samples.scheduleWithFixedDelay(this::sample, 0, 500, TimeUnit.MILLISECONDS);
            emit("started", new JSONObject().put("recordDir", recordDir.getAbsolutePath()));
        } catch (Exception error) { emitError("start", error); }
    }

    private final AtomicBoolean recordStarted = new AtomicBoolean();
    private void sample() {
        try {
            FFmpegPlayer p = player;
            if (p == null || p.isReleased()) return;
            JSONObject raw = new JSONObject(p.getStats());
            JSONObject data = select(raw, STATS);
            JSONObject recorder = new JSONObject(p.getRecordState());
            data.put("recorder", select(recorder, new String[]{"recording", "state", "queuePackets", "queueBytes",
                    "queueDrops", "queueHighWatermark", "packetsWritten", "writeErrors", "lastError", "videoPacketCount", "audioPacketCount", "completedSegmentCount"}));
            data.put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize());
            data.put("fdCount", count("/proc/self/fd"));
            data.put("threadCount", count("/proc/self/task"));
            emit("stats", data);
            if (config.getBoolean("recording") && "PLAYING".equals(data.optString("playerState"))
                    && recordStarted.compareAndSet(false, true)) {
                control.execute(() -> {
                    try {
                        require(p.startSegmentRecord(new File(recordDir, "segment_%03d.mp4").getAbsolutePath(), config.optInt("segmentSeconds", 5)));
                        emit("record_started", new JSONObject());
                    } catch (Exception error) { emitError("record_start", error); }
                });
            }
        } catch (Exception error) { if (!stopped.get()) emitError("sample", error); }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String command = intent.getStringExtra("command");
        if ("stop".equals(command)) stopPlayer();
        else if ("fault".equals(command) || "restored".equals(command)) {
            emit(command, new JSONObject());
        }
    }
    private void stopPlayer() {
        if (!stopped.compareAndSet(false, true)) return;
        emit("stop_requested", new JSONObject());
        samples.shutdown();
        control.execute(() -> {
            try {
                FFmpegPlayer p = player;
                if (p != null) {
                    JSONObject recorder = new JSONObject(p.stopRecord());
                    emit("record_stopped", select(recorder, new String[]{"success", "state", "queuePackets", "queueBytes", "queueDrops", "packetsWritten", "writeErrors"}));
                    require(p.stop()); p.release();
                }
                emit("stopped", new JSONObject().put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
                        .put("fdCount", count("/proc/self/fd")).put("threadCount", count("/proc/self/task")));
            } catch (Exception error) { emitError("stop", error); }
        });
    }
    @Override protected void onDestroy() {
        stopPlayer(); main.removeCallbacks(heartbeat); control.shutdown(); super.onDestroy();
    }
    private int count(String path) { String[] entries = new File(path).list(); return entries == null ? -1 : entries.length; }
    private void require(String json) throws Exception {
        if (!new JSONObject(json).optBoolean("success")) throw new IllegalStateException("player operation failed");
    }
    private static JSONObject select(JSONObject raw, String[] keys) throws Exception {
        JSONObject result = new JSONObject();
        for (String key : keys) if (raw.has(key)) result.put(key, raw.get(key) instanceof String
                ? raw.getString(key).replaceAll("(?i)rtsps?://[^\\s\"<>]+", "rtsp://[REDACTED]") : raw.get(key));
        return result;
    }
    private void emitError(String phase, Exception error) {
        try { emit("error", new JSONObject().put("phase", phase).put("type", error.getClass().getSimpleName())); }
        catch (Exception ignored) { }
    }
    private void emit(String kind, JSONObject data) {
        try { Log.i("RTSPMatrix", new JSONObject().put("caseId", caseId).put("kind", kind)
                .put("t", SystemClock.elapsedRealtime()).put("data", data).toString()); }
        catch (Exception ignored) { }
    }
}
