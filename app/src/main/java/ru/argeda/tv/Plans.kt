package ru.argeda.tv

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись по расписанию: будущую передачу из телепрограммы можно записать — в её время приложение
 * само начнёт запись (за минуту до начала, до конца передачи + 2 минуты), даже если оно закрыто.
 */
internal object Plans {
    class Plan(@JvmField val key: String, @JvmField val channel: String, @JvmField val title: String,
               @JvmField val start: Long, @JvmField val stop: Long)

    private const val PREF = "recPlans"
    private const val EARLY = 60_000L
    private const val TAIL = 2 * 60_000L

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("argeda", Context.MODE_PRIVATE)

    fun load(c: Context): MutableList<Plan> {
        val r = ArrayList<Plan>()
        try {
            val a = JSONArray(prefs(c).getString(PREF, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                r.add(Plan(o.getString("k"), o.optString("n"), o.optString("p"), o.getLong("t"), o.getLong("e")))
            }
        } catch (_: Exception) {
        }
        return r
    }

    private fun save(c: Context, l: List<Plan>) {
        val a = JSONArray()
        try {
            for (p in l) a.put(JSONObject().put("k", p.key).put("n", p.channel).put("p", p.title).put("t", p.start).put("e", p.stop))
        } catch (_: Exception) {
        }
        prefs(c).edit().putString(PREF, a.toString()).apply()
    }

    fun has(c: Context, ch: Channel, start: Long): Boolean = load(c).any { it.start == start && it.key == ch.key() }

    /**
     * Будущая передача: что сделать — напомнить или записать (или отменить уже назначенное).
     * changed — обновить отметки в списке.
     */
    fun ask(a: Activity, ch: Channel, p: Epg.Prog, changed: Runnable) {
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
        val rem = Alerts.has(a, ch, p.start)
        val rec = has(a, ch, p.start)
        val items = arrayOf(
            if (rem) "Снять напоминание" else "Напомнить за минуту до начала",
            if (rec) "Отменить запись" else "●  Записать передачу"
        )
        AlertDialog.Builder(a).setTitle(hm.format(Date(p.start)) + "  " + p.title.ifEmpty { "Передача" })
            .setItems(items) { _, w ->
                if (w == 0) {
                    val on = Alerts.toggle(a, ch, p)
                    Ui.toast(a, if (on) "Напомню в " + hm.format(Date(p.start - EARLY)) + ": «" + p.title + "»" else "Напоминание снято")
                } else if (!rec && Pin.locked(Store.get(a), ch)) {
                    // Записать канал 18+ — только после PIN-кода.
                    Pin.unlock(a) { ask(a, ch, p, changed) }
                    return@setItems
                } else {
                    val on = toggle(a, ch, p)
                    Ui.toast(a, if (on) "Запишу «" + p.title + "» в " + hm.format(Date(p.start)) + ". Приставку не выключайте"
                    else "Запись отменена")
                }
                changed.run()
            }.show()
    }

    /** Назначает или отменяет запись; true — теперь назначена. */
    fun toggle(c: Context, ch: Channel, p: Epg.Prog): Boolean {
        val l = load(c)
        val on = !l.removeAll { it.start == p.start && it.key == ch.key() }
        if (on) l.add(Plan(ch.key(), ch.name, p.title, p.start, maxOf(p.stop, p.start + 5 * 60_000L)))
        save(c, l)
        schedule(c)
        return on
    }

    /** Будильник на ближайшую запись (после перезагрузки — заново, см. [BootReceiver]). */
    fun schedule(c: Context) {
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        val l = load(app)
        val keep = l.filter { it.stop > now }
        if (keep.size != l.size) save(app, keep)
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(app, 7302, Intent(app, PlanReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val next = keep.minOfOrNull { it.start - EARLY }
        if (next == null) {
            am.cancel(pi)
            return
        }
        val at = maxOf(now + 1000L, next)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** Пришло время: запускает все записи, которым пора начаться. */
    fun fire(c: Context) {
        val app = c.applicationContext
        val now = System.currentTimeMillis()
        val l = load(app)
        val due = l.filter { it.start - EARLY <= now + 5_000L && it.stop > now }
        save(app, l.filter { it !in due && it.stop > now })
        if (due.isNotEmpty()) {
            val st = Store.get(app)
            if (st.all.isEmpty()) st.loadSync()
            for (p in due) {
                val ch = st.all.firstOrNull { it.key() == p.key }
                if (ch == null) {
                    Ui.toast(app, "Запись «${p.title}» не началась: канала «${p.channel}» нет в плейлисте")
                    continue
                }
                val s = st.current()
                val ua = ch.userAgent.ifEmpty { s?.ua.orEmpty() }.ifEmpty { Net.UA }
                Recorder.start(app, ch, ua, p.stop - now + TAIL)
            }
        }
        schedule(app)
    }
}

class PlanReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Plans.fire(c)
    }
}
