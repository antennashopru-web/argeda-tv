package ru.argeda.tv;

import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

/** Источник каналов: ссылка M3U, учётная запись Xtream Codes или файл. Хранится только на устройстве. */
final class Source {
    String id = "", name = "", type = "m3u", url = "", server = "", user = "", pass = "", epg = "", ua = "";

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("type", type).put("url", url)
                .put("server", server).put("user", user).put("pass", pass).put("epg", epg).put("ua", ua);
    }

    static Source from(JSONObject o) {
        Source s = new Source();
        s.id = o.optString("id");
        s.name = o.optString("name");
        s.type = o.optString("type", "m3u");
        s.url = o.optString("url");
        s.server = o.optString("server");
        s.user = o.optString("user");
        s.pass = o.optString("pass");
        s.epg = o.optString("epg");
        s.ua = o.optString("ua");
        return s;
    }

    String title() {
        if (!name.trim().isEmpty()) return name.trim();
        if ("xtream".equals(type)) return host(server);
        if ("file".equals(type)) return "Плейлист из файла";
        return host(url);
    }

    /** Только имя сервера — без пути и токена. */
    static String host(String u) {
        try {
            String h = Uri.parse(u.contains("://") ? u : "http://" + u).getHost();
            return h == null || h.isEmpty() ? "Плейлист" : h;
        } catch (RuntimeException e) {
            return "Плейлист";
        }
    }
}
