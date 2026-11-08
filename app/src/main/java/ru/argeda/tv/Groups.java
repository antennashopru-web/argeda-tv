package ru.argeda.tv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Категории для списка каналов в плеере: тот же порядок, что на главном экране. */
final class Groups {
    static final String ALL = "\u0001all", FAV = "\u0001fav", REC = "\u0001rec", FOLDER = "\u0001f:";

    static final class G {
        final String id, title;
        final int count;

        G(String id, String title, int count) {
            this.id = id;
            this.title = title;
            this.count = count;
        }
    }

    private Groups() {}

    static List<G> list(Store st, List<Channel> vis) {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        int favN = 0;
        for (Channel c : vis) {
            Integer n = m.get(c.group);
            m.put(c.group, n == null ? 1 : n + 1);
            if (st.isFav(c)) favN++;
        }
        List<G> r = new ArrayList<>();
        if (favN > 0) r.add(new G(FAV, "★  Избранное", favN));
        for (Store.Folder f : st.folders) {
            int n = ordered(f.keys, vis).size();
            if (n > 0) r.add(new G(FOLDER + f.name, "☆  " + f.name, n));
        }
        for (String g : st.favGroups) {
            Integer n = m.get(g);
            if (n != null) r.add(new G(g, "▸  " + Channel.groupTitle(g), n));
        }
        int recN = ordered(st.recent, vis).size();
        if (recN > 0) r.add(new G(REC, "Недавние", recN));
        r.add(new G(ALL, "Все каналы", vis.size()));
        for (Map.Entry<String, Integer> e : m.entrySet())
            if (!st.isFavGroup(e.getKey())) r.add(new G(e.getKey(), Channel.groupTitle(e.getKey()), e.getValue()));
        return r;
    }

    static List<Channel> channels(Store st, String id, List<Channel> vis) {
        if (ALL.equals(id)) return vis;
        if (REC.equals(id)) return ordered(st.recent, vis);
        if (FAV.equals(id)) return ordered(st.favOrder, vis);
        if (id.startsWith(FOLDER)) {
            Store.Folder f = st.folder(id.substring(FOLDER.length()));
            return f == null ? new ArrayList<>() : ordered(f.keys, vis);
        }
        List<Channel> r = new ArrayList<>();
        for (Channel c : vis) if (id.equals(c.group)) r.add(c);
        return r;
    }

    static String title(String id) {
        if (ALL.equals(id)) return "Все каналы";
        if (FAV.equals(id)) return "Избранное";
        if (REC.equals(id)) return "Недавние";
        if (id.startsWith(FOLDER)) return id.substring(FOLDER.length());
        return Channel.groupTitle(id);
    }

    static List<Channel> search(String query, List<Channel> vis) {
        String q = norm(query);
        List<Channel> r = new ArrayList<>();
        if (q.isEmpty()) return r;
        for (Channel c : vis) if (norm(c.name).contains(q) || String.valueOf(c.num).equals(q)) r.add(c);
        return r;
    }

    static String norm(String s) {
        return s.trim().toLowerCase(Locale.getDefault()).replace('ё', 'е');
    }

    private static List<Channel> ordered(List<String> keys, List<Channel> vis) {
        List<Channel> r = new ArrayList<>();
        if (keys.isEmpty()) return r;
        Map<String, Channel> m = new HashMap<>();
        for (Channel c : vis) if (!m.containsKey(c.key())) m.put(c.key(), c);
        for (String k : keys) {
            Channel c = m.get(k);
            if (c != null) r.add(c);
        }
        return r;
    }
}
