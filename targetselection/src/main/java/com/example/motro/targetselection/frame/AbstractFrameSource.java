package com.example.motro.targetselection.frame;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * {@link VideoFrameSource} 的公共基类：把“异步投递、恰好一次回调、取消、关闭、状态桥接”
 * 这些与平台无关的机制收敛到一处，子类只需要实现一件事 —— 在调用线程上把画面取出来。
 *
 * <p><b>本类不含任何 Android 依赖</b>（不是疏忽，是有意设计）：这样整套调度与竞态处理
 * 可以在纯 JVM 单测里被完整覆盖，Android 特有问题只留在很薄的子类里。
 * 平台相关的两个钩子见 {@link #captureFromView} 与 {@link #isCallingThreadMainThread()}。</p>
 *
 * <p><b>两个入口，用途不同</b>：</p>
 * <ul>
 *   <li>{@link #capture(FrameCaptureRequest)} —— <b>阻塞</b>，在调用线程上完成取帧，
 *       只能在工作线程调用；在主线程调用会明确返回 {@code UNSUPPORTED} 而不是把 UI 卡住。</li>
 *   <li>{@link #captureAsync(FrameCaptureRequest, FrameCaptureCallback, Executor)} ——
 *       <b>非阻塞</b>，主线程可安全调用；取帧在内部单线程上跑，结果投递到调用方给的 Executor。</li>
 * </ul>
 *
 * <p><b>线程约定</b>：</p>
 * <ul>
 *   <li>本类内部只用<b>一个</b>守护线程做异步取帧（懒创建、跨多次请求复用、{@link #close()} 时关闭），
 *       不会随点击次数增长 —— 连续点击策略（latest-wins）留到 Slice 11 统一处理。</li>
 *   <li>{@link #captureFromView} 在调用者线程（阻塞路径）或内部工作线程（异步路径）上执行，
 *       同一实例可能被并发调用，实现需保证线程安全。</li>
 * </ul>
 *
 * <p><b>关闭语义</b>：{@link #close()} 之后 —— 新的取帧立刻返回 {@code CANCELLED}；
 * 已在排队的异步任务也会在开始时看到已关闭而返回 {@code CANCELLED}；
 * 回调保证仍然被触发恰好一次（不会让调用方永远等下去）。</p>
 */
public abstract class AbstractFrameSource implements VideoFrameSource {

    /** 请求未给出预算时使用的上限，避免无限等待。 */
    public static final long DEFAULT_CAPTURE_TIMEOUT_MS = 1500L;

    private final Object stateLock = new Object();
    private boolean closed;
    private ExecutorService workExecutor;

    // ------------------------------------------------------------------ 子类钩子

    /**
     * 在<b>调用线程</b>上完成真正的取帧。子类在这里做 View/Surface 校验，
     * 并返回精确的 {@link FrameCaptureResult}。
     *
     * <p>约定：</p>
     * <ul>
     *   <li>返回值不得为 null；拿不到画面时返回 {@code NO_FRAME} / {@code SOURCE_NOT_READY} /
     *       {@code INVALID_SIZE} 等明确状态，<b>不要返回成功但空图的结果</b>。</li>
     *   <li>可以对异常做内部处理（例如把平台抛出的异常翻译成 {@code CAPTURE_FAILED}）；
     *       漏网的 {@link Exception} 会被基类兜底成 {@code CAPTURE_FAILED}，带上类名与消息，
     *       不会被静默吞掉。</li>
     *   <li>成功结果的帧必须由本方法自行构造（含 {@link FrameSourceType}），
     *       基类只负责传递与释放。</li>
     * </ul>
     *
     * @param request       取帧参数，非 null
     * @param requestTimeNs 本次取帧的计时起点（{@code System.nanoTime()}），
     *                      取自 {@link FrameCaptureRequest#requestTimeNs}，未标注时为调用时刻
     * @return 非 null 的结果
     */
    protected abstract FrameCaptureResult captureFromView(FrameCaptureRequest request, long requestTimeNs);

    /**
     * 当前线程是否为主线程。基类为纯 JVM 实现，默认返回 false；
     * Android 子类覆写为 Looper 判断，用于在阻塞入口上拦住主线程调用。
     */
    protected boolean isCallingThreadMainThread() {
        return false;
    }

    /** 子类清理钩子：释放 View 引用等。在 {@link #close()} 内、工作线程关闭之前调用。 */
    protected void onClose() {
    }

    /**
     * 本次取帧的等待上限（毫秒）。默认取 {@link FrameCaptureRequest#timeoutMs}，
     * 未给出（0）时回退到 {@link #DEFAULT_CAPTURE_TIMEOUT_MS}。
     */
    protected long captureTimeoutMs(FrameCaptureRequest request) {
        return request.timeoutMs > 0L ? request.timeoutMs : DEFAULT_CAPTURE_TIMEOUT_MS;
    }

    // ------------------------------------------------------------------ 公开 API

    /**
     * 阻塞式取帧，返回精确状态。<b>禁止在主线程调用</b>（会返回 {@code UNSUPPORTED} 而非卡住 UI）。
     *
     * @param request 非 null
     * @return 非 null；调用方必须 {@link FrameCaptureResult#takeFrame()} 或 {@code release()}
     * @throws IllegalArgumentException request 为 null
     */
    @Override
    public final FrameCaptureResult capture(FrameCaptureRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (isClosed()) {
            return FrameCaptureResult.cancelled("frame source is closed");
        }
        if (isCallingThreadMainThread()) {
            return FrameCaptureResult.unsupported(
                    "blocking capture() must not run on the main thread; use captureAsync() instead");
        }
        return captureGuarded(request);
    }

    /**
     * 非阻塞取帧，主线程可安全调用。
     *
     * @param request          非 null
     * @param callback         非 null；保证在 {@code callbackExecutor} 上被调用<b>恰好一次</b>
     * @param callbackExecutor 非 null，回调的执行线程；建议用主线程 Executor 以便直接更新 UI
     * @return 句柄，可用于 {@link FrameCaptureHandle#cancel()} 放弃本次请求
     * @throws IllegalArgumentException 任一参数为 null
     */
    public final FrameCaptureHandle captureAsync(FrameCaptureRequest request,
                                                 FrameCaptureCallback callback,
                                                 Executor callbackExecutor) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (callbackExecutor == null) {
            throw new IllegalArgumentException("callbackExecutor must not be null");
        }

        // 取消动作：cancel() 已赢下状态迁移，这里只需把 CANCELLED 投递出去（恰好一次）
        FrameCaptureHandle handle = FrameCaptureHandle.create(() -> postToExecutor(
                callbackExecutor, callback,
                FrameCaptureResult.cancelled("capture cancelled by caller")));

        if (isClosed()) {
            postIfFirst(handle, callbackExecutor, callback,
                    FrameCaptureResult.cancelled("frame source is closed"));
            return handle;
        }

        try {
            workExecutor().execute(() -> {
                FrameCaptureResult result;
                try {
                    result = capture(request);
                } catch (Throwable t) {
                    // 兜底：无论如何都要有结果，否则回调永远不会被触发
                    result = FrameCaptureResult.captureFailed(describe(t));
                }
                // finish() 失败说明调用方已取消 → 结果作废，就地释放，避免位图泄漏
                postIfFirst(handle, callbackExecutor, callback, result);
            });
        } catch (RejectedExecutionException e) {
            postIfFirst(handle, callbackExecutor, callback,
                    FrameCaptureResult.unsupported("frame source is closed"));
        }
        return handle;
    }

    /** 是否已关闭。 */
    public final boolean isClosed() {
        synchronized (stateLock) {
            return closed;
        }
    }

    /**
     * 关闭：停掉内部工作线程、释放子类资源。幂等。
     * 关闭后不会再有新的取帧真正执行（返回 {@code CANCELLED}）。
     */
    @Override
    public final void close() {
        final ExecutorService executor;
        synchronized (stateLock) {
            if (closed) {
                return;
            }
            closed = true;
            executor = workExecutor;
            workExecutor = null;
        }
        try {
            onClose();
        } finally {
            if (executor != null) {
                // shutdown 而非 shutdownNow：不打断正在进行的回读，避免设备侧状态不一致
                executor.shutdown();
            }
        }
    }

    // ------------------------------------------------------------------ Slice 0 桥接

    /**
     * 把 {@link #capture} 的结果反向桥接回 Slice 0 的异常式契约：
     * {@code SUCCESS → 帧}、{@code NO_FRAME → null}、其余状态 → {@link FrameCaptureException}。
     */
    @Override
    public final CapturedFrame captureFrame(FrameCaptureRequest request) throws FrameCaptureException {
        FrameCaptureResult result = capture(request);
        try {
            if (result.status == FrameCaptureStatus.SUCCESS) {
                return result.takeFrame();
            }
            if (result.status == FrameCaptureStatus.NO_FRAME) {
                return null;
            }
            throw new FrameCaptureException(result.status + ": " + result.debugReason);
        } finally {
            // takeFrame 之后是空操作；失败路径保证不会留下未释放的图像
            result.release();
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private FrameCaptureResult captureGuarded(FrameCaptureRequest request) {
        final long requestTimeNs = request.hasRequestTimeNs() ? request.requestTimeNs : System.nanoTime();
        try {
            FrameCaptureResult result = captureFromView(request, requestTimeNs);
            if (result == null) {
                // 子类违约：宁可报失败，也不让“空”变成一次成功
                return FrameCaptureResult.captureFailed(
                        "captureFromView() returned null; implementations must return an explicit status");
            }
            return result;
        } catch (Exception e) {
            // Error 不在兜底范围内：让 OOM 之类继续向上暴露，别伪装成一次普通的取帧失败
            return FrameCaptureResult.captureFailed(describe(e));
        }
    }

    private ExecutorService workExecutor() {
        synchronized (stateLock) {
            if (workExecutor == null) {
                workExecutor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "targetselection-frame-source");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            return workExecutor;
        }
    }

    /** 抢到交付权才投递；没抢到（已被取消）就释放结果。 */
    private static void postIfFirst(FrameCaptureHandle handle,
                                    Executor executor,
                                    FrameCaptureCallback callback,
                                    FrameCaptureResult result) {
        if (handle.finish()) {
            postToExecutor(executor, callback, result);
        } else {
            result.release();
        }
    }

    private static void postToExecutor(Executor executor,
                                       FrameCaptureCallback callback,
                                       FrameCaptureResult result) {
        try {
            executor.execute(() -> callback.onCaptureCompleted(result));
        } catch (RuntimeException e) {
            // 回调 Executor 拒绝执行：结果无人消费，必须就地释放，否则位图泄漏
            result.release();
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown capture failure";
        }
        String message = error.getMessage();
        if (message == null || message.isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return error.getClass().getSimpleName() + ": " + message;
    }
}
