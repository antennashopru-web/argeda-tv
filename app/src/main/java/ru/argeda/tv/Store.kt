package ru.argeda.tv

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.Executors
import javax.net.ssl.SSLException

/** Плейлисты, каналы, избранное и настройки. Всё хранится только на устройстве. */
internal class Store private constructor(@JvmField val app: Context) {
    fun interface Listener {
        fun changed()
    }

    /** Своя папка в избранном («Кино», «Детям»…): название и каналы в порядке добавления. */
    class Folder(@JvmField var name: String) {
        @JvmField val keys: MutableList<String> = ArrayList()
    }

    private val p: SharedPreferences = app.getSharedPreferences("argeda", Context.MODE_PRIVATE)
    @JvmField val sources: MutableList<Source> = ArrayList()
    private var curId: String

    @JvmField var all: List<Channel> = ArrayList()
    private var visibleCache: List<Channel>? = null
    @JvmField var loadedId: String? = null
    @JvmField var loadingId: String? = null
    private var headerEpg = ""

    /** Часовой пояс сервера Xtream (для ссылок на архив). */
    @JvmField var headerTz = ""
    @JvmField var busy = false

    /** Текст ошибки последней загрузки (пусто — всё хорошо) и её порядковый номер. */
    @JvmField var status = ""
    @JvmField var statusSeq = 0
    private var gen = 0

    private val fav = HashSet<String>()

    /** Избранное в порядке, который задал зритель (новые — в конец). */
    @JvmField val favOrder: MutableList<String> = ArrayList()

    /** Скрытые каналы и категории: в списках их нет, вернуть можно в настройках. */
    @JvmField val hiddenCh: MutableSet<String> = HashSet()
    @JvmField val hiddenGroups: MutableSet<String> = HashSet()

    /** Настройки под PIN-кодом открыты до выхода из приложения. */
    @JvmField var settingsOpen = false

    /** Избранные категории (группы плейлиста) в порядке добавления: их показывают наверху списка групп. */
    @JvmField val favGroups: MutableList<String> = ArrayList()

    @JvmField val folders: MutableList<Folder> = ArrayList()
    @JvmField val recent: MutableList<String> = ArrayList()
    @JvmField var lastKey: String?

    /** Каналы 18+ открываются PIN-кодом до выхода из приложения. */
    @JvmField var showAdult = false

    @JvmField var playList: List<Channel>? = null
    @JvmField var playIndex = 0
    @JvmField var playTitle = "Все каналы"

    @JvmField var listener: Listener? = null

    private var epg: Map<String, List<Epg.Prog>>? = null
    private var epgIcons: Map<String, String>? = null
    private var epgId: String? = null
    private var epgBusy = false
    @JvmField var epgStatus = ""

    private val bg = Executors.newSingleThreadExecutor()
    private val epgBg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    init {
        jsonArray("sources") { a -> for (i in 0 until a.length()) sources.add(Source.from(a.getJSONObject(i))) }
        curId = p.getString("cur", "") ?: ""
        fav.addAll(p.getStringSet("fav", HashSet()) ?: emptySet())
        jsonArray("favo") { a ->
            for (i in 0 until a.length()) {
                val k = a.getString(i)
                if (fav.contains(k) && !favOrder.contains(k)) favOrder.add(k)
            }
        }
        for (k in fav) if (!favOrder.contains(k)) favOrder.add(k)
        hiddenCh.addAll(p.getStringSet("hidden", HashSet()) ?: emptySet())
        hiddenGroups.addAll(p.getStringSet("hiddenG", HashSet()) ?: emptySet())
        jsonArray("favg") { a -> for (i in 0 until a.length()) favGroups.add(a.getString(i)) }
        jsonArray("recent") { a -> for (i in 0 until a.length()) recent.add(a.getString(i)) }
        lastKey = p.getString("last", "")
        jsonArray("folders") { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val f = Folder(o.optString("n"))
                val k = o.optJSONArray("k")
                if (k != null) for (j in 0 until k.length()) f.keys.add(k.getString(j))
                if (f.name.isNotEmpty()) folders.add(f)
            }
        }
    }

    private inline fun jsonArray(key: String, read: (JSONArray) -> Unit) {
        try {
            read(JSONArray(p.getString(key, "[]")))
        } catch (_: JSONException) {
        }
    }

    // ---------- Источники ----------

    fun current(): Source? = sources.firstOrNull { it.id == curId } ?: sources.firstOrNull()

    fun byId(id: String?): Source? = if (id == null) null else sources.firstOrNull { it.id == id }

    fun select(s: Source) {
        curId = s.id
        p.edit().putString("cur", curId).apply()
    }

    fun putSource(s: Source) {
        val old = byId(s.id)
        if (old == null) sources.add(s) else sources[sources.indexOf(old)] = s
        curId = s.id
        saveSources()
        resetLoaded()
        if (s.id == epgId) {
            epg = null
            epgId = null
        }
    }

    fun removeSource(s: Source) {
        sources.remove(s)
        delete(cacheFile(s))
        for (i in 0 until 5) delete(epgFile(s, i))
        if (s.id == curId) curId = if (sources.isEmpty()) "" else sources[0].id
        saveSources()
        resetLoaded()
    }

    private fun resetLoaded() {
        gen++
        busy = false
        loadedId = null
        loadingId = null
        all = ArrayList()
        visibleCache = null
        status = ""
    }

    private fun saveSources() {
        val a = JSONArray()
        try {
            for (s in sources) a.put(s.toJson())
        } catch (_: JSONException) {
        }
        p.edit().putString("sources", a.toString()).putString("cur", curId).apply()
    }

    fun cacheFile(s: Source) = File(app.filesDir, "pl_" + s.id + ".m3u")

    fun epgFile(s: Source) = File(app.filesDir, "epg_" + s.id + ".xml")

    // ---------- Загрузка плейлиста ----------

    /** Сначала показывает сохранённую копию, затем при необходимости скачивает свежий плейлист. */
    fun load(force: Boolean) {
        val s = current()
        if (s == null) {
            resetLoaded()
            notifyChanged()
            return
        }
        val showing = s.id == loadedId
        if (!showing) {
            all = ArrayList()
            visibleCache = null
        }
        val g = ++gen
        busy = true
        loadingId = s.id
        notifyChanged()
        bg.execute {
            val f = cacheFile(s)
            var text = if (f.exists()) readFile(f) else null
            var err = ""
            if (text != null && !showing && (!force || s.type == "file")) deliver(g, s, text)
            val stale = text == null || System.currentTimeMillis() - f.lastModified() > PLAYLIST_TTL
            if (s.type != "file" && (force || stale)) {
                try {
                    val t = fetch(s)
                    writeFile(f, t)
                    if (t != text || (force && !showing)) deliver(g, s, t)
                    text = t
                } catch (e: Exception) {
                    err = describe(e)
                    if (text != null && force && !showing) deliver(g, s, text)
                }
            } else if (text == null) {
                err = if (s.type == "file") "Файл плейлиста не найден — выберите его снова" else "Плейлист не загружен"
            }
            val fe = err
            main.post {
                if (g != gen) return@post
                busy = false
                loadingId = null
                status = fe
                statusSeq++
                notifyChanged()
                loadEpg(s, false)
            }
        }
    }

    /** Для экрана просмотра, если система закрыла приложение в фоне. */
    fun loadSync() {
        val s = current() ?: return
        val t = readFile(cacheFile(s)) ?: return
        val r = M3u.parse(t)
        all = r.list
        visibleCache = null
        headerEpg = r.epg
        headerTz = r.tz
        loadedId = s.id
        val list = all
        LogoDb.apply(app, list) { if (all === list) notifyChanged() }
    }

    private fun deliver(g: Int, s: Source, text: String) {
        val r = M3u.parse(text)
        main.post {
            if (g != gen) return@post
            all = r.list
            visibleCache = null
            headerEpg = r.epg
            headerTz = r.tz
            loadedId = s.id
            val e = epg
            if (e != null && s.id == epgId) Epg.attach(all, e, epgIcons)
            val list = all
            LogoDb.apply(app, list) { if (all === list) notifyChanged() }
            notifyChanged()
        }
    }

    // ---------- Телепрограмма ----------

    fun epgUrl(s: Source?): String = epgUrls(s).firstOrNull() ?: ""

    /**
     * Адреса телепрограммы: свои из настроек плейлиста или url-tvg (их может быть несколько через запятую,
     * пробел или с новой строки). Программа берётся из первого адреса, где канал нашёлся.
     */
    fun epgUrls(s: Source?): List<String> {
        val r = ArrayList<String>()
        if (s == null) return r
        val u = if (s.epg.isNotBlank()) s.epg.trim() else headerEpg
        for (raw in u.split(Regex("[,;\\s]+"))) {
            val x = raw.trim()
            if ((x.startsWith("http://") || x.startsWith("https://")) && !r.contains(x) && r.size < 5) r.add(x)
        }
        if (r.isEmpty()) r.add(DEFAULT_EPG)
        return r
    }

    fun epgFile(s: Source, i: Int): File = if (i == 0) epgFile(s) else File(app.filesDir, "epg_" + s.id + "_" + i + ".xml")

    fun epgLoading(): Boolean = epgBusy

    fun hasEpg(): Boolean = epg != null && epgId != null && epgId == loadedId

    fun loadEpg(s: Source?, force: Boolean) {
        if (s == null || s.id != loadedId || epgBusy) return
        val urls = epgUrls(s)
        if (urls.isEmpty()) return
        if (!force && epg != null && s.id == epgId) return
        epgBusy = true
        val wanted = HashSet<String>()
        var days = 0
        for (c in all) {
            Epg.keys(c, wanted)
            days = maxOf(days, Catchup.days(c, s))
        }
        val past = maxOf(6 * 3600_000L, minOf(days, 7) * 86_400_000L)
        epgBg.execute {
            var err = ""
            var m: MutableMap<String, List<Epg.Prog>>? = null
            val icons = HashMap<String, String>()
            for (i in urls.indices) {
                val f = epgFile(s, i)
                try {
                    if (force || !f.exists() || System.currentTimeMillis() - f.lastModified() > EPG_TTL) {
                        val tmp = File(f.path + ".part")
                        try {
                            Net.download(urls[i], s.ua, tmp, 400L shl 20)
                            if (!tmp.renameTo(f)) throw IOException("Не удалось сохранить телепрограмму")
                        } catch (e: Exception) {
                            delete(tmp)
                            if (!f.exists()) throw e
                            err = describe(e)
                        }
                    }
                    val one = Epg.parse(f, wanted, past, icons)
                    val cur = m
                    if (cur == null) m = HashMap(one)
                    else for ((k, v) in one) cur.putIfAbsent(k, v)
                } catch (e: Exception) {
                    err = describe(e)
                }
            }
            val fm = m
            val fe = err
            main.post {
                epgBusy = false
                epgStatus = fe
                if (fm != null) {
                    epg = fm
                    epgIcons = icons
                    epgId = s.id
                    if (s.id == loadedId) Epg.attach(all, fm, icons)
                }
                notifyChanged()
            }
        }
    }

    // ---------- Каналы ----------

    fun visible(): List<Channel> {
        visibleCache?.let { return it }
        val v = if (showAdult && hiddenCh.isEmpty() && hiddenGroups.isEmpty()) all
        else all.filterTo(ArrayList(all.size)) { (showAdult || !it.adult) && !isHidden(it) }
        visibleCache = v
        return v
    }

    fun adultCount(): Int = all.count { it.adult }

    fun setShowAdult(b: Boolean) {
        showAdult = b
        visibleCache = null
    }

    fun isFav(c: Channel): Boolean = fav.contains(c.key())

    fun toggleFav(c: Channel): Boolean {
        val k = c.key()
        val on = !fav.remove(k)
        favOrder.remove(k)
        if (on) {
            fav.add(k)
            favOrder.add(k)
        }
        saveFav()
        return on
    }

    private fun saveFav() {
        p.edit().putStringSet("fav", HashSet(fav)).putString("favo", JSONArray(favOrder).toString()).apply()
    }

    /** Сдвигает канал в избранном (folder == null) или в папке на одно место; false — дальше некуда. */
    fun move(f: Folder?, c: Channel, dir: Int): Boolean {
        val l = f?.keys ?: favOrder
        val i = l.indexOf(c.key())
        val j = i + dir
        if (i < 0 || j < 0 || j >= l.size) return false
        l[i] = l[j]
        l[j] = c.key()
        if (f == null) saveFav() else saveFolders()
        return true
    }

    // ---------- Скрытые каналы и категории ----------

    fun isHidden(c: Channel): Boolean = hiddenCh.contains(c.key()) || hiddenGroups.contains(c.group)

    fun setHidden(c: Channel, on: Boolean) {
        if (on) hiddenCh.add(c.key()) else hiddenCh.remove(c.key())
        visibleCache = null
        p.edit().putStringSet("hidden", HashSet(hiddenCh)).apply()
    }

    fun unhide(key: String) {
        hiddenCh.remove(key)
        visibleCache = null
        p.edit().putStringSet("hidden", HashSet(hiddenCh)).apply()
    }

    fun setHiddenGroup(g: String, on: Boolean) {
        if (on) hiddenGroups.add(g) else hiddenGroups.remove(g)
        visibleCache = null
        p.edit().putStringSet("hiddenG", HashSet(hiddenGroups)).apply()
    }

    /** Название канала по ключу (для списка скрытых); null — в текущем плейлисте его нет. */
    fun byKey(k: String): Channel? = all.firstOrNull { it.key() == k }

    // ---------- PIN на настройки ----------

    fun settingsPin(): Boolean = p.getBoolean("setpin", false)

    fun setSettingsPin(b: Boolean) {
        p.edit().putBoolean("setpin", b).apply()
    }

    // ---------- Резервная копия ----------

    @Throws(JSONException::class)
    fun exportJson(): String {
        val o = JSONObject()
        o.put("argeda", 1)
        val v = JSONObject()
        for ((k, x) in p.all) {
            if (NOT_BACKED.contains(k) || x == null) continue
            if (x is Set<*>) {
                val a = JSONArray()
                for (s in x) a.put(s)
                v.put(k, JSONObject().put("set", a))
            } else v.put(k, x)
        }
        o.put("prefs", v)
        return o.toString(1)
    }

    /** Заменяет настройки копией. Нужен перезапуск экрана: всё перечитывается заново. */
    @Throws(JSONException::class)
    fun importJson(text: String) {
        val o = JSONObject(text)
        if (o.optInt("argeda") != 1) throw JSONException("Это не копия настроек ARGEDA TV")
        val v = o.getJSONObject("prefs")
        val ed = p.edit().clear()
        val it = v.keys()
        while (it.hasNext()) {
            val k = it.next()
            val x = v.get(k)
            when {
                x is JSONObject && x.has("set") -> {
                    val a = x.getJSONArray("set")
                    val s = HashSet<String>()
                    for (i in 0 until a.length()) s.add(a.getString(i))
                    ed.putStringSet(k, s)
                }
                x is Boolean -> ed.putBoolean(k, x)
                x is Int -> ed.putInt(k, x)
                x is Long -> ed.putLong(k, x)
                x is Double -> ed.putFloat(k, x.toFloat())
                else -> ed.putString(k, x.toString())
            }
        }
        ed.commit()
        synchronized(Store::class.java) {
            inst = null // при следующем Store.get всё прочитается из восстановленных настроек
        }
    }

    fun isFavGroup(g: String): Boolean = favGroups.contains(g)

    fun toggleFavGroup(g: String): Boolean {
        val on = !favGroups.remove(g)
        if (on) favGroups.add(g)
        p.edit().putString("favg", JSONArray(favGroups).toString()).apply()
        return on
    }

    // ---------- Папки избранного ----------

    fun folder(name: String): Folder? = folders.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Создаёт папку; null — если такая уже есть или название пустое. */
    fun addFolder(raw: String): Folder? {
        val name = raw.trim()
        if (name.isEmpty() || folder(name) != null) return null
        val f = Folder(name)
        folders.add(f)
        saveFolders()
        return f
    }

    fun renameFolder(f: Folder, raw: String): Boolean {
        val name = raw.trim()
        val same = folder(name)
        if (name.isEmpty() || (same != null && same !== f)) return false
        f.name = name
        saveFolders()
        return true
    }

    fun removeFolder(f: Folder) {
        folders.remove(f)
        saveFolders()
    }

    fun inFolder(f: Folder, c: Channel): Boolean = f.keys.contains(c.key())

    fun toggleInFolder(f: Folder, c: Channel): Boolean {
        val k = c.key()
        val on = !f.keys.remove(k)
        if (on) f.keys.add(k)
        saveFolders()
        return on
    }

    private fun saveFolders() {
        val a = JSONArray()
        try {
            for (f in folders) a.put(JSONObject().put("n", f.name).put("k", JSONArray(f.keys)))
        } catch (_: JSONException) {
        }
        p.edit().putString("folders", a.toString()).apply()
    }

    fun played(c: Channel) {
        val k = c.key()
        lastKey = k
        recent.remove(k)
        recent.add(0, k)
        while (recent.size > 30) recent.removeAt(recent.size - 1)
        p.edit().putString("recent", JSONArray(recent).toString()).putString("last", k).apply()
    }

    fun preview(): Boolean = p.getBoolean("preview", true)

    fun setPreview(b: Boolean) {
        p.edit().putBoolean("preview", b).apply()
    }

    /** Заставка с анимацией логотипа и голосом при запуске. */
    fun splash(): Boolean = p.getBoolean("splash", true)

    fun setSplash(b: Boolean) {
        p.edit().putBoolean("splash", b).apply()
    }

    fun fit(): Int = p.getInt("fit", 0)

    fun setFit(f: Int) {
        p.edit().putInt("fit", f).apply()
    }

    // ---------- PIN-код ----------

    fun hasPin(): Boolean = !p.getString("pin", "").isNullOrEmpty()

    fun checkPin(pin: String): Boolean = hash(pin) == p.getString("pin", "")

    fun setPin(pin: String) {
        p.edit().putString("pin", hash(pin)).apply()
    }

    // ---------- Служебное ----------

    private fun notifyChanged() {
        listener?.changed()
    }

    companion object {
        /**
         * Телепрограмма для плейлистов, где её нет: бесплатная iptvx.one, ~4300 каналов (РФ, СНГ, Европа),
         * облегчённая версия без описаний — 15 МБ вместо 70. Каналы сопоставляются по названию.
         */
        const val DEFAULT_EPG = "https://iptvx.one/EPG_LITE"
        private const val PLAYLIST_TTL = 6 * 3600_000L
        private const val EPG_TTL = 12 * 3600_000L

        /** Что не копируется: служебные отметки и то, что зависит от устройства. */
        private val NOT_BACKED = setOf("bw", "updAt")

        private var inst: Store? = null

        @JvmStatic
        fun get(c: Context): Store = synchronized(Store::class.java) {
            inst ?: Store(c.applicationContext).also { inst = it }
        }

        @Throws(Exception::class)
        private fun fetch(s: Source): String {
            if (s.type == "xtream") return Xtream.toM3u(s)
            val t = Net.getText(s.url, s.ua, 64 shl 20)
            if (!t.contains("#EXTINF") && !t.contains("#EXTM3U")) throw IOException("По ссылке нет плейлиста M3U")
            return t
        }

        private fun hash(s: String): String = try {
            val d = MessageDigest.getInstance("SHA-256").digest(("argeda-tv:$s").toByteArray(Charsets.UTF_8))
            d.joinToString("") { String.format("%02x", it) }
        } catch (_: Exception) {
            s
        }

        @JvmStatic
        fun describe(e: Exception): String {
            if (e is Net.HttpError) return e.message ?: ""
            if (e is UnknownHostException) return "Сервер не найден. Проверьте адрес и интернет"
            if (e is SocketTimeoutException) return "Сервер не отвечает"
            if (e is ConnectException) return "Не удалось подключиться к серверу"
            if (e is SSLException) return "Ошибка защищённого соединения"
            if (e is JSONException) return "Сервер Xtream вернул неожиданный ответ"
            val m = e.message
            return if (m.isNullOrEmpty()) e.javaClass.simpleName else m
        }

        @JvmStatic
        fun readFile(f: File): String? {
            if (!f.exists()) return null
            return try {
                FileInputStream(f).use { Net.text(Net.readAll(it, 128 shl 20)) }
            } catch (_: IOException) {
                null
            }
        }

        @JvmStatic
        @Throws(IOException::class)
        fun writeFile(f: File, s: String) {
            val tmp = File(f.path + ".tmp")
            FileOutputStream(tmp).use { it.write(s.toByteArray(Charsets.UTF_8)) }
            if (!tmp.renameTo(f)) throw IOException("Не удалось сохранить плейлист")
        }

        @JvmStatic
        fun delete(f: File) {
            f.delete()
        }
    }
}
