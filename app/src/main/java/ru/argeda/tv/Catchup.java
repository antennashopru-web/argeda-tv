package ru.argeda.tv;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ссылки на архив (catchup) для типов, которые встречаются у провайдеров:
 * shift (?utc=…&lutc=…), flussonic (index-START-DUR.m3u8), xc (Xtream Codes /timeshift/),
 * default/append (шаблон catchup-source с {utc}, {lutc}, {duration}, {Y}{m}{d}… и т. п.).
 */
final class Catchup {
    private static final Pattern VAR = Pattern.compile("\\$?\\{(\\w+)(?::([^}]*))?\\}");
    private static final Pattern XC = Pattern.compile("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(\\.\\w+)?(\\?.*)?$");
    private static final long DAY = 86_400_000L;

    private Catchup() {}

    /** Глубина архива в днях (0 — архива нет). */
    static int days(Channel c, Source s) {
        if (s != null && "none".equals(s.archive)) return 0;
        if (c.catchupDays > 0) return Math.min(c.catchupDays, 30);
        return !c.catchupType.isEmpty() || !c.catchupSource.isEmpty() ? 3 : 0;
    }

    /** Можно ли смотреть с этого момента в записи. */
    static boolean can(Channel c, Source s, long start, long now) {
        int d = days(c, s);
        return d > 0 && start < now - 60_000L && start >= now - d * DAY;
    }

    static String mode(Channel c, Source s) {
        String m = s == null ? "auto" : s.archive;
        if ("none".equals(m)) return null;
        if (!"auto".equals(m)) return m;
        String t = c.catchupType.toLowerCase(Locale.ROOT);
        if (t.equals("fs") || t.startsWith("flussonic")) return "flussonic";
        if (t.equals("xc")) return "xc";
        if (t.equals("append")) return c.catchupSource.isEmpty() ? "shift" : "append";
        if (t.equals("shift") || t.equals("timeshift")) return "shift";
        if (!c.catchupSource.isEmpty()) return c.catchupSource.contains("://") ? "default" : "append";
        if (s != null && "xtream".equals(s.type)) return "xc";
        return "shift";
    }

    /** Ссылка на запись с момента start; end — конец передачи (для типов, где нужна длительность). */
    static String url(Channel c, Source s, String tz, long start, long end, long now) {
        String m = mode(c, s);
        if (m == null) return null;
        long st = start / 1000, nw = now / 1000;
        long en = Math.max(end, start + 60_000L) / 1000;
        switch (m) {
            case "default":
                return fill(c.catchupSource, st, en, nw);
            case "append":
                return c.url + fill(c.catchupSource, st, en, nw);
            case "flussonic":
                return flussonic(c.url, st, en - st);
            case "xc":
                return xc(c.url, st, en - st, tz);
            default:
                return c.url + (c.url.contains("?") ? "&" : "?") + "utc=" + st + "&lutc=" + nw;
        }
    }

    static String fill(String tpl, long st, long en, long nw) {
        Matcher m = VAR.matcher(tpl);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String k = m.group(1), f = m.group(2), v;
            switch (k) {
                case "utc": case "start":
                    v = f != null ? fmt(f, st) : String.valueOf(st);
                    break;
                case "utcend": case "end":
                    v = f != null ? fmt(f, en) : String.valueOf(en);
                    break;
                case "lutc": case "now": case "timestamp":
                    v = f != null ? fmt(f, nw) : String.valueOf(nw);
                    break;
                case "duration":
                    v = String.valueOf(en - st);
                    break;
                case "offset":
                    v = String.valueOf(nw - st);
                    break;
                case "Y": case "m": case "d": case "H": case "M": case "S":
                    v = fmt(k, st);
                    break;
                default:
                    v = m.group();
            }
            m.appendReplacement(b, Matcher.quoteReplacement(v));
        }
        m.appendTail(b);
        return b.toString();
    }

    /** Формат в стиле {utc:Y-m-d:H-M}: Y m d H M S → год, месяц, день, часы, минуты, секунды (местное время). */
    private static String fmt(String f, long sec) {
        StringBuilder p = new StringBuilder();
        for (char ch : f.toCharArray()) {
            switch (ch) {
                case 'Y': p.append("yyyy"); break;
                case 'm': p.append("MM"); break;
                case 'd': p.append("dd"); break;
                case 'H': p.append("HH"); break;
                case 'M': p.append("mm"); break;
                case 'S': p.append("ss"); break;
                default:
                    if (Character.isLetter(ch)) p.append('\'').append(ch).append('\'');
                    else p.append(ch);
            }
        }
        return new SimpleDateFormat(p.toString(), Locale.ROOT).format(new Date(sec * 1000));
    }

    static String flussonic(String url, long st, long dur) {
        int q = url.indexOf('?');
        String path = q >= 0 ? url.substring(0, q) : url, query = q >= 0 ? url.substring(q) : "";
        int slash = path.lastIndexOf('/');
        String dir = path.substring(0, slash + 1), file = path.substring(slash + 1);
        if (file.equals("mpegts")) return dir + "timeshift_abs-" + st + ".ts" + query;
        if (file.endsWith(".ts")) return dir + "timeshift_abs-" + st + ".ts" + query;
        if (file.endsWith(".m3u8")) return dir + file.substring(0, file.length() - 5) + "-" + st + "-" + dur + ".m3u8" + query;
        return path + (path.endsWith("/") ? "" : "/") + "index-" + st + "-" + dur + ".m3u8" + query;
    }

    static String xc(String url, long st, long dur, String tz) {
        Matcher m = XC.matcher(url);
        if (!m.matches()) return url + (url.contains("?") ? "&" : "?") + "utc=" + st + "&lutc=" + (System.currentTimeMillis() / 1000);
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.ROOT);
        f.setTimeZone(tz == null || tz.isEmpty() ? TimeZone.getDefault() : TimeZone.getTimeZone(tz));
        String ext = m.group(5) == null ? ".ts" : m.group(5);
        return m.group(1) + "/timeshift/" + m.group(2) + "/" + m.group(3) + "/" + Math.max(1, (dur + 59) / 60) + "/"
                + f.format(new Date(st * 1000)) + "/" + m.group(4) + ext + (m.group(6) == null ? "" : m.group(6));
    }
}
