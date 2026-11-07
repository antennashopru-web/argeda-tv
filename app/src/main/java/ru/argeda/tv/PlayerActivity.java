package ru.argeda.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Полный экран. Поток тот же, что в окне предпросмотра (общий проигрыватель Playback).
 * Пульт: ▲▼ — каналы, OK/◀ — список, ▶ — сведения и кнопки; в записи ◀▶ — перемотка, «Назад» — в эфир.
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity implements Playback.Listener {
    private static final String[] FIT_NAMES = {"Вписать", "Заполнить", "Растянуть"};

    private Store st;
    private Playback pb;
    private PlayerView video;
    private View root, side, info, epgSide, buffering;
    private TextView error, digits, seek, sideTitle, epgTitle, epgSideEmpty, infoArch, infoNum, infoName, infoClock,
            infoGroup, infoNow, infoNext, bList, bLive, bFav, bAudio;
    private ProgressBar infoProg;
    private RecyclerView sideList, epgSideList;
    private ChannelAdapter sa;
    private EpgAdapter ea;
    private List<Channel> list;
    private int index, fit, eatUp = -1;
    private boolean tv;
    private String digitBuf = "";
    private long seekBase, seekDelta;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final Runnable hideInfoRun = () -> info.setVisibility(View.GONE);
    private final Runnable hideSideRun = this::hideSide;
    private final Runnable hideEpgRun = this::hideEpg;
    private final Runnable zapRun = () -> {
        if (list != null && !list.isEmpty()) pb.play(list.get(index));
    };
    private final Runnable digitRun = this::jumpToNumber;
    private final Runnable seekRun = () -> {
        seek.setVisibility(View.GONE);
        if (seekDelta != 0) pb.seekArchive(seekBase + seekDelta);
        seekDelta = 0;
    };
    private final Runnable clockRun = new Runnable() {
        @Override
        public void run() {
            if (info.getVisibility() == View.VISIBLE) fillTime();
            ui.postDelayed(this, pb.isArchive() ? 1000 : 20_000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_player);
        st = Store.get(this);
        pb = Playback.get(this);
        Logos.init(this);
        tv = Ui.isTv(this);
        root = findViewById(R.id.root);
        video = findViewById(R.id.video);
        side = findViewById(R.id.side);
        info = findViewById(R.id.info);
        epgSide = findViewById(R.id.epgSide);
        buffering = findViewById(R.id.buffering);
        error = findViewById(R.id.error);
        digits = findViewById(R.id.digits);
        seek = findViewById(R.id.seek);
        sideTitle = findViewById(R.id.sideTitle);
        sideList = findViewById(R.id.sideList);
        epgTitle = findViewById(R.id.epgTitle);
        epgSideEmpty = findViewById(R.id.epgSideEmpty);
        epgSideList = findViewById(R.id.epgSideList);
        infoArch = findViewById(R.id.infoArch);
        infoNum = findViewById(R.id.infoNum);
        infoName = findViewById(R.id.infoName);
        infoClock = findViewById(R.id.infoClock);
        infoGroup = findViewById(R.id.infoGroup);
        infoNow = findViewById(R.id.infoNow);
        infoNext = findViewById(R.id.infoNext);
        infoProg = findViewById(R.id.infoProg);
        bList = findViewById(R.id.bList);
        bLive = findViewById(R.id.bLive);
        bFav = findViewById(R.id.bFav);
        bAudio = findViewById(R.id.bAudio);

        list = st.playList;
        index = st.playIndex;
        if (list == null || list.isEmpty()) {
            // Система закрыла приложение в фоне — восстанавливаем список из сохранённого плейлиста.
            if (st.all.isEmpty()) st.loadSync();
            list = st.visible();
            index = 0;
            String key = getIntent().getStringExtra("key");
            for (int i = 0; i < list.size(); i++) if (list.get(i).key().equals(key)) index = i;
            st.playTitle = "Все каналы";
        }
        if (list.isEmpty()) {
            finish();
            return;
        }
        if (index < 0 || index >= list.size()) index = 0;
        st.playList = list;
        st.playIndex = index;

        fit = st.fit();
        applyFit();

        sa = new ChannelAdapter(st, new ChannelAdapter.Listener() {
            @Override
            public void click(int pos) {
                hideSide();
                if (pos != index || pb.isArchive()) {
                    index = pos;
                    switchTo(true);
                }
            }

            @Override
            public void longClick(int pos) {
                Channel c = list.get(pos);
                boolean on = st.toggleFav(c);
                Ui.toast(PlayerActivity.this, on ? "«" + c.name + "» добавлен в избранное" : "«" + c.name + "» убран из избранного");
                sa.notifyItemChanged(pos);
            }

            @Override
            public void focus(int pos) {}

            @Override
            public boolean key(int pos, int code) {
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    hideSide();
                    return true;
                }
                return false;
            }
        });
        sideList.setLayoutManager(new LinearLayoutManager(this));
        sideList.setAdapter(sa);
        sideList.setItemAnimator(null);

        ea = new EpgAdapter(new EpgAdapter.Listener() {
            @Override
            public void click(int pos) {
                if (pos < ea.rows().size()) fromEpg(ea.rows().get(pos));
            }

            @Override
            public void focus(int pos) {}

            @Override
            public boolean key(int pos, int code) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    hideEpg();
                    return true;
                }
                return false;
            }
        });
        epgSideList.setLayoutManager(new LinearLayoutManager(this));
        epgSideList.setAdapter(ea);
        epgSideList.setItemAnimator(null);

        bList.setOnClickListener(v -> showSide());
        findViewById(R.id.bEpg).setOnClickListener(v -> showEpg());
        bLive.setOnClickListener(v -> toLive());
        bFav.setOnClickListener(v -> {
            Channel c = list.get(index);
            boolean on = st.toggleFav(c);
            bFav.setText(on ? "★  В избранном" : "☆  В избранное");
            Ui.toast(this, on ? "Добавлено в избранное" : "Убрано из избранного");
        });
        findViewById(R.id.bFit).setOnClickListener(v -> {
            fit = (fit + 1) % 3;
            applyFit();
            st.setFit(fit);
            Ui.toast(this, "Формат: " + FIT_NAMES[fit]);
        });
        bAudio.setOnClickListener(v -> pickAudio());
        side.setClickable(true);
        info.setClickable(true);
        epgSide.setClickable(true);
        setupGestures();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (list == null || list.isEmpty()) return;
        hideSystemUi();
        pb.acquire();
        video.setPlayer(pb.player);
        pb.addListener(this);
        Channel want = list.get(index);
        if (pb.channel == null) pb.play(want);
        else if (!MainActivity.same(pb.channel, want)) {
            int i = indexIn(list, pb.channel);
            if (i >= 0) index = i;
            else pb.play(want);
        }
        showInfo(false);
        playbackChanged();
        ui.post(clockRun);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacksAndMessages(null);
        if (pb == null || list == null || list.isEmpty()) return;
        pb.removeListener(this);
        video.setPlayer(null);
        pb.release();
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    private static int indexIn(List<Channel> l, Channel c) {
        for (int i = 0; i < l.size(); i++) if (MainActivity.same(l.get(i), c)) return i;
        return -1;
    }

    // ---------- Состояние проигрывателя ----------

    @Override
    public void playbackChanged() {
        boolean err = !pb.error.isEmpty();
        buffering.setVisibility(pb.buffering && !err ? View.VISIBLE : View.GONE);
        if (err) {
            error.setText(pb.error + (pb.fatal ? (tv ? "\n\nOK — повторить   ▲▼ — другой канал" : "\n\nКоснитесь экрана, чтобы повторить") : ""));
            error.setVisibility(View.VISIBLE);
        } else error.setVisibility(View.GONE);
        boolean arch = pb.isArchive();
        infoArch.setVisibility(arch ? View.VISIBLE : View.GONE);
        bLive.setVisibility(arch ? View.VISIBLE : View.GONE);
        bAudio.setVisibility(audioTracks().size() > 1 ? View.VISIBLE : View.GONE);
        if (info.getVisibility() == View.VISIBLE) fillTime();
        if (epgSide.getVisibility() == View.VISIBLE) {
            long ps = arch ? pb.archStart : 0;
            if (ps != ea.playing) {
                ea.playing = ps;
                ea.notifyItemRangeChanged(0, ea.getItemCount(), "p");
            }
        }
    }

    private void switchTo(boolean now) {
        Channel c = list.get(index);
        st.playIndex = index;
        st.played(c);
        ui.removeCallbacks(zapRun);
        ui.removeCallbacks(seekRun);
        seekDelta = 0;
        seek.setVisibility(View.GONE);
        if (now) pb.play(c);
        else {
            if (pb.player != null) pb.player.stop();
            ui.postDelayed(zapRun, 350); // быстрое листание: запускаем только последний канал
        }
        showInfo(false);
    }

    private void zap(int d) {
        if (list.size() < 2) return;
        index = ((index + d) % list.size() + list.size()) % list.size();
        switchTo(false);
    }

    private void toLive() {
        pb.play(list.get(index));
        Ui.toast(this, "Прямой эфир");
        showInfo(false);
    }

    private void togglePause(int k) {
        if (pb.player == null) return;
        boolean play = k == KeyEvent.KEYCODE_MEDIA_PLAY || (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !pb.player.getPlayWhenReady());
        if (play) {
            if (!pb.isArchive()) pb.player.seekToDefaultPosition();
            pb.player.play();
        } else pb.player.pause();
        showInfo(false);
    }

    /** Перемотка записи: нажатия копятся, поток перезапрашивается один раз, когда их отпустили. */
    private void seekBy(int dir, int repeat) {
        if (!pb.isArchive()) return;
        if (seekDelta == 0) seekBase = pb.position();
        long step = repeat > 8 ? 300_000L : repeat > 3 ? 120_000L : 60_000L;
        seekDelta += dir * step;
        long now = System.currentTimeMillis();
        long target = Math.min(seekBase + seekDelta, now);
        long min = seekDelta / 60_000L;
        seek.setText((min > 0 ? "+" : "−") + Math.abs(min) + " мин   →   " + (target >= now - 30_000L ? "эфир" : hm.format(new Date(target))));
        seek.setVisibility(View.VISIBLE);
        ui.removeCallbacks(seekRun);
        ui.postDelayed(seekRun, 800);
        showInfo(false);
    }

    private void applyFit() {
        video.setResizeMode(fit == 1 ? AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                : fit == 2 ? AspectRatioFrameLayout.RESIZE_MODE_FILL : AspectRatioFrameLayout.RESIZE_MODE_FIT);
    }

    // ---------- Звуковые дорожки ----------

    private static final class Track {
        final Tracks.Group g;
        final int i;
        final String label;
        final boolean sel;

        Track(Tracks.Group g, int i, String label, boolean sel) {
            this.g = g;
            this.i = i;
            this.label = label;
            this.sel = sel;
        }
    }

    private List<Track> audioTracks() {
        List<Track> r = new ArrayList<>();
        if (pb.player == null) return r;
        for (Tracks.Group g : pb.player.getCurrentTracks().getGroups()) {
            if (g.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                String label = f.label != null ? f.label
                        : f.language != null && !"und".equals(f.language) ? new Locale(f.language).getDisplayLanguage()
                        : "Дорожка " + (r.size() + 1);
                if (f.channelCount >= 6) label += " (5.1)";
                r.add(new Track(g, i, label, g.isTrackSelected(i)));
            }
        }
        return r;
    }

    private void pickAudio() {
        List<Track> ts = audioTracks();
        if (ts.size() < 2 || pb.player == null) return;
        String[] names = new String[ts.size()];
        int sel = 0;
        for (int i = 0; i < ts.size(); i++) {
            names[i] = ts.get(i).label;
            if (ts.get(i).sel) sel = i;
        }
        new AlertDialog.Builder(this).setTitle("Звуковая дорожка").setSingleChoiceItems(names, sel, (d, w) -> {
            d.dismiss();
            if (pb.player == null) return;
            Track t = ts.get(w);
            pb.player.setTrackSelectionParameters(pb.player.getTrackSelectionParameters().buildUpon()
                    .setOverrideForType(new TrackSelectionOverride(t.g.getMediaTrackGroup(), t.i)).build());
        }).show();
    }

    // ---------- Панели ----------

    private void showInfo(boolean focus) {
        Channel c = list.get(index);
        infoNum.setText(String.valueOf(c.num));
        infoName.setText(c.name);
        infoGroup.setText(c.groupTitle());
        bFav.setText(st.isFav(c) ? "★  В избранном" : "☆  В избранное");
        fillTime();
        info.setVisibility(View.VISIBLE);
        if (focus) (pb.isArchive() ? bLive : bList).requestFocus();
        ui.removeCallbacks(hideInfoRun);
        ui.postDelayed(hideInfoRun, focus || !tv ? 8000 : 5000);
    }

    private void fillTime() {
        Channel c = list.get(index);
        if (pb.isArchive() && MainActivity.same(pb.channel, c)) {
            long pos = pb.position(), end = pb.archEnd > pb.archStart ? pb.archEnd : pb.archStart + 3600_000L;
            infoClock.setText(hm.format(new Date(pos)) + (pb.player != null && !pb.player.getPlayWhenReady() ? "  · пауза" : ""));
            infoNow.setText(hm.format(new Date(pb.archStart)) + "–" + hm.format(new Date(end)) + "   "
                    + (pb.archTitle.isEmpty() ? "Запись эфира" : pb.archTitle));
            infoNow.setVisibility(View.VISIBLE);
            infoProg.setProgress((int) Math.max(0, Math.min(1000, (pos - pb.archStart) * 1000 / Math.max(1, end - pb.archStart))));
            infoProg.setVisibility(View.VISIBLE);
            infoNext.setText(tv ? "◀ ▶ — перемотка    «Назад» — в прямой эфир" : "Свайп влево/вправо — перемотка на 5 минут");
            infoNext.setVisibility(View.VISIBLE);
            return;
        }
        long t = System.currentTimeMillis();
        infoClock.setText(hm.format(new Date(t)));
        Epg.Prog p = Epg.at(c.progs, t), n = Epg.after(c.progs, t);
        if (p != null) {
            infoNow.setText(hm.format(new Date(p.start)) + "–" + hm.format(new Date(p.stop)) + "   " + p.title);
            infoNow.setVisibility(View.VISIBLE);
            infoProg.setProgress((int) ((t - p.start) * 1000 / Math.max(1, p.stop - p.start)));
            infoProg.setVisibility(View.VISIBLE);
        } else {
            infoNow.setVisibility(View.GONE);
            infoProg.setVisibility(View.GONE);
        }
        if (n != null) {
            infoNext.setText("Далее: " + hm.format(new Date(n.start)) + "   " + n.title);
            infoNext.setVisibility(View.VISIBLE);
        } else infoNext.setVisibility(View.GONE);
    }

    private void showSide() {
        info.setVisibility(View.GONE);
        hideEpg();
        sideTitle.setText(st.playTitle + "  ·  " + list.size());
        sa.current = list.get(index);
        sa.set(list);
        side.setVisibility(View.VISIBLE);
        ((LinearLayoutManager) sideList.getLayoutManager()).scrollToPositionWithOffset(index, Ui.dp(this, 140));
        focusAt(sideList, index, 10);
        ui.removeCallbacks(hideSideRun);
        ui.postDelayed(hideSideRun, 20_000);
    }

    private void hideSide() {
        side.setVisibility(View.GONE);
        ui.removeCallbacks(hideSideRun);
    }

    private void showEpg() {
        info.setVisibility(View.GONE);
        hideSide();
        Channel c = list.get(index);
        epgTitle.setText("Телепрограмма  ·  " + c.name);
        List<EpgAdapter.Row> rows = EpgAdapter.build(c, st.current(), System.currentTimeMillis());
        ea.playing = pb.isArchive() ? pb.archStart : 0;
        ea.set(rows);
        epgSide.setVisibility(View.VISIBLE);
        if (rows.isEmpty()) {
            epgSideEmpty.setText(st.epgUrl(st.current()).isEmpty()
                    ? "Телепрограммы нет. Адрес телепрограммы (XMLTV) можно добавить в настройках плейлиста."
                    : "Для этого канала нет телепрограммы и архива.");
            epgSideEmpty.setVisibility(View.VISIBLE);
            epgSideEmpty.setFocusable(true);
            epgSideEmpty.requestFocus();
        } else {
            epgSideEmpty.setVisibility(View.GONE);
            int i = EpgAdapter.nowIndex(rows);
            if (pb.isArchive()) for (int k = 0; k < rows.size(); k++) if (rows.get(k).p.start == pb.archStart) i = k;
            ((LinearLayoutManager) epgSideList.getLayoutManager()).scrollToPositionWithOffset(i, Ui.dp(this, 140));
            focusAt(epgSideList, i, 10);
        }
        ui.removeCallbacks(hideEpgRun);
        ui.postDelayed(hideEpgRun, 30_000);
    }

    private void hideEpg() {
        epgSide.setVisibility(View.GONE);
        ui.removeCallbacks(hideEpgRun);
    }

    /** Строка телепрограммы на полном экране: запись — с начала передачи, текущая — с начала или в эфир. */
    private void fromEpg(EpgAdapter.Row r) {
        Channel c = list.get(index);
        if (r.future) {
            Ui.toast(this, "Передача начнётся в " + hm.format(new Date(r.p.start)));
            return;
        }
        if (r.now && pb.isArchive()) {
            hideEpg();
            toLive();
            return;
        }
        if (!r.archive) {
            Ui.toast(this, Catchup.days(c, st.current()) > 0 ? "Эта передача уже не хранится в архиве" : "У этого канала нет архива");
            return;
        }
        hideEpg();
        pb.playArchive(c, r.p.start, r.p.start, Math.min(r.p.stop, System.currentTimeMillis()), r.slot ? "" : r.p.title);
        if (r.now) Ui.toast(this, "Передача с начала");
        showInfo(false);
    }

    private void focusAt(RecyclerView rv, int pos, int tries) {
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh != null) vh.itemView.requestFocus();
        else if (tries > 0) rv.postDelayed(() -> focusAt(rv, pos, tries - 1), 40);
    }

    // ---------- Номер канала цифрами ----------

    private void digit(int d) {
        if (digitBuf.length() >= 5) digitBuf = "";
        digitBuf += d;
        digits.setText(digitBuf);
        digits.setVisibility(View.VISIBLE);
        ui.removeCallbacks(digitRun);
        ui.postDelayed(digitRun, 1500);
    }

    private void jumpToNumber() {
        digits.setVisibility(View.GONE);
        int n;
        try {
            n = Integer.parseInt(digitBuf);
        } catch (NumberFormatException e) {
            n = -1;
        }
        digitBuf = "";
        if (n <= 0) return;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).num == n) {
                index = i;
                switchTo(true);
                return;
            }
        }
        List<Channel> vis = st.visible();
        for (int i = 0; i < vis.size(); i++) {
            if (vis.get(i).num == n) {
                list = vis;
                st.playList = vis;
                st.playTitle = "Все каналы";
                index = i;
                switchTo(true);
                return;
            }
        }
        Ui.toast(this, "Канала с номером " + n + " нет");
    }

    // ---------- Пульт ----------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        if (e.getAction() == KeyEvent.ACTION_UP) {
            if (k == eatUp) {
                eatUp = -1;
                return true;
            }
            return super.dispatchKeyEvent(e);
        }
        if (e.getAction() == KeyEvent.ACTION_DOWN && list != null && !list.isEmpty() && handleKey(k, e.getRepeatCount())) {
            eatUp = k;
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean handleKey(int k, int repeat) {
        boolean sideOn = side.getVisibility() == View.VISIBLE, infoOn = info.getVisibility() == View.VISIBLE,
                epgOn = epgSide.getVisibility() == View.VISIBLE, arch = pb.isArchive();
        if (infoOn) {
            ui.removeCallbacks(hideInfoRun);
            ui.postDelayed(hideInfoRun, info.hasFocus() ? 8000 : 5000);
        }
        if (sideOn) {
            ui.removeCallbacks(hideSideRun);
            ui.postDelayed(hideSideRun, 20_000);
        }
        if (epgOn) {
            ui.removeCallbacks(hideEpgRun);
            ui.postDelayed(hideEpgRun, 30_000);
        }
        switch (k) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (sideOn) {
                    hideSide();
                    return true;
                }
                if (epgOn) {
                    hideEpg();
                    return true;
                }
                if (infoOn) {
                    info.setVisibility(View.GONE);
                    return true;
                }
                if (arch) {
                    toLive();
                    return true;
                }
                return false;
            case KeyEvent.KEYCODE_CHANNEL_UP:
            case KeyEvent.KEYCODE_PAGE_UP:
                zap(1);
                return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN:
                zap(-1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                togglePause(k);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekBy(1, repeat);
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekBy(-1, repeat);
                return true;
            case KeyEvent.KEYCODE_GUIDE:
                if (epgOn) hideEpg();
                else showEpg();
                return true;
            default:
                break;
        }
        if (sideOn || epgOn) return false; // списками управляет RecyclerView
        if (infoOn && info.hasFocus()) {
            if (k == KeyEvent.KEYCODE_DPAD_UP) {
                zap(1);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
                zap(-1);
                return true;
            }
            return false; // ◀ ▶ OK — по кнопкам панели
        }
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
            digit(k - KeyEvent.KEYCODE_0);
            return true;
        }
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) {
            digit(k - KeyEvent.KEYCODE_NUMPAD_0);
            return true;
        }
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP:
                zap(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                zap(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (!digitBuf.isEmpty()) {
                    ui.removeCallbacks(digitRun);
                    jumpToNumber();
                } else if (pb.fatal) pb.retry();
                else if (arch) showInfo(true);
                else showSide();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (arch) seekBy(-1, repeat);
                else showSide();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (arch) seekBy(1, repeat);
                else showInfo(true);
                return true;
            case KeyEvent.KEYCODE_INFO:
            case KeyEvent.KEYCODE_MENU:
                showInfo(true);
                return true;
            default:
                return false;
        }
    }

    // ---------- Жесты на телефоне ----------

    @SuppressLint("ClickableViewAccessibility")
    private void setupGestures() {
        final int swipe = Ui.dp(this, 60);
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(@NonNull MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (side.getVisibility() == View.VISIBLE) hideSide();
                else if (epgSide.getVisibility() == View.VISIBLE) hideEpg();
                else if (pb.fatal) pb.retry();
                else if (info.getVisibility() == View.VISIBLE) info.setVisibility(View.GONE);
                else showInfo(false);
                return true;
            }

            @Override
            public boolean onFling(MotionEvent a, @NonNull MotionEvent b, float vx, float vy) {
                if (a == null) return false;
                float dx = b.getX() - a.getX(), dy = b.getY() - a.getY();
                if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > swipe) {
                    zap(dy < 0 ? 1 : -1);
                    return true;
                }
                if (Math.abs(dx) > swipe) {
                    if (pb.isArchive()) {
                        seekBy(dx > 0 ? 1 : -1, 9); // свайп — 5 минут
                    } else if (dx > 0) {
                        if (epgSide.getVisibility() == View.VISIBLE) hideEpg();
                        else showSide();
                    } else {
                        if (side.getVisibility() == View.VISIBLE) hideSide();
                        else showEpg();
                    }
                    return true;
                }
                return false;
            }
        });
        root.setOnTouchListener((v, e) -> gd.onTouchEvent(e));
    }
}
