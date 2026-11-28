package ru.argeda.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Запись эфира в файл .ts на устройстве. HLS (m3u8) — скачиваются куски потока по мере выхода,
 * обычный поток (mpegts) — копируется как есть. Запись идёт в фоновой службе, даже если экран закрыт.
 * Зашифрованные (AES) и fMP4-потоки записать нельзя — об этом сообщается.
 */
internal object Recorder {
    class Job(@JvmField val name: String, @JvmField val until: Long) {
        @JvmField val stop = AtomicBoolean(false)
        /** Файл, который пишется сейчас (file:// или content:// на флешке). */
        @JvmField @Volatile var uri = ""
        /** Где идёт запись: «на флешке» / «в памяти устройства». */
        @JvmField @Volatile var where = ""
        @JvmField @Volatile var bytes = 0L
        @JvmField @Volatile var error = ""
    }

    @JvmField val jobs: MutableList<Job> = Collections.synchronizedList(ArrayList())
    private val main = Handler(Looper.getMainLooper())

    /** Записи в памяти устройства и на флешке, новые сверху. */
    fun files(c: Context): List<RecStore.Rec> = RecStore.all(c)

    /** Запись канала 18+ (в названии файла есть пометка) — смотреть по PIN-коду. */
    fun isAdult(name: String): Boolean = M3u.ADULT.matcher(name).find()

    fun active(r: RecStore.Rec): Job? = synchronized(jobs) { jobs.firstOrNull { it.uri == r.uri } }

    fun start(c: Context, ch: Channel, ua: String, durationMs: Long) {
        val app = c.applicationContext
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(Date())
        var safe = ch.name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "Канал" }
        if (ch.adult && !M3u.ADULT.matcher(safe).find()) safe += " (18+)" // такую запись тоже смотрят по PIN-коду
        val base = "$safe $stamp"
        val job = Job(ch.name, System.currentTimeMillis() + durationMs)
        jobs.add(job)
        val i = Intent(app, RecordService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (e: Exception) {
            // Запись по расписанию, а система не дала запустить службу из фона — пишем без неё.
        }
        Thread({
            var sink: RecSink? = null
            try {
                val k = RecSink.open(app, base, job) { w -> main.post { Ui.toast(app, w) } }
                sink = k
                job.where = if (k.place.usb) "на флешке" else "в памяти устройства"
                k.use { out -> capture(job, ch, ua, out, false) }
            } catch (e: Exception) {
                if (job.error.isEmpty() && !job.stop.get()) job.error = sink?.why(e) ?: Store.describe(e)
            } finally {
                try {
                    sink?.close()
                } catch (_: Exception) {
                }
                jobs.remove(job)
                main.post {
                    val msg = if (job.error.isNotEmpty()) "Запись «${job.name}» остановлена: ${job.error}"
                    else "Запись «${job.name}» готова (${job.where}): Настройки → «Записи»"
                    Ui.toast(app, msg)
                    RecordService.refresh(app)
                }
            }
        }, "record").start()
    }

    /** Пишет поток канала в out, пока job не остановят или не выйдет время. tail — для HLS начать с последних кусков. */
    internal fun capture(job: Job, ch: Channel, ua: String, out: OutputStream, tail: Boolean) {
        if (isHls(ch.url)) hls(job, ch, ua, out, tail) else plain(job, ch, ua, out)
    }

    private fun isHls(u: String): Boolean = u.substringBefore('?').lowercase(Locale.ROOT).let { it.endsWith(".m3u8") || it.endsWith(".m3u") }

    private fun open(u: String, ch: Channel, ua: String) = Net.open(u, ua, null, 10000, 20000, ch.referrer)

    private fun done(job: Job) = job.stop.get() || System.currentTimeMillis() >= job.until

    private fun plain(job: Job, ch: Channel, ua: String, out: OutputStream) {
        val c = open(ch.url, ch, ua)
        try {
            c.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (!done(job)) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    job.bytes += n
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun hls(job: Job, ch: Channel, ua: String, out: OutputStream, tail: Boolean) {
        var url = ch.url
        var text = Net.getTextQuick(url, ua, 4 shl 20)
        // Главный плейлист с вариантами качества — берём самое высокое.
        if (text.contains("#EXT-X-STREAM-INF")) {
            url = bestVariant(url, text) ?: throw IOException("В потоке нет видео для записи")
            text = Net.getTextQuick(url, ua, 4 shl 20)
        }
        val seen = LinkedHashSet<String>()
        if (tail) {
            // Пауза эфира: на экране было примерно три последних куска, более старые не нужны.
            val segs = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            for (t in segs.dropLast(3)) seen.add(URL(URL(url), t).toString())
        }
        var fails = 0
        while (!done(job)) {
            if (text.contains("METHOD=AES-128") || text.contains("METHOD=SAMPLE-AES")) throw IOException("поток зашифрован, его нельзя записать")
            if (text.contains("#EXT-X-MAP")) throw IOException("этот формат потока пока нельзя записать")
            val target = Regex("#EXT-X-TARGETDURATION:(\\d+)").find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 6L
            for (line in text.lines()) {
                val t = line.trim()
                if (t.isEmpty() || t.startsWith("#")) continue
                val seg = URL(URL(url), t).toString()
                if (!seen.add(seg)) continue
                if (done(job)) break
                val c = open(seg, ch, ua)
                try {
                    c.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            job.bytes += n
                        }
                    }
                } finally {
                    c.disconnect()
                }
                while (seen.size > 500) seen.remove(seen.first())
            }
            if (text.contains("#EXT-X-ENDLIST")) break
            sleep(job, maxOf(1000L, target * 500))
            text = try {
                Net.getTextQuick(url, ua, 4 shl 20).also { fails = 0 }
            } catch (e: IOException) {
                if (++fails >= 10) throw e
                sleep(job, 2000)
                continue
            }
        }
    }

    private fun sleep(job: Job, ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (!job.stop.get() && System.currentTimeMillis() < end) Thread.sleep(200)
    }

    private fun bestVariant(base: String, text: String): String? {
        var best: String? = null
        var bw = -1L
        val lines = text.lines()
        for (i in lines.indices) {
            val l = lines[i].trim()
            if (!l.startsWith("#EXT-X-STREAM-INF")) continue
            val b = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.trim().startsWith("#") }?.trim() ?: continue
            if (b > bw) {
                bw = b
                best = URL(URL(base), next).toString()
            }
        }
        return best
    }
}

/** Служба нужна, чтобы система не останавливала запись, пока экран выключен или открыт другой экран. */
class RecordService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val n = notification(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        if (Recorder.jobs.isEmpty()) stopSelf()
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 4401
        private const val CH = "record"

        internal fun refresh(c: Context) {
            if (Recorder.jobs.isEmpty()) c.stopService(Intent(c, RecordService::class.java))
            else (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(c))
        }

        private fun notification(c: Context): Notification {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(NotificationChannel(CH, "Запись передач", NotificationManager.IMPORTANCE_LOW))
            }
            val names = synchronized(Recorder.jobs) { Recorder.jobs.joinToString(", ") { it.name } }
            val open = PendingIntent.getActivity(
                c, 0, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(c, CH) else Notification.Builder(c)
            return b.setSmallIcon(R.drawable.ic_live)
                .setContentTitle("ARGEDA TV — идёт запись")
                .setContentText(names.ifEmpty { "Запись" })
                .setContentIntent(open)
                .setOngoing(true)
                .build()
        }
    }
}
