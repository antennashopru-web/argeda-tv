package ru.argeda.tv;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Список каналов: на главном экране и в боковой панели плеера. */
final class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.VH> {
    interface Listener {
        void click(int pos);

        void longClick(int pos);

        void focus(int pos);

        boolean key(int pos, int keyCode);
    }

    /** Цвета плиток с инициалами (палитра дизайна ARGEDA TV), выбираются по имени канала. */
    private static final int[] TILES = {
            0xFF2563EB, 0xFFDC2626, 0xFF059669, 0xFFD97706, 0xFF7C3AED,
            0xFFDB2777, 0xFF0891B2, 0xFF4F46E5, 0xFFE11D48, 0xFF65A30D };

    static final class VH extends RecyclerView.ViewHolder {
        final TextView num, initials, name, now, fav;
        final ImageView logo;
        final ProgressBar prog;

        VH(View v) {
            super(v);
            num = v.findViewById(R.id.num);
            initials = v.findViewById(R.id.initials);
            name = v.findViewById(R.id.name);
            now = v.findViewById(R.id.now);
            fav = v.findViewById(R.id.fav);
            logo = v.findViewById(R.id.logo);
            prog = v.findViewById(R.id.prog);
        }
    }

    private final Store st;
    private final Listener l;
    private List<Channel> items = new ArrayList<>();
    /** Канал, который сейчас идёт (подсвечивается в списке плеера). */
    Channel current;

    ChannelAdapter(Store st, Listener l) {
        this.st = st;
        this.l = l;
    }

    @SuppressWarnings("NotifyDataSetChanged")
    void set(List<Channel> list) {
        items = list;
        notifyDataSetChanged();
    }

    List<Channel> items() {
        return items;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        VH h = new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_channel, parent, false));
        h.itemView.setOnClickListener(v -> {
            int i = h.getBindingAdapterPosition();
            if (i != RecyclerView.NO_POSITION) l.click(i);
        });
        h.itemView.setOnLongClickListener(v -> {
            int i = h.getBindingAdapterPosition();
            if (i != RecyclerView.NO_POSITION) l.longClick(i);
            return true;
        });
        h.itemView.setOnFocusChangeListener((v, f) -> {
            int i = h.getBindingAdapterPosition();
            if (f && i != RecyclerView.NO_POSITION) l.focus(i);
        });
        h.itemView.setOnKeyListener((v, code, e) -> {
            int i = h.getBindingAdapterPosition();
            return e.getAction() == KeyEvent.ACTION_DOWN && i != RecyclerView.NO_POSITION && l.key(i, code);
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Channel c = items.get(pos);
        h.num.setText(String.valueOf(c.num));
        h.name.setText(c.name);
        h.initials.setText(c.initials());
        h.initials.setBackgroundTintList(ColorStateList.valueOf(TILES[(c.name.hashCode() & 0x7fffffff) % TILES.length]));
        Logos.show(h.logo, h.initials, c.logo);
        long t = System.currentTimeMillis();
        Epg.Prog p = Epg.at(c.progs, t);
        if (p != null && !p.title.isEmpty()) {
            h.now.setText(p.title);
            h.now.setVisibility(View.VISIBLE);
            h.prog.setProgress((int) ((t - p.start) * 1000 / Math.max(1, p.stop - p.start)));
            h.prog.setVisibility(View.VISIBLE);
        } else {
            h.now.setVisibility(View.GONE);
            h.prog.setVisibility(View.GONE);
        }
        h.fav.setVisibility(st.isFav(c) ? View.VISIBLE : View.GONE);
        h.itemView.setSelected(c == current);
    }
}
