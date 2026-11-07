package ru.argeda.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/** Учётная запись Xtream Codes → список прямых каналов в виде M3U. */
final class Xtream {
    private Xtream() {}

    static String base(String server) {
        String s = server.trim();
        if (!s.contains("://")) s = "http://" + s;
        try {
            URL u = new URL(s);
            return u.getProtocol() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (MalformedURLException e) {
            while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            return s;
        }
    }

    static String toM3u(Source s) throws Exception {
        String b = base(s.server), u = enc(s.user), p = enc(s.pass);
        String q = "?username=" + u + "&password=" + p;
        String tz = "";
        Object info = new JSONTokener(Net.getText(b + "/player_api.php" + q, s.ua, 1 << 20)).nextValue();
        if (info instanceof JSONObject) {
            JSONObject ui = ((JSONObject) info).optJSONObject("user_info"), si = ((JSONObject) info).optJSONObject("server_info");
            if (ui != null && "0".equals(str(ui, "auth"))) throw new IOException("Сервер Xtream не принял логин или пароль");
            if (si != null) tz = clean(str(si, "timezone"));
        }
        Object cats = new JSONTokener(Net.getText(b + "/player_api.php" + q + "&action=get_live_categories", s.ua, 8 << 20)).nextValue();
        if (!(cats instanceof JSONArray)) throw new IOException("Сервер Xtream не принял логин или пароль");
        Map<String, String> names = new HashMap<>();
        JSONArray ca = (JSONArray) cats;
        for (int i = 0; i < ca.length(); i++) {
            JSONObject o = ca.optJSONObject(i);
            if (o != null) names.put(str(o, "category_id"), str(o, "category_name"));
        }
        JSONArray st = new JSONArray(Net.getText(b + "/player_api.php" + q + "&action=get_live_streams", s.ua, 64 << 20));
        StringBuilder sb = new StringBuilder(st.length() * 160);
        sb.append("#EXTM3U url-tvg=\"").append(b).append("/xmltv.php").append(q).append("\" x-timezone=\"").append(tz).append("\"\n");
        for (int i = 0; i < st.length(); i++) {
            JSONObject o = st.optJSONObject(i);
            if (o == null) continue;
            String id = str(o, "stream_id");
            if (id.isEmpty()) continue;
            String group = names.get(str(o, "category_id"));
            sb.append("#EXTINF:-1 tvg-id=\"").append(clean(str(o, "epg_channel_id")))
                    .append("\" tvg-logo=\"").append(clean(str(o, "stream_icon")))
                    .append("\" group-title=\"").append(clean(group == null ? "" : group)).append('"');
            if ("1".equals(str(o, "tv_archive"))) sb.append(" catchup=\"xc\" catchup-days=\"").append(clean(str(o, "tv_archive_duration"))).append('"');
            sb.append(',').append(clean(str(o, "name"))).append('\n')
                    .append(b).append("/live/").append(u).append('/').append(p).append('/').append(id).append(".m3u8\n");
        }
        return sb.toString();
    }

    private static String str(JSONObject o, String k) {
        Object v = o.opt(k);
        return v == null || v == JSONObject.NULL ? "" : v.toString().trim();
    }

    private static String clean(String s) {
        return s.replace('"', '\'').replace('\n', ' ').replace('\r', ' ');
    }

    private static String enc(String s) throws UnsupportedEncodingException {
        return URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20");
    }
}
