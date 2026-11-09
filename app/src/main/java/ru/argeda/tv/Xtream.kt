package ru.argeda.tv

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.net.URLEncoder

/** Учётная запись Xtream Codes → список прямых каналов в виде M3U. */
internal object Xtream {
    @JvmStatic
    fun base(server: String): String {
        var s = server.trim()
        if (!s.contains("://")) s = "http://$s"
        return try {
            val u = URL(s)
            u.protocol + "://" + u.host + (if (u.port > 0) ":" + u.port else "")
        } catch (_: MalformedURLException) {
            s.trimEnd('/')
        }
    }

    @JvmStatic
    @Throws(Exception::class)
    fun toM3u(s: Source): String {
        val b = base(s.server)
        val u = enc(s.user)
        val p = enc(s.pass)
        val q = "?username=$u&password=$p"
        var tz = ""
        val info = JSONTokener(Net.getText("$b/player_api.php$q", s.ua, 1 shl 20)).nextValue()
        if (info is JSONObject) {
            val ui = info.optJSONObject("user_info")
            val si = info.optJSONObject("server_info")
            if (ui != null && str(ui, "auth") == "0") throw IOException("Сервер Xtream не принял логин или пароль")
            if (si != null) tz = clean(str(si, "timezone"))
        }
        val cats = JSONTokener(Net.getText("$b/player_api.php$q&action=get_live_categories", s.ua, 8 shl 20)).nextValue()
        if (cats !is JSONArray) throw IOException("Сервер Xtream не принял логин или пароль")
        val names = HashMap<String, String>()
        for (i in 0 until cats.length()) {
            val o = cats.optJSONObject(i) ?: continue
            names[str(o, "category_id")] = str(o, "category_name")
        }
        val st = JSONArray(Net.getText("$b/player_api.php$q&action=get_live_streams", s.ua, 64 shl 20))
        val sb = StringBuilder(st.length() * 160)
        sb.append("#EXTM3U url-tvg=\"").append(b).append("/xmltv.php").append(q).append("\" x-timezone=\"").append(tz).append("\"\n")
        for (i in 0 until st.length()) {
            val o = st.optJSONObject(i) ?: continue
            val id = str(o, "stream_id")
            if (id.isEmpty()) continue
            val group = names[str(o, "category_id")]
            sb.append("#EXTINF:-1 tvg-id=\"").append(clean(str(o, "epg_channel_id")))
                .append("\" tvg-logo=\"").append(clean(str(o, "stream_icon")))
                .append("\" group-title=\"").append(clean(group ?: "")).append('"')
            if (str(o, "tv_archive") == "1") sb.append(" catchup=\"xc\" catchup-days=\"").append(clean(str(o, "tv_archive_duration"))).append('"')
            sb.append(',').append(clean(str(o, "name"))).append('\n')
                .append(b).append("/live/").append(u).append('/').append(p).append('/').append(id).append(".m3u8\n")
        }
        return sb.toString()
    }

    private fun str(o: JSONObject, k: String): String {
        val v = o.opt(k)
        return if (v == null || v == JSONObject.NULL) "" else v.toString().trim()
    }

    private fun clean(s: String): String = s.replace('"', '\'').replace('\n', ' ').replace('\r', ' ')

    private fun enc(s: String): String = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")
}
