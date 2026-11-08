package ru.argeda.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.ViewGroup;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
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
            infoGroup, infoNow, infoNext, bList, bLive, bFav, bAudio, bQuality, bSubs;
    private ProgressBar infoProg;
    private RecyclerView sideList, epgSideList;
    private ChannelAdapter sa;
    private EpgAdapter ea;
    private List<Channel> list;
    private int index, fit, eatUp = -1;
    private boolean tv;
    /** Фокус на кнопках панели сведений (◀ ▶ OK — по кнопкам). Иначе пульт управляет каналами и перемоткой. */
    private boolean btns;
    private String digitBuf = "";
    private long seekBase, seekDelta;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final Runnable hideInfoRun = this::hideInfo;
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
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        Themes.apply(this, base, true);
    }

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
        bQuality = findViewById(R.id.bQuality);
        bSubs = findViewById(R.id.bSubs);

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
            favButton(on);
            Ui.toast(this, on ? "Добавлено в избранное" : "Убрано из избранного");
        });
        findViewById(R.id.bFit).setOnClickListener(v -> {
            fit = (fit + 1) % 3;
            applyFit();
            st.setFit(fit);
            Ui.toast(this, "Формат: " + FIT_NAMES[fit]);
        });
        bAudio.setOnClickListener(v -> pickAudio());
        bQuality.setOnClickListener(v -> pickQuality());
        bSubs.setOnClickListener(v -> pickSubs());
        findViewById(R.id.bStats).setOnClickListener(v -> showStats());
        TextView bSleep = findViewById(R.id.bSleep);
        bSleep.setText("Сон: " + Alerts.sleepLabel());
        bSleep.setOnClickListener(v -> {
            Alerts.nextSleep(this);
            bSleep.setText("Сон: " + Alerts.sleepLabel());
            showInfo(true);
        });
        TextView bRot = findViewById(R.id.bRot);
        if (!tv) {
            // Телефон и планшет: экран поворачивается за устройством, либо закреплён кнопкой «Поворот».
            rot = getSharedPreferences("argeda", MODE_PRIVATE).getInt("rot", 0);
            bRot.setVisibility(View.VISIBLE);
            bRot.setText("Поворот: " + ROT_NAMES[rot]);
            bRot.setOnClickListener(v -> {
                rot = (rot + 1) % ROT_NAMES.length;
                getSharedPreferences("argeda", MODE_PRIVATE).edit().putInt("rot", rot).apply();
                bRot.setText("Поворот: " + ROT_NAMES[rot]);
                applyRotation();
            });
        }
        applyRotation();
        fitPanels();
        // Панели кликабельны только чтобы касание не проходило к видео; фокус пульта им не нужен
        // (иначе после появления панели OK попадал в пустую панель и ничего не делал).
        for (View v : new View[]{side, info, epgSide}) {
            v.setClickable(true);
            v.setFocusable(false);
        }
        // Корень держит фокус, когда панели скрыты, чтобы он не оставался на невидимой кнопке.
        root.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) root.setDefaultFocusHighlightEnabled(false);
        setupGestures();
    }

    private static final String[] ROT_NAMES = {"авто", "альбомный", "книжный"};
    private int rot;

    private void applyRotation() {
        int o;
        if (tv) o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        else if (rot == 1) o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
        else if (rot == 2) o = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
        else o = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR;
        setRequestedOrientation(o);
    }

    /** Боковые списки в книжной ориентации узкого экрана не шире 90% ширины. */
    private void fitPanels() {
        int w = getResources().getDisplayMetrics().widthPixels;
        setWidth(side, Math.min(Ui.dp(this, 380), w * 9 / 10));
        setWidth(epgSide, Math.min(Ui.dp(this, 440), w * 9 / 10));
    }

    private static void setWidth(View v, int w) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp.width != w) {
            lp.width = w;
            v.setLayoutParams(lp);
        }
    }

    /** Телефон: кнопка «Домой» во время просмотра — «картинка в картинке» (Android 8+). */
    @SuppressLint("NewApi")
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (tv || android.os.Build.VERSION.SDK_INT < 26 || pb.player == null || !pb.player.isPlaying()) return;
        try {
            int w = 16, h = 9;
            Format f = pb.player.getVideoFormat();
            if (f != null && f.width > 0 && f.height > 0) {
                w = f.width;
                h = f.height;
                if (w * 100 > h * 239) h = w * 100 / 239; // система принимает от 1:2,39 до 2,39:1
                if (h * 100 > w * 239) w = h * 100 / 239;
            }
            enterPictureInPictureMode(new android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(new android.util.Rational(w, h)).build());
        } catch (Exception ignored) {
            // устройство не поддерживает «картинку в картинке»
        }
    }

    @SuppressLint("NewApi")
    @Override
    public void onPictureInPictureModeChanged(boolean pip, Configuration c) {
        super.onPictureInPictureModeChanged(pip, c);
        if (!pip) return;
        hideInfo();
        hideSide();
        hideEpg();
        digits.setVisibility(View.GONE);
        seek.setVisibility(View.GONE);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration c) {
        super.onConfigurationChanged(c);
        fitPanels();
        hideSystemUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Alerts.attach(this);
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
        Alerts.detach(this);
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
        Ui.keepScreenOn(this, pb.active());
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
        bQuality.setVisibility(tracks(C.TRACK_TYPE_VIDEO).size() > 1 ? View.VISIBLE : View.GONE);
        bSubs.setVisibility(!tracks(C.TRACK_TYPE_TEXT).isEmpty() ? View.VISIBLE : View.GONE);
        // «В прямой эфир» исчезла под фокусом — переводим его на соседнюю кнопку, а не в пустоту.
        if (btns && info.getVisibility() == View.VISIBLE && !info.hasFocus()) bList.requestFocus();
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
        livePausedAt = 0;
        st.playIndex = index;
        st.played(c);
        ui.removeCallbacks(zapRun);
        ui.removeCallbacks(seekRun);
        seekDelta = 0;
        seek.setVisibility(View.GONE);
        if (now) pb.play(c);
        else {
            if (pb.player != null) pb.player.stop();
            ui.postDelayed(zapRun, 200); // быстрое листание: запускаем только последний канал
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

    /** Когда поставили на паузу прямой эфир (0 — не на паузе). */
    private long livePausedAt;

    private void togglePause(int k) {
        if (pb.player == null) return;
        boolean play = k == KeyEvent.KEYCODE_MEDIA_PLAY || (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !pb.player.getPlayWhenReady());
        if (play) {
            long at = livePausedAt;
            livePausedAt = 0;
            if (!pb.isArchive()) {
                // Пауза эфира: если у канала есть архив, продолжаем с места паузы, иначе — снова эфир.
                Channel c = list.get(index);
                long now = System.currentTimeMillis();
                if (at > 0 && MainActivity.same(pb.channel, c) && Catchup.can(c, st.current(), at, now)) {
                    Epg.Prog p = Epg.at(c.progs, at);
                    pb.playArchive(c, at, p != null ? p.start : at, p != null ? Math.min(p.stop, now) : 0, p != null ? p.title : "");
                    Ui.toast(this, "С места паузы");
                    showInfo(false);
                    return;
                }
                pb.player.seekToDefaultPosition();
            }
            pb.player.play();
        } else {
            if (!pb.isArchive() && pb.player.getPlayWhenReady()) livePausedAt = System.currentTimeMillis();
            pb.player.pause();
        }
        showInfo(false);
    }

    /** Кнопка 0 (или «Предыдущий канал» на пульте): назад к каналу, который смотрели перед этим. */
    private void prevChannel() {
        String k = st.recent.size() > 1 ? st.recent.get(1) : null;
        if (k != null) {
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).key().equals(k)) {
                    index = i;
                    switchTo(true);
                    return;
                }
            }
            List<Channel> vis = st.visible();
            for (int i = 0; i < vis.size(); i++) {
                if (vis.get(i).key().equals(k)) {
                    list = vis;
                    st.playList = vis;
                    st.playTitle = "Все каналы";
                    index = i;
                    switchTo(true);
                    return;
                }
            }
        }
        Ui.toast(this, "Предыдущего канала пока нет");
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

    /** Дорожки видео (качество) или субтитров. */
    private List<Track> tracks(int type) {
        List<Track> r = new ArrayList<>();
        if (pb.player == null) return r;
        for (Tracks.Group g : pb.player.getCurrentTracks().getGroups()) {
            if (g.getType() != type) continue;
            for (int i = 0; i < g.length; i++) {
                if (!g.isTrackSupported(i)) continue;
                Format f = g.getTrackFormat(i);
                String label;
                if (type == C.TRACK_TYPE_VIDEO) {
                    label = f.height > 0 ? f.height + "p" : "Вариант " + (r.size() + 1);
                    if (f.bitrate > 0) label += String.format(Locale.ROOT, "  ·  %.1f Мбит/с", f.bitrate / 1e6).replace('.', ',');
                } else {
                    label = f.label != null ? f.label
                            : f.language != null && !"und".equals(f.language) ? new Locale(f.language).getDisplayLanguage()
                            : "Субтитры " + (r.size() + 1);
                }
                r.add(new Track(g, i, label, g.isTrackSelected(i)));
            }
        }
        if (type == C.TRACK_TYPE_VIDEO) {
            java.util.Collections.sort(r, (a, b) -> Integer.compare(b.g.getTrackFormat(b.i).height, a.g.getTrackFormat(a.i).height));
        }
        return r;
    }

    /** Качество: «Авто» — по скорости интернета, иначе одно выбранное. */
    private void pickQuality() {
        List<Track> ts = tracks(C.TRACK_TYPE_VIDEO);
        if (ts.size() < 2 || pb.player == null) return;
        boolean auto = !pb.player.getTrackSelectionParameters().overrides.containsKey(ts.get(0).g.getMediaTrackGroup());
        String[] names = new String[ts.size() + 1];
        names[0] = "Авто";
        int sel = 0;
        for (int i = 0; i < ts.size(); i++) {
            names[i + 1] = ts.get(i).label;
            if (!auto && ts.get(i).sel) sel = i + 1;
        }
        new AlertDialog.Builder(this).setTitle("Качество").setSingleChoiceItems(names, sel, (d, w) -> {
            d.dismiss();
            if (pb.player == null) return;
            androidx.media3.common.TrackSelectionParameters.Builder b = pb.player.getTrackSelectionParameters().buildUpon();
            if (w == 0) b.clearOverridesOfType(C.TRACK_TYPE_VIDEO);
            else b.setOverrideForType(new TrackSelectionOverride(ts.get(w - 1).g.getMediaTrackGroup(), ts.get(w - 1).i));
            pb.player.setTrackSelectionParameters(b.build());
        }).show();
    }

    private void pickSubs() {
        List<Track> ts = tracks(C.TRACK_TYPE_TEXT);
        if (ts.isEmpty() || pb.player == null) return;
        boolean off = pb.player.getTrackSelectionParameters().disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        String[] names = new String[ts.size() + 1];
        names[0] = "Выключены";
        int sel = 0;
        for (int i = 0; i < ts.size(); i++) {
            names[i + 1] = ts.get(i).label;
            if (!off && ts.get(i).sel) sel = i + 1;
        }
        new AlertDialog.Builder(this).setTitle("Субтитры").setSingleChoiceItems(names, sel, (d, w) -> {
            d.dismiss();
            if (pb.player == null) return;
            androidx.media3.common.TrackSelectionParameters.Builder b = pb.player.getTrackSelectionParameters().buildUpon();
            if (w == 0) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
            else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(new TrackSelectionOverride(ts.get(w - 1).g.getMediaTrackGroup(), ts.get(w - 1).i));
            pb.player.setTrackSelectionParameters(b.build());
        }).show();
    }

    /** Сведения о потоке: разрешение, кодеки, скорость, частота кадров. */
    @OptIn(markerClass = UnstableApi.class)
    private void showStats() {
        if (pb.player == null) return;
        StringBuilder s = new StringBuilder();
        Format v = pb.player.getVideoFormat(), a = pb.player.getAudioFormat();
        if (v != null) {
            s.append("Видео: ").append(v.width).append("×").append(v.height);
            if (v.frameRate > 0) s.append(", ").append(Math.round(v.frameRate)).append(" кадр/с");
            if (v.sampleMimeType != null) s.append("\nКодек видео: ").append(codec(v.sampleMimeType));
            if (v.bitrate > 0) s.append(String.format(Locale.ROOT, "\nПоток видео: %.1f Мбит/с", v.bitrate / 1e6).replace('.', ','));
        } else s.append("Видео: нет данных");
        if (a != null) {
            s.append("\nЗвук: ").append(a.sampleMimeType != null ? codec(a.sampleMimeType) : "?");
            if (a.channelCount > 0) s.append(", ").append(a.channelCount >= 6 ? "5.1" : a.channelCount == 2 ? "стерео" : a.channelCount + " кан.");
            if (a.sampleRate > 0) s.append(", ").append(a.sampleRate / 1000).append(" кГц");
        }
        long buf = pb.player.getTotalBufferedDuration();
        s.append(String.format(Locale.ROOT, "\nЗапас в буфере: %.1f с", buf / 1000.0).replace('.', ','));
        long bw = pb.bandwidth();
        if (bw > 0) s.append(String.format(Locale.ROOT, "\nСкорость интернета: %.1f Мбит/с", bw / 1e6).replace('.', ','));
        Channel c = list.get(index);
        s.append("\n\nСсылка: ").append(c.url);
        new AlertDialog.Builder(this).setTitle(c.name).setMessage(s.toString()).setPositiveButton("OK", null).show();
    }

    private static String codec(String mime) {
        switch (mime) {
            case "video/avc": return "H.264";
            case "video/hevc": return "H.265 (HEVC)";
            case "video/mp2t": case "video/mpeg2": return "MPEG-2";
            case "video/av01": return "AV1";
            case "video/x-vnd.on2.vp9": return "VP9";
            case "audio/mp4a-latm": return "AAC";
            case "audio/ac3": return "Dolby Digital (AC-3)";
            case "audio/eac3": return "Dolby Digital Plus";
            case "audio/mpeg": case "audio/mpeg-L2": return "MPEG Audio";
            default: return mime;
        }
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

    private void favButton(boolean on) {
        bFav.setText(on ? "В избранном" : "В избранное");
        bFav.setCompoundDrawablesRelativeWithIntrinsicBounds(on ? R.drawable.ic_star : R.drawable.ic_star_outline, 0, 0, 0);
    }

    private void showInfo(boolean focus) {
        Channel c = list.get(index);
        infoNum.setText(String.valueOf(c.num));
        infoName.setText(c.name);
        infoGroup.setText(c.groupTitle());
        favButton(st.isFav(c));
        fillTime();
        info.setVisibility(View.VISIBLE);
        if (focus) {
            btns = true;
            (pb.isArchive() ? bLive : bList).requestFocus();
        } else if (!btns) parkFocus();
        ui.removeCallbacks(hideInfoRun);
        ui.postDelayed(hideInfoRun, btns || !tv ? 8000 : 5000);
    }

    private void hideInfo() {
        info.setVisibility(View.GONE);
        btns = false;
        parkFocus();
    }

    /** Убирает фокус с кнопок и списков: дальше пульт обрабатывает handleKey. */
    private void parkFocus() {
        if (tv || !root.isInTouchMode()) root.requestFocus();
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
        btns = false;
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
        boolean had = side.hasFocus();
        side.setVisibility(View.GONE);
        ui.removeCallbacks(hideSideRun);
        if (had) parkFocus();
    }

    private void showEpg() {
        info.setVisibility(View.GONE);
        btns = false;
        hideSide();
        Channel c = list.get(index);
        epgTitle.setText("Телепрограмма  ·  " + c.name);
        List<EpgAdapter.Row> rows = EpgAdapter.build(c, st.current(), System.currentTimeMillis());
        ea.playing = pb.isArchive() ? pb.archStart : 0;
        ea.channel = c;
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
        boolean had = epgSide.hasFocus();
        epgSide.setVisibility(View.GONE);
        ui.removeCallbacks(hideEpgRun);
        if (had) parkFocus();
    }

    /** Строка телепрограммы на полном экране: запись — с начала передачи, текущая — с начала или в эфир. */
    private void fromEpg(EpgAdapter.Row r) {
        Channel c = list.get(index);
        if (r.future) {
            remind(c, r);
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

    /** Будущая передача: OK ставит или снимает напоминание. */
    private void remind(Channel c, EpgAdapter.Row r) {
        boolean on = Alerts.toggle(this, c, r.p);
        Ui.toast(this, on ? "Напомню в " + hm.format(new Date(r.p.start - 60_000L)) + ", за минуту до начала" : "Напоминание снято");
        int i = ea.rows().indexOf(r);
        if (i >= 0) ea.notifyItemChanged(i);
    }

    /** Напоминание: «Смотреть» — включить канал прямо здесь. */
    void openKey(String key) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).key().equals(key)) {
                index = i;
                switchTo(true);
                return;
            }
        }
        List<Channel> vis = st.visible();
        for (int i = 0; i < vis.size(); i++) {
            if (vis.get(i).key().equals(key)) {
                list = vis;
                st.playList = vis;
                st.playTitle = "Все каналы";
                index = i;
                switchTo(true);
                return;
            }
        }
        Ui.toast(this, "Этого канала больше нет в плейлисте");
    }

    private void focusAt(RecyclerView rv, int pos, int tries) {
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh != null) vh.itemView.requestFocus();
        else if (tries > 0) rv.postDelayed(() -> focusAt(rv, pos, tries - 1), 40);
    }

    // ---------- Номер канала цифрами ----------

    private void digit(int d) {
        if (d == 0 && digitBuf.isEmpty()) {
            prevChannel(); // номера каналов не начинаются с 0
            return;
        }
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
        if (!infoOn) btns = false;
        if (infoOn) {
            ui.removeCallbacks(hideInfoRun);
            ui.postDelayed(hideInfoRun, btns ? 8000 : 5000);
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
                    hideInfo();
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
            case KeyEvent.KEYCODE_LAST_CHANNEL:
                prevChannel();
                return true;
            case KeyEvent.KEYCODE_GUIDE:
                if (epgOn) hideEpg();
                else showEpg();
                return true;
            default:
                break;
        }
        if (epgOn && epgSideEmpty.hasFocus() && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            hideEpg(); // пустая телепрограмма: ◀ ▶ закрывают её, как и список
            return true;
        }
        if (sideOn || epgOn) return false; // списками управляет RecyclerView
        // Цифры набирают номер канала всегда, даже когда открыта панель с кнопками.
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
            digit(k - KeyEvent.KEYCODE_0);
            return true;
        }
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) {
            digit(k - KeyEvent.KEYCODE_NUMPAD_0);
            return true;
        }
        if (infoOn && btns && info.hasFocus()) {
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
                else if (info.getVisibility() == View.VISIBLE) hideInfo();
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
