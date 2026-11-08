package ru.argeda.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Автообновление без магазина приложений: раз в 6 часов смотрим последний релиз на GitHub,
 * и если там APK с бо́льшим versionCode — предлагаем скачать и установить.
 * Файл релиза называется argeda-tv-КОД.apk, его выкладывает workflow сборки.
 */
final class Updater {
    static final String REPO = "antennashopru-web/argeda-tv";
    static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** Копия APK в ветке update (её кладёт workflow сборки) — на случай, если сервер релизов GitHub сбоит. */
    private static final String MIRROR = "https://raw.githubusercontent.com/" + REPO + "/update/";
    private static final String OCTET = "application/octet-stream";
    private static final long EVERY = 6 * 3600_000L;
    private static final Pattern ASSET = Pattern.compile("argeda-tv-(\\d+)\\.apk");
    private static final ExecutorService bg = Executors.newSingleThreadExecutor();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private static boolean running, offered, report;
    /** Скачанный файл ждёт, пока пользователь разрешит установку из этого приложения. */
    private static File pending;

    private Updater() {}

    static long installedCode(Context c) {
        try {
            PackageInfo p = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String versionName(Context c) {
        try {
            String v = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    /** manual — из меню: проверяем сразу и сообщаем, если обновлений нет. */
    static void check(Activity a, boolean manual) {
        if (running) {
            // Фоновая проверка уже идёт: сообщим её результат, как будто проверку запустили из меню.
            if (manual) {
                report = true;
                Ui.toast(a, "Проверяю обновления…");
            }
            return;
        }
        SharedPreferences p = a.getSharedPreferences("argeda", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && (offered || now - p.getLong("updAt", 0) < EVERY)) return;
        running = true;
        if (manual) Ui.toast(a, "Проверяю обновления…");
        final long have = installedCode(a);
        final Context app = a.getApplicationContext();
        bg.execute(() -> {
            String err = null, url = null, api = null, tag = null, notes = "";
            long code = 0, size = 0;
            try {
                Release r = find();
                tag = r.tag;
                notes = r.notes;
                code = r.code;
                url = r.url;
                api = r.api;
                size = r.size;
                p.edit().putLong("updAt", System.currentTimeMillis()).apply();
            } catch (Net.HttpError e) {
                err = e.code == 404 ? "Обновлений пока нет"
                        : e.code >= 500 ? "Сервер GitHub временно недоступен, попробуйте позже" : e.getMessage();
            } catch (Exception e) {
                err = e.getMessage() != null && e.getMessage().startsWith("Нет связи") ? e.getMessage() : "Не удалось проверить обновления";
            }
            final String fErr = err, fUrl = url, fTag = tag, fNotes = notes;
            final long fCode = code;
            final List<Source> fSrc = sources(url, api, code);
            final long fSize = size;
            ui.post(() -> {
                running = false;
                boolean tell = manual || report;
                report = false;
                // Экран мог смениться (поворот, плеер) — тогда сообщаем через контекст приложения.
                boolean gone = a.isFinishing() || a.isDestroyed();
                Context t = gone ? app : a;
                if (fErr != null) {
                    if (tell) Ui.toast(t, fErr);
                } else if (fUrl == null || fCode <= have) {
                    if (tell) Ui.toast(t, "Установлена последняя версия " + versionName(app));
                } else if (gone) {
                    Ui.toast(app, "Доступна новая версия " + fTag.replaceFirst("^v", "") + ". Меню → «Проверить обновления»");
                } else {
                    offer(a, fSrc, fSize, fTag, fNotes);
                }
            });
        });
    }

    /** Последний релиз: номер версии, тег, текст и откуда качать. */
    static final class Release {
        String tag = "", notes = "", url, api;
        long code, size;
    }

    /** Файл со сведениями о последней версии в ветке update (его пишет workflow сборки). */
    private static final String MIRROR_INFO = MIRROR + "latest.json";
    private static final Pattern TAG_CODE = Pattern.compile("/releases/tag/v\\d+\\.\\d+\\.(\\d+)$");

    /**
     * Узнать последнюю версию. У части провайдеров (особенно мобильных) api.github.com отвечает 5xx
     * или не открывается, поэтому три способа по очереди: API, latest.json в ветке update
     * и переадресация github.com/…/releases/latest на страницу тега. Ошибка — от первого способа.
     */
    static Release find() throws Exception {
        // Все три способа сразу и параллельно: берём первый удачный ответ. Если адрес висит
        // (провайдер не пускает), не ждём его тайм-аута по очереди — проверка укладывается в ~20 с.
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            java.util.concurrent.CompletionService<Release> cs = new java.util.concurrent.ExecutorCompletionService<>(pool);
            final java.util.concurrent.atomic.AtomicReference<Exception> apiErr = new java.util.concurrent.atomic.AtomicReference<>();
            cs.submit(() -> {
                try {
                    return fromApi(Net.getTextQuick(LATEST, Net.UA, 1 << 20));
                } catch (Exception e) {
                    apiErr.set(e);
                    throw e;
                }
            });
            cs.submit(() -> fromMirror(Net.getTextQuick(MIRROR_INFO, Net.UA, 1 << 16)));
            cs.submit(() -> {
                Release r = fromRedirect();
                if (r == null) throw new IOException("нет переадресации");
                return r;
            });
            long until = System.currentTimeMillis() + 20000;
            for (int i = 0; i < 3; i++) {
                long left = until - System.currentTimeMillis();
                java.util.concurrent.Future<Release> f = left > 0 ? cs.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS) : null;
                if (f == null) break;
                try {
                    Release r = f.get();
                    if (r != null && r.code > 0) return r;
                } catch (java.util.concurrent.ExecutionException ignored) {
                }
            }
            Exception e = apiErr.get();
            if (e instanceof Net.HttpError) throw e;
            throw new IOException("Нет связи с сервером обновлений. Проверьте интернет и попробуйте позже");
        } finally {
            pool.shutdownNow();
        }
    }

    static Release fromApi(String json) throws Exception {
        JSONObject j = new JSONObject(json);
        Release r = new Release();
        r.tag = j.optString("tag_name", "");
        r.notes = cleanNotes(j.optString("body", ""));
        JSONArray as = j.optJSONArray("assets");
        for (int i = 0; as != null && i < as.length(); i++) {
            JSONObject x = as.getJSONObject(i);
            Matcher m = ASSET.matcher(x.optString("name"));
            if (m.matches() && Long.parseLong(m.group(1)) > r.code) {
                r.code = Long.parseLong(m.group(1));
                r.url = x.optString("browser_download_url");
                r.api = x.optString("url");
                r.size = x.optLong("size", 0);
            }
        }
        return r;
    }

    /** latest.json: {"code":158,"tag":"v1.2.58","size":1201192,"notes":"…"}. */
    static Release fromMirror(String json) throws Exception {
        JSONObject j = new JSONObject(json);
        Release r = new Release();
        r.code = j.getLong("code");
        r.tag = j.optString("tag", "");
        r.size = j.optLong("size", 0);
        r.notes = cleanNotes(j.optString("notes", ""));
        r.url = "https://github.com/" + REPO + "/releases/download/" + r.tag + "/argeda-tv-" + r.code + ".apk";
        return r;
    }

    /** github.com/…/releases/latest → …/releases/tag/v1.2.N; номер сборки N, versionCode = 100 + N. */
    private static Release fromRedirect() throws IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                new java.net.URL("https://github.com/" + REPO + "/releases/latest").openConnection();
        try {
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", Net.UA);
            String loc = c.getResponseCode() / 100 == 3 ? c.getHeaderField("Location") : null;
            Matcher m = loc == null ? null : TAG_CODE.matcher(loc);
            if (m == null || !m.find()) return null;
            Release r = new Release();
            r.code = 100 + Long.parseLong(m.group(1));
            r.tag = loc.substring(loc.lastIndexOf('/') + 1);
            r.url = "https://github.com/" + REPO + "/releases/download/" + r.tag + "/argeda-tv-" + r.code + ".apk";
            return r;
        } finally {
            c.disconnect();
        }
    }

    /** Откуда можно скачать APK, в порядке попыток. */
    static final class Source {
        final String url, accept;

        Source(String url, String accept) {
            this.url = url;
            this.accept = accept;
        }
    }

    static List<Source> sources(String browser, String api, long code) {
        List<Source> l = new ArrayList<>();
        String name = "argeda-tv-" + code + ".apk";
        // Через API: GitHub сразу отдаёт ссылку на хранилище файлов. Обычная ссылка релиза
        // (github.com/…/releases/download/…) временами отвечает 504, поэтому она не первая.
        if (api != null && !api.isEmpty()) l.add(new Source(api, OCTET));
        if (browser != null && !browser.isEmpty()) l.add(new Source(browser, null));
        l.add(new Source("https://github.com/" + REPO + "/releases/latest/download/" + name, null));
        l.add(new Source(MIRROR + name, null));
        return l;
    }

    /**
     * Текст релиза для зрителя: строка «Что нового: …», если она есть, иначе только первая строка.
     * Без технических подробностей, служебных строк (Co-authored-by, ссылки) и номера PR «(#9)».
     */
    static String cleanNotes(String body) {
        if (body == null) return "";
        String first = null;
        for (String line : body.split("\r?\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String low = t.toLowerCase(java.util.Locale.ROOT);
            if (low.startsWith("что нового:")) { first = t.substring(11).trim(); break; }
            boolean service = low.contains("http") || low.contains("-by:") || low.startsWith("claude-");
            if (first == null && !service) first = t;
        }
        if (first == null) return "";
        first = first.replaceAll("\\s*\\(#\\d+\\)\\s*$", "").trim();
        return first.length() > 200 ? first.substring(0, 200) + "…" : first;
    }

    private static void offer(Activity a, List<Source> src, long size, String tag, String notes) {
        offered = true;
        String msg = "Доступна новая версия ARGEDA TV " + tag.replaceFirst("^v", "") + ".";
        if (!notes.isEmpty()) msg += "\n\nЧто нового: " + notes;
        new AlertDialog.Builder(a)
                .setTitle("Обновление")
                .setMessage(msg)
                .setPositiveButton("Обновить", (d, w) -> download(a, src, size))
                .setNegativeButton("Позже", null)
                .show();
    }

    private static File target(Context c) {
        // До Android 7 установщик читает файл напрямую, поэтому он должен лежать во внешнем хранилище приложения.
        File dir = Build.VERSION.SDK_INT >= 24 ? new File(c.getCacheDir(), "updates") : c.getExternalCacheDir();
        if (dir == null) dir = new File(c.getCacheDir(), "updates");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "argeda-update.apk");
    }

    /** Три круга по всем адресам, с паузой между кругами: сбои GitHub обычно проходят за секунды. */
    private static final long[] PAUSE = {0, 3000, 8000};

    private static void download(Activity a, List<Source> src, long size) {
        Ui.toast(a, "Скачиваю обновление…");
        final File out = target(a);
        final File part = new File(out.getPath() + ".part");
        bg.execute(() -> {
            String err = null;
            boolean ok = false;
            Set<String> gone = new HashSet<>();
            for (int round = 0; round < PAUSE.length && !ok; round++) {
                if (PAUSE[round] > 0) {
                    try {
                        Thread.sleep(PAUSE[round]);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
                for (Source s : src) {
                    if (gone.contains(s.url)) continue;
                    try {
                        Net.download(s.url, Net.UA, s.accept, part, 200L << 20);
                        verify(part, size);
                        //noinspection ResultOfMethodCallIgnored
                        out.delete();
                        if (!part.renameTo(out)) throw new IOException("Не удалось сохранить файл");
                        ok = true;
                        break;
                    } catch (Net.HttpError e) {
                        if (e.code == 404 || e.code == 403) gone.add(s.url);
                        err = e.code >= 500 ? "сервер GitHub временно недоступен (код " + e.code + "). Попробуйте позже" : e.getMessage();
                    } catch (Exception e) {
                        err = e.getMessage() == null ? "нет связи с сервером" : e.getMessage();
                    }
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
                if (gone.size() == src.size()) break;
            }
            final String fErr = ok ? null : err;
            ui.post(() -> {
                if (fErr != null) Ui.toast(a, "Не удалось скачать обновление: " + fErr);
                else install(a, out);
            });
        });
    }

    /** Файл должен быть целым APK (zip) нужного размера, иначе установщик выдаст невнятную ошибку. */
    private static void verify(File f, long size) throws IOException {
        if (size > 0 && f.length() != size) throw new IOException("файл скачался не полностью");
        try (FileInputStream in = new FileInputStream(f)) {
            if (in.read() != 'P' || in.read() != 'K') throw new IOException("сервер прислал не APK");
        }
    }

    private static void install(Activity a, File f) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            pending = f;
            Ui.toast(a, "Разрешите ARGEDA TV устанавливать приложения и вернитесь назад");
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception e) {
                Ui.toast(a, "Включите «Неизвестные источники» для ARGEDA TV в настройках");
            }
            return;
        }
        pending = null;
        Uri u = Build.VERSION.SDK_INT >= 24
                ? FileProvider.getUriForFile(a, a.getPackageName() + ".files", f)
                : Uri.fromFile(f);
        Intent i = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(u, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            a.startActivity(i);
        } catch (Exception e) {
            Ui.toast(a, "Не удалось открыть установщик: " + e.getMessage());
        }
    }

    /** Вызывается из onResume: пользователь вернулся из настроек с разрешением на установку. */
    static void resume(Activity a) {
        if (pending == null || !pending.exists()) return;
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) return;
        install(a, pending);
    }
}
