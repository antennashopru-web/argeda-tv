package ru.argeda.tv

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Рамка 16:9 для окна предпросмотра: высота считается от ширины.
 *
 * Видео идёт через SurfaceView (отдельный видеослой). TextureView на ТВ-приставке ARTAK ломал
 * отрисовку всего экрана (правая половина чёрная), а видеослой там ставится с небольшим сдвигом,
 * поэтому поверх видео ничего не рисуем. Вместо этого внутренний отступ рамки подобран так,
 * что квадратные углы видео целиком лежат внутри скруглённой рамки и не вылезают за неё.
 */
open class RatioFrame @JvmOverloads constructor(c: Context, a: AttributeSet? = null) : FrameLayout(c, a) {
    override fun onMeasure(w: Int, h: Int) {
        val width = View.MeasureSpec.getSize(w)
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(width * 9 / 16, View.MeasureSpec.EXACTLY))
    }
}
