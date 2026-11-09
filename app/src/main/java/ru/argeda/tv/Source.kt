package ru.argeda.tv

import android.net.Uri
import org.json.JSONException
import org.json.JSONObject

/** Источник каналов: ссылка M3U, учётная запись Xtream Codes или файл. Хранится только на устройстве. */
internal class Source {
    @JvmField var id = ""
    @JvmField var name = ""
    @JvmField var type = "m3u"
    @JvmField var url = ""
    @JvmField var server = ""
    @JvmField var user = ""
    @JvmField var pass = ""
    @JvmField var epg = ""
    @JvmField var ua = ""

    /** Тип архива: auto, shift, flussonic, xc, none. */
    @JvmField var archive = "auto"

    @Throws(JSONException::class)
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type).put("url", url)
        .put("server", server).put("user", user).put("pass", pass).put("epg", epg).put("ua", ua).put("archive", archive)

    fun title(): String = when {
        name.isNotBlank() -> name.trim()
        type == "xtream" -> host(server)
        type == "file" -> "Плейлист из файла"
        else -> host(url)
    }

    companion object {
        @JvmStatic
        fun from(o: JSONObject) = Source().apply {
            id = o.optString("id")
            name = o.optString("name")
            type = o.optString("type", "m3u")
            url = o.optString("url")
            server = o.optString("server")
            user = o.optString("user")
            pass = o.optString("pass")
            epg = o.optString("epg")
            ua = o.optString("ua")
            archive = o.optString("archive", "auto")
        }

        /** Только имя сервера — без пути и токена. */
        @JvmStatic
        fun host(u: String): String = try {
            val h = Uri.parse(if (u.contains("://")) u else "http://$u").host
            if (h.isNullOrEmpty()) "Плейлист" else h
        } catch (_: RuntimeException) {
            "Плейлист"
        }
    }
}
