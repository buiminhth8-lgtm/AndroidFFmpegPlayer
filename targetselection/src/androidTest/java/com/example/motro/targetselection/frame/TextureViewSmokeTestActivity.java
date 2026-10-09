package com.example.motro.targetselection.frame;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.TextureView;
import android.view.ViewGroup;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 冒烟测试宿主：一个画了纯色的 {@link TextureView}。
 *
 * <p>{@code TextureView.getBitmap()} 读的是 View 自己的纹理层，窗口背景色不算数 ——
 * 所以必须真的往它的画布上画东西（{@link TextureView#lockCanvas()}），
 * 否则回读到的是全透明位图，“尺寸对但内容是空的”会让断言形同虚设。</p>
 */
public final class TextureViewSmokeTestActivity extends Activity
        implements TextureView.SurfaceTextureListener {

    /** 填充色：纯绿，便于用阈值断言回读像素。 */
    public static final int FILL_GREEN = 255;

    private final CountDownLatch available = new CountDownLatch(1);
    private TextureView textureView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextureView view = new TextureView(this);
        view.setSurfaceTextureListener(this);
        setContentView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        textureView = view;
    }

    TextureView textureView() {
        return textureView;
    }

    boolean awaitAvailable(long timeoutMs) throws InterruptedException {
        return available.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** 往纹理画布上填纯色；画不上（画布未就绪）返回 false。 */
    boolean drawFillColor() {
        Canvas canvas = textureView.lockCanvas();
        if (canvas == null) {
            return false;
        }
        try {
            canvas.drawColor(Color.rgb(0, FILL_GREEN, 0));
            return true;
        } finally {
            textureView.unlockCanvasAndPost(canvas);
        }
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        available.countDown();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }
}
