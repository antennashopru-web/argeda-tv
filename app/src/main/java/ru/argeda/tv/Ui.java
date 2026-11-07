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
}
