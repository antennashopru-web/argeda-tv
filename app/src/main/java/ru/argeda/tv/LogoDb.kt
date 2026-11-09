package ru.argeda.tv

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.regex.Pattern
import java.util.zip.GZIPInputStream

/**
 * Логотипы для каналов, у которых в плейлисте нет tvg-logo: подбор по названию по открытой базе iptv-org.
 * База (ключ названия → ссылка на картинку) собирается workflow «Логотипы» в ветку data
 * скриптом tools/logos.py; приложение скачивает её раз в неделю и держит только нужные строки.
 */
internal object LogoDb {
    const val URL = "https://raw.githubusercontent.com/antennashopru-web/argeda-tv/data/logos.tsv.gz"
    private const val TTL = 7 * 86_400_000L
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val NOISE = Pattern.compile("(?i)(\\(.*?\\)|\\[.*?\\]|\\b(hd|fhd|uhd|sd|hq|hdr|4k|8k|hevc|h\\.?26[45]|1080[pi]?|720p|50\\s?fps|orig|original)\\b)")
    private val SHIFT = Pattern.compile("\\s[+-]\\d+\\b")

    /** Код страны или частота кадров в конце названия: «TLC RO», «Liberty Кино UKR», «РБК HD 50». */
    private val COUNTRY = Pattern.compile("\\s([A-Z]{2,3}|50|60)$")

    /** Ключ без «HD», сдвига «+2», пометок в скобках и кода страны. Та же логика — в tools/logos.py. */
    @JvmStatic
    fun key(name: String): String = key(name, true)

    /** shift=false — оставить «+2»: у таких каналов своя программа со сдвигом по времени. */
    @JvmStatic
    fun key(name: String, shift: Boolean): String {
        var s = NOISE.matcher(name).replaceAll(" ")
        if (shift) s = SHIFT.matcher(s).replaceAll(" ")
        repeat(3) { s = COUNTRY.matcher(s.trim()).replaceAll("") }
        return Epg.norm(s)
    }

    /** «матчтв» → «матч»: провайдеры часто дописывают «ТВ», а в базе его нет. */
    @JvmStatic
    fun noTv(k: String): String {
        for (t in arrayOf("tv", "тв")) if (k.endsWith(t) && k.length > t.length + 1) return k.substring(0, k.length - t.length)
        return k
    }

    /** Подставляет логотипы в фоне; done вызывается на главном потоке, если что-то изменилось. */
    @JvmStatic
    fun apply(ctx: Context, chs: List<Channel>, done: Runnable) {
        val need = HashMap<Channel, Array<String>>()
        val wanted = HashSet<String>()
        for (c in chs) {
            if (c.logo.isNotEmpty()) continue
            val kk = key(c.name)
            val k = arrayOf(Epg.norm(c.name), kk, noTv(kk))
            need[c] = k
            wanted.addAll(k)
        }
        if (need.isEmpty()) return
        val f = File(ctx.filesDir, "logos.tsv.gz")
        bg.execute {
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > TTL) {
                val tmp = File(f.path + ".part")
                try {
                    Net.download(URL, null, tmp, 32L shl 20)
                    if (!tmp.renameTo(f)) tmp.delete()
                } catch (_: Exception) {
                    tmp.delete() // нет сети — работаем со старой копией, если она есть
                }
            }
            if (!f.exists()) return@execute
            val found = HashMap<String, String>()
            try {
                GZIPInputStream(FileInputStream(f), 65536).bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (line in lines) {
                        val t = line.indexOf('\t')
                        if (t > 0 && wanted.contains(line.substring(0, t))) found[line.substring(0, t)] = line.substring(t + 1)
                    }
                }
            } catch (_: Exception) {
                f.delete()
                return@execute
            }
            if (found.isEmpty()) return@execute
            main.post {
                var changed = false
                for ((c, keys) in need) {
                    if (c.logo.isNotEmpty()) continue
                    val u = keys.firstNotNullOfOrNull { found[it] } ?: continue
                    c.logo = u
                    c.logoGuessed = true
                    changed = true
                }
                if (changed) done.run()
            }
        }
    }
}
