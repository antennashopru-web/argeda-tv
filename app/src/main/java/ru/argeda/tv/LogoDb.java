package ru.argeda.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Логотипы для каналов, у которых в плейлисте нет tvg-logo: подбор по названию по открытой базе iptv-org.
 * База (ключ названия → ссылка на картинку) собирается workflow «Логотипы» в ветку data
 * скриптом tools/logos.py; приложение скачивает её раз в неделю и держит только нужные строки.
 */
final class LogoDb {
    static final String URL = "https://raw.githubusercontent.com/antennashopru-web/argeda-tv/data/logos.tsv.gz";
    private static final long TTL = 7 * 86_400_000L;
    private static final ExecutorService bg = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private static final Pattern NOISE = Pattern.compile("(?i)(\\(.*?\\)|\\[.*?\\]|\\b(hd|fhd|uhd|sd|hq|hdr|4k|8k|hevc|h\\.?26[45]|1080[pi]?|720p|50\\s?fps|orig|original)\\b)");
    private static final Pattern SHIFT = Pattern.compile("\\s[+-]\\d+\\b");
    /** Код страны или частота кадров в конце названия: «TLC RO», «Liberty Кино UKR», «РБК HD 50». */
    private static final Pattern COUNTRY = Pattern.compile("\\s([A-Z]{2,3}|50|60)$");

    private LogoDb() {}

    /** Ключ без «HD», сдвига «+2», пометок в скобках и кода страны. Та же логика — в tools/logos.py. */
    static String key(String name) {
        return key(name, true);
    }

    /** shift=false — оставить «+2»: у таких каналов своя программа со сдвигом по времени. */
    static String key(String name, boolean shift) {
        String s = NOISE.matcher(name).replaceAll(" ");
        if (shift) s = SHIFT.matcher(s).replaceAll(" ");
        for (int i = 0; i < 3; i++) s = COUNTRY.matcher(s.trim()).replaceAll("");
        return Epg.norm(s);
    }

    /** «матчтв» → «матч»: провайдеры часто дописывают «ТВ», а в базе его нет. */
    static String noTv(String k) {
        for (String t : new String[]{"tv", "тв"})
            if (k.endsWith(t) && k.length() > t.length() + 1) return k.substring(0, k.length() - t.length());
        return k;
    }

    /** Подставляет логотипы в фоне; done вызывается на главном потоке, если что-то изменилось. */
    static void apply(Context ctx, List<Channel> chs, Runnable done) {
        final Map<Channel, String[]> need = new HashMap<>();
        final Set<String> wanted = new HashSet<>();
        for (Channel c : chs) {
            if (!c.logo.isEmpty()) continue;
            String kk = key(c.name);
            String[] k = {Epg.norm(c.name), kk, noTv(kk)};
            need.put(c, k);
            for (String x : k) wanted.add(x);
        }
        if (need.isEmpty()) return;
        final File f = new File(ctx.getFilesDir(), "logos.tsv.gz");
        bg.execute(() -> {
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > TTL) {
                File tmp = new File(f.getPath() + ".part");
                try {
                    Net.download(URL, null, tmp, 32L << 20);
                    if (!tmp.renameTo(f)) //noinspection ResultOfMethodCallIgnored
                        tmp.delete();
                } catch (Exception e) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete(); // нет сети — работаем со старой копией, если она есть
                }
            }
            if (!f.exists()) return;
            final Map<String, String> found = new HashMap<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(f), 65536), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    int t = line.indexOf('\t');
                    if (t > 0 && wanted.contains(line.substring(0, t))) found.put(line.substring(0, t), line.substring(t + 1));
                }
            } catch (Exception e) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return;
            }
            if (found.isEmpty()) return;
            main.post(() -> {
                boolean changed = false;
                for (Map.Entry<Channel, String[]> e : need.entrySet()) {
                    Channel c = e.getKey();
                    if (!c.logo.isEmpty()) continue;
                    String u = null;
                    for (String k : e.getValue()) if (u == null) u = found.get(k);
                    if (u != null) {
                        c.logo = u;
                        c.logoGuessed = true;
                        changed = true;
                    }
                }
                if (changed) done.run();
            });
        });
    }
}
