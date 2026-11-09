package ru.argeda.tv

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Главный экран: группы, каналы с текущей передачей, справа — окно предпросмотра и телепрограмма
 * выбранного канала с записями. На узком экране телефона предпросмотр сверху, список снизу.
 */
@OptIn(markerClass = [UnstableApi::class])
internal class MainActivity : Activity(), Store.Listener, Playback.Listener {
    private class Group(val id: String, val title: String, val count: Int) {
        /** Заголовок блока («МОЁ», «ПЛЕЙЛИСТ»): не выбирается. */
        fun header(): Boolean = id.startsWith(G_HEAD)
    }

    private lateinit var st: Store
    private lateinit var pb: Playback
    private var groupsView: RecyclerView? = null
    private lateinit var channelsView: RecyclerView
    private lateinit var epgList: RecyclerView
    private lateinit var preview: PlayerView
    private lateinit var pv: View
    private lateinit var pBuf: View
    private lateinit var pArch: View
    private var epgBox: View? = null
    private var tChannels: View? = null
    private var tEpg: View? = null
    private lateinit var pMsg: TextView
    private lateinit var pTitle: TextView
    private lateinit var pNow: TextView
    private var pDesc: TextView? = null
    private lateinit var epgEmpty: TextView
    private lateinit var pProg: ProgressBar
    private lateinit var ea: EpgAdapter

    /** Выбранный канал: его показывают окно предпросмотра и телепрограмма. */
    private var sel: Channel? = null
    private var previewRun: Runnable? = null

    /** Была ли телепрограмма, когда панель канала заполнялась (чтобы обновить её, когда программа загрузится). */
    private var infoHadEpg = false
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val groups = ArrayList<Group>()
    private val ga = GroupAdapter()
    private lateinit var ca: ChannelAdapter
    private lateinit var source: TextView
    private lateinit var loadingText: TextView
    private lateinit var emptyText: TextView
    private lateinit var emptyBtn: TextView
    private lateinit var search: EditText
    private lateinit var body: View
    private lateinit var empty: View
    private lateinit var loading: View
    private var narrow = false
    private var focusLast = true
    private var curGroup = G_ALL
    private var listKey = ""
    private var seenSeq = 0
    private val ui = Handler(Looper.getMainLooper())

    /** Заставка при запуске; пока она видна, звук предпросмотра выключен, чтобы не мешать голосу. */
    private var splash: Splash? = null
    private val filterRun = Runnable { applyFilter() }
    private var groupRun: Runnable? = null
    private val tick: Runnable = object : Runnable {
        override fun run() {
            if (st.hasEpg()) ca.notifyItemRangeChanged(0, ca.itemCount, "t")
            sel?.let { updateNow(it) }
            ui.postDelayed(this, 60_000)
        }
    }
    private var backAt = 0L

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Themes.apply(this, base, false)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        Ui.tvSafeArea(this)
        st = Store.get(this)
        pb = Playback.get(this)
        Logos.init(this)
        groupsView = findViewById(R.id.groups)
        channelsView = findViewById(R.id.channels)
        source = findViewById(R.id.source)
        search = findViewById(R.id.search)
        body = findViewById(R.id.body)
        empty = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)
        loadingText = findViewById(R.id.loadingText)
        emptyText = findViewById(R.id.emptyText)
        emptyBtn = findViewById(R.id.emptyBtn)

        pv = findViewById(R.id.pv)
        preview = findViewById(R.id.preview)
        pBuf = findViewById(R.id.pBuf)
        pArch = findViewById(R.id.pArch)
        pMsg = findViewById(R.id.pMsg)
        pTitle = findViewById(R.id.pTitle)
        pNow = findViewById(R.id.pNow)
        pProg = findViewById(R.id.pProg)
        pDesc = findViewById(R.id.pDesc)
        epgList = findViewById(R.id.epgList)
        epgEmpty = findViewById(R.id.epgEmpty)
        epgBox = findViewById(R.id.epgBox)
        tChannels = findViewById(R.id.tChannels)
        tEpg = findViewById(R.id.tEpg)

        // Разметка выбирается по ширине экрана (layout-w600dp): на узкой нет колонки групп.
        narrow = groupsView == null
        if (b != null) curGroup = b.getString("g", G_ALL)

        groupsView?.let {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = ga
            it.itemAnimator = null
        }
        if (tChannels != null) {
            tChannels!!.setOnClickListener { setTab(false) }
            tEpg!!.setOnClickListener { setTab(true) }
            setTab(false)
        }
        pv.setOnClickListener { sel?.let { openFull(it) } }
        pv.setOnKeyListener { _, code, e ->
            if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT) {
                focusChannel(indexOfSel())
                true
            } else false
        }

        ea = EpgAdapter(object : EpgAdapter.Listener {
            override fun click(pos: Int) {
                val s = sel
                if (s != null && pos < ea.rows().size) openFromEpg(s, ea.rows()[pos])
            }

            override fun focus(pos: Int) {
                if (pDesc == null || pos >= ea.rows().size) return
                showDesc(ea.rows()[pos].p)
            }

            override fun key(pos: Int, keyCode: Int): Boolean {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !narrow) {
                    focusChannel(indexOfSel())
                    return true
                }
                return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
            }
        })
        epgList.layoutManager = LinearLayoutManager(this)
        epgList.adapter = ea
        epgList.itemAnimator = null

        ca = ChannelAdapter(st, object : ChannelAdapter.Listener {
            override fun click(pos: Int) {
                val l = ca.items()
                if (pos < 0 || pos >= l.size) return
                val c = l[pos]
                if (!channelsView.isInTouchMode || !st.preview() || (c === sel && same(pb.channel, c))) openFull(c)
                else select(c, PREVIEW_NOW) // касание: сначала в окне, второе касание — на весь экран
            }

            override fun longClick(pos: Int) {
                toggleFav(pos)
            }

            override fun focus(pos: Int) {
                val l = ca.items()
                if (pos >= 0 && pos < l.size && l[pos] !== sel) select(l[pos], PREVIEW_LATER)
            }

            override fun key(pos: Int, keyCode: Int): Boolean {
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !narrow) {
                    focusGroup()
                    return true
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !narrow) {
                    if (ea.itemCount > 0) focusEpg() else pv.requestFocus()
                    return true
                }
                return false
            }
        })
        channelsView.layoutManager = LinearLayoutManager(this)
        channelsView.adapter = ca
        channelsView.itemAnimator = null
        channelsView.setHasFixedSize(true)

        source.setOnClickListener { if (narrow) pickGroup() else pickSource() }
        findViewById<View>(R.id.menu).setOnClickListener { menu() }
        emptyBtn.setOnClickListener {
            val s = st.current()
            if (s == null) addSource() else editSource(s)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}

            override fun afterTextChanged(e: Editable?) {
                ui.removeCallbacks(filterRun)
                ui.postDelayed(filterRun, 250)
            }
        })
        search.setOnEditorActionListener { _, _, _ ->
            hideKeyboard()
            ui.removeCallbacks(filterRun)
            applyFilter()
            focusChannel(0)
            true
        }

        if (b == null && st.splash()) splash = Splash.show(this) { splashDone() }
    }

    /** Звук канала плавно нарастает, пока доигрывает хвост озвучки заставки. */
    private fun splashDone() {
        val va = ValueAnimator.ofFloat(0f, 1f).setDuration(1800)
        va.startDelay = 300
        va.addUpdateListener { an -> pb.player?.volume = an.animatedValue as Float }
        va.start()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Пока идёт заставка, любая кнопка пульта только пропускает её.
        val s = splash
        if (s != null && s.showing()) {
            if (e.action == KeyEvent.ACTION_UP) s.skip()
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onStart() {
        super.onStart()
        Alerts.attach(this)
        pb.acquire()
        preview.player = pb.player
        if (splash?.showing() == true) pb.player?.volume = 0f
        pb.addListener(this)
        if (!st.preview() && pb.channel != null) pb.stop()
        playbackChanged()
    }

    override fun onStop() {
        super.onStop()
        Alerts.detach(this)
        splash?.cancel()
        splash = null
        previewRun?.let { ui.removeCallbacks(it) }
        ui.removeCallbacks(updRun)
        pb.removeListener(this)
        preview.player = null
        pb.release()
    }

    override fun onResume() {
        super.onResume()
        st.listener = this
        val s = st.current()
        if (s != null && s.id != st.loadedId && s.id != st.loadingId) st.load(false)
        render()
        ui.postDelayed(tick, 60_000)
        Updater.resume(this)
        ui.postDelayed(updRun, 4000) // проверка обновлений не мешает запуску канала
    }

    /** Окно обновления не показываем поверх заставки: ждём, пока она закончится. */
    private val updRun: Runnable = object : Runnable {
        override fun run() {
            if (splash?.showing() == true) ui.postDelayed(this, 1500)
            else Updater.check(this@MainActivity, false)
        }
    }

    override fun onPause() {
        super.onPause()
        st.listener = null
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            st.setShowAdult(false)
            pb.stop()
        }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("g", curGroup)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        Backup.result(this, req, res, data)
    }

    override fun changed() {
        render()
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_MENU) {
            menu()
            return true
        }
        if (code == KeyEvent.KEYCODE_GUIDE) {
            openGuide()
            return true
        }
        if (code == KeyEvent.KEYCODE_SEARCH) {
            search.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager?)?.showSoftInput(search, 0)
            return true
        }
        return super.onKeyDown(code, e)
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (search.length() > 0) {
            search.setText("")
            return
        }
        val list = body.visibility == View.VISIBLE && ca.itemCount > 0
        // Пульт: «Назад» из групп, телепрограммы, окна или верхней строки сначала возвращает в список каналов.
        if (list && !narrow && !channelsView.hasFocus() && !channelsView.isInTouchMode) {
            focusChannel(indexOfSel())
            return
        }
        // Телефон: с вкладки «Телепрограмма» — на «Каналы».
        if (list && tEpg?.isSelected == true) {
            setTab(false)
            return
        }
        // Выход — только по второму нажатию: случайное «Назад» на пульте не закрывает приложение.
        val now = System.currentTimeMillis()
        if (now - backAt > 2500) {
            backAt = now
            Ui.toast(this, "Нажмите «Назад» ещё раз, чтобы выйти")
            return
        }
        super.onBackPressed()
    }

    // ---------- Отрисовка ----------

    private fun render() {
        val s = st.current()
        val vis = st.visible()
        if (st.statusSeq != seenSeq) {
            seenSeq = st.statusSeq
            if (st.status.isNotEmpty() && vis.isNotEmpty()) Ui.toast(this, "Не удалось обновить плейлист: " + st.status + ". Показаны сохранённые каналы")
        }
        if (s == null) {
            show(empty)
            emptyText.text = "Добавьте плейлист вашего IPTV-провайдера"
            emptyBtn.text = "Добавить плейлист"
            emptyBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_plus, 0, 0, 0)
            source.text = "Плейлисты  ▾"
            return
        }
        if (!narrow) source.text = s.title() + "  ▾"
        if (vis.isEmpty()) {
            if (st.busy) {
                show(loading)
                loadingText.text = "Загружаю «" + s.title() + "»…"
            } else {
                show(empty)
                emptyText.text = if (st.status.isEmpty()) "В плейлисте нет каналов" else "Не удалось загрузить плейлист\n" + st.status
                emptyBtn.text = "Изменить плейлист"
                emptyBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            }
            if (narrow) source.text = "Плейлисты  ▾"
            return
        }
        show(body)
        buildGroups()
        applyFilter()
        val cur = sel
        if (cur != null) {
            val fresh = findSame(vis, cur)
            if (fresh == null) {
                sel = null
                ca.current = null
            } else if (fresh !== cur || infoHadEpg != st.hasEpg()) {
                sel = fresh
                ca.current = fresh
                showInfo(fresh)
            }
        }
        if (sel == null) showInfo(null)
    }

    private fun show(v: View) {
        body.visibility = if (v === body) View.VISIBLE else View.GONE
        empty.visibility = if (v === empty) View.VISIBLE else View.GONE
        loading.visibility = if (v === loading) View.VISIBLE else View.GONE
        if (v === empty && !narrow) emptyBtn.requestFocus()
    }

    @Suppress("NotifyDataSetChanged")
    private fun buildGroups() {
        val vis = st.visible()
        val m = LinkedHashMap<String, Int>()
        var favN = 0
        for (c in vis) {
            m[c.group] = (m[c.group] ?: 0) + 1
            if (st.isFav(c)) favN++
        }
        groups.clear()
        // Блок «МОЁ»: избранное, свои папки (пустую тоже видно, чтобы было куда складывать)
        // и избранные категории в порядке добавления.
        val mine = ArrayList<Group>()
        if (favN > 0) mine.add(Group(G_FAV, "★  Избранное", favN))
        for (f in st.folders) mine.add(Group(G_FOLDER + f.name, "☆  " + f.name, ordered(f.keys, vis).size))
        for (g in st.favGroups) {
            val n = m[g] ?: continue
            mine.add(Group(g, "▸  " + Channel.groupTitle(g), n))
        }
        if (mine.isNotEmpty()) {
            groups.add(Group(G_HEAD + "my", "МОЁ", 0))
            groups.addAll(mine)
            groups.add(Group(G_HEAD + "pl", "ПЛЕЙЛИСТ", 0))
        }
        val recN = ordered(st.recent, vis).size
        if (recN > 0) groups.add(Group(G_REC, "Недавние", recN))
        groups.add(Group(G_ALL, "Все каналы", vis.size))
        for ((k, v) in m) if (!st.isFavGroup(k)) groups.add(Group(k, Channel.groupTitle(k), v))
        if (indexOfGroup(curGroup) < 0) curGroup = G_ALL
        ga.notifyDataSetChanged()
        if (narrow) source.text = titleOf(curGroup) + "  ▾"
    }

    private fun applyFilter() {
        val q = norm(search.text.toString())
        val vis = st.visible()
        val res: List<Channel> = if (q.isNotEmpty()) {
            vis.filter { norm(it.name).contains(q) || it.num.toString() == q }
        } else {
            channelsFor(curGroup, vis)
        }
        val key = if (q.isEmpty()) "g:$curGroup" else "q:$q"
        val same = key == listKey
        listKey = key
        ca.set(res)
        if (!same) channelsView.scrollToPosition(0)
        if (focusLast && res.isNotEmpty()) {
            focusLast = false
            var i = indexOfKey(res, st.lastKey)
            val playing = pb.channel?.let { findSame(res, it) }
            if (playing != null) i = res.indexOf(playing)
            if (i >= 0 || !narrow) {
                i = maxOf(0, i)
                // На приставке последний канал включается в окне сразу при запуске, на телефоне — только по касанию.
                select(res[i], if (channelsView.isInTouchMode) PREVIEW_NONE else PREVIEW_NOW)
                focusChannel(i)
            }
        }
    }

    private fun channelsFor(g: String, vis: List<Channel>): List<Channel> = when {
        g == G_ALL -> vis
        g == G_REC -> ordered(st.recent, vis)
        g.startsWith(G_FOLDER) -> st.folder(g.substring(G_FOLDER.length))?.let { ordered(it.keys, vis) } ?: ArrayList()
        g == G_FAV -> ordered(st.favOrder, vis)
        else -> vis.filter { it.group == g }
    }

    private fun indexOfGroup(id: String): Int = groups.indexOfFirst { it.id == id }

    private fun titleOf(id: String): String = when {
        id == G_ALL -> "Все каналы"
        id == G_FAV -> "Избранное"
        id == G_REC -> "Недавние"
        id.startsWith(G_FOLDER) -> id.substring(G_FOLDER.length)
        else -> Channel.groupTitle(id)
    }

    // ---------- Фокус для пульта ----------

    private fun focusChannel(pos: Int) {
        if (pos < 0 || pos >= ca.itemCount) return
        (channelsView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, channelsView.height / 3)
        focusAt(channelsView, pos, 10)
    }

    private fun focusGroup() {
        val gv = groupsView ?: return
        val i = maxOf(0, indexOfGroup(curGroup))
        (gv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, gv.height / 3)
        focusAt(gv, i, 10)
    }

    private fun focusAt(rv: RecyclerView, pos: Int, tries: Int) {
        val vh = rv.findViewHolderForAdapterPosition(pos)
        if (vh != null) vh.itemView.requestFocus()
        else if (tries > 0) rv.postDelayed({ focusAt(rv, pos, tries - 1) }, 40)
    }

    private fun firstVisibleChannel(): Int =
        maxOf(0, (channelsView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition())

    private fun selectGroup(id: String, moveFocus: Boolean) {
        val changed = id != curGroup || search.length() > 0
        curGroup = id
        if (search.length() > 0) search.setText("")
        ui.removeCallbacks(filterRun)
        ga.notifyItemRangeChanged(0, groups.size, "sel")
        if (changed) applyFilter()
        if (narrow) source.text = titleOf(id) + "  ▾"
        if (moveFocus && !narrow) focusChannel(0)
    }

    // ---------- Предпросмотр и телепрограмма ----------

    private fun select(c: Channel, mode: Int) {
        val old = sel
        sel = c
        ca.current = c
        val l = ca.items()
        if (old != null && l.contains(old)) ca.notifyItemChanged(l.indexOf(old), "sel")
        if (l.contains(c)) ca.notifyItemChanged(l.indexOf(c), "sel")
        showInfo(c)
        previewRun?.let { ui.removeCallbacks(it) }
        if (mode == PREVIEW_NONE || !st.preview() || (same(pb.channel, c) && !pb.isArchive())) return
        val r = Runnable { if (sel === c) pb.play(c) }
        previewRun = r
        if (mode == PREVIEW_NOW) r.run()
        else ui.postDelayed(r, 600) // пока листают список, поток не дёргаем
    }

    private fun showInfo(c: Channel?) {
        if (c == null) {
            pTitle.text = if (st.visible().isEmpty()) "" else "Выберите канал"
            pNow.text = ""
            pProg.visibility = View.GONE
            pDesc?.visibility = View.GONE
            ea.set(ArrayList())
            epgEmpty.visibility = View.GONE
            return
        }
        infoHadEpg = st.hasEpg()
        pTitle.text = "${c.num}   ${c.name}"
        updateNow(c)
        val now = System.currentTimeMillis()
        val rows = EpgAdapter.build(c, st.current(), now)
        ea.playing = playingStart(c)
        ea.channel = c
        ea.set(rows)
        if (rows.isEmpty()) {
            val s = st.current()
            epgEmpty.text = when {
                st.epgUrl(s).isEmpty() ->
                    "Телепрограммы нет. Адрес телепрограммы (XMLTV) можно добавить в настройках плейлиста — тогда здесь будут передачи, а у каналов с архивом — записи."
                st.epgLoading() -> "Загружаю телепрограмму…"
                else -> "Для этого канала телепрограммы нет."
            }
            epgEmpty.visibility = View.VISIBLE
        } else {
            epgEmpty.visibility = View.GONE
            val i = EpgAdapter.nowIndex(rows)
            (epgList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, Ui.dp(this, 60))
        }
    }

    private fun updateNow(c: Channel) {
        val t = System.currentTimeMillis()
        val p = Epg.at(c.progs, t)
        val days = Catchup.days(c, st.current())
        if (p != null) {
            pNow.text = hm.format(Date(p.start)) + "–" + hm.format(Date(p.stop)) + "   " + p.title
            pProg.progress = ((t - p.start) * 1000 / maxOf(1L, p.stop - p.start)).toInt()
            pProg.visibility = View.VISIBLE
        } else {
            pNow.text = c.groupTitle() + if (days > 0) "   ·   архив " + days + " " + dayWord(days) else ""
            pProg.visibility = View.GONE
        }
        showDesc(p)
    }

    private fun showDesc(p: Epg.Prog?) {
        val d = pDesc ?: return
        val text = p?.desc
        if (!text.isNullOrEmpty()) {
            d.text = text
            d.visibility = View.VISIBLE
        } else d.visibility = View.GONE
    }

    private fun playingStart(c: Channel): Long {
        if (!same(pb.channel, c)) return -1
        return if (pb.isArchive()) pb.archStart else 0
    }

    private fun focusEpg() {
        val i = EpgAdapter.nowIndex(ea.rows())
        (epgList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, Ui.dp(this, 60))
        focusAt(epgList, i, 10)
    }

    private fun indexOfSel(): Int = sel?.let { maxOf(0, ca.items().indexOf(it)) } ?: 0

    private fun setTab(epg: Boolean) {
        val tc = tChannels ?: return
        tc.isSelected = !epg
        tEpg?.isSelected = epg
        channelsView.visibility = if (epg) View.GONE else View.VISIBLE
        epgBox?.visibility = if (epg) View.VISIBLE else View.GONE
    }

    /** Строка телепрограммы: запись — смотреть с начала передачи, текущая — прямой эфир. */
    private fun openFromEpg(c: Channel, r: EpgAdapter.Row) {
        val now = System.currentTimeMillis()
        if (r.future) {
            val on = Alerts.toggle(this, c, r.p)
            Ui.toast(this, if (on) "Напомню в " + hm.format(Date(r.p.start - 60_000L)) + ", за минуту до начала" else "Напоминание снято")
            val i = ea.rows().indexOf(r)
            if (i >= 0) ea.notifyItemChanged(i)
            return
        }
        if (r.now) {
            // Текущая передача — прямой эфир; «с начала» — в телепрограмме на полном экране.
            if (!same(pb.channel, c) || pb.isArchive()) pb.play(c)
            openFull(c)
            return
        }
        if (!r.archive) {
            Ui.toast(this, if (Catchup.days(c, st.current()) > 0) "Эта передача уже не хранится в архиве" else "У этого канала нет архива")
            return
        }
        pb.playArchive(c, r.p.start, r.p.start, minOf(r.p.stop, now), if (r.slot) "" else r.p.title)
        openFull(c)
    }

    /** На весь экран: тот же поток продолжает играть без новой загрузки. */
    private fun openFull(c: Channel) {
        var l = ca.items()
        var i = l.indexOf(c)
        if (i < 0) {
            l = st.visible()
            i = maxOf(0, l.indexOf(c))
        }
        st.playList = ArrayList(l)
        st.playIndex = i
        st.playTitle = if (search.length() > 0) "Поиск: " + search.text.toString().trim() else titleOf(curGroup)
        st.played(c)
        if (!same(pb.channel, c)) pb.play(c)
        previewRun?.let { ui.removeCallbacks(it) }
        focusLast = true
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", c.key()))
    }

    override fun playbackChanged() {
        val on = pb.channel != null
        Ui.keepScreenOn(this, pb.active())
        pBuf.visibility = if (on && pb.buffering && pb.error.isEmpty()) View.VISIBLE else View.GONE
        pArch.visibility = if (on && pb.isArchive()) View.VISIBLE else View.GONE
        val msg = when {
            pb.error.isNotEmpty() && on -> pb.error
            on -> ""
            !st.preview() -> "Предпросмотр выключен.\nOK — смотреть на весь экран"
            channelsView.isInTouchMode -> "Коснитесь канала, чтобы посмотреть его здесь"
            else -> ""
        }
        pMsg.text = msg
        pMsg.visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
        val s = sel
        if (s != null && ea.itemCount > 0) {
            val ps = playingStart(s)
            if (ps != ea.playing) {
                ea.playing = ps
                ea.notifyItemRangeChanged(0, ea.itemCount, "p")
            }
        }
    }

    /**
     * Долгое нажатие на канал: окно «В избранное» — общее избранное, свои папки и «Новая папка».
     * Пункт с галочкой убирает канал оттуда, без галочки — добавляет.
     */
    private fun toggleFav(pos: Int) {
        val l = ca.items()
        if (pos < 0 || pos >= l.size) return
        val c = l[pos]
        val labels = ArrayList<String>()
        labels.add((if (st.isFav(c)) "✓  " else "      ") + "★  Избранное")
        for (f in st.folders) labels.add((if (st.inFolder(f, c)) "✓  " else "      ") + "☆  " + f.name)
        labels.add("      ＋  Новая папка…")
        // В избранном и в своей папке канал можно передвинуть: порядок сохраняется.
        val inF = if (curGroup.startsWith(G_FOLDER) && search.length() == 0) st.folder(curGroup.substring(G_FOLDER.length)) else null
        val movable = search.length() == 0 && (curGroup == G_FAV || inF != null)
        val moveAt = labels.size
        if (movable) {
            labels.add("      ▲  Передвинуть выше")
            labels.add("      ▼  Передвинуть ниже")
        }
        labels.add("      ⊘  Скрыть канал")
        val hideAt = labels.size - 1
        AlertDialog.Builder(this).setTitle(c.name).setItems(labels.toTypedArray()) { _, w ->
            if (movable && (w == moveAt || w == moveAt + 1)) {
                if (st.move(inF, c, if (w == moveAt) -1 else 1)) {
                    applyFilter()
                    val i = ca.items().indexOf(c)
                    if (i >= 0) {
                        select(c, PREVIEW_NONE)
                        focusChannel(i)
                    }
                }
                return@setItems
            }
            if (w == hideAt) {
                st.setHidden(c, true)
                Ui.toast(this, "«" + c.name + "» скрыт. Вернуть: Настройки → «Скрытые каналы и категории»")
                if (same(pb.channel, c)) pb.stop()
                render()
                return@setItems
            }
            if (w == 0) {
                val on = st.toggleFav(c)
                Ui.toast(this, if (on) "«" + c.name + "» добавлен в избранное" else "«" + c.name + "» убран из избранного")
                favChanged(c)
            } else if (w <= st.folders.size) {
                val f = st.folders[w - 1]
                val on = st.toggleInFolder(f, c)
                Ui.toast(this, if (on) "«" + c.name + "» добавлен в папку «" + f.name + "»" else "«" + c.name + "» убран из папки «" + f.name + "»")
                favChanged(c)
            } else {
                askText("Новая папка", "") { name ->
                    val f = st.addFolder(name)
                    if (f == null) {
                        Ui.toast(this, if (name.trim().isEmpty()) "Введите название папки" else "Папка «" + name.trim() + "» уже есть")
                        return@askText
                    }
                    st.toggleInFolder(f, c)
                    Ui.toast(this, "Папка «" + f.name + "» создана, «" + c.name + "» добавлен в неё")
                    favChanged(c)
                }
            }
        }.show()
    }

    /** После изменения избранного или папок: пересчитать группы и обновить список, если он сейчас о них. */
    private fun favChanged(c: Channel) {
        val favList = search.length() == 0 && (curGroup == G_FAV || curGroup.startsWith(G_FOLDER))
        buildGroups()
        if (favList) applyFilter()
        else {
            val i = ca.items().indexOf(c)
            if (i >= 0) ca.notifyItemChanged(i, "t")
        }
    }

    /** Долгое нажатие на свою папку: переименовать или удалить. */
    private fun manageFolder(id: String) {
        val f = st.folder(id.substring(G_FOLDER.length)) ?: return
        AlertDialog.Builder(this).setTitle("Папка «" + f.name + "»").setItems(arrayOf("Переименовать", "Удалить папку")) { _, w ->
            if (w == 0) {
                askText("Новое название", f.name) { name ->
                    if (!st.renameFolder(f, name)) {
                        Ui.toast(this, if (name.trim().isEmpty()) "Введите название папки" else "Папка «" + name.trim() + "» уже есть")
                        return@askText
                    }
                    val cur = curGroup == id
                    if (cur) curGroup = G_FOLDER + f.name
                    buildGroups()
                    if (cur) applyFilter()
                    focusGroupId(G_FOLDER + f.name)
                }
            } else {
                AlertDialog.Builder(this).setTitle("Удалить папку «" + f.name + "»?")
                    .setMessage("Каналы останутся в плейлисте, удалится только папка.")
                    .setPositiveButton("Удалить") { _, _ ->
                        st.removeFolder(f)
                        Ui.toast(this, "Папка «" + f.name + "» удалена")
                        buildGroups()
                        applyFilter()
                        focusGroupId(curGroup)
                    }
                    .setNegativeButton("Отмена", null).show()
            }
        }.show()
    }

    private fun focusGroupId(id: String) {
        val gv = groupsView ?: return
        val i = indexOfGroup(id)
        if (i >= 0) {
            gv.scrollToPosition(i)
            focusAt(gv, i, 10)
        }
    }

    /** Долгое нажатие на категорию: добавить её в избранное (наверх списка) или убрать оттуда. */
    private fun toggleFavGroup(id: String) {
        if (id.startsWith(G_FOLDER)) {
            manageFolder(id)
            return
        }
        if (id.startsWith("\u0001")) {
            Ui.toast(this, "В избранное можно добавить категорию из плейлиста")
            return
        }
        val t = Channel.groupTitle(id)
        val fav = st.isFavGroup(id)
        AlertDialog.Builder(this).setTitle(t).setItems(
            arrayOf(if (fav) "Убрать из «Моё»" else "Добавить в «Моё»", "Скрыть категорию")
        ) { _, w ->
            if (w == 0) {
                val on = st.toggleFavGroup(id)
                Ui.toast(this, if (on) "Категория «$t» добавлена в «Моё»" else "Категория «$t» убрана из «Моё»")
                buildGroups()
                focusGroupId(id)
            } else {
                st.setHiddenGroup(id, true)
                if (curGroup == id) curGroup = G_ALL
                Ui.toast(this, "Категория «$t» скрыта. Вернуть: Настройки → «Скрытые каналы и категории»")
                render()
                focusGroupId(curGroup)
            }
        }.show()
    }

    private fun addSource() {
        startActivity(Intent(this, SourceActivity::class.java))
    }

    private fun editSource(s: Source) {
        startActivity(Intent(this, SourceActivity::class.java).putExtra("id", s.id))
    }

    private fun pickSource() {
        val cur = st.current()
        val labels = ArrayList<String>()
        for (s in st.sources) labels.add((if (cur != null && s.id == cur.id) "✓  " else "      ") + s.title())
        labels.add("＋  Добавить плейлист")
        if (cur != null) labels.add("✎  Изменить «" + cur.title() + "»")
        AlertDialog.Builder(this).setTitle("Плейлисты").setItems(labels.toTypedArray()) { _, w ->
            if (w < st.sources.size) {
                val s = st.sources[w]
                if (cur == null || s.id != cur.id) {
                    st.select(s)
                    curGroup = G_ALL
                    search.setText("")
                    focusLast = true
                    st.load(false)
                }
            } else if (w == st.sources.size) addSource()
            else if (cur != null) editSource(cur)
        }.show()
    }

    private fun pickGroup() {
        val s = st.current()
        if (groups.isEmpty() || s == null || body.visibility != View.VISIBLE) {
            pickSource()
            return
        }
        val gs = groups.filter { !it.header() }
        val labels = Array(gs.size) { gs[it].title + "  (" + gs[it].count + ")" }
        val selIdx = maxOf(0, gs.indexOfFirst { it.id == curGroup })
        val d = AlertDialog.Builder(this).setTitle(s.title()).setSingleChoiceItems(labels, selIdx) { dd, w ->
            dd.dismiss()
            selectGroup(gs[w].id, true)
        }.create()
        d.show()
        // Долгое нажатие на категорию — избранное или скрыть, на папке — переименовать или удалить.
        d.listView.setOnItemLongClickListener { _, _, w, _ ->
            if (w < 0 || w >= gs.size) return@setOnItemLongClickListener false
            val gid = gs[w].id
            if (gid.startsWith("\u0001") && !gid.startsWith(G_FOLDER)) return@setOnItemLongClickListener false
            d.dismiss()
            toggleFavGroup(gid)
            true
        }
    }

    /** Настройки под PIN-кодом (если включено): код спрашивается один раз до выхода из приложения. */
    private fun menu() {
        if (!st.settingsPin() || st.settingsOpen || !st.hasPin()) {
            settings()
            return
        }
        askPin("PIN-код для настроек") { pin ->
            if (!st.checkPin(pin)) {
                Ui.toast(this, "Неверный PIN-код")
                return@askPin
            }
            st.settingsOpen = true
            settings()
        }
    }

    private fun settings() {
        val items = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        val s = st.current()
        // Плейлист выбирается здесь, в настройках (в верхней строке на ТВ его нет).
        items.add(if (s == null) "Плейлист: добавить…" else "Плейлист: " + s.title() + "…")
        acts.add { pickSource() }
        if (s != null) {
            items.add("Обновить плейлист")
            acts.add {
                st.load(true)
                Ui.toast(this, "Обновляю плейлист…")
            }
            if (st.epgUrl(s).isNotEmpty()) {
                items.add("Обновить телепрограмму")
                acts.add {
                    st.loadEpg(s, true)
                    Ui.toast(this, "Загружаю телепрограмму…")
                }
            }
        }
        if (st.visible().isNotEmpty()) {
            items.add("Телепрограмма всех каналов")
            acts.add { openGuide() }
            items.add("Поиск передачи…")
            acts.add { askText("Название передачи", "") { searchProgs(it) } }
        }
        if (st.hiddenCh.isNotEmpty() || st.hiddenGroups.isNotEmpty()) {
            items.add("Скрытые каналы и категории…")
            acts.add { manageHidden() }
        }
        items.add(if (st.preview()) "Окно предпросмотра: выключить" else "Окно предпросмотра: включить")
        acts.add {
            st.setPreview(!st.preview())
            if (!st.preview()) pb.stop()
            else sel?.let { select(it, PREVIEW_NOW) }
            playbackChanged()
        }
        if (st.adultCount() > 0) {
            items.add(if (st.showAdult) "Скрыть каналы 18+" else "Показать каналы 18+")
            acts.add { toggleAdult() }
        }
        items.add(if (st.settingsPin()) "PIN-код на настройки: выключить" else "PIN-код на настройки: включить")
        acts.add { toggleSettingsPin() }
        if (st.hasPin()) {
            items.add("Изменить PIN-код")
            acts.add { changePin() }
        }
        items.add("Резервная копия настроек…")
        acts.add { Backup.menu(this) }
        items.add(if (st.splash()) "Заставка при запуске: выключить" else "Заставка при запуске: включить")
        acts.add { st.setSplash(!st.splash()) }
        val auto = getSharedPreferences("argeda", MODE_PRIVATE).getBoolean("autostart", false)
        items.add(if (auto) "Запуск при включении приставки: выключить" else "Запуск при включении приставки: включить")
        acts.add { setAutostart(!auto) }
        items.add("Таймер сна: " + Alerts.sleepLabel())
        acts.add { Alerts.nextSleep(this) }
        items.add("Тема: " + Themes.NAMES[Themes.get(this)])
        acts.add { chooseTheme() }
        items.add("Проверить обновления")
        acts.add { Updater.check(this, true) }
        items.add("О приложении")
        acts.add { about() }
        AlertDialog.Builder(this).setTitle("Настройки")
            .setItems(items.toTypedArray()) { _, w -> acts[w]() }.show()
    }

    /** Сетка телепрограммы по каналам открытого списка. */
    private fun openGuide() {
        val l = ca.items()
        GuideActivity.list = ArrayList(if (l.isEmpty()) st.visible() else l)
        GuideActivity.title = if (l.isEmpty()) "Все каналы" else if (search.length() > 0) "Поиск" else titleOf(curGroup)
        if (!st.hasEpg()) Ui.toast(this, if (st.epgLoading()) "Телепрограмма ещё загружается" else "Телепрограммы пока нет")
        startActivity(Intent(this, GuideActivity::class.java))
    }

    /** Поиск по названиям передач во всей телепрограмме: от архива до будущих дней. */
    private fun searchProgs(query: String) {
        val q = norm(query)
        if (q.length < 2) {
            Ui.toast(this, "Введите хотя бы 2 буквы")
            return
        }
        if (!st.hasEpg()) {
            Ui.toast(this, if (st.epgLoading()) "Телепрограмма ещё загружается, попробуйте через минуту" else "Телепрограммы нет — искать негде")
            return
        }
        val now = System.currentTimeMillis()
        val found = ArrayList<Pair<Channel, Epg.Prog>>()
        for (c in st.visible()) {
            val progs = c.progs ?: continue
            for (p in progs) {
                if (p.stop <= now - 7 * 86_400_000L || !norm(p.title).contains(q)) continue
                // В прошлом показываем только то, что есть в архиве.
                if (p.stop <= now && !Catchup.can(c, st.current(), p.start, now)) continue
                found.add(c to p)
            }
        }
        if (found.isEmpty()) {
            Ui.toast(this, "Передач «" + query.trim() + "» не нашлось")
            return
        }
        // Сначала идущие и будущие по времени, затем записи — от свежих к старым.
        val sorted = found.sortedWith { a, b ->
            val x = a.second
            val y = b.second
            val fx = x.stop > now
            val fy = y.stop > now
            if (fx != fy) (if (fx) -1 else 1)
            else if (fx) x.start.compareTo(y.start) else y.start.compareTo(x.start)
        }
        val n = minOf(sorted.size, 150)
        val df = SimpleDateFormat("EEE d MMM, HH:mm", Locale("ru"))
        val labels = Array(n) { i ->
            val (c, p) = sorted[i]
            val state = if (p.start <= now && now < p.stop) "СЕЙЧАС · " else if (p.stop <= now) "ЗАПИСЬ · " else ""
            state + df.format(Date(p.start)) + " · " + c.name + "\n" + p.title
        }
        AlertDialog.Builder(this).setTitle("Найдено: " + found.size).setItems(labels) { _, w ->
            val (c, p) = sorted[w]
            val t = System.currentTimeMillis()
            if (p.start > t) {
                val on = Alerts.toggle(this, c, p)
                Ui.toast(this, if (on) "Напомню в " + hm.format(Date(p.start - 60_000L)) + ": «" + p.title + "»" else "Напоминание снято")
                return@setItems
            }
            openFromEpg(c, EpgAdapter.Row(p, Catchup.can(c, st.current(), p.start, t), p.stop > t, false, false))
        }.show()
    }

    /** Список скрытых: отмеченные остаются скрытыми, снятые возвращаются. */
    private fun manageHidden() {
        val keys = ArrayList<String>()
        val names = ArrayList<String>()
        val isGroup = ArrayList<Boolean>()
        for (g in st.hiddenGroups) {
            keys.add(g)
            names.add("Категория: " + Channel.groupTitle(g))
            isGroup.add(true)
        }
        for (k in st.hiddenCh) {
            keys.add(k)
            names.add(st.byKey(k)?.name ?: k)
            isGroup.add(false)
        }
        val on = BooleanArray(keys.size) { true }
        AlertDialog.Builder(this).setTitle("Снимите галочку, чтобы вернуть")
            .setMultiChoiceItems(names.toTypedArray(), on) { _, w, c -> on[w] = c }
            .setPositiveButton("Готово") { _, _ ->
                var back = 0
                for (i in keys.indices) {
                    if (on[i]) continue
                    back++
                    if (isGroup[i]) st.setHiddenGroup(keys[i], false) else st.unhide(keys[i])
                }
                if (back > 0) {
                    Ui.toast(this, "Возвращено: $back")
                    render()
                }
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun toggleSettingsPin() {
        if (st.settingsPin()) {
            st.setSettingsPin(false)
            Ui.toast(this, "Настройки открываются без PIN-кода")
            return
        }
        if (st.hasPin()) {
            st.setSettingsPin(true)
            st.settingsOpen = true
            Ui.toast(this, "Настройки защищены PIN-кодом (тем же, что для 18+)")
            return
        }
        askPin("Придумайте PIN-код (4–8 цифр)") { pin ->
            if (pin.length < 4) {
                Ui.toast(this, "PIN-код — не меньше 4 цифр")
                return@askPin
            }
            st.setPin(pin)
            st.setSettingsPin(true)
            st.settingsOpen = true
            Ui.toast(this, "Настройки защищены PIN-кодом")
        }
    }

    private fun chooseTheme() {
        AlertDialog.Builder(this).setTitle("Тема оформления")
            .setSingleChoiceItems(Themes.NAMES, Themes.get(this)) { d, w ->
                d.dismiss()
                if (w != Themes.get(this)) Themes.set(this, w)
            }.show()
    }

    private fun toggleAdult() {
        if (st.showAdult) {
            st.setShowAdult(false)
            if (pb.channel?.adult == true) pb.stop()
            Ui.toast(this, "Каналы 18+ скрыты")
            render()
            return
        }
        if (!st.hasPin()) {
            askPin("Придумайте PIN-код для каналов 18+ (4–8 цифр)") { pin ->
                if (pin.length < 4) {
                    Ui.toast(this, "PIN-код — не меньше 4 цифр")
                    return@askPin
                }
                st.setPin(pin)
                st.setShowAdult(true)
                Ui.toast(this, "PIN-код сохранён. Каналы 18+ видны до выхода из приложения")
                render()
            }
        } else {
            askPin("Введите PIN-код") { pin ->
                if (!st.checkPin(pin)) {
                    Ui.toast(this, "Неверный PIN-код")
                    return@askPin
                }
                st.setShowAdult(true)
                Ui.toast(this, "Каналы 18+ видны до выхода из приложения")
                render()
            }
        }
    }

    private fun changePin() {
        askPin("Текущий PIN-код") { old ->
            if (!st.checkPin(old)) {
                Ui.toast(this, "Неверный PIN-код")
                return@askPin
            }
            askPin("Новый PIN-код (4–8 цифр)") { pin ->
                if (pin.length < 4) {
                    Ui.toast(this, "PIN-код — не меньше 4 цифр")
                    return@askPin
                }
                st.setPin(pin)
                Ui.toast(this, "PIN-код изменён")
            }
        }
    }

    private fun askText(title: String, value: String, cb: (String) -> Unit) {
        val e = EditText(this)
        e.isSingleLine = true
        e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        e.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(40))
        e.setText(value)
        e.setSelection(value.length)
        dialogWith(title, e) { cb(e.text.toString()) }
    }

    private fun askPin(title: String, cb: (String) -> Unit) {
        val e = EditText(this)
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        e.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(8))
        e.gravity = Gravity.CENTER
        e.textSize = 24f
        dialogWith(title, e) { cb(e.text.toString().trim()) }
    }

    /** Окно с полем ввода: OK на клавиатуре нажимает кнопку «OK». */
    private fun dialogWith(title: String, e: EditText, ok: () -> Unit) {
        val f = FrameLayout(this)
        val pad = Ui.dp(this, 24)
        f.setPadding(pad, Ui.dp(this, 8), pad, 0)
        f.addView(e)
        val d = AlertDialog.Builder(this).setTitle(title).setView(f)
            .setPositiveButton("OK") { _, _ -> ok() }
            .setNegativeButton("Отмена", null).create()
        e.setOnEditorActionListener { _, _, _ ->
            d.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            true
        }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        e.requestFocus()
    }

    /** Автозапуск. На Android 10+ системе нужно разрешение «Поверх других окон», иначе окно из фона не откроется. */
    private fun setAutostart(on: Boolean) {
        getSharedPreferences("argeda", MODE_PRIVATE).edit().putBoolean("autostart", on).apply()
        if (!on) {
            Ui.toast(this, "Автозапуск выключен")
            return
        }
        if (Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                Ui.toast(this, "Разрешите ARGEDA TV «Поверх других окон» — без этого система не даст открыться при включении")
            } catch (_: Exception) {
                Ui.toast(this, "Автозапуск включён. Если приставка не откроет ARGEDA TV сама, разрешите ей «Поверх других окон» в настройках Android")
            }
            return
        }
        Ui.toast(this, "ARGEDA TV будет открываться при включении приставки")
    }

    private fun about() {
        val v = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        AlertDialog.Builder(this).setTitle("ARGEDA TV $v").setMessage(
            "Проигрыватель IPTV для приставок и телефонов. Своих каналов в приложении нет: " +
                "оно показывает плейлист вашего провайдера (ссылка M3U, Xtream Codes или файл).\n\n" +
                "Пульт в списке:\n▲ ▼ — выбор канала (он включается в окне справа)\nOK — на весь экран\n" +
                "▶ — телепрограмма и записи, ◀ — группы\nДолгое нажатие OK на канале — избранное, свои папки, порядок в избранном, скрыть канал\n" +
                "Долгое нажатие OK на категории — добавить в «Моё» или скрыть, на своей папке — переименовать или удалить\n" +
                "Кнопка «Гид» или Настройки → «Телепрограмма всех каналов» — сетка передач, ◀ ▶ — время\n\n" +
                "Пульт на весь экран:\n▲ ▼ — каналы, OK или ◀ — список, ▶ — сведения и кнопки, цифры — номер канала, 0 — предыдущий канал\n" +
                "⏯ — пауза; если у канала есть архив, после паузы эфир продолжится с того же места\n" +
                "В записи: ◀ ▶ — перемотка, «Назад» — вернуться в эфир\n\n" +
                "Телефон: касание канала — в окне, второе касание — на весь экран. " +
                "На весь экран: свайп вверх/вниз — каналы, в записи свайп влево/вправо — перемотка.\n\n" +
                "Будущая передача в телепрограмме: OK — напомнить за минуту до начала (пока приложение открыто).\n\n" +
                "Ссылки и пароли хранятся только на этом устройстве."
        ).setPositiveButton("OK", null).show()
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager?)?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ---------- Группы ----------

    private inner class GroupAdapter : RecyclerView.Adapter<GroupAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.name)
            val count: TextView = v.findViewById(R.id.count)
        }

        override fun getItemCount(): Int = groups.size

        override fun getItemViewType(pos: Int): Int = if (groups[pos].header()) 1 else 0

        @Suppress("DEPRECATION")
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH {
            val h = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false))
            if (type == 1) {
                // Заголовок блока: мелкий приглушённый текст, пульт его пропускает.
                h.itemView.isFocusable = false
                h.itemView.isClickable = false
                h.itemView.background = null
                h.itemView.minimumHeight = Ui.dp(this@MainActivity, 28)
                h.name.setTextColor(resources.getColor(R.color.muted))
                h.name.textSize = 12f
                h.name.letterSpacing = 0.1f
                return h
            }
            h.itemView.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p >= 0) selectGroup(groups[p].id, true)
            }
            h.itemView.setOnLongClickListener {
                val p = h.bindingAdapterPosition
                if (p >= 0) toggleFavGroup(groups[p].id)
                true
            }
            h.itemView.setOnFocusChangeListener { _, focused ->
                val p = h.bindingAdapterPosition
                if (!focused || p < 0) return@setOnFocusChangeListener
                val id = groups[p].id
                groupRun?.let { ui.removeCallbacks(it) }
                if (id == curGroup && search.length() == 0) return@setOnFocusChangeListener
                val r = Runnable { selectGroup(id, false) }
                groupRun = r
                ui.postDelayed(r, 250)
            }
            h.itemView.setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN || code != KeyEvent.KEYCODE_DPAD_RIGHT) return@setOnKeyListener false
                groupRun?.let { ui.removeCallbacks(it) }
                val p = h.bindingAdapterPosition
                if (p >= 0 && groups[p].id != curGroup) selectGroup(groups[p].id, false)
                focusChannel(firstVisibleChannel())
                true
            }
            return h
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val g = groups[pos]
            h.name.text = g.title
            h.count.text = if (g.header()) "" else g.count.toString()
            h.itemView.isSelected = g.id == curGroup
        }
    }

    companion object {
        private const val G_ALL = "\u0001all"
        private const val G_FAV = "\u0001fav"
        private const val G_REC = "\u0001rec"

        private fun dayWord(n: Int): String {
            val m10 = n % 10
            val m100 = n % 100
            if (m10 == 1 && m100 != 11) return "день"
            if (m10 in 2..4 && (m100 < 12 || m100 > 14)) return "дня"
            return "дней"
        }
        private const val G_FOLDER = "\u0001f:"
        private const val G_HEAD = "\u0002"
        private const val PREVIEW_NONE = 0
        private const val PREVIEW_LATER = 1
        private const val PREVIEW_NOW = 2

        @JvmStatic
        internal fun same(a: Channel?, b: Channel?): Boolean = a != null && b != null && (a === b || a.url == b.url)

        private fun findSame(l: List<Channel>, c: Channel?): Channel? {
            if (c == null) return null
            return l.firstOrNull { same(it, c) }
        }

        /** Каналы по списку ключей (избранное, папка, недавние) в его порядке. */
        private fun ordered(keys: List<String>, vis: List<Channel>): List<Channel> {
            val r = ArrayList<Channel>()
            if (keys.isEmpty()) return r
            val m = HashMap<String, Channel>()
            for (c in vis) m.putIfAbsent(c.key(), c)
            for (k in keys) m[k]?.let { r.add(it) }
            return r
        }

        private fun norm(s: String): String = s.trim().lowercase(Locale.getDefault()).replace('ё', 'е')

        private fun indexOfKey(l: List<Channel>, key: String?): Int {
            if (key.isNullOrEmpty()) return -1
            return l.indexOfFirst { it.key() == key }
        }
    }
}
