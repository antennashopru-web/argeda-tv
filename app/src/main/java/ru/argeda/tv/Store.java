package ru.argeda.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;

/** Плейлисты, каналы, избранное и настройки. Всё хранится только на устройстве. */
final class Store {
    interface Listener {
        void changed();
    }

    private static final long PLAYLIST_TTL = 6 * 3600_000L, EPG_TTL = 12 * 3600_000L;
    private static Store inst;

    static synchronized Store get(Context c) {
        if (inst == null) inst = new Store(c.getApplicationContext());
        return inst;
    }

    final Context app;
    private final SharedPreferences p;
    final List<Source> sources = new ArrayList<>();
    private String curId;

    List<Channel> all = new ArrayList<>();
    private List<Channel> visibleCache;
    String loadedId, loadingId;
    private String headerEpg = "";
    boolean busy;
    /** Текст ошибки последней загрузки (пусто — всё хорошо) и её порядковый номер. */
    String status = "";
    int statusSeq;
    private int gen;

    private final Set<String> fav = new HashSet<>();
    final List<String> recent = new ArrayList<>();
    String lastKey;
    /** Каналы 18+ открываются PIN-кодом до выхода из приложения. */
    boolean showAdult;

    List<Channel> playList;
    int playIndex;
    String playTitle = "Все каналы";

    Listener listener;

    private Map<String, List<Epg.Prog>> epg;
    private String epgId;
    private boolean epgBusy;
    String epgStatus = "";

    private final ExecutorService bg = Executors.newSingleThreadExecutor(), epgBg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private Store(Context c) {
        app = c;
        p = c.getSharedPreferences("argeda", Context.MODE_PRIVATE);
        try {
            JSONArray a = new JSONArray(p.getString("sources", "[]"));
            for (int i = 0; i < a.length(); i++) sources.add(Source.from(a.getJSONObject(i)));
        } catch (JSONException ignored) {
        }
        curId = p.getString("cur", "");
        fav.addAll(p.getStringSet("fav", new HashSet<>()));
        try {
            JSONArray a = new JSONArray(p.getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) recent.add(a.getString(i));
        } catch (JSONException ignored) {
        }
        lastKey = p.getString("last", "");
    }

    // ---------- Источники ----------

    Source current() {
        for (Source s : sources) if (s.id.equals(curId)) return s;
        return sources.isEmpty() ? null : sources.get(0);
    }

    Source byId(String id) {
        if (id != null) for (Source s : sources) if (s.id.equals(id)) return s;
        return null;
    }

    void select(Source s) {
        curId = s.id;
        p.edit().putString("cur", curId).apply();
    }

    void putSource(Source s) {
        Source old = byId(s.id);
        if (old == null) sources.add(s);
        else sources.set(sources.indexOf(old), s);
        curId = s.id;
        saveSources();
        resetLoaded();
        if (s.id.equals(epgId)) {
            epg = null;
            epgId = null;
        }
    }

    void removeSource(Source s) {
        sources.remove(s);
        delete(cacheFile(s));
        delete(epgFile(s));
        if (s.id.equals(curId)) curId = sources.isEmpty() ? "" : sources.get(0).id;
        saveSources();
        resetLoaded();
    }

    private void resetLoaded() {
        gen++;
        busy = false;
        loadedId = null;
        loadingId = null;
        all = new ArrayList<>();
        visibleCache = null;
        status = "";
    }

    private void saveSources() {
        JSONArray a = new JSONArray();
        try {
            for (Source s : sources) a.put(s.toJson());
        } catch (JSONException ignored) {
        }
        p.edit().putString("sources", a.toString()).putString("cur", curId).apply();
    }

    File cacheFile(Source s) {
        return new File(app.getFilesDir(), "pl_" + s.id + ".m3u");
    }

    File epgFile(Source s) {
        return new File(app.getFilesDir(), "epg_" + s.id + ".xml");
    }

    // ---------- Загрузка плейлиста ----------

    /** Сначала показывает сохранённую копию, затем при необходимости скачивает свежий плейлист. */
    void load(boolean force) {
        final Source s = current();
        if (s == null) {
            resetLoaded();
            notifyChanged();
            return;
        }
        final boolean showing = s.id.equals(loadedId);
        if (!showing) {
            all = new ArrayList<>();
            visibleCache = null;
        }
        final int g = ++gen;
        busy = true;
        loadingId = s.id;
        notifyChanged();
        bg.execute(() -> {
            File f = cacheFile(s);
            String text = f.exists() ? readFile(f) : null, err = "";
            if (text != null && !showing && (!force || "file".equals(s.type))) deliver(g, s, text);
            boolean stale = text == null || System.currentTimeMillis() - f.lastModified() > PLAYLIST_TTL;
            if (!"file".equals(s.type) && (force || stale)) {
                try {
                    String t = fetch(s);
                    writeFile(f, t);
                    if (!t.equals(text) || (force && !showing)) deliver(g, s, t);
                    text = t;
                } catch (Exception e) {
                    err = describe(e);
                    if (text != null && force && !showing) deliver(g, s, text);
                }
            } else if (text == null) {
                err = "file".equals(s.type) ? "Файл плейлиста не найден — выберите его снова" : "Плейлист не загружен";
            }
            final String fe = err;
            main.post(() -> {
                if (g != gen) return;
                busy = false;
                loadingId = null;
                status = fe;
                statusSeq++;
                notifyChanged();
                loadEpg(s, false);
            });
        });
    }

    /** Для экрана просмотра, если система закрыла приложение в фоне. */
    void loadSync() {
        Source s = current();
        if (s == null) return;
        String t = readFile(cacheFile(s));
        if (t == null) return;
        M3u.Result r = M3u.parse(t);
        all = r.list;
        visibleCache = null;
        headerEpg = r.epg;
        loadedId = s.id;
    }

    private void deliver(int g, Source s, String text) {
        M3u.Result r = M3u.parse(text);
        main.post(() -> {
            if (g != gen) return;
            all = r.list;
            visibleCache = null;
            headerEpg = r.epg;
            loadedId = s.id;
            if (epg != null && s.id.equals(epgId)) Epg.attach(all, epg);
            notifyChanged();
        });
    }

    private static String fetch(Source s) throws Exception {
        if ("xtream".equals(s.type)) return Xtream.toM3u(s);
        String t = Net.getText(s.url, s.ua, 64 << 20);
        if (!t.contains("#EXTINF") && !t.contains("#EXTM3U")) throw new IOException("По ссылке нет плейлиста M3U");
        return t;
    }

    // ---------- Телепрограмма ----------

    String epgUrl(Source s) {
        if (s == null) return "";
        String u = !s.epg.trim().isEmpty() ? s.epg.trim() : headerEpg;
        int comma = u.indexOf(',');
        if (comma > 0) u = u.substring(0, comma);
        u = u.trim();
        return u.startsWith("http://") || u.startsWith("https://") ? u : "";
    }

    boolean hasEpg() {
        return epg != null && epgId != null && epgId.equals(loadedId);
    }

    void loadEpg(Source s, boolean force) {
        if (s == null || !s.id.equals(loadedId) || epgBusy) return;
        String url = epgUrl(s);
        if (url.isEmpty()) return;
        if (!force && epg != null && s.id.equals(epgId)) return;
        epgBusy = true;
        epgBg.execute(() -> {
            File f = epgFile(s);
            String err = "";
            Map<String, List<Epg.Prog>> m = null;
            try {
                if (force || !f.exists() || System.currentTimeMillis() - f.lastModified() > EPG_TTL) {
                    File tmp = new File(f.getPath() + ".part");
                    try {
                        Net.download(url, s.ua, tmp, 400L << 20);
                        if (!tmp.renameTo(f)) throw new IOException("Не удалось сохранить телепрограмму");
                    } catch (Exception e) {
                        delete(tmp);
                        if (!f.exists()) throw e;
                        err = describe(e);
                    }
                }
                m = Epg.parse(f);
            } catch (Exception e) {
                err = describe(e);
            }
            final Map<String, List<Epg.Prog>> fm = m;
            final String fe = err;
            main.post(() -> {
                epgBusy = false;
                epgStatus = fe;
                if (fm != null) {
                    epg = fm;
                    epgId = s.id;
                    if (s.id.equals(loadedId)) Epg.attach(all, fm);
                }
                notifyChanged();
            });
        });
    }

    // ---------- Каналы ----------

    List<Channel> visible() {
        if (visibleCache == null) {
            if (showAdult) visibleCache = all;
            else {
                List<Channel> v = new ArrayList<>(all.size());
                for (Channel c : all) if (!c.adult) v.add(c);
                visibleCache = v;
            }
        }
        return visibleCache;
    }

    int adultCount() {
        int n = 0;
        for (Channel c : all) if (c.adult) n++;
        return n;
    }

    void setShowAdult(boolean b) {
        showAdult = b;
        visibleCache = null;
    }

    boolean isFav(Channel c) {
        return fav.contains(c.key());
    }

    boolean toggleFav(Channel c) {
        String k = c.key();
        boolean on = !fav.remove(k);
        if (on) fav.add(k);
        p.edit().putStringSet("fav", new HashSet<>(fav)).apply();
        return on;
    }

    void played(Channel c) {
        String k = c.key();
        lastKey = k;
        recent.remove(k);
        recent.add(0, k);
        while (recent.size() > 30) recent.remove(recent.size() - 1);
        p.edit().putString("recent", new JSONArray(recent).toString()).putString("last", k).apply();
    }

    int fit() {
        return p.getInt("fit", 0);
    }

    void setFit(int f) {
        p.edit().putInt("fit", f).apply();
    }

    // ---------- PIN-код ----------

    boolean hasPin() {
        return !p.getString("pin", "").isEmpty();
    }

    boolean checkPin(String pin) {
        return hash(pin).equals(p.getString("pin", ""));
    }

    void setPin(String pin) {
        p.edit().putString("pin", hash(pin)).apply();
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("argeda-tv:" + s).getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return s;
        }
    }

    // ---------- Служебное ----------

    private void notifyChanged() {
        if (listener != null) listener.changed();
    }

    static String describe(Exception e) {
        if (e instanceof Net.HttpError) return e.getMessage();
        if (e instanceof UnknownHostException) return "Сервер не найден. Проверьте адрес и интернет";
        if (e instanceof SocketTimeoutException) return "Сервер не отвечает";
        if (e instanceof ConnectException) return "Не удалось подключиться к серверу";
        if (e instanceof SSLException) return "Ошибка защищённого соединения";
        if (e instanceof JSONException) return "Сервер Xtream вернул неожиданный ответ";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    static String readFile(File f) {
        if (!f.exists()) return null;
        try (InputStream in = new FileInputStream(f)) {
            return Net.text(Net.readAll(in, 128 << 20));
        } catch (IOException e) {
            return null;
        }
    }

    static void writeFile(File f, String s) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) throw new IOException("Не удалось сохранить плейлист");
    }

    static void delete(File f) {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
