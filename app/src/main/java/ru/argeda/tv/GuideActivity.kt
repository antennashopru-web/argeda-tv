package ru.argeda.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Сетка телепрограммы: все каналы списка, по три передачи с выбранного момента.
 * ◀ ▶ — час назад / вперёд, OK — смотреть: эфир, запись с начала передачи или напоминание о будущей.
 */
class GuideActivity : Activity() {
    private lateinit var st: Store
    private var at = 0L // 0 — сейчас
    private lateinit var whenText: TextView
    private lateinit var rv: RecyclerView
    private val ad = Adapter()
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val day = SimpleDateFormat("EEE d MMM, HH:mm", Locale("ru"))
    private var tv = false

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Themes.apply(this, base, false)
    }

    @Suppress("DEPRECATION")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        st = Store.get(this)
        tv = Ui.isTv(this)
        if (list.isNullOrEmpty()) list = st.visible()
        if (list().isEmpty()) {
            finish()
            return
        }
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(resources.getColor(R.color.bg))
        root.fitsSystemWindows = true
        val pad = Ui.dp(this, 12)
        root.setPadding(pad, pad, pad, 0)

        val top = LinearLayout(this)
        top.gravity = Gravity.CENTER_VERTICAL
        val t = text(20f, R.color.text)
        t.setTypeface(null, Typeface.BOLD)
        t.text = "Телепрограмма · $title"
        t.isSingleLine = true
        t.ellipsize = TextUtils.TruncateAt.END
        top.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!tv) {
            top.addView(button("◀") { shift(-1) })
            top.addView(button("Сейчас") { setAt(0) })
            top.addView(button("▶") { shift(1) })
        }
        root.addView(top)
        whenText = text(14f, R.color.accentLight)
        whenText.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8))
        root.addView(whenText)

        rv = RecyclerView(this)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = ad
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.setPadding(0, 0, 0, pad)
        root.addView(rv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        Ui.tvSafeArea(this)
        setAt(0)
        rv.post { focus(0) }
    }

    override fun onStart() {
        super.onStart()
        Alerts.attach(this)
    }

    override fun onStop() {
        super.onStop()
        Alerts.detach(this)
    }

    private fun list(): List<Channel> = list ?: emptyList()

    @Suppress("DEPRECATION")
    private fun text(sp: Float, color: Int): TextView {
        val v = TextView(this)
        v.textSize = sp
        v.setTextColor(resources.getColor(color))
        return v
    }

    private fun button(s: String, l: View.OnClickListener): TextView {
        val v = text(15f, R.color.text)
        v.text = s
        v.gravity = Gravity.CENTER
        v.setBackgroundResource(R.drawable.btn_bg)
        v.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0)
        v.setOnClickListener(l)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40))
        lp.leftMargin = Ui.dp(this, 8)
        v.layoutParams = lp
        return v
    }

    private fun time(): Long = if (at == 0L) System.currentTimeMillis() else at

    private fun shift(dir: Int) {
        val now = System.currentTimeMillis()
        val base = if (at == 0L) now - now % HOUR else at // шаг от начала текущего часа
        val t = base + dir * HOUR
        if (t < now - 7 * DAY || t > now + 2 * DAY) return
        setAt(if (abs(t - now) < HOUR && t <= now) 0 else t)
    }

    @Suppress("NotifyDataSetChanged")
    private fun setAt(t: Long) {
        at = t
        val hint = if (tv) "   ◀ ▶ — время, OK — смотреть" else "   Коснитесь передачи, чтобы смотреть"
        if (t == 0L) whenText.text = "Сейчас в эфире$hint"
        else {
            var d = day.format(Date(t))
            val c = Calendar.getInstance()
            val x = Calendar.getInstance()
            x.timeInMillis = t
            if (c.get(Calendar.DAY_OF_YEAR) == x.get(Calendar.DAY_OF_YEAR) && c.get(Calendar.YEAR) == x.get(Calendar.YEAR))
                d = "Сегодня, " + hm.format(Date(t))
            whenText.text = d + hint
        }
        ad.notifyDataSetChanged()
    }

    private fun focus(pos: Int) {
        rv.findViewHolderForAdapterPosition(pos)?.itemView?.requestFocus()
    }

    private fun open(c: Channel) {
        val l = list()
        val now = System.currentTimeMillis()
        val t = time()
        val p = Epg.at(c.progs, t)
        val pb = Playback.get(this)
        if (at != 0L && p != null && p.start > now) {
            val on = Alerts.toggle(this, c, p)
            Ui.toast(this, if (on) "Напомню в " + hm.format(Date(p.start - 60_000L)) + ": «" + p.title + "»" else "Напоминание снято")
            ad.notifyItemChanged(l.indexOf(c))
            return
        }
        st.playList = ArrayList(l)
        st.playIndex = maxOf(0, l.indexOf(c))
        st.playTitle = title
        st.played(c)
        if (at != 0L && t < now - 60_000L) {
            val from = p?.start ?: t
            if (!Catchup.can(c, st.current(), from, now)) {
                Ui.toast(this, if (Catchup.days(c, st.current()) > 0) "Эта передача уже не хранится в архиве" else "У этого канала нет архива")
                return
            }
            pb.playArchive(c, from, from, if (p != null) minOf(p.stop, now) else 0, p?.title ?: "")
        } else if (!MainActivity.same(pb.channel, c) || pb.isArchive()) pb.play(c)
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("key", c.key()))
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    shift(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    shift(1)
                    return true
                }
                KeyEvent.KEYCODE_GUIDE -> {
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private inner class Adapter : RecyclerView.Adapter<Adapter.VH>() {
        inner class VH(row: LinearLayout) : RecyclerView.ViewHolder(row) {
            val name: TextView = row.getChildAt(0) as TextView
            val cells: Array<TextView> = Array(3) { row.getChildAt(it + 1) as TextView }
        }

        override fun getItemCount(): Int = list().size

        override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH {
            val ctx = this@GuideActivity
            val row = LinearLayout(ctx)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setBackgroundResource(R.drawable.item_bg)
            row.isFocusable = true
            row.isClickable = true
            val p = Ui.dp(ctx, 10)
            row.setPadding(p, p / 2, p, p / 2)
            row.minimumHeight = Ui.dp(ctx, 52)
            val lp = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = Ui.dp(ctx, 2)
            row.layoutParams = lp
            val wide = parent.width > Ui.dp(ctx, 600) || tv
            val n = text(15f, R.color.text)
            n.setTypeface(null, Typeface.BOLD)
            n.maxLines = 2
            n.ellipsize = TextUtils.TruncateAt.END
            row.addView(n, LinearLayout.LayoutParams(Ui.dp(ctx, if (wide) 200 else 110), ViewGroup.LayoutParams.WRAP_CONTENT))
            for (i in 0 until 3) {
                val c = text(14f, if (i == 0) R.color.text else R.color.muted)
                c.maxLines = 2
                c.ellipsize = TextUtils.TruncateAt.END
                c.setPadding(Ui.dp(ctx, 10), 0, 0, 0)
                // На узком экране — только текущая и следующая передачи.
                if (i == 2 && !wide) c.visibility = View.GONE
                row.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            val h = VH(row)
            row.setOnClickListener {
                val i = h.bindingAdapterPosition
                if (i != RecyclerView.NO_POSITION) open(list()[i])
            }
            return h
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val c = list()[pos]
            h.name.text = "${c.num}  ${c.name}"
            val ps = progsAt(c, time())
            for (i in 0 until 3) {
                val v = h.cells[i]
                if (i >= ps.size) {
                    v.text = if (i == 0) "Нет телепрограммы" else ""
                    continue
                }
                val p = ps[i]
                val mark = if (p.start > System.currentTimeMillis() && Alerts.has(this@GuideActivity, c, p.start)) "  · напомню" else ""
                v.text = hm.format(Date(p.start)) + "  " + p.title.ifEmpty { "Без названия" } + mark
            }
        }
    }

    companion object {
        private const val HOUR = 3600_000L
        private const val DAY = 24 * HOUR

        /** Список каналов для сетки (тот, что открыт на главном экране) и его название. */
        @JvmField
        var list: List<Channel>? = null

        @JvmField
        var title = "Все каналы"

        /** До трёх передач канала, начиная с идущей в момент t. */
        private fun progsAt(c: Channel, t: Long): List<Epg.Prog> =
            c.progs?.asSequence()?.filter { it.stop > t }?.take(3)?.toList() ?: emptyList()
    }
}
