package ru.argeda.tv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Разбор плейлистов M3U/M3U8: #EXTINF с атрибутами, #EXTGRP, #EXTVLCOPT, заголовки после «|». */
final class M3u {
    static final class Result {
        final List<Channel> list = new ArrayList<>();
        String epg = "", tz = "";
    }

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)=(?:\"([^\"]*)\"|'([^']*)'|([^\\s,\"']+))");
    /** Группы и каналы «для взрослых» скрываются, пока их не откроют PIN-кодом. */
    static final Pattern ADULT = Pattern.compile("(?iu)(взросл|эрот|порно|xxx|porn|adult|18\\s*\\+)");

    private M3u() {}

    static Result parse(String text) {
        Result r = new Result();
        if (text == null) return r;
        Channel cur = null;
        String sticky = "", defType = "", defSource = "";
        int defDays = 0;
        int n = text.length(), i = 0;
        if (n > 0 && text.charAt(0) == '\uFEFF') i = 1;
        while (i < n) {
            int e = text.indexOf('\n', i);
            if (e < 0) e = n;
            String line = text.substring(i, e).trim();
            i = e + 1;
            if (line.isEmpty()) continue;
            if (line.charAt(0) == '#') {
                String up = line.substring(0, Math.min(12, line.length())).toUpperCase(Locale.ROOT);
                if (up.startsWith("#EXTM3U")) {
                    Map<String, String> a = attrs(line);
                    String g = first(a, "url-tvg", "x-tvg-url", "tvg-url");
                    if (!g.isEmpty() && r.epg.isEmpty()) r.epg = g;
                    defType = first(a, "catchup", "catchup-type");
                    defSource = first(a, "catchup-source");
                    defDays = toInt(first(a, "catchup-days", "tvg-rec", "timeshift"));
                    r.tz = first(a, "x-timezone");
                } else if (up.startsWith("#EXTINF")) {
                    cur = extinf(line);
                } else if (up.startsWith("#EXTGRP:")) {
                    sticky = line.substring(8).trim();
                    if (cur != null && cur.group.isEmpty()) cur.group = sticky;
                } else if (up.startsWith("#EXTVLCOPT:")) {
                    if (cur != null) vlcopt(cur, line.substring(11).trim());
                }
                continue;
            }
            if (cur == null) cur = new Channel();
            String url = line;
            int bar = url.indexOf('|');
            if (bar > 0) {
                headers(cur, url.substring(bar + 1));
                url = url.substring(0, bar).trim();
            }
            if (!url.contains("://")) {
                cur = null;
                continue;
            }
            cur.url = url;
            if (cur.group.isEmpty()) cur.group = sticky;
            if (cur.catchupType.isEmpty()) cur.catchupType = defType;
            if (cur.catchupSource.isEmpty()) cur.catchupSource = defSource;
            if (cur.catchupDays == 0) cur.catchupDays = defDays;
            if (cur.name.isEmpty()) cur.name = !cur.tvgName.isEmpty() ? cur.tvgName : "Канал " + (r.list.size() + 1);
            cur.num = r.list.size() + 1;
            cur.adult = ADULT.matcher(cur.group).find() || ADULT.matcher(cur.name).find();
            r.list.add(cur);
            cur = null;
        }
        return r;
    }

    private static Channel extinf(String line) {
        Channel c = new Channel();
        int colon = line.indexOf(':');
        String body = colon >= 0 ? line.substring(colon + 1) : "";
        int comma = -1;
        boolean q = false;
        for (int k = 0; k < body.length(); k++) {
            char ch = body.charAt(k);
            if (ch == '"') q = !q;
            else if (ch == ',' && !q) {
                comma = k;
                break;
            }
        }
        Map<String, String> a = attrs(comma >= 0 ? body.substring(0, comma) : body);
        c.name = comma >= 0 ? body.substring(comma + 1).trim() : "";
        c.group = first(a, "group-title");
        c.logo = first(a, "tvg-logo", "logo");
        c.tvgId = first(a, "tvg-id");
        c.tvgName = first(a, "tvg-name");
        c.userAgent = first(a, "user-agent", "http-user-agent");
        c.referrer = first(a, "http-referrer", "referrer");
        c.catchupDays = toInt(first(a, "catchup-days", "tvg-rec", "timeshift"));
        c.catchupType = first(a, "catchup", "catchup-type");
        c.catchupSource = first(a, "catchup-source");
        return c;
    }

    private static void vlcopt(Channel c, String opt) {
        int eq = opt.indexOf('=');
        if (eq < 0) return;
        String k = opt.substring(0, eq).trim().toLowerCase(Locale.ROOT), v = opt.substring(eq + 1).trim();
        if (k.equals("http-user-agent")) c.userAgent = v;
        else if (k.equals("http-referrer") || k.equals("http-referer")) c.referrer = v;
    }

    /** Заголовки в стиле Kodi: http://…|User-Agent=…&Referer=… */
    private static void headers(Channel c, String s) {
        for (String part : s.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq).trim().toLowerCase(Locale.ROOT), v = part.substring(eq + 1).trim();
            if (k.equals("user-agent")) c.userAgent = v;
            else if (k.equals("referer") || k.equals("referrer")) c.referrer = v;
        }
    }

    static Map<String, String> attrs(String s) {
        Map<String, String> m = new HashMap<>();
        Matcher x = ATTR.matcher(s);
        while (x.find()) {
            String v = x.group(2) != null ? x.group(2) : x.group(3) != null ? x.group(3) : x.group(4);
            m.put(x.group(1).toLowerCase(Locale.ROOT), v.trim());
        }
        return m;
    }

    private static int toInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String first(Map<String, String> a, String... keys) {
        for (String k : keys) {
            String v = a.get(k);
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }
}
