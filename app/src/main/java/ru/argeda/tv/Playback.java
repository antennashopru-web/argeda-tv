package ru.argeda.tv;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Один проигрыватель на всё приложение: окно предпросмотра на главном экране и полный экран
 * показывают один и тот же поток, поэтому переход на весь экран идёт без новой загрузки.
 */
@OptIn(markerClass = UnstableApi.class)
final class Playback {
    interface Listener {
        void playbackChanged();
    }

    private static Playback inst;

    static synchronized Playback get(Context c) {
        if (inst == null) inst = new Playback(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final Store st;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Listener> ls = new ArrayList<>();
    ExoPlayer player;
    private int owners;

    Channel channel;
    /** Архив: 0 — прямой эфир. archBase — момент, с которого идёт текущий поток; archStart/archEnd — границы передачи. */
    long archBase, archStart, archEnd;
    String archTitle = "";
    boolean buffering, fatal;
    String error = "";
    private int retries;
    private boolean triedHls;
    private long playedMs, playingSince;

    private final Runnable retryRun = () -> start(triedHls);

    /**
     * Скорость сети запоминается между каналами и запусками приложения: новый канал сразу
     * берёт подходящее качество, без разгона с самого низкого.
     */
    private DefaultBandwidthMeter meter;
    private static final String PREF_BW = "bw";

    private Playback(Context c) {
        app = c;
        st = Store.get(c);
    }

    void addListener(Listener l) {
        if (!ls.contains(l)) ls.add(l);
    }

    void removeListener(Listener l) {
        ls.remove(l);
    }

    private void notifyChanged() {
        for (Listener l : new ArrayList<>(ls)) l.playbackChanged();
    }

    /** Экран, которому нужен проигрыватель. Пока есть хоть один такой экран, поток не останавливается. */
    void acquire() {
        owners++;
        if (player != null) return;
        if (meter == null) {
            long bw = app.getSharedPreferences("argeda", Context.MODE_PRIVATE).getLong(PREF_BW, 0);
            DefaultBandwidthMeter.Builder mb = new DefaultBandwidthMeter.Builder(app);
            if (bw > 0) mb.setInitialBitrateEstimate(bw);
            meter = mb.build();
        }
        // Эфир стартует, как только набралось 0,5 с видео (по умолчанию ExoPlayer ждёт 2,5 с).
        DefaultLoadControl load = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 50_000, 500, 2_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build();
        player = new ExoPlayer.Builder(app, new DefaultRenderersFactory(app).setEnableDecoderFallback(true))
                .setLoadControl(load)
                .setBandwidthMeter(meter)
                .build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        player.addListener(listener);
        if (channel != null) start(false); // возвращение в приложение — продолжаем с того же места
    }

    void release() {
        if (--owners > 0) return;
        owners = 0;
        ui.removeCallbacks(retryRun);
        saveBandwidth();
        if (player != null) {
            if (isArchive()) archBase = position();
            player.release();
            player = null;
        }
        buffering = false;
    }

    private void saveBandwidth() {
        if (meter == null) return;
        long bw = meter.getBitrateEstimate();
        if (bw > 100_000) app.getSharedPreferences("argeda", Context.MODE_PRIVATE).edit().putLong(PREF_BW, bw).apply();
    }

    boolean isArchive() {
        return archStart > 0;
    }

    /** Текущий момент эфира, который сейчас на экране (для архива — время записи). */
    long position() {
        if (!isArchive()) return System.currentTimeMillis();
        long played = playedMs + (playingSince > 0 ? SystemClock.elapsedRealtime() - playingSince : 0);
        return archBase + played;
    }

    void play(Channel c) {
        channel = c;
        archStart = archEnd = archBase = 0;
        archTitle = "";
        retries = 0;
        start(false);
    }

    void playArchive(Channel c, long from, long progStart, long progEnd, String title) {
        channel = c;
        archStart = Math.max(1, progStart);
        archEnd = progEnd;
        archBase = from;
        archTitle = title == null ? "" : title;
        retries = 0;
        start(false);
    }

    void seekArchive(long at) {
        if (channel == null || !isArchive()) return;
        long now = System.currentTimeMillis();
        if (at >= now - 30_000L) {
            play(channel);
            return;
        }
        int days = Catchup.days(channel, st.current());
        archBase = Math.max(at, now - days * 86_400_000L + 60_000L);
        retries = 0;
        start(false);
    }

    void stop() {
        ui.removeCallbacks(retryRun);
        channel = null;
        archStart = 0;
        error = "";
        fatal = false;
        buffering = false;
        if (player != null) player.stop();
        notifyChanged();
    }

    void retry() {
        retries = 0;
        start(false);
    }

    private static boolean looksHls(String url) {
        return url.toLowerCase(Locale.ROOT).contains(".m3u");
    }

    private void start(boolean asHls) {
        ui.removeCallbacks(retryRun);
        if (player == null || channel == null) {
            notifyChanged();
            return;
        }
        Channel c = channel;
        triedHls = asHls;
        fatal = false;
        error = "";
        playedMs = 0;
        playingSince = 0;
        Source s = st.current();
        String url = c.url;
        if (isArchive()) {
            long end = archEnd > archBase ? archEnd : archBase + 3600_000L;
            url = Catchup.url(c, s, st.headerTz, archBase, end, System.currentTimeMillis());
            if (url == null) {
                fail("Архив для этого плейлиста выключен в настройках.", true);
                return;
            }
        }
        String scheme = Uri.parse(url).getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            player.stop();
            fail("Ссылки вида " + scheme + ":// пока не поддерживаются.", true);
            return;
        }
        String ua = !c.userAgent.isEmpty() ? c.userAgent : s != null && !s.ua.isEmpty() ? s.ua : Net.UA;
        Map<String, String> hdr = new HashMap<>();
        if (!c.referrer.isEmpty()) hdr.put("Referer", c.referrer);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8_000)
                .setReadTimeoutMs(20_000)
                .setDefaultRequestProperties(hdr);
        MediaItem.Builder mb = new MediaItem.Builder().setUri(url);
        if (asHls || looksHls(url)) mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build());
        player.setMediaSource(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(app, http)).createMediaSource(mb.build()));
        player.prepare();
        player.setPlayWhenReady(true);
        notifyChanged();
    }

    private void fail(String msg, boolean fin) {
        error = msg;
        fatal = fin;
        buffering = false;
        notifyChanged();
    }

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            buffering = state == Player.STATE_BUFFERING;
            if (state == Player.STATE_READY) {
                retries = 0;
                error = "";
                fatal = false;
            } else if (state == Player.STATE_ENDED) {
                if (isArchive()) seekArchive(position()); // запись закончилась — идём дальше по времени
                else ui.postDelayed(retryRun, 1500);      // прямой эфир оборвался — переподключаемся
            }
            notifyChanged();
        }

        @Override
        public void onIsPlayingChanged(boolean playing) {
            long t = SystemClock.elapsedRealtime();
            if (playing) playingSince = t;
            else if (playingSince > 0) {
                playedMs += t - playingSince;
                playingSince = 0;
            }
            notifyChanged(); // экран не гаснет, пока идёт видео
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            onError(e);
        }

        @Override
        public void onTracksChanged(@NonNull Tracks t) {
            notifyChanged();
        }
    };

    /** Видео идёт или вот-вот пойдёт (буферизация, переподключение) — экран гасить нельзя. */
    boolean active() {
        return player != null && channel != null && player.getPlayWhenReady();
    }

    private void onError(PlaybackException e) {
        if (player == null || channel == null) return;
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            player.seekToDefaultPosition();
            player.prepare();
            return;
        }
        if (!triedHls && (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
                || e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)) {
            start(true); // ссылка без «.m3u8», но внутри HLS
            return;
        }
        int http = httpCode(e);
        boolean hard = http == 401 || http == 403 || http == 404 || isCodec(e.errorCode);
        if (!hard && retries < 3) {
            retries++;
            fail(describe(e) + "\nПовтор " + retries + " из 3…", false);
            ui.postDelayed(retryRun, 1000L * retries);
            return;
        }
        String msg = describe(e);
        if (isArchive() && (http == 404 || http == 400 || http == 403))
            msg = "Запись недоступна (код " + http + ").\nЕсли у провайдера другой тип архива, выберите его в настройках плейлиста.";
        fail(msg, true);
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
        if (http == 404) return "Канал не найден на сервере (код 404).\nВозможно, плейлист устарел — обновите его в настройках.";
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
}
