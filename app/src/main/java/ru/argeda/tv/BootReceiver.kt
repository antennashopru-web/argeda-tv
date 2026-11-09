package ru.argeda.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Автозапуск: после включения приставки открывает ARGEDA TV, если это включено в настройках. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val a = i.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != "android.intent.action.QUICKBOOT_POWERON") return
        if (!c.getSharedPreferences("argeda", Context.MODE_PRIVATE).getBoolean("autostart", false)) return
        try {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            // Система не дала открыть окно из фона (Android 10+ без разрешения «Поверх других окон»).
        }
    }
}
