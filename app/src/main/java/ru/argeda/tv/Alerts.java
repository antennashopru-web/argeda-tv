package ru.argeda.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Таймер сна и напоминания о передачах. Оба показывают окно поверх того экрана приложения,
 * который сейчас открыт (главный или полноэкранный), поэтому экраны сообщают о себе в {@link #attach}.
 * Напоминания работают, пока приложение открыто; пропущенные за последние 5 минут показываются при возврате.
 */
final class Alerts {
    private Alerts() {}

    private static final Handler H = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> top = new WeakReference<>(null);
    private static AlertDialog shown;

    static void attach(Activity a) {
        top = new WeakReference<>(a);
        scheduleReminders(a);
    }

    static void detach(Activity a) {
        if (top.get() == a) top = new WeakReference<>(null);
        if (shown != null && shown.getOwnerActivity() == a) shown = null;
    }

    private static Activity top() {
        Activity a = top.get();
        return a == null || a.isFinishing() ? null : a;
    }

    private static void show(Activity a, AlertDialog.Builder b) {
        if (shown != null) {
            try {
                shown.dismiss();
            } catch (Exception ignored) {
            }
        }
        shown = b.create();
        shown.setOwnerActivity(a);
        shown.show();
    }

    // ---------- Таймер сна ----------

    static final int[] SLEEP = {0, 30, 60, 90, 120};
    private static long sleepAt;
    private static final Runnable sleepWarn = Alerts::warnSleep, sleepFire = Alerts::sleepNow;

    /** «выкл» или «23:40» — когда приложение закроется. */
    static String sleepLabel() {
        return sleepAt == 0 ? "выкл" : new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(sleepAt));
    }

    /** Следующее значение по кругу: выкл → 30 → 60 → 90 → 120 мин → выкл. */
    static void nextSleep(Context c) {
        int min = 0;
        if (sleepAt == 0) min = SLEEP[1];
        else {
            long left = sleepAt - System.currentTimeMillis();
            for (int m : SLEEP) {
                if (m * 60_000L > left + 60_000L) {
                    min = m;
                    break;
                }
            }
        }
        setSleep(min);
        Ui.toast(c, min == 0 ? "Таймер сна выключен" : "ARGEDA TV выключится через " + min + " мин, в " + sleepLabel());
    }

    static void setSleep(int min) {
        H.removeCallbacks(sleepWarn);
        H.removeCallbacks(sleepFire);
        if (min <= 0) {
            sleepAt = 0;
            return;
        }
        long ms = min * 60_000L;
        sleepAt = System.currentTimeMillis() + ms;
        H.postDelayed(sleepWarn, ms - 60_000L);
        H.postDelayed(sleepFire, ms);
    }

    private static void warnSleep() {
        Activity a = top();
        if (a == null) return;
        show(a, new AlertDialog.Builder(a)
                .setTitle("Таймер сна")
                .setMessage("Через минуту ARGEDA TV выключится.")
                .setPositiveButton("Смотреть дальше", (d, w) -> {
                    setSleep(0);
                    Ui.toast(a, "Таймер сна выключен");
                })
                .setNegativeButton("Выключить сейчас", (d, w) -> sleepNow()));
    }

    private static void sleepNow() {
        setSleep(0);
        Activity a = top();
        if (shown != null) {
            try {
                shown.dismiss();
            } catch (Exception ignored) {
            }
            shown = null;
        }
        if (a == null) return;
        Playback.get(a).stop();
        a.finishAffinity();
    }

    // ---------- Напоминания ----------

    static final class Reminder {
        final String key, channel, title;
        final long start;

        Reminder(String key, String channel, String title, long start) {
            this.key = key;
            this.channel = channel;
            this.title = title;
            this.start = start;
        }
    }

    private static final long EARLY = 60_000L, LATE = 5 * 60_000L;
    private static final Runnable remindRun = Alerts::fireReminders;

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("argeda", Context.MODE_PRIVATE);
    }

    private static List<Reminder> load(Context c) {
        List<Reminder> r = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("reminders", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                r.add(new Reminder(o.getString("k"), o.optString("n"), o.optString("p"), o.getLong("t")));
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    private static void save(Context c, List<Reminder> l) {
        JSONArray a = new JSONArray();
        try {
            for (Reminder r : l) a.put(new JSONObject().put("k", r.key).put("n", r.channel).put("p", r.title).put("t", r.start));
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("reminders", a.toString()).apply();
    }

    static boolean has(Context c, Channel ch, long start) {
        for (Reminder r : load(c)) if (r.start == start && r.key.equals(ch.key())) return true;
        return false;
    }

    /** Ставит или снимает напоминание; true — теперь стоит. */
    static boolean toggle(Context c, Channel ch, Epg.Prog p) {
        List<Reminder> l = load(c);
        boolean on = true;
        for (int i = l.size() - 1; i >= 0; i--) {
            if (l.get(i).start == p.start && l.get(i).key.equals(ch.key())) {
                l.remove(i);
                on = false;
            }
        }
        if (on) l.add(new Reminder(ch.key(), ch.name, p.title, p.start));
        save(c, l);
        scheduleReminders(c);
        return on;
    }

    private static void scheduleReminders(Context c) {
        H.removeCallbacks(remindRun);
        long next = Long.MAX_VALUE;
        for (Reminder r : load(c)) next = Math.min(next, r.start - EARLY);
        if (next == Long.MAX_VALUE) return;
        H.postDelayed(remindRun, Math.max(0, next - System.currentTimeMillis()));
    }

    private static void fireReminders() {
        Activity a = top();
        if (a == null) return; // приложение свёрнуто: покажем, когда вернутся (attach)
        long now = System.currentTimeMillis();
        List<Reminder> l = load(a), keep = new ArrayList<>();
        Reminder due = null;
        for (Reminder r : l) {
            if (r.start - EARLY > now) keep.add(r);
            else if (r.start + LATE > now && (due == null || r.start > due.start)) due = r;
        }
        save(a, keep);
        scheduleReminders(a);
        if (due == null) return;
        final Reminder r = due;
        String when = r.start > now ? "В " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(r.start)) + " начинается"
                : "Уже идёт";
        show(a, new AlertDialog.Builder(a)
                .setTitle("Напоминание")
                .setMessage(when + " «" + (r.title.isEmpty() ? "передача" : r.title) + "» на канале «" + r.channel + "».")
                .setPositiveButton("Смотреть", (d, w) -> watch(a, r.key))
                .setNegativeButton("Закрыть", null));
    }

    /** Включает канал на весь экран (или переключает уже открытый полноэкранный режим). */
    private static void watch(Activity a, String key) {
        if (a instanceof PlayerActivity) {
            ((PlayerActivity) a).openKey(key);
            return;
        }
        Store st = Store.get(a);
        List<Channel> vis = st.visible();
        for (int i = 0; i < vis.size(); i++) {
            Channel c = vis.get(i);
            if (!c.key().equals(key)) continue;
            st.playList = new ArrayList<>(vis);
            st.playIndex = i;
            st.playTitle = "Все каналы";
            st.played(c);
            Playback.get(a).play(c);
            a.startActivity(new Intent(a, PlayerActivity.class).putExtra("key", key));
            return;
        }
        Ui.toast(a, "Этого канала больше нет в плейлисте");
    }
}
