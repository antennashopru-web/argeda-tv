package ru.argeda.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Collections
import java.util.Locale

/**
 * Маленький веб-сервер в домашней сети, пока открыт экран плейлиста:
 * на телефоне открывают http://IP:порт/код и вставляют ссылку — не нужно набирать её пультом.
 * Принимает подключения только из локальной сети и только по случайному коду в адресе.
 */
internal class LinkServer {
    fun interface Callback {
        fun got(fields: Map<String, String>)
    }

    @JvmField val code: String = (100000 + SecureRandom().nextInt(900000)).toString()
    private var ss: ServerSocket? = null
    @Volatile private var on = false
    @JvmField var port = 0

    fun start(cb: Callback): Boolean {
        var p = 8765
        while (p < 8775 && ss == null) {
            try {
                ss = ServerSocket(p)
                port = p
            } catch (_: IOException) {
            }
            p++
        }
        val server = ss ?: return false
        on = true
        val t = Thread({
            while (on) {
                try {
                    server.accept().use { s ->
                        s.soTimeout = 8000
                        handle(s, cb)
                    }
                } catch (_: IOException) {
                    if (!on) break
                }
            }
        }, "link-server")
        t.isDaemon = true
        t.start()
        return true
    }

    fun stop() {
        on = false
        try {
            ss?.close()
        } catch (_: IOException) {
        }
        ss = null
    }

    private fun handle(s: Socket, cb: Callback) {
        val a = s.inetAddress
        if (!(a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress)) return
        val input: InputStream = BufferedInputStream(s.getInputStream())
        val out = s.getOutputStream()
        val req = line(input) ?: return
        var len = 0
        var h = line(input)
        while (h != null && h.isNotEmpty()) {
            if (h.lowercase(Locale.ROOT).startsWith("content-length:")) h.substring(15).trim().toIntOrNull()?.let { len = it }
            h = line(input)
        }
        val p = req.split(" ")
        if (p.size < 2) return
        var path = p[1]
        val q = path.indexOf('?')
        if (q >= 0) path = path.substring(0, q)
        if (path != "/$code") {
            send(out, 404, "text/plain; charset=utf-8", "Не найдено. Проверьте адрес на экране телевизора.")
            return
        }
        if (p[0] == "POST" && len in 1..65536) {
            val b = ByteArray(len)
            var r = 0
            while (r < len) {
                val n = input.read(b, r, len - r)
                if (n < 0) break
                r += n
            }
            cb.got(form(String(b, 0, r, Charsets.UTF_8)))
            send(out, 200, "text/html; charset=utf-8", page(true))
            return
        }
        send(out, 200, "text/html; charset=utf-8", page(false))
    }

    companion object {
        @JvmStatic
        fun localIp(): String? {
            try {
                for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in Collections.list(ni.inetAddresses)) {
                        if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress
                    }
                }
            } catch (_: Exception) {
            }
            return null
        }

        private fun line(input: InputStream): String? {
            val b = ByteArrayOutputStream()
            var c: Int
            while (true) {
                c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (b.size() > 8192) throw IOException("line too long")
                if (c != '\r'.code) b.write(c)
            }
            if (c < 0 && b.size() == 0) return null
            return b.toString("ISO-8859-1")
        }

        private fun form(body: String): Map<String, String> {
            val m = HashMap<String, String>()
            for (part in body.split("&")) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                try {
                    m[URLDecoder.decode(part.substring(0, eq), "UTF-8")] = URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                } catch (_: Exception) {
                }
            }
            return m
        }

        private fun send(o: OutputStream, status: Int, type: String, body: String) {
            val b = body.toByteArray(Charsets.UTF_8)
            val h = "HTTP/1.1 " + status + (if (status == 200) " OK" else " Not Found") + "\r\nContent-Type: " + type +
                "\r\nContent-Length: " + b.size + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            o.write(h.toByteArray(Charsets.ISO_8859_1))
            o.write(b)
            o.flush()
        }

        private fun page(done: Boolean): String {
            return "<!doctype html><html lang=ru><head><meta charset=utf-8>" +
                "<meta name=viewport content='width=device-width,initial-scale=1'><title>ARGEDA TV</title><style>" +
                "body{font:16px system-ui,sans-serif;background:#0B1220;color:#F2F5FA;margin:0 auto;padding:20px;max-width:560px}" +
                "h1{font-size:22px;margin:0 0 6px}h2{font-size:17px;margin:24px 0 8px;color:#FF8A65}" +
                "label{display:block;margin:10px 0 4px;color:#94A3B8;font-size:14px}" +
                "input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #2B3D5C;" +
                "background:#1A263B;color:#F2F5FA;font-size:16px}" +
                "button{margin-top:22px;width:100%;padding:14px;border:0;border-radius:24px;background:#E53935;color:#fff;font-size:17px}" +
                ".ok{background:#1A263B;border-left:4px solid #4CAF50;border-radius:10px;padding:14px;margin:12px 0}" +
                "p{color:#94A3B8;font-size:14px}</style></head><body><h1>ARGEDA TV</h1>" +
                "<p>Заполните одно: ссылку M3U <b>или</b> данные Xtream Codes.</p>" +
                (if (done) "<div class=ok>✓ Отправлено на телевизор. Проверьте данные на экране и нажмите «Сохранить».</div>" else "") +
                "<form method=post><label>Название (необязательно)</label><input name=name autocomplete=off>" +
                "<h2>Ссылка на плейлист M3U</h2><input name=url inputmode=url autocomplete=off autocapitalize=none placeholder='http://…'>" +
                "<h2>или Xtream Codes</h2><label>Адрес сервера</label>" +
                "<input name=server inputmode=url autocomplete=off autocapitalize=none placeholder='http://example.com:8080'>" +
                "<label>Логин</label><input name=user autocomplete=off autocapitalize=none>" +
                "<label>Пароль</label><input name=pass type=password autocomplete=off>" +
                "<h2>Телепрограмма XMLTV (необязательно)</h2>" +
                "<input name=epg inputmode=url autocomplete=off autocapitalize=none placeholder='http://…/epg.xml.gz'>" +
                "<button>Отправить на телевизор</button></form>" +
                "<p>Данные идут только по вашей домашней сети прямо на приставку.</p></body></html>"
        }
    }
}
