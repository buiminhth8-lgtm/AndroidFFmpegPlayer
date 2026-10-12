package com.example.motro.targetselection.api;

/**
 * 一次「点击选择目标」请求的规范化输入，<b>不可变、播放器无关</b>。
 *
 * <p>它是 {@link TargetSelectionController} 的公开入口参数。与 Slice 0 的
 * {@link TouchSelectionRequest} 的关系：后者只承载「点在哪、什么时候点的」，
 * 是坐标映射层的最小输入；本类在此之上多带一个可选的 {@link #requestId}，
 * 用于把「同一时刻发出的多次点击」在日志/回调里区分开 ——
 * 这是后续 Slice（latest-wins、取消、去重）需要的挂钩，
 * 本 Slice 只是把它<b>透传</b>进 {@link TargetHitResult}，不做任何调度。</p>
 *
 * <p><b>为什么坐标是 View 坐标</b>：整条管线的坐标换算只有一个出处
 * （{@code VideoCoordinateMapper}），View→Video 的映射由控制器在收到本请求后
 * 统一执行。调用方（app 层）只需要把 {@code MotionEvent} 的局部坐标原样传进来，
 * 不必自己先算一遍视频坐标 —— 那会立刻产生第二份映射公式。</p>
 *
 * <p><b>时间必须是单调时钟</b>：{@link #eventTimeNs} 与模块内取帧计时同源
 * （{@link System#nanoTime()}），用于后续把「点击时刻」与「取帧时刻」对齐。
 * 用墙上时间（{@code currentTimeMillis}）会静默对齐到错误的帧。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class TargetSelectionRequest {

    /** {@link #eventTimeNs} 的哨兵值：调用方未提供时刻（不影响坐标映射）。 */
    public static final long NO_EVENT_TIME_NS = -1L;

    /** {@link #NO_REQUEST_ID}：调用方未提供请求 id。 */
    public static final long NO_REQUEST_ID = -1L;

    /** 触摸点相对承载 View 左边缘的像素 x。 */
    public final float viewX;

    /** 触摸点相对承载 View 上边缘的像素 y。 */
    public final float viewY;

    /** 按下时刻，{@link System#nanoTime()} 基准；未知为 {@link #NO_EVENT_TIME_NS}。 */
    public final long eventTimeNs;

    /**
     * 可选的请求标识，用于在日志与回调里区分同一次会话里的多次点击。
     * 未知为 {@link #NO_REQUEST_ID}；控制器不解析它的含义，只原样透传。
     */
    public final long requestId;

    private TargetSelectionRequest(float viewX, float viewY, long eventTimeNs, long requestId) {
        this.viewX = viewX;
        this.viewY = viewY;
        this.eventTimeNs = eventTimeNs;
        this.requestId = requestId;
    }

    /**
     * 完整构造。
     *
     * @param viewX       触摸点 View 坐标 x，必须有限
     * @param viewY       触摸点 View 坐标 y，必须有限
     * @param eventTimeNs 按下时刻（{@link System#nanoTime()} 基准），未知传 {@link #NO_EVENT_TIME_NS}
     * @param requestId   请求标识，未知传 {@link #NO_REQUEST_ID}
     * @throws IllegalArgumentException 坐标非有限值，或 eventTimeNs 为负数（且非哨兵值）
     */
    public static TargetSelectionRequest of(float viewX, float viewY, long eventTimeNs, long requestId) {
        requireFinite(viewX, "viewX");
        requireFinite(viewY, "viewY");
        if (eventTimeNs < 0L && eventTimeNs != NO_EVENT_TIME_NS) {
            throw new IllegalArgumentException("eventTimeNs must be >= 0 or NO_EVENT_TIME_NS but was "
                    + eventTimeNs + "; use a monotonic clock (System.nanoTime()), not wall clock");
        }
        return new TargetSelectionRequest(viewX, viewY, eventTimeNs, requestId);
    }

    /**
     * 不带请求 id 的构造（{@link #NO_REQUEST_ID}）。
     *
     * @param eventTimeNs 按下时刻（{@link System#nanoTime()} 基准），未知传 {@link #NO_EVENT_TIME_NS}
     */
    public static TargetSelectionRequest of(float viewX, float viewY, long eventTimeNs) {
        return of(viewX, viewY, eventTimeNs, NO_REQUEST_ID);
    }

    /** 用「此刻」作为按下时刻（{@link System#nanoTime()}）。适合直接由 {@code ACTION_DOWN} 回调调用。 */
    public static TargetSelectionRequest ofNow(float viewX, float viewY) {
        return of(viewX, viewY, System.nanoTime(), NO_REQUEST_ID);
    }

    /**
     * 由 Slice 0 的 {@link TouchSelectionRequest} 升级而来（保留坐标与时刻，
     * 请求 id 取未知值）。用于把已有的触摸入口平滑接到完整管线上。
     *
     * @param touch 触摸请求，非 null
     * @return 非 null 的请求
     * @throws IllegalArgumentException touch 为 null
     */
    public static TargetSelectionRequest fromTouch(TouchSelectionRequest touch) {
        if (touch == null) {
            throw new IllegalArgumentException("touch must not be null");
        }
        return of(touch.viewX, touch.viewY, touch.eventTimeNs, NO_REQUEST_ID);
    }

    public boolean hasEventTime() {
        return eventTimeNs != NO_EVENT_TIME_NS;
    }

    public boolean hasRequestId() {
        return requestId != NO_REQUEST_ID;
    }

    private static void requireFinite(float value, String name) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite but was " + value);
        }
    }

    @Override
    public String toString() {
        return "TargetSelectionRequest{view=(" + viewX + "," + viewY + ")"
                + ", eventTimeNs=" + eventTimeNs
                + ", requestId=" + (requestId == NO_REQUEST_ID ? "-" : requestId) + "}";
    }
}
