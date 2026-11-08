package ru.argeda.tv;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Рамка 16:9 для окна предпросмотра: высота считается от ширины.
 *
 * Видео идёт через SurfaceView (отдельный видеослой). TextureView на ТВ-приставке ARTAK ломал
 * отрисовку всего экрана (правая половина чёрная), а видеослой там ставится с небольшим сдвигом,
 * поэтому поверх видео ничего не рисуем. Вместо этого внутренний отступ рамки подобран так,
 * что квадратные углы видео целиком лежат внутри скруглённой рамки и не вылезают за неё.
 */
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
