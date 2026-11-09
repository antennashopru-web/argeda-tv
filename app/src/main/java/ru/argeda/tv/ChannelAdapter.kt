package ru.argeda.tv

import android.content.res.ColorStateList
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Список каналов: на главном экране и в боковой панели плеера. */
internal class ChannelAdapter(private val st: Store, private val l: Listener) : RecyclerView.Adapter<ChannelAdapter.VH>() {
    interface Listener {
        fun click(pos: Int)
        fun longClick(pos: Int)
        fun focus(pos: Int)
        fun key(pos: Int, keyCode: Int): Boolean
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val num: TextView = v.findViewById(R.id.num)
        val initials: TextView = v.findViewById(R.id.initials)
        val name: TextView = v.findViewById(R.id.name)
        val now: TextView = v.findViewById(R.id.now)
        val fav: TextView = v.findViewById(R.id.fav)
        val logo: ImageView = v.findViewById(R.id.logo)
        val prog: ProgressBar = v.findViewById(R.id.prog)
    }

    private var items: List<Channel> = ArrayList()

    /** Канал, который сейчас идёт (подсвечивается в списке плеера). */
    var current: Channel? = null

    @Suppress("NotifyDataSetChanged")
    fun set(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    fun items(): List<Channel> = items

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): VH {
        val h = VH(LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false))
        h.itemView.setOnClickListener {
            val i = h.bindingAdapterPosition
            if (i != RecyclerView.NO_POSITION) l.click(i)
        }
        h.itemView.setOnLongClickListener {
            val i = h.bindingAdapterPosition
            if (i != RecyclerView.NO_POSITION) l.longClick(i)
            true
        }
        h.itemView.setOnFocusChangeListener { _, f ->
            val i = h.bindingAdapterPosition
            if (f && i != RecyclerView.NO_POSITION) l.focus(i)
        }
        h.itemView.setOnKeyListener { _, code, e ->
            val i = h.bindingAdapterPosition
            e.action == KeyEvent.ACTION_DOWN && i != RecyclerView.NO_POSITION && l.key(i, code)
        }
        return h
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val c = items[pos]
        h.num.text = c.num.toString()
        h.name.text = c.name
        h.initials.text = c.initials()
        h.initials.backgroundTintList = ColorStateList.valueOf(TILES[(c.name.hashCode() and 0x7fffffff) % TILES.size])
        Logos.show(h.logo, h.initials, c.logo)
        val t = System.currentTimeMillis()
        val p = Epg.at(c.progs, t)
        if (p != null && p.title.isNotEmpty()) {
            h.now.text = p.title
            h.now.visibility = View.VISIBLE
            h.prog.progress = ((t - p.start) * 1000 / maxOf(1L, p.stop - p.start)).toInt()
            h.prog.visibility = View.VISIBLE
        } else {
            h.now.visibility = View.GONE
            h.prog.visibility = View.GONE
        }
        h.fav.visibility = if (st.isFav(c)) View.VISIBLE else View.GONE
        h.itemView.isSelected = c === current
    }

    private companion object {
        /** Цвета плиток с инициалами (палитра дизайна ARGEDA TV), выбираются по имени канала. */
        val TILES = intArrayOf(
            0xFF2563EB.toInt(), 0xFFDC2626.toInt(), 0xFF059669.toInt(), 0xFFD97706.toInt(), 0xFF7C3AED.toInt(),
            0xFFDB2777.toInt(), 0xFF0891B2.toInt(), 0xFF4F46E5.toInt(), 0xFFE11D48.toInt(), 0xFF65A30D.toInt()
        )
    }
}
