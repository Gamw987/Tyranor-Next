package com.core.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

/**
 * 系统悬浮窗管理器：用 [WindowManager.TYPE_APPLICATION_OVERLAY] 把 [ImGuiOverlayView]
 * 挂在系统最上层，可浮在第三方 App（如 JoiPlay 游戏）窗口之上。
 *
 * <p>【权限】需要 android.permission.SYSTEM_ALERT_WINDOW，且必须由用户在系统设置
 * 中手动开启“显示过其他应用”。本类只负责检测与挂载，绝不自动拉起系统设置。
 *
 * <p>【生命周期】以 Application 上下文持有 WindowManager 与 View，随进程存续；
 * 设置页开关变更时由调用方 [show]/[hide] 控制。
 */
class ExternalOverlayManager private constructor(
    context: Context,
) {

    private val appContext: Context = context.applicationContext

    private val windowManager: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val view: ImGuiOverlayView = ImGuiOverlayView(appContext.applicationContext)

    @Volatile
    private var showing = false

    /** 当前是否已获系统悬浮窗权限（API 23+ 才需要；更低版本默认视为已授权）。 */
    fun isOverlayPermissionGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(appContext.applicationContext)
        } else {
            true
        }

    /** 挂载浮层。无权限或已显示时直接返回 false。 */
    @SuppressLint("WrongConstant")
    fun show(): Boolean {
        if (showing) return true
        if (!isOverlayPermissionGranted()) return false

        val density = appContext.resources.displayMetrics.density
        val width = (360f * density).toInt()
        val height = (240f * density).toInt()
        val margin = (16f * density).toInt()

        val params = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = margin
        params.y = margin
        params.packageName = appContext.packageName

        try {
            windowManager.addView(view, params)
            showing = true
            return true
        } catch (e: Exception) {
            showing = false
            return false
        }
    }

    /** 移除浮层并停止渲染。未显示时直接返回。 */
    fun hide() {
        if (!showing) return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // 可能已随窗口/进程一并销毁，忽略
        }
        view.stopRender()
        showing = false
    }

    /** 按开关状态同步显示/隐藏。 */
    fun toggle(enable: Boolean) {
        if (enable) show() else hide()
    }

    companion object {
        @Volatile
        private var instance: ExternalOverlayManager? = null

        fun init(context: Context): ExternalOverlayManager {
            return instance ?: synchronized(this) {
                instance ?: ExternalOverlayManager(context).also { instance = it }
            }
        }

        fun get(): ExternalOverlayManager? = instance
    }
}
