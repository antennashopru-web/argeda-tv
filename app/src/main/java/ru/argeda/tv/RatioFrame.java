package ru.argeda.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Рамка 16:9 для окна предпросмотра: высота считается от ширины.
 * Углы видео скругляются без обрезки самого видео: поверх детей рамка заново рисует свой фон
 * только в уголках вне скруглённого прямоугольника. Так видео остаётся в SurfaceView, которое
 * на ТВ-приставках работает надёжно (TextureView с clipToOutline давал чёрный экран).
 */
public class RatioFrame extends FrameLayout {
    private final Path inner = new Path();
    private final float radius;

    public RatioFrame(Context c) {
        this(c, null);
    }

    public RatioFrame(Context c, AttributeSet a) {
        super(c, a);
        radius = 15 * c.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
    }

    @Override
    protected void onMeasure(int w, int h) {
        int width = View.MeasureSpec.getSize(w);
        super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(width * 9 / 16, View.MeasureSpec.EXACTLY));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        inner.reset();
        inner.addRoundRect(getPaddingLeft(), getPaddingTop(), w - getPaddingRight(), h - getPaddingBottom(),
                radius, radius, Path.Direction.CW);
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        Drawable bg = getBackground();
        if (bg == null || inner.isEmpty()) return;
        int s = c.save();
        if (Build.VERSION.SDK_INT >= 26) c.clipOutPath(inner);
        else c.clipPath(inner, Region.Op.DIFFERENCE);
        bg.draw(c);
        c.restoreToCount(s);
    }
}
