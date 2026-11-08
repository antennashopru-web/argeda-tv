package ru.argeda.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Главный экран: группы, каналы с текущей передачей, справа — окно предпросмотра и телепрограмма
 * выбранного канала с записями. На узком экране телефона предпросмотр сверху, список снизу.
 */
@OptIn(markerClass = UnstableApi.class)
public class MainActivity extends Activity implements Store.Listener, Playback.Listener {
    private static final String G_ALL = "\u0001all", G_FAV = "\u0001fav", G_REC = "\u0001rec";

    private static final class Group {
        final String id, title;
        final int count;

        Group(String id, String title, int count) {
            this.id = id;
            this.title = title;
            this.count = count;
        }
    }

    private Store st;
    private Playback pb;
    private RecyclerView groupsView, channelsView, epgList;
    private PlayerView preview;
    private View pv, pBuf, pArch, epgBox, tChannels, tEpg;
    private TextView pMsg, pTitle, pNow, pDesc, epgEmpty;
    private ProgressBar pProg;
    private EpgAdapter ea;
    /** Выбранный канал: его показывают окно предпросмотра и телепрограмма. */
    private Channel sel;
    private Runnable previewRun;
    /** Была ли телепрограмма, когда панель канала заполнялась (чтобы обновить её, когда программа загрузится). */
    private boolean infoHadEpg;
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final List<Group> groups = new ArrayList<>();
    private final GroupAdapter ga = new GroupAdapter();
    private ChannelAdapter ca;
    private TextView source, loadingText, emptyText, emptyBtn;
    private EditText search;
    private View body, empty, loading;
    private boolean narrow, focusLast = true;
    private String curGroup = G_ALL, listKey = "";
    private int seenSeq;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Заставка при запуске; пока она видна, звук предпросмотра выключен, чтобы не мешать голосу. */
    private Splash splash;
    private final Runnable filterRun = this::applyFilter;
    private Runnable groupRun;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (st.hasEpg()) ca.notifyItemRangeChanged(0, ca.getItemCount(), "t");
            if (sel != null) updateNow(sel);
            ui.postDelayed(this, 60_000);
        }
    };

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        Themes.apply(this, base, false);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        Ui.tvSafeArea(this);
        st = Store.get(this);
        pb = Playback.get(this);
        Logos.init(this);
        groupsView = findViewById(R.id.groups);
        channelsView = findViewById(R.id.channels);
        source = findViewById(R.id.source);
        search = findViewById(R.id.search);
        body = findViewById(R.id.body);
        empty = findViewById(R.id.empty);
        loading = findViewById(R.id.loading);
        loadingText = findViewById(R.id.loadingText);
        emptyText = findViewById(R.id.emptyText);
        emptyBtn = findViewById(R.id.emptyBtn);

        pv = findViewById(R.id.pv);
        preview = findViewById(R.id.preview);
        pBuf = findViewById(R.id.pBuf);
        pArch = findViewById(R.id.pArch);
        pMsg = findViewById(R.id.pMsg);
        pTitle = findViewById(R.id.pTitle);
        pNow = findViewById(R.id.pNow);
        pProg = findViewById(R.id.pProg);
        pDesc = findViewById(R.id.pDesc);
        epgList = findViewById(R.id.epgList);
        epgEmpty = findViewById(R.id.epgEmpty);
        epgBox = findViewById(R.id.epgBox);
        tChannels = findViewById(R.id.tChannels);
        tEpg = findViewById(R.id.tEpg);

        // Разметка выбирается по ширине экрана (layout-w600dp): на узкой нет колонки групп.
        narrow = groupsView == null;
        if (b != null) curGroup = b.getString("g", G_ALL);

        if (groupsView != null) {
            groupsView.setLayoutManager(new LinearLayoutManager(this));
            groupsView.setAdapter(ga);
            groupsView.setItemAnimator(null);
        }
        if (tChannels != null) {
            tChannels.setOnClickListener(v -> setTab(false));
            tEpg.setOnClickListener(v -> setTab(true));
            setTab(false);
        }
        pv.setOnClickListener(v -> {
            if (sel != null) openFull(sel);
        });
        pv.setOnKeyListener((v, code, e) -> {
            if (e.getAction() == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                focusChannel(indexOfSel());
                return true;
            }
            return false;
        });

        ea = new EpgAdapter(new EpgAdapter.Listener() {
            @Override
            public void click(int pos) {
                if (sel != null && pos < ea.rows().size()) openFromEpg(sel, ea.rows().get(pos));
            }

            @Override
            public void focus(int pos) {
                if (pDesc == null || pos >= ea.rows().size()) return;
                Epg.Prog p = ea.rows().get(pos).p;
                showDesc(p);
            }

            @Override
            public boolean key(int pos, int code) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && !narrow) {
                    focusChannel(indexOfSel());
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return true;
                return false;
            }
        });
        epgList.setLayoutManager(new LinearLayoutManager(this));
        epgList.setAdapter(ea);
        epgList.setItemAnimator(null);

        ca = new ChannelAdapter(st, new ChannelAdapter.Listener() {
            @Override
            public void click(int pos) {
                List<Channel> l = ca.items();
                if (pos < 0 || pos >= l.size()) return;
                Channel c = l.get(pos);
                if (!channelsView.isInTouchMode() || !st.preview() || (c == sel && same(pb.channel, c))) openFull(c);
                else select(c, PREVIEW_NOW); // касание: сначала в окне, второе касание — на весь экран
            }

            @Override
            public void longClick(int pos) {
                toggleFav(pos);
            }

            @Override
            public void focus(int pos) {
                List<Channel> l = ca.items();
                if (pos >= 0 && pos < l.size() && l.get(pos) != sel) select(l.get(pos), PREVIEW_LATER);
            }

            @Override
            public boolean key(int pos, int code) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && !narrow) {
                    focusGroup();
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT && !narrow) {
                    if (ea.getItemCount() > 0) focusEpg();
                    else pv.requestFocus();
                    return true;
                }
                return false;
            }
        });
        channelsView.setLayoutManager(new LinearLayoutManager(this));
        channelsView.setAdapter(ca);
        channelsView.setItemAnimator(null);
        channelsView.setHasFixedSize(true);

        source.setOnClickListener(v -> {
            if (narrow) pickGroup();
            else pickSource();
        });
        findViewById(R.id.menu).setOnClickListener(v -> menu());
        emptyBtn.setOnClickListener(v -> {
            Source s = st.current();
            if (s == null) addSource();
            else editSource(s);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable e) {
                ui.removeCallbacks(filterRun);
                ui.postDelayed(filterRun, 250);
            }
        });
        search.setOnEditorActionListener((v, a, e) -> {
            hideKeyboard();
            ui.removeCallbacks(filterRun);
            applyFilter();
            focusChannel(0);
            return true;
        });

        if (b == null && st.splash()) splash = Splash.show(this, this::splashDone);
    }

    /** Звук канала плавно нарастает, пока доигрывает хвост озвучки заставки. */
    private void splashDone() {
        android.animation.ValueAnimator va = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(1800);
        va.setStartDelay(300);
        va.addUpdateListener(an -> {
            if (pb.player != null) pb.player.setVolume((float) an.getAnimatedValue());
        });
        va.start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Пока идёт заставка, любая кнопка пульта только пропускает её.
        if (splash != null && splash.showing()) {
            if (e.getAction() == KeyEvent.ACTION_UP) splash.skip();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    protected void onStart() {
        super.onStart();
        pb.acquire();
        preview.setPlayer(pb.player);
        if (splash != null && splash.showing() && pb.player != null) pb.player.setVolume(0f);
        pb.addListener(this);
        if (!st.preview() && pb.channel != null) pb.stop();
        playbackChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (splash != null) splash.cancel();
        splash = null;
        ui.removeCallbacks(previewRun);
        ui.removeCallbacks(updRun);
        pb.removeListener(this);
        preview.setPlayer(null);
        pb.release();
    }

    @Override
    protected void onResume() {
        super.onResume();
        st.listener = this;
        Source s = st.current();
        if (s != null && !s.id.equals(st.loadedId) && !s.id.equals(st.loadingId)) st.load(false);
        render();
        ui.postDelayed(tick, 60_000);
        Updater.resume(this);
        ui.postDelayed(updRun, 4000); // проверка обновлений не мешает запуску канала
    }

    /** Окно обновления не показываем поверх заставки: ждём, пока она закончится. */
    private final Runnable updRun = new Runnable() {
        @Override
        public void run() {
            if (splash != null && splash.showing()) ui.postDelayed(this, 1500);
            else Updater.check(MainActivity.this, false);
        }
    };

    @Override
    protected void onPause() {
        super.onPause();
        st.listener = null;
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            st.setShowAdult(false);
            pb.stop();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("g", curGroup);
    }

    @Override
    public void changed() {
        render();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            menu();
            return true;
        }
        if (code == KeyEvent.KEYCODE_SEARCH) {
            search.requestFocus();
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(search, 0);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (search.length() > 0) {
            search.setText("");
            return;
        }
        boolean list = body.getVisibility() == View.VISIBLE && ca.getItemCount() > 0;
        // Пульт: «Назад» из групп, телепрограммы, окна или верхней строки сначала возвращает в список каналов.
        if (list && !narrow && !channelsView.hasFocus() && !channelsView.isInTouchMode()) {
            focusChannel(indexOfSel());
            return;
        }
        // Телефон: с вкладки «Телепрограмма» — на «Каналы».
        if (list && tEpg != null && tEpg.isSelected()) {
            setTab(false);
            return;
        }
        // Выход — только по второму нажатию: случайное «Назад» на пульте не закрывает приложение.
        long now = System.currentTimeMillis();
        if (now - backAt > 2500) {
            backAt = now;
            Ui.toast(this, "Нажмите «Назад» ещё раз, чтобы выйти");
            return;
        }
        super.onBackPressed();
    }

    private long backAt;

    // ---------- Отрисовка ----------

    private void render() {
        Source s = st.current();
        List<Channel> vis = st.visible();
        if (st.statusSeq != seenSeq) {
            seenSeq = st.statusSeq;
            if (!st.status.isEmpty() && !vis.isEmpty()) Ui.toast(this, "Не удалось обновить плейлист: " + st.status + ". Показаны сохранённые каналы");
        }
        if (s == null) {
            show(empty);
            emptyText.setText("Добавьте плейлист вашего IPTV-провайдера");
            emptyBtn.setText("＋  Добавить плейлист");
            source.setText("Плейлисты  ▾");
            return;
        }
        if (!narrow) source.setText(s.title() + "  ▾");
        if (vis.isEmpty()) {
            if (st.busy) {
                show(loading);
                loadingText.setText("Загружаю «" + s.title() + "»…");
            } else {
                show(empty);
                emptyText.setText(st.status.isEmpty() ? "В плейлисте нет каналов" : "Не удалось загрузить плейлист\n" + st.status);
                emptyBtn.setText("Изменить плейлист");
            }
            if (narrow) source.setText("Плейлисты  ▾");
            return;
        }
        show(body);
        buildGroups();
        applyFilter();
        if (sel != null) {
            Channel fresh = findSame(vis, sel);
            if (fresh == null) {
                sel = null;
                ca.current = null;
            } else if (fresh != sel || infoHadEpg != st.hasEpg()) {
                sel = fresh;
                ca.current = fresh;
                showInfo(fresh);
            }
        }
        if (sel == null) showInfo(null);
    }

    private void show(View v) {
        body.setVisibility(v == body ? View.VISIBLE : View.GONE);
        empty.setVisibility(v == empty ? View.VISIBLE : View.GONE);
        loading.setVisibility(v == loading ? View.VISIBLE : View.GONE);
        if (v == empty && !narrow) emptyBtn.requestFocus();
    }

    @SuppressWarnings("NotifyDataSetChanged")
    private void buildGroups() {
        List<Channel> vis = st.visible();
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        int favN = 0;
        for (Channel c : vis) {
            Integer n = m.get(c.group);
            m.put(c.group, n == null ? 1 : n + 1);
            if (st.isFav(c)) favN++;
        }
        groups.clear();
        if (favN > 0) groups.add(new Group(G_FAV, "★  Избранное", favN));
        int recN = recentOf(vis).size();
        if (recN > 0) groups.add(new Group(G_REC, "Недавние", recN));
        groups.add(new Group(G_ALL, "Все каналы", vis.size()));
        for (Map.Entry<String, Integer> e : m.entrySet()) groups.add(new Group(e.getKey(), Channel.groupTitle(e.getKey()), e.getValue()));
        if (indexOfGroup(curGroup) < 0) curGroup = G_ALL;
        ga.notifyDataSetChanged();
        if (narrow) source.setText(titleOf(curGroup) + "  ▾");
    }

    private void applyFilter() {
        String q = norm(search.getText().toString());
        List<Channel> vis = st.visible(), res;
        if (!q.isEmpty()) {
            res = new ArrayList<>();
            for (Channel c : vis) if (norm(c.name).contains(q) || String.valueOf(c.num).equals(q)) res.add(c);
        } else {
            res = channelsFor(curGroup, vis);
        }
        String key = q.isEmpty() ? "g:" + curGroup : "q:" + q;
        boolean same = key.equals(listKey);
        listKey = key;
        ca.set(res);
        if (!same) channelsView.scrollToPosition(0);
        if (focusLast && !res.isEmpty()) {
            focusLast = false;
            int i = indexOfKey(res, st.lastKey);
            if (pb.channel != null) {
                Channel playing = findSame(res, pb.channel);
                if (playing != null) i = res.indexOf(playing);
            }
            if (i >= 0 || !narrow) {
                i = Math.max(0, i);
                // На приставке последний канал включается в окне сразу при запуске, на телефоне — только по касанию.
                select(res.get(i), channelsView.isInTouchMode() ? PREVIEW_NONE : PREVIEW_NOW);
                focusChannel(i);
            }
        }
    }

    private List<Channel> channelsFor(String g, List<Channel> vis) {
        if (G_ALL.equals(g)) return vis;
        if (G_REC.equals(g)) return recentOf(vis);
        List<Channel> r = new ArrayList<>();
        boolean f = G_FAV.equals(g);
        for (Channel c : vis) if (f ? st.isFav(c) : g.equals(c.group)) r.add(c);
        return r;
    }

    private List<Channel> recentOf(List<Channel> vis) {
        List<Channel> r = new ArrayList<>();
        if (st.recent.isEmpty()) return r;
        Map<String, Channel> m = new HashMap<>();
        for (Channel c : vis) if (!m.containsKey(c.key())) m.put(c.key(), c);
        for (String k : st.recent) {
            Channel c = m.get(k);
            if (c != null) r.add(c);
        }
        return r;
    }

    private static String norm(String s) {
        return s.trim().toLowerCase(Locale.getDefault()).replace('ё', 'е');
    }

    private static int indexOfKey(List<Channel> l, String key) {
        if (key == null || key.isEmpty()) return -1;
        for (int i = 0; i < l.size(); i++) if (l.get(i).key().equals(key)) return i;
        return -1;
    }

    private int indexOfGroup(String id) {
        for (int i = 0; i < groups.size(); i++) if (groups.get(i).id.equals(id)) return i;
        return -1;
    }

    private String titleOf(String id) {
        if (G_ALL.equals(id)) return "Все каналы";
        if (G_FAV.equals(id)) return "Избранное";
        if (G_REC.equals(id)) return "Недавние";
        return Channel.groupTitle(id);
    }

    // ---------- Фокус для пульта ----------

    private void focusChannel(int pos) {
        if (pos < 0 || pos >= ca.getItemCount()) return;
        ((LinearLayoutManager) channelsView.getLayoutManager()).scrollToPositionWithOffset(pos, channelsView.getHeight() / 3);
        focusAt(channelsView, pos, 10);
    }

    private void focusGroup() {
        int i = Math.max(0, indexOfGroup(curGroup));
        ((LinearLayoutManager) groupsView.getLayoutManager()).scrollToPositionWithOffset(i, groupsView.getHeight() / 3);
        focusAt(groupsView, i, 10);
    }

    private void focusAt(RecyclerView rv, int pos, int tries) {
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh != null) vh.itemView.requestFocus();
        else if (tries > 0) rv.postDelayed(() -> focusAt(rv, pos, tries - 1), 40);
    }

    private int firstVisibleChannel() {
        int i = ((LinearLayoutManager) channelsView.getLayoutManager()).findFirstVisibleItemPosition();
        return Math.max(0, i);
    }

    private void selectGroup(String id, boolean moveFocus) {
        boolean changed = !id.equals(curGroup) || search.length() > 0;
        curGroup = id;
        if (search.length() > 0) search.setText("");
        ui.removeCallbacks(filterRun);
        ga.notifyItemRangeChanged(0, groups.size(), "sel");
        if (changed) applyFilter();
        if (narrow) source.setText(titleOf(id) + "  ▾");
        if (moveFocus && !narrow) focusChannel(0);
    }

    // ---------- Действия ----------

    // ---------- Предпросмотр и телепрограмма ----------

    private static final int PREVIEW_NONE = 0, PREVIEW_LATER = 1, PREVIEW_NOW = 2;

    private void select(Channel c, int mode) {
        Channel old = sel;
        sel = c;
        ca.current = c;
        List<Channel> l = ca.items();
        if (old != null && l.contains(old)) ca.notifyItemChanged(l.indexOf(old), "sel");
        if (l.contains(c)) ca.notifyItemChanged(l.indexOf(c), "sel");
        showInfo(c);
        ui.removeCallbacks(previewRun);
        if (mode == PREVIEW_NONE || !st.preview() || (same(pb.channel, c) && !pb.isArchive())) return;
        previewRun = () -> {
            if (sel == c) pb.play(c);
        };
        if (mode == PREVIEW_NOW) previewRun.run();
        else ui.postDelayed(previewRun, 600); // пока листают список, поток не дёргаем
    }

    private void showInfo(Channel c) {
        if (c == null) {
            pTitle.setText(st.visible().isEmpty() ? "" : "Выберите канал");
            pNow.setText("");
            pProg.setVisibility(View.GONE);
            if (pDesc != null) pDesc.setVisibility(View.GONE);
            ea.set(new ArrayList<>());
            epgEmpty.setVisibility(View.GONE);
            return;
        }
        infoHadEpg = st.hasEpg();
        pTitle.setText(c.num + "   " + c.name);
        updateNow(c);
        long now = System.currentTimeMillis();
        List<EpgAdapter.Row> rows = EpgAdapter.build(c, st.current(), now);
        ea.playing = playingStart(c);
        ea.set(rows);
        if (rows.isEmpty()) {
            Source s = st.current();
            epgEmpty.setText(st.epgUrl(s).isEmpty()
                    ? "Телепрограммы нет. Адрес телепрограммы (XMLTV) можно добавить в настройках плейлиста — тогда здесь будут передачи, а у каналов с архивом — записи."
                    : st.epgLoading() ? "Загружаю телепрограмму…" : "Для этого канала телепрограммы нет.");
            epgEmpty.setVisibility(View.VISIBLE);
        } else {
            epgEmpty.setVisibility(View.GONE);
            int i = EpgAdapter.nowIndex(rows);
            ((LinearLayoutManager) epgList.getLayoutManager()).scrollToPositionWithOffset(i, Ui.dp(this, 60));
        }
    }

    private void updateNow(Channel c) {
        long t = System.currentTimeMillis();
        Epg.Prog p = Epg.at(c.progs, t);
        int days = Catchup.days(c, st.current());
        if (p != null) {
            pNow.setText(hm.format(new Date(p.start)) + "–" + hm.format(new Date(p.stop)) + "   " + p.title);
            pProg.setProgress((int) ((t - p.start) * 1000 / Math.max(1, p.stop - p.start)));
            pProg.setVisibility(View.VISIBLE);
        } else {
            pNow.setText(c.groupTitle() + (days > 0 ? "   ·   архив " + days + " " + dayWord(days) : ""));
            pProg.setVisibility(View.GONE);
        }
        showDesc(p);
    }

    private void showDesc(Epg.Prog p) {
        if (pDesc == null) return;
        if (p != null && p.desc != null && !p.desc.isEmpty()) {
            pDesc.setText(p.desc);
            pDesc.setVisibility(View.VISIBLE);
        } else pDesc.setVisibility(View.GONE);
    }

    private static String dayWord(int n) {
        int m10 = n % 10, m100 = n % 100;
        if (m10 == 1 && m100 != 11) return "день";
        if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return "дня";
        return "дней";
    }

    private long playingStart(Channel c) {
        if (!same(pb.channel, c)) return -1;
        return pb.isArchive() ? pb.archStart : 0;
    }

    private void focusEpg() {
        int i = EpgAdapter.nowIndex(ea.rows());
        ((LinearLayoutManager) epgList.getLayoutManager()).scrollToPositionWithOffset(i, Ui.dp(this, 60));
        focusAt(epgList, i, 10);
    }

    private int indexOfSel() {
        return sel == null ? 0 : Math.max(0, ca.items().indexOf(sel));
    }

    private void setTab(boolean epg) {
        if (tChannels == null) return;
        tChannels.setSelected(!epg);
        tEpg.setSelected(epg);
        channelsView.setVisibility(epg ? View.GONE : View.VISIBLE);
        epgBox.setVisibility(epg ? View.VISIBLE : View.GONE);
    }

    static boolean same(Channel a, Channel b) {
        return a != null && b != null && (a == b || a.url.equals(b.url));
    }

    private static Channel findSame(List<Channel> l, Channel c) {
        if (c == null) return null;
        for (Channel x : l) if (same(x, c)) return x;
        return null;
    }

    /** Строка телепрограммы: запись — смотреть с начала передачи, текущая — прямой эфир. */
    private void openFromEpg(Channel c, EpgAdapter.Row r) {
        long now = System.currentTimeMillis();
        if (r.future) {
            Ui.toast(this, "Передача начнётся в " + hm.format(new Date(r.p.start)));
            return;
        }
        if (r.now) {
            // Текущая передача — прямой эфир; «с начала» — в телепрограмме на полном экране.
            if (!same(pb.channel, c) || pb.isArchive()) pb.play(c);
            openFull(c);
            return;
        }
        if (!r.archive) {
            Ui.toast(this, Catchup.days(c, st.current()) > 0 ? "Эта передача уже не хранится в архиве" : "У этого канала нет архива");
            return;
        }
        pb.playArchive(c, r.p.start, r.p.start, Math.min(r.p.stop, now), r.slot ? "" : r.p.title);
        openFull(c);
    }

    /** На весь экран: тот же поток продолжает играть без новой загрузки. */
    private void openFull(Channel c) {
        List<Channel> l = ca.items();
        int i = l.indexOf(c);
        if (i < 0) {
            l = st.visible();
            i = Math.max(0, l.indexOf(c));
        }
        st.playList = new ArrayList<>(l);
        st.playIndex = i;
        st.playTitle = search.length() > 0 ? "Поиск: " + search.getText().toString().trim() : titleOf(curGroup);
        st.played(c);
        if (!same(pb.channel, c)) pb.play(c);
        ui.removeCallbacks(previewRun);
        focusLast = true;
        startActivity(new Intent(this, PlayerActivity.class).putExtra("key", c.key()));
    }

    @Override
    public void playbackChanged() {
        boolean on = pb.channel != null;
        Ui.keepScreenOn(this, pb.active());
        pBuf.setVisibility(on && pb.buffering && pb.error.isEmpty() ? View.VISIBLE : View.GONE);
        pArch.setVisibility(on && pb.isArchive() ? View.VISIBLE : View.GONE);
        String msg = !pb.error.isEmpty() && on ? pb.error
                : on ? "" : !st.preview() ? "Предпросмотр выключен.\nOK — смотреть на весь экран"
                : channelsView.isInTouchMode() ? "Коснитесь канала, чтобы посмотреть его здесь" : "";
        pMsg.setText(msg);
        pMsg.setVisibility(msg.isEmpty() ? View.GONE : View.VISIBLE);
        if (sel != null && ea.getItemCount() > 0) {
            long ps = playingStart(sel);
            if (ps != ea.playing) {
                ea.playing = ps;
                ea.notifyItemRangeChanged(0, ea.getItemCount(), "p");
            }
        }
    }

    private void toggleFav(int pos) {
        List<Channel> l = ca.items();
        if (pos < 0 || pos >= l.size()) return;
        Channel c = l.get(pos);
        boolean on = st.toggleFav(c);
        Ui.toast(this, on ? "«" + c.name + "» добавлен в избранное" : "«" + c.name + "» убран из избранного");
        boolean favList = G_FAV.equals(curGroup) && search.length() == 0;
        buildGroups();
        if (favList) applyFilter();
        else ca.notifyItemChanged(pos, "t");
    }

    private void addSource() {
        startActivity(new Intent(this, SourceActivity.class));
    }

    private void editSource(Source s) {
        startActivity(new Intent(this, SourceActivity.class).putExtra("id", s.id));
    }

    private void pickSource() {
        Source cur = st.current();
        List<String> labels = new ArrayList<>();
        for (Source s : st.sources) labels.add((cur != null && s.id.equals(cur.id) ? "✓  " : "      ") + s.title());
        labels.add("＋  Добавить плейлист");
        if (cur != null) labels.add("✎  Изменить «" + cur.title() + "»");
        new AlertDialog.Builder(this).setTitle("Плейлисты").setItems(labels.toArray(new String[0]), (d, w) -> {
            if (w < st.sources.size()) {
                Source s = st.sources.get(w);
                if (cur == null || !s.id.equals(cur.id)) {
                    st.select(s);
                    curGroup = G_ALL;
                    search.setText("");
                    focusLast = true;
                    st.load(false);
                }
            } else if (w == st.sources.size()) addSource();
            else if (cur != null) editSource(cur);
        }).show();
    }

    private void pickGroup() {
        Source s = st.current();
        if (groups.isEmpty() || s == null || body.getVisibility() != View.VISIBLE) {
            pickSource();
            return;
        }
        String[] labels = new String[groups.size()];
        int sel = 0;
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            labels[i] = g.title + "  (" + g.count + ")";
            if (g.id.equals(curGroup)) sel = i;
        }
        new AlertDialog.Builder(this).setTitle(s.title()).setSingleChoiceItems(labels, sel, (d, w) -> {
            d.dismiss();
            selectGroup(groups.get(w).id, true);
        }).show();
    }

    private void menu() {
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        Source s = st.current();
        if (narrow || s == null) {
            items.add("Плейлисты…");
            acts.add(this::pickSource);
        }
        if (s != null) {
            items.add("Обновить плейлист");
            acts.add(() -> {
                st.load(true);
                Ui.toast(this, "Обновляю плейлист…");
            });
            if (!st.epgUrl(s).isEmpty()) {
                items.add("Обновить телепрограмму");
                acts.add(() -> {
                    st.loadEpg(s, true);
                    Ui.toast(this, "Загружаю телепрограмму…");
                });
            }
        }
        items.add(st.preview() ? "Окно предпросмотра: выключить" : "Окно предпросмотра: включить");
        acts.add(() -> {
            st.setPreview(!st.preview());
            if (!st.preview()) pb.stop();
            else if (sel != null) select(sel, PREVIEW_NOW);
            playbackChanged();
        });
        if (st.adultCount() > 0) {
            items.add(st.showAdult ? "Скрыть каналы 18+" : "Показать каналы 18+");
            acts.add(this::toggleAdult);
        }
        if (st.hasPin()) {
            items.add("Изменить PIN-код");
            acts.add(this::changePin);
        }
        items.add(st.splash() ? "Заставка при запуске: выключить" : "Заставка при запуске: включить");
        acts.add(() -> st.setSplash(!st.splash()));
        items.add("Тема: " + Themes.NAMES[Themes.get(this)]);
        acts.add(this::chooseTheme);
        items.add("Проверить обновления");
        acts.add(() -> Updater.check(this, true));
        items.add("О приложении");
        acts.add(this::about);
        new AlertDialog.Builder(this).setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    private void chooseTheme() {
        new AlertDialog.Builder(this).setTitle("Тема оформления")
                .setSingleChoiceItems(Themes.NAMES, Themes.get(this), (d, w) -> {
                    d.dismiss();
                    if (w != Themes.get(this)) Themes.set(this, w);
                }).show();
    }

    private void toggleAdult() {
        if (st.showAdult) {
            st.setShowAdult(false);
            if (pb.channel != null && pb.channel.adult) pb.stop();
            Ui.toast(this, "Каналы 18+ скрыты");
            render();
            return;
        }
        if (!st.hasPin()) {
            askPin("Придумайте PIN-код для каналов 18+ (4–8 цифр)", pin -> {
                if (pin.length() < 4) {
                    Ui.toast(this, "PIN-код — не меньше 4 цифр");
                    return;
                }
                st.setPin(pin);
                st.setShowAdult(true);
                Ui.toast(this, "PIN-код сохранён. Каналы 18+ видны до выхода из приложения");
                render();
            });
        } else {
            askPin("Введите PIN-код", pin -> {
                if (!st.checkPin(pin)) {
                    Ui.toast(this, "Неверный PIN-код");
                    return;
                }
                st.setShowAdult(true);
                Ui.toast(this, "Каналы 18+ видны до выхода из приложения");
                render();
            });
        }
    }

    private void changePin() {
        askPin("Текущий PIN-код", old -> {
            if (!st.checkPin(old)) {
                Ui.toast(this, "Неверный PIN-код");
                return;
            }
            askPin("Новый PIN-код (4–8 цифр)", pin -> {
                if (pin.length() < 4) {
                    Ui.toast(this, "PIN-код — не меньше 4 цифр");
                    return;
                }
                st.setPin(pin);
                Ui.toast(this, "PIN-код изменён");
            });
        });
    }

    private interface PinCallback {
        void got(String pin);
    }

    private void askPin(String title, PinCallback cb) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        e.setGravity(Gravity.CENTER);
        e.setTextSize(24);
        FrameLayout f = new FrameLayout(this);
        int pad = Ui.dp(this, 24);
        f.setPadding(pad, Ui.dp(this, 8), pad, 0);
        f.addView(e);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(f)
                .setPositiveButton("OK", (x, w) -> cb.got(e.getText().toString().trim()))
                .setNegativeButton("Отмена", null).create();
        e.setOnEditorActionListener((v, a, ev) -> {
            d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            return true;
        });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        e.requestFocus();
    }

    private void about() {
        String v = "";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this).setTitle("ARGEDA TV " + v).setMessage(
                "Проигрыватель IPTV для приставок и телефонов. Своих каналов в приложении нет: "
                        + "оно показывает плейлист вашего провайдера (ссылка M3U, Xtream Codes или файл).\n\n"
                        + "Пульт в списке:\n▲ ▼ — выбор канала (он включается в окне справа)\nOK — на весь экран\n"
                        + "▶ — телепрограмма и записи, ◀ — группы\nДолгое нажатие OK — избранное\n\n"
                        + "Пульт на весь экран:\n▲ ▼ — каналы, OK или ◀ — список, ▶ — сведения и кнопки, цифры — номер канала\n"
                        + "В записи: ◀ ▶ — перемотка, «Назад» — вернуться в эфир\n\n"
                        + "Телефон: касание канала — в окне, второе касание — на весь экран. "
                        + "На весь экран: свайп вверх/вниз — каналы, в записи свайп влево/вправо — перемотка.\n\n"
                        + "Ссылки и пароли хранятся только на этом устройстве.")
                .setPositiveButton("OK", null).show();
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    // ---------- Группы ----------

    private final class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final TextView name, count;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                count = v.findViewById(R.id.count);
            }
        }

        @Override
        public int getItemCount() {
            return groups.size();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            VH h = new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group, parent, false));
            h.itemView.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p >= 0) selectGroup(groups.get(p).id, true);
            });
            h.itemView.setOnFocusChangeListener((v, focused) -> {
                int p = h.getBindingAdapterPosition();
                if (!focused || p < 0) return;
                String id = groups.get(p).id;
                ui.removeCallbacks(groupRun);
                if (id.equals(curGroup) && search.length() == 0) return;
                groupRun = () -> selectGroup(id, false);
                ui.postDelayed(groupRun, 250);
            });
            h.itemView.setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN || code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                ui.removeCallbacks(groupRun);
                int p = h.getBindingAdapterPosition();
                if (p >= 0 && !groups.get(p).id.equals(curGroup)) selectGroup(groups.get(p).id, false);
                focusChannel(firstVisibleChannel());
                return true;
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Group g = groups.get(pos);
            h.name.setText(g.title);
            h.count.setText(String.valueOf(g.count));
            h.itemView.setSelected(g.id.equals(curGroup));
        }
    }
}
