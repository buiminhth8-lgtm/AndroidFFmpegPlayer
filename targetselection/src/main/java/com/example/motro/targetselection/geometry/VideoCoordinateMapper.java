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

    private static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : (value > max ? max : value);
    }
}
