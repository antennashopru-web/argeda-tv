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

    /** Размер текста: множитель к системному. */
    @JvmField
    val TEXT_NAMES = arrayOf("Мелкий", "Обычный", "Крупный", "Очень крупный")
    private val TEXT_SCALE = floatArrayOf(0.85f, 1f, 1.15f, 1.3f)
    private const val PREF_TEXT = "textSize"

    @JvmStatic
    fun textSize(c: Context): Int = c.getSharedPreferences("argeda", Context.MODE_PRIVATE).getInt(PREF_TEXT, 1).coerceIn(0, TEXT_SCALE.size - 1)

    @JvmStatic
    fun setTextSize(a: Activity, i: Int) {
        a.getSharedPreferences("argeda", Context.MODE_PRIVATE).edit().putInt(PREF_TEXT, i).apply()
        a.recreate()
    }

    /** Вызывать из attachBaseContext после super: подменяет ночной режим и размер текста для ресурсов экрана. */
    @JvmStatic
    fun apply(a: Activity, base: Context, forceDark: Boolean) {
        val mode = if (forceDark) DARK else get(base)
        val scale = TEXT_SCALE[textSize(base)]
        if (mode == SYSTEM && scale == 1f) return
        val o = Configuration()
        if (mode != SYSTEM) {
            val ui = base.resources.configuration.uiMode
            o.uiMode = (ui and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (mode == DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        if (scale != 1f) o.fontScale = base.resources.configuration.fontScale * scale
        a.applyOverrideConfiguration(o)
    }
}
