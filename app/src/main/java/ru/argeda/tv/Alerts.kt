package ru.argeda.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Таймер сна и напоминания о передачах. Оба показывают окно поверх того экрана приложения,
 * который сейчас открыт (главный или полноэкранный), поэтому экраны сообщают о себе в [attach].
 * Напоминания работают, пока приложение открыто; пропущенные за последние 5 минут показываются при возврате.
 */
internal object Alerts {
    private val H = Handler(Looper.getMainLooper())
    private var top = WeakReference<Activity>(null)
    private var shown: AlertDialog? = null

    @JvmStatic
    fun attach(a: Activity) {
        top = WeakReference(a)
        scheduleReminders(a)
    }

    @JvmStatic
    fun detach(a: Activity) {
        if (top.get() === a) top = WeakReference(null)
        if (shown?.ownerActivity === a) shown = null
    }

    private fun top(): Activity? = top.get()?.takeUnless { it.isFinishing }

    private fun dismissShown() {
        try {
            shown?.dismiss()
        } catch (_: Exception) {
        }
    }

    private fun show(a: Activity, b: AlertDialog.Builder) {
        dismissShown()
        shown = b.create().also {
            it.setOwnerActivity(a)
            it.show()
        }
    }

    // ---------- Таймер сна ----------

    @JvmField
    val SLEEP = intArrayOf(0, 30, 60, 90, 120)
    private var sleepAt = 0L
    private val sleepWarn = Runnable { warnSleep() }
    private val sleepFire = Runnable { sleepNow() }

    /** «выкл» или «23:40» — когда приложение закроется. */
    @JvmStatic
    fun sleepLabel(): String =
        if (sleepAt == 0L) "выкл" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(sleepAt))

    /** Следующее значение по кругу: выкл → 30 → 60 → 90 → 120 мин → выкл. */
    @JvmStatic
    fun nextSleep(c: Context) {
        var min = 0
        if (sleepAt == 0L) min = SLEEP[1]
        else {
            val left = sleepAt - System.currentTimeMillis()
            for (m in SLEEP) {
                if (m * 60_000L > left + 60_000L) {
                    min = m
                    break
                }
            }
        }
        setSleep(min)
        Ui.toast(c, if (min == 0) "Таймер сна выключен" else "ARGEDA TV выключится через $min мин, в ${sleepLabel()}")
    }

    @JvmStatic
    fun setSleep(min: Int) {
        H.removeCallbacks(sleepWarn)
        H.removeCallbacks(sleepFire)
        if (min <= 0) {
            sleepAt = 0
            return
        }
        val ms = min * 60_000L
        sleepAt = System.currentTimeMillis() + ms
        H.postDelayed(sleepWarn, ms - 60_000L)
        H.postDelayed(sleepFire, ms)
    }

    private fun warnSleep() {
        val a = top() ?: return
        show(
            a, AlertDialog.Builder(a)
                .setTitle("Таймер сна")
                .setMessage("Через минуту ARGEDA TV выключится.")
                .setPositiveButton("Смотреть дальше") { _, _ ->
                    setSleep(0)
                    Ui.toast(a, "Таймер сна выключен")
                }
                .setNegativeButton("Выключить сейчас") { _, _ -> sleepNow() }
        )
    }

    private fun sleepNow() {
        setSleep(0)
        val a = top()
        if (shown != null) {
            dismissShown()
            shown = null
        }
        if (a == null) return
        Playback.get(a).stop()
        a.finishAffinity()
    }

    // ---------- Напоминания ----------

    class Reminder(@JvmField val key: String, @JvmField val channel: String, @JvmField val title: String, @JvmField val start: Long)

    private const val EARLY = 60_000L
    private const val LATE = 5 * 60_000L
    private val remindRun = Runnable { fireReminders() }

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("argeda", Context.MODE_PRIVATE)

    private fun load(c: Context): MutableList<Reminder> {
        val r = ArrayList<Reminder>()
        try {
            val a = JSONArray(prefs(c).getString("reminders", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                r.add(Reminder(o.getString("k"), o.optString("n"), o.optString("p"), o.getLong("t")))
            }
        } catch (_: Exception) {
        }
        return r
    }

    private fun save(c: Context, l: List<Reminder>) {
        val a = JSONArray()
        try {
            for (r in l) a.put(JSONObject().put("k", r.key).put("n", r.channel).put("p", r.title).put("t", r.start))
        } catch (_: Exception) {
        }
        prefs(c).edit().putString("reminders", a.toString()).apply()
    }

    @JvmStatic
    fun has(c: Context, ch: Channel, start: Long): Boolean = load(c).any { it.start == start && it.key == ch.key() }

    /** Ставит или снимает напоминание; true — теперь стоит. */
    @JvmStatic
    fun toggle(c: Context, ch: Channel, p: Epg.Prog): Boolean {
        val l = load(c)
        val on = !l.removeAll { it.start == p.start && it.key == ch.key() }
        if (on) l.add(Reminder(ch.key(), ch.name, p.title, p.start))
        save(c, l)
        scheduleReminders(c)
        return on
    }

    private fun scheduleReminders(c: Context) {
        H.removeCallbacks(remindRun)
        val next = load(c).minOfOrNull { it.start - EARLY } ?: return
        H.postDelayed(remindRun, maxOf(0L, next - System.currentTimeMillis()))
    }

    private fun fireReminders() {
        val a = top() ?: return // приложение свёрнуто: покажем, когда вернутся (attach)
        val now = System.currentTimeMillis()
        val keep = ArrayList<Reminder>()
        var due: Reminder? = null
        for (r in load(a)) {
            if (r.start - EARLY > now) keep.add(r)
            else if (r.start + LATE > now && (due == null || r.start > due.start)) due = r
        }
        save(a, keep)
        scheduleReminders(a)
        val r = due ?: return
        val whenText = if (r.start > now) "В " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(r.start)) + " начинается"
        else "Уже идёт"
        show(
            a, AlertDialog.Builder(a)
                .setTitle("Напоминание")
                .setMessage(whenText + " «" + r.title.ifEmpty { "передача" } + "» на канале «" + r.channel + "».")
                .setPositiveButton("Смотреть") { _, _ -> watch(a, r.key) }
                .setNegativeButton("Закрыть", null)
        )
    }

    /** Включает канал на весь экран (или переключает уже открытый полноэкранный режим). */
    private fun watch(a: Activity, key: String) {
        if (a is PlayerActivity) {
            a.openKey(key)
            return
        }
        val st = Store.get(a)
        val vis = st.visible()
        val i = vis.indexOfFirst { it.key() == key }
        if (i < 0) {
            Ui.toast(a, "Этого канала больше нет в плейлисте")
            return
        }
        val c = vis[i]
        st.playList = ArrayList(vis)
        st.playIndex = i
        st.playTitle = "Все каналы"
        st.played(c)
        Playback.get(a).play(c)
        a.startActivity(Intent(a, PlayerActivity::class.java).putExtra("key", key))
    }
}
