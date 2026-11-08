package ru.argeda.tv;

import android.graphics.Typeface;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Телепрограмма канала: прошедшие передачи с пометкой «запись», текущая — «сейчас». */
final class EpgAdapter extends RecyclerView.Adapter<EpgAdapter.VH> {
    private static final long HOUR = 3600_000L, DAY = 24 * HOUR;

    static final class Row {
        final Epg.Prog p;
        final boolean archive, now, future, slot;

        Row(Epg.Prog p, boolean archive, boolean now, boolean future, boolean slot) {
            this.p = p;
            this.archive = archive;
            this.now = now;
            this.future = future;
            this.slot = slot;
        }
    }

    interface Listener {
        void click(int pos);

        void focus(int pos);

        boolean key(int pos, int keyCode);
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView day, time, title, mark;
        final View row;

        VH(View v) {
            super(v);
            day = v.findViewById(R.id.day);
            time = v.findViewById(R.id.time);
            title = v.findViewById(R.id.title);
            mark = v.findViewById(R.id.mark);
            row = v.findViewById(R.id.row);
        }
    }

    private final Listener l;
    private List<Row> rows = new ArrayList<>();
    /** Начало передачи, которая сейчас идёт на экране (0 — прямой эфир). */
    long playing = -1;
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("EEEE, d MMMM", new Locale("ru"));

    EpgAdapter(Listener l) {
        this.l = l;
    }

    /** Строки программы: по XMLTV, а если её нет, но есть архив — часовые отрезки записи. */
    static List<Row> build(Channel c, Source s, long now) {
        List<Row> r = new ArrayList<>();
        int days = Math.min(Catchup.days(c, s), 7);
        if (c.progs != null && !c.progs.isEmpty()) {
            long from = days > 0 ? now - days * DAY : now - 3 * HOUR;
            for (Epg.Prog p : c.progs) {
                if (p.stop <= from) continue;
                boolean isNow = p.start <= now && now < p.stop;
                boolean past = p.stop <= now;
                r.add(new Row(p, (past || isNow) && Catchup.can(c, s, p.start, now), isNow, p.start > now, false));
            }
            if (!r.isEmpty()) return r;
        }
        if (days > 0) {
            long off = TimeZone.getDefault().getOffset(now);
            long cur = now - ((now + off) % HOUR + HOUR) % HOUR; // начало текущего часа по местному времени
            long first = cur - days * DAY;
            while (first < now - days * DAY) first += HOUR;
            for (long t = first; t < cur; t += HOUR) r.add(new Row(new Epg.Prog(t, t + HOUR, "Запись эфира"), true, false, false, true));
            r.add(new Row(new Epg.Prog(cur, cur + HOUR, "Прямой эфир"), Catchup.can(c, s, cur, now), true, false, true));
        }
        return r;
    }

    static int nowIndex(List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).now) return i;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).future) return i;
        return Math.max(0, rows.size() - 1);
    }

    @SuppressWarnings("NotifyDataSetChanged")
    /** Канал, чья это телепрограмма: по нему отмечаются передачи с напоминанием. */
    Channel channel;

    void set(List<Row> r) {
        rows = r;
        notifyDataSetChanged();
    }

    List<Row> rows() {
        return rows;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        VH h = new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_epg, parent, false));
        h.row.setOnClickListener(v -> {
            int i = h.getBindingAdapterPosition();
            if (i != RecyclerView.NO_POSITION) l.click(i);
        });
        h.row.setOnFocusChangeListener((v, f) -> {
            int i = h.getBindingAdapterPosition();
            if (f && i != RecyclerView.NO_POSITION) l.focus(i);
        });
        h.row.setOnKeyListener((v, code, e) -> {
            int i = h.getBindingAdapterPosition();
            return e.getAction() == KeyEvent.ACTION_DOWN && i != RecyclerView.NO_POSITION && l.key(i, code);
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Row r = rows.get(pos);
        String day = dayLabel(r.p.start);
        boolean header = pos == 0 || !day.equals(dayLabel(rows.get(pos - 1).p.start));
        h.day.setVisibility(header ? View.VISIBLE : View.GONE);
        h.day.setText(day);
        h.time.setText(hm.format(new Date(r.p.start)));
        h.title.setText(r.p.title.isEmpty() ? "Без названия" : r.p.title);
        boolean dim = !r.now && !r.future && !r.archive;
        h.title.setAlpha(dim ? 0.5f : 1f);
        h.time.setAlpha(dim ? 0.5f : 1f);
        h.title.setTypeface(null, r.now ? Typeface.BOLD : Typeface.NORMAL);
        if (r.now) {
            h.mark.setVisibility(View.VISIBLE);
            h.mark.setText("СЕЙЧАС");
            h.mark.setBackgroundResource(R.drawable.live_bg);
        } else if (r.archive) {
            h.mark.setVisibility(View.VISIBLE);
            h.mark.setText("ЗАПИСЬ");
            h.mark.setBackgroundResource(R.drawable.mark_bg);
        } else if (r.future && channel != null && Alerts.has(h.itemView.getContext(), channel, r.p.start)) {
            h.mark.setVisibility(View.VISIBLE);
            h.mark.setText("НАПОМНЮ");
            h.mark.setBackgroundResource(R.drawable.mark_bg);
        } else h.mark.setVisibility(View.GONE);
        h.row.setSelected(playing == 0 ? r.now : r.p.start == playing);
    }

    private String dayLabel(long t) {
        Calendar a = Calendar.getInstance(), b = Calendar.getInstance();
        b.setTimeInMillis(t);
        int diff = (int) Math.round((startOfDay(b) - startOfDay(a)) / (double) DAY);
        if (diff == 0) return "Сегодня";
        if (diff == -1) return "Вчера";
        if (diff == 1) return "Завтра";
        String s = dayFmt.format(new Date(t));
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static long startOfDay(Calendar c) {
        Calendar d = (Calendar) c.clone();
        d.set(Calendar.HOUR_OF_DAY, 0);
        d.set(Calendar.MINUTE, 0);
        d.set(Calendar.SECOND, 0);
        d.set(Calendar.MILLISECOND, 0);
        return d.getTimeInMillis();
    }
}
