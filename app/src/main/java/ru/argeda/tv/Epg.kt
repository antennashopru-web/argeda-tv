package ru.argeda.tv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/**
 * Телепрограмма XMLTV (.xml или .xml.gz). Хранятся только каналы из плейлиста и только окно
 * «глубина архива назад … 2 суток вперёд»; описания — для ближайших суток.
 */
internal object Epg {
    class Prog(@JvmField val start: Long, @JvmField var stop: Long, @JvmField val title: String) {
        @JvmField var desc: String? = null
    }

    private val NOISE = Pattern.compile("(?i)(\\(.*?\\)|\\[.*?\\]|\\b(hd|fhd|uhd|sd|hq|4k|8k|hevc|h\\.?26[45]|1080[pi]?|720p|50\\s?fps|orig|original)\\b)")

    /** «НТВ +4 (Томск)», «ITV4 +1 UK»: сдвиг по времени относительно основного канала. */
    private val SHIFTED = Pattern.compile("^(.*\\S)\\s*\\+(\\d{1,2})\\b(.*)$")
    private val CYR = Pattern.compile("[\\u0400-\\u04FF]")

    /** Ключи для сопоставления канала: tvg-id и название без «HD» и пометок. */
    @JvmStatic
    fun keys(c: Channel, out: MutableSet<String>) {
        if (c.tvgId.isNotEmpty()) out.add("id:" + c.tvgId.lowercase(Locale.ROOT))
        for (n in arrayOf(c.tvgName, c.name)) {
            if (n.isEmpty()) continue
            out.addAll(names(n))
            base(n)?.let { out.addAll(names(it)) }
        }
    }

    /** Варианты названия от точного к вольному: «Матч ТВ HD RU» → матчтвhdru, матчтвru, матчтв, матч. */
    private fun names(n: String): Array<String> {
        val l = LogoDb.key(n, false)
        return arrayOf(norm(n), norm(strip(n)), l, LogoDb.noTv(l))
    }

    /** Название основного канала для «Звезда +4 (Алтай)» → «Звезда (Алтай)»; null — сдвига нет. */
    private fun base(n: String): String? {
        val m = SHIFTED.matcher(n.trim())
        return if (m.matches()) m.group(1) + m.group(3) else null
    }

    /**
     * Насколько сдвинута программа «+N»-канала. Российские дубли («Первый +4») идут для восточных
     * часовых поясов и выходят на N часов раньше Москвы; зарубежные («ITV4 +1») — на N часов позже.
     */
    private fun shift(n: String): Long {
        val m = SHIFTED.matcher(n.trim())
        if (!m.matches()) return 0
        val h = m.group(2)!!.toLong() * 3600_000L
        return if (CYR.matcher(n).find()) -h else h
    }

    /**
     * @param wanted ключи каналов плейлиста (null — брать все)
     * @param past   сколько миллисекунд прошлого хранить (для архива)
     */
    @JvmStatic
    @Throws(IOException::class)
    fun parse(f: File, wanted: Set<String>?, past: Long, icons: MutableMap<String, String>?): Map<String, List<Prog>> {
        val now = System.currentTimeMillis()
        val from = now - past
        val to = now + 48 * 3600_000L
        val descFrom = now - 6 * 3600_000L
        val descTo = now + 24 * 3600_000L
        val accepted = HashSet<String>()
        var channelsSeen = 0
        val names = HashMap<String, List<String>>()
        val progs = HashMap<String, MutableList<Prog>>()
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        try {
            open(f).use { input ->
                val x = Xml.newPullParser()
                try {
                    x.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
                } catch (_: XmlPullParserException) {
                }
                x.setInput(input, null)
                var chId: String? = null
                var chIcon: String? = null
                var pCh: String? = null
                var title: String? = null
                var desc: String? = null
                var chNames: MutableList<String>? = null
                var ps = 0L
                var pe = 0L
                try {
                    var ev = x.next()
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG) {
                            val t = x.name
                            if (t == "channel") {
                                chId = x.getAttributeValue(null, "id")
                                chIcon = null
                                chNames = ArrayList()
                            } else if (t == "icon" && chNames != null && chIcon == null) {
                                val src = x.getAttributeValue(null, "src")
                                if (src != null && (src.startsWith("http://") || src.startsWith("https://"))) chIcon = src.trim()
                            } else if (t == "display-name" && chNames != null) {
                                chNames.add(x.nextText().trim())
                            } else if (t == "programme") {
                                pCh = x.getAttributeValue(null, "channel")
                                ps = time(x.getAttributeValue(null, "start"), cal)
                                pe = time(x.getAttributeValue(null, "stop"), cal)
                                title = null
                                desc = null
                                if (pCh == null || ps == 0L || ps > to || (pe != 0L && pe < from) ||
                                    (channelsSeen > 0 && wanted != null && !accepted.contains(pCh))
                                ) {
                                    pCh = null
                                    skip(x)
                                }
                            } else if (t == "title" && pCh != null && title == null) {
                                title = x.nextText().trim()
                            } else if (t == "desc" && pCh != null && desc == null && ps >= descFrom && ps <= descTo) {
                                var d = x.nextText().trim()
                                if (d.length > 400) d = d.substring(0, 400) + "…"
                                desc = d
                            }
                        } else if (ev == XmlPullParser.END_TAG) {
                            val t = x.name
                            if (t == "channel") {
                                channelsSeen++
                                val id = chId
                                val ns = chNames
                                if (id != null && ns != null) {
                                    names[id] = ns
                                    if (wanted == null || matches(id, ns, wanted)) {
                                        accepted.add(id)
                                        val ic = chIcon
                                        if (ic != null && icons != null) {
                                            icons["id:" + id.lowercase(Locale.ROOT)] = ic
                                            for (n in ns) for (k in names(n)) if (k.isNotEmpty() && !icons.containsKey(k)) icons[k] = ic
                                        }
                                    }
                                }
                                chId = null
                                chNames = null
                            } else if (t == "programme" && pCh != null) {
                                val pr = Prog(ps, pe, title ?: "")
                                pr.desc = desc
                                progs.getOrPut(pCh) { ArrayList() }.add(pr)
                                pCh = null
                            }
                        }
                        ev = x.next()
                    }
                } catch (_: XmlPullParserException) {
                    // Битый конец файла: оставляем то, что успели прочитать.
                }
            }
        } catch (_: XmlPullParserException) {
            throw IOException("Телепрограмма не в формате XMLTV")
        }

        val out = HashMap<String, List<Prog>>()
        for ((key, l) in progs) {
            l.sortWith { a, b -> a.start.compareTo(b.start) }
            for (i in l.indices) {
                val p = l[i]
                if (p.stop <= p.start) p.stop = if (i + 1 < l.size) l[i + 1].start else p.start + 3600_000L
            }
            out["id:" + key.lowercase(Locale.ROOT)] = l
            val ns = names[key] ?: continue
            for (n in ns) for (k in names(n)) putIfAbsent(out, k, l)
        }
        return out
    }

    /**
     * Подбирает программу каналам: по tvg-id, затем по названию (без «HD», «UHD» и т. п.).
     * Каналам без tvg-logo в плейлисте отдаёт логотип из телепрограммы (тег icon).
     */
    @JvmStatic
    fun attach(chs: List<Channel>, m: Map<String, List<Prog>>?, icons: Map<String, String>?) {
        if (m == null) return
        for (c in chs) {
            if ((c.logo.isEmpty() || c.logoGuessed) && !icons.isNullOrEmpty()) {
                var ic: String? = null
                if (c.tvgId.isNotEmpty()) ic = icons["id:" + c.tvgId.lowercase(Locale.ROOT)]
                if (ic == null && c.tvgName.isNotEmpty()) ic = icon(icons, c.tvgName)
                if (ic == null) ic = icon(icons, c.name)
                if (ic != null) {
                    c.logo = ic
                    c.logoGuessed = false
                }
            }
            var l: List<Prog>? = null
            if (c.tvgId.isNotEmpty()) l = m["id:" + c.tvgId.lowercase(Locale.ROOT)]
            if (l == null && c.tvgName.isNotEmpty()) l = find(m, c.tvgName)
            if (l == null) l = find(m, c.name)
            if (l == null) {
                // Нет своей программы у «+N»-дубля — берём основной канал и сдвигаем время.
                val src = base(c.name)?.let { find(m, it) }
                if (src != null) l = shifted(src, shift(c.name))
            }
            c.progs = l
        }
    }

    private fun icon(icons: Map<String, String>, name: String): String? {
        for (k in names(name)) icons[k]?.let { return it }
        return base(name)?.let { icon(icons, it) }
    }

    private fun matches(id: String, names: List<String>, wanted: Set<String>): Boolean {
        if (wanted.contains("id:" + id.lowercase(Locale.ROOT))) return true
        for (n in names) for (k in names(n)) if (k.isNotEmpty() && wanted.contains(k)) return true
        return false
    }

    private fun find(m: Map<String, List<Prog>>, name: String): List<Prog>? {
        for (k in names(name)) m[k]?.let { return it }
        return null
    }

    private fun shifted(src: List<Prog>, by: Long): List<Prog> {
        if (by == 0L) return src
        return src.map { p -> Prog(p.start + by, p.stop + by, p.title).also { it.desc = p.desc } }
    }

    @JvmStatic
    fun at(l: List<Prog>?, t: Long): Prog? {
        if (l.isNullOrEmpty()) return null
        var lo = 0
        var hi = l.size - 1
        var f = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (l[mid].start <= t) {
                f = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        if (f < 0) return null
        val p = l[f]
        return if (t < p.stop) p else null
    }

    @JvmStatic
    fun after(l: List<Prog>?, t: Long): Prog? = l?.firstOrNull { it.start > t }

    private fun putIfAbsent(m: MutableMap<String, List<Prog>>, k: String, l: List<Prog>) {
        if (k.isNotEmpty() && !m.containsKey(k)) m[k] = l
    }

    @JvmStatic
    fun norm(s: String): String {
        val b = StringBuilder(s.length)
        for (c in s.lowercase(Locale.ROOT)) {
            val ch = if (c == 'ё') 'е' else c
            if (Character.isLetterOrDigit(ch)) b.append(ch)
        }
        return b.toString()
    }

    private fun strip(s: String): String = NOISE.matcher(s).replaceAll(" ")

    /** «20261107183000 +0300» → миллисекунды UTC. */
    @JvmStatic
    fun time(str: String?, c: Calendar): Long {
        if (str == null) return 0
        val s = str.trim()
        if (s.length < 12) return 0
        return try {
            c.clear()
            val sec = if (s.length >= 14 && Character.isDigit(s[12])) s.substring(12, 14).toInt() else 0
            c.set(
                s.substring(0, 4).toInt(), s.substring(4, 6).toInt() - 1, s.substring(6, 8).toInt(),
                s.substring(8, 10).toInt(), s.substring(10, 12).toInt(), sec
            )
            var t = c.timeInMillis
            val rest = s.substring(minOf(s.length, if (sec > 0 || s.length >= 14) 14 else 12)).trim()
            if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
                val off = (rest.substring(1, 3).toInt() * 60L + rest.substring(3, 5).toInt()) * 60_000L
                t -= if (rest[0] == '+') off else -off
            } else {
                t -= TimeZone.getDefault().getOffset(t)
            }
            t
        } catch (_: RuntimeException) {
            0
        }
    }

    private fun open(f: File): InputStream {
        val input = BufferedInputStream(FileInputStream(f), 65536)
        input.mark(4)
        val a = input.read()
        val b = input.read()
        input.reset()
        return if (a == 0x1f && b == 0x8b) GZIPInputStream(input, 65536) else input
    }

    private fun skip(x: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (x.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }
}
