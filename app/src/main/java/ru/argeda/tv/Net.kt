package ru.argeda.tv

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** Простые HTTP-запросы: плейлист, Xtream API, телепрограмма, логотипы. */
internal object Net {
    const val UA = "ARGEDA-TV/1.0 (Linux; Android)"

    class HttpError(@JvmField val code: Int) : IOException(text(code)) {
        private companion object {
            fun text(c: Int): String = when {
                c == 401 || c == 403 -> "Доступ запрещён (код $c). Проверьте ссылку и подписку у провайдера"
                c == 404 -> "По ссылке ничего нет (код 404)"
                c >= 500 -> "Ошибка на сервере провайдера (код $c)"
                else -> "Сервер ответил кодом $c"
            }
        }
    }

    /** Ход скачивания: done байт из total (total = -1, если сервер не сообщил размер). */
    fun interface Progress {
        @Throws(IOException::class)
        fun on(done: Long, total: Long)
    }

    /** accept — заголовок Accept (null — не отправлять). */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun open(url: String, ua: String?, accept: String? = null, connectMs: Int = 15000, readMs: Int = 30000): HttpURLConnection {
        var u = url.trim()
        repeat(6) { hop ->
            val x = URL(u)
            val proto = x.protocol
            if (proto != "http" && proto != "https") throw IOException("Поддерживаются только ссылки http:// и https://")
            val c = x.openConnection() as HttpURLConnection
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", if (ua.isNullOrEmpty()) UA else ua)
            c.setRequestProperty("Accept-Encoding", "gzip")
            // Accept нужен только самому API; на переадресованный адрес хранилища его не шлём.
            if (accept != null && hop == 0) c.setRequestProperty("Accept", accept)
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                val loc = c.getHeaderField("Location")
                c.disconnect()
                if (loc == null) throw IOException("Сервер переадресовал запрос в никуда")
                u = URL(x, loc).toString()
                return@repeat
            }
            if (code != 200) {
                c.disconnect()
                throw HttpError(code)
            }
            return c
        }
        throw IOException("Слишком много переадресаций")
    }

    private fun body(c: HttpURLConnection): InputStream {
        var input: InputStream = BufferedInputStream(c.inputStream, 65536)
        if ("gzip".equals(c.contentEncoding, ignoreCase = true)) input = GZIPInputStream(input, 65536)
        return input
    }

    @JvmStatic
    @Throws(IOException::class)
    fun get(url: String, ua: String?, max: Int): ByteArray {
        val c = open(url, ua)
        try {
            return body(c).use { readAll(it, max) }
        } finally {
            c.disconnect()
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun getText(url: String, ua: String?, max: Int): String = text(get(url, ua, max))

    /** Короткий запрос с малыми тайм-аутами: если сервер не отвечает, быстро пробуем другой. */
    @JvmStatic
    @Throws(IOException::class)
    fun getTextQuick(url: String, ua: String?, max: Int): String {
        val c = open(url, ua, null, 8000, 10000)
        try {
            return text(body(c).use { readAll(it, max) })
        } finally {
            c.disconnect()
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun download(url: String, ua: String?, out: File, max: Long) = download(url, ua, null, out, max, null)

    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun download(url: String, ua: String?, accept: String?, out: File, max: Long, p: Progress? = null) {
        val c = open(url, ua, accept)
        var len = -1L
        if (c.contentEncoding == null) len = c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
        try {
            body(c).use { input ->
                FileOutputStream(out).use { o ->
                    val buf = ByteArray(65536)
                    var total = 0L
                    p?.on(0, len)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > max) throw IOException("Файл слишком большой")
                        o.write(buf, 0, n)
                        p?.on(total, len)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun readAll(input: InputStream, max: Int): ByteArray {
        val b = ByteArrayOutputStream(65536)
        val buf = ByteArray(65536)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > max) throw IOException("Файл слишком большой")
            b.write(buf, 0, n)
        }
        return b.toByteArray()
    }

    /** Текст в UTF-8; сжатые gzip данные распаковываются. */
    @JvmStatic
    @Throws(IOException::class)
    fun text(bytes: ByteArray): String {
        var b = bytes
        if (b.size > 2 && (b[0].toInt() and 0xff) == 0x1f && (b[1].toInt() and 0xff) == 0x8b) {
            b = readAll(GZIPInputStream(ByteArrayInputStream(b)), 100 shl 20)
        }
        return String(b, Charsets.UTF_8)
    }
}
