package ru.argeda.tv;

import java.util.List;
import java.util.Locale;

/** Канал из плейлиста пользователя. */
final class Channel {
    String name = "", url = "", group = "", logo = "", tvgId = "", tvgName = "", userAgent = "", referrer = "";
    int catchupDays;
    /** Номер по порядку в плейлисте, начиная с 1. */
    int num;
    boolean adult;
    /** Телепрограмма канала, если она найдена в XMLTV. */
    List<Epg.Prog> progs;

    /** Ключ для избранного и недавних: не зависит от ссылки, в которой может меняться токен. */
    String key() {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    String groupTitle() {
        return groupTitle(group);
    }

    static String groupTitle(String g) {
        if (g == null || g.isEmpty()) return "Без группы";
        char ch = g.charAt(0);
        // Заглавную делаем только кириллице и латинице: в грузинском, армянском и др. так не принято.
        boolean cap = (ch >= 'a' && ch <= 'z') || (ch >= 'а' && ch <= 'я') || ch == 'ё';
        return cap ? Character.toUpperCase(ch) + g.substring(1) : g;
    }

    /** Буквы-заглушка вместо логотипа: «Первый канал» → «ПК», «НТВ» → «НТВ». */
    String initials() {
        String a = null, b = null;
        for (String s : name.trim().split("[^\\p{L}\\p{N}]+")) {
            if (s.isEmpty()) continue;
            if (a == null) a = s;
            else {
                b = s;
                break;
            }
        }
        if (a == null) return "";
        String r = b == null ? a.substring(0, Math.min(3, a.length())) : "" + a.charAt(0) + b.charAt(0);
        return r.toUpperCase(Locale.getDefault());
    }
}
