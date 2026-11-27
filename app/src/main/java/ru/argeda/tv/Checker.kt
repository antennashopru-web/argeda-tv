package ru.argeda.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Проверка каналов: к каждому каналу делается короткий запрос. Кто не ответил или ответил ошибкой,
 * помечается «не работает» (строка в списке бледнее), и такие каналы можно скрыть одной кнопкой.
 * Видео не скачивается — только первые байты ответа.
 */
internal object Checker {
    private const val THREADS = 16
    private const val CONNECT_MS = 6000
    private const val READ_MS = 8000

    enum class Result { OK, DEAD, SKIP }

    fun start(a: Activity, done: Runnable) {
        val st = Store.get(a)
        val list = ArrayList(st.visible())
        if (list.isEmpty()) return
        val src = st.current()
        val total = list.size
        val checked = AtomicInteger()
        val deadCount = AtomicInteger()
        val stop = AtomicBoolean(false)
        val dead = Collections.synchronizedList(ArrayList<Channel>())
        val tested = Collections.synchronizedSet(HashSet<String>())
        val main = Handler(Looper.getMainLooper())
        val pool = Executors.newFixedThreadPool(THREADS)

        val dlg = AlertDialog.Builder(a).setTitle("Проверка каналов")
            .setMessage(progressText(0, total, 0))
            .setCancelable(false)
            .setNegativeButton("Остановить") { _, _ -> stop.set(true) }
            .show()

        fun finish() {
            if (dlg.isShowing) try {
                dlg.dismiss()
            } catch (_: Exception) {
            }
            val n = checked.get()
            // Пометки «не работает» обновляются только у проверенных каналов.
            st.setDead(HashSet(tested), dead.map { it.key() })
            done.run()
            if (a.isFinishing) return
            val msg = StringBuilder()
            msg.append("Проверено каналов: ").append(n).append(" из ").append(total).append('.')
            msg.append("\nНе работают: ").append(dead.size).append('.')
            if (dead.isEmpty()) msg.append("\n\nВсе проверенные каналы отвечают.")
            else msg.append("\n\nНерабочие каналы в списке стали бледнее. Их можно скрыть, а вернуть потом в настройках: «Скрытые каналы и категории».")
            val b = AlertDialog.Builder(a).setTitle("Проверка закончена").setMessage(msg)
            if (dead.isNotEmpty()) {
                b.setPositiveButton("Скрыть нерабочие (${dead.size})") { _, _ ->
                    for (c in ArrayList(dead)) st.setHidden(c, true)
                    done.run()
                    Ui.toast(a, "Скрыто каналов: ${dead.size}")
                }
                b.setNeutralButton("Список") { _, _ -> showList(a, ArrayList(dead)) }
            }
            b.setNegativeButton("Закрыть", null).show()
        }

        val left = AtomicInteger(total)
        for (c in list) {
            pool.execute {
                if (!stop.get()) {
                    val r = check(c, src?.ua)
                    if (r != Result.SKIP) tested.add(c.key())
                    if (r == Result.DEAD) {
                        dead.add(c)
                        deadCount.incrementAndGet()
                    }
                    val n = checked.incrementAndGet()
                    if (n % 5 == 0 || n == total) main.post {
                        if (dlg.isShowing) dlg.setMessage(progressText(checked.get(), total, deadCount.get()))
                    }
                }
                if (left.decrementAndGet() == 0) main.post { finish() }
            }
        }
        pool.shutdown()
    }

    private fun progressText(n: Int, total: Int, dead: Int) =
        "Проверено $n из $total\nНе работают: $dead"

    private fun showList(a: Activity, dead: List<Channel>) {
        AlertDialog.Builder(a).setTitle("Не работают (${dead.size})")
            .setItems(dead.map { it.name }.toTypedArray(), null)
            .setPositiveButton("Закрыть", null).show()
    }

    /** Один канал: ответ 200 и хоть какие-то данные; у HLS — настоящий плейлист #EXTM3U. */
    fun check(c: Channel, sourceUa: String?): Result {
        val u = c.url.trim()
        if (!u.startsWith("http://", true) && !u.startsWith("https://", true)) return Result.SKIP
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.instanceFollowRedirects = true
            val ua = c.userAgent.ifEmpty { sourceUa.orEmpty() }
            conn.setRequestProperty("User-Agent", ua.ifEmpty { Net.UA })
            if (c.referrer.isNotEmpty()) conn.setRequestProperty("Referer", c.referrer)
            val code = conn.responseCode
            if (code !in 200..299) return Result.DEAD
            val buf = ByteArray(2048)
            val n = conn.inputStream.use { readSome(it, buf) }
            if (n <= 0) return Result.DEAD
            val type = conn.contentType.orEmpty().lowercase()
            val hls = type.contains("mpegurl") || u.substringBefore('?').lowercase().endsWith(".m3u8")
            if (hls && !String(buf, 0, n, Charsets.UTF_8).contains("#EXTM3U")) Result.DEAD else Result.OK
        } catch (_: Exception) {
            Result.DEAD
        } finally {
            conn?.disconnect()
        }
    }

    private fun readSome(input: java.io.InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
            if (n >= 512) break
        }
        return n
    }
}
