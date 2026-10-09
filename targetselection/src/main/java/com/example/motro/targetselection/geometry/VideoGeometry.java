package com.example.motro.targetselection.geometry;

/**
 * 一次渲染周期内“视频几何”的不可变快照：解码画面尺寸、承载 View 尺寸、
 * 实际显示内容区（去黑边后的矩形）、缩放方式、旋转与镜像。
 *
 * <p>这是后续“点击点 → 视频坐标”映射所需数据的唯一来源，实现方（FFmpegPlayer GL 渲染、
 * Media3 PlayerView、VLC、Camera2 Preview、GStreamer）只需如实汇报自己当前的几何，
 * 本模块不反向依赖任何播放器类型。</p>
 *
 * <p>坐标系约定：</p>
 * <ul>
 *   <li>View 坐标：原点在 View 左上角，像素，y 向下。</li>
 *   <li>视频坐标：原点在**解码帧左上角**，像素，y 向下，尺寸为 videoWidth × videoHeight。</li>
 *   <li>内容区 {@code contentLeft/Top/Right/Bottom} 是 View 坐标下**可见的视频区域**
 *       （不含黑边）。{@link VideoScaleType#FIT_CENTER} 时它小于 View（四周黑边）；
 *       {@link VideoScaleType#CENTER_CROP} 时它等于整个 View（无黑边，画面被裁）。</li>
 * </ul>
 *
 * <p><b>坐标换算不要写在本类里</b>：{@link #mapViewPointToVideo} 只是委托给
 * {@link VideoCoordinateMapper} 的便捷入口，全部公式只有那一处实现，
 * 以免旋转/镜像顺序出现第二份（写错的那份）。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享；{@link VideoGeometryProvider} 每次应返回新的快照。</p>
 */
public final class VideoGeometry {

    /** 几何未知时的占位快照：{@link #isUsable()} 恒为 false。 */
    public static final VideoGeometry EMPTY = new VideoGeometry(0, 0, 0, 0, 0, 0, 0, 0,
            VideoScaleType.FIT_CENTER, 0, false, false);

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

    /** 缩放方式；决定内容区是「留黑边」还是「裁切」，也参与反解的裁切窗口还原。 */
    public final VideoScaleType scaleType;

    private VideoGeometry(int videoWidth, int videoHeight, int viewWidth, int viewHeight,
                          int contentLeft, int contentTop, int contentRight, int contentBottom,
                          VideoScaleType scaleType, int rotationDegrees, boolean mirrorX, boolean mirrorY) {
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.viewWidth = viewWidth;
        this.viewHeight = viewHeight;
        this.contentLeft = contentLeft;
        this.contentTop = contentTop;
        this.contentRight = contentRight;
        this.contentBottom = contentBottom;
        this.scaleType = scaleType == null ? VideoScaleType.FIT_CENTER : scaleType;
        this.rotationDegrees = rotationDegrees;
        this.mirrorX = mirrorX;
        this.mirrorY = mirrorY;
    }

    /** 无黑边、无旋转、无镜像的常见情况：内容区 == 整个 View。 */
    public static VideoGeometry create(int videoWidth, int videoHeight, int viewWidth, int viewHeight) {
        return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                0, 0, viewWidth, viewHeight, VideoScaleType.FIT_CENTER, 0, false, false);
    }

    /**
     * 由缩放方式自动推导内容区（推荐入口：app 层拿不到真实 contentRect 时用它）。
     *
     * <p>与 {@link #create(int, int, int, int, int, int, int, int, int, boolean, boolean)}
     * 的区别：那个重载要求调用方自己算好 {@code contentRect}，本重载替你算，
     * 且旋转 90/270 时会正确交换宽高（黑边方向不会算反）。</p>
     */
    public static VideoGeometry createWithScale(int videoWidth, int videoHeight,
                                                int viewWidth, int viewHeight,
                                                VideoScaleType scaleType) {
        return createWithScale(videoWidth, videoHeight, viewWidth, viewHeight, scaleType, 0, false, false);
    }

    /** 见 {@link #createWithScale(int, int, int, int, VideoScaleType)}；本重载可同时指定旋转与镜像。 */
    public static VideoGeometry createWithScale(int videoWidth, int videoHeight,
                                                int viewWidth, int viewHeight,
                                                VideoScaleType scaleType,
                                                int rotationDegrees, boolean mirrorX, boolean mirrorY) {
        final int rotation = normalizeRotation(rotationDegrees);
        final int[] rect = VideoCoordinateMapper.deriveContentRect(
                viewWidth, viewHeight, videoWidth, videoHeight, scaleType, rotation);
        final int left = rect == null ? 0 : rect[0];
        final int top = rect == null ? 0 : rect[1];
        final int right = rect == null ? 0 : rect[2];
        final int bottom = rect == null ? 0 : rect[3];
        return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                left, top, right, bottom, scaleType, rotation, mirrorX, mirrorY);
    }

    /**
     * 完整快照，内容区由调用方给定（例如渲染器能直接汇报真实可见区域）。
     *
     * <p>注意 rotation 为 90/270 时，内容区宽高应当与 (videoHeight, videoWidth) 成比例。</p>
     */
    public static VideoGeometry create(int videoWidth, int videoHeight, int viewWidth, int viewHeight,
                                       int contentLeft, int contentTop, int contentRight, int contentBottom,
                                       int rotationDegrees, boolean mirrorX, boolean mirrorY) {
        return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                contentLeft, contentTop, contentRight, contentBottom,
                VideoScaleType.FIT_CENTER, normalizeRotation(rotationDegrees), mirrorX, mirrorY);
    }

    public int contentWidth() {
        return contentRight - contentLeft;
    }

    public int contentHeight() {
        return contentBottom - contentTop;
    }

    /**
     * 几何是否足以做坐标映射。任一关键尺寸为 0，或内容区超出 View 范围，都视为不可用；
     * 此时映射会返回 {@code INVALID_GEOMETRY}，而不是猜一个坐标。
     */
    public boolean isUsable() {
        return videoWidth > 0 && videoHeight > 0
                && viewWidth > 0 && viewHeight > 0
                && contentWidth() > 0 && contentHeight() > 0
                && contentLeft >= 0 && contentTop >= 0
                && contentRight <= viewWidth && contentBottom <= viewHeight;
    }

    /** View 坐标下的点是否落在可见视频区域内（黑边上不算）。 */
    public boolean containsViewPoint(float viewX, float viewY) {
        return viewX >= contentLeft && viewX <= contentRight
                && viewY >= contentTop && viewY <= contentBottom;
    }

    /**
     * 把 View 坐标点反解为视频像素坐标的便捷入口 —— <b>委托给
     * {@link VideoCoordinateMapper#map}</b>，本类不持有任何公式。
     *
     * <p>需要区分“点在黑边”与“几何未知”时请直接调用 mapper 拿
     * {@link VideoPointMappingResult}；本方法只做“拿到了还是没拿到”的二元返回。</p>
     *
     * @return {@code float[2]} = {videoX, videoY}；越界或几何不可用时返回 {@code null}
     */
    public float[] mapViewPointToVideo(float viewX, float viewY) {
        final VideoPointMappingResult result = VideoCoordinateMapper.map(this, viewX, viewY);
        return result.status.isMapped() ? new float[]{result.videoX, result.videoY} : null;
    }

    /** 复制并替换内容区（例如 app 层拿到更精确的可见区域时只改这一项）。 */
    public VideoGeometry withContent(int contentLeft, int contentTop, int contentRight, int contentBottom) {
        return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                contentLeft, contentTop, contentRight, contentBottom,
                scaleType, rotationDegrees, mirrorX, mirrorY);
    }

    /**
     * 复制并替换 View 尺寸，内容区按新尺寸整体重设。
     *
     * <p>这是**原样重设**：不会按视频宽高比重新算黑边。若 View 尺寸变化时要保持等比显示，
     * 用 {@link #withScaleType} 或直接重新 {@link #createWithScale}。</p>
     */
    public VideoGeometry withViewSize(int viewWidth, int viewHeight) {
        return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                0, 0, viewWidth, viewHeight, scaleType, rotationDegrees, mirrorX, mirrorY);
    }

    /** 复制并替换旋转/镜像。注意：内容区**不会**随之重算，需要重算请接着调 {@link #withScaleType}。 */
    public VideoGeometry withOrientation(int rotationDegrees, boolean mirrorX, boolean mirrorY) {
        return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                contentLeft, contentTop, contentRight, contentBottom,
                scaleType, normalizeRotation(rotationDegrees), mirrorX, mirrorY);
    }

    /**
     * 复制并替换缩放方式，并按新方式**重算内容区**（即一次完整的重新适配）。
     * 尺寸非法导致无法推导时保留原内容区，避免把退化几何悄悄变成“看起来可用”。
     */
    public VideoGeometry withScaleType(VideoScaleType newScaleType) {
        final VideoScaleType effective = newScaleType == null ? VideoScaleType.FIT_CENTER : newScaleType;
        final int[] rect = VideoCoordinateMapper.deriveContentRect(
                viewWidth, viewHeight, videoWidth, videoHeight, effective, rotationDegrees);
        if (rect == null) {
            return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                    contentLeft, contentTop, contentRight, contentBottom,
                    effective, rotationDegrees, mirrorX, mirrorY);
        }
        return new VideoGeometry(videoWidth, videoHeight, viewWidth, viewHeight,
                rect[0], rect[1], rect[2], rect[3], effective, rotationDegrees, mirrorX, mirrorY);
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

    @Override
    public String toString() {
        return "VideoGeometry{video=" + videoWidth + "x" + videoHeight
                + ", view=" + viewWidth + "x" + viewHeight
                + ", content=[" + contentLeft + "," + contentTop + "," + contentRight + "," + contentBottom + "]"
                + ", scale=" + scaleType
                + ", rotation=" + rotationDegrees
                + ", mirrorX=" + mirrorX + ", mirrorY=" + mirrorY + "}";
    }
}
