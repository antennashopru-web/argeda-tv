package ru.argeda.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.ArrayAdapter
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import java.util.Locale

/**
 * Показ канала на телевизоре через Chromecast (или телевизор со встроенным Chromecast).
 * Телевизор сам открывает ссылку канала, поэтому работают каналы, чей сервер это разрешает
 * (обычно HLS — .m3u8); каналы, которым нужны особые заголовки, на телевизоре могут не открыться.
 */
internal object Cast {
    private var pending: Channel? = null
    private var listening = false

    /** Есть ли на телефоне сервисы Google, без них Chromecast не работает. */
    fun available(c: Context): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(c) == ConnectionResult.SUCCESS
    } catch (_: Throwable) {
        false
    }

    private fun context(c: Context): CastContext? = try {
        @Suppress("DEPRECATION")
        CastContext.getSharedInstance(c.applicationContext)
    } catch (_: Throwable) {
        null
    }

    fun pick(a: Activity, ch: Channel) {
        val cc = context(a)
        if (cc == null) {
            Ui.toast(a, "Chromecast недоступен на этом телефоне")
            return
        }
        listen(a, cc)
        val cur = cc.sessionManager.currentCastSession
        if (cur != null && cur.isConnected) {
            val name = cur.castDevice?.friendlyName ?: "телевизор"
            AlertDialog.Builder(a).setTitle("Показ на «$name»")
                .setItems(arrayOf("Показать «" + ch.name + "»", "Остановить показ")) { _, w ->
                    if (w == 0) load(a, cur, ch)
                    else cc.sessionManager.endCurrentSession(true)
                }.show()
            return
        }
        val sel = cc.mergedSelector ?: MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()
        val router = MediaRouter.getInstance(a)
        val routes = ArrayList<MediaRouter.RouteInfo>()
        val names = ArrayAdapter<String>(a, android.R.layout.simple_list_item_1, ArrayList())
        fun refresh() {
            routes.clear()
            for (r in router.routes) if (!r.isDefaultOrBluetooth && r.isEnabled && r.matchesSelector(sel)) routes.add(r)
            names.clear()
            names.addAll(routes.map { it.name })
            names.notifyDataSetChanged()
        }
        val cb = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        }
        router.addCallback(sel, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        refresh()
        val d = AlertDialog.Builder(a).setTitle("Показать на телевизоре")
            .setAdapter(names) { _, w ->
                val r = routes.getOrNull(w) ?: return@setAdapter
                pending = ch
                Ui.toast(a, "Подключаюсь к «" + r.name + "»…")
                router.selectRoute(r)
            }
            .setNegativeButton("Отмена", null)
            .create()
        d.setOnDismissListener { router.removeCallback(cb) }
        d.show()
        // Пока ничего не нашлось — подсказка, а не пустое окно (нажатие на неё ничего не делает).
        if (routes.isEmpty()) names.add("Ищу телевизоры в вашей сети Wi-Fi…")
    }

    private fun listen(a: Activity, cc: CastContext) {
        if (listening) return
        listening = true
        val app = a.applicationContext
        cc.sessionManager.addSessionManagerListener(object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(s: CastSession, id: String) {
                pending?.let { load(app, s, it) }
                pending = null
            }

            override fun onSessionResumed(s: CastSession, was: Boolean) {
                pending?.let { load(app, s, it) }
                pending = null
            }

            override fun onSessionStartFailed(s: CastSession, err: Int) {
                pending = null
                Ui.toast(app, "Не удалось подключиться к телевизору")
            }

            override fun onSessionStarting(s: CastSession) {}
            override fun onSessionEnding(s: CastSession) {}
            override fun onSessionEnded(s: CastSession, err: Int) {}
            override fun onSessionResuming(s: CastSession, id: String) {}
            override fun onSessionResumeFailed(s: CastSession, err: Int) {}
            override fun onSessionSuspended(s: CastSession, reason: Int) {}
        }, CastSession::class.java)
    }

    private fun load(c: Context, s: CastSession, ch: Channel) {
        val client = s.remoteMediaClient ?: return
        val md = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
        md.putString(MediaMetadata.KEY_TITLE, ch.name)
        val u = ch.url.substringBefore('?').lowercase(Locale.ROOT)
        val type = when {
            u.endsWith(".m3u8") || u.endsWith(".m3u") -> "application/x-mpegURL"
            u.endsWith(".mpd") -> "application/dash+xml"
            u.endsWith(".mp4") -> "video/mp4"
            else -> "application/x-mpegURL"
        }
        val info = MediaInfo.Builder(ch.url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType(type)
            .setMetadata(md)
            .build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
            .setResultCallback { r ->
                if (r.status.isSuccess) {
                    Playback.get(c).player?.pause() // на телефоне — пауза, звук идёт с телевизора
                    Ui.toast(c, "«" + ch.name + "» на телевизоре")
                } else Ui.toast(c, "Телевизор не смог открыть «" + ch.name + "». Не все каналы можно показать через Chromecast")
            }
    }
}

/** Настройки Chromecast: стандартный приёмник Google. Указан в манифесте. */
class CastSetup : OptionsProvider {
    override fun getCastOptions(c: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID).build()

    override fun getAdditionalSessionProviders(c: Context): List<SessionProvider>? = null
}
