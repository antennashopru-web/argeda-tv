package ru.argeda.tv;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

/** Светлая / тёмная тема. Цвета лежат в values (светлая) и values-night (тёмная). */
final class Themes {
    static final int DARK = 0, LIGHT = 1, SYSTEM = 2;
    static final String[] NAMES = {"Тёмная", "Светлая", "Как в системе"};
    private static final String PREF = "theme";

    private Themes() {}

    static int get(Context c) {
        return c.getSharedPreferences("argeda", Context.MODE_PRIVATE).getInt(PREF, DARK);
    }

    static void set(Activity a, int mode) {
        a.getSharedPreferences("argeda", Context.MODE_PRIVATE).edit().putInt(PREF, mode).apply();
        a.recreate();
    }

    /** Вызывать из attachBaseContext после super: подменяет ночной режим для ресурсов экрана. */
    static void apply(Activity a, Context base, boolean forceDark) {
        int mode = forceDark ? DARK : get(base);
        if (mode == SYSTEM) return;
        int ui = base.getResources().getConfiguration().uiMode;
        Configuration o = new Configuration();
        o.uiMode = (ui & ~Configuration.UI_MODE_NIGHT_MASK)
                | (mode == DARK ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        a.applyOverrideConfiguration(o);
    }
}
