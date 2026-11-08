package ru.argeda.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Рамка 16:9 для окна предпросмотра: высота считается от ширины.
 *
 * Видео рисуется в TextureView, то есть в том же слое, что и весь интерфейс: на ТВ-приставках
 * отдельный видеослой (SurfaceView) выводится со сдвигом, и рамка с ним не совпадала.
 * Углы скругляются без обрезки (clipToOutline/clipPath на части приставок ломали отрисовку экрана):
 * поверх видео рисуются только четыре уголка, залитые картинкой фона страницы и рамки.
 */
public class RatioFrame extends FrameLayout {
    private final Path corners = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private Bitmap cover;
    private boolean dirty = true;

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
        float l = getPaddingLeft(), t = getPaddingTop(), r = w - getPaddingRight(), b = h - getPaddingBottom();
        Path rect = new Path();
        rect.addRect(l, t, r, b, Path.Direction.CW);
        Path round = new Path();
        round.addRoundRect(l, t, r, b, radius, radius, Path.Direction.CW);
        corners.reset();
        corners.op(rect, round, Path.Op.DIFFERENCE);
        dirty = true;
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        dirty = true;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || corners.isEmpty()) return;
        if (dirty || cover == null || cover.getWidth() != w || cover.getHeight() != h) {
            if (cover == null || cover.getWidth() != w || cover.getHeight() != h) {
                if (cover != null) cover.recycle();
                cover = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            Canvas bc = new Canvas(cover);
            bc.drawColor(getResources().getColor(R.color.bg));
            Drawable bg = getBackground();
            if (bg != null) {
                bg.setBounds(0, 0, w, h);
                bg.draw(bc);
            }
            paint.setShader(new BitmapShader(cover, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
            dirty = false;
        }
        c.drawPath(corners, paint);
    }
}
