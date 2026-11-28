package ru.argeda.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import java.util.concurrent.Executors

/**
 * Строка «ARGEDA TV» на главном экране Android TV: каналы из «Моё» (если там пусто — недавние).
 * Нажатие на карточку сразу включает канал. Обновляется, когда зритель выходит из приложения.
 * На телефонах и на приставках без главного экрана Android TV (API < 26, нет TvProvider) ничего не делает.
 */
internal object HomeChannel {
    private const val MAX = 20
    private const val PREF_CH = "homeCh"
    private const val PREF_PROGS = "homeProgs"
    const val EXTRA_PLAY = "play"

    private val bg = Executors.newSingleThreadExecutor()
    private var lastSig = ""

    private fun supported(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 26 && c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    /** Снимок каналов берётся на главном потоке, запись в TvProvider — в фоне. */
    fun update(c: Context) {
        val app = c.applicationContext
        if (!supported(app)) return
        val st = Store.get(app)
        if (st.all.isEmpty()) return
        val vis = st.visible()
        val byKey = HashMap<String, Channel>(vis.size * 2)
        for (ch in vis) if (!ch.adult) byKey.putIfAbsent(ch.key(), ch) // 18+ на главный экран Android TV не выносим
        val keys = st.favOrder.ifEmpty { st.recent }
        val now = System.currentTimeMillis()
        val items = keys.mapNotNull { byKey[it] }.take(MAX).map { ch ->
            Item(ch.key(), ch.name, ch.logo, Epg.at(ch.progs, now)?.title.orEmpty())
        }
        val title = if (st.favOrder.isNotEmpty()) "ARGEDA TV · Моё" else "ARGEDA TV · Недавние"
        // Если ничего не поменялось (те же каналы, те же передачи), TvProvider не трогаем.
        val sig = title + "\n" + items.joinToString("\n") { it.key + "|" + it.logo + "|" + it.now }
        if (sig == lastSig) return
        lastSig = sig
        bg.execute {
            try {
                publish(app, title, items)
            } catch (_: Throwable) {
                // Нет TvProvider или он отказал — главный экран просто без нашей строки.
            }
        }
    }

    private class Item(val key: String, val name: String, val logo: String, val now: String)

    private fun publish(app: Context, title: String, items: List<Item>) {
        val h = PreviewChannelHelper(app)
        val p = app.getSharedPreferences("argeda", Context.MODE_PRIVATE)
        var id = p.getLong(PREF_CH, -1L)
        if (id > 0 && h.getPreviewChannel(id) == null) id = -1L
        val link = Uri.parse(Intent(app, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME))
        if (id <= 0) {
            val b = PreviewChannel.Builder().setDisplayName(title).setAppLinkIntentUri(link)
            icon(app)?.let { b.setLogo(it) }
            id = h.publishChannel(b.build())
            p.edit().putLong(PREF_CH, id).apply()
            try {
                TvContractCompat.requestChannelBrowsable(app, id)
            } catch (_: Throwable) {
            }
        } else {
            h.updatePreviewChannel(id, PreviewChannel.Builder().setDisplayName(title).setAppLinkIntentUri(link).build())
        }
        // Старые карточки убираем и ставим заново: так порядок всегда как в «Моё».
        for (old in p.getString(PREF_PROGS, "").orEmpty().split(',')) {
            val pid = old.toLongOrNull() ?: continue
            try {
                h.deletePreviewProgram(pid)
            } catch (_: Throwable) {
            }
        }
        val ids = ArrayList<Long>()
        val banner = Uri.parse("android.resource://" + app.packageName + "/" + R.drawable.banner)
        for ((i, it) in items.withIndex()) {
            val play = Intent(app, MainActivity::class.java).putExtra(EXTRA_PLAY, it.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val logo = it.logo.takeIf { l -> l.startsWith("http://") || l.startsWith("https://") }
            val b = PreviewProgram.Builder()
                .setChannelId(id)
                .setType(TvContractCompat.PreviewPrograms.TYPE_CHANNEL)
                .setTitle(it.name)
                .setDescription(it.now)
                .setPosterArtUri(if (logo != null) Uri.parse(logo) else banner)
                .setPosterArtAspectRatio(if (logo != null) TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1 else TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
                .setLive(true)
                .setInternalProviderId(it.key)
                .setWeight(items.size - i)
                .setIntentUri(Uri.parse(play.toUri(Intent.URI_INTENT_SCHEME)))
            ids.add(h.publishPreviewProgram(b.build()))
        }
        p.edit().putString(PREF_PROGS, ids.joinToString(",")).apply()
    }

    /** Значок приложения картинкой (адаптивный значок — это не PNG, его надо нарисовать). */
    private fun icon(c: Context): Bitmap? = try {
        val d = ContextCompat.getDrawable(c, R.mipmap.ic_launcher) ?: throw IllegalStateException()
        val bm = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bm)
        d.setBounds(0, 0, 160, 160)
        d.draw(cv)
        bm
    } catch (_: Throwable) {
        android.graphics.BitmapFactory.decodeResource(c.resources, R.drawable.banner)
    }
}
