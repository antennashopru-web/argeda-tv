package ru.argeda.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Автозапуск: после включения приставки открывает ARGEDA TV, если это включено в настройках. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a) && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) return;
        if (!c.getSharedPreferences("argeda", Context.MODE_PRIVATE).getBoolean("autostart", false)) return;
        try {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
            // Система не дала открыть окно из фона (Android 10+ без разрешения «Поверх других окон»).
        }
    }
}
