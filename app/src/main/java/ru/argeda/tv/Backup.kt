package ru.argeda.tv

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Резервная копия настроек: плейлисты, избранное и папки, скрытые каналы, PIN-код, тема, напоминания.
 * Файл argeda-tv-nastroyki.json сохраняется через системный выбор файла, а если его нет (многие приставки) —
 * в папку «Загрузки» (Download). Оттуда же копию можно восстановить, в том числе на другом устройстве.
 */
internal object Backup {
    const val REQ_SAVE = 7101
    const val REQ_OPEN = 7102
    private const val NAME = "argeda-tv-nastroyki.json"

    @JvmStatic
    fun menu(a: Activity) {
        AlertDialog.Builder(a).setTitle("Резервная копия настроек")
            .setMessage(
                "В копии: плейлисты (ссылки, логины и пароли), избранное, папки, скрытые каналы, PIN-код, тема и напоминания. " +
                    "Храните файл в надёжном месте."
            )
            .setPositiveButton("Сохранить") { _, _ -> save(a) }
            .setNeutralButton("Восстановить") { _, _ -> open(a) }
            .setNegativeButton("Отмена", null).show()
    }

    private fun save(a: Activity) {
        try {
            a.startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json").putExtra(Intent.EXTRA_TITLE, NAME), REQ_SAVE
            )
        } catch (_: Exception) {
            saveToDownloads(a)
        }
    }

    private fun open(a: Activity) {
        try {
            a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_OPEN)
        } catch (_: Exception) {
            openFromDownloads(a)
        }
    }

    /** Вызывать из onActivityResult; true — это был ответ на наш запрос. */
    @JvmStatic
    fun result(a: Activity, req: Int, code: Int, data: Intent?): Boolean {
        if (req != REQ_SAVE && req != REQ_OPEN) return false
        val u = data?.data
        if (code != Activity.RESULT_OK || u == null) return true
        if (req == REQ_SAVE) {
            try {
                (a.contentResolver.openOutputStream(u, "wt") ?: throw IOException("Файл недоступен")).use { o ->
                    o.write(Store.get(a).exportJson().toByteArray(Charsets.UTF_8))
                    Ui.toast(a, "Копия настроек сохранена")
                }
            } catch (e: Exception) {
                Ui.toast(a, "Не удалось сохранить копию: " + Store.describe(e))
            }
        } else {
            try {
                (a.contentResolver.openInputStream(u) ?: throw IOException("Файл недоступен")).use { input ->
                    restore(a, Net.text(Net.readAll(input, 4 shl 20)))
                }
            } catch (e: Exception) {
                Ui.toast(a, "Не удалось прочитать копию: " + Store.describe(e))
            }
        }
        return true
    }

    /** Android 6–9: запись в «Загрузки» требует разрешения на память. */
    private fun storageDenied(a: Activity): Boolean {
        if (Build.VERSION.SDK_INT < 23 || Build.VERSION.SDK_INT >= 29) return false
        val perm = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (a.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) return false
        a.requestPermissions(arrayOf(perm), 7103)
        Ui.toast(a, "Разрешите ARGEDA TV доступ к файлам и повторите")
        return true
    }

    @Suppress("DEPRECATION")
    private fun saveToDownloads(a: Activity) {
        if (storageDenied(a)) return
        try {
            val b = Store.get(a).exportJson().toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= 29) {
                val cr = a.contentResolver
                findInDownloads(a)?.let { cr.delete(it, null, null) }
                val v = ContentValues()
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                val u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: throw IOException("Папка «Загрузки» недоступна")
                (cr.openOutputStream(u) ?: throw IOException("Папка «Загрузки» недоступна")).use { it.write(b) }
            } else {
                val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), NAME)
                FileOutputStream(f).use { it.write(b) }
            }
            Ui.toast(a, "Копия сохранена в «Загрузки» (Download) → $NAME")
        } catch (e: Exception) {
            Ui.toast(a, "Не удалось сохранить копию: " + Store.describe(e))
        }
    }

    @Suppress("DEPRECATION")
    private fun openFromDownloads(a: Activity) {
        if (storageDenied(a)) return
        try {
            val input: InputStream?
            if (Build.VERSION.SDK_INT >= 29) {
                val u = findInDownloads(a) ?: throw FileNotFoundException("В «Загрузках» нет файла $NAME")
                input = a.contentResolver.openInputStream(u)
            } else {
                val f = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), NAME)
                if (!f.exists()) throw FileNotFoundException("В «Загрузках» нет файла $NAME")
                input = FileInputStream(f)
            }
            (input ?: throw IOException("Файл недоступен")).use { restore(a, Net.text(Net.readAll(it, 4 shl 20))) }
        } catch (e: Exception) {
            Ui.toast(a, "Не удалось прочитать копию: " + Store.describe(e))
        }
    }

    private fun findInDownloads(a: Activity): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        try {
            a.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
                MediaStore.MediaColumns.DISPLAY_NAME + "=?", arrayOf(NAME), null
            )?.use { c ->
                if (c.moveToFirst()) return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun restore(a: Activity, text: String) {
        AlertDialog.Builder(a).setTitle("Восстановить настройки?")
            .setMessage("Текущие плейлисты, избранное и настройки заменятся копией. Приложение закроется — откройте его снова.")
            .setPositiveButton("Восстановить") { _, _ ->
                try {
                    Store.get(a).importJson(text)
                    Playback.get(a).stop()
                    Ui.toast(a.applicationContext, "Настройки восстановлены. Откройте ARGEDA TV снова")
                    a.finishAffinity()
                    a.window.decorView.postDelayed({ System.exit(0) }, 600)
                } catch (e: Exception) {
                    Ui.toast(a, "Не удалось восстановить: " + Store.describe(e))
                }
            }
            .setNegativeButton("Отмена", null).show()
    }
}
