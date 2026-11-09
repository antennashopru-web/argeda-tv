package ru.argeda.tv

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Ссылки на архив (catchup) для типов, которые встречаются у провайдеров:
 * shift (?utc=…&lutc=…), flussonic (index-START-DUR.m3u8), xc (Xtream Codes /timeshift/),
 * default/append (шаблон catchup-source с {utc}, {lutc}, {duration}, {Y}{m}{d}… и т. п.).
 */
internal object Catchup {
    private val VAR = Pattern.compile("\\$?\\{(\\w+)(?::([^}]*))?\\}")
    private val XC = Pattern.compile("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(\\.\\w+)?(\\?.*)?$")
    private const val DAY = 86_400_000L

    /** Глубина архива в днях (0 — архива нет). */
    @JvmStatic
    fun days(c: Channel, s: Source?): Int {
        if (s != null && s.archive == "none") return 0
        if (c.catchupDays > 0) return minOf(c.catchupDays, 30)
        return if (c.catchupType.isNotEmpty() || c.catchupSource.isNotEmpty()) 3 else 0
    }

    /** Можно ли смотреть с этого момента в записи. */
    @JvmStatic
    fun can(c: Channel, s: Source?, start: Long, now: Long): Boolean {
        val d = days(c, s)
        return d > 0 && start < now - 60_000L && start >= now - d * DAY
    }

    @JvmStatic
    fun mode(c: Channel, s: Source?): String? {
        val m = s?.archive ?: "auto"
        if (m == "none") return null
        if (m != "auto") return m
        val t = c.catchupType.lowercase(Locale.ROOT)
        return when {
            t == "fs" || t.startsWith("flussonic") -> "flussonic"
            t == "xc" -> "xc"
            t == "append" -> if (c.catchupSource.isEmpty()) "shift" else "append"
            t == "shift" || t == "timeshift" -> "shift"
            c.catchupSource.isNotEmpty() -> if (c.catchupSource.contains("://")) "default" else "append"
            s != null && s.type == "xtream" -> "xc"
            else -> "shift"
        }
    }

    /** Ссылка на запись с момента start; end — конец передачи (для типов, где нужна длительность). */
    @JvmStatic
    fun url(c: Channel, s: Source?, tz: String?, start: Long, end: Long, now: Long): String? {
        val m = mode(c, s) ?: return null
        val st = start / 1000
        val nw = now / 1000
        val en = maxOf(end, start + 60_000L) / 1000
        return when (m) {
            "default" -> fill(c.catchupSource, st, en, nw)
            "append" -> c.url + fill(c.catchupSource, st, en, nw)
            "flussonic" -> flussonic(c.url, st, en - st)
            "xc" -> xc(c.url, st, en - st, tz)
            else -> c.url + (if (c.url.contains("?")) "&" else "?") + "utc=" + st + "&lutc=" + nw
        }
    }

    @JvmStatic
    fun fill(tpl: String, st: Long, en: Long, nw: Long): String {
        val m = VAR.matcher(tpl)
        val b = StringBuffer()
        while (m.find()) {
            val k = m.group(1)!!
            val f: String? = m.group(2)
            val v = when (k) {
                "utc", "start" -> if (f != null) fmt(f, st) else st.toString()
                "utcend", "end" -> if (f != null) fmt(f, en) else en.toString()
                "lutc", "now", "timestamp" -> if (f != null) fmt(f, nw) else nw.toString()
                "duration" -> (en - st).toString()
                "offset" -> (nw - st).toString()
                "Y", "m", "d", "H", "M", "S" -> fmt(k, st)
                else -> m.group()
            }
            m.appendReplacement(b, Matcher.quoteReplacement(v))
        }
        m.appendTail(b)
        return b.toString()
    }

    /** Формат в стиле {utc:Y-m-d:H-M}: Y m d H M S → год, месяц, день, часы, минуты, секунды (местное время). */
    private fun fmt(f: String, sec: Long): String {
        val p = StringBuilder()
        for (ch in f) {
            when (ch) {
                'Y' -> p.append("yyyy")
                'm' -> p.append("MM")
                'd' -> p.append("dd")
                'H' -> p.append("HH")
                'M' -> p.append("mm")
                'S' -> p.append("ss")
                else -> if (Character.isLetter(ch)) p.append('\'').append(ch).append('\'') else p.append(ch)
            }
        }
        return SimpleDateFormat(p.toString(), Locale.ROOT).format(Date(sec * 1000))
    }

    @JvmStatic
    fun flussonic(url: String, st: Long, dur: Long): String {
        val q = url.indexOf('?')
        val path = if (q >= 0) url.substring(0, q) else url
        val query = if (q >= 0) url.substring(q) else ""
        val slash = path.lastIndexOf('/')
        val dir = path.substring(0, slash + 1)
        val file = path.substring(slash + 1)
        if (file == "mpegts") return dir + "timeshift_abs-" + st + ".ts" + query
        if (file.endsWith(".ts")) return dir + "timeshift_abs-" + st + ".ts" + query
        if (file.endsWith(".m3u8")) return dir + file.substring(0, file.length - 5) + "-" + st + "-" + dur + ".m3u8" + query
        return path + (if (path.endsWith("/")) "" else "/") + "index-" + st + "-" + dur + ".m3u8" + query
    }

    @JvmStatic
    fun xc(url: String, st: Long, dur: Long, tz: String?): String {
        val m = XC.matcher(url)
        if (!m.matches()) return url + (if (url.contains("?")) "&" else "?") + "utc=" + st + "&lutc=" + (System.currentTimeMillis() / 1000)
        val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.ROOT)
        f.timeZone = if (tz.isNullOrEmpty()) TimeZone.getDefault() else TimeZone.getTimeZone(tz)
        val ext = m.group(5) ?: ".ts"
        return m.group(1) + "/timeshift/" + m.group(2) + "/" + m.group(3) + "/" + maxOf(1L, (dur + 59) / 60) + "/" +
            f.format(Date(st * 1000)) + "/" + m.group(4) + ext + (m.group(6) ?: "")
    }
}
