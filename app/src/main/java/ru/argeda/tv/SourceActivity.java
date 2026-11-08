package ru.argeda.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Добавление и изменение плейлиста: ссылка M3U, Xtream Codes или файл; на ТВ — ввод с телефона. */
public class SourceActivity extends Activity {
    private static final int PICK = 7;

    private Store st;
    private Source src;
    private boolean isNew;
    private String type = "m3u", archive = "auto";
    private static final String[] ARCH_IDS = {"auto", "shift", "flussonic", "xc", "none"};
    private static final String[] ARCH_NAMES = {"Автоматически", "Сдвиг (?utc=…&lutc=…)", "Flussonic", "Xtream Codes", "Не использовать"};
    private EditText name, url, server, user, pass, epg, ua;
    private TextView tM3u, tXtream, tFile, fileInfo, phone;
    private View gM3u, gXtream, gFile;
    private File picked;
    private LinkServer link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        Themes.apply(this, base, false);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_source);
        Ui.tvSafeArea(this);
        st = Store.get(this);
        name = findViewById(R.id.name);
        url = findViewById(R.id.url);
        server = findViewById(R.id.server);
        user = findViewById(R.id.user);
        pass = findViewById(R.id.pass);
        epg = findViewById(R.id.epg);
        ua = findViewById(R.id.ua);
        tM3u = findViewById(R.id.tM3u);
        tXtream = findViewById(R.id.tXtream);
        tFile = findViewById(R.id.tFile);
        fileInfo = findViewById(R.id.fileInfo);
        phone = findViewById(R.id.phone);
        gM3u = findViewById(R.id.gM3u);
        gXtream = findViewById(R.id.gXtream);
        gFile = findViewById(R.id.gFile);

        if (getResources().getConfiguration().screenWidthDp > 760) {
            View form = findViewById(R.id.form);
            ViewGroup.LayoutParams lp = form.getLayoutParams();
            lp.width = Ui.dp(this, 700);
            form.setLayoutParams(lp);
        }

        src = st.byId(getIntent().getStringExtra("id"));
        isNew = src == null;
        if (isNew) {
            src = new Source();
            src.id = Long.toString(System.currentTimeMillis(), 36);
        } else if (b == null) {
            name.setText(src.name);
            url.setText(src.url);
            server.setText(src.server);
            user.setText(src.user);
            pass.setText(src.pass);
            epg.setText(src.epg);
            ua.setText(src.ua);
        }
        if (b != null) src.id = b.getString("sid", src.id);
        picked = new File(getFilesDir(), "pick_" + src.id + ".tmp");
        if (b == null) Store.delete(picked);
        else if (picked.exists()) fileInfo.setText("Файл выбран — нажмите «Сохранить»");
        if (!isNew && "file".equals(src.type) && !picked.exists()) fileInfo.setText("Файл загружен ранее. Можно выбрать другой.");

        archive = b != null ? b.getString("archive", src.archive) : src.archive;
        TextView arch = findViewById(R.id.archive);
        arch.setText(archName(archive));
        arch.setOnClickListener(v -> {
            int sel = 0;
            for (int i = 0; i < ARCH_IDS.length; i++) if (ARCH_IDS[i].equals(archive)) sel = i;
            new AlertDialog.Builder(this).setTitle("Тип архива")
                    .setSingleChoiceItems(ARCH_NAMES, sel, (d, w) -> {
                        archive = ARCH_IDS[w];
                        arch.setText(archName(archive));
                        d.dismiss();
                    }).show();
        });
        ((TextView) findViewById(R.id.title)).setText(isNew ? "Новый плейлист" : "Плейлист «" + src.title() + "»");
        setType(b != null ? b.getString("type", src.type) : src.type);

        tM3u.setOnClickListener(v -> setType("m3u"));
        tXtream.setOnClickListener(v -> setType("xtream"));
        tFile.setOnClickListener(v -> setType("file"));
        findViewById(R.id.pick).setOnClickListener(v -> pickFile());
        findViewById(R.id.save).setOnClickListener(v -> save());
        findViewById(R.id.cancel).setOnClickListener(v -> finish());
        View del = findViewById(R.id.delete);
        del.setVisibility(isNew ? View.GONE : View.VISIBLE);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage("Удалить плейлист «" + src.title() + "» с этого устройства?")
                .setPositiveButton("Удалить", (d, w) -> {
                    st.removeSource(src);
                    Store.delete(picked);
                    finish();
                })
                .setNegativeButton("Отмена", null).show());
        if (isNew && Ui.isTv(this)) url.requestFocus();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("type", type);
        out.putString("archive", archive);
        out.putString("sid", src.id);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Ui.isTv(this)) return;
        String ip = LinkServer.localIp();
        if (ip == null) return;
        link = new LinkServer();
        if (!link.start(f -> runOnUiThread(() -> received(f)))) {
            link = null;
            return;
        }
        phone.setText("📱 Удобнее ввести с телефона. Подключите телефон к той же сети Wi-Fi и откройте в браузере:\n\n"
                + "http://" + ip + ":" + link.port + "/" + link.code
                + "\n\nСсылка или логин появятся на этом экране — останется нажать «Сохранить».");
        phone.setVisibility(View.VISIBLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (link != null) link.stop();
        link = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdown();
    }

    private static String archName(String id) {
        for (int i = 0; i < ARCH_IDS.length; i++) if (ARCH_IDS[i].equals(id)) return ARCH_NAMES[i];
        return ARCH_NAMES[0];
    }

    private void setType(String t) {
        type = "xtream".equals(t) || "file".equals(t) ? t : "m3u";
        tM3u.setSelected("m3u".equals(type));
        tXtream.setSelected("xtream".equals(type));
        tFile.setSelected("file".equals(type));
        gM3u.setVisibility("m3u".equals(type) ? View.VISIBLE : View.GONE);
        gXtream.setVisibility("xtream".equals(type) ? View.VISIBLE : View.GONE);
        gFile.setVisibility("file".equals(type) ? View.VISIBLE : View.GONE);
    }

    private void received(Map<String, String> f) {
        String u = trim(f.get("url")), sv = trim(f.get("server"));
        if (!u.isEmpty()) {
            setType("m3u");
            url.setText(u);
        } else if (!sv.isEmpty()) {
            setType("xtream");
            server.setText(sv);
            user.setText(trim(f.get("user")));
            pass.setText(trim(f.get("pass")));
        }
        String e = trim(f.get("epg")), n = trim(f.get("name"));
        if (!e.isEmpty()) epg.setText(e);
        if (!n.isEmpty()) name.setText(n);
        Ui.toast(this, "Данные получены с телефона. Проверьте и нажмите «Сохранить»");
        findViewById(R.id.save).requestFocus();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    // ---------- Файл ----------

    @SuppressWarnings("deprecation")
    private void pickFile() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK);
            return;
        } catch (ActivityNotFoundException ignored) {
        }
        try {
            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK);
        } catch (ActivityNotFoundException e) {
            Ui.toast(this, "На этом устройстве нет выбора файлов. Используйте ссылку M3U");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        fileInfo.setText("Читаю файл…");
        io.execute(() -> {
            String msg;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                if (in == null) throw new java.io.IOException("Файл недоступен");
                String t = Net.text(Net.readAll(in, 64 << 20));
                int n = M3u.parse(t).list.size();
                if (n == 0) msg = "В файле нет каналов — нужен плейлист .m3u или .m3u8";
                else {
                    Store.writeFile(picked, t);
                    msg = "Файл выбран: каналов — " + n + ". Нажмите «Сохранить».";
                }
            } catch (Exception e) {
                msg = "Не удалось прочитать файл: " + Store.describe(e);
            }
            final String m = msg;
            runOnUiThread(() -> fileInfo.setText(m));
        });
    }

    // ---------- Сохранение ----------

    private void save() {
        String n = name.getText().toString().trim(), u = url.getText().toString().trim();
        String sv = server.getText().toString().trim(), us = user.getText().toString().trim(), pw = pass.getText().toString().trim();
        String ep = epg.getText().toString().trim(), ag = ua.getText().toString().trim();
        File cache = st.cacheFile(src);
        switch (type) {
            case "m3u":
                if (!u.matches("(?i)https?://\\S+")) {
                    Ui.toast(this, "Ссылка должна начинаться с http:// или https:// и быть без пробелов");
                    url.requestFocus();
                    return;
                }
                break;
            case "xtream":
                if (sv.isEmpty() || us.isEmpty() || pw.isEmpty()) {
                    Ui.toast(this, "Заполните адрес сервера, логин и пароль");
                    (sv.isEmpty() ? server : us.isEmpty() ? user : pass).requestFocus();
                    return;
                }
                break;
            default:
                boolean hadFile = !isNew && "file".equals(src.type) && cache.exists();
                if (!picked.exists() && !hadFile) {
                    Ui.toast(this, "Выберите файл плейлиста");
                    return;
                }
        }
        if (!ep.isEmpty() && !ep.matches("(?i)https?://\\S+")) {
            Ui.toast(this, "Адрес телепрограммы должен начинаться с http:// или https://");
            epg.requestFocus();
            return;
        }

        String oldKey = src.type + "\n" + src.url + "\n" + src.server + "\n" + src.user + "\n" + src.pass + "\n" + src.ua;
        String oldEpg = src.epg;
        src.type = type;
        src.name = n;
        src.url = "m3u".equals(type) ? u : "";
        src.server = "xtream".equals(type) ? sv : "";
        src.user = "xtream".equals(type) ? us : "";
        src.pass = "xtream".equals(type) ? pw : "";
        src.epg = ep;
        src.ua = ag;
        src.archive = archive;
        String newKey = src.type + "\n" + src.url + "\n" + src.server + "\n" + src.user + "\n" + src.pass + "\n" + src.ua;

        if ("file".equals(type)) {
            if (picked.exists()) {
                Store.delete(cache);
                if (!picked.renameTo(cache)) {
                    Ui.toast(this, "Не удалось сохранить файл");
                    return;
                }
            }
        } else if (isNew || !oldKey.equals(newKey)) {
            Store.delete(cache); // новая ссылка — скачаем каналы заново
        }
        if (!ep.equals(oldEpg)) Store.delete(st.epgFile(src));
        st.putSource(src);
        finish();
    }
}
