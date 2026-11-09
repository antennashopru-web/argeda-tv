package ru.argeda.tv

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.roundToInt

internal object Ui {
    @JvmStatic
    fun dp(c: Context, v: Float): Int = (v * c.resources.displayMetrics.density).roundToInt()

    @JvmStatic
    fun dp(c: Context, v: Int): Int = dp(c, v.toFloat())

    @JvmStatic
    fun toast(c: Context, s: String) {
        Toast.makeText(c, s, if (s.length > 60) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /** Приставка или телевизор: пульт вместо сенсора. */
    @JvmStatic
    fun isTv(c: Context): Boolean {
        val m = c.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager?
        val pm = c.packageManager
        return (m != null && m.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) ||
            pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    }

    /**
     * Безопасная зона на телевизоре: многие ТВ обрезают края картинки (overscan), поэтому
     * интерфейс отступает от краёв экрана. Фон корневого вида остаётся на весь экран.
     * Вместе с собственными отступами разметки получается около 5% по бокам, как советует Google.
     */
    @JvmStatic
    fun tvSafeArea(a: Activity) {
        if (!isTv(a)) return
        val content = a.findViewById<ViewGroup?>(android.R.id.content)
        if (content == null || content.childCount == 0) return
        val root = content.getChildAt(0)
        root.fitsSystemWindows = false // на ТВ нет системных панелей, а их отступы затёрли бы наши
        val h = dp(a, 32f)
        val v = dp(a, 20f)
        root.setPadding(root.paddingLeft + h, root.paddingTop + v, root.paddingRight + h, root.paddingBottom + v)
    }

    /** Не даём экрану погаснуть, пока идёт просмотр. */
    @JvmStatic
    fun keepScreenOn(a: Activity, on: Boolean) {
        if (on) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
