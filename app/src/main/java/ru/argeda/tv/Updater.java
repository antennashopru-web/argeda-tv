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
    static final String LATEST = "https://api.github.com/repos/antennashopru-web/argeda-tv/releases/latest";
    private static final long EVERY = 6 * 3600_000L;
    private static final Pattern ASSET = Pattern.compile("argeda-tv-(\\d+)\\.apk");
    private static final ExecutorService bg = Executors.newSingleThreadExecutor();
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private static boolean running, offered;
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

    /** manual — из меню: проверяем сразу и сообщаем, если обновлений нет. */
    static void check(Activity a, boolean manual) {
        if (running) return;
        SharedPreferences p = a.getSharedPreferences("argeda", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && (offered || now - p.getLong("updAt", 0) < EVERY)) return;
        running = true;
        if (manual) Ui.toast(a, "Проверяю обновления…");
        final long have = installedCode(a);
        bg.execute(() -> {
            String err = null, url = null, tag = null, notes = "";
            long code = 0;
            try {
                JSONObject r = new JSONObject(Net.getText(LATEST, Net.UA, 1 << 20));
                tag = r.optString("tag_name", "");
                notes = r.optString("body", "");
                JSONArray as = r.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) {
                    JSONObject x = as.getJSONObject(i);
                    Matcher m = ASSET.matcher(x.optString("name"));
                    if (m.matches() && Long.parseLong(m.group(1)) > code) {
                        code = Long.parseLong(m.group(1));
                        url = x.optString("browser_download_url");
                    }
                }
                p.edit().putLong("updAt", System.currentTimeMillis()).apply();
            } catch (Net.HttpError e) {
                err = e.code == 404 ? "Обновлений пока нет" : e.getMessage();
            } catch (Exception e) {
                err = "Не удалось проверить обновления";
            }
            final String fErr = err, fUrl = url, fTag = tag, fNotes = notes;
            final long fCode = code;
            ui.post(() -> {
                running = false;
                if (a.isFinishing()) return;
                if (fErr != null) {
                    if (manual) Ui.toast(a, fErr);
                } else if (fUrl == null || fCode <= have) {
                    if (manual) Ui.toast(a, "Установлена последняя версия");
                } else {
                    offer(a, fUrl, fTag, fNotes);
                }
            });
        });
    }

    private static void offer(Activity a, String url, String tag, String notes) {
        offered = true;
        String msg = "Доступна новая версия ARGEDA TV " + tag.replaceFirst("^v", "") + ".";
        if (!notes.isEmpty()) msg += "\n\n" + (notes.length() > 600 ? notes.substring(0, 600) + "…" : notes);
        new AlertDialog.Builder(a)
                .setTitle("Обновление")
                .setMessage(msg)
                .setPositiveButton("Обновить", (d, w) -> download(a, url))
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

    private static void download(Activity a, String url) {
        Ui.toast(a, "Скачиваю обновление…");
        final File out = target(a);
        bg.execute(() -> {
            String err = null;
            try {
                Net.download(url, Net.UA, out, 200L << 20);
            } catch (Exception e) {
                err = e.getMessage();
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            final String fErr = err;
            ui.post(() -> {
                if (fErr != null) Ui.toast(a, "Не удалось скачать обновление: " + fErr);
                else install(a, out);
            });
        });
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
