package ru.argeda.tv

import java.util.Locale

/** Канал из плейлиста пользователя. */
internal class Channel {
    @JvmField var name = ""
    @JvmField var url = ""
    @JvmField var group = ""
    @JvmField var logo = ""
    @JvmField var tvgId = ""
    @JvmField var tvgName = ""
    @JvmField var userAgent = ""
    @JvmField var referrer = ""

    /** Архив: тип (catchup), шаблон ссылки (catchup-source) и глубина в днях. */
    @JvmField var catchupType = ""
    @JvmField var catchupSource = ""
    @JvmField var catchupDays = 0

    /** Номер по порядку в плейлисте, начиная с 1. */
    @JvmField var num = 0
    @JvmField var adult = false

    /** Логотип подобран по названию (LogoDb), а не задан в плейлисте: телепрограмма может его заменить. */
    @JvmField var logoGuessed = false

    /** Телепрограмма канала, если она найдена в XMLTV. */
    @JvmField var progs: List<Epg.Prog>? = null

    /** Ключ для избранного и недавних: не зависит от ссылки, в которой может меняться токен. */
    fun key(): String = name.trim().lowercase(Locale.ROOT)

    fun groupTitle(): String = groupTitle(group)

    /** Буквы-заглушка вместо логотипа: «Первый канал» → «ПК», «НТВ» → «НТВ». */
    fun initials(): String {
        val words = name.trim().split(WORD_SPLIT).filter { it.isNotEmpty() }
        val a = words.getOrNull(0) ?: return ""
        val b = words.getOrNull(1)
        val r = if (b == null) a.take(3) else "${a[0]}${b[0]}"
        return r.uppercase(Locale.getDefault())
    }

    companion object {
        private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}]+")

        @JvmStatic
        fun groupTitle(g: String?): String {
            if (g.isNullOrEmpty()) return "Без группы"
            val ch = g[0]
            // Заглавную делаем только кириллице и латинице: в грузинском, армянском и др. так не принято.
            val cap = ch in 'a'..'z' || ch in 'а'..'я' || ch == 'ё'
            return if (cap) ch.uppercaseChar() + g.substring(1) else g
        }
    }
}
