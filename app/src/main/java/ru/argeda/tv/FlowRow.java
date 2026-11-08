package ru.argeda.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Кнопки в строку с переносом: если не помещаются по ширине (телефон в книжной ориентации),
 * уходят на следующую строку, а не обрезаются у края. Отступы между кнопками — свои, поля детей не учитываются.
 */
public final class FlowRow extends ViewGroup {
    private final int gap;

    public FlowRow(Context c, AttributeSet a) {
        super(c, a);
        gap = Ui.dp(c, 10);
    }

    @Override
    protected void onMeasure(int ws, int hs) {
        int max = MeasureSpec.getSize(ws) - getPaddingLeft() - getPaddingRight();
        boolean bounded = MeasureSpec.getMode(ws) != MeasureSpec.UNSPECIFIED;
        int x = 0, y = 0, rowH = 0, used = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            measureChild(v, MeasureSpec.makeMeasureSpec(Math.max(0, max), bounded ? MeasureSpec.AT_MOST : MeasureSpec.UNSPECIFIED), hs);
            int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
            if (x > 0 && bounded && x + gap + w > max) {
                y += rowH + gap;
                x = 0;
                rowH = 0;
            }
            x += (x > 0 ? gap : 0) + w;
            used = Math.max(used, x);
            rowH = Math.max(rowH, h);
        }
        int width = bounded ? MeasureSpec.getSize(ws) : used + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(width, resolveSize(y + rowH + getPaddingTop() + getPaddingBottom(), hs));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int max = r - l - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowH = 0;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == GONE) continue;
            int w = v.getMeasuredWidth(), h = v.getMeasuredHeight();
            if (x > 0 && x + gap + w > max) {
                y += rowH + gap;
                x = 0;
                rowH = 0;
            }
            if (x > 0) x += gap;
            int left = rtl ? r - l - getPaddingRight() - x - w : getPaddingLeft() + x;
            v.layout(left, getPaddingTop() + y, left + w, getPaddingTop() + y + h);
            x += w;
            rowH = Math.max(rowH, h);
        }
    }
}
