package ru.argeda.tv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import java.util.Locale

/**
 * Один проигрыватель на всё приложение: окно предпросмотра на главном экране и полный экран
 * показывают один и тот же поток, поэтому переход на весь экран идёт без новой загрузки.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class Playback private constructor(private val app: Context) {
    fun interface Listener {
        fun playbackChanged()
    }

    private val st: Store = Store.get(app)
    private val ui = Handler(Looper.getMainLooper())
    private val ls = ArrayList<Listener>()
    @JvmField var player: ExoPlayer? = null
    private var owners = 0

    @JvmField var channel: Channel? = null

    /** Архив: 0 — прямой эфир. archBase — момент, с которого идёт текущий поток; archStart/archEnd — границы передачи. */
    @JvmField var archBase = 0L
    @JvmField var archStart = 0L
    @JvmField var archEnd = 0L
    @JvmField var archTitle = ""
    @JvmField var buffering = false
    @JvmField var fatal = false
    @JvmField var error = ""
    private var retries = 0
    private var triedHls = false
    private var playedMs = 0L
    private var playingSince = 0L

    private val retryRun = Runnable { start(triedHls) }

    /**
     * Скорость сети запоминается между каналами и запусками приложения: новый канал сразу
     * берёт подходящее качество, без разгона с самого низкого.
     */
    private var meter: DefaultBandwidthMeter? = null

    fun addListener(l: Listener) {
        if (!ls.contains(l)) ls.add(l)
    }

    fun removeListener(l: Listener) {
        ls.remove(l)
    }

    private fun notifyChanged() {
        for (l in ArrayList(ls)) l.playbackChanged()
    }

    /** Экран, которому нужен проигрыватель. Пока есть хоть один такой экран, поток не останавливается. */
    fun acquire() {
        owners++
        if (player != null) return
        val m = meter ?: run {
            val bw = app.getSharedPreferences("argeda", Context.MODE_PRIVATE).getLong(PREF_BW, 0)
            val mb = DefaultBandwidthMeter.Builder(app)
            if (bw > 0) mb.setInitialBitrateEstimate(bw)
            mb.build().also { meter = it }
        }
        // Эфир стартует, как только набралось 0,5 с видео (по умолчанию ExoPlayer ждёт 2,5 с).
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 50_000, 500, 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val p = ExoPlayer.Builder(app, DefaultRenderersFactory(app).setEnableDecoderFallback(true))
            .setLoadControl(load)
            .setBandwidthMeter(m)
            .build()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        p.addListener(listener)
        player = p
        if (channel != null) start(false) // возвращение в приложение — продолжаем с того же места
    }

    fun release() {
        if (--owners > 0) return
        owners = 0
        ui.removeCallbacks(retryRun)
        saveBandwidth()
        player?.let {
            if (isArchive()) archBase = position()
            it.release()
            player = null
        }
        buffering = false
    }

    private fun saveBandwidth() {
        val bw = meter?.bitrateEstimate ?: return
        if (bw > 100_000) app.getSharedPreferences("argeda", Context.MODE_PRIVATE).edit().putLong(PREF_BW, bw).apply()
    }

    /** Оценка скорости интернета, бит/с (0 — ещё не известна). */
    fun bandwidth(): Long = meter?.bitrateEstimate ?: 0

    fun isArchive(): Boolean = archStart > 0

    /** Текущий момент эфира, который сейчас на экране (для архива — время записи). */
    fun position(): Long {
        if (!isArchive()) return System.currentTimeMillis()
        val played = playedMs + if (playingSince > 0) SystemClock.elapsedRealtime() - playingSince else 0
        return archBase + played
    }

    fun play(c: Channel) {
        channel = c
        archStart = 0
        archEnd = 0
        archBase = 0
        archTitle = ""
        retries = 0
        start(false)
    }

    fun playArchive(c: Channel, from: Long, progStart: Long, progEnd: Long, title: String?) {
        channel = c
        archStart = maxOf(1L, progStart)
        archEnd = progEnd
        archBase = from
        archTitle = title ?: ""
        retries = 0
        start(false)
    }

    fun seekArchive(at: Long) {
        val c = channel
        if (c == null || !isArchive()) return
        val now = System.currentTimeMillis()
        if (at >= now - 30_000L) {
            play(c)
            return
        }
        val days = Catchup.days(c, st.current())
        archBase = maxOf(at, now - days * 86_400_000L + 60_000L)
        retries = 0
        start(false)
    }

    fun stop() {
        ui.removeCallbacks(retryRun)
        channel = null
        archStart = 0
        error = ""
        fatal = false
        buffering = false
        player?.stop()
        notifyChanged()
    }

    fun retry() {
        retries = 0
        start(false)
    }

    private fun start(asHls: Boolean) {
        ui.removeCallbacks(retryRun)
        val p = player
        val c = channel
        if (p == null || c == null) {
            notifyChanged()
            return
        }
        triedHls = asHls
        fatal = false
        error = ""
        playedMs = 0
        playingSince = 0
        val s = st.current()
        var url: String = c.url
        if (isArchive()) {
            val end = if (archEnd > archBase) archEnd else archBase + 3600_000L
            val a = Catchup.url(c, s, st.headerTz, archBase, end, System.currentTimeMillis())
            if (a == null) {
                fail("Архив для этого плейлиста выключен в настройках.", true)
                return
            }
            url = a
        }
        val scheme = Uri.parse(url).scheme
        if (scheme == null || !(scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true))) {
            p.stop()
            fail("Ссылки вида $scheme:// пока не поддерживаются.", true)
            return
        }
        val ua = when {
            c.userAgent.isNotEmpty() -> c.userAgent
            s != null && s.ua.isNotEmpty() -> s.ua
            else -> Net.UA
        }
        val hdr = HashMap<String, String>()
        if (c.referrer.isNotEmpty()) hdr["Referer"] = c.referrer
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(20_000)
            .setDefaultRequestProperties(hdr)
        val mb = MediaItem.Builder().setUri(url)
        if (asHls || looksHls(url)) mb.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        p.setMediaSource(DefaultMediaSourceFactory(DefaultDataSource.Factory(app, http)).createMediaSource(mb.build()))
        p.prepare()
        p.playWhenReady = true
        notifyChanged()
    }

    private fun fail(msg: String, fin: Boolean) {
        error = msg
        fatal = fin
        buffering = false
        notifyChanged()
    }

    private val listener: Player.Listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            if (state == Player.STATE_READY) {
                retries = 0
                error = ""
                fatal = false
            } else if (state == Player.STATE_ENDED) {
                if (isArchive()) seekArchive(position()) // запись закончилась — идём дальше по времени
                else ui.postDelayed(retryRun, 1500) // прямой эфир оборвался — переподключаемся
            }
            notifyChanged()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            val t = SystemClock.elapsedRealtime()
            if (playing) playingSince = t
            else if (playingSince > 0) {
                playedMs += t - playingSince
                playingSince = 0
            }
            notifyChanged() // экран не гаснет, пока идёт видео
        }

        override fun onPlayerError(e: PlaybackException) {
            onError(e)
        }

        override fun onTracksChanged(t: Tracks) {
            notifyChanged()
        }
    }

    /** Видео идёт или вот-вот пойдёт (буферизация, переподключение) — экран гасить нельзя. */
    fun active(): Boolean = player != null && channel != null && player!!.playWhenReady

    private fun onError(e: PlaybackException) {
        val p = player ?: return
        if (channel == null) return
        if (e.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
            p.seekToDefaultPosition()
            p.prepare()
            return
        }
        if (!triedHls && (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        ) {
            start(true) // ссылка без «.m3u8», но внутри HLS
            return
        }
        val http = httpCode(e)
        val hard = http == 401 || http == 403 || http == 404 || isCodec(e.errorCode)
        if (!hard && retries < 3) {
            retries++
            fail(describe(e) + "\nПовтор " + retries + " из 3…", false)
            ui.postDelayed(retryRun, 1000L * retries)
            return
        }
        var msg = describe(e)
        if (isArchive() && (http == 404 || http == 400 || http == 403))
            msg = "Запись недоступна (код $http).\nЕсли у провайдера другой тип архива, выберите его в настройках плейлиста."
        fail(msg, true)
    }

    companion object {
        private const val PREF_BW = "bw"
        private var inst: Playback? = null

        @JvmStatic
        fun get(c: Context): Playback = synchronized(Playback::class.java) {
            inst ?: Playback(c.applicationContext).also { inst = it }
        }

        private fun looksHls(url: String): Boolean = url.lowercase(Locale.ROOT).contains(".m3u")

        private fun httpCode(e: PlaybackException): Int =
            (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0

        private fun isCodec(code: Int): Boolean =
            code == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED || code == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED ||
                code == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ||
                code == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES

        private fun describe(e: PlaybackException): String {
            val http = httpCode(e)
            if (http == 401 || http == 403) return "Доступ к каналу закрыт (код $http).\nПроверьте подписку у провайдера."
            if (http == 404) return "Канал не найден на сервере (код 404).\nВозможно, плейлист устарел — обновите его в настройках."
            if (http > 0) return "Сервер канала ответил ошибкой $http."
            return when (e.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Нет связи с сервером канала."
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "Не удалось разобрать поток канала."
                else -> if (isCodec(e.errorCode)) "Устройство не может показать видео этого канала." else "Канал сейчас не показывает."
            }
        }
    }
}
