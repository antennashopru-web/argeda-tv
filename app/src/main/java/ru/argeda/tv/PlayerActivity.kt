package ru.argeda.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Полный экран. Поток тот же, что в окне предпросмотра (общий проигрыватель Playback).
 * Пульт: ▲▼ — каналы, OK/◀ — список, ▶ — сведения и кнопки; в записи ◀▶ — перемотка, «Назад» — в эфир.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class PlayerActivity : Activity(), Playback.Listener {
    private lateinit var st: Store
    private lateinit var pb: Playback
    private lateinit var video: PlayerView
    private lateinit var root: View
    private lateinit var side: View
    private lateinit var info: View
    private lateinit var epgSide: View
    private lateinit var buffering: View
    private lateinit var error: TextView
    private lateinit var digits: TextView
    private lateinit var seek: TextView
    private lateinit var sideTitle: TextView
    private lateinit var epgTitle: TextView
    private lateinit var epgSideEmpty: TextView
    private lateinit var infoArch: TextView
    private lateinit var infoNum: TextView
    private lateinit var infoName: TextView
    private lateinit var infoClock: TextView
    private lateinit var infoGroup: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoNext: TextView
    private lateinit var bList: TextView
    private lateinit var bLive: TextView
    private lateinit var bFav: TextView
    private lateinit var bAudio: TextView
    private lateinit var bQuality: TextView
    private lateinit var bSubs: TextView
    private lateinit var infoProg: ProgressBar
    private lateinit var sideList: RecyclerView
    private lateinit var epgSideList: RecyclerView
    private lateinit var sa: ChannelAdapter
    private lateinit var ea: EpgAdapter
    private var list: List<Channel> = emptyList()

    /** Что сейчас показано в боковом списке: категория или результаты поиска (может отличаться от list). */
    private var browse: List<Channel>? = null
    private var browseTitle = ""
    private lateinit var sideSearch: EditText
    private var index = 0
    private var fit = 0
    private var eatUp = -1
    private var tv = false

    /** Фокус на кнопках панели сведений (◀ ▶ OK — по кнопкам). Иначе пульт управляет каналами и перемоткой. */
    private var btns = false
    private var digitBuf = ""
    private var seekBase = 0L
    private var seekDelta = 0L
    private val ui = Handler(Looper.getMainLooper())
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val hideInfoRun = Runnable { hideInfo() }
    private val hideSideRun = Runnable { hideSide() }
    private val hideEpgRun = Runnable { hideEpg() }
    private val zapRun = Runnable { if (list.isNotEmpty()) pb.play(list[index]) }
    private val digitRun = Runnable { jumpToNumber() }
    private val seekRun = Runnable {
        seek.visibility = View.GONE
        if (seekDelta != 0L) pb.seekArchive(seekBase + seekDelta)
        seekDelta = 0
    }
    private val clockRun: Runnable = object : Runnable {
        override fun run() {
            if (info.visibility == View.VISIBLE) fillTime()
            ui.postDelayed(this, if (pb.isArchive()) 1000L else 20_000L)
        }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Themes.apply(this, base, true)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_player)
        st = Store.get(this)
        pb = Playback.get(this)
        Logos.init(this)
        tv = Ui.isTv(this)
        root = findViewById(R.id.root)
        video = findViewById(R.id.video)
        side = findViewById(R.id.side)
        info = findViewById(R.id.info)
        epgSide = findViewById(R.id.epgSide)
        buffering = findViewById(R.id.buffering)
        error = findViewById(R.id.error)
        digits = findViewById(R.id.digits)
        seek = findViewById(R.id.seek)
        sideTitle = findViewById(R.id.sideTitle)
        sideList = findViewById(R.id.sideList)
        sideSearch = findViewById(R.id.sideSearch)
        epgTitle = findViewById(R.id.epgTitle)
        epgSideEmpty = findViewById(R.id.epgSideEmpty)
        epgSideList = findViewById(R.id.epgSideList)
        infoArch = findViewById(R.id.infoArch)
        infoNum = findViewById(R.id.infoNum)
        infoName = findViewById(R.id.infoName)
        infoClock = findViewById(R.id.infoClock)
        infoGroup = findViewById(R.id.infoGroup)
        infoNow = findViewById(R.id.infoNow)
        infoNext = findViewById(R.id.infoNext)
        infoProg = findViewById(R.id.infoProg)
        bList = findViewById(R.id.bList)
        bLive = findViewById(R.id.bLive)
        bFav = findViewById(R.id.bFav)
        bAudio = findViewById(R.id.bAudio)
        bQuality = findViewById(R.id.bQuality)
        bSubs = findViewById(R.id.bSubs)

        list = st.playList ?: emptyList()
        index = st.playIndex
        if (list.isEmpty()) {
            // Система закрыла приложение в фоне — восстанавливаем список из сохранённого плейлиста.
            if (st.all.isEmpty()) st.loadSync()
            list = st.visible()
            index = 0
            val key = intent.getStringExtra("key")
            for (i in list.indices) if (list[i].key() == key) index = i
            st.playTitle = "Все каналы"
        }
        if (list.isEmpty()) {
            finish()
            return
        }
        if (index < 0 || index >= list.size) index = 0
        st.playList = list
        st.playIndex = index

        fit = st.fit()
        applyFit()

        sa = ChannelAdapter(st, object : ChannelAdapter.Listener {
            override fun click(pos: Int) {
                val br = browse ?: return
                if (pos < 0 || pos >= br.size) return
                hideSide()
                if (br !== list) {
                    // Выбрали канал из другой категории или из поиска: дальше ▲▼ листают уже этот список.
                    list = br
                    st.playList = list
                    st.playTitle = browseTitle
                    index = pos
                    switchTo(true)
                } else if (pos != index || pb.isArchive()) {
                    index = pos
                    switchTo(true)
                }
            }

            override fun longClick(pos: Int) {
                val br = browse ?: return
                if (pos < 0 || pos >= br.size) return
                val c = br[pos]
                val on = st.toggleFav(c)
                Ui.toast(this@PlayerActivity, if (on) "«" + c.name + "» добавлен в избранное" else "«" + c.name + "» убран из избранного")
                sa.notifyItemChanged(pos)
            }

            override fun focus(pos: Int) {}

            override fun key(pos: Int, keyCode: Int): Boolean {
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    hideSide()
                    return true
                }
                return false
            }
        })
        sideList.layoutManager = LinearLayoutManager(this)
        sideList.adapter = sa
        sideList.itemAnimator = null

        ea = EpgAdapter(object : EpgAdapter.Listener {
            override fun click(pos: Int) {
                if (pos < ea.rows().size) fromEpg(ea.rows()[pos])
            }

            override fun focus(pos: Int) {}

            override fun key(pos: Int, keyCode: Int): Boolean {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    hideEpg()
                    return true
                }
                return false
            }
        })
        epgSideList.layoutManager = LinearLayoutManager(this)
        epgSideList.adapter = ea
        epgSideList.itemAnimator = null

        bList.setOnClickListener { showSide() }
        sideTitle.setOnClickListener { pickGroup() }
        findViewById<View>(R.id.sideSearchBtn).setOnClickListener { toggleSearch() }
        sideSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

            override fun afterTextChanged(e: Editable?) {
                keepSide()
                ui.removeCallbacks(searchRun)
                ui.postDelayed(searchRun, 250)
            }
        })
        sideSearch.setOnEditorActionListener { _, _, _ ->
            ui.removeCallbacks(searchRun)
            searchRun.run()
            hideKeyboard()
            if (!browse.isNullOrEmpty()) focusAt(sideList, 0, 10)
            true
        }
        findViewById<View>(R.id.bEpg).setOnClickListener { showEpg() }
        bLive.setOnClickListener { toLive() }
        bFav.setOnClickListener {
            val c = list[index]
            val on = st.toggleFav(c)
            favButton(on)
            Ui.toast(this, if (on) "Добавлено в избранное" else "Убрано из избранного")
        }
        findViewById<View>(R.id.bFit).setOnClickListener {
            fit = (fit + 1) % 3
            applyFit()
            st.setFit(fit)
            Ui.toast(this, "Формат: " + FIT_NAMES[fit])
        }
        bAudio.setOnClickListener { pickAudio() }
        bQuality.setOnClickListener { pickQuality() }
        bSubs.setOnClickListener { pickSubs() }
        findViewById<View>(R.id.bStats).setOnClickListener { showStats() }
        val bSleep = findViewById<TextView>(R.id.bSleep)
        bSleep.text = "Сон: " + Alerts.sleepLabel()
        bSleep.setOnClickListener {
            Alerts.nextSleep(this)
            bSleep.text = "Сон: " + Alerts.sleepLabel()
            showInfo(true)
        }
        val bRot = findViewById<TextView>(R.id.bRot)
        if (!tv) {
            // Телефон и планшет: экран поворачивается за устройством, либо закреплён кнопкой «Поворот».
            rot = getSharedPreferences("argeda", MODE_PRIVATE).getInt("rot", 0)
            bRot.visibility = View.VISIBLE
            bRot.text = "Поворот: " + ROT_NAMES[rot]
            bRot.setOnClickListener {
                rot = (rot + 1) % ROT_NAMES.size
                getSharedPreferences("argeda", MODE_PRIVATE).edit().putInt("rot", rot).apply()
                bRot.text = "Поворот: " + ROT_NAMES[rot]
                applyRotation()
            }
        }
        applyRotation()
        fitPanels()
        // Панели кликабельны только чтобы касание не проходило к видео; фокус пульта им не нужен
        // (иначе после появления панели OK попадал в пустую панель и ничего не делал).
        for (v in arrayOf(side, info, epgSide)) {
            v.isClickable = true
            v.isFocusable = false
        }
        // Корень держит фокус, когда панели скрыты, чтобы он не оставался на невидимой кнопке.
        root.isFocusable = true
        if (Build.VERSION.SDK_INT >= 26) root.setDefaultFocusHighlightEnabled(false)
        setupGestures()
    }

    private var rot = 0

    private fun applyRotation() {
        val o = when {
            tv -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            rot == 1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            rot == 2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        requestedOrientation = o
    }

    /** Боковые списки в книжной ориентации узкого экрана не шире 90% ширины. */
    private fun fitPanels() {
        val w = resources.displayMetrics.widthPixels
        setWidth(side, min(Ui.dp(this, 380), w * 9 / 10))
        setWidth(epgSide, min(Ui.dp(this, 440), w * 9 / 10))
    }

    /** Телефон: кнопка «Домой» во время просмотра — «картинка в картинке» (Android 8+). */
    @SuppressLint("NewApi")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val p = pb.player
        if (tv || Build.VERSION.SDK_INT < 26 || p == null || !p.isPlaying) return
        try {
            var w = 16
            var h = 9
            val f = p.videoFormat
            if (f != null && f.width > 0 && f.height > 0) {
                w = f.width
                h = f.height
                if (w * 100 > h * 239) h = w * 100 / 239 // система принимает от 1:2,39 до 2,39:1
                if (h * 100 > w * 239) w = h * 100 / 239
            }
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(w, h)).build())
        } catch (ignored: Exception) {
            // устройство не поддерживает «картинку в картинке»
        }
    }

    @SuppressLint("NewApi")
    override fun onPictureInPictureModeChanged(pip: Boolean, c: Configuration) {
        super.onPictureInPictureModeChanged(pip, c)
        if (!pip) return
        hideInfo()
        hideSide()
        hideEpg()
        digits.visibility = View.GONE
        seek.visibility = View.GONE
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        fitPanels()
        hideSystemUi()
    }

    override fun onStart() {
        super.onStart()
        Alerts.attach(this)
        if (list.isEmpty()) return
        hideSystemUi()
        pb.acquire()
        video.player = pb.player
        pb.addListener(this)
        val want = list[index]
        if (pb.channel == null) pb.play(want)
        else if (!MainActivity.same(pb.channel, want)) {
            val i = indexIn(list, pb.channel)
            if (i >= 0) index = i else pb.play(want)
        }
        showInfo(false)
        playbackChanged()
        ui.post(clockRun)
    }

    override fun onStop() {
        super.onStop()
        ui.removeCallbacksAndMessages(null)
        Alerts.detach(this)
        if (!::pb.isInitialized || list.isEmpty()) return
        pb.removeListener(this)
        video.player = null
        pb.release()
    }

    override fun onWindowFocusChanged(focus: Boolean) {
        super.onWindowFocusChanged(focus)
        if (focus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    // ---------- Состояние проигрывателя ----------

    override fun playbackChanged() {
        Ui.keepScreenOn(this, pb.active())
        val err = pb.error.isNotEmpty()
        buffering.visibility = if (pb.buffering && !err) View.VISIBLE else View.GONE
        if (err) {
            error.text = pb.error + (if (pb.fatal) (if (tv) "\n\nOK — повторить   ▲▼ — другой канал" else "\n\nКоснитесь экрана, чтобы повторить") else "")
            error.visibility = View.VISIBLE
        } else error.visibility = View.GONE
        val arch = pb.isArchive()
        infoArch.visibility = if (arch) View.VISIBLE else View.GONE
        bLive.visibility = if (arch) View.VISIBLE else View.GONE
        bAudio.visibility = if (audioTracks().size > 1) View.VISIBLE else View.GONE
        bQuality.visibility = if (tracks(C.TRACK_TYPE_VIDEO).size > 1) View.VISIBLE else View.GONE
        bSubs.visibility = if (tracks(C.TRACK_TYPE_TEXT).isNotEmpty()) View.VISIBLE else View.GONE
        // «В прямой эфир» исчезла под фокусом — переводим его на соседнюю кнопку, а не в пустоту.
        if (btns && info.visibility == View.VISIBLE && !info.hasFocus()) bList.requestFocus()
        if (info.visibility == View.VISIBLE) fillTime()
        if (epgSide.visibility == View.VISIBLE) {
            val ps = if (arch) pb.archStart else 0L
            if (ps != ea.playing) {
                ea.playing = ps
                ea.notifyItemRangeChanged(0, ea.itemCount, "p")
            }
        }
    }

    private fun switchTo(now: Boolean) {
        val c = list[index]
        livePausedAt = 0
        st.playIndex = index
        st.played(c)
        ui.removeCallbacks(zapRun)
        ui.removeCallbacks(seekRun)
        seekDelta = 0
        seek.visibility = View.GONE
        if (now) pb.play(c)
        else {
            pb.player?.stop()
            ui.postDelayed(zapRun, 200) // быстрое листание: запускаем только последний канал
        }
        showInfo(false)
    }

    private fun zap(d: Int) {
        if (list.size < 2) return
        index = ((index + d) % list.size + list.size) % list.size
        switchTo(false)
    }

    private fun toLive() {
        pb.play(list[index])
        Ui.toast(this, "Прямой эфир")
        showInfo(false)
    }

    /** Когда поставили на паузу прямой эфир (0 — не на паузе). */
    private var livePausedAt = 0L

    private fun togglePause(k: Int) {
        val pl = pb.player ?: return
        val play = k == KeyEvent.KEYCODE_MEDIA_PLAY || (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE && !pl.playWhenReady)
        if (play) {
            val at = livePausedAt
            livePausedAt = 0
            if (!pb.isArchive()) {
                // Пауза эфира: если у канала есть архив, продолжаем с места паузы, иначе — снова эфир.
                val c = list[index]
                val now = System.currentTimeMillis()
                if (at > 0 && MainActivity.same(pb.channel, c) && Catchup.can(c, st.current(), at, now)) {
                    val p = Epg.at(c.progs, at)
                    pb.playArchive(c, at, p?.start ?: at, if (p != null) min(p.stop, now) else 0L, p?.title ?: "")
                    Ui.toast(this, "С места паузы")
                    showInfo(false)
                    return
                }
                pl.seekToDefaultPosition()
            }
            pl.play()
        } else {
            if (!pb.isArchive() && pl.playWhenReady) livePausedAt = System.currentTimeMillis()
            pl.pause()
        }
        showInfo(false)
    }

    /** Ищет канал по ключу сначала в текущем списке, потом среди всех каналов; true — нашёлся и включён. */
    private fun openWhere(match: (Channel) -> Boolean): Boolean {
        for (i in list.indices) {
            if (match(list[i])) {
                index = i
                switchTo(true)
                return true
            }
        }
        val vis = st.visible()
        for (i in vis.indices) {
            if (match(vis[i])) {
                list = vis
                st.playList = vis
                st.playTitle = "Все каналы"
                index = i
                switchTo(true)
                return true
            }
        }
        return false
    }

    /** Кнопка 0 (или «Предыдущий канал» на пульте): назад к каналу, который смотрели перед этим. */
    private fun prevChannel() {
        val k = if (st.recent.size > 1) st.recent[1] else null
        if (k != null && openWhere { it.key() == k }) return
        Ui.toast(this, "Предыдущего канала пока нет")
    }

    /** Перемотка записи: нажатия копятся, поток перезапрашивается один раз, когда их отпустили. */
    private fun seekBy(dir: Int, repeat: Int) {
        if (!pb.isArchive()) return
        if (seekDelta == 0L) seekBase = pb.position()
        val step = if (repeat > 8) 300_000L else if (repeat > 3) 120_000L else 60_000L
        seekDelta += dir * step
        val now = System.currentTimeMillis()
        val target = min(seekBase + seekDelta, now)
        val mins = seekDelta / 60_000L
        seek.text = (if (mins > 0) "+" else "−") + abs(mins) + " мин   →   " + (if (target >= now - 30_000L) "эфир" else hm.format(Date(target)))
        seek.visibility = View.VISIBLE
        ui.removeCallbacks(seekRun)
        ui.postDelayed(seekRun, 800)
        showInfo(false)
    }

    private fun applyFit() {
        video.resizeMode = when (fit) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    // ---------- Звуковые дорожки ----------

    private class Track(val g: Tracks.Group, val i: Int, val label: String, val sel: Boolean)

    private fun audioTracks(): List<Track> {
        val r = ArrayList<Track>()
        val p = pb.player ?: return r
        for (g in p.currentTracks.groups) {
            if (g.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                var label = f.label ?: if (f.language != null && "und" != f.language) Locale(f.language!!).displayLanguage
                else "Дорожка " + (r.size + 1)
                if (f.channelCount >= 6) label += " (5.1)"
                r.add(Track(g, i, label, g.isTrackSelected(i)))
            }
        }
        return r
    }

    /** Дорожки видео (качество) или субтитров. */
    private fun tracks(type: Int): List<Track> {
        val r = ArrayList<Track>()
        val p = pb.player ?: return r
        for (g in p.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                var label: String
                if (type == C.TRACK_TYPE_VIDEO) {
                    label = if (f.height > 0) f.height.toString() + "p" else "Вариант " + (r.size + 1)
                    if (f.bitrate > 0) label += String.format(Locale.ROOT, "  ·  %.1f Мбит/с", f.bitrate / 1e6).replace('.', ',')
                } else {
                    label = f.label ?: if (f.language != null && "und" != f.language) Locale(f.language!!).displayLanguage
                    else "Субтитры " + (r.size + 1)
                }
                r.add(Track(g, i, label, g.isTrackSelected(i)))
            }
        }
        if (type == C.TRACK_TYPE_VIDEO) {
            r.sortWith { a, b -> b.g.getTrackFormat(b.i).height.compareTo(a.g.getTrackFormat(a.i).height) }
        }
        return r
    }

    /** Качество: «Авто» — по скорости интернета, иначе одно выбранное. */
    private fun pickQuality() {
        val ts = tracks(C.TRACK_TYPE_VIDEO)
        val p = pb.player
        if (ts.size < 2 || p == null) return
        val auto = !p.trackSelectionParameters.overrides.containsKey(ts[0].g.mediaTrackGroup)
        val names = Array(ts.size + 1) { "" }
        names[0] = "Авто"
        var sel = 0
        for (i in ts.indices) {
            names[i + 1] = ts[i].label
            if (!auto && ts[i].sel) sel = i + 1
        }
        AlertDialog.Builder(this).setTitle("Качество").setSingleChoiceItems(names, sel) { d, w ->
            d.dismiss()
            val pl = pb.player ?: return@setSingleChoiceItems
            val b = pl.trackSelectionParameters.buildUpon()
            if (w == 0) b.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            else b.setOverrideForType(TrackSelectionOverride(ts[w - 1].g.mediaTrackGroup, ts[w - 1].i))
            pl.trackSelectionParameters = b.build()
        }.show()
    }

    private fun pickSubs() {
        val ts = tracks(C.TRACK_TYPE_TEXT)
        val p = pb.player
        if (ts.isEmpty() || p == null) return
        val off = p.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
        val names = Array(ts.size + 1) { "" }
        names[0] = "Выключены"
        var sel = 0
        for (i in ts.indices) {
            names[i + 1] = ts[i].label
            if (!off && ts[i].sel) sel = i + 1
        }
        AlertDialog.Builder(this).setTitle("Субтитры").setSingleChoiceItems(names, sel) { d, w ->
            d.dismiss()
            val pl = pb.player ?: return@setSingleChoiceItems
            val b = pl.trackSelectionParameters.buildUpon()
            if (w == 0) b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            else b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(ts[w - 1].g.mediaTrackGroup, ts[w - 1].i))
            pl.trackSelectionParameters = b.build()
        }.show()
    }

    /** Сведения о потоке: разрешение, кодеки, скорость, частота кадров. */
    private fun showStats() {
        val p = pb.player ?: return
        val s = StringBuilder()
        val v = p.videoFormat
        val a = p.audioFormat
        if (v != null) {
            s.append("Видео: ").append(v.width).append("×").append(v.height)
            if (v.frameRate > 0) s.append(", ").append(Math.round(v.frameRate)).append(" кадр/с")
            v.sampleMimeType?.let { s.append("\nКодек видео: ").append(codec(it)) }
            if (v.bitrate > 0) s.append(String.format(Locale.ROOT, "\nПоток видео: %.1f Мбит/с", v.bitrate / 1e6).replace('.', ','))
        } else s.append("Видео: нет данных")
        if (a != null) {
            s.append("\nЗвук: ").append(a.sampleMimeType?.let { codec(it) } ?: "?")
            if (a.channelCount > 0) s.append(", ").append(if (a.channelCount >= 6) "5.1" else if (a.channelCount == 2) "стерео" else a.channelCount.toString() + " кан.")
            if (a.sampleRate > 0) s.append(", ").append(a.sampleRate / 1000).append(" кГц")
        }
        val buf = p.totalBufferedDuration
        s.append(String.format(Locale.ROOT, "\nЗапас в буфере: %.1f с", buf / 1000.0).replace('.', ','))
        val bw = pb.bandwidth()
        if (bw > 0) s.append(String.format(Locale.ROOT, "\nСкорость интернета: %.1f Мбит/с", bw / 1e6).replace('.', ','))
        val c = list[index]
        s.append("\n\nСсылка: ").append(c.url)
        AlertDialog.Builder(this).setTitle(c.name).setMessage(s.toString()).setPositiveButton("OK", null).show()
    }

    private fun pickAudio() {
        val ts = audioTracks()
        if (ts.size < 2 || pb.player == null) return
        val names = Array(ts.size) { ts[it].label }
        var sel = 0
        for (i in ts.indices) if (ts[i].sel) sel = i
        AlertDialog.Builder(this).setTitle("Звуковая дорожка").setSingleChoiceItems(names, sel) { d, w ->
            d.dismiss()
            val pl = pb.player ?: return@setSingleChoiceItems
            val t = ts[w]
            pl.trackSelectionParameters = pl.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(t.g.mediaTrackGroup, t.i)).build()
        }.show()
    }

    // ---------- Панели ----------

    private fun favButton(on: Boolean) {
        bFav.text = if (on) "В избранном" else "В избранное"
        bFav.setCompoundDrawablesRelativeWithIntrinsicBounds(if (on) R.drawable.ic_star else R.drawable.ic_star_outline, 0, 0, 0)
    }

    private fun showInfo(focus: Boolean) {
        val c = list[index]
        infoNum.text = c.num.toString()
        infoName.text = c.name
        infoGroup.text = c.groupTitle()
        favButton(st.isFav(c))
        fillTime()
        info.visibility = View.VISIBLE
        if (focus) {
            btns = true
            (if (pb.isArchive()) bLive else bList).requestFocus()
        } else if (!btns) parkFocus()
        ui.removeCallbacks(hideInfoRun)
        ui.postDelayed(hideInfoRun, if (btns || !tv) 8000L else 5000L)
    }

    private fun hideInfo() {
        info.visibility = View.GONE
        btns = false
        parkFocus()
    }

    /** Убирает фокус с кнопок и списков: дальше пульт обрабатывает handleKey. */
    private fun parkFocus() {
        if (tv || !root.isInTouchMode) root.requestFocus()
    }

    private fun fillTime() {
        val c = list[index]
        if (pb.isArchive() && MainActivity.same(pb.channel, c)) {
            val pos = pb.position()
            val end = if (pb.archEnd > pb.archStart) pb.archEnd else pb.archStart + 3600_000L
            infoClock.text = hm.format(Date(pos)) + (if (pb.player?.playWhenReady == false) "  · пауза" else "")
            infoNow.text = hm.format(Date(pb.archStart)) + "–" + hm.format(Date(end)) + "   " +
                    pb.archTitle.ifEmpty { "Запись эфира" }
            infoNow.visibility = View.VISIBLE
            infoProg.progress = ((pos - pb.archStart) * 1000 / max(1L, end - pb.archStart)).coerceIn(0L, 1000L).toInt()
            infoProg.visibility = View.VISIBLE
            infoNext.text = if (tv) "◀ ▶ — перемотка    «Назад» — в прямой эфир" else "Свайп влево/вправо — перемотка на 5 минут"
            infoNext.visibility = View.VISIBLE
            return
        }
        val t = System.currentTimeMillis()
        infoClock.text = hm.format(Date(t))
        val p = Epg.at(c.progs, t)
        val n = Epg.after(c.progs, t)
        if (p != null) {
            infoNow.text = hm.format(Date(p.start)) + "–" + hm.format(Date(p.stop)) + "   " + p.title
            infoNow.visibility = View.VISIBLE
            infoProg.progress = ((t - p.start) * 1000 / max(1L, p.stop - p.start)).toInt()
            infoProg.visibility = View.VISIBLE
        } else {
            infoNow.visibility = View.GONE
            infoProg.visibility = View.GONE
        }
        if (n != null) {
            infoNext.text = "Далее: " + hm.format(Date(n.start)) + "   " + n.title
            infoNext.visibility = View.VISIBLE
        } else infoNext.visibility = View.GONE
    }

    private fun showSide() {
        info.visibility = View.GONE
        btns = false
        hideEpg()
        ui.removeCallbacks(searchRun)
        sideSearch.setText("")
        sideSearch.visibility = View.GONE
        sa.current = list[index]
        setBrowse(list, st.playTitle)
        side.visibility = View.VISIBLE
        (sideList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, Ui.dp(this, 140))
        focusAt(sideList, index, 10)
        keepSide()
    }

    private fun hideSide() {
        val had = side.hasFocus()
        hideKeyboard()
        side.visibility = View.GONE
        ui.removeCallbacks(hideSideRun)
        ui.removeCallbacks(searchRun)
        if (had) parkFocus()
    }

    /** Список не прячется сам, пока им пользуются (выбор категории, набор поиска). */
    private fun keepSide() {
        ui.removeCallbacks(hideSideRun)
        ui.postDelayed(hideSideRun, if (sideSearch.visibility == View.VISIBLE) 60_000L else 20_000L)
    }

    private fun setBrowse(l: List<Channel>, title: String) {
        browse = l
        browseTitle = title
        sideTitle.text = title + "  ·  " + l.size + "  ▾"
        sa.set(l)
        sideList.scrollToPosition(0)
    }

    /** Выбор категории прямо в плеере — как на главном экране. */
    private fun pickGroup() {
        keepSide()
        val vis = st.visible()
        val gs = Groups.list(st, vis)
        val labels = Array(gs.size) { "" }
        var sel = -1
        for (i in gs.indices) {
            val g = gs[i]
            labels[i] = g.title + "  (" + g.count + ")"
            if (sel < 0 && Groups.title(g.id) == browseTitle) sel = i
        }
        AlertDialog.Builder(this).setTitle("Категории").setSingleChoiceItems(labels, sel) { d, w ->
            d.dismiss()
            val g = gs[w]
            ui.removeCallbacks(searchRun)
            sideSearch.setText("")
            sideSearch.visibility = View.GONE
            hideKeyboard()
            val l = Groups.channels(st, g.id, st.visible())
            setBrowse(l, Groups.title(g.id))
            val at = max(0, l.indexOf(list[index]))
            (sideList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(at, Ui.dp(this, 140))
            if (l.isNotEmpty()) focusAt(sideList, at, 10)
            keepSide()
        }.show()
    }

    private fun toggleSearch() {
        if (sideSearch.visibility == View.VISIBLE && sideSearch.length() == 0) {
            sideSearch.visibility = View.GONE
            hideKeyboard()
            keepSide()
            return
        }
        sideSearch.visibility = View.VISIBLE
        sideSearch.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager?)?.showSoftInput(sideSearch, InputMethodManager.SHOW_IMPLICIT)
        keepSide()
    }

    private val searchRun = Runnable {
        if (side.visibility != View.VISIBLE) return@Runnable
        val q = sideSearch.text.toString().trim()
        if (q.isEmpty()) {
            setBrowse(list, st.playTitle)
            (sideList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(index, Ui.dp(this, 140))
        } else setBrowse(Groups.search(q, st.visible()), "Поиск: $q")
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager?)?.hideSoftInputFromWindow(sideSearch.windowToken, 0)
    }

    private fun showEpg() {
        info.visibility = View.GONE
        btns = false
        hideSide()
        val c = list[index]
        epgTitle.text = "Телепрограмма  ·  " + c.name
        val rows = EpgAdapter.build(c, st.current(), System.currentTimeMillis())
        ea.playing = if (pb.isArchive()) pb.archStart else 0L
        ea.channel = c
        ea.set(rows)
        epgSide.visibility = View.VISIBLE
        if (rows.isEmpty()) {
            epgSideEmpty.text = if (st.epgUrl(st.current()).isEmpty())
                "Телепрограммы нет. Адрес телепрограммы (XMLTV) можно добавить в настройках плейлиста."
            else "Для этого канала нет телепрограммы и архива."
            epgSideEmpty.visibility = View.VISIBLE
            epgSideEmpty.isFocusable = true
            epgSideEmpty.requestFocus()
        } else {
            epgSideEmpty.visibility = View.GONE
            var i = EpgAdapter.nowIndex(rows)
            if (pb.isArchive()) for (k in rows.indices) if (rows[k].p.start == pb.archStart) i = k
            (epgSideList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, Ui.dp(this, 140))
            focusAt(epgSideList, i, 10)
        }
        ui.removeCallbacks(hideEpgRun)
        ui.postDelayed(hideEpgRun, 30_000)
    }

    private fun hideEpg() {
        val had = epgSide.hasFocus()
        epgSide.visibility = View.GONE
        ui.removeCallbacks(hideEpgRun)
        if (had) parkFocus()
    }

    /** Строка телепрограммы на полном экране: запись — с начала передачи, текущая — с начала или в эфир. */
    private fun fromEpg(r: EpgAdapter.Row) {
        val c = list[index]
        if (r.future) {
            remind(c, r)
            return
        }
        if (r.now && pb.isArchive()) {
            hideEpg()
            toLive()
            return
        }
        if (!r.archive) {
            Ui.toast(this, if (Catchup.days(c, st.current()) > 0) "Эта передача уже не хранится в архиве" else "У этого канала нет архива")
            return
        }
        hideEpg()
        pb.playArchive(c, r.p.start, r.p.start, min(r.p.stop, System.currentTimeMillis()), if (r.slot) "" else r.p.title)
        if (r.now) Ui.toast(this, "Передача с начала")
        showInfo(false)
    }

    /** Будущая передача: OK ставит или снимает напоминание. */
    private fun remind(c: Channel, r: EpgAdapter.Row) {
        val on = Alerts.toggle(this, c, r.p)
        Ui.toast(this, if (on) "Напомню в " + hm.format(Date(r.p.start - 60_000L)) + ", за минуту до начала" else "Напоминание снято")
        val i = ea.rows().indexOf(r)
        if (i >= 0) ea.notifyItemChanged(i)
    }

    /** Напоминание: «Смотреть» — включить канал прямо здесь. */
    fun openKey(key: String) {
        if (!openWhere { it.key() == key }) Ui.toast(this, "Этого канала больше нет в плейлисте")
    }

    private fun focusAt(rv: RecyclerView, pos: Int, tries: Int) {
        val vh = rv.findViewHolderForAdapterPosition(pos)
        if (vh != null) vh.itemView.requestFocus()
        else if (tries > 0) rv.postDelayed({ focusAt(rv, pos, tries - 1) }, 40)
    }

    // ---------- Номер канала цифрами ----------

    private fun digit(d: Int) {
        if (d == 0 && digitBuf.isEmpty()) {
            prevChannel() // номера каналов не начинаются с 0
            return
        }
        if (digitBuf.length >= 5) digitBuf = ""
        digitBuf += d
        digits.text = digitBuf
        digits.visibility = View.VISIBLE
        ui.removeCallbacks(digitRun)
        ui.postDelayed(digitRun, 1500)
    }

    private fun jumpToNumber() {
        digits.visibility = View.GONE
        val n = digitBuf.toIntOrNull() ?: -1
        digitBuf = ""
        if (n <= 0) return
        if (!openWhere { it.num == n }) Ui.toast(this, "Канала с номером $n нет")
    }

    // ---------- Пульт ----------

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (e.action == KeyEvent.ACTION_UP) {
            if (k == eatUp) {
                eatUp = -1
                return true
            }
            return super.dispatchKeyEvent(e)
        }
        if (e.action == KeyEvent.ACTION_DOWN && list.isNotEmpty() && handleKey(k, e.repeatCount)) {
            eatUp = k
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    private fun handleKey(k: Int, repeat: Int): Boolean {
        val sideOn = side.visibility == View.VISIBLE
        val infoOn = info.visibility == View.VISIBLE
        val epgOn = epgSide.visibility == View.VISIBLE
        val arch = pb.isArchive()
        if (!infoOn) btns = false
        if (infoOn) {
            ui.removeCallbacks(hideInfoRun)
            ui.postDelayed(hideInfoRun, if (btns) 8000L else 5000L)
        }
        if (sideOn) {
            ui.removeCallbacks(hideSideRun)
            ui.postDelayed(hideSideRun, 20_000)
        }
        if (epgOn) {
            ui.removeCallbacks(hideEpgRun)
            ui.postDelayed(hideEpgRun, 30_000)
        }
        when (k) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (sideOn) {
                    hideSide()
                    return true
                }
                if (epgOn) {
                    hideEpg()
                    return true
                }
                if (infoOn) {
                    hideInfo()
                    return true
                }
                if (arch) {
                    toLive()
                    return true
                }
                return false
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                zap(1)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                zap(-1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                togglePause(k)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(1, repeat)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(-1, repeat)
                return true
            }
            KeyEvent.KEYCODE_LAST_CHANNEL -> {
                prevChannel()
                return true
            }
            KeyEvent.KEYCODE_GUIDE -> {
                if (epgOn) hideEpg() else showEpg()
                return true
            }
        }
        if (epgOn && epgSideEmpty.hasFocus() && (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            hideEpg() // пустая телепрограмма: ◀ ▶ закрывают её, как и список
            return true
        }
        if (sideOn || epgOn) return false // списками управляет RecyclerView
        // Цифры набирают номер канала всегда, даже когда открыта панель с кнопками.
        if (k >= KeyEvent.KEYCODE_0 && k <= KeyEvent.KEYCODE_9) {
            digit(k - KeyEvent.KEYCODE_0)
            return true
        }
        if (k >= KeyEvent.KEYCODE_NUMPAD_0 && k <= KeyEvent.KEYCODE_NUMPAD_9) {
            digit(k - KeyEvent.KEYCODE_NUMPAD_0)
            return true
        }
        if (infoOn && btns && info.hasFocus()) {
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                // Кнопки могут стоять в две строки: ▲▼ сначала переходят между ними, а с крайней строки — листают каналы.
                val f = currentFocus
                val n = f?.focusSearch(if (k == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN)
                if (n != null && n !== f && n.parent === f.parent) {
                    n.requestFocus()
                    return true
                }
                zap(if (k == KeyEvent.KEYCODE_DPAD_UP) 1 else -1)
                return true
            }
            return false // ◀ ▶ OK — по кнопкам панели
        }
        when (k) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                zap(1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                zap(-1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (digitBuf.isNotEmpty()) {
                    ui.removeCallbacks(digitRun)
                    jumpToNumber()
                } else if (pb.fatal) pb.retry()
                else if (arch) showInfo(true)
                else showSide()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (arch) seekBy(-1, repeat) else showSide()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (arch) seekBy(1, repeat) else showInfo(true)
                return true
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU -> {
                showInfo(true)
                return true
            }
            else -> return false
        }
    }

    // ---------- Жесты на телефоне ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val swipe = Ui.dp(this, 60)
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (side.visibility == View.VISIBLE) hideSide()
                else if (epgSide.visibility == View.VISIBLE) hideEpg()
                else if (pb.fatal) pb.retry()
                else if (info.visibility == View.VISIBLE) hideInfo()
                else showInfo(false)
                return true
            }

            override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
                if (a == null) return false
                val dx = b.x - a.x
                val dy = b.y - a.y
                if (abs(dy) > abs(dx) && abs(dy) > swipe) {
                    zap(if (dy < 0) 1 else -1)
                    return true
                }
                if (abs(dx) > swipe) {
                    if (pb.isArchive()) {
                        seekBy(if (dx > 0) 1 else -1, 9) // свайп — 5 минут
                    } else if (dx > 0) {
                        if (epgSide.visibility == View.VISIBLE) hideEpg() else showSide()
                    } else {
                        if (side.visibility == View.VISIBLE) hideSide() else showEpg()
                    }
                    return true
                }
                return false
            }
        })
        root.setOnTouchListener { _, e -> gd.onTouchEvent(e) }
    }

    companion object {
        private val FIT_NAMES = arrayOf("Вписать", "Заполнить", "Растянуть")
        private val ROT_NAMES = arrayOf("авто", "альбомный", "книжный")

        private fun codec(mime: String): String = when (mime) {
            "video/avc" -> "H.264"
            "video/hevc" -> "H.265 (HEVC)"
            "video/mp2t", "video/mpeg2" -> "MPEG-2"
            "video/av01" -> "AV1"
            "video/x-vnd.on2.vp9" -> "VP9"
            "audio/mp4a-latm" -> "AAC"
            "audio/ac3" -> "Dolby Digital (AC-3)"
            "audio/eac3" -> "Dolby Digital Plus"
            "audio/mpeg", "audio/mpeg-L2" -> "MPEG Audio"
            else -> mime
        }

        private fun setWidth(v: View, w: Int) {
            val lp = v.layoutParams
            if (lp.width != w) {
                lp.width = w
                v.layoutParams = lp
            }
        }

        private fun indexIn(l: List<Channel>, c: Channel?): Int {
            for (i in l.indices) if (MainActivity.same(l[i], c)) return i
            return -1
        }
    }
}
