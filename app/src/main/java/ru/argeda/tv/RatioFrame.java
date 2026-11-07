package ru.argeda.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/** Рамка 16:9 для окна предпросмотра: высота считается от ширины. */
public class RatioFrame extends FrameLayout {
    public RatioFrame(Context c) {
        super(c);
    }

    public RatioFrame(Context c, AttributeSet a) {
        super(c, a);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = View.MeasureSpec.getSize(w);
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(width * 9 / 16, View.MeasureSpec.EXACTLY));
    }
}
