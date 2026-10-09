package com.example.motro;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.motro.ffmpeg.FFmpegPlayer;
import com.example.motro.targetselection.api.TouchSelectionRequest;
import com.example.motro.targetselection.debug.TargetDebugOverlayView;
import com.example.motro.targetselection.debug.TargetDebugState;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.frame.FrameCaptureResult;
import com.example.motro.targetselection.frame.SurfaceViewFrameSource;
import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;
import com.example.motro.targetselection.geometry.VideoScaleType;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Debug 抓帧探针（仅存在于 debug APK）：验证「任意播放器 → SurfaceView → 取帧」这条链路真的能出图。
 *
 * <p>它同时是「播放器无关」的结构性证据：本 Activity 只把 {@code SurfaceView} 交给
 * {@link SurfaceViewFrameSource}，取帧实现完全不知道画面是哪个播放器渲染的 ——
 * 换成 Media3 / VLC / GStreamer 只要同样输出到 SurfaceView，本探针一字不改就能继续用。</p>
 *
 * <p>用法（两种模式）：</p>
 * <ul>
 *   <li>带 {@code --es com.example.motro.probe.URL "rtsp://..."} → 用 FFmpegPlayer 播放该地址；</li>
 *   <li>不带 URL → 直接往 SurfaceView 画纯色测试图案，用来验证取帧本身（不依赖任何流）。</li>
 * </ul>
 *
 * <p>刻意保持最小：没有 Target Selection UI、不落盘、不长期持有位图。
 * 日志只输出 width/height、耗时与结果状态，供 {@code adb logcat} 核对。</p>
 *
 * <p>Slice 3 增加的最小接入：叠一层 {@link TargetDebugOverlayView}，把
 * 「ACTION_DOWN → {@link VideoCoordinateMapper} → {@link TargetDebugState} → 叠加层」
 * 这条链路在真机上跑通 —— 点击画面即可看到触摸点、内容区、映射后的视频坐标与状态。
 * 几何由本 Activity 自己算（探针拿不到渲染器的真实 contentRect，用 FIT_CENTER 兜底），
 * 这在正式接入时应当换成播放器/渲染层汇报的真实几何。仍然是调试入口，不是正式业务。</p>
 */
public final class FrameCaptureProbeActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "FrameCaptureProbe";
    private static final String EXTRA_PREFIX = "com.example.motro.probe.";
    private static final long CAPTURE_TIMEOUT_MS = 1500L;
    private static final long[] AUTO_CAPTURE_DELAYS_MS = {1500L, 3000L, 4500L};
    private static final int PATTERN_COLOR = 0xFF2E7D32;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService captureWorker = Executors.newSingleThreadExecutor(
            runnable -> new Thread(runnable, "FrameCaptureProbeWorker"));
    private final AtomicBoolean capturing = new AtomicBoolean(false);
    private final AtomicBoolean released = new AtomicBoolean(false);

    private SurfaceView surfaceView;
    private TextView statusView;
    private TargetDebugOverlayView overlay;
    private FFmpegPlayer player;
    private SurfaceViewFrameSource frameSource;
    private Thread patternThread;
    private volatile boolean patternRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 叠在播放器 SurfaceView 之上：只观察触摸、绝不消费（onTouchEvent 恒 false），
        // 所以播放器的手势不受影响。放在面板之前加，保证面板仍在最上层可点。
        overlay = new TargetDebugOverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setOnTouchListener((view, event) -> {
            handleDebugTouch(event);
            return false;   // 只观察不截获：事件继续传给下方的播放器
        });

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0x99000000);
        panel.setPadding(24, 16, 24, 16);

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setText("FrameCaptureProbe: waiting for Surface");
        panel.addView(statusView);

        Button captureButton = new Button(this);
        captureButton.setText("Capture frame");
        captureButton.setOnClickListener(view -> captureOnce("manual"));
        panel.addView(captureButton);

        Button clearOverlayButton = new Button(this);
        clearOverlayButton.setText("Clear overlay");
        clearOverlayButton.setOnClickListener(view -> {
            overlay.clearDebugState();
            Log.i(TAG, "debugTouch cleared");
        });
        panel.addView(clearOverlayButton);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.BOTTOM;
        root.addView(panel, panelParams);
        setContentView(root);

        surfaceView.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        frameSource = new SurfaceViewFrameSource(surfaceView);
        String url = getIntent().getStringExtra(EXTRA_PREFIX + "URL");
        if (url == null || url.trim().isEmpty()) {
            Log.i(TAG, "no URL extra → drawing a solid test pattern onto the SurfaceView");
            startTestPattern(holder);
        } else {
            startPlayer(holder.getSurface(), url.trim());
        }
        for (long delay : AUTO_CAPTURE_DELAYS_MS) {
            mainHandler.postDelayed(() -> captureOnce("auto@" + delay + "ms"), delay);
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 不需要处理：SurfaceViewFrameSource 每次取帧都会重新读 View 的有效尺寸
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopTestPattern();
        FFmpegPlayer current = player;
        if (current != null && !current.isReleased()) {
            current.clearSurface();
        }
    }

    // ------------------------------------------------------------------ 取帧

    /** 抓一帧并把 width/height、耗时、结果打到 logcat；位图用完立即释放，不长期持有。 */
    private void captureOnce(String trigger) {
        final SurfaceViewFrameSource source = frameSource;
        if (source == null || released.get()) {
            Log.w(TAG, "capture[" + trigger + "] skipped: frame source not ready");
            return;
        }
        if (!capturing.compareAndSet(false, true)) {
            Log.w(TAG, "capture[" + trigger + "] skipped: previous capture still running");
            return;
        }
        captureWorker.execute(() -> {
            try {
                long startNs = System.nanoTime();
                FrameCaptureResult result = source.capture(FrameCaptureRequest.latestFrame(CAPTURE_TIMEOUT_MS));
                long costMs = (System.nanoTime() - startNs) / 1_000_000L;

                CapturedFrame frame = result.frame();
                String size = frame == null ? "-" : (frame.width + "x" + frame.height);
                String sourceType = frame == null ? "-" : String.valueOf(frame.sourceType);
                String rawCode = result.rawCode == FrameCaptureResult.NO_RAW_CODE
                        ? "none" : String.valueOf(result.rawCode);

                Log.i(TAG, "capture[" + trigger + "] result=" + result.status
                        + " size=" + size
                        + " costMs=" + costMs
                        + " sourceType=" + sourceType
                        + " rawCode=" + rawCode
                        + (result.debugReason == null ? "" : " reason=" + result.debugReason));

                final String line = result.status + "  " + size + "  " + costMs + "ms";
                result.release();  // 立即释放回读位图 —— 探针不缓存任何帧
                mainHandler.post(() -> statusView.setText(line + "\n" + formatPlayerState()));
            } catch (Throwable t) {
                Log.e(TAG, "capture[" + trigger + "] threw", t);
            } finally {
                capturing.set(false);
            }
        });
    }

    // ------------------------------------------------------------------ 调试叠加层

    /**
     * {@code ACTION_DOWN} 的最小验证链路：View 坐标 → 视频坐标 → 叠加层。
     *
     * <p>用 {@code ACTION_DOWN} 而不是 UP：目标选择要的是「按下那一刻的画面」，
     * 等 UP 会引入手指悬停/滑动带来的时间不确定性。</p>
     *
     * <p>时刻用 {@link TouchSelectionRequest#downFromUptimeMillis} 从
     * {@code MotionEvent.getEventTime()}（uptimeMillis 基准）换算到本模块的
     * {@code System.nanoTime()} 基准 —— 这样它才能和取帧时刻对齐。直接把毫秒当纳秒
     * 塞进去，或者用墙上时间，都会对齐到完全错误的帧。</p>
     */
    private void handleDebugTouch(MotionEvent event) {
        final TargetDebugOverlayView target = overlay;
        if (target == null || event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return;
        }
        final TouchSelectionRequest request = TouchSelectionRequest.downFromUptimeMillis(
                event.getX(), event.getY(), event.getEventTime(),
                SystemClock.uptimeMillis(), System.nanoTime());

        final VideoPointMappingResult mapping =
                VideoCoordinateMapper.map(currentGeometry(), request.viewX, request.viewY);
        final TargetDebugState state = TargetDebugState.of(mapping, request.eventTimeNs);
        target.setDebugState(state);

        Log.i(TAG, "debugTouch " + state.debugString());
    }

    /**
     * 探针用的几何：优先用播放器汇报的视频尺寸，拿不到就认为「视频尺寸 == View 尺寸」。
     *
     * <p>缩放方式固定 {@code FIT_CENTER} —— 探针拿不到渲染器的真实 contentRect，
     * 这里只是兜底推导。正式接入时应由播放器/渲染层直接汇报真实可见区域
     * （{@code VideoGeometry.create(...)} 那条路），而不是在这里猜。</p>
     */
    private VideoGeometry currentGeometry() {
        final SurfaceView view = surfaceView;
        final int viewWidth = view == null ? 0 : view.getWidth();
        final int viewHeight = view == null ? 0 : view.getHeight();

        int videoWidth = 0;
        int videoHeight = 0;
        final FFmpegPlayer current = player;
        if (current != null) {
            try {
                JSONObject stats = new JSONObject(current.getStats());
                videoWidth = stats.optInt("width");
                videoHeight = stats.optInt("height");
            } catch (Throwable ignored) {
                // 拿不到统计就退回“视频尺寸 == View 尺寸”
            }
        }
        if (videoWidth <= 0 || videoHeight <= 0) {
            videoWidth = viewWidth;
            videoHeight = viewHeight;
        }
        return VideoGeometry.createWithScale(videoWidth, videoHeight,
                viewWidth, viewHeight, VideoScaleType.FIT_CENTER);
    }

    private String formatPlayerState() {
        FFmpegPlayer current = player;
        if (current == null) {
            return "source: test pattern";
        }
        try {
            JSONObject stats = new JSONObject(current.getStats());
            return "player: " + stats.optString("playerState", "?")
                    + "  " + stats.optInt("width") + "x" + stats.optInt("height");
        } catch (Throwable t) {
            return "player: state unavailable";
        }
    }

    // ------------------------------------------------------------------ 两种画面来源

    private void startPlayer(Surface surface, String url) {
        try {
            player = new FFmpegPlayer();
            requireSuccess("surface", player.setSurface(surface));
            requireSuccess("prepare", player.prepare(url, 5000));
            requireSuccess("start", player.start());
            Log.i(TAG, "player started for " + url);
        } catch (Throwable t) {
            Log.e(TAG, "player start failed", t);
            statusView.setText("player start failed: " + t.getMessage());
        }
    }

    /** 无流可播时自带画面：往 SurfaceView 画布填纯色，保证 PixelCopy 有真实内容可读。 */
    private void startTestPattern(final SurfaceHolder holder) {
        patternRunning = true;
        patternThread = new Thread(() -> {
            while (patternRunning) {
                Canvas canvas = null;
                try {
                    canvas = holder.lockCanvas();
                    if (canvas != null) {
                        canvas.drawColor(PATTERN_COLOR);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "test pattern draw failed", t);
                    break;
                } finally {
                    if (canvas != null) {
                        try {
                            holder.unlockCanvasAndPost(canvas);
                        } catch (Throwable ignored) {
                            // Surface 可能已销毁，交给 surfaceDestroyed 收尾
                        }
                    }
                }
                try {
                    Thread.sleep(200L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "FrameCaptureProbePattern");
        patternThread.start();
    }

    private void stopTestPattern() {
        patternRunning = false;
        Thread thread = patternThread;
        patternThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private static void requireSuccess(String operation, String json) throws Exception {
        JSONObject result = new JSONObject(json == null ? "" : json);
        if (!result.optBoolean("success", false)) {
            throw new IllegalStateException(operation + " failed: " + json);
        }
    }

    @Override
    protected void onDestroy() {
        released.set(true);
        mainHandler.removeCallbacksAndMessages(null);
        stopTestPattern();
        try {
            if (frameSource != null) {
                frameSource.close();
            }
        } catch (Throwable ignored) {
            // 关闭失败不影响退出
        }
        try {
            if (player != null && !player.isReleased()) {
                player.stop();
                player.release();
            }
        } catch (Throwable ignored) {
            // 同上
        }
        captureWorker.shutdownNow();
        super.onDestroy();
    }
}
