package ru.argeda.tv

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.max

/**
 * Кнопки в строку с переносом: если не помещаются по ширине (телефон в книжной ориентации),
 * уходят на следующую строку, а не обрезаются у края. Отступы между кнопками — свои, поля детей не учитываются.
 */
class FlowRow(c: Context, a: AttributeSet?) : ViewGroup(c, a) {
    private val gap = Ui.dp(c, 10)

    override fun onMeasure(ws: Int, hs: Int) {
        val maxW = MeasureSpec.getSize(ws) - paddingLeft - paddingRight
        val bounded = MeasureSpec.getMode(ws) != MeasureSpec.UNSPECIFIED
        var x = 0
        var y = 0
        var rowH = 0
        var used = 0
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            measureChild(v, MeasureSpec.makeMeasureSpec(max(0, maxW), if (bounded) MeasureSpec.AT_MOST else MeasureSpec.UNSPECIFIED), hs)
            val w = v.measuredWidth
            val h = v.measuredHeight
            if (x > 0 && bounded && x + gap + w > maxW) {
                y += rowH + gap
                x = 0
                rowH = 0
            }
            x += (if (x > 0) gap else 0) + w
            used = max(used, x)
            rowH = max(rowH, h)
        }
        val width = if (bounded) MeasureSpec.getSize(ws) else used + paddingLeft + paddingRight
        setMeasuredDimension(width, resolveSize(y + rowH + paddingTop + paddingBottom, hs))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            if (v.visibility == GONE) continue
            val w = v.measuredWidth
            val h = v.measuredHeight
            if (x > 0 && x + gap + w > maxW) {
                y += rowH + gap
                x = 0
                rowH = 0
            }
            if (x > 0) x += gap
            val left = if (rtl) r - l - paddingRight - x - w else paddingLeft + x
            v.layout(left, paddingTop + y, left + w, paddingTop + y + h)
            x += w
            rowH = max(rowH, h)
        }
    }
}
