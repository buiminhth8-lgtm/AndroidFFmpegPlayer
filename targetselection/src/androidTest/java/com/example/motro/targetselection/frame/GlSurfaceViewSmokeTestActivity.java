package com.example.motro.targetselection.frame;

import android.app.Activity;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.ViewGroup;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 冒烟测试宿主：一个持续渲染纯色的 {@link GLSurfaceView}。
 *
 * <p>为什么用 GLSurfaceView 而不是手工 {@code lockCanvas} 的 SurfaceView：
 * PixelCopy 的设计初衷就是从 GPU 合成的 Surface 回读（替代 {@code glReadPixels}），
 * GL 输出才是它最典型、最可靠的输入；再用「清屏色 == 回读像素」做内容断言，
 * 就能证明拿到的是<b>真实画面</b>而不只是一张尺寸正确的空位图。</p>
 *
 * <p>GL 线程与测试线程之间用 {@link #awaitFirstFrame} 同步，避免“刚建好 Surface 就回读”
 * 得到 {@code ERROR_SOURCE_NO_DATA} 的假失败。</p>
 */
public final class GlSurfaceViewSmokeTestActivity extends Activity {

    /** 清屏色：纯红，便于用阈值断言回读像素（避免浮点→8bit 舍入带来的抖动）。 */
    public static final int CLEAR_RED = 255;

    private final CountDownLatch firstFrame = new CountDownLatch(1);
    private GLSurfaceView glSurfaceView;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        GLSurfaceView view = new GLSurfaceView(this);
        view.setEGLContextClientVersion(2);
        view.setRenderer(new GLSurfaceView.Renderer() {
            @Override
            public void onSurfaceCreated(GL10 gl, EGLConfig config) {
                GLES20.glClearColor(1f, 0f, 0f, 1f);
            }

            @Override
            public void onSurfaceChanged(GL10 gl, int width, int height) {
                GLES20.glViewport(0, 0, width, height);
                surfaceWidth = width;
                surfaceHeight = height;
            }

            @Override
            public void onDrawFrame(GL10 gl) {
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                firstFrame.countDown();
            }
        });
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        setContentView(view, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        glSurfaceView = view;
    }

    /** 供被测实现使用的真实 SurfaceView（GLSurfaceView 是它的子类）。 */
    GLSurfaceView surfaceView() {
        return glSurfaceView;
    }

    /** 等待 GL 至少渲染出一帧。 */
    boolean awaitFirstFrame(long timeoutMs) throws InterruptedException {
        return firstFrame.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    int renderedWidth() {
        return surfaceWidth;
    }

    int renderedHeight() {
        return surfaceHeight;
    }
}
