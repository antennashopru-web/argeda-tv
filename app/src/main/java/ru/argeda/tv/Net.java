package ru.argeda.tv;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/** Простые HTTP-запросы: плейлист, Xtream API, телепрограмма, логотипы. */
final class Net {
    static final String UA = "ARGEDA-TV/1.0 (Linux; Android)";

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code) {
            super(text(code));
            this.code = code;
        }

        private static String text(int c) {
            if (c == 401 || c == 403) return "Доступ запрещён (код " + c + "). Проверьте ссылку и подписку у провайдера";
            if (c == 404) return "По ссылке ничего нет (код 404)";
            if (c >= 500) return "Ошибка на сервере провайдера (код " + c + ")";
            return "Сервер ответил кодом " + c;
        }
    }

    private Net() {}

    static HttpURLConnection open(String url, String ua) throws IOException {
        return open(url, ua, null);
    }

    /** accept — заголовок Accept (null — не отправлять). */
    static HttpURLConnection open(String url, String ua, String accept) throws IOException {
        String u = url.trim();
        for (int hop = 0; hop < 6; hop++) {
            URL x = new URL(u);
            String proto = x.getProtocol();
            if (!"http".equals(proto) && !"https".equals(proto)) throw new IOException("Поддерживаются только ссылки http:// и https://");
            HttpURLConnection c = (HttpURLConnection) x.openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", ua == null || ua.isEmpty() ? UA : ua);
            c.setRequestProperty("Accept-Encoding", "gzip");
            // Accept нужен только самому API; на переадресованный адрес хранилища его не шлём.
            if (accept != null && hop == 0) c.setRequestProperty("Accept", accept);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && code != 304) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("Сервер переадресовал запрос в никуда");
                u = new URL(x, loc).toString();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new HttpError(code);
            }
            return c;
        }
        throw new IOException("Слишком много переадресаций");
    }

    private static InputStream body(HttpURLConnection c) throws IOException {
        InputStream in = new BufferedInputStream(c.getInputStream(), 65536);
        if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in, 65536);
        return in;
    }

    static byte[] get(String url, String ua, int max) throws IOException {
        HttpURLConnection c = open(url, ua);
        try (InputStream in = body(c)) {
            return readAll(in, max);
        } finally {
            c.disconnect();
        }
    }

    static String getText(String url, String ua, int max) throws IOException {
        return text(get(url, ua, max));
    }

    static void download(String url, String ua, File out, long max) throws IOException {
        download(url, ua, null, out, max);
    }

    static void download(String url, String ua, String accept, File out, long max) throws IOException {
        HttpURLConnection c = open(url, ua, accept);
        try (InputStream in = body(c); OutputStream o = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) throw new IOException("Файл слишком большой");
                o.write(buf, 0, n);
            }
        } finally {
            c.disconnect();
        }
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(65536);
        byte[] buf = new byte[65536];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new IOException("Файл слишком большой");
            b.write(buf, 0, n);
        }
        return b.toByteArray();
    }

    /** Текст в UTF-8; сжатые gzip данные распаковываются. */
    static String text(byte[] b) throws IOException {
        if (b.length > 2 && (b[0] & 0xff) == 0x1f && (b[1] & 0xff) == 0x8b) {
            b = readAll(new GZIPInputStream(new ByteArrayInputStream(b)), 100 << 20);
        }
        return new String(b, StandardCharsets.UTF_8);
    }
}
