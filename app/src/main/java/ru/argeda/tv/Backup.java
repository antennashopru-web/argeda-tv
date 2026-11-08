package ru.argeda.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Резервная копия настроек: плейлисты, избранное и папки, скрытые каналы, PIN-код, тема, напоминания.
 * Файл argeda-tv-nastroyki.json сохраняется через системный выбор файла, а если его нет (многие приставки) —
 * в папку «Загрузки» (Download). Оттуда же копию можно восстановить, в том числе на другом устройстве.
 */
final class Backup {
    private Backup() {}

    static final int REQ_SAVE = 7101, REQ_OPEN = 7102;
    private static final String NAME = "argeda-tv-nastroyki.json";

    static void menu(Activity a) {
        new AlertDialog.Builder(a).setTitle("Резервная копия настроек")
                .setMessage("В копии: плейлисты (ссылки, логины и пароли), избранное, папки, скрытые каналы, PIN-код, тема и напоминания. "
                        + "Храните файл в надёжном месте.")
                .setPositiveButton("Сохранить", (d, w) -> save(a))
                .setNeutralButton("Восстановить", (d, w) -> open(a))
                .setNegativeButton("Отмена", null).show();
    }

    private static void save(Activity a) {
        try {
            a.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, NAME), REQ_SAVE);
        } catch (Exception e) {
            saveToDownloads(a);
        }
    }

    private static void open(Activity a) {
        try {
            a.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"), REQ_OPEN);
        } catch (Exception e) {
            openFromDownloads(a);
        }
    }

    /** Вызывать из onActivityResult; true — это был ответ на наш запрос. */
    static boolean result(Activity a, int req, int code, Intent data) {
        if (req != REQ_SAVE && req != REQ_OPEN) return false;
        if (code != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri u = data.getData();
        if (req == REQ_SAVE) {
            try (OutputStream o = a.getContentResolver().openOutputStream(u, "wt")) {
                if (o == null) throw new java.io.IOException("Файл недоступен");
                o.write(Store.get(a).exportJson().getBytes(StandardCharsets.UTF_8));
                Ui.toast(a, "Копия настроек сохранена");
            } catch (Exception e) {
                Ui.toast(a, "Не удалось сохранить копию: " + Store.describe(e));
            }
        } else {
            try (InputStream in = a.getContentResolver().openInputStream(u)) {
                if (in == null) throw new java.io.IOException("Файл недоступен");
                restore(a, Net.text(Net.readAll(in, 4 << 20)));
            } catch (Exception e) {
                Ui.toast(a, "Не удалось прочитать копию: " + Store.describe(e));
            }
        }
        return true;
    }

    /** Android 6–9: запись в «Загрузки» требует разрешения на память. */
    private static boolean storageDenied(Activity a) {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 29) return false;
        String perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
        if (a.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        a.requestPermissions(new String[]{perm}, 7103);
        Ui.toast(a, "Разрешите ARGEDA TV доступ к файлам и повторите");
        return true;
    }

    private static void saveToDownloads(Activity a) {
        if (storageDenied(a)) return;
        try {
            byte[] b = Store.get(a).exportJson().getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver cr = a.getContentResolver();
                Uri old = findInDownloads(a);
                if (old != null) cr.delete(old, null, null);
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, NAME);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) throw new java.io.IOException("Папка «Загрузки» недоступна");
                try (OutputStream o = cr.openOutputStream(u)) {
                    if (o == null) throw new java.io.IOException("Папка «Загрузки» недоступна");
                    o.write(b);
                }
            } else {
                File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), NAME);
                try (OutputStream o = new FileOutputStream(f)) {
                    o.write(b);
                }
            }
            Ui.toast(a, "Копия сохранена в «Загрузки» (Download) → " + NAME);
        } catch (Exception e) {
            Ui.toast(a, "Не удалось сохранить копию: " + Store.describe(e));
        }
    }

    private static void openFromDownloads(Activity a) {
        if (storageDenied(a)) return;
        try {
            InputStream in;
            if (Build.VERSION.SDK_INT >= 29) {
                Uri u = findInDownloads(a);
                if (u == null) throw new java.io.FileNotFoundException("В «Загрузках» нет файла " + NAME);
                in = a.getContentResolver().openInputStream(u);
            } else {
                File f = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), NAME);
                if (!f.exists()) throw new java.io.FileNotFoundException("В «Загрузках» нет файла " + NAME);
                in = new FileInputStream(f);
            }
            if (in == null) throw new java.io.IOException("Файл недоступен");
            try (InputStream s = in) {
                restore(a, Net.text(Net.readAll(s, 4 << 20)));
            }
        } catch (Exception e) {
            Ui.toast(a, "Не удалось прочитать копию: " + Store.describe(e));
        }
    }

    private static Uri findInDownloads(Activity a) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try (Cursor c = a.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID}, MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{NAME}, null)) {
            if (c != null && c.moveToFirst())
                return android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void restore(Activity a, String text) {
        new AlertDialog.Builder(a).setTitle("Восстановить настройки?")
                .setMessage("Текущие плейлисты, избранное и настройки заменятся копией. Приложение закроется — откройте его снова.")
                .setPositiveButton("Восстановить", (d, w) -> {
                    try {
                        Store.get(a).importJson(text);
                        Playback.get(a).stop();
                        Ui.toast(a.getApplicationContext(), "Настройки восстановлены. Откройте ARGEDA TV снова");
                        a.finishAffinity();
                        a.getWindow().getDecorView().postDelayed(() -> System.exit(0), 600);
                    } catch (Exception e) {
                        Ui.toast(a, "Не удалось восстановить: " + Store.describe(e));
                    }
                })
                .setNegativeButton("Отмена", null).show();
    }
}
