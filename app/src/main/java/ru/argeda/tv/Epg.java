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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Телепрограмма XMLTV (.xml или .xml.gz). Хранятся только каналы из плейлиста и только окно
 * «глубина архива назад … 2 суток вперёд»; описания — для ближайших суток.
 */
final class Epg {
    static final class Prog {
        final long start;
        long stop;
        final String title;
        String desc;

        Prog(long start, long stop, String title) {
            this.start = start;
            this.stop = stop;
            this.title = title;
        }
    }

    private static final Pattern NOISE = Pattern.compile("(?i)(\\(.*?\\)|\\[.*?\\]|\\b(hd|fhd|uhd|sd|hq|4k|8k|hevc|h\\.?26[45]|1080[pi]?|720p|50\\s?fps|orig|original)\\b)");

    private Epg() {}

    /** Ключи для сопоставления канала: tvg-id и название без «HD» и пометок. */
    static void keys(Channel c, Set<String> out) {
        if (!c.tvgId.isEmpty()) out.add("id:" + c.tvgId.toLowerCase(Locale.ROOT));
        for (String n : new String[]{c.tvgName, c.name}) {
            if (n.isEmpty()) continue;
            out.add(norm(n));
            out.add(norm(strip(n)));
        }
    }

    /**
     * @param wanted ключи каналов плейлиста (null — брать все)
     * @param past   сколько миллисекунд прошлого хранить (для архива)
     */
    static Map<String, List<Prog>> parse(File f, Set<String> wanted, long past, Map<String, String> icons) throws IOException {
        long now = System.currentTimeMillis(), from = now - past, to = now + 48 * 3600_000L;
        long descFrom = now - 6 * 3600_000L, descTo = now + 24 * 3600_000L;
        Set<String> accepted = new HashSet<>();
        int channelsSeen = 0;
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
            String chId = null, chIcon = null, pCh = null, title = null, desc = null;
            List<String> chNames = null;
            long ps = 0, pe = 0;
            try {
                for (int ev = x.next(); ev != XmlPullParser.END_DOCUMENT; ev = x.next()) {
                    if (ev == XmlPullParser.START_TAG) {
                        String t = x.getName();
                        if ("channel".equals(t)) {
                            chId = x.getAttributeValue(null, "id");
                            chIcon = null;
                            chNames = new ArrayList<>();
                        } else if ("icon".equals(t) && chNames != null && chIcon == null) {
                            String src = x.getAttributeValue(null, "src");
                            if (src != null && (src.startsWith("http://") || src.startsWith("https://"))) chIcon = src.trim();
                        } else if ("display-name".equals(t) && chNames != null) {
                            chNames.add(x.nextText().trim());
                        } else if ("programme".equals(t)) {
                            pCh = x.getAttributeValue(null, "channel");
                            ps = time(x.getAttributeValue(null, "start"), cal);
                            pe = time(x.getAttributeValue(null, "stop"), cal);
                            title = null;
                            desc = null;
                            if (pCh == null || ps == 0 || ps > to || (pe != 0 && pe < from)
                                    || (channelsSeen > 0 && wanted != null && !accepted.contains(pCh))) {
                                pCh = null;
                                skip(x);
                            }
                        } else if ("title".equals(t) && pCh != null && title == null) {
                            title = x.nextText().trim();
                        } else if ("desc".equals(t) && pCh != null && desc == null && ps >= descFrom && ps <= descTo) {
                            desc = x.nextText().trim();
                            if (desc.length() > 400) desc = desc.substring(0, 400) + "…";
                        }
                    } else if (ev == XmlPullParser.END_TAG) {
                        String t = x.getName();
                        if ("channel".equals(t)) {
                            channelsSeen++;
                            if (chId != null && chNames != null) {
                                names.put(chId, chNames);
                                if (wanted == null || matches(chId, chNames, wanted)) {
                                    accepted.add(chId);
                                    if (chIcon != null && icons != null) {
                                        icons.put("id:" + chId.toLowerCase(Locale.ROOT), chIcon);
                                        for (String n : chNames) {
                                            if (!icons.containsKey(norm(n))) icons.put(norm(n), chIcon);
                                            if (!icons.containsKey(norm(strip(n)))) icons.put(norm(strip(n)), chIcon);
                                        }
                                    }
                                }
                            }
                            chId = null;
                            chNames = null;
                        } else if ("programme".equals(t) && pCh != null) {
                            List<Prog> l = progs.get(pCh);
                            if (l == null) progs.put(pCh, l = new ArrayList<>());
                            Prog pr = new Prog(ps, pe, title == null ? "" : title);
                            pr.desc = desc;
                            l.add(pr);
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

    /**
     * Подбирает программу каналам: по tvg-id, затем по названию (без «HD», «UHD» и т. п.).
     * Каналам без tvg-logo в плейлисте отдаёт логотип из телепрограммы (тег icon).
     */
    static void attach(List<Channel> chs, Map<String, List<Prog>> m, Map<String, String> icons) {
        if (m == null) return;
        for (Channel c : chs) {
            if (c.logo.isEmpty() && icons != null && !icons.isEmpty()) {
                String ic = null;
                if (!c.tvgId.isEmpty()) ic = icons.get("id:" + c.tvgId.toLowerCase(Locale.ROOT));
                if (ic == null && !c.tvgName.isEmpty()) ic = icon(icons, c.tvgName);
                if (ic == null) ic = icon(icons, c.name);
                if (ic != null) c.logo = ic;
            }
            List<Prog> l = null;
            if (!c.tvgId.isEmpty()) l = m.get("id:" + c.tvgId.toLowerCase(Locale.ROOT));
            if (l == null && !c.tvgName.isEmpty()) l = find(m, c.tvgName);
            if (l == null) l = find(m, c.name);
            c.progs = l;
        }
    }

    private static String icon(Map<String, String> icons, String name) {
        String s = icons.get(norm(name));
        return s != null ? s : icons.get(norm(strip(name)));
    }

    private static boolean matches(String id, List<String> names, Set<String> wanted) {
        if (wanted.contains("id:" + id.toLowerCase(Locale.ROOT))) return true;
        for (String n : names) if (wanted.contains(norm(n)) || wanted.contains(norm(strip(n)))) return true;
        return false;
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
