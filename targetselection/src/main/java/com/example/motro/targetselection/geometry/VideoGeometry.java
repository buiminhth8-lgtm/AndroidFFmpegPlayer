package com.example.motro.targetselection.geometry;

/**
 * 一次渲染周期内“视频几何”的不可变快照：解码画面尺寸、承载 View 尺寸、
 * 实际显示内容区（去黑边后的矩形）、旋转与镜像。
 *
 * <p>这是后续“点击点 → 视频坐标”映射所需数据的唯一来源，实现方（FFmpegPlayer GL 渲染、
 * Media3 PlayerView、VLC、Camera2 Preview、GStreamer）只需如实汇报自己当前的几何，
 * 本模块不反向依赖任何播放器类型。</p>
 *
 * <p>坐标系约定：</p>
 * <ul>
 *   <li>View 坐标：原点在 View 左上角，像素，y 向下。</li>
 *   <li>视频坐标：原点在**解码帧左上角**，像素，y 向下，尺寸为 videoWidth × videoHeight。</li>
 *   <li>内容区 {@code contentLeft/Top/Right/Bottom} 是 View 坐标下实际显示视频的区域
 *       （video aspect-fit 之后的 letterbox / pillarbox 结果），不含黑边。</li>
 * </ul>
 *
 * <p>显示管线约定（反解顺序即由此推导）：</p>
 * <pre>显示内容 = 镜像( 顺时针旋转( 解码帧 ) )</pre>
 * <p>即 {@code rotationDegrees} 先作用于解码帧，{@code mirrorX/mirrorY} 再作用于显示坐标系
 * （归一化到 [0,1] 后镜像），与“前置摄像头自拍镜像”这类视觉直觉一致。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享；{@link VideoGeometryProvider} 每次应返回新的快照。</p>
 */
public final class VideoGeometry {

    /** 几何未知时的占位快照：{@link #isUsable()} 恒为 false。 */
    public static final VideoGeometry EMPTY = new VideoGeometry(0, 0, 0, 0, 0, 0, 0, 0, 0, false, false);

    public final int videoWidth;
    public final int videoHeight;
    public final int viewWidth;
    public final int viewHeight;
    public final int contentLeft;
    public final int contentTop;
    public final int contentRight;
    public final int contentBottom;

    /** 顺时针旋转角度，取值 0 / 90 / 180 / 270。 */
    public final int rotationDegrees;

    /** 显示坐标系下的水平镜像（自拍镜像）。 */
    public final boolean mirrorX;

    /** 显示坐标系下的垂直镜像。 */
    public final boolean mirrorY;

    private VideoGeometry(int videoWidth, int videoHeight, int viewWidth, int viewHeight,
                          int contentLeft, int contentTop, int contentRight, int contentBottom,
                          int rotationDegrees, boolean mirrorX, boolean mirrorY) {
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        this.contentLeft = contentLeft;
        this.contentTop = contentTop;
        this.contentRight = contentRight;
        this.contentBottom = contentBottom;
        this.rotationDegrees = rotationDegrees;
        this.mirrorX = mirrorX;
        this.mirrorY = mirrorY;
    }

    /** 无黑边、无旋转、无镜像的常见情况：内容区 == 整个 View。 */
    public static VideoGeometry create(int videoWidth, int videoHeight, int viewWidth, int viewHeight) {
        return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                0, 0, viewWidth, viewHeight, 0, false, false);
    }

    /** 完整快照。注意 rotation 为 90/270 时，内容区宽高应当与 (videoHeight, videoWidth) 成比例。 */
    public static VideoGeometry create(int videoWidth, int videoHeight, int viewWidth, int viewHeight,
                                       int contentLeft, int contentTop, int contentRight, int contentBottom,
                                       int rotationDegrees, boolean mirrorX, boolean mirrorY) {
        return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                contentLeft, contentTop, contentRight, contentBottom,
                normalizeRotation(rotationDegrees), mirrorX, mirrorY);
    }

    public int contentWidth() {
        return contentRight - contentLeft;
    }

    public int contentHeight() {
        return contentBottom - contentTop;
    }

    /**
     * 几何是否足以做坐标映射。任一关键尺寸为 0，或内容区超出 View 范围，都视为不可用；
     * 此时控制器会返回 {@code GEOMETRY_UNAVAILABLE}，而不是猜一个坐标。
     */
    public boolean isUsable() {
        return videoWidth > 0 && videoHeight > 0
                && viewWidth > 0 && viewHeight > 0
                && contentWidth() > 0 && contentHeight() > 0
                && contentLeft >= 0 && contentTop >= 0
                && contentRight <= viewWidth && contentBottom <= viewHeight;
    }

    /** View 坐标下的点是否落在显示内容区（黑边上不算）。 */
    public boolean containsViewPoint(float viewX, float viewY) {
        return viewX >= contentLeft && viewX <= contentRight
                && viewY >= contentTop && viewY <= contentBottom;
    }

    /**
     * 把 View 坐标点反解为视频像素坐标。**纯几何运算**：不涉及 MotionEvent、View 层级、
     * 手势识别或任何播放器类型，可在任意线程调用。
     *
     * <p>落在黑边（内容区之外）或几何不可用时返回 {@code null}，由调用方转成
     * {@code OUTSIDE_VIDEO} / {@code GEOMETRY_UNAVAILABLE}，避免把黑边点击算成 MISS。</p>
     *
     * @return {@code float[2]} = {videoX, videoY}，已裁剪到 [0, videoWidth] × [0, videoHeight]；越界返回 null
     */
    public float[] mapViewPointToVideo(float viewX, float viewY) {
        if (!isUsable()) {
            return null;
        }
        if (!containsViewPoint(viewX, viewY)) {
            return null;
        }

        // 归一化到显示内容区的 [0,1]
        float u = (viewX - contentLeft) / (float) contentWidth();
        float v = (viewY - contentTop) / (float) contentHeight();

        // 显示 = 镜像(旋转(解码帧))，故反解顺序为：先解除镜像，再反旋转
        if (mirrorX) {
            u = 1f - u;
        }
        if (mirrorY) {
            v = 1f - v;
        }

        final float w = videoWidth;
        final float h = videoHeight;
        final float videoX;
        final float videoY;
        switch (rotationDegrees) {
            case 90:
                // 解码帧 (x,y) → 显示 (X,Y) = (H-1-y, x)
                videoX = v * w;
                videoY = (1f - u) * h;
                break;
            case 180:
                videoX = (1f - u) * w;
                videoY = (1f - v) * h;
                break;
            case 270:
                // 解码帧 (x,y) → 显示 (X,Y) = (y, W-1-x)
                videoX = (1f - v) * w;
                videoY = u * h;
                break;
            case 0:
            default:
                videoX = u * w;
                videoY = v * h;
                break;
        }
        return new float[]{clamp(videoX, 0f, w), clamp(videoY, 0f, h)};
    }

    /** 复制并替换内容区（例如旋转/黑边变化时只改这一项）。 */
    public VideoGeometry withContent(int contentLeft, int contentTop, int contentRight, int contentBottom) {
        return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                contentLeft, contentTop, contentRight, contentBottom, rotationDegrees, mirrorX, mirrorY);
    }

    /** 复制并替换 View 尺寸（内容区按新尺寸整体重设，避免沿用旧的越界矩形）。 */
    public VideoGeometry withViewSize(int viewWidth, int viewHeight) {
        return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                0, 0, viewWidth, viewHeight, rotationDegrees, mirrorX, mirrorY);
    }

    /** 复制并替换旋转/镜像。 */
    public VideoGeometry withOrientation(int rotationDegrees, boolean mirrorX, boolean mirrorY) {
        return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                contentLeft, contentTop, contentRight, contentBottom,
                normalizeRotation(rotationDegrees), mirrorX, mirrorY);
    }

    private static int normalizeRotation(int rotationDegrees) {
        int r = rotationDegrees % 360;
        if (r < 0) {
            r += 360;
        }
        if (r == 90 || r == 180 || r == 270) {
            return r;
        }
        // 非法角度按“未旋转”处理，避免产生无法反解的快照
        return 0;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    @Override
    public String toString() {
        return "VideoGeometry{video=" + videoWidth + "x" + videoHeight
                + ", view=" + viewWidth + "x" + viewHeight
                + ", content=[" + contentLeft + "," + contentTop + "," + contentRight + "," + contentBottom + "]"
                + ", rotation=" + rotationDegrees
                + ", mirrorX=" + mirrorX + ", mirrorY=" + mirrorY + "}";
    }
}
