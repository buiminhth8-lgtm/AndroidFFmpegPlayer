package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameImage;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometry;

/**
 * 把「点击点附近的局部区域」从整帧里裁出来 —— 播放器无关、<b>零 Android 依赖</b>，
 * 因此裁剪边界逻辑可以在纯 JVM 单测里穷举（这正是最容易出越界崩溃的地方）。
 *
 * <p><b>为什么只裁局部而不是分析整帧</b>：1080p 整帧 {@code getPixels} 要搬 8MB，
 * 而 400×400 的 ROI 只有 640KB；颜色分析也从 200 万像素降到 16 万像素。
 * 这条「先缩小战场」的选择是后续所有 Slice 能在几毫秒内出结果的前提。</p>
 *
 * <p><b>两套坐标的换算全部委托给 {@link VideoCoordinateMapper}</b>：视频坐标 → 帧坐标
 * （{@link VideoCoordinateMapper#mapVideoToFrame}）、半径的视频尺度 → 帧尺度
 * （{@link VideoCoordinateMapper#videoToFrameScale}）。本类不含任何旋转/镜像/缩放公式，
 * 只做「夹取、取整、读像素」。</p>
 *
 * <p><b>边界策略（不崩溃是硬要求）</b>：ROI 先按半径展开，再逐边夹进
 * {@code [0, frameWidth] × [0, frameHeight]}；因此靠左/右/上/下边缘、乃至四个角上的点击
 * 都只会得到一个<b>变小但合法</b>的矩形，绝不会出现负宽高或越界下标。
 * 若夹取后宽或高退化为 0（半径折算到帧尺度后小于半个像素），报
 * {@link TouchRoiStatus#INVALID_ROI} 而不是去读一块空区域。</p>
 *
 * <p><b>像素所有权</b>：本类<b>不</b>释放传入的帧（所有权在调用方），也<b>不</b>缓存帧或像素；
 * 每次调用新分配一块 ROI 缓冲（理由见 {@link TouchRoiResult})。本类无状态，
 * 可被多线程并发调用。</p>
 *
 * <p>线程约定：无状态、纯逻辑，任意线程可调用；像素读取走
 * {@link FrameImage#readArgbRegion}，其线程安全由载体自身保证。</p>
 */
public final class TouchRoiExtractor {

    /**
     * 裁出 ROI。
     *
     * @param frame    待裁剪的帧，非 null；其图像载荷在调用期间必须有效
     * @param geometry 几何快照，可为 null（null → 视作「帧坐标 == 视频坐标」，1:1）
     * @param videoX   点击点视频像素 x
     * @param videoY   点击点视频像素 y
     * @param radiusX  ROI 水平半径（<b>视频</b>像素），必须 &gt; 0
     * @param radiusY  ROI 垂直半径（<b>视频</b>像素），必须 &gt; 0
     * @return 非 null 的结果；状态见 {@link TouchRoiStatus}
     */
    public TouchRoiResult extract(CapturedFrame frame, VideoGeometry geometry,
                                  float videoX, float videoY,
                                  int radiusX, int radiusY) {
        if (frame == null) {
            return TouchRoiResult.failure(TouchRoiStatus.INVALID_FRAME, "frame is null");
        }
        final FrameImage image = frame.image();
        if (image == null) {
            return TouchRoiResult.failure(TouchRoiStatus.INVALID_FRAME,
                    "frame has no image payload (empty or already released)");
        }
        final int frameWidth = frame.width;
        final int frameHeight = frame.height;
        if (frameWidth <= 0 || frameHeight <= 0) {
            return TouchRoiResult.failure(TouchRoiStatus.INVALID_FRAME,
                    "frame size is " + frameWidth + "x" + frameHeight);
        }
        if (radiusX <= 0 || radiusY <= 0) {
            return TouchRoiResult.failure(TouchRoiStatus.INVALID_ROI,
                    "roi radius must be > 0 but was " + radiusX + "x" + radiusY);
        }
        if (!Float.isFinite(videoX) || !Float.isFinite(videoY)) {
            return TouchRoiResult.failure(TouchRoiStatus.INVALID_ROI,
                    "touch point must be finite but was " + videoX + "," + videoY);
        }

        // 1) 视频坐标系的范围：有几何就用几何的解码尺寸，没有就认为帧就是视频帧
        final boolean hasGeometry = geometry != null && geometry.isUsable();
        final int videoWidth = hasGeometry ? geometry.videoWidth : frameWidth;
        final int videoHeight = hasGeometry ? geometry.videoHeight : frameHeight;
        if (videoX < 0f || videoY < 0f || videoX > videoWidth || videoY > videoHeight) {
            return TouchRoiResult.failure(TouchRoiStatus.OUTSIDE_VIDEO,
                    "touch point " + videoX + "," + videoY
                            + " is outside video " + videoWidth + "x" + videoHeight);
        }

        // 2) 换算到帧坐标 + 半径的视频尺度 → 帧尺度
        final float touchFrameX;
        final float touchFrameY;
        final float scale;
        if (hasGeometry) {
            final float[] mapped = VideoCoordinateMapper.mapVideoToFrame(
                    geometry, frameWidth, frameHeight, videoX, videoY);
            final float mappedScale = VideoCoordinateMapper.videoToFrameScale(
                    geometry, frameWidth, frameHeight);
            if (mapped == null || Float.isNaN(mappedScale)) {
                return TouchRoiResult.failure(TouchRoiStatus.INVALID_ROI,
                        "cannot convert video point to frame space with the given geometry");
            }
            touchFrameX = mapped[0];
            touchFrameY = mapped[1];
            scale = mappedScale;
        } else {
            // 无几何：帧与视频 1:1（例如直接把解码帧交给分析的通道）
            touchFrameX = videoX;
            touchFrameY = videoY;
            scale = 1f;
        }

        // 3) 展开 + 逐边夹取：这是「不因越界崩溃」的全部秘密
        final int centerX = Math.round(touchFrameX);
        final int centerY = Math.round(touchFrameY);
        final int radiusFrameX = Math.max(1, Math.round(radiusX * scale));
        final int radiusFrameY = Math.max(1, Math.round(radiusY * scale));
        final int left = Math.max(0, centerX - radiusFrameX);
        final int top = Math.max(0, centerY - radiusFrameY);
        final int right = Math.min(frameWidth, centerX + radiusFrameX);
        final int bottom = Math.min(frameHeight, centerY + radiusFrameY);
        final int width = right - left;
        final int height = bottom - top;
        if (width <= 0 || height <= 0) {
            return TouchRoiResult.failure(TouchRoiStatus.INVALID_ROI,
                    "roi collapsed to " + width + "x" + height + " around "
                            + touchFrameX + "," + touchFrameY);
        }

        // 4) 视频坐标下的 ROI（对外交流一律用它，调试叠加层也画它）
        final TargetBoundingBox videoRect = TargetBoundingBox.of(
                Math.max(0f, videoX - radiusX),
                Math.max(0f, videoY - radiusY),
                Math.min(videoWidth, videoX + radiusX),
                Math.min(videoHeight, videoY + radiusY));

        final TouchRoi roi = TouchRoi.of(videoRect, left, top, width, height, touchFrameX, touchFrameY);

        // 5) 像素回读：一次性拷一块，不做逐像素 JNI 往返
        final int[] buffer = new int[width * height];
        final FrameImage target = frame.image();
        if (target == null) {
            // 竞态：读取前帧被释放了
            return TouchRoiResult.failure(TouchRoiStatus.INVALID_FRAME,
                    "frame was released while extracting the roi");
        }
        if (!target.readArgbRegion(left, top, width, height, buffer, 0, width)) {
            return TouchRoiResult.failure(TouchRoiStatus.PIXELS_UNAVAILABLE,
                    "frame image does not support ARGB readout");
        }
        return TouchRoiResult.success(roi, buffer);
    }
}
