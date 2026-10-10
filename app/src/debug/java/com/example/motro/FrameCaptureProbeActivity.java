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
import com.example.motro.targetselection.api.TargetSelectionConfig;
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
import com.example.motro.targetselection.geometry.VideoPointMappingStatus;
import com.example.motro.targetselection.geometry.VideoScaleType;
import com.example.motro.targetselection.vision.BlueMaskAnalyzer;
import com.example.motro.targetselection.vision.BlueMaskResult;
import com.example.motro.targetselection.vision.LabelExtractionResult;
import com.example.motro.targetselection.vision.LabelRegionExtractor;
import com.example.motro.targetselection.vision.TargetBoxAnalyzer;
import com.example.motro.targetselection.vision.TargetBoxDetectionResult;
import com.example.motro.targetselection.vision.TouchRoiRequest;

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
 *
 * <p>Slice 4 增加：点击后在工作线程上跑一次
 * 「取帧 → {@link TouchRoiRequest} → {@link BlueMaskAnalyzer}」，把结果并进
 * {@link TargetDebugState}，于是叠加层能显示检索 ROI 框、ROI 状态、蓝像素数与占比、耗时。
 * 三点刻意为之：<b>只分析点击附近</b>（ROI 半径来自配置）、<b>只在点击时跑一次</b>
 * （不随帧刷新）、<b>只在工作线程跑</b>（不碰 UI / 解码 / 渲染线程）。
 * 取到的帧用完立即释放，叠加层只拿紧凑位掩码的统计量、不持有任何 Bitmap。
 * 本探针显式打开 {@code debugMaskEnabled} 以便在真机上核对 ROI 原始像素；
 * 这是调试入口的 opt-in，正式路径默认关闭。</p>
 *
 * <p>Slice 5 增加：同一份掩码<u>继续往下走</u>两段 ——
 * {@code TargetBoxDetector} 恢复候选框、{@code TargetHitTester} 判定这次点击命中了哪个框，
 * 结果并进 {@link TargetDebugState}。叠加层因此能画出候选框（虚线黄）与选中框（粗实线洋红），
 * 并显示候选数、HIT/MISS、每框的 conf/comp/edges 与 detect/hit/total 三步耗时。
 * 之所以复用同一份掩码（{@code TargetBoxAnalyzer.analyze(BlueMaskResult, x, y)}）
 * 而不是重跑一遍：像素回读与 HSV 遍历没必要为调试付两遍。</p>
 *
 * <p>Slice 7 增加：命中之后在<b>同一帧、同一份几何</b>上再跑一次
 * {@link LabelRegionExtractor}，把选中的框顶部附近的 {@code id:xxxx} 区域找出来并裁好，
 * 结果并进 {@link TargetDebugState}。叠加层因此能画出标签搜索带（点划浅蓝）、
 * 全部标签候选（细实线橙）与选中的标签 ROI（粗实线白），并显示状态、候选数、
 * 距离/对齐/文本形态得分与 search/crop 分步耗时。
 * 仍然不识别文字内容（那是后续 Slice 的事）：本探针只回答"编号那一小块找到没有、在哪"。</p>
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
    /** 视觉分析的去重开关：连续点击时丢掉后来的请求，而不是排队等一串旧帧。 */
    private final AtomicBoolean analyzing = new AtomicBoolean(false);

    /**
     * 蓝色掩码分析器（播放器无关）。ROI 半径与蓝阈值全部来自
     * {@link TargetSelectionConfig}，本探针只额外打开 debug 像素保留以便真机核对。
     */
    private final BlueMaskAnalyzer blueMaskAnalyzer = new BlueMaskAnalyzer(
            TargetSelectionConfig.defaults().toBuilder().debugMaskEnabled(true).build());

    /**
     * 矩形恢复 + 命中判定的单入口（播放器无关）。
     * 与 {@link #blueMaskAnalyzer} 共用同一份配置，保证"探针看到的"与"单测/正式路径算的"一致。
     */
    private final TargetBoxAnalyzer targetBoxAnalyzer = new TargetBoxAnalyzer(
            TargetSelectionConfig.defaults().toBuilder().debugMaskEnabled(true).build());

    /**
     * 标签区域提取（播放器无关，Slice 7）。同样是<b>同一份配置</b> ——
     * 搜索结果带尺寸与裁剪下限都由它决定，探针不再制造第二套参数。
     */
    private final LabelRegionExtractor labelRegionExtractor = new LabelRegionExtractor(
            TargetSelectionConfig.defaults().toBuilder().debugMaskEnabled(true).build());

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
     * {@code ACTION_DOWN} 的最小验证链路：View 坐标 → 视频坐标 → 叠加层，
     * 随后异步补上「ROI + 蓝色掩码」。
     *
     * <p>用 {@code ACTION_DOWN} 而不是 UP：目标选择要的是「按下那一刻的画面」，
     * 等 UP 会引入手指悬停/滑动带来的时间不确定性。</p>
     *
     * <p>时刻用 {@link TouchSelectionRequest#downFromUptimeMillis} 从
     * {@code MotionEvent.getEventTime()}（uptimeMillis 基准）换算到本模块的
     * {@code System.nanoTime()} 基准 —— 这样它才能和取帧时刻对齐。直接把毫秒当纳秒
     * 塞进去，或者用墙上时间，都会对齐到完全错误的帧。</p>
     *
     * <p><b>两段式更新是有意的</b>：坐标映射是纯几何运算，可以立刻在按压瞬间画出来，
     * 让用户马上看到"我点到哪了"；而取帧 + 颜色分析要回读像素，只能在工作线程做，
     * 结果稍后并进来。首帧反馈不受分析耗时影响。</p>
     */
    private void handleDebugTouch(MotionEvent event) {
        final TargetDebugOverlayView target = overlay;
        if (target == null || event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return;
        }
        final TouchSelectionRequest request = TouchSelectionRequest.downFromUptimeMillis(
                event.getX(), event.getY(), event.getEventTime(),
                SystemClock.uptimeMillis(), System.nanoTime());

        // 几何只取一次快照：映射与后续 ROI 裁剪必须用同一份几何，否则两次调用之间
        // 若 View 尺寸/旋转变了，ROI 会落在与触摸点不同的坐标系上。
        final VideoGeometry geometry = currentGeometry();
        final VideoPointMappingResult mapping =
                VideoCoordinateMapper.map(geometry, request.viewX, request.viewY);

        // 1) 立即反馈：先把「触摸 → 视频坐标」画出来，视觉分析是异步补上的
        final TargetDebugState state = TargetDebugState.of(mapping, request.eventTimeNs);
        target.setDebugState(state);
        Log.i(TAG, "debugTouch " + state.debugString());

        // 2) 只有拿到有效视频坐标才值得取帧分析（点在黑边上时不要浪费一次帧回读）
        if (mapping.status == VideoPointMappingStatus.SUCCESS) {
            startBlueMaskAnalysis(geometry, mapping, request.eventTimeNs);
        }
    }

    /**
     * 在<b>工作线程</b>上跑一次「取帧 → ROI → 蓝色掩码」，结果并进调试状态。
     *
     * <p>刻意做成「点击时一次」而不是「每帧一次」：视觉分析的目的是回答"我刚才点的那块区域
     * 是不是蓝色"，它天然是事件驱动的。每帧跑会让分析器和解码器抢 CPU，正是要避免的事。</p>
     */
    private void startBlueMaskAnalysis(VideoGeometry geometry,
                                       VideoPointMappingResult mapping,
                                       long eventTimeNs) {
        final SurfaceViewFrameSource source = frameSource;
        if (source == null || released.get()) {
            Log.w(TAG, "blueMask skipped: frame source not ready");
            return;
        }
        if (!analyzing.compareAndSet(false, true)) {
            Log.w(TAG, "blueMask skipped: previous analysis still running");
            return;
        }
        captureWorker.execute(() -> {
            try {
                final FrameCaptureResult result =
                        source.capture(FrameCaptureRequest.latestFrame(CAPTURE_TIMEOUT_MS));
                try {
                    final CapturedFrame frame = result.frame();
                    if (frame == null || !frame.hasImage()) {
                        Log.w(TAG, "blueMask skipped: capture " + result.status
                                + (result.debugReason == null ? "" : " " + result.debugReason));
                        return;
                    }
                    final TouchRoiRequest roiRequest = TouchRoiRequest.forMapping(frame, geometry, mapping);
                    if (roiRequest == null) {
                        Log.w(TAG, "blueMask skipped: mapping carries no video coordinates");
                        return;
                    }
                    final BlueMaskResult mask = blueMaskAnalyzer.analyze(roiRequest);
                    Log.i(TAG, "blueMask " + mask.debugString()
                            + (mask.debugRoiArgb == null
                                    ? "" : " debugPixels=" + mask.debugRoiArgb.length));

                    // 同一份掩码继续往下走：恢复候选框 + 判定这次点击命中了哪个框。
                    // 传 mapping 里的视频坐标（与掩码同一次点击、同坐标系），不要用 ROI 局部坐标。
                    final TargetBoxDetectionResult detection =
                            targetBoxAnalyzer.analyze(mask, mapping.videoX, mapping.videoY);
                    Log.i(TAG, "boxes " + detection.debugString());

                    // Slice 7：有选中的框才谈得上"找它的编号"。
                    // 用同一个 frame 与同一份 geometry —— 换一份几何会让搜索带落在别的坐标系上。
                    // 注意 NO_LABEL 不是 MISS：框依然命中，只是编号区域没找到（上层报 HIT_ID_UNKNOWN）。
                    final LabelExtractionResult label = detection.selectedBox == null
                            ? null
                            : labelRegionExtractor.extract(frame, geometry, detection.selectedBox.rectInVideo);
                    if (label != null) {
                        Log.i(TAG, "labelRegion " + label.debugString());
                    }

                    final TargetDebugState.Builder builder = TargetDebugState
                            .builder(mapping, eventTimeNs)
                            .blueMask(mask)
                            .boxDetection(detection);
                    if (label != null) {
                        builder.labelExtraction(label);
                    }
                    final TargetDebugState updated = builder.build();
                    mainHandler.post(() -> {
                        final TargetDebugOverlayView current = overlay;
                        if (current != null) {
                            current.setDebugState(updated);
                        }
                    });
                } finally {
                    // 无论成功失败都立刻释放回读位图 —— 探针不缓存任何帧
                    result.release();
                }
            } catch (Throwable t) {
                Log.e(TAG, "blueMask failed", t);
            } finally {
                analyzing.set(false);
            }
        });
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
