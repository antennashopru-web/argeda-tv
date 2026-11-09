package ru.argeda.tv

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** Телепрограмма канала: прошедшие передачи с пометкой «запись», текущая — «сейчас». */
internal class EpgAdapter(private val l: Listener) : RecyclerView.Adapter<EpgAdapter.VH>() {
    class Row(
        @JvmField val p: Epg.Prog,
        @JvmField val archive: Boolean,
        @JvmField val now: Boolean,
        @JvmField val future: Boolean,
        @JvmField val slot: Boolean
    )

    interface Listener {
        fun click(pos: Int)
        fun focus(pos: Int)
        fun key(pos: Int, keyCode: Int): Boolean
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val day: TextView = v.findViewById(R.id.day)
        val time: TextView = v.findViewById(R.id.time)
        val title: TextView = v.findViewById(R.id.title)
        val mark: TextView = v.findViewById(R.id.mark)
        val row: View = v.findViewById(R.id.row)
    }

    private var rows: List<Row> = ArrayList()

    /** Начало передачи, которая сейчас идёт на экране (0 — прямой эфир). */
    var playing = -1L
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))

    /** Канал, чья это телепрограмма: по нему отмечаются передачи с напоминанием. */
    var channel: Channel? = null

    @Suppress("NotifyDataSetChanged")
    fun set(r: List<Row>) {
        rows = r
        notifyDataSetChanged()
    }

    fun rows(): List<Row> = rows

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH {
        val h = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_epg, parent, false))
        h.row.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (i != RecyclerView.NO_POSITION) l.click(i)
        }
        h.row.setOnFocusChangeListener { _, f ->
            val i = h.bindingAdapterPosition
            if (f && i != RecyclerView.NO_POSITION) l.focus(i)
        }
        h.row.setOnKeyListener { _, code, e ->
            val i = h.bindingAdapterPosition
            e.action == KeyEvent.ACTION_DOWN && i != RecyclerView.NO_POSITION && l.key(i, code)
        }
        return h
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val r = rows[pos]
        val day = dayLabel(r.p.start)
        val header = pos == 0 || day != dayLabel(rows[pos - 1].p.start)
        h.day.visibility = if (header) View.VISIBLE else View.GONE
        h.day.text = day
        h.time.text = hm.format(Date(r.p.start))
        h.title.text = r.p.title.ifEmpty { "Без названия" }
        val dim = !r.now && !r.future && !r.archive
        h.title.alpha = if (dim) 0.5f else 1f
        h.time.alpha = if (dim) 0.5f else 1f
        h.title.setTypeface(null, if (r.now) Typeface.BOLD else Typeface.NORMAL)
        val ch = channel
        when {
            r.now -> {
                h.mark.visibility = View.VISIBLE
                h.mark.text = "СЕЙЧАС"
                h.mark.setBackgroundResource(R.drawable.live_bg)
            }
            r.archive -> {
                h.mark.visibility = View.VISIBLE
                h.mark.text = "ЗАПИСЬ"
                h.mark.setBackgroundResource(R.drawable.mark_bg)
            }
            r.future && ch != null && Alerts.has(h.itemView.context, ch, r.p.start) -> {
                h.mark.visibility = View.VISIBLE
                h.mark.text = "НАПОМНЮ"
                h.mark.setBackgroundResource(R.drawable.mark_bg)
            }
            else -> h.mark.visibility = View.GONE
        }
        h.row.isSelected = if (playing == 0L) r.now else r.p.start == playing
    }

    private fun dayLabel(t: Long): String {
        val a = Calendar.getInstance()
        val b = Calendar.getInstance()
        b.timeInMillis = t
        val diff = ((startOfDay(b) - startOfDay(a)) / DAY.toDouble()).roundToInt()
        if (diff == 0) return "Сегодня"
        if (diff == -1) return "Вчера"
        if (diff == 1) return "Завтра"
        val s = dayFmt.format(Date(t))
        return if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)
    }

    companion object {
        private const val HOUR = 3600_000L
        private const val DAY = 24 * HOUR

        /** Строки программы: по XMLTV, а если её нет, но есть архив — часовые отрезки записи. */
        @JvmStatic
        fun build(c: Channel, s: Source?, now: Long): List<Row> {
            val r = ArrayList<Row>()
            val days = minOf(Catchup.days(c, s), 7)
            val progs = c.progs
            if (!progs.isNullOrEmpty()) {
                val from = if (days > 0) now - days * DAY else now - 3 * HOUR
                for (p in progs) {
                    if (p.stop <= from) continue
                    val isNow = p.start <= now && now < p.stop
                    val past = p.stop <= now
                    r.add(Row(p, (past || isNow) && Catchup.can(c, s, p.start, now), isNow, p.start > now, false))
                }
                if (r.isNotEmpty()) return r
            }
            if (days > 0) {
                val off = TimeZone.getDefault().getOffset(now).toLong()
                val cur = now - ((now + off) % HOUR + HOUR) % HOUR // начало текущего часа по местному времени
                var first = cur - days * DAY
                while (first < now - days * DAY) first += HOUR
                var t = first
                while (t < cur) {
                    r.add(Row(Epg.Prog(t, t + HOUR, "Запись эфира"), true, false, false, true))
                    t += HOUR
                }
                r.add(Row(Epg.Prog(cur, cur + HOUR, "Прямой эфир"), Catchup.can(c, s, cur, now), true, false, true))
            }
            return r
        }

        @JvmStatic
        fun nowIndex(rows: List<Row>): Int {
            rows.indexOfFirst { it.now }.let { if (it >= 0) return it }
            rows.indexOfFirst { it.future }.let { if (it >= 0) return it }
            return maxOf(0, rows.size - 1)
        }

        private fun startOfDay(c: Calendar): Long {
            val d = c.clone() as Calendar
            d.set(Calendar.HOUR_OF_DAY, 0)
            d.set(Calendar.MINUTE, 0)
            d.set(Calendar.SECOND, 0)
            d.set(Calendar.MILLISECOND, 0)
            return d.timeInMillis
        }
    }
}
