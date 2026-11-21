package com.core.overlay;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.TextureView;

/**
 * 外部悬浮窗的宿主视图。
 *
 * <p>TextureView 提供 Surface，native 层在同一渲染线程内自建 EGL/GLES3 上下文并用
 * ImGui 绘制，因此不需要 SYSTEM_ALERT_WINDOW，也不会与引擎自己的 GL 上下文共享状态。
 * 第 1 步只负责显示：本视图不消费触摸事件，事件会继续落到下面的游戏 SurfaceView。
 */
public class ImGuiOverlayView extends TextureView implements TextureView.SurfaceTextureListener {

    static {
        System.loadLibrary("imgui_overlay");
    }

    private static final long FRAME_INTERVAL_MS = 16L;

    private volatile boolean running;
    private Thread renderThread;

    public ImGuiOverlayView(Context context) {
        super(context);
        setOpaque(false);
        setSurfaceTextureListener(this);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        stopRender();
        surfaceTexture.setDefaultBufferSize(Math.max(1, width), Math.max(1, height));
        final Surface surface = new Surface(surfaceTexture);
        running = true;
        renderThread = new Thread(() -> renderLoop(surface, width, height), "ImGuiOverlay");
        renderThread.start();
    }

    private void renderLoop(Surface surface, int width, int height) {
        try {
            nativeStart(surface, width, height);
            while (running) {
                nativeStep();
                try {
                    Thread.sleep(FRAME_INTERVAL_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Throwable failure) {
            android.util.Log.w("ImGuiOverlay", "render loop failed", failure);
        } finally {
            nativeStop();
            surface.release();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        surfaceTexture.setDefaultBufferSize(Math.max(1, width), Math.max(1, height));
        nativeResize(width, height);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        stopRender();
        // Surface 由渲染线程在退出时释放，这里不再返回 false 持有它。
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        // 内容由 native 渲染线程直接提交，无需额外处理。
    }

    /** 停止渲染线程；可重复调用。 */
    public void stopRender() {
        running = false;
        Thread thread = renderThread;
        renderThread = null;
        if (thread != null) {
            try {
                thread.join(800L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static native void nativeStart(Surface surface, int width, int height);

    private static native void nativeResize(int width, int height);

    private static native void nativeStep();

    private static native void nativeStop();
}
