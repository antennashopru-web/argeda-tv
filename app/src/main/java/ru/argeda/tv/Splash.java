package ru.argeda.tv;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * Заставка при запуске под музыкальное вступление и голос «ARGEDA TV» (res/raw/argeda_tv.mp3).
 * Плитка знака влетает, лопасти раскручиваются, на «удар» музыки выпрыгивает экран с Play,
 * вспыхивает свечение, расходятся кольца и искры, по плитке проходит блик, надпись появляется по буквам.
 * Лежит поверх главного экрана: плейлист и первый канал грузятся одновременно с ней.
 * Любая кнопка или касание её пропускают.
 */
final class Splash {
    interface Done {
        void run();
    }

    /** Момент «удара» в музыке, когда выпрыгивает экран с Play, мс. */
    static final long HIT = 620;
    private static final long HOLD = 2450, FADE = 300;
    private static final int[] SPARK = {0xFFE30613, 0xFF0047BB, 0xFFF28C00, 0xFFA855F7, 0xFFC4B5FD, Color.WHITE};

    private final Activity a;
    private final Done done;
    private FrameLayout root;
    private Fx fx;
    private MediaPlayer voice;
    private boolean closing;
    private final Runnable auto = this::dismiss;

    private Splash(Activity a, Done done) {
        this.a = a;
        this.done = done;
    }

    static Splash show(Activity a, Done done) {
        Splash s = new Splash(a, done);
        s.build();
        return s;
    }

    boolean showing() {
        return root != null && !closing;
    }

    private int dp(float v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    private void build() {
        root = new FrameLayout(a);
        root.setBackgroundColor(0xFF0B0816);
        root.setClickable(true);
        root.setOnClickListener(v -> skip());

        int size = dp(150);
        fx = new Fx(a, size);
        root.addView(fx, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        // Плитка знака: белая, скруглённая; всё внутри обрезается по её форме, чтобы блик не вылезал.
        FrameLayout logo = new FrameLayout(a);
        GradientDrawable white = new GradientDrawable();
        white.setColor(Color.WHITE);
        white.setCornerRadius(dp(36));
        logo.setBackground(white);
        final float r = dp(36);
        logo.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        logo.setClipToOutline(true);
        logo.setElevation(dp(16));
        ImageView blades = new ImageView(a);
        blades.setImageResource(R.drawable.splash_blades);
        ImageView screen = new ImageView(a);
        screen.setImageResource(R.drawable.splash_screen);
        View shine = new View(a);
        shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00FFFFFF, 0xB3FFFFFF, 0x00FFFFFF}));
        logo.addView(blades, new FrameLayout.LayoutParams(size, size));
        logo.addView(screen, new FrameLayout.LayoutParams(size, size));
        logo.addView(shine, new FrameLayout.LayoutParams(size / 3, size * 2, Gravity.CENTER_VERTICAL));
        col.addView(logo, new LinearLayout.LayoutParams(size, size));
        fx.anchor = logo;

        // Надпись по буквам: ARGEDA белым, TV фирменным фиолетовым.
        LinearLayout title = new LinearLayout(a);
        title.setOrientation(LinearLayout.HORIZONTAL);
        String text = "ARGEDA TV";
        TextView[] letters = new TextView[text.length()];
        for (int i = 0; i < text.length(); i++) {
            TextView l = new TextView(a);
            l.setText(String.valueOf(text.charAt(i)));
            l.setTextColor(i >= 7 ? 0xFFB57BFF : Color.WHITE);
            l.setTextSize(36);
            l.setTypeface(Typeface.DEFAULT_BOLD);
            l.setIncludeFontPadding(false);
            l.setShadowLayer(dp(10), 0, 0, i >= 7 ? 0xCC8B5CF6 : 0x667C3AED);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = lp.rightMargin = dp(1.5f);
            title.addView(l, lp);
            letters[i] = l;
        }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(26);
        col.addView(title, tl);

        View line = new View(a);
        line.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x00A855F7, 0xFFA855F7, 0xFFFFFFFF, 0xFFA855F7, 0x00A855F7}));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(dp(220), dp(2));
        ll.topMargin = dp(12);
        col.addView(line, ll);

        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        a.addContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Плитка влетает из глубины с лёгким поворотом.
        logo.setScaleX(0.25f);
        logo.setScaleY(0.25f);
        logo.setRotation(-25f);
        logo.setAlpha(0f);
        logo.animate().scaleX(1f).scaleY(1f).rotation(0f).alpha(1f).setDuration(560).setInterpolator(new OvershootInterpolator(1.4f));
        // Лопасти: два оборота с замедлением, останавливаются к «удару».
        blades.setRotation(-720f);
        blades.setScaleX(0.3f);
        blades.setScaleY(0.3f);
        blades.animate().rotation(0f).scaleX(1f).scaleY(1f).setDuration(HIT + 80).setInterpolator(new DecelerateInterpolator(2.2f));
        // Экран с Play выпрыгивает на «удар».
        screen.setScaleX(0f);
        screen.setScaleY(0f);
        screen.animate().scaleX(1f).scaleY(1f).setStartDelay(HIT - 40).setDuration(420).setInterpolator(new OvershootInterpolator(2.6f));
        // Блик проходит по плитке.
        shine.setRotation(20f);
        shine.setTranslationX(-size * 0.6f);
        shine.animate().translationX(size * 1.3f).setStartDelay(HIT + 420).setDuration(650).setInterpolator(new AccelerateDecelerateInterpolator());
        // Плитка «вздыхает» на ударе.
        logo.postDelayed(() -> {
            if (closing) return;
            logo.animate().scaleX(1.07f).scaleY(1.07f).setDuration(120).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> logo.animate().scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(new OvershootInterpolator(2f)));
        }, HIT);
        // Буквы по одной: снизу и из крупного масштаба.
        for (int i = 0; i < letters.length; i++) {
            TextView l = letters[i];
            l.setAlpha(0f);
            l.setTranslationY(dp(22));
            l.setScaleX(1.6f);
            l.setScaleY(1.6f);
            l.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(HIT + 230 + i * 55L).setDuration(380)
                    .setInterpolator(new OvershootInterpolator(1.8f));
        }
        // Линия под надписью растёт от центра.
        line.setScaleX(0f);
        line.setAlpha(0f);
        line.animate().scaleX(1f).alpha(1f).setStartDelay(HIT + 700).setDuration(520).setInterpolator(new DecelerateInterpolator(2f));

        fx.start(HOLD + FADE);
        speak();
        root.postDelayed(auto, HOLD);
    }

    private void speak() {
        try {
            MediaPlayer m = new MediaPlayer();
            m.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            android.content.res.AssetFileDescriptor fd = a.getResources().openRawResourceFd(R.raw.argeda_tv);
            m.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            fd.close();
            m.setOnPreparedListener(MediaPlayer::start);
            m.setOnCompletionListener(p -> releaseVoice());
            m.setOnErrorListener((p, w, e) -> {
                releaseVoice();
                return true;
            });
            voice = m;
            m.prepareAsync();
        } catch (Exception ignored) {
            releaseVoice();
        }
    }

    private void releaseVoice() {
        if (voice != null) {
            voice.release();
            voice = null;
        }
    }

    /** Пропуск кнопкой или касанием: звук тоже замолкает. */
    void skip() {
        releaseVoice();
        dismiss();
    }

    /** Убирает заставку плавно, чуть приближая; звук доигрывает до конца. */
    void dismiss() {
        if (root == null || closing) return;
        closing = true;
        root.removeCallbacks(auto);
        root.setClickable(false);
        root.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setStartDelay(0).setDuration(FADE).withEndAction(this::remove);
        done.run();
    }

    /** Сразу убирает заставку и звук (уход с экрана). */
    void cancel() {
        releaseVoice();
        if (root == null) return;
        root.removeCallbacks(auto);
        root.animate().cancel();
        if (!closing) {
            closing = true;
            done.run();
        }
        remove();
    }

    private void remove() {
        if (root == null) return;
        if (fx != null) fx.stop();
        ViewGroup p = (ViewGroup) root.getParent();
        if (p != null) {
            p.removeView(root);
            // Некоторые ТВ-приставки после снятия полноэкранного слоя перерисовывают не весь экран.
            View top = p.getRootView();
            top.invalidate();
            top.requestLayout();
        }
        root = null;
    }

    /** Фон заставки: дышащее свечение, лучи, ореол, кольца и искры от «удара». Всё считается от времени. */
    private static final class Fx extends View {
        private final float logo, dens;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path ray = new Path();
        private final float[] ang, spd, sz;
        private final int[] col;
        private ValueAnimator va;
        private long t;
        View anchor;

        Fx(Context c, int logoPx) {
            super(c);
            logo = logoPx;
            dens = c.getResources().getDisplayMetrics().density;
            Random r = new Random(7);
            int n = 46;
            ang = new float[n];
            spd = new float[n];
            sz = new float[n];
            col = new int[n];
            for (int i = 0; i < n; i++) {
                ang[i] = (float) (r.nextFloat() * Math.PI * 2);
                spd[i] = 0.35f + r.nextFloat() * 0.75f;
                sz[i] = 1.2f + r.nextFloat() * 2.6f;
                col[i] = SPARK[r.nextInt(SPARK.length)];
            }
        }

        void start(long dur) {
            va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(dur);
            va.setInterpolator(new LinearInterpolator());
            va.addUpdateListener(v -> {
                t = v.getCurrentPlayTime();
                invalidate();
            });
            va.start();
        }

        void stop() {
            if (va != null) va.cancel();
        }

        private static float clamp(float v) {
            return v < 0 ? 0 : (v > 1 ? 1 : v);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            // Центр плитки знака (после разметки берём её настоящее место).
            float cx = w / 2f, cy = h / 2f - 41 * dens;
            if (anchor != null && anchor.getWidth() > 0) {
                View parent = (View) anchor.getParent();
                cx = parent.getLeft() + anchor.getLeft() + anchor.getWidth() / 2f;
                cy = parent.getTop() + anchor.getTop() + anchor.getHeight() / 2f;
            }
            float big = Math.max(w, h);

            // 1. Фиолетовое свечение: разгорается, дышит и вспыхивает на ударе.
            float in = clamp(t / 500f);
            float hit = t < HIT ? 0 : (float) Math.exp(-(t - HIT) / 260.0);
            float breathe = 0.85f + 0.15f * (float) Math.sin(t / 380.0);
            int a1 = Math.min(255, (int) (255 * (0.55f * in * breathe + 0.45f * hit)));
            p.setShader(new RadialGradient(cx, cy, big * 0.62f,
                    new int[]{Color.argb(a1, 76, 40, 160), Color.argb(a1 / 3, 40, 20, 90), 0x00000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);

            // 2. Лучи за плиткой: медленно вращаются, появляются к удару.
            float rays = clamp((t - HIT + 100) / 500f);
            if (rays > 0) {
                p.setShader(new RadialGradient(cx, cy, big * 0.5f,
                        new int[]{Color.argb((int) (70 * rays), 200, 170, 255), 0x00000000}, null, Shader.TileMode.CLAMP));
                c.save();
                c.rotate(t / 40f, cx, cy);
                ray.reset();
                for (int i = 0; i < 12; i++) {
                    double a0 = i * Math.PI / 6, da = Math.PI / 30;
                    ray.moveTo(cx, cy);
                    ray.lineTo(cx + (float) Math.cos(a0 - da) * big, cy + (float) Math.sin(a0 - da) * big);
                    ray.lineTo(cx + (float) Math.cos(a0 + da) * big, cy + (float) Math.sin(a0 + da) * big);
                    ray.close();
                }
                c.drawPath(ray, p);
                c.restore();
            }

            // 3. Ореол прямо за плиткой.
            float halo = clamp(0.5f * in + 0.9f * hit);
            p.setShader(new RadialGradient(cx, cy, logo * 1.25f,
                    new int[]{Color.argb((int) (255 * halo), 168, 85, 247), 0x00A855F7}, null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, logo * 1.25f, p);
            p.setShader(null);

            // 4. Три кольца расходятся от удара.
            p.setStyle(Paint.Style.STROKE);
            for (int k = 0; k < 3; k++) {
                float rt = (t - HIT - k * 140) / 900f;
                if (rt <= 0 || rt >= 1) continue;
                float e = 1 - (1 - rt) * (1 - rt);
                p.setStrokeWidth((3.5f - 2.5f * rt) * dens);
                p.setColor(k == 1 ? Color.argb((int) (200 * (1 - rt)), 255, 255, 255)
                        : Color.argb((int) (200 * (1 - rt)), 196, 181, 253));
                c.drawCircle(cx, cy, logo * 0.62f + e * big * 0.42f, p);
            }
            p.setStyle(Paint.Style.FILL);

            // 5. Искры фирменных цветов разлетаются и гаснут.
            float st = (t - HIT) / 1300f;
            if (st > 0 && st < 1) {
                float e = 1 - (float) Math.pow(1 - st, 3);
                int al = (int) (255 * (1 - st));
                for (int i = 0; i < ang.length; i++) {
                    float d = logo * 0.55f + e * spd[i] * big * 0.45f;
                    float x = cx + (float) Math.cos(ang[i]) * d, y = cy + (float) Math.sin(ang[i]) * d + st * st * 40 * dens;
                    p.setColor((col[i] & 0x00FFFFFF) | (al << 24));
                    c.drawCircle(x, y, sz[i] * dens * (1.2f - 0.6f * st), p);
                }
            }
        }
    }
}
