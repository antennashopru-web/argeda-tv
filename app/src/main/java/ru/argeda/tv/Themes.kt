package ru.argeda.tv

import android.app.Activity
import android.content.Context
import android.content.res.Configuration

/** Светлая / тёмная тема. Цвета лежат в values (светлая) и values-night (тёмная). */
internal object Themes {
    const val DARK = 0
    const val LIGHT = 1
    const val SYSTEM = 2

    @JvmField
    val NAMES = arrayOf("Тёмная", "Светлая", "Как в системе")
    private const val PREF = "theme"

    @JvmStatic
    fun get(c: Context): Int = c.getSharedPreferences("argeda", Context.MODE_PRIVATE).getInt(PREF, DARK)

    @JvmStatic
    fun set(a: Activity, mode: Int) {
        a.getSharedPreferences("argeda", Context.MODE_PRIVATE).edit().putInt(PREF, mode).apply()
        a.recreate()
    }

    /** Вызывать из attachBaseContext после super: подменяет ночной режим для ресурсов экрана. */
    @JvmStatic
    fun apply(a: Activity, base: Context, forceDark: Boolean) {
        val mode = if (forceDark) DARK else get(base)
        if (mode == SYSTEM) return
        val ui = base.resources.configuration.uiMode
        val o = Configuration()
        o.uiMode = (ui and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (mode == DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        a.applyOverrideConfiguration(o)
    }
}
