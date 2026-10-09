package com.example.motro.targetselection;

import com.example.motro.targetselection.frame.FrameImage;

/**
 * 测试用假图像载体。核心 API 能在纯 JVM 里被完整测试，正是 {@code FrameImage} 抽象存在的理由之一
 * （{@code android.graphics.Bitmap} 无法在 JVM 单测中实例化）。
 */
final class FakeFrameImage implements FrameImage {

    final int width;
    final int height;
    int releaseCount;

    FakeFrameImage(int width, int height) {
        this.width = width;
        this.height = height;
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
}
