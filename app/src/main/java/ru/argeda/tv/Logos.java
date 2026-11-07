package ru.argeda.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Логотипы каналов: память + кэш на диске; пока логотипа нет, видны буквы названия. */
final class Logos {
    private static LruCache<String, Bitmap> mem;
    private static File dir;
    private static final ExecutorService pool = Executors.newFixedThreadPool(3);
    private static final Set<String> bad = Collections.synchronizedSet(new HashSet<>());
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Logos() {}

    static synchronized void init(Context c) {
        if (mem != null) return;
        dir = new File(c.getCacheDir(), "logos");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        int kb = (int) Math.min(24 * 1024, Runtime.getRuntime().maxMemory() / 1024 / 12);
        mem = new LruCache<String, Bitmap>(kb) {
            @Override
            protected int sizeOf(String k, Bitmap b) {
                return Math.max(1, b.getByteCount() / 1024);
            }
        };
    }

    static void show(ImageView iv, TextView initials, String url) {
        iv.setTag(url);
        Bitmap b = url.isEmpty() ? null : mem.get(url);
        iv.setImageBitmap(b);
        initials.setVisibility(b == null ? View.VISIBLE : View.INVISIBLE);
        if (b != null || bad.contains(url) || !(url.startsWith("http://") || url.startsWith("https://"))) return;
        pool.execute(() -> {
            if (!url.equals(iv.getTag())) return; // строка уже показывает другой канал
            Bitmap bm = fetch(url);
            if (bm == null) {
                bad.add(url);
                return;
            }
            mem.put(url, bm);
            main.post(() -> {
                if (url.equals(iv.getTag())) {
                    iv.setImageBitmap(bm);
                    initials.setVisibility(View.INVISIBLE);
                }
            });
        });
    }

    private static Bitmap fetch(String url) {
        File f = new File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length());
        try {
            byte[] b;
            if (f.exists()) {
                try (InputStream in = new FileInputStream(f)) {
                    b = Net.readAll(in, 2 << 20);
                }
            } else {
                b = Net.get(url, null, 2 << 20);
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(b);
                }
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(b, 0, b.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return null;
            }
            int s = 1;
            while (o.outWidth / (s * 2) >= 160 && o.outHeight / (s * 2) >= 90) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            return BitmapFactory.decodeByteArray(b, 0, b.length, d);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }
}
