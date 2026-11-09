package ru.argeda.tv

import java.util.Locale

/** Категории для списка каналов в плеере: тот же порядок, что на главном экране. */
internal object Groups {
    const val ALL = "\u0001all"
    const val FAV = "\u0001fav"
    const val REC = "\u0001rec"
    const val FOLDER = "\u0001f:"

    class G(@JvmField val id: String, @JvmField val title: String, @JvmField val count: Int)

    @JvmStatic
    fun list(st: Store, vis: List<Channel>): List<G> {
        val m = LinkedHashMap<String, Int>()
        var favN = 0
        for (c in vis) {
            m[c.group] = (m[c.group] ?: 0) + 1
            if (st.isFav(c)) favN++
        }
        val r = ArrayList<G>()
        if (favN > 0) r.add(G(FAV, "★  Избранное", favN))
        for (f in st.folders) {
            val n = ordered(f.keys, vis).size
            if (n > 0) r.add(G(FOLDER + f.name, "☆  " + f.name, n))
        }
        for (g in st.favGroups) {
            val n = m[g] ?: continue
            r.add(G(g, "▸  " + Channel.groupTitle(g), n))
        }
        val recN = ordered(st.recent, vis).size
        if (recN > 0) r.add(G(REC, "Недавние", recN))
        r.add(G(ALL, "Все каналы", vis.size))
        for ((k, v) in m) if (!st.isFavGroup(k)) r.add(G(k, Channel.groupTitle(k), v))
        return r
    }

    @JvmStatic
    fun channels(st: Store, id: String, vis: List<Channel>): List<Channel> = when {
        id == ALL -> vis
        id == REC -> ordered(st.recent, vis)
        id == FAV -> ordered(st.favOrder, vis)
        id.startsWith(FOLDER) -> st.folder(id.substring(FOLDER.length))?.let { ordered(it.keys, vis) } ?: ArrayList()
        else -> vis.filterTo(ArrayList()) { it.group == id }
    }

    @JvmStatic
    fun title(id: String): String = when {
        id == ALL -> "Все каналы"
        id == FAV -> "Избранное"
        id == REC -> "Недавние"
        id.startsWith(FOLDER) -> id.substring(FOLDER.length)
        else -> Channel.groupTitle(id)
    }

    @JvmStatic
    fun search(query: String, vis: List<Channel>): List<Channel> {
        val q = norm(query)
        if (q.isEmpty()) return ArrayList()
        return vis.filterTo(ArrayList()) { norm(it.name).contains(q) || it.num.toString() == q }
    }

    @JvmStatic
    fun norm(s: String): String = s.trim().lowercase(Locale.getDefault()).replace('ё', 'е')

    private fun ordered(keys: List<String>, vis: List<Channel>): List<Channel> {
        val r = ArrayList<Channel>()
        if (keys.isEmpty()) return r
        val m = HashMap<String, Channel>()
        for (c in vis) m.putIfAbsent(c.key(), c)
        for (k in keys) m[k]?.let { r.add(it) }
        return r
    }
}
