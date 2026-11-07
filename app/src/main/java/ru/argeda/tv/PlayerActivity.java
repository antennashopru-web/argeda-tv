package ru.argeda.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.net.Uri;
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
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Просмотр канала: переключение пультом и жестами, список каналов, сведения, формат и звук. */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {
    private static final String[] FIT_NAMES = {"Вписать", "Заполнить", "Растянуть"};

    private Store st;
    private ExoPlayer player;
    private PlayerView video;
    private View root, side, info, buffering;
    private TextView error, digits, sideTitle, infoNum, infoName, infoClock, infoGroup, infoNow, infoNext, bList, bFav, bAudio;
    private ProgressBar infoProg;
    private RecyclerView sideList;
    private ChannelAdapter sa;
    private List<Channel> list;
    private int index, retries, fit, eatUp = -1;
    private boolean triedHls, fatal, tv;
    private String digitBuf = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final Runnable hideInfoRun = () -> info.setVisibility(View.GONE);
    private final Runnable hideSideRun = this::hideSide;
    private final Runnable retryRun = () -> start(triedHls);
    private final Runnable startRun = () -> start(false);
    private final Runnable digitRun = this::jumpToNumber;
    private final Runnable clockRun = new Runnable() {
        @Override
        public void run() {
            if (info.getVisibility() == View.VISIBLE) fillTime();
            ui.postDelayed(this, 20_000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_player);
        st = Store.get(this);
        Logos.init(this);
        tv = Ui.isTv(this);
        root = findViewById(R.id.root);
        video = findViewById(R.id.video);
        side = findViewById(R.id.side);
        info = findViewById(R.id.info);
        buffering = findViewById(R.id.buffering);
        error = findViewById(R.id.error);
        digits = findViewById(R.id.digits);
        sideTitle = findViewById(R.id.sideTitle);
        sideList = findViewById(R.id.sideList);
        infoNum = findViewById(R.id.infoNum);
        infoName = findViewById(R.id.infoName);
        infoClock = findViewById(R.id.infoClock);
        infoGroup = findViewById(R.id.infoGroup);
        infoNow = findViewById(R.id.infoNow);
        infoNext = findViewById(R.id.infoNext);
        infoProg = findViewById(R.id.infoProg);
        bList = findViewById(R.id.bList);
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
                if (pos != index) {
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

        bList.setOnClickListener(v -> showSide());
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
        setupGestures();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (list == null || list.isEmpty()) return;
        hideSystemUi();
        player = new ExoPlayer.Builder(this, new DefaultRenderersFactory(this).setEnableDecoderFallback(true)).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.addListener(listener);
        video.setPlayer(player);
        start(false);
        showInfo(false);
        ui.postDelayed(clockRun, 20_000);
    }

    @Override
    protected void onStop() {
        super.onStop();
        ui.removeCallbacksAndMessages(null);
        if (player != null) {
            video.setPlayer(null);
            player.release();
            player = null;
        }
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

    // ---------- Воспроизведение ----------

    private static boolean looksHls(String url) {
        return url.toLowerCase(Locale.ROOT).contains(".m3u");
    }

    private void start(boolean asHls) {
        if (player == null) return;
        Channel c = list.get(index);
        triedHls = asHls;
        fatal = false;
        error.setVisibility(View.GONE);
        String scheme = Uri.parse(c.url).getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            player.stop();
            showError("Ссылки вида " + scheme + ":// пока не поддерживаются.", true);
            return;
        }
        Source s = st.current();
        String ua = !c.userAgent.isEmpty() ? c.userAgent : s != null && !s.ua.isEmpty() ? s.ua : Net.UA;
        Map<String, String> hdr = new HashMap<>();
        if (!c.referrer.isEmpty()) hdr.put("Referer", c.referrer);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(12_000)
                .setReadTimeoutMs(20_000)
                .setDefaultRequestProperties(hdr);
        MediaItem.Builder mb = new MediaItem.Builder().setUri(c.url);
        if (asHls || looksHls(c.url)) mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build());
        player.setMediaSource(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(this, http)).createMediaSource(mb.build()));
        player.prepare();
        player.setPlayWhenReady(true);
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            buffering.setVisibility(state == Player.STATE_BUFFERING ? View.VISIBLE : View.GONE);
            if (state == Player.STATE_READY) {
                retries = 0;
                error.setVisibility(View.GONE);
            } else if (state == Player.STATE_ENDED) {
                // Прямой эфир оборвался — переподключаемся.
                ui.removeCallbacks(retryRun);
                ui.postDelayed(retryRun, 1500);
            }
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            onError(e);
        }

        @Override
        public void onTracksChanged(@NonNull Tracks t) {
            bAudio.setVisibility(audioTracks().size() > 1 ? View.VISIBLE : View.GONE);
        }
    };

    private void onError(PlaybackException e) {
        if (player == null) return;
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        Channel c = list.get(index);
        if (!triedHls && !looksHls(c.url) && (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
            start(true); // ссылка без «.m3u8», но внутри HLS
            return;
        }
        int http = httpCode(e);
        boolean hard = http == 401 || http == 403 || http == 404 || isCodec(e.errorCode);
        if (!hard && retries < 3) {
            retries++;
            showError(describe(e) + "\nПовтор " + retries + " из 3…", false);
            ui.removeCallbacks(retryRun);
            ui.postDelayed(retryRun, 2500L * retries);
            return;
        }
        showError(describe(e) + (tv ? "\n\nOK — повторить   ▲▼ — другой канал" : "\n\nКоснитесь экрана, чтобы повторить"), true);
    }

    private static int httpCode(PlaybackException e) {
        Throwable t = e.getCause();
        return t instanceof HttpDataSource.InvalidResponseCodeException ? ((HttpDataSource.InvalidResponseCodeException) t).responseCode : 0;
    }

    private static boolean isCodec(int code) {
        return code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED || code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED
                || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED || code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES;
    }

    private static String describe(PlaybackException e) {
        int http = httpCode(e);
        if (http == 401 || http == 403) return "Доступ к каналу закрыт (код " + http + ").\nПроверьте подписку у провайдера.";
        if (http == 404) return "Канал не найден на сервере (код 404).\nВозможно, плейлист устарел — обновите его в меню.";
        if (http > 0) return "Сервер канала ответил ошибкой " + http + ".";
        switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "Нет связи с сервером канала.";
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
                return "Не удалось разобрать поток канала.";
            default:
                if (isCodec(e.errorCode)) return "Устройство не может показать видео этого канала.";
                return "Канал сейчас не показывает.";
        }
    }

    private void showError(String msg, boolean fin) {
        buffering.setVisibility(View.GONE);
        error.setText(msg);
        error.setVisibility(View.VISIBLE);
        fatal = fin;
    }

    private void retryNow() {
        retries = 0;
        ui.removeCallbacks(retryRun);
        start(false);
    }

    private void switchTo(boolean now) {
        Channel c = list.get(index);
        st.playIndex = index;
        st.played(c);
        retries = 0;
        fatal = false;
        ui.removeCallbacks(retryRun);
        ui.removeCallbacks(startRun);
        error.setVisibility(View.GONE);
        if (now) start(false);
        else {
            if (player != null) player.stop();
            ui.postDelayed(startRun, 350); // быстрое листание: запускаем только последний канал
        }
        showInfo(false);
    }

    private void zap(int d) {
        if (list.size() < 2) return;
        index = ((index + d) % list.size() + list.size()) % list.size();
        switchTo(false);
    }

    private void togglePause(int k) {
        if (player == null) return;
        boolean play = k == KeyEvent.KEYCODE_MEDIA_PLAY || (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !player.getPlayWhenReady());
        if (play) {
            player.seekToDefaultPosition();
            player.play();
        } else player.pause();
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
        if (player == null) return r;
        for (Tracks.Group g : player.getCurrentTracks().getGroups()) {
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
        if (ts.size() < 2 || player == null) return;
        String[] names = new String[ts.size()];
        int sel = 0;
        for (int i = 0; i < ts.size(); i++) {
            names[i] = ts.get(i).label;
            if (ts.get(i).sel) sel = i;
        }
        new AlertDialog.Builder(this).setTitle("Звуковая дорожка").setSingleChoiceItems(names, sel, (d, w) -> {
            d.dismiss();
            if (player == null) return;
            Track t = ts.get(w);
            player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
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
        if (focus) bList.requestFocus();
        ui.removeCallbacks(hideInfoRun);
        ui.postDelayed(hideInfoRun, focus || !tv ? 8000 : 5000);
    }

    private void fillTime() {
        long t = System.currentTimeMillis();
        infoClock.setText(hm.format(new Date(t)));
        Channel c = list.get(index);
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
        sideTitle.setText(st.playTitle + "  ·  " + list.size());
        sa.current = list.get(index);
        sa.set(list);
        side.setVisibility(View.VISIBLE);
        ((LinearLayoutManager) sideList.getLayoutManager()).scrollToPositionWithOffset(index, Ui.dp(this, 140));
        focusAt(index, 10);
        ui.removeCallbacks(hideSideRun);
        ui.postDelayed(hideSideRun, 20_000);
    }

    private void focusAt(int pos, int tries) {
        RecyclerView.ViewHolder vh = sideList.findViewHolderForAdapterPosition(pos);
        if (vh != null) vh.itemView.requestFocus();
        else if (tries > 0) sideList.postDelayed(() -> focusAt(pos, tries - 1), 40);
    }

    private void hideSide() {
        side.setVisibility(View.GONE);
        ui.removeCallbacks(hideSideRun);
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
        if (e.getAction() == KeyEvent.ACTION_DOWN && list != null && !list.isEmpty() && handleKey(k)) {
            eatUp = k;
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private boolean handleKey(int k) {
        boolean sideOn = side.getVisibility() == View.VISIBLE, infoOn = info.getVisibility() == View.VISIBLE;
        if (infoOn) {
            ui.removeCallbacks(hideInfoRun);
            ui.postDelayed(hideInfoRun, info.hasFocus() ? 8000 : 5000);
        }
        if (sideOn) {
            ui.removeCallbacks(hideSideRun);
            ui.postDelayed(hideSideRun, 20_000);
        }
        switch (k) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (sideOn) {
                    hideSide();
                    return true;
                }
                if (infoOn) {
                    info.setVisibility(View.GONE);
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
            default:
                break;
        }
        if (sideOn) return false; // списком управляет RecyclerView
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
                } else if (fatal) retryNow();
                else showSide();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                showSide();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
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
                else if (fatal) retryNow();
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
                    if (dx > 0) showSide();
                    else hideSide();
                    return true;
                }
                return false;
            }
        });
        root.setOnTouchListener((v, e) -> gd.onTouchEvent(e));
    }
}
