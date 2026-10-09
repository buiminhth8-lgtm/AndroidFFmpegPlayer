package com.example.motro.targetselection.frame;

/**
 * 一次取帧的**完整结果**：状态 + 可选图像 + 诊断信息。不可变对象（图像所有权可转移/释放）。
 *
 * <p>与 Slice 0 的 {@link VideoFrameSource#captureFrame} 的关系：那个方法只能“返回帧或抛异常”，
 * 表达不了 UTF-8 之外的丰富失败原因。本类补齐这一点，并由 {@link VideoFrameSource#capture}
 * 作为推荐入口。两者互相桥接的规则见 {@link AbstractFrameSource}。</p>
 *
 * <p><b>不变量（构造处强制）</b>：{@link #status} 为 {@link FrameCaptureStatus#SUCCESS}
 * 当且仅当持有一张 {@link #hasFrame() 有图像} 的帧。因此不会出现
 * “状态成功但没有图”或“状态失败却带着图”这种自相矛盾的结果。</p>
 *
 * <p><b>所有权与释放（必须遵守，否则会泄漏位数兆字节的位图）</b>：</p>
 * <ul>
 *   <li>当 {@link #status} 为 {@code SUCCESS} 时，本对象**持有**帧的所有权。</li>
 *   <li>消费方必须二选一，且只需一次：{@link #takeFrame()} 把所有权取走，
 *       或 {@link #release()} 就地释放。</li>
 *   <li>{@link #takeFrame()} 与 {@link #release()} 都幂等；先 take 再 release 是安全的空操作。</li>
 *   <li>失败结果不持有任何图像，{@link #release()} 是空操作。</li>
 * </ul>
 *
 * <p>线程约定：字段 final 可跨线程读；图像句柄的读写受内部锁保护。
 * 回调式入口（{@link AbstractFrameSource#captureAsync}）会把结果交给回调线程，
 * 由回调方负责释放。</p>
 */
public final class FrameCaptureResult {

    /** {@link #rawCode} 的哨兵值：本次结果不涉及平台错误码。 */
    public static final int NO_RAW_CODE = Integer.MIN_VALUE;

    private final Object lock = new Object();
    private CapturedFrame frame;
    private boolean released;

    /** 结局，非 null。 */
    public final FrameCaptureStatus status;

    /**
     * 平台原始返回码（诊断用），无则 {@link #NO_RAW_CODE}。
     * 典型来源是 PixelCopy 的 result code —— 刻意只放在这里，
     * 不让它泄漏成业务层的分支条件。
     */
    public final int rawCode;

    /** 人可读的失败原因，可为 null。会被原样带进上层日志，不要放敏感内容。 */
    public final String debugReason;

    private FrameCaptureResult(FrameCaptureStatus status, CapturedFrame frame,
                               int rawCode, String debugReason) {
        this.status = status;
        this.frame = frame;
        this.rawCode = rawCode;
        this.debugReason = debugReason;
    }

    /**
     * 成功结果。
     *
     * @param frame 必须非 null 且 {@link CapturedFrame#hasImage()} 为 true
     * @throws IllegalArgumentException frame 为空帧（此时应用 {@link #noFrame}）
     */
    public static FrameCaptureResult success(CapturedFrame frame) {
        return success(frame, NO_RAW_CODE);
    }

    /** 成功结果 + 平台返回码。 */
    public static FrameCaptureResult success(CapturedFrame frame, int rawCode) {
        if (frame == null) {
            throw new IllegalArgumentException("success requires a frame; use noFrame() instead");
        }
        if (!frame.hasImage()) {
            throw new IllegalArgumentException(
                    "success requires a frame with image; use noFrame() instead");
        }
        return new FrameCaptureResult(FrameCaptureStatus.SUCCESS, frame, rawCode, null);
    }

    /**
     * 失败结果（规范入口）。
     *
     * @param status 非 null 且不得为 {@link FrameCaptureStatus#SUCCESS}
     * @throws IllegalArgumentException status 为 null 或 SUCCESS
     */
    public static FrameCaptureResult failure(FrameCaptureStatus status, String debugReason) {
        return failure(status, debugReason, NO_RAW_CODE);
    }

    /** 失败结果 + 平台返回码。 */
    public static FrameCaptureResult failure(FrameCaptureStatus status, String debugReason, int rawCode) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (status == FrameCaptureStatus.SUCCESS) {
            throw new IllegalArgumentException("use success(frame) for SUCCESS results");
        }
        return new FrameCaptureResult(status, null, rawCode, debugReason);
    }

    /** 来源当前没有可用画面（正常时序）。 */
    public static FrameCaptureResult noFrame(String debugReason) {
        return failure(FrameCaptureStatus.NO_FRAME, debugReason);
    }

    /** View / 表面尚未就绪。 */
    public static FrameCaptureResult sourceNotReady(String debugReason) {
        return failure(FrameCaptureStatus.SOURCE_NOT_READY, debugReason);
    }

    /** View 尺寸为 0 或目标尺寸非法。 */
    public static FrameCaptureResult invalidSize(String debugReason) {
        return failure(FrameCaptureStatus.INVALID_SIZE, debugReason);
    }

    /** 该来源不支持本次请求。 */
    public static FrameCaptureResult unsupported(String debugReason) {
        return failure(FrameCaptureStatus.UNSUPPORTED, debugReason);
    }

    /** 超时未完成；{@code rawCode} 保留平台侧的超时码。 */
    public static FrameCaptureResult timeout(String debugReason, int rawCode) {
        return failure(FrameCaptureStatus.TIMEOUT, debugReason, rawCode);
    }

    /** 请求被取消。 */
    public static FrameCaptureResult cancelled(String debugReason) {
        return failure(FrameCaptureStatus.CANCELLED, debugReason);
    }

    /** 取帧确实失败；{@code rawCode} 保留平台侧的失败码。 */
    public static FrameCaptureResult captureFailed(String debugReason, int rawCode) {
        return failure(FrameCaptureStatus.CAPTURE_FAILED, debugReason, rawCode);
    }

    /** 取帧确实失败，无平台返回码。 */
    public static FrameCaptureResult captureFailed(String debugReason) {
        return failure(FrameCaptureStatus.CAPTURE_FAILED, debugReason, NO_RAW_CODE);
    }

    /** 是否成功。 */
    public boolean isSuccess() {
        return status == FrameCaptureStatus.SUCCESS;
    }

    /** 是否还持有可用图像（被 take 走或 release 后为 false）。 */
    public boolean hasFrame() {
        synchronized (lock) {
            return !released && frame != null;
        }
    }

    /** 当前持有的帧；已取走/已释放/失败结果返回 null。 */
    public CapturedFrame frame() {
        synchronized (lock) {
            return released ? null : frame;
        }
    }

    /** 便捷方法：当前持有的图像载荷；无则 null。 */
    public FrameImage image() {
        CapturedFrame current = frame();
        return current == null ? null : current.image();
    }

    /**
     * 把帧的所有权取走。取走后本对象不再持有图像，再调用 {@link #release()} 是空操作。
     *
     * @return 帧；已取走或本就无图时返回 null
     */
    public CapturedFrame takeFrame() {
        synchronized (lock) {
            CapturedFrame taken = frame;
            frame = null;
            return taken;
        }
    }

    /** 释放当前持有的图像（幂等）。{@link #takeFrame()} 之后调用是空操作。 */
    public void release() {
        final CapturedFrame toRelease;
        synchronized (lock) {
            if (released) {
                return;
            }
            released = true;
            toRelease = frame;
            frame = null;
        }
        // 锁外释放：不持锁等待系统回收
        if (toRelease != null) {
            toRelease.release();
        }
    }

    @Override
    public String toString() {
        return "FrameCaptureResult{status=" + status
                + ", rawCode=" + (rawCode == NO_RAW_CODE ? "none" : String.valueOf(rawCode))
                + ", hasFrame=" + hasFrame()
                + ", debugReason=" + debugReason + "}";
    }
}
