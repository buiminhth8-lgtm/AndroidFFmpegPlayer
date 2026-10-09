package com.example.motro.targetselection.geometry;

/**
 * <b>本模块唯一的坐标换算实现</b>。任何「View 坐标 ↔ 视频坐标」的运算都必须走这里 ——
 * 包括内容区推导、反解、旋转与镜像的解除。别在别处再写一份公式：一旦有两份，
 * rotation / mirror 的顺序迟早会在其中一份里写错，而且只错一半（最难看的那种 bug）。
 *
 * <p><b>正解顺序（显示管线）</b>：</p>
 * <pre>显示内容 = 镜像( 顺时针旋转( 解码帧 ) )</pre>
 * <p>即先对解码帧施加 {@code rotationDegrees}，再在显示坐标系（归一化到 [0,1]）上施加镜像。
 * 这与“前置摄像头自拍”的视觉直觉一致。</p>
 *
 * <p><b>反解顺序（本类的 {@link #map}）</b>，严格是上面的逆：</p>
 * <pre>
 *   display coordinate            (viewX, viewY)
 *     → 去 contentRect offset      u ∈ [0,1] 于可见区域内
 *     → 归一化到显示帧坐标          u′ = uStart + u · uVisibleFrac   （CENTER_CROP 在此还原裁切窗口）
 *     → inverse mirror             mirrorX/mirrorY 时 u′ = 1 − u′
 *     → inverse rotation           按 0/90/180/270 映射回源方向
 *     → source video coordinate    videoX ∈ [0, videoWidth]，已裁剪
 * </pre>
 *
 * <p><b>为什么不能写 {@code xVideo = xView * videoWidth / viewWidth}</b>：那条式子隐含了
 * “无黑边、无旋转、无镜像、宽高比一致”四个前提。真实播放器里这四个前提随时会被打破
 * （2:1 视频放进方形 View 就有黑边；竖屏视频带 90° 旋转；自拍是镜像），
 * 代入后得到的是**看起来合理但系统性偏移**的坐标 —— 蓝框判定会时对时错，极难排查。
 * 唯一安全的做法是按上面五步逐步解除。</p>
 *
 * <p><b>裁剪与对外承诺</b>：输出的 {@code videoX/videoY} 一律被夹在
 * [0, videoWidth] × [0, videoHeight] 内。落点是否“在画面上”由
 * {@link VideoPointMappingStatus} 表达，绝不靠越界坐标去暗示。</p>
 *
 * <p><b>正反两个方向都在本类里</b>：{@link #map} 反解（View → 视频），
 * {@link #mapVideoToView} 正推（视频 → View，是前者的严格逆）。之所以两个方向都要有，
 * 是因为调试可视化必须把「映射后的视频点」「识别出的目标框」画回屏幕上 ——
 * 那是个视频坐标，直接当 View 坐标画是错的（尺度、原点都不同）。</p>
 *
 * <p>线程约定：本类无状态（无字段、无缓存），所有方法均为纯函数，可任意线程并发调用。</p>
 */
public final class VideoCoordinateMapper {

    private VideoCoordinateMapper() {
        // 工具类，不实例化
    }

    // ------------------------------------------------------------------ 内容区推导

    /**
     * 旋转是否为 90 / 270 度 —— 此时显示画面的宽高与解码帧正好对调，
     * 内容区推导必须用「对调后的尺寸」，否则黑边方向会算反。
     */
    public static boolean isAxisSwapped(int rotationDegrees) {
        return rotationDegrees == 90 || rotationDegrees == 270;
    }

    /**
     * 由 View 与视频尺寸推导可见内容区（View 坐标，含黑边语义）。
     *
     * <p>这是给 {@link VideoGeometry#createWithScale} 与 {@link VideoGeometry#withScaleType}
     * 用的：如果 app 层能从渲染器直接拿到真实 {@code contentRect}，就以它为准（本方法不必调用）；
     * 拿不到时用这里的结果兜底。</p>
     *
     * @return {@code int[4]} = {left, top, right, bottom}；尺寸非法（&lt;= 0）时返回 null
     */
    public static int[] deriveContentRect(int viewWidth, int viewHeight,
                                          int videoWidth, int videoHeight,
                                          VideoScaleType scaleType, int rotationDegrees) {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return null;
        }
        final boolean swapped = isAxisSwapped(rotationDegrees);
        // 旋转 90/270 后，显示画面的宽高与解码帧对调
        final double displayedWidth = swapped ? videoHeight : videoWidth;
        final double displayedHeight = swapped ? videoWidth : videoHeight;
        final double scaleX = viewWidth / displayedWidth;
        final double scaleY = viewHeight / displayedHeight;
        final double scale = (scaleType != null && scaleType.isCrop())
                ? Math.max(scaleX, scaleY)   // 铺满：长边溢出，被裁掉
                : Math.min(scaleX, scaleY);  // 完整：短边留黑边

        int contentWidth = (int) Math.round(displayedWidth * scale);
        int contentHeight = (int) Math.round(displayedHeight * scale);
        // 取整后可能多出 1px，夹回 View 内，保证 isUsable() 的“内容区不越界”成立
        contentWidth = Math.max(1, Math.min(viewWidth, contentWidth));
        contentHeight = Math.max(1, Math.min(viewHeight, contentHeight));

        final int left = (viewWidth - contentWidth) / 2;
        final int top = (viewHeight - contentHeight) / 2;
        return new int[]{left, top, left + contentWidth, top + contentHeight};
    }

    // ------------------------------------------------------------------ 主映射

    /**
     * 把 View 坐标点反解为视频像素坐标。纯几何运算：不接触 MotionEvent、View 层级、手势识别，
     * 任何线程都可调用。
     *
     * @param geometry 几何快照，可为 null（→ {@code INVALID_GEOMETRY}）
     * @param viewX    相对承载 View 左边缘的像素 x
     * @param viewY    相对承载 View 上边缘的像素 y
     * @return 非 null 的映射结果，状态见 {@link VideoPointMappingStatus}
     */
    public static VideoPointMappingResult map(VideoGeometry geometry, float viewX, float viewY) {
        if (geometry == null || !geometry.isUsable()) {
            return VideoPointMappingResult.invalidGeometry(geometry, viewX, viewY);
        }
        if (!geometry.containsViewPoint(viewX, viewY)) {
            // 黑边 / View 之外：不产生坐标，也不该被上层当成“识别没命中”
            return VideoPointMappingResult.outsideVideo(geometry, viewX, viewY);
        }

        final boolean swapped = isAxisSwapped(geometry.rotationDegrees);
        final double displayedWidth = swapped ? geometry.videoHeight : geometry.videoWidth;
        final double displayedHeight = swapped ? geometry.videoWidth : geometry.videoHeight;
        final double visibleWidth = geometry.contentWidth();
        final double visibleHeight = geometry.contentHeight();

        // 缩放比：与 deriveContentRect 保持同一套算法，保证正反自洽
        final double scaleX = visibleWidth / displayedWidth;
        final double scaleY = visibleHeight / displayedHeight;
        final double scale = geometry.scaleType.isCrop()
                ? Math.max(scaleX, scaleY)
                : Math.min(scaleX, scaleY);

        // 可见范围占整幅显示画面的比例；CENTER_CROP 时 < 1，正是被裁掉的那部分
        final double visibleFracU = clamp01(visibleWidth / (displayedWidth * scale));
        final double visibleFracV = clamp01(visibleHeight / (displayedHeight * scale));
        // 裁切窗口居中，故从两侧各去掉一半
        final double startU = (1.0 - visibleFracU) * 0.5;
        final double startV = (1.0 - visibleFracV) * 0.5;

        // 1) 去 contentRect offset + 2) 归一化到显示画面坐标
        final double localU = (viewX - geometry.contentLeft) / visibleWidth;
        final double localV = (viewY - geometry.contentTop) / visibleHeight;
        double u = startU + localU * visibleFracU;
        double v = startV + localV * visibleFracV;

        // 3) 反镜像（显示坐标系下取反）
        if (geometry.mirrorX) {
            u = 1.0 - u;
        }
        if (geometry.mirrorY) {
            v = 1.0 - v;
        }

        // 4) 反旋转 → 5) 源视频坐标（这一步之后才是真正可裁剪的像素值）
        final double width = geometry.videoWidth;
        final double height = geometry.videoHeight;
        final double rawVideoX;
        final double rawVideoY;
        switch (geometry.rotationDegrees) {
            case 90:
                // 解码帧 (x,y) → 显示 (X,Y) = (H-1-y, x)
                rawVideoX = v * width;
                rawVideoY = (1.0 - u) * height;
                break;
            case 180:
                rawVideoX = (1.0 - u) * width;
                rawVideoY = (1.0 - v) * height;
                break;
            case 270:
                // 解码帧 (x,y) → 显示 (X,Y) = (y, W-1-x)
                rawVideoX = (1.0 - v) * width;
                rawVideoY = u * height;
                break;
            case 0:
            default:
                rawVideoX = u * width;
                rawVideoY = v * height;
                break;
        }

        final float videoX = (float) clamp(rawVideoX, 0.0, width);
        final float videoY = (float) clamp(rawVideoY, 0.0, height);
        return VideoPointMappingResult.success(geometry, viewX, viewY,
                videoX, videoY,
                (float) (videoX / width), (float) (videoY / height));
    }

    // ------------------------------------------------------------------ 正向映射

    /**
     * {@link #map} 的<b>严格逆</b>：把视频像素坐标正推回承载 View 的坐标。纯几何运算，
     * 不接触任何 Android 类型，任意线程可调用。
     *
     * <p><b>存在意义有两个</b>：</p>
     * <ol>
     *   <li><b>可视化</b>：调试层要把「映射后的视频点」「识别出的目标框」画回屏幕上。
     *       视频坐标不能直接当 View 坐标画 —— 尺度、原点、以及旋转/裁切都还没解除。</li>
     *   <li><b>自洽性检查</b>：{@code mapVideoToView(map(p))} 必须还原出 {@code p}。
     *       这把「反解有没有写错」变成可断言的事实，而不是靠肉眼看图猜。</li>
     * </ol>
     *
     * <p><b>顺序是 {@link #map} 的逆</b>：</p>
     * <pre>
     *   source video coordinate     (videoX, videoY)  ∈ [0, videoWidth] × [0, videoHeight]
     *     → forward rotation         按 0/90/180/270 映射到显示方向
     *     → forward mirror           mirrorX/mirrorY 时 u′ = 1 − u′（与反解同一操作，它是对合的）
     *     → restore visible window   u = (u′ − startU) / visibleFrac   （CENTER_CROP 在此加回裁切偏移）
     *     → display / view coordinate viewX = contentLeft + u · visibleWidth
     * </pre>
     *
     * <p>与反解共用同一套 {@code contentRect / visibleFrac / scale} 推导，因此正反必然自洽 ——
     * 这也是「坐标公式只有一份」的实际含义：不是指一个方法，而是指一套推导。</p>
     *
     * <p><b>越界处理</b>：输入先夹到视频范围内；若该点落在 CENTER_CROP 被裁掉的区域
     * （或旋转后被移出可见窗口），结果再夹到 [0, viewWidth] × [0, viewHeight] ——
     * 此时它没有真实的屏幕对应位置，返回值只是「最近的可见点」，
     * 往返误差会立刻暴露这种失真，不会伪装成正常落点。</p>
     *
     * @param geometry 几何快照，可为 null
     * @param videoX   视频（解码帧）像素 x
     * @param videoY   视频（解码帧）像素 y
     * @return {@code float[2]} = {viewX, viewY}；几何不可用或输入非有限值时返回 {@code null}
     */
    public static float[] mapVideoToView(VideoGeometry geometry, float videoX, float videoY) {
        if (geometry == null || !geometry.isUsable()) {
            return null;
        }
        if (!Float.isFinite(videoX) || !Float.isFinite(videoY)) {
            return null;
        }

        final double width = geometry.videoWidth;
        final double height = geometry.videoHeight;
        final double sourceX = clamp(videoX, 0.0, width);
        final double sourceY = clamp(videoY, 0.0, height);

        // 1) 正向旋转：源视频坐标 → 显示画面归一化坐标（u 沿显示画面水平，v 垂直）
        double u;
        double v;
        switch (geometry.rotationDegrees) {
            case 90:
                // map() 里是 videoX = v·W, videoY = (1−u)·H，此处取逆
                u = 1.0 - sourceY / height;
                v = sourceX / width;
                break;
            case 180:
                u = 1.0 - sourceX / width;
                v = 1.0 - sourceY / height;
                break;
            case 270:
                // map() 里是 videoX = (1−v)·W, videoY = u·H，此处取逆
                u = sourceY / height;
                v = 1.0 - sourceX / width;
                break;
            case 0:
            default:
                u = sourceX / width;
                v = sourceY / height;
                break;
        }

        // 2) 正向镜像（与反解同一式子：该操作是对合的，两次即还原）
        if (geometry.mirrorX) {
            u = 1.0 - u;
        }
        if (geometry.mirrorY) {
            v = 1.0 - v;
        }

        // 3) 还原可见窗口：把显示画面坐标折回可见区域内（与 map() 完全同一套推导）
        final boolean swapped = isAxisSwapped(geometry.rotationDegrees);
        final double displayedWidth = swapped ? height : width;
        final double displayedHeight = swapped ? width : height;
        final double visibleWidth = geometry.contentWidth();
        final double visibleHeight = geometry.contentHeight();
        final double scaleX = visibleWidth / displayedWidth;
        final double scaleY = visibleHeight / displayedHeight;
        final double scale = geometry.scaleType.isCrop()
                ? Math.max(scaleX, scaleY)
                : Math.min(scaleX, scaleY);
        final double visibleFracU = clamp01(visibleWidth / (displayedWidth * scale));
        final double visibleFracV = clamp01(visibleHeight / (displayedHeight * scale));
        final double startU = (1.0 - visibleFracU) * 0.5;
        final double startV = (1.0 - visibleFracV) * 0.5;

        // 4) 显示画面坐标 → View 坐标
        final double localU = (u - startU) / visibleFracU;
        final double localV = (v - startV) / visibleFracV;
        final double rawViewX = geometry.contentLeft + localU * visibleWidth;
        final double rawViewY = geometry.contentTop + localV * visibleHeight;

        return new float[]{
                (float) clamp(rawViewX, 0.0, geometry.viewWidth),
                (float) clamp(rawViewY, 0.0, geometry.viewHeight)};
    }

    // ------------------------------------------------------------------ 视频坐标 → 分析帧坐标

    /**
     * 视频像素坐标 → <b>分析帧</b>像素坐标。这是「点击点」与「像素」之间缺失的那一步：
     * {@link #map} 给出的 videoX/videoY 是以 {@code geometry.videoWidth/videoHeight}
     * 为尺度的解码帧坐标，而 {@code CapturedFrame} 是从承载 View 回读、并可能被
     * {@code maxFrameLongSidePx} 缩小过的<b>另一幅图</b>。直接把视频坐标当帧内下标去裁像素，
     * 会在「View 有黑边」「回读被缩小」时系统性取错区域 —— 而且错得很隐蔽
     * （ROI 依然在画面里，只是位置/大小不对）。</p>
     *
     * <p><b>推导方式</b>：先复用 {@link #mapVideoToView} 把视频点正推回 View 坐标，
     * 再把 View 坐标按「帧 / View」的等比缩放折算到帧上：</p>
     * <pre>
     *   viewPoint = mapVideoToView(videoX, videoY)
     *   frameX    = viewPoint.x · frameWidth  / viewWidth
     *   frameY    = viewPoint.y · frameHeight / viewHeight
     * </pre>
     * <p><b>为什么是除以 View 尺寸而不是内容区尺寸</b>：回读帧是<b>整个 View</b>的等比缩放副本
     * （SurfaceView 的 PixelCopy、TextureView 的 getBitmap 都是；回读尺寸也正来自
     * {@code FrameSize.forCapture(view.getWidth(), view.getHeight(), ...)}）。
     * FIT_CENTER 时黑边<b>也在帧里</b>，所以必须按 View 尺寸折算 ——
     * 若按内容区折算，黑边会把自己"吃掉"，落点在纵向（或横向）系统性偏移。
     * 旋转、镜像、裁切全部由上一步解除，本方法只做一次线性缩放，
     * 不引入任何独立于 {@link #mapVideoToView} 的几何假设。</p>
     *
     * <p><b>为什么不像 {@link #map} 那样对越界硬失败</b>：这里的调用方是 ROI 裁剪，
     * 它本身就能容忍边界上的取整误差（会在帧范围内夹取矩形）。因此结果被夹在
     * [0, frameWidth] × [0, frameHeight]；调用方应把返回的点视为「落在了帧边缘」。</p>
     *
     * @param geometry    几何快照；为 null 或不可用时返回 null（调用方应退化为 1:1 假设）
     * @param frameWidth  分析帧宽度，&gt; 0
     * @param frameHeight 分析帧高度，&gt; 0
     * @param videoX      视频像素 x
     * @param videoY      视频像素 y
     * @return {@code float[2]} = {frameX, frameY}；几何/尺寸不可用或输入非有限值时返回 null
     */
    public static float[] mapVideoToFrame(VideoGeometry geometry,
                                          int frameWidth, int frameHeight,
                                          float videoX, float videoY) {
        if (geometry == null || !geometry.isUsable()) {
            return null;
        }
        if (frameWidth <= 0 || frameHeight <= 0) {
            return null;
        }
        if (!Float.isFinite(videoX) || !Float.isFinite(videoY)) {
            return null;
        }
        final float[] viewPoint = mapVideoToView(geometry, videoX, videoY);
        if (viewPoint == null) {
            return null;
        }
        final double frameX = (double) viewPoint[0] * frameWidth / geometry.viewWidth;
        final double frameY = (double) viewPoint[1] * frameHeight / geometry.viewHeight;
        return new float[]{
                (float) clamp(frameX, 0.0, frameWidth),
                (float) clamp(frameY, 0.0, frameHeight)};
    }

    /**
     * 视频像素<b>长度</b> → 分析帧像素长度的比例，用于把 ROI 半径从视频尺度换算到帧尺度
     * （例如配置里写「200 视频像素」，回读帧被缩小到三分之一时要跟着缩三分之一）。
     *
     * <p>它是两段等比缩放的乘积：<b>视频 → 内容区</b>（{@code contentWidth / 显示宽度}）
     * 与 <b>View → 帧</b>（{@code frameWidth / viewWidth}）。两段都保持宽高比，
     * 所以合成后依然是单一标量，横向与纵向在取整误差内相等 ——
     * 正因如此这里只返回一个数，调用方不必（也不该）自己写除法。</p>
     *
     * <p>旋转 90/270 时显示宽度与解码帧宽度对调，用 {@link #isAxisSwapped} 校正，
     * 与 {@link #map} / {@link #mapVideoToView} 使用同一套推导。</p>
     *
     * @return 比例，恒 &gt; 0；几何不可用或帧尺寸非法时返回 {@link Float#NaN}
     */
    public static float videoToFrameScale(VideoGeometry geometry, int frameWidth, int frameHeight) {
        if (geometry == null || !geometry.isUsable() || frameWidth <= 0 || frameHeight <= 0) {
            return Float.NaN;
        }
        final boolean swapped = isAxisSwapped(geometry.rotationDegrees);
        final double displayedWidth = swapped ? geometry.videoHeight : geometry.videoWidth;
        final double videoToView = geometry.contentWidth() / displayedWidth;
        final double viewToFrame = (double) frameWidth / geometry.viewWidth;
        return (float) (videoToView * viewToFrame);
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }
}
