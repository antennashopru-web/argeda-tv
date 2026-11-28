package ru.argeda.tv

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/**
 * Пауза прямого эфира для каналов без архива: пока стоит пауза, эфир пишется во временный файл,
 * а после паузы показ идёт из этого файла — с того места, где остановились. Файл растёт, пока
 * идёт запись, поэтому чтение ждёт новых данных, а не заканчивается. Не больше 30 минут.
 */
@OptIn(markerClass = [UnstableApi::class])
internal object TimeShift {
    const val MAX_MS = 30 * 60_000L
    private const val MAX_BYTES = 2_000_000_000L

    @Volatile private var job: Recorder.Job? = null
    @Volatile private var running = false
    @JvmField @Volatile var file: File? = null
    /** Канал, который пишется (url). */
    @JvmField @Volatile var url = ""

    fun active(): Boolean = job != null

    fun bytes(): Long = job?.bytes ?: 0L

    fun start(c: Context, ch: Channel, ua: String) {
        stop()
        val dir = c.externalCacheDir ?: c.cacheDir
        val f = File(dir, "timeshift.ts")
        f.delete()
        val j = Recorder.Job(ch.name, f, System.currentTimeMillis() + MAX_MS)
        job = j
        file = f
        url = ch.url
        running = true
        Thread({
            try {
                FileOutputStream(f).use { out ->
                    val o = object : java.io.OutputStream() {
                        override fun write(b: Int) = out.write(b)
                        override fun write(b: ByteArray, off: Int, len: Int) {
                            out.write(b, off, len)
                            out.flush()
                            if (j.bytes > MAX_BYTES) j.stop.set(true)
                        }
                    }
                    Recorder.capture(j, ch, ua, o, true)
                }
            } catch (e: Exception) {
                if (!j.stop.get()) j.error = Store.describe(e)
            } finally {
                if (job === j) running = false
            }
        }, "timeshift").start()
    }

    fun stop() {
        job?.stop?.set(true)
        job = null
        running = false
        file?.delete()
        file = null
        url = ""
    }

    /** Источник для проигрывателя: читает растущий файл и ждёт, пока запись идёт. */
    class Source(private val f: File) : BaseDataSource(false) {
        private var raf: RandomAccessFile? = null
        private var uri: Uri? = null

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            transferInitializing(dataSpec)
            val r = RandomAccessFile(f, "r")
            r.seek(dataSpec.position)
            raf = r
            transferStarted(dataSpec)
            return C.LENGTH_UNSET.toLong()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val r = raf ?: return C.RESULT_END_OF_INPUT
            var idle = 0
            while (true) {
                val n = r.read(buffer, offset, length)
                if (n > 0) {
                    bytesTransferred(n)
                    return n
                }
                // Конец файла: если запись ещё идёт — ждём продолжения (до 20 с без новых данных).
                if (!running || idle >= 400) return C.RESULT_END_OF_INPUT
                try {
                    Thread.sleep(50)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException()
                }
                idle++
            }
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            try {
                raf?.close()
            } finally {
                raf = null
                if (uri != null) {
                    uri = null
                    transferEnded()
                }
            }
        }
    }

    class Factory(private val f: File) : DataSource.Factory {
        override fun createDataSource(): DataSource = Source(f)
    }
}
