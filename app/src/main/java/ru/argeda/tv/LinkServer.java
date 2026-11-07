package ru.argeda.tv;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Маленький веб-сервер в домашней сети, пока открыт экран плейлиста:
 * на телефоне открывают http://IP:порт/код и вставляют ссылку — не нужно набирать её пультом.
 * Принимает подключения только из локальной сети и только по случайному коду в адресе.
 */
final class LinkServer {
    interface Callback {
        void got(Map<String, String> fields);
    }

    final String code = String.valueOf(100000 + new SecureRandom().nextInt(900000));
    private ServerSocket ss;
    private volatile boolean on;
    int port;

    boolean start(Callback cb) {
        for (int p = 8765; p < 8775 && ss == null; p++) {
            try {
                ss = new ServerSocket(p);
                port = p;
            } catch (IOException ignored) {
            }
        }
        if (ss == null) return false;
        on = true;
        Thread t = new Thread(() -> {
            while (on) {
                try (Socket s = ss.accept()) {
                    s.setSoTimeout(8000);
                    handle(s, cb);
                } catch (IOException e) {
                    if (!on) break;
                }
            }
        }, "link-server");
        t.setDaemon(true);
        t.start();
        return true;
    }

    void stop() {
        on = false;
        try {
            if (ss != null) ss.close();
        } catch (IOException ignored) {
        }
        ss = null;
    }

    static String localIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void handle(Socket s, Callback cb) throws IOException {
        InetAddress a = s.getInetAddress();
        if (!(a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isLoopbackAddress())) return;
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();
        String req = line(in);
        if (req == null) return;
        int len = 0;
        for (String h = line(in); h != null && !h.isEmpty(); h = line(in)) {
            if (h.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                try {
                    len = Integer.parseInt(h.substring(15).trim());
                } catch (NumberFormatException ignored) {
                }
            }
        }
        String[] p = req.split(" ");
        if (p.length < 2) return;
        String path = p[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (!path.equals("/" + code)) {
            send(out, 404, "text/plain; charset=utf-8", "Не найдено. Проверьте адрес на экране телевизора.");
            return;
        }
        if ("POST".equals(p[0]) && len > 0 && len <= 65536) {
            byte[] b = new byte[len];
            int r = 0;
            while (r < len) {
                int n = in.read(b, r, len - r);
                if (n < 0) break;
                r += n;
            }
            cb.got(form(new String(b, 0, r, StandardCharsets.UTF_8)));
            send(out, 200, "text/html; charset=utf-8", page(true));
            return;
        }
        send(out, 200, "text/html; charset=utf-8", page(false));
    }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (b.size() > 8192) throw new IOException("line too long");
            if (c != '\r') b.write(c);
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("ISO-8859-1");
    }

    private static Map<String, String> form(String body) {
        Map<String, String> m = new HashMap<>();
        for (String part : body.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try {
                m.put(URLDecoder.decode(part.substring(0, eq), "UTF-8"), URLDecoder.decode(part.substring(eq + 1), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static void send(OutputStream o, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 " + status + (status == 200 ? " OK" : " Not Found") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + b.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        o.write(h.getBytes(StandardCharsets.ISO_8859_1));
        o.write(b);
        o.flush();
    }

    private static String page(boolean done) {
        return "<!doctype html><html lang=ru><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'><title>ARGEDA TV</title><style>"
                + "body{font:16px system-ui,sans-serif;background:#0B1220;color:#F2F5FA;margin:0 auto;padding:20px;max-width:560px}"
                + "h1{font-size:22px;margin:0 0 6px}h2{font-size:17px;margin:24px 0 8px;color:#FF8A65}"
                + "label{display:block;margin:10px 0 4px;color:#94A3B8;font-size:14px}"
                + "input{width:100%;box-sizing:border-box;padding:12px;border-radius:10px;border:1px solid #2B3D5C;"
                + "background:#1A263B;color:#F2F5FA;font-size:16px}"
                + "button{margin-top:22px;width:100%;padding:14px;border:0;border-radius:24px;background:#E53935;color:#fff;font-size:17px}"
                + ".ok{background:#1A263B;border-left:4px solid #4CAF50;border-radius:10px;padding:14px;margin:12px 0}"
                + "p{color:#94A3B8;font-size:14px}</style></head><body><h1>ARGEDA TV</h1>"
                + "<p>Заполните одно: ссылку M3U <b>или</b> данные Xtream Codes.</p>"
                + (done ? "<div class=ok>✓ Отправлено на телевизор. Проверьте данные на экране и нажмите «Сохранить».</div>" : "")
                + "<form method=post><label>Название (необязательно)</label><input name=name autocomplete=off>"
                + "<h2>Ссылка на плейлист M3U</h2><input name=url inputmode=url autocomplete=off autocapitalize=none placeholder='http://…'>"
                + "<h2>или Xtream Codes</h2><label>Адрес сервера</label>"
                + "<input name=server inputmode=url autocomplete=off autocapitalize=none placeholder='http://example.com:8080'>"
                + "<label>Логин</label><input name=user autocomplete=off autocapitalize=none>"
                + "<label>Пароль</label><input name=pass type=password autocomplete=off>"
                + "<h2>Телепрограмма XMLTV (необязательно)</h2>"
                + "<input name=epg inputmode=url autocomplete=off autocapitalize=none placeholder='http://…/epg.xml.gz'>"
                + "<button>Отправить на телевизор</button></form>"
                + "<p>Данные идут только по вашей домашней сети прямо на приставку.</p></body></html>";
    }
}
