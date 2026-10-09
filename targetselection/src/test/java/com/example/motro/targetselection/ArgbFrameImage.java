package com.example.motro.targetselection;

import com.example.motro.targetselection.frame.FrameImage;

/**
 * 单测用的内存图：带一块可读像素缓冲，因此可以覆盖
 * {@link FrameImage#readArgbRegion} 这条只有真实载体才会走的路径。
 *
 * <p>与 {@link FakeFrameImage} 的分工：那个只提供尺寸（用来验证
 * {@code PIXELS_UNAVAILABLE}）；这个能真正出像素，用来验证「裁出来的矩形对不对」。</p>
 */
final class ArgbFrameImage implements FrameImage {

    final int width;
    final int height;
    final int[] pixels;

    /** 置 false 可模拟「载体不支持像素回读」。 */
    boolean readoutSupported = true;

    int releaseCount;
    int readCount;
    int lastReadX = -1;
    int lastReadY = -1;
    int lastReadWidth;
    int lastReadHeight;

    private ArgbFrameImage(int width, int height, int[] pixels) {
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    /**
     * @param pixels 行优先 ARGB，长度必须 &ge; {@code width * height}
     * @return 直接引用传入数组（测试里需要按像素断言，故意的）
     */
    static ArgbFrameImage of(int width, int height, int[] pixels) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("bad size " + width + "x" + height);
        }
        if (pixels.length < width * height) {
            throw new IllegalArgumentException("pixels too short: " + pixels.length + " < " + (width * height));
        }
        return new ArgbFrameImage(width, height, pixels);
    }

    static ArgbFrameImage solid(int width, int height, int argb) {
        return of(width, height, TestImages.solid(width, height, argb));
    }

    int pixelAt(int x, int y) {
        return pixels[y * width + x];
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void release() {
        releaseCount++;
    }

    @Override
    public boolean readArgbRegion(int x, int y, int width, int height,
                                  int[] out, int offset, int stride) {
        if (!readoutSupported) {
            return false;
        }
        readCount++;
        lastReadX = x;
        lastReadY = y;
        lastReadWidth = width;
        lastReadHeight = height;
        for (int row = 0; row < height; row++) {
            System.arraycopy(this.pixels, (y + row) * this.width + x,
                    out, offset + row * stride, width);
        }
        return true;
    }
}
