package ru.argeda.tv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Заставка при запуске: лопасти знака раскручиваются, из центра появляется экран с кнопкой Play,
 * выезжает надпись ARGEDA TV и звучит голос. Лежит поверх главного экрана, поэтому плейлист
 * и первый канал грузятся одновременно с ней. Длится около 1,8 с, любая кнопка или касание её убирает.
 */
final class Splash {
    interface Done {
        void run();
    }

    private static final long HOLD = 1700, FADE = 250;

    private final Activity a;
    private final Done done;
    private FrameLayout root;
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
        GradientDrawable bg = new GradientDrawable();
        bg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        bg.setColors(new int[]{0xFF2A1F52, 0xFF0D0A1A});
        bg.setGradientRadius(dp(420));
        root.setBackground(bg);
        root.setClickable(true);
        root.setOnClickListener(v -> skip());

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);

        int size = dp(150);
        FrameLayout logo = new FrameLayout(a);
        View tile = new View(a);
        GradientDrawable white = new GradientDrawable();
        white.setColor(Color.WHITE);
        white.setCornerRadius(dp(36));
        tile.setBackground(white);
        tile.setElevation(dp(10));
        ImageView blades = new ImageView(a);
        blades.setImageResource(R.drawable.splash_blades);
        blades.setElevation(dp(10));
        ImageView screen = new ImageView(a);
        screen.setImageResource(R.drawable.splash_screen);
        screen.setElevation(dp(10));
        logo.addView(tile, new FrameLayout.LayoutParams(size, size));
        logo.addView(blades, new FrameLayout.LayoutParams(size, size));
        logo.addView(screen, new FrameLayout.LayoutParams(size, size));
        col.addView(logo, new LinearLayout.LayoutParams(size, size));

        TextView title = new TextView(a);
        SpannableString t = new SpannableString("ARGEDA TV");
        t.setSpan(new ForegroundColorSpan(0xFFA855F7), 7, 9, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        title.setText(t);
        title.setTextColor(Color.WHITE);
        title.setTextSize(34);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.08f);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(22);
        col.addView(title, tl);

        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        a.addContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Анимация: плитка «выпрыгивает», лопасти раскручиваются, экран появляется из центра, затем надпись.
        tile.setScaleX(0.4f);
        tile.setScaleY(0.4f);
        tile.setAlpha(0f);
        tile.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420).setInterpolator(new OvershootInterpolator(1.6f));
        blades.setRotation(-270f);
        blades.setScaleX(0.2f);
        blades.setScaleY(0.2f);
        blades.setAlpha(0f);
        blades.animate().rotation(0f).scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(60).setDuration(800)
                .setInterpolator(new DecelerateInterpolator(2f));
        screen.setScaleX(0f);
        screen.setScaleY(0f);
        screen.animate().scaleX(1f).scaleY(1f).setStartDelay(520).setDuration(380).setInterpolator(new OvershootInterpolator(2.2f));
        title.setAlpha(0f);
        title.setTranslationY(dp(18));
        title.animate().alpha(1f).translationY(0f).setStartDelay(650).setDuration(420).setInterpolator(new DecelerateInterpolator());

        root.postDelayed(this::speak, 350);
        root.postDelayed(auto, HOLD);
    }

    private void speak() {
        if (closing) return;
        try {
            MediaPlayer m = new MediaPlayer();
            m.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
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

    /** Пропуск кнопкой или касанием: голос тоже замолкает. */
    void skip() {
        releaseVoice();
        dismiss();
    }

    /** Убирает заставку плавно; голос договаривает до конца. */
    void dismiss() {
        if (root == null || closing) return;
        closing = true;
        root.removeCallbacks(auto);
        root.setClickable(false);
        root.animate().alpha(0f).setStartDelay(0).setDuration(FADE).withEndAction(this::remove);
        done.run();
    }

    /** Сразу убирает заставку и голос (уход с экрана). */
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
        ViewGroup p = (ViewGroup) root.getParent();
        if (p != null) p.removeView(root);
        root = null;
    }
}
