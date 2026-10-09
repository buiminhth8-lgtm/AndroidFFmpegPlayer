package com.example.motro.targetselection.api;

/**
 * 一次触摸（{@code ACTION_DOWN}）的规范化输入，不可变。**播放器无关**：只承载
 * 「点在哪、什么时候点的」，不引用 {@code MotionEvent}、{@code View} 或任何播放器类型。
 *
 * <p><b>为什么用 ACTION_DOWN 而不是 ACTION_UP</b>：目标选择要的是「用户按下的那一刻画面」，
 * 按下即取帧才能与视觉反馈同步；等 UP 会引入手指悬停/滑动带来的时间不确定性，
 * 也会让“点一下就抬手”与“按住不动”产生不同的行为。</p>
 *
 * <p><b>时间必须是单调时钟</b>：{@link #eventTimeNs} 用于后续与取帧时刻对齐
 * （见 {@code CapturedFrame#captureRequestNs}），因此必须与模块内取帧计时
 * <b>同源</b> —— 即 {@link System#nanoTime()} 基准。用墙上时间（{@code System.currentTimeMillis()}）
 * 会对齐到完全错误的帧，而且系统时间被调整时会静默出错。</p>
 *
 * <p>从 {@code MotionEvent} 取时间的推荐做法：{@code getEventTime()} 是 uptimeMillis 基准，
 * 与本模块的 nanoTime 基准不同源，需用
 * {@link #downFromUptimeMillis(float, float, long, long, long)} 换算，
 * 不要直接把毫秒当纳秒塞进来。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class TouchSelectionRequest {

    /** {@link #eventTimeNs} 的哨兵值：调用方没有提供时刻（此时无法与帧对齐，但不影响坐标映射）。 */
    public static final long NO_EVENT_TIME_NS = -1L;

    /** 触摸点相对承载 View 左边缘的像素 x。 */
    public final float viewX;

    /** 触摸点相对承载 View 上边缘的像素 y。 */
    public final float viewY;

    /** 按下时刻，{@link System#nanoTime()} 基准；未知为 {@link #NO_EVENT_TIME_NS}。 */
    public final long eventTimeNs;

    private TouchSelectionRequest(float viewX, float viewY, long eventTimeNs) {
        this.viewX = viewX;
        this.viewY = viewY;
        this.eventTimeNs = eventTimeNs;
    }

    /**
     * 用显式时刻构造。
     *
     * @param viewX       触摸点 View 坐标 x
     * @param viewY       触摸点 View 坐标 y
     * @param eventTimeNs 按下时刻（{@link System#nanoTime()} 基准），未知传 {@link #NO_EVENT_TIME_NS}
     * @throws IllegalArgumentException 坐标非有限值，或 eventTimeNs 为负数（且非哨兵值）
     */
    public static TouchSelectionRequest down(float viewX, float viewY, long eventTimeNs) {
        requireFinite(viewX, "viewX");
        requireFinite(viewY, "viewY");
        if (eventTimeNs < 0L && eventTimeNs != NO_EVENT_TIME_NS) {
            throw new IllegalArgumentException("eventTimeNs must be >= 0 or NO_EVENT_TIME_NS but was "
                    + eventTimeNs + "; use a monotonic clock (System.nanoTime()), not wall clock");
        }
        return new TouchSelectionRequest(viewX, viewY, eventTimeNs);
    }

    /** 用「此刻」作为按下时刻（{@link System#nanoTime()}）。适合直接由 {@code ACTION_DOWN} 回调调用。 */
    public static TouchSelectionRequest downNow(float viewX, float viewY) {
        return down(viewX, viewY, System.nanoTime());
    }

    /**
     * 把 {@code MotionEvent.getEventTime()}（uptimeMillis 基准）换算到本模块的 nanoTime 基准。
     *
     * <p>换算方式：{@code eventTimeNs = nowNanoTime - (nowUptimeMillis - eventUptimeMillis) × 1e6}。
     * 三个「现在」值由调用方传入，本类因此保持纯 JVM（不需要 {@code SystemClock}），也可单测。</p>
     *
     * @param viewX             触摸点 View 坐标 x
     * @param viewY             触摸点 View 坐标 y
     * @param eventUptimeMillis 事件时刻（{@code MotionEvent.getEventTime()}）
     * @param nowUptimeMillis   当前 uptime（{@code SystemClock.uptimeMillis()}）
     * @param nowNanoTimeNs     当前 nanoTime（{@code System.nanoTime()}），与上者应尽量同时读取
     * @throws IllegalArgumentException 事件时刻晚于当前 uptime（时钟用反了，宁可失败也不要算出未来时间）
     */
    public static TouchSelectionRequest downFromUptimeMillis(float viewX, float viewY,
                                                             long eventUptimeMillis,
                                                             long nowUptimeMillis,
                                                             long nowNanoTimeNs) {
        if (eventUptimeMillis > nowUptimeMillis) {
            throw new IllegalArgumentException("eventUptimeMillis (" + eventUptimeMillis
                    + ") must not be later than nowUptimeMillis (" + nowUptimeMillis + ")");
        }
        final long ageMs = nowUptimeMillis - eventUptimeMillis;
        return down(viewX, viewY, nowNanoTimeNs - ageMs * 1_000_000L);
    }

    public boolean hasEventTime() {
        return eventTimeNs != NO_EVENT_TIME_NS;
    }

    /**
     * 距该次按下已经过去多久（纳秒），用于判断“这一帧是不是太旧了”。
     *
     * @param nowNs 当前时刻（{@link System#nanoTime()}）
     * @return 已过去的时间；时刻未知时返回 {@link #NO_EVENT_TIME_NS}；时钟回拨导致为负时返回 0
     */
    public long ageNs(long nowNs) {
        if (!hasEventTime()) {
            return NO_EVENT_TIME_NS;
        }
        final long delta = nowNs - eventTimeNs;
        return delta < 0L ? 0L : delta;
    }

    private static void requireFinite(float value, String name) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite but was " + value);
        }
    }

    @Override
    public String toString() {
        return "TouchSelectionRequest{view=(" + viewX + "," + viewY + "), eventTimeNs=" + eventTimeNs + "}";
    }
}
