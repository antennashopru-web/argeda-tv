package ru.argeda.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Сетка телепрограммы: все каналы списка, по три передачи с выбранного момента.
 * ◀ ▶ — час назад / вперёд, OK — смотреть: эфир, запись с начала передачи или напоминание о будущей.
 */
public final class GuideActivity extends Activity {
    private static final long HOUR = 3600_000L, DAY = 24 * HOUR;
    /** Список каналов для сетки (тот, что открыт на главном экране) и его название. */
    static List<Channel> list;
    static String title = "Все каналы";

    private Store st;
    private long at; // 0 — сейчас
    private TextView when;
    private RecyclerView rv;
    private final Adapter ad = new Adapter();
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat day = new SimpleDateFormat("EEE d MMM, HH:mm", new Locale("ru"));
    private boolean tv;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        Themes.apply(this, base, false);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        st = Store.get(this);
        tv = Ui.isTv(this);
        if (list == null || list.isEmpty()) list = st.visible();
        if (list.isEmpty()) {
            finish();
            return;
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getResources().getColor(R.color.bg));
        root.setFitsSystemWindows(true);
        int pad = Ui.dp(this, 12);
        root.setPadding(pad, pad, pad, 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(20, R.color.text);
        t.setTypeface(null, Typeface.BOLD);
        t.setText("Телепрограмма · " + title);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!tv) {
            top.addView(button("◀", v -> shift(-1)));
            top.addView(button("Сейчас", v -> setAt(0)));
            top.addView(button("▶", v -> shift(1)));
        }
        root.addView(top);
        when = text(14, R.color.accentLight);
        when.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        root.addView(when);

        rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(ad);
        rv.setItemAnimator(null);
        rv.setClipToPadding(false);
        rv.setPadding(0, 0, 0, pad);
        root.addView(rv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        Ui.tvSafeArea(this);
        setAt(0);
        rv.post(() -> focus(0));
    }

    @Override
    protected void onStart() {
        super.onStart();
        Alerts.attach(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        Alerts.detach(this);
    }

    private TextView text(float sp, int color) {
        TextView v = new TextView(this);
        v.setTextSize(sp);
        v.setTextColor(getResources().getColor(color));
        return v;
    }

    private TextView button(String s, View.OnClickListener l) {
        TextView v = text(15, R.color.text);
        v.setText(s);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundResource(R.drawable.btn_bg);
        v.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        v.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40));
        lp.leftMargin = Ui.dp(this, 8);
        v.setLayoutParams(lp);
        return v;
    }

    private long time() {
        return at == 0 ? System.currentTimeMillis() : at;
    }

    private void shift(int dir) {
        long now = System.currentTimeMillis();
        long base = at == 0 ? now - now % HOUR : at; // шаг от начала текущего часа
        long t = base + dir * HOUR;
        if (t < now - 7 * DAY || t > now + 2 * DAY) return;
        setAt(Math.abs(t - now) < HOUR && t <= now ? 0 : t);
    }

    @SuppressWarnings("NotifyDataSetChanged")
    private void setAt(long t) {
        at = t;
        String hint = tv ? "   ◀ ▶ — время, OK — смотреть" : "   Коснитесь передачи, чтобы смотреть";
        if (t == 0) when.setText("Сейчас в эфире" + hint);
        else {
            String d = day.format(new Date(t));
            Calendar c = Calendar.getInstance();
            Calendar x = Calendar.getInstance();
            x.setTimeInMillis(t);
            if (c.get(Calendar.DAY_OF_YEAR) == x.get(Calendar.DAY_OF_YEAR) && c.get(Calendar.YEAR) == x.get(Calendar.YEAR))
                d = "Сегодня, " + hm.format(new Date(t));
            when.setText(d + hint);
        }
        ad.notifyDataSetChanged();
    }

    private void focus(int pos) {
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh != null) vh.itemView.requestFocus();
    }

    /** До трёх передач канала, начиная с идущей в момент t. */
    private static List<Epg.Prog> progsAt(Channel c, long t) {
        List<Epg.Prog> r = new ArrayList<>();
        if (c.progs == null) return r;
        for (Epg.Prog p : c.progs) {
            if (p.stop <= t) continue;
            r.add(p);
            if (r.size() == 3) break;
        }
        return r;
    }

    private void open(Channel c) {
        long now = System.currentTimeMillis(), t = time();
        Epg.Prog p = Epg.at(c.progs, t);
        Playback pb = Playback.get(this);
        if (at != 0 && p != null && p.start > now) {
            boolean on = Alerts.toggle(this, c, p);
            Ui.toast(this, on ? "Напомню в " + hm.format(new Date(p.start - 60_000L)) + ": «" + p.title + "»" : "Напоминание снято");
            ad.notifyItemChanged(list.indexOf(c));
            return;
        }
        st.playList = new ArrayList<>(list);
        st.playIndex = Math.max(0, list.indexOf(c));
        st.playTitle = title;
        st.played(c);
        if (at != 0 && t < now - 60_000L) {
            long from = p != null ? p.start : t;
            if (!Catchup.can(c, st.current(), from, now)) {
                Ui.toast(this, Catchup.days(c, st.current()) > 0 ? "Эта передача уже не хранится в архиве" : "У этого канала нет архива");
                return;
            }
            pb.playArchive(c, from, from, p != null ? Math.min(p.stop, now) : 0, p != null ? p.title : "");
        } else if (!MainActivity.same(pb.channel, c) || pb.isArchive()) pb.play(c);
        startActivity(new Intent(this, PlayerActivity.class).putExtra("key", c.key()));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int k = e.getKeyCode();
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_MEDIA_REWIND) {
                shift(-1);
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
                shift(1);
                return true;
            }
            if (k == KeyEvent.KEYCODE_GUIDE) {
                finish();
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView[] cells = new TextView[3];

            VH(LinearLayout row) {
                super(row);
                name = (TextView) row.getChildAt(0);
                for (int i = 0; i < 3; i++) cells[i] = (TextView) row.getChildAt(i + 1);
            }
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout row = new LinearLayout(GuideActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.item_bg);
            row.setFocusable(true);
            row.setClickable(true);
            int p = Ui.dp(GuideActivity.this, 10);
            row.setPadding(p, p / 2, p, p / 2);
            row.setMinimumHeight(Ui.dp(GuideActivity.this, 52));
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dp(GuideActivity.this, 2);
            row.setLayoutParams(lp);
            boolean wide = parent.getWidth() > Ui.dp(GuideActivity.this, 600) || tv;
            TextView n = text(15, R.color.text);
            n.setTypeface(null, Typeface.BOLD);
            n.setMaxLines(2);
            n.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(n, new LinearLayout.LayoutParams(Ui.dp(GuideActivity.this, wide ? 200 : 110), ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int i = 0; i < 3; i++) {
                TextView c = text(14, i == 0 ? R.color.text : R.color.muted);
                c.setMaxLines(2);
                c.setEllipsize(TextUtils.TruncateAt.END);
                c.setPadding(Ui.dp(GuideActivity.this, 10), 0, 0, 0);
                // На узком экране — только текущая и следующая передачи.
                if (i == 2 && !wide) c.setVisibility(View.GONE);
                row.addView(c, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            }
            VH h = new VH(row);
            row.setOnClickListener(v -> {
                int i = h.getBindingAdapterPosition();
                if (i != RecyclerView.NO_POSITION) open(list.get(i));
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Channel c = list.get(pos);
            h.name.setText(c.num + "  " + c.name);
            long t = time();
            List<Epg.Prog> ps = progsAt(c, t);
            for (int i = 0; i < 3; i++) {
                TextView v = h.cells[i];
                if (i >= ps.size()) {
                    v.setText(i == 0 ? "Нет телепрограммы" : "");
                    continue;
                }
                Epg.Prog p = ps.get(i);
                String mark = p.start > System.currentTimeMillis() && Alerts.has(GuideActivity.this, c, p.start) ? "  · напомню" : "";
                v.setText(hm.format(new Date(p.start)) + "  " + (p.title.isEmpty() ? "Без названия" : p.title) + mark);
            }
        }
    }
}
