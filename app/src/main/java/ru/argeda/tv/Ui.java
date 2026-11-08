package ru.argeda.tv;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.widget.Toast;

final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static void toast(Context c, String s) {
        Toast.makeText(c, s, s.length() > 60 ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
    }

    /** Приставка или телевизор: пульт вместо сенсора. */
    static boolean isTv(Context c) {
        UiModeManager m = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        PackageManager pm = c.getPackageManager();
        return (m != null && m.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION)
                || pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || !pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
    }

    /**
     * Безопасная зона на телевизоре: многие ТВ обрезают края картинки (overscan), поэтому
     * интерфейс отступает от краёв экрана. Фон корневого вида остаётся на весь экран.
     * Вместе с собственными отступами разметки получается около 5% по бокам, как советует Google.
     */
    static void tvSafeArea(android.app.Activity a) {
        if (!isTv(a)) return;
        android.view.ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return;
        android.view.View root = content.getChildAt(0);
        root.setFitsSystemWindows(false); // на ТВ нет системных панелей, а их отступы затёрли бы наши
        int h = dp(a, 32), v = dp(a, 20);
        root.setPadding(root.getPaddingLeft() + h, root.getPaddingTop() + v,
                root.getPaddingRight() + h, root.getPaddingBottom() + v);
    }

    /** Не даём экрану погаснуть, пока идёт просмотр. */
    static void keepScreenOn(android.app.Activity a, boolean on) {
        if (on) a.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else a.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
