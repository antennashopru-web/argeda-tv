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
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/**
 * Заставка при запуске под звук res/raw/argeda_tv.mp3 (мощный удар + басовый голос «ARGEDA TV»).
 *
 * <p>Сцены (мс): 0–{@link #HIT} — «зарядка»: искры по спирали стягиваются в светящееся ядро, из него
 * появляется плитка знака с раскрученными лопастями. {@link #HIT} — удар: вспышка, встряска, экран с Play,
 * ударные кольца, взрыв искр, лучи. Затем надпись по буквам и голос, блик, вокруг знака кружат огоньки.
 * {@link #HOLD} — финал: пролёт сквозь знак (он стремительно растёт и растворяется).
 *
 * <p>Лежит поверх главного экрана: плейлист и первый канал грузятся одновременно с ней.
 * Любая кнопка или касание её пропускают.
 */
final class Splash {
    interface Done {
        void run();
    }

    /** Момент удара в звуке, мс (совпадает с HIT в tools/sound/mix.py). */
    static final long HIT = 1300;
    private static final long HOLD = 3900, WARP = 550, SKIP = 250;
    private static final int[] SPARK = {0xFFE30613, 0xFF0047BB, 0xFFF28C00, 0xFFA855F7, 0xFFC4B5FD, Color.WHITE};

    private final Activity a;
    private final Done done;
    private FrameLayout root;
    private LinearLayout col;
    private Fx fx;
    private MediaPlayer voice;
    private boolean closing;
    private final Runnable auto = () -> close(true);

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
        root.setBackgroundColor(0xFF07050F);
        root.setClickable(true);
        root.setOnClickListener(v -> skip());
        root.setFocusable(false); // касание пропускает заставку, но фокус пульта остаётся на списке

        int size = dp(150);
        fx = new Fx(a, size);
        root.addView(fx, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        col = new LinearLayout(a);
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

        // Плитка рождается из ядра в конце «зарядки».
        long born = HIT - 420;
        logo.setScaleX(0f);
        logo.setScaleY(0f);
        logo.setRotation(-45f);
        logo.animate().scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(born).setDuration(520)
                .setInterpolator(new OvershootInterpolator(1.5f));
        // Лопасти: три оборота с замедлением, останавливаются на ударе.
        blades.setRotation(-1080f);
        blades.animate().rotation(0f).setStartDelay(born).setDuration(420 + 160).setInterpolator(new DecelerateInterpolator(1.6f));
        // Экран с Play выпрыгивает на удар.
        screen.setScaleX(0f);
        screen.setScaleY(0f);
        screen.animate().scaleX(1f).scaleY(1f).setStartDelay(HIT - 30).setDuration(440).setInterpolator(new OvershootInterpolator(2.6f));
        // Удар: плитка «вздыхает», вся сцена вздрагивает.
        root.postDelayed(() -> {
            if (closing) return;
            logo.animate().scaleX(1.12f).scaleY(1.12f).setStartDelay(0).setDuration(110).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> logo.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(new OvershootInterpolator(2f)));
            shake(col, dp(7));
        }, HIT);
        // Буквы по одной вместе с голосом.
        for (int i = 0; i < letters.length; i++) {
            TextView l = letters[i];
            l.setAlpha(0f);
            l.setTranslationY(dp(24));
            l.setScaleX(1.8f);
            l.setScaleY(1.8f);
            l.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(HIT + 260 + i * 60L).setDuration(400)
                    .setInterpolator(new OvershootInterpolator(1.8f));
        }
        // Линия под надписью растёт от центра.
        line.setScaleX(0f);
        line.setAlpha(0f);
        line.animate().scaleX(1f).alpha(1f).setStartDelay(HIT + 900).setDuration(600).setInterpolator(new DecelerateInterpolator(2f));
        // Два блика по плитке.
        shine.setRotation(20f);
        shine.setTranslationX(-size * 0.6f);
        shine.animate().translationX(size * 1.3f).setStartDelay(HIT + 650).setDuration(650).setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    shine.setTranslationX(-size * 0.6f);
                    shine.animate().translationX(size * 1.3f).setStartDelay(900).setDuration(650)
                            .setInterpolator(new AccelerateDecelerateInterpolator()).withEndAction(null);
                });
        // Пока звучит голос, знак медленно «парит».
        col.animate().translationY(-dp(6)).setStartDelay(HIT + 900).setDuration(HOLD - HIT - 900)
                .setInterpolator(new AccelerateDecelerateInterpolator());

        fx.start(HOLD + WARP);
        speak();
        root.postDelayed(auto, HOLD);
    }

    /** Короткая затухающая встряска. */
    private static void shake(View v, float amp) {
        ValueAnimator s = ValueAnimator.ofFloat(0f, 1f);
        s.setDuration(320);
        s.addUpdateListener(an -> {
            float f = an.getAnimatedFraction(), k = (1 - f) * amp;
            v.setTranslationX((float) Math.sin(f * 40) * k);
            v.setTranslationY((float) Math.cos(f * 33) * k * 0.6f);
        });
        s.start();
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

    /** Пропуск кнопкой или касанием: звук замолкает, заставка быстро гаснет. */
    void skip() {
        releaseVoice();
        close(false);
    }

    void dismiss() {
        close(true);
    }

    /** warp: финальный пролёт сквозь знак; иначе — просто быстрое затухание. */
    private void close(boolean warp) {
        if (root == null || closing) return;
        closing = true;
        root.removeCallbacks(auto);
        root.setClickable(false);
        if (warp) {
            fx.warp = true;
            col.animate().cancel();
            col.animate().scaleX(5f).scaleY(5f).alpha(0f).setStartDelay(0).setDuration(WARP)
                    .setInterpolator(new AccelerateInterpolator(2f));
            root.animate().alpha(0f).setStartDelay(WARP / 2).setDuration(WARP / 2).withEndAction(this::remove);
        } else {
            root.animate().alpha(0f).setStartDelay(0).setDuration(SKIP).withEndAction(this::remove);
        }
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

    /** Эффекты на фоне; всё считается от времени t (мс), поэтому кадры не накапливают ошибок. */
    private static final class Fx extends View {
        private static final int N_IN = 70, N_OUT = 56, N_ORBIT = 18, N_STAR = 90;
        private final float logo, dens;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path ray = new Path();
        private final float[] inAng = new float[N_IN], inDist = new float[N_IN], inDelay = new float[N_IN], inSz = new float[N_IN];
        private final int[] inCol = new int[N_IN];
        private final float[] outAng = new float[N_OUT], outSpd = new float[N_OUT], outSz = new float[N_OUT];
        private final int[] outCol = new int[N_OUT];
        private final float[] orbR = new float[N_ORBIT], orbPh = new float[N_ORBIT], orbSpd = new float[N_ORBIT];
        private final float[] starX = new float[N_STAR], starY = new float[N_STAR], starPh = new float[N_STAR];
        private ValueAnimator va;
        private long t, warpAt = -1;
        boolean warp;
        View anchor;

        Fx(Context c, int logoPx) {
            super(c);
            logo = logoPx;
            dens = c.getResources().getDisplayMetrics().density;
            Random r = new Random(7);
            for (int i = 0; i < N_IN; i++) {
                inAng[i] = (float) (r.nextFloat() * Math.PI * 2);
                inDist[i] = 0.55f + r.nextFloat() * 0.6f;
                inDelay[i] = r.nextFloat() * 0.35f;
                inSz[i] = 1.2f + r.nextFloat() * 2.2f;
                inCol[i] = SPARK[r.nextInt(SPARK.length)];
            }
            for (int i = 0; i < N_OUT; i++) {
                outAng[i] = (float) (r.nextFloat() * Math.PI * 2);
                outSpd[i] = 0.35f + r.nextFloat() * 0.8f;
                outSz[i] = 1.4f + r.nextFloat() * 2.8f;
                outCol[i] = SPARK[r.nextInt(SPARK.length)];
            }
            for (int i = 0; i < N_ORBIT; i++) {
                orbR[i] = 0.75f + r.nextFloat() * 0.55f;
                orbPh[i] = (float) (r.nextFloat() * Math.PI * 2);
                orbSpd[i] = (0.6f + r.nextFloat() * 0.8f) * (r.nextBoolean() ? 1 : -1);
            }
            for (int i = 0; i < N_STAR; i++) {
                starX[i] = r.nextFloat();
                starY[i] = r.nextFloat();
                starPh[i] = (float) (r.nextFloat() * Math.PI * 2);
            }
        }

        void start(long dur) {
            va = ValueAnimator.ofFloat(0f, 1f);
            va.setDuration(dur);
            va.setInterpolator(new LinearInterpolator());
            va.addUpdateListener(v -> {
                t = v.getCurrentPlayTime();
                if (warp && warpAt < 0) warpAt = t;
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

        private void grad(float cx, float cy, float rad, int c0) {
            p.setShader(new RadialGradient(cx, cy, Math.max(rad, 1f), new int[]{c0, c0 & 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float cx = w / 2f, cy = h / 2f - 41 * dens;
            if (anchor != null && anchor.getWidth() > 0) {
                View parent = (View) anchor.getParent();
                cx = parent.getLeft() + anchor.getLeft() + anchor.getWidth() / 2f;
                cy = parent.getTop() + anchor.getTop() + anchor.getHeight() / 2f;
            }
            float big = Math.max(w, h);
            float hit = t < HIT ? 0 : (float) Math.exp(-(t - HIT) / 280.0);
            float after = clamp((t - HIT) / 600f);
            float charge = clamp(t / (float) HIT);

            // 1. Звёздная пыль: мерцает, во время зарядки её «засасывает» к центру.
            for (int i = 0; i < N_STAR; i++) {
                float pull = t < HIT ? charge * charge * 0.25f : 0;
                float x = starX[i] * w, y = starY[i] * h;
                x += (cx - x) * pull;
                y += (cy - y) * pull;
                float tw = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(t / 220.0 + starPh[i]));
                p.setShader(null);
                p.setColor(Color.argb((int) (120 * tw * clamp(t / 400f)), 200, 190, 255));
                c.drawCircle(x, y, 1.1f * dens, p);
            }

            // 2. Фиолетовое свечение: растёт с зарядкой, вспыхивает на ударе, потом дышит.
            float breathe = 0.85f + 0.15f * (float) Math.sin(t / 380.0);
            float glow = t < HIT ? 0.25f * charge : 0.55f * breathe;
            int a1 = Math.min(255, (int) (255 * (glow + 0.5f * hit)));
            p.setShader(new RadialGradient(cx, cy, big * 0.65f,
                    new int[]{Color.argb(a1, 80, 42, 170), Color.argb(a1 / 3, 40, 20, 90), 0x00000000},
                    new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);

            // 3. Лучи за плиткой после удара.
            if (after > 0) {
                p.setShader(new RadialGradient(cx, cy, big * 0.55f,
                        new int[]{Color.argb((int) (80 * after), 200, 170, 255), 0x00000000}, null, Shader.TileMode.CLAMP));
                c.save();
                c.rotate(t / 36f, cx, cy);
                ray.reset();
                for (int i = 0; i < 14; i++) {
                    double a0 = i * Math.PI / 7, da = Math.PI / 34;
                    ray.moveTo(cx, cy);
                    ray.lineTo(cx + (float) Math.cos(a0 - da) * big, cy + (float) Math.sin(a0 - da) * big);
                    ray.lineTo(cx + (float) Math.cos(a0 + da) * big, cy + (float) Math.sin(a0 + da) * big);
                    ray.close();
                }
                c.drawPath(ray, p);
                c.restore();
            }

            // 4. Зарядка: искры по спирали летят в центр, ядро разгорается.
            if (t < HIT + 60) {
                for (int i = 0; i < N_IN; i++) {
                    float f = clamp((charge - inDelay[i]) / (1 - inDelay[i]));
                    if (f <= 0 || f >= 1) continue;
                    float e = f * f * f;
                    float d = big * inDist[i] * (1 - e);
                    double an = inAng[i] + e * 3.2;
                    float x = cx + (float) Math.cos(an) * d, y = cy + (float) Math.sin(an) * d;
                    float px = cx + (float) Math.cos(an - 0.12) * d * 1.08f, py = cy + (float) Math.sin(an - 0.12) * d * 1.08f;
                    p.setShader(null);
                    int al = (int) (255 * Math.min(1f, f * 3));
                    p.setColor((inCol[i] & 0x00FFFFFF) | (al << 24));
                    p.setStrokeWidth(inSz[i] * dens);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    c.drawLine(px, py, x, y, p);
                }
                float core = charge * charge;
                grad(cx, cy, logo * (0.15f + 0.9f * core), Color.argb((int) (255 * core), 230, 210, 255));
                c.drawCircle(cx, cy, logo * (0.15f + 0.9f * core), p);
            }

            // 5. Ореол прямо за плиткой.
            float halo = clamp(t < HIT ? 0.4f * charge : 0.45f * breathe + 0.9f * hit);
            grad(cx, cy, logo * 1.3f, Color.argb((int) (255 * halo), 168, 85, 247));
            c.drawCircle(cx, cy, logo * 1.3f, p);
            p.setShader(null);

            // 6. Ударные кольца.
            p.setStyle(Paint.Style.STROKE);
            for (int k = 0; k < 4; k++) {
                float rt = (t - HIT - k * 130) / 1000f;
                if (rt <= 0 || rt >= 1) continue;
                float e = 1 - (1 - rt) * (1 - rt);
                p.setStrokeWidth((4.5f - 3.5f * rt) * dens);
                p.setColor(k % 2 == 0 ? Color.argb((int) (220 * (1 - rt)), 255, 255, 255)
                        : Color.argb((int) (200 * (1 - rt)), 196, 181, 253));
                c.drawCircle(cx, cy, logo * 0.62f + e * big * 0.5f, p);
            }
            p.setStyle(Paint.Style.FILL);

            // 7. Взрыв искр фирменных цветов.
            float st = (t - HIT) / 1500f;
            if (st > 0 && st < 1) {
                float e = 1 - (float) Math.pow(1 - st, 3);
                int al = (int) (255 * (1 - st));
                for (int i = 0; i < N_OUT; i++) {
                    float d = logo * 0.55f + e * outSpd[i] * big * 0.5f;
                    float x = cx + (float) Math.cos(outAng[i]) * d, y = cy + (float) Math.sin(outAng[i]) * d + st * st * 50 * dens;
                    p.setColor((outCol[i] & 0x00FFFFFF) | (al << 24));
                    c.drawCircle(x, y, outSz[i] * dens * (1.3f - 0.7f * st), p);
                }
            }

            // 8. Огоньки кружат вокруг знака, пока звучит голос.
            float orb = clamp((t - HIT - 400) / 500f);
            if (orb > 0) {
                for (int i = 0; i < N_ORBIT; i++) {
                    double an = orbPh[i] + orbSpd[i] * t / 600.0;
                    float rr = logo * orbR[i];
                    float x = cx + (float) Math.cos(an) * rr, y = cy + (float) Math.sin(an) * rr * 0.55f;
                    float tw = 0.5f + 0.5f * (float) Math.sin(t / 150.0 + i);
                    grad(x, y, 6 * dens, Color.argb((int) (230 * orb * tw), 220, 200, 255));
                    c.drawCircle(x, y, 6 * dens, p);
                }
                p.setShader(null);
            }

            // 9. Вспышка на ударе и белый «пролёт» в финале.
            int flash = (int) (190 * hit * hit);
            if (warpAt >= 0) flash = Math.max(flash, (int) (230 * clamp((t - warpAt) / (float) WARP)));
            if (flash > 0) {
                p.setShader(null);
                p.setColor(Color.argb(Math.min(255, flash), 245, 240, 255));
                c.drawRect(0, 0, w, h, p);
            }
        }
    }
}
