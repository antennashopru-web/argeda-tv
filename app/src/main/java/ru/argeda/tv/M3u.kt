package ru.argeda.tv

import java.util.Locale
import java.util.regex.Pattern

/** Разбор плейлистов M3U/M3U8: #EXTINF с атрибутами, #EXTGRP, #EXTVLCOPT, заголовки после «|». */
internal object M3u {
    class Result {
        @JvmField val list: MutableList<Channel> = ArrayList()
        @JvmField var epg = ""
        @JvmField var tz = ""
    }

    private val ATTR = Pattern.compile("([A-Za-z0-9_-]+)=(?:\"([^\"]*)\"|'([^']*)'|([^\\s,\"']+))")

    /** Группы и каналы «для взрослых» скрываются, пока их не откроют PIN-кодом. */
    @JvmField
    val ADULT: Pattern = Pattern.compile("(?iu)(взросл|эрот|порно|xxx|porn|adult|18\\s*\\+)")

    @JvmStatic
    fun parse(text: String?): Result {
        val r = Result()
        if (text == null) return r
        var cur: Channel? = null
        var sticky = ""
        var defType = ""
        var defSource = ""
        var defDays = 0
        val n = text.length
        var i = if (n > 0 && text[0] == '﻿') 1 else 0
        while (i < n) {
            var e = text.indexOf('\n', i)
            if (e < 0) e = n
            val line = text.substring(i, e).trim()
            i = e + 1
            if (line.isEmpty()) continue
            if (line[0] == '#') {
                val up = line.substring(0, minOf(12, line.length)).uppercase(Locale.ROOT)
                when {
                    up.startsWith("#EXTM3U") -> {
                        val a = attrs(line)
                        val g = first(a, "url-tvg", "x-tvg-url", "tvg-url")
                        if (g.isNotEmpty() && r.epg.isEmpty()) r.epg = g
                        defType = first(a, "catchup", "catchup-type")
                        defSource = first(a, "catchup-source")
                        defDays = toInt(first(a, "catchup-days", "tvg-rec", "timeshift"))
                        r.tz = first(a, "x-timezone")
                    }
                    up.startsWith("#EXTINF") -> cur = extinf(line)
                    up.startsWith("#EXTGRP:") -> {
                        sticky = line.substring(8).trim()
                        if (cur != null && cur.group.isEmpty()) cur.group = sticky
                    }
                    up.startsWith("#EXTVLCOPT:") -> if (cur != null) vlcopt(cur, line.substring(11).trim())
                }
                continue
            }
            val c = cur ?: Channel()
            var url = line
            val bar = url.indexOf('|')
            if (bar > 0) {
                headers(c, url.substring(bar + 1))
                url = url.substring(0, bar).trim()
            }
            cur = null
            if (!url.contains("://")) continue
            c.url = url
            if (c.group.isEmpty()) c.group = sticky
            if (c.catchupType.isEmpty()) c.catchupType = defType
            if (c.catchupSource.isEmpty()) c.catchupSource = defSource
            if (c.catchupDays == 0) c.catchupDays = defDays
            if (c.name.isEmpty()) c.name = c.tvgName.ifEmpty { "Канал " + (r.list.size + 1) }
            c.num = r.list.size + 1
            c.adult = ADULT.matcher(c.group).find() || ADULT.matcher(c.name).find()
            r.list.add(c)
        }
        return r
    }

    private fun extinf(line: String): Channel {
        val c = Channel()
        val colon = line.indexOf(':')
        val body = if (colon >= 0) line.substring(colon + 1) else ""
        var comma = -1
        var q = false
        for (k in body.indices) {
            val ch = body[k]
            if (ch == '"') q = !q
            else if (ch == ',' && !q) {
                comma = k
                break
            }
        }
        val a = attrs(if (comma >= 0) body.substring(0, comma) else body)
        c.name = if (comma >= 0) body.substring(comma + 1).trim() else ""
        c.group = first(a, "group-title")
        c.logo = first(a, "tvg-logo", "logo")
        c.tvgId = first(a, "tvg-id")
        c.tvgName = first(a, "tvg-name")
        c.userAgent = first(a, "user-agent", "http-user-agent")
        c.referrer = first(a, "http-referrer", "referrer")
        c.catchupDays = toInt(first(a, "catchup-days", "tvg-rec", "timeshift"))
        c.catchupType = first(a, "catchup", "catchup-type")
        c.catchupSource = first(a, "catchup-source")
        return c
    }

    private fun vlcopt(c: Channel, opt: String) {
        val eq = opt.indexOf('=')
        if (eq < 0) return
        val k = opt.substring(0, eq).trim().lowercase(Locale.ROOT)
        val v = opt.substring(eq + 1).trim()
        if (k == "http-user-agent") c.userAgent = v
        else if (k == "http-referrer" || k == "http-referer") c.referrer = v
    }

    /** Заголовки в стиле Kodi: http://…|User-Agent=…&Referer=… */
    private fun headers(c: Channel, s: String) {
        for (part in s.split("&")) {
            val eq = part.indexOf('=')
            if (eq < 0) continue
            val k = part.substring(0, eq).trim().lowercase(Locale.ROOT)
            val v = part.substring(eq + 1).trim()
            if (k == "user-agent") c.userAgent = v
            else if (k == "referer" || k == "referrer") c.referrer = v
        }
    }

    @JvmStatic
    fun attrs(s: String): Map<String, String> {
        val m = HashMap<String, String>()
        val x = ATTR.matcher(s)
        while (x.find()) {
            val v = x.group(2) ?: x.group(3) ?: x.group(4)
            m[x.group(1)!!.lowercase(Locale.ROOT)] = v!!.trim()
        }
        return m
    }

    private fun toInt(s: String): Int = s.trim().toIntOrNull() ?: 0

    private fun first(a: Map<String, String>, vararg keys: String): String {
        for (k in keys) {
            val v = a[k]
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }
}
