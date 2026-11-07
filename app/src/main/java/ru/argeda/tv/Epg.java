package ru.argeda.tv;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Телепрограмма XMLTV (.xml или .xml.gz). Хранится только окно «3 часа назад … 36 часов вперёд». */
final class Epg {
    static final class Prog {
        final long start;
        long stop;
        final String title;

        Prog(long start, long stop, String title) {
            this.start = start;
            this.stop = stop;
            this.title = title;
        }
    }

    private static final Pattern NOISE = Pattern.compile("(?i)(\\(.*?\\)|\\[.*?\\]|\\b(hd|fhd|uhd|sd|hq|4k|8k|hevc|h\\.?26[45]|1080[pi]?|720p|50\\s?fps|orig|original)\\b)");

    private Epg() {}

    static Map<String, List<Prog>> parse(File f) throws IOException {
        long now = System.currentTimeMillis(), from = now - 3 * 3600_000L, to = now + 36 * 3600_000L;
        Map<String, List<String>> names = new HashMap<>();
        Map<String, List<Prog>> progs = new HashMap<>();
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        try (InputStream in = open(f)) {
            XmlPullParser x = Xml.newPullParser();
            try {
                x.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true);
            } catch (XmlPullParserException ignored) {
            }
            x.setInput(in, null);
            String chId = null, pCh = null, title = null;
            List<String> chNames = null;
            long ps = 0, pe = 0;
            try {
                for (int ev = x.next(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        String t = x.getName();
                        if ("channel".equals(t)) {
                            chId = x.getAttributeValue(null, "id");
                            chNames = new ArrayList<>();
                        } else if ("display-name".equals(t) && chNames != null) {
                            chNames.add(x.nextText().trim());
                        } else if ("programme".equals(t)) {
                            pCh = x.getAttributeValue(null, "channel");
                            ps = time(x.getAttributeValue(null, "start"), cal);
                            pe = time(x.getAttributeValue(null, "stop"), cal);
                            title = null;
                            if (pCh == null || ps == 0 || ps > to || (pe != 0 && pe < from)) {
                                pCh = null;
                                skip(x);
                            }
                        } else if ("title".equals(t) && pCh != null && title == null) {
                            title = x.nextText().trim();
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        String t = x.getName();
                        if ("channel".equals(t)) {
                            if (chId != null && chNames != null) names.put(chId, chNames);
                            chId = null;
                            chNames = null;
                        } else if ("programme".equals(t) && pCh != null) {
                            List<Prog> l = progs.get(pCh);
                            if (l == null) progs.put(pCh, l = new ArrayList<>());
                            l.add(new Prog(ps, pe, title == null ? "" : title));
                            pCh = null;
                        }
                    }
                }
            } catch (XmlPullParserException broken) {
                // Битый конец файла: оставляем то, что успели прочитать.
            }
        } catch (XmlPullParserException e) {
            throw new IOException("Телепрограмма не в формате XMLTV");
        }

        Map<String, List<Prog>> out = new HashMap<>();
        for (Map.Entry<String, List<Prog>> e : progs.entrySet()) {
            List<Prog> l = e.getValue();
            Collections.sort(l, (a, b) -> Long.compare(a.start, b.start));
            for (int i = 0; i < l.size(); i++) {
                Prog p = l.get(i);
                if (p.stop <= p.start) p.stop = i + 1 < l.size() ? l.get(i + 1).start : p.start + 3600_000L;
            }
            out.put("id:" + e.getKey().toLowerCase(Locale.ROOT), l);
            List<String> ns = names.get(e.getKey());
            if (ns == null) continue;
            for (String n : ns) {
                putIfAbsent(out, norm(n), l);
                putIfAbsent(out, norm(strip(n)), l);
            }
        }
        return out;
    }

    /** Подбирает программу каналам: по tvg-id, затем по названию (без «HD», «UHD» и т. п.). */
    static void attach(List<Channel> chs, Map<String, List<Prog>> m) {
        if (m == null) return;
        for (Channel c : chs) {
            List<Prog> l = null;
            if (!c.tvgId.isEmpty()) l = m.get("id:" + c.tvgId.toLowerCase(Locale.ROOT));
            if (l == null && !c.tvgName.isEmpty()) l = find(m, c.tvgName);
            if (l == null) l = find(m, c.name);
            c.progs = l;
        }
    }

    private static List<Prog> find(Map<String, List<Prog>> m, String name) {
        List<Prog> l = m.get(norm(name));
        return l != null ? l : m.get(norm(strip(name)));
    }

    static Prog at(List<Prog> l, long t) {
        if (l == null || l.isEmpty()) return null;
        int lo = 0, hi = l.size() - 1, f = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (l.get(mid).start <= t) {
                f = mid;
                lo = mid + 1;
            } else hi = mid - 1;
        }
        if (f < 0) return null;
        Prog p = l.get(f);
        return t < p.stop ? p : null;
    }

    static Prog after(List<Prog> l, long t) {
        if (l == null) return null;
        for (Prog p : l) if (p.start > t) return p;
        return null;
    }

    private static void putIfAbsent(Map<String, List<Prog>> m, String k, List<Prog> l) {
        if (!k.isEmpty() && !m.containsKey(k)) m.put(k, l);
    }

    static String norm(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (ch == 'ё') ch = 'е';
            if (Character.isLetterOrDigit(ch)) b.append(ch);
        }
        return b.toString();
    }

    private static String strip(String s) {
        return NOISE.matcher(s).replaceAll(" ");
    }

    /** «20261107183000 +0300» → миллисекунды UTC. */
    static long time(String s, Calendar c) {
        if (s == null) return 0;
        s = s.trim();
        if (s.length() < 12) return 0;
        try {
            c.clear();
            int sec = s.length() >= 14 && Character.isDigit(s.charAt(12)) ? Integer.parseInt(s.substring(12, 14)) : 0;
            c.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(4, 6)) - 1, Integer.parseInt(s.substring(6, 8)),
                    Integer.parseInt(s.substring(8, 10)), Integer.parseInt(s.substring(10, 12)), sec);
            long t = c.getTimeInMillis();
            String rest = s.substring(Math.min(s.length(), sec > 0 || s.length() >= 14 ? 14 : 12)).trim();
            if (rest.length() >= 5 && (rest.charAt(0) == '+' || rest.charAt(0) == '-')) {
                long off = (Integer.parseInt(rest.substring(1, 3)) * 60L + Integer.parseInt(rest.substring(3, 5))) * 60_000L;
                t -= rest.charAt(0) == '+' ? off : -off;
            } else {
                t -= TimeZone.getDefault().getOffset(t);
            }
            return t;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static InputStream open(File f) throws IOException {
        InputStream in = new BufferedInputStream(new FileInputStream(f), 65536);
        in.mark(4);
        int a = in.read(), b = in.read();
        in.reset();
        return a == 0x1f && b == 0x8b ? new GZIPInputStream(in, 65536) : in;
    }

    private static void skip(XmlPullParser x) throws XmlPullParserException, IOException {
        int depth = 1;
        while (depth > 0) {
            int ev = x.next();
            if (ev == XmlPullParser.START_TAG) depth++;
            else if (ev == XmlPullParser.END_TAG) depth--;
            else if (ev == XmlPullParser.END_DOCUMENT) return;
        }
    }
}
