package com.example.motro.targetselection.debug;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoPointMappingStatus;

import java.util.List;

/**
 * 调试用叠加层：在视频上方把「触摸点 → 内容区 → 映射结果」画出来，用来肉眼验证坐标映射是否正确。
 *
 * <p><b>它不是业务组件</b>：不取帧、不识别、不做命中测试，只负责把
 * {@link TargetDebugState} 画出来。因此它不会、也不该影响播放：不消费触摸、不持有 Bitmap、
 * 不在每帧重绘。</p>
 *
 * <p><b>叠加方式（由 app 层决定，本类不关心父容器）</b>：</p>
 * <pre>
 *   FrameLayout
 *   ├── SurfaceView / TextureView   ← 播放器输出
 *   └── TargetDebugOverlayView      ← 本类，MATCH_PARENT
 * </pre>
 * <p>本类<b>默认不拦截触摸</b>：{@link #onTouchEvent} 恒返回 {@code false}，
 * 且构造时设 {@code clickable=false / focusable=false}，所以按在叠加层上的手势会继续
 * 传给下方的播放器与手势层。若确实需要接收调试点击，由 app 层挂
 * {@code setOnTouchListener} 并在回调里返回 {@code false} 即可「只观察不截获」。</p>
 *
 * <p><b>绘制约定（重要）</b>：视频坐标不能直接当 View 坐标画，尺度与原点都不同。
 * 因此本类画「映射后的视频点」时，一律调用
 * {@link VideoCoordinateMapper#mapVideoToView} 正推回 View 坐标 ——
 * <b>本类不含任何缩放/旋转/镜像公式</b>（那会让调试层和正式映射算法变成两套逻辑，
 * 于是调试层可能"看起来对"而实际错）。正推是反解的严格逆，所以正常情况下
 * 映射点应当精确落在触摸点上，连线长度≈0；连线明显甩开就说明几何有问题，
 * 这正是这个叠加层最有价值的诊断信号。</p>
 *
 * <p><b>性能约定</b>：{@link Paint}、{@link StringBuilder} 等全部在构造时创建并复用，
 * {@link #onDraw(Canvas)} 不主动分配对象（逐行量宽走 {@code CharSequence} 重载，
 * 浮点输出也绕开 {@code StringBuilder.append(float)} 内部产生的临时 String）。
 * 叠加层只在 {@link #setDebugState}/{@link #clearDebugState}/{@link #setOverlayEnabled}
 * 被调用时 {@code invalidate()}，<b>不随播放帧刷新</b>，对解码/渲染帧率无影响。</p>
 *
 * <p>线程约定：{@link #setDebugState} 等更新方法须在主线程调用（View 的常规约束）；
 * 传入的 {@link TargetDebugState} 不可变，可安全地从工作线程算好再传进来。</p>
 */
public class TargetDebugOverlayView extends View {

    // ---------------------------------------------------------------- 外观常量（内部默认值，不引入主题体系）

    private static final int COLOR_CONTENT_RECT = 0xFF00E5FF;
    private static final int COLOR_TOUCH_HIT = 0xFF00E676;
    private static final int COLOR_TOUCH_OUTSIDE = 0xFFFFC107;
    private static final int COLOR_TOUCH_INVALID = 0xFFFF3D00;
    private static final int COLOR_MAPPED_POINT = 0xFFFF4081;
    private static final int COLOR_RESERVED_BOX = 0xFFFFFF00;
    private static final int COLOR_TEXT = 0xFFF5F5F5;
    private static final int COLOR_TEXT_BG = 0xB3000000;

    /** 以下尺寸均以 dp 为单位，构造时按屏幕密度换算成像素。 */
    private static final float DP_CONTENT_STROKE = 2f;
    private static final float DP_TOUCH_RADIUS = 9f;
    private static final float DP_TOUCH_CROSS_ARM = 16f;
    private static final float DP_MAPPED_RADIUS = 6f;
    private static final float DP_LINK_STROKE = 2f;
    private static final float DP_TEXT_SIZE = 12f;
    private static final float DP_LINE_HEIGHT = 16f;
    private static final float DP_TEXT_MARGIN = 10f;
    private static final float DP_TEXT_PADDING = 8f;

    private final Paint rectPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint touchFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint touchStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mappedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBgPaint = new Paint();

    /** 复用同一份文本缓冲，避免每次 {@code onDraw} 都新建。 */
    private final StringBuilder text = new StringBuilder(384);

    private final float strokePx;
    private final float touchRadiusPx;
    private final float crossArmPx;
    private final float mappedRadiusPx;
    private final float lineHeightPx;
    private final float textMarginPx;
    private final float textPaddingPx;

    /** 当前状态；null 表示「无内容可画」。 */
    private TargetDebugState state;

    /** 调试开关（关掉后完全不绘制）。 */
    private boolean overlayEnabled = true;

    public TargetDebugOverlayView(Context context) {
        this(context, null);
    }

    public TargetDebugOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TargetDebugOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        final float density = getResources().getDisplayMetrics().density;
        this.strokePx = DP_CONTENT_STROKE * density;
        this.touchRadiusPx = DP_TOUCH_RADIUS * density;
        this.crossArmPx = DP_TOUCH_CROSS_ARM * density;
        this.mappedRadiusPx = DP_MAPPED_RADIUS * density;
        this.lineHeightPx = DP_LINE_HEIGHT * density;
        this.textMarginPx = DP_TEXT_MARGIN * density;
        this.textPaddingPx = DP_TEXT_PADDING * density;

        rectPaint.setStyle(Paint.Style.STROKE);
        rectPaint.setStrokeWidth(strokePx);
        rectPaint.setColor(COLOR_CONTENT_RECT);

        touchFillPaint.setStyle(Paint.Style.FILL);
        touchFillPaint.setColor(COLOR_TOUCH_HIT);

        touchStrokePaint.setStyle(Paint.Style.STROKE);
        touchStrokePaint.setStrokeWidth(strokePx);
        touchStrokePaint.setColor(COLOR_TOUCH_HIT);

        mappedPaint.setStyle(Paint.Style.FILL);
        mappedPaint.setColor(COLOR_MAPPED_POINT);

        linkPaint.setStyle(Paint.Style.STROKE);
        linkPaint.setStrokeWidth(DP_LINK_STROKE * density);
        linkPaint.setColor(COLOR_MAPPED_POINT);

        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(strokePx);
        boxPaint.setColor(COLOR_RESERVED_BOX);

        textPaint.setTextSize(DP_TEXT_SIZE * density);
        textPaint.setColor(COLOR_TEXT);

        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setColor(COLOR_TEXT_BG);

        // 纯展示层：既不抢焦点，也不消费手势
        setClickable(false);
        setFocusable(false);
        setFocusableInTouchMode(false);
    }

    // ------------------------------------------------------------------ 状态更新

    /**
     * 更新要绘制的调试信息并重绘。<b>null 等同于 {@link #clearDebugState()}</b>，
     * 这样调用方不必为了「这次没有数据」而写分支。
     *
     * <p>传入同一个实例时会直接返回 —— 避免无意义的重绘。</p>
     */
    public void setDebugState(TargetDebugState newState) {
        if (this.state == newState) {
            return;
        }
        this.state = newState;
        invalidate();
    }

    /** 清空调试信息：叠加层随即变为完全空白，也不再持有任何状态。 */
    public void clearDebugState() {
        if (this.state == null) {
            return;
        }
        this.state = null;
        invalidate();
    }

    /** 当前调试状态；从未设置或已清空时为 null。 */
    public TargetDebugState getDebugState() {
        return state;
    }

    /** 是否持有可绘制状态。 */
    public boolean hasDebugState() {
        return state != null;
    }

    /**
     * 调试总开关。关闭后 {@link #onDraw(Canvas)} 立即不绘制任何东西
     * （但不清空已持有的状态，重新开启即可恢复）。
     *
     * <p>只想临时隐藏时用标准的 {@link #setVisibility(int)} 也可以；本方法额外的好处是
     * 连绘制都省掉，适合「调试模式关闭」这种长期状态。</p>
     */
    public void setOverlayEnabled(boolean enabled) {
        if (this.overlayEnabled == enabled) {
            return;
        }
        this.overlayEnabled = enabled;
        invalidate();
    }

    public boolean isOverlayEnabled() {
        return overlayEnabled;
    }

    // ------------------------------------------------------------------ 触摸

    /**
     * 恒返回 {@code false}：叠加层<b>绝不消费</b>触摸事件，手势会继续交给下方兄弟
     * （播放器 / 手势层）。需要观察点击时请由 app 层挂 {@code OnTouchListener}。
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    /**
     * 离开窗口时丢弃状态：叠加上层可能被复用，留着旧数据会画出与当前画面不符的标记。
     * 本类不持有 Activity / Player / Bitmap，因此无需释放其他资源。
     */
    @Override
    protected void onDetachedFromWindow() {
        clearDebugState();
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        final TargetDebugState current = state;
        if (!overlayEnabled || current == null) {
            return;
        }

        // 1) 视频内容区（View 坐标，直接可用）
        if (current.hasGeometry()) {
            canvas.drawRect(current.contentLeft(), current.contentTop(),
                    current.contentRight(), current.contentBottom(), rectPaint);
        }

        // 2) 预留的 ROI / 候选框（本 Slice 恒为空，直接返回）
        drawReservedBoxes(canvas, current);

        // 3) 原始触摸点：状态色 + 十字
        final int touchColor = colorForStatus(current.status());
        touchFillPaint.setColor(touchColor);
        touchStrokePaint.setColor(touchColor);
        final float touchX = current.touchViewX();
        final float touchY = current.touchViewY();
        canvas.drawCircle(touchX, touchY, touchRadiusPx, touchFillPaint);
        canvas.drawLine(touchX - crossArmPx, touchY, touchX + crossArmPx, touchY, touchStrokePaint);
        canvas.drawLine(touchX, touchY - crossArmPx, touchX, touchY + crossArmPx, touchStrokePaint);

        // 4) 映射后的视频点在 View 上的落点（经 mapper 正推）+ 往返连线
        if (current.hasMappedViewPoint()) {
            canvas.drawLine(touchX, touchY, current.mappedViewX, current.mappedViewY, linkPaint);
            canvas.drawCircle(current.mappedViewX, current.mappedViewY, mappedRadiusPx, mappedPaint);
        }

        // 5) 文本信息
        drawText(canvas, current);
    }

    private int colorForStatus(VideoPointMappingStatus status) {
        switch (status) {
            case SUCCESS:
                return COLOR_TOUCH_HIT;
            case OUTSIDE_VIDEO:
                return COLOR_TOUCH_OUTSIDE;
            case INVALID_GEOMETRY:
            default:
                return COLOR_TOUCH_INVALID;
        }
    }

    /**
     * 预留路径：把识别阶段的框（视频坐标）正推回 View 再画。
     * 本 Slice 不会被触发（{@link TargetDebugState} 里这些字段恒为 null/空），
     * 但绘制入口先备好，后续 Slice 填上数据即可复用，不必再改本类结构。
     */
    private void drawReservedBoxes(Canvas canvas, TargetDebugState current) {
        final List<TargetBoundingBox> boxes = current.detectedBoxes;
        if (boxes.isEmpty()
                && current.searchRoi == null
                && current.selectedBox == null
                && current.labelRoi == null) {
            return;
        }
        for (int i = 0; i < boxes.size(); i++) {
            drawVideoBox(canvas, current.mapping.geometry, boxes.get(i));
        }
        drawVideoBox(canvas, current.mapping.geometry, current.searchRoi);
        drawVideoBox(canvas, current.mapping.geometry, current.labelRoi);
        drawVideoBox(canvas, current.mapping.geometry, current.selectedBox);
    }

    /** 把一个视频坐标下的矩形画到 View 上：四个角各自经 mapper 正推（旋转/镜像因此自动正确）。 */
    private void drawVideoBox(Canvas canvas, VideoGeometry geometry, TargetBoundingBox box) {
        if (box == null) {
            return;
        }
        final float[] topLeft = VideoCoordinateMapper.mapVideoToView(geometry, box.left, box.top);
        final float[] topRight = VideoCoordinateMapper.mapVideoToView(geometry, box.right, box.top);
        final float[] bottomRight = VideoCoordinateMapper.mapVideoToView(geometry, box.right, box.bottom);
        final float[] bottomLeft = VideoCoordinateMapper.mapVideoToView(geometry, box.left, box.bottom);
        if (topLeft == null || topRight == null || bottomRight == null || bottomLeft == null) {
            return;
        }
        canvas.drawLine(topLeft[0], topLeft[1], topRight[0], topRight[1], boxPaint);
        canvas.drawLine(topRight[0], topRight[1], bottomRight[0], bottomRight[1], boxPaint);
        canvas.drawLine(bottomRight[0], bottomRight[1], bottomLeft[0], bottomLeft[1], boxPaint);
        canvas.drawLine(bottomLeft[0], bottomLeft[1], topLeft[0], topLeft[1], boxPaint);
    }

    /**
     * 左上角文本信息块。全程复用 {@link #text} 与两个 Paint，不产生临时对象：
     * 浮点用自写的 {@link #appendFixed1} 输出（{@code append(float)} 内部会造 String），
     * 逐行 {@code measureText/drawText} 也走 CharSequence 重载。
     */
    private void drawText(Canvas canvas, TargetDebugState current) {
        final StringBuilder b = text;
        b.setLength(0);

        b.append("view=");
        appendFixed1(b, current.touchViewX());
        b.append(',');
        appendFixed1(b, current.touchViewY());
        b.append("   status=").append(current.status()).append('\n');

        if (current.hasGeometry()) {
            b.append("video=").append(current.videoWidth()).append('x').append(current.videoHeight())
                    .append("   content=[").append(current.contentLeft()).append(',')
                    .append(current.contentTop()).append(',')
                    .append(current.contentRight()).append(',')
                    .append(current.contentBottom()).append("]\n");
        } else {
            b.append("video=-   content=-\n");
        }

        b.append("rot=").append(current.rotationDegrees())
                .append("   mirror=").append(current.mirrorX()).append(',').append(current.mirrorY())
                .append("   scale=").append(current.scaleType()).append('\n');

        if (current.hasMappedViewPoint()) {
            b.append("video=");
            appendFixed1(b, current.videoX());
            b.append(',');
            appendFixed1(b, current.videoY());
            b.append("   norm=");
            appendFixed1(b, current.normalizedX());
            b.append(',');
            appendFixed1(b, current.normalizedY());
            b.append('\n').append("mappedView=");
            appendFixed1(b, current.mappedViewX);
            b.append(',');
            appendFixed1(b, current.mappedViewY);
            b.append("   rtErr=");
            appendFixed1(b, current.roundTripErrorPx);
            b.append("px");
        } else {
            b.append("video=-   mapped=-");
        }

        // 量出块尺寸：逐行测量（换行符不参与宽度）
        final int length = b.length();
        int lines = 1;
        float maxWidth = 0f;
        int lineStart = 0;
        for (int i = 0; i <= length; i++) {
            if (i == length || b.charAt(i) == '\n') {
                final float lineWidth = textPaint.measureText(b, lineStart, i);
                if (lineWidth > maxWidth) {
                    maxWidth = lineWidth;
                }
                if (i < length) {
                    lines++;
                }
                lineStart = i + 1;
            }
        }

        final float left = textMarginPx;
        final float top = textMarginPx;
        final float right = Math.min(getWidth(), left + maxWidth + textPaddingPx * 2f);
        final float bottom = Math.min(getHeight(), top + lines * lineHeightPx + textPaddingPx * 2f);
        if (right > left && bottom > top) {
            canvas.drawRect(left, top, right, bottom, textBgPaint);
        }

        float baseline = top + textPaddingPx - textPaint.ascent();
        lineStart = 0;
        for (int i = 0; i <= length; i++) {
            if (i == length || b.charAt(i) == '\n') {
                if (i > lineStart) {
                    canvas.drawText(b, lineStart, i, left + textPaddingPx, baseline, textPaint);
                }
                baseline += lineHeightPx;
                lineStart = i + 1;
            }
        }
    }

    /**
     * 把浮点按「一位小数」写入缓冲，<b>不产生临时 String</b>
     * （{@code StringBuilder.append(float)} 内部会 {@code FloatingDecimal} 出一个 String）。
     */
    private static void appendFixed1(StringBuilder out, float value) {
        if (Float.isNaN(value)) {
            out.append("NaN");
            return;
        }
        if (Float.isInfinite(value)) {
            out.append(value > 0 ? "inf" : "-inf");
            return;
        }
        final boolean negative = value < 0f;
        final int scaled = Math.round(Math.abs(value) * 10f);
        if (negative) {
            out.append('-');
        }
        out.append(scaled / 10).append('.').append((char) ('0' + scaled % 10));
    }

    /** 供调试日志使用；不参与绘制。 */
    @Override
    public String toString() {
        return "TargetDebugOverlayView{enabled=" + overlayEnabled
                + ", state=" + (state == null ? "none" : "set")
                + ", clickable=" + isClickable() + ", focusable=" + isFocusable() + "}";
    }
}
