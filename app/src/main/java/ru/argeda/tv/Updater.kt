package ru.argeda.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * Автообновление без магазина приложений: раз в 6 часов смотрим последний релиз на GitHub,
 * и если там APK с бо́льшим versionCode — предлагаем скачать и установить.
 * Файл релиза называется argeda-tv-КОД.apk, его выкладывает workflow сборки.
 */
internal object Updater {
    const val REPO = "antennashopru-web/argeda-tv"
    const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    /** Копия APK в ветке update (её кладёт workflow сборки) — на случай, если сервер релизов GitHub сбоит. */
    private const val MIRROR = "https://raw.githubusercontent.com/$REPO/update/"
    private const val OCTET = "application/octet-stream"
    private const val EVERY = 6 * 3600_000L
    private val ASSET = Pattern.compile("argeda-tv-(\\d+)\\.apk")
    private val bg = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var running = false
    private var offered = false
    private var report = false

    /** Скачанный файл ждёт, пока пользователь разрешит установку из этого приложения. */
    private var pending: File? = null

    @JvmStatic
    fun installedCode(c: Context): Long = try {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else @Suppress("DEPRECATION") p.versionCode.toLong()
    } catch (_: Exception) {
        0
    }

    @JvmStatic
    fun versionName(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    /** manual — из меню: проверяем сразу и сообщаем, если обновлений нет. */
    @JvmStatic
    fun check(a: Activity, manual: Boolean) {
        if (running) {
            // Фоновая проверка уже идёт: сообщим её результат, как будто проверку запустили из меню.
            if (manual) {
                report = true
                Ui.toast(a, "Проверяю обновления…")
            }
            return
        }
        val p = a.getSharedPreferences("argeda", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!manual && (offered || now - p.getLong("updAt", 0) < EVERY)) return
        running = true
        if (manual) Ui.toast(a, "Проверяю обновления…")
        val have = installedCode(a)
        val app = a.applicationContext
        bg.execute {
            var err: String? = null
            var r = Release()
            try {
                r = find()
                p.edit().putLong("updAt", System.currentTimeMillis()).apply()
            } catch (e: Net.HttpError) {
                err = when {
                    e.code == 404 -> "Обновлений пока нет"
                    e.code >= 500 -> "Сервер GitHub временно недоступен, попробуйте позже"
                    else -> e.message
                }
            } catch (e: Exception) {
                val m = e.message
                err = if (m != null && m.startsWith("Нет связи")) m else "Не удалось проверить обновления"
            }
            val src = sources(r.url, r.api, r.code)
            ui.post {
                running = false
                val tell = manual || report
                report = false
                // Экран мог смениться (поворот, плеер) — тогда сообщаем через контекст приложения.
                val gone = a.isFinishing || a.isDestroyed
                val t: Context = if (gone) app else a
                when {
                    err != null -> if (tell) Ui.toast(t, err)
                    r.url == null || r.code <= have -> if (tell) Ui.toast(t, "Установлена последняя версия " + versionName(app))
                    gone -> Ui.toast(app, "Доступна новая версия " + r.tag.replaceFirst(Regex("^v"), "") + ". Настройки → «Проверить обновления»")
                    else -> offer(a, src, r.size, r.tag, r.notes)
                }
            }
        }
    }

    /** Последний релиз: номер версии, тег, текст и откуда качать. */
    class Release {
        @JvmField var tag = ""
        @JvmField var notes = ""
        @JvmField var url: String? = null
        @JvmField var api: String? = null
        @JvmField var code = 0L
        @JvmField var size = 0L
    }

    /** Файл со сведениями о последней версии в ветке update (его пишет workflow сборки). */
    private const val MIRROR_INFO = MIRROR + "latest.json"

    /** Та же ветка update через CDN jsDelivr — не GitHub, на случай если провайдер режет все адреса GitHub. */
    private const val CDN = "https://cdn.jsdelivr.net/gh/$REPO@update/"
    private val TAG_CODE = Pattern.compile("/releases/tag/v\\d+\\.\\d+\\.(\\d+)$")

    /**
     * Узнать последнюю версию. У части провайдеров (особенно мобильных) api.github.com отвечает 5xx
     * или не открывается, поэтому четыре способа: API, latest.json в ветке update (напрямую и через jsDelivr)
     * и переадресация github.com/…/releases/latest на страницу тега. Ошибка — от первого способа.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun find(): Release {
        // Все способы сразу и параллельно: берём первый удачный ответ. Если адрес висит
        // (провайдер не пускает), не ждём его тайм-аута по очереди — проверка укладывается в ~20 с.
        val pool = Executors.newFixedThreadPool(4)
        try {
            val cs = ExecutorCompletionService<Release>(pool)
            val apiErr = AtomicReference<Exception>()
            cs.submit {
                try {
                    fromApi(Net.getTextQuick(LATEST, Net.UA, 1 shl 20))
                } catch (e: Exception) {
                    apiErr.set(e)
                    throw e
                }
            }
            cs.submit { fromMirror(Net.getTextQuick(MIRROR_INFO, Net.UA, 1 shl 16)) }
            cs.submit { fromMirror(Net.getTextQuick(CDN + "latest.json", Net.UA, 1 shl 16)) }
            cs.submit { fromRedirect() ?: throw IOException("нет переадресации") }
            // Зеркала (особенно jsDelivr) могут несколько минут отдавать прошлую версию. Поэтому после
            // первого удачного ответа ждём остальные ещё до 4 с и берём самую новую версию из всех.
            var until = System.currentTimeMillis() + 20000
            var best: Release? = null
            for (i in 0 until 4) {
                val left = until - System.currentTimeMillis()
                val f = (if (left > 0) cs.poll(left, TimeUnit.MILLISECONDS) else null) ?: break
                try {
                    val r = f.get()
                    if (r != null && r.code > 0) {
                        if (best == null) until = minOf(until, System.currentTimeMillis() + 4000)
                        if (best == null || r.code > best.code) best = r
                    }
                } catch (_: ExecutionException) {
                }
            }
            if (best != null) return best
            val e = apiErr.get()
            if (e is Net.HttpError) throw e
            throw IOException("Нет связи с сервером обновлений. Проверьте интернет и попробуйте позже")
        } finally {
            pool.shutdownNow()
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun fromApi(json: String): Release {
        val j = JSONObject(json)
        val r = Release()
        r.tag = j.optString("tag_name", "")
        r.notes = cleanNotes(j.optString("body", ""))
        val assets = j.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val x = assets.getJSONObject(i)
            val m = ASSET.matcher(x.optString("name"))
            if (m.matches() && m.group(1)!!.toLong() > r.code) {
                r.code = m.group(1)!!.toLong()
                r.url = x.optString("browser_download_url")
                r.api = x.optString("url")
                r.size = x.optLong("size", 0)
            }
        }
        return r
    }

    /** latest.json: {"code":158,"tag":"v1.2.58","size":1201192,"notes":"…"}. */
    @JvmStatic
    @Throws(Exception::class)
    fun fromMirror(json: String): Release {
        val j = JSONObject(json)
        val r = Release()
        r.code = j.getLong("code")
        r.tag = j.optString("tag", "")
        r.size = j.optLong("size", 0)
        r.notes = cleanNotes(j.optString("notes", ""))
        r.url = "https://github.com/$REPO/releases/download/${r.tag}/argeda-tv-${r.code}.apk"
        return r
    }

    /** github.com/…/releases/latest → …/releases/tag/v1.2.N; номер сборки N, versionCode = 100 + N. */
    private fun fromRedirect(): Release? {
        val c = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", Net.UA)
            val loc = (if (c.responseCode / 100 == 3) c.getHeaderField("Location") else null) ?: return null
            val m = TAG_CODE.matcher(loc)
            if (!m.find()) return null
            val r = Release()
            r.code = 100 + m.group(1)!!.toLong()
            r.tag = loc.substring(loc.lastIndexOf('/') + 1)
            r.url = "https://github.com/$REPO/releases/download/${r.tag}/argeda-tv-${r.code}.apk"
            return r
        } finally {
            c.disconnect()
        }
    }

    /** Откуда можно скачать APK, в порядке попыток. */
    class Source(@JvmField val url: String, @JvmField val accept: String?)

    @JvmStatic
    fun sources(browser: String?, api: String?, code: Long): List<Source> {
        val l = ArrayList<Source>()
        val name = "argeda-tv-$code.apk"
        // Через API: GitHub сразу отдаёт ссылку на хранилище файлов. Обычная ссылка релиза
        // (github.com/…/releases/download/…) временами отвечает 504, поэтому она не первая.
        if (!api.isNullOrEmpty()) l.add(Source(api, OCTET))
        if (!browser.isNullOrEmpty()) l.add(Source(browser, null))
        l.add(Source("https://github.com/$REPO/releases/latest/download/$name", null))
        l.add(Source(MIRROR + name, null))
        l.add(Source(CDN + name, null))
        return l
    }

    /**
     * Текст релиза для зрителя: строка «Что нового: …», если она есть, иначе только первая строка.
     * Без технических подробностей, служебных строк (Co-authored-by, ссылки) и номера PR «(#9)».
     */
    @JvmStatic
    fun cleanNotes(body: String?): String {
        if (body == null) return ""
        var first: String? = null
        for (line in body.split(Regex("\r?\n"))) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val low = t.lowercase(Locale.ROOT)
            if (low.startsWith("что нового:")) {
                first = t.substring(11).trim()
                break
            }
            val service = low.contains("http") || low.contains("-by:") || low.startsWith("claude-")
            if (first == null && !service) first = t
        }
        if (first == null) return ""
        val s = first.replace(Regex("\\s*\\(#\\d+\\)\\s*$"), "").trim()
        return if (s.length > 200) s.substring(0, 200) + "…" else s
    }

    private fun offer(a: Activity, src: List<Source>, size: Long, tag: String, notes: String) {
        offered = true
        var msg = "Доступна новая версия ARGEDA TV " + tag.replaceFirst(Regex("^v"), "") + "."
        if (notes.isNotEmpty()) msg += "\n\nЧто нового: $notes"
        AlertDialog.Builder(a)
            .setTitle("Обновление")
            .setMessage(msg)
            .setPositiveButton("Обновить") { _, _ -> download(a, src, size) }
            .setNegativeButton("Позже", null)
            .show()
    }

    private fun target(c: Context): File {
        // До Android 7 установщик читает файл напрямую, поэтому он должен лежать во внешнем хранилище приложения.
        val dir = (if (Build.VERSION.SDK_INT >= 24) File(c.cacheDir, "updates") else c.externalCacheDir) ?: File(c.cacheDir, "updates")
        dir.mkdirs()
        return File(dir, "argeda-update.apk")
    }

    /** Три круга по всем адресам, с паузой между кругами: сбои GitHub обычно проходят за секунды. */
    private val PAUSE = longArrayOf(0, 3000, 8000)

    /** Окно с ходом скачивания: этап, полоса, мегабайты и проценты, кнопка «Отмена». */
    private class Status(a: Activity) {
        val dialog: AlertDialog
        val bar: ProgressBar
        val text: TextView
        @Volatile var cancelled = false
        var shownAt = 0L

        init {
            val dp = a.resources.displayMetrics.density
            val box = LinearLayout(a)
            box.orientation = LinearLayout.VERTICAL
            val pad = (24 * dp).toInt()
            box.setPadding(pad, (8 * dp).toInt(), pad, 0)
            text = TextView(a)
            text.textSize = 16f
            text.text = "Подключаюсь к серверу…"
            bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal)
            bar.max = 1000
            bar.isIndeterminate = true
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = (12 * dp).toInt()
            box.addView(text)
            box.addView(bar, lp)
            dialog = AlertDialog.Builder(a)
                .setTitle("Скачиваю обновление")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton("Отмена") { _, _ -> cancelled = true }
                .show()
        }

        fun stage(s: String) {
            ui.post {
                bar.isIndeterminate = true
                text.text = s
            }
        }

        /** Не чаще 5 раз в секунду, чтобы не тормозить экран. */
        fun progress(done: Long, total: Long) {
            val now = System.currentTimeMillis()
            if (now - shownAt < 200 && done < total) return
            shownAt = now
            ui.post {
                if (total > 0) {
                    bar.isIndeterminate = false
                    bar.progress = (done * 1000 / total).toInt()
                    text.text = String.format(Locale.ROOT, "Скачано %s из %s МБ (%d%%)", mb(done), mb(total), done * 100 / total)
                        .replace('.', ',')
                } else {
                    text.text = String.format(Locale.ROOT, "Скачано %s МБ", mb(done)).replace('.', ',')
                }
            }
        }

        fun close() {
            ui.post {
                try {
                    dialog.dismiss()
                } catch (_: Exception) {
                }
            }
        }

        private fun mb(b: Long): String = String.format(Locale.ROOT, "%.1f", b / 1048576.0)
    }

    private fun download(a: Activity, src: List<Source>, size: Long) {
        val st = Status(a)
        val out = target(a)
        val part = File(out.path + ".part")
        val tries = src.size * PAUSE.size
        bg.execute {
            var err: String? = null
            var ok = false
            var attempt = 0
            val gone = HashSet<String>()
            var round = 0
            rounds@ while (round < PAUSE.size && !ok && !st.cancelled) {
                if (PAUSE[round] > 0) {
                    st.stage("Сервер не отвечает. Новая попытка через " + PAUSE[round] / 1000 + " с…")
                    try {
                        Thread.sleep(PAUSE[round])
                    } catch (_: InterruptedException) {
                        break@rounds
                    }
                }
                for (s in src) {
                    if (st.cancelled) break
                    if (gone.contains(s.url)) continue
                    attempt++
                    st.stage(if (attempt == 1) "Подключаюсь к серверу…" else "Пробую другой адрес (попытка $attempt из $tries)…")
                    try {
                        Net.download(s.url, Net.UA, s.accept, part, 200L shl 20) { done, total ->
                            if (st.cancelled) throw IOException("отменено")
                            st.progress(done, if (total > 0) total else size)
                        }
                        st.stage("Проверяю файл…")
                        verify(part, size)
                        out.delete()
                        if (!part.renameTo(out)) throw IOException("Не удалось сохранить файл")
                        ok = true
                        break
                    } catch (e: Net.HttpError) {
                        if (e.code == 404 || e.code == 403) gone.add(s.url)
                        err = if (e.code >= 500) "сервер GitHub временно недоступен (код ${e.code}). Попробуйте позже" else e.message
                    } catch (e: Exception) {
                        err = e.message ?: "нет связи с сервером"
                    }
                    part.delete()
                }
                if (gone.size == src.size) break
                round++
            }
            val fErr = if (ok) null else err
            val cancelled = st.cancelled
            if (ok) st.stage("Открываю установщик…")
            st.close()
            ui.post {
                if (cancelled) {
                    Ui.toast(a, "Скачивание обновления отменено")
                } else if (fErr != null) {
                    if (a.isFinishing || a.isDestroyed) {
                        Ui.toast(a.applicationContext, "Не удалось скачать обновление: $fErr")
                        return@post
                    }
                    AlertDialog.Builder(a)
                        .setTitle("Обновление не скачалось")
                        .setMessage("Не удалось скачать обновление: $fErr")
                        .setPositiveButton("Повторить") { _, _ -> download(a, src, size) }
                        .setNegativeButton("Закрыть", null)
                        .show()
                } else {
                    install(a, out)
                }
            }
        }
    }

    /** Файл должен быть целым APK (zip) нужного размера, иначе установщик выдаст невнятную ошибку. */
    private fun verify(f: File, size: Long) {
        if (size > 0 && f.length() != size) throw IOException("файл скачался не полностью")
        FileInputStream(f).use { input ->
            if (input.read() != 'P'.code || input.read() != 'K'.code) throw IOException("сервер прислал не APK")
        }
    }

    private fun install(a: Activity, f: File) {
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            pending = f
            Ui.toast(a, "Разрешите ARGEDA TV устанавливать приложения и вернитесь назад")
            try {
                a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
            } catch (_: Exception) {
                Ui.toast(a, "Включите «Неизвестные источники» для ARGEDA TV в настройках")
            }
            return
        }
        pending = null
        val u = if (Build.VERSION.SDK_INT >= 24) FileProvider.getUriForFile(a, a.packageName + ".files", f) else Uri.fromFile(f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(u, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            a.startActivity(i)
        } catch (e: Exception) {
            Ui.toast(a, "Не удалось открыть установщик: " + e.message)
        }
    }

    /** Вызывается из onResume: пользователь вернулся из настроек с разрешением на установку. */
    @JvmStatic
    fun resume(a: Activity) {
        val f = pending
        if (f == null || !f.exists()) return
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) return
        install(a, f)
    }
}
