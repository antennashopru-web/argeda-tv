package ru.argeda.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Главный экран: группы слева, каналы справа, поиск и меню сверху. */
public class MainActivity extends Activity implements Store.Listener {
    private static final String G_ALL = "\u0001all", G_FAV = "\u0001fav", G_REC = "\u0001rec";

    private static final class Group {
        final String id, title;
        final int count;

        Group(String id, String title, int count) {
            this.id = id;
            this.title = title;
            this.count = count;
        }
    }

    private Store st;
    private RecyclerView groupsView, channelsView;
    private final List<Group> groups = new ArrayList<>();
    private final GroupAdapter ga = new GroupAdapter();
    private ChannelAdapter ca;
    private TextView source, loadingText, emptyText, emptyBtn;
    private EditText search;
    private View body, empty, loading;
    private boolean narrow, focusLast = true;
    private String curGroup = G_ALL, listKey = "";
    private int seenSeq;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable filterRun = this::applyFilter;
    private Runnable groupRun;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (st.hasEpg()) ca.notifyItemRangeChanged(0, ca.getItemCount(), "t");
            ui.postDelayed(this, 60_000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        st = Store.get(this);
        Logos.init(this);
        groupsView = findViewById(R.id.groups);
        channelsView = findViewById(R.id.channels);
        source = findViewById(R.id.source);
        search = findViewById(R.id.search);
        body = findViewById(R.id.body);
        empty = findViewById(R.id.empty);
        loading = findViewById(R.id.loading);
        loadingText = findViewById(R.id.loadingText);
        emptyText = findViewById(R.id.emptyText);
        emptyBtn = findViewById(R.id.emptyBtn);

        narrow = getResources().getConfiguration().screenWidthDp < 600;
        if (narrow) {
            groupsView.setVisibility(View.GONE);
            findViewById(R.id.brand).setVisibility(View.GONE);
        }
        if (b != null) curGroup = b.getString("g", G_ALL);

        groupsView.setLayoutManager(new LinearLayoutManager(this));
        groupsView.setAdapter(ga);
        groupsView.setItemAnimator(null);

        ca = new ChannelAdapter(st, new ChannelAdapter.Listener() {
            @Override
            public void click(int pos) {
                play(pos);
            }

            @Override
            public void longClick(int pos) {
                toggleFav(pos);
            }

            @Override
            public boolean key(int pos, int code) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && !narrow) {
                    focusGroup();
                    return true;
                }
                return false;
            }
        });
        channelsView.setLayoutManager(new LinearLayoutManager(this));
        channelsView.setAdapter(ca);
        channelsView.setItemAnimator(null);
        channelsView.setHasFixedSize(true);

        source.setOnClickListener(v -> {
            if (narrow) pickGroup();
            else pickSource();
        });
        findViewById(R.id.menu).setOnClickListener(v -> menu());
        emptyBtn.setOnClickListener(v -> {
            Source s = st.current();
            if (s == null) addSource();
            else editSource(s);
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable e) {
                ui.removeCallbacks(filterRun);
                ui.postDelayed(filterRun, 250);
            }
        });
        search.setOnEditorActionListener((v, a, e) -> {
            hideKeyboard();
            ui.removeCallbacks(filterRun);
            applyFilter();
            focusChannel(0);
            return true;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        st.listener = this;
        Source s = st.current();
        if (s != null && !s.id.equals(st.loadedId) && !s.id.equals(st.loadingId)) st.load(false);
        render();
        ui.postDelayed(tick, 60_000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        st.listener = null;
        ui.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) st.setShowAdult(false);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("g", curGroup);
    }

    @Override
    public void changed() {
        render();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            menu();
            return true;
        }
        if (code == KeyEvent.KEYCODE_SEARCH) {
            search.requestFocus();
            InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (im != null) im.showSoftInput(search, 0);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (search.length() > 0) {
            search.setText("");
            return;
        }
        super.onBackPressed();
    }

    // ---------- Отрисовка ----------

    private void render() {
        Source s = st.current();
        List<Channel> vis = st.visible();
        if (st.statusSeq != seenSeq) {
            seenSeq = st.statusSeq;
            if (!st.status.isEmpty() && !vis.isEmpty()) Ui.toast(this, "Не удалось обновить плейлист: " + st.status + ". Показаны сохранённые каналы");
        }
        if (s == null) {
            show(empty);
            emptyText.setText("Добавьте плейлист вашего IPTV-провайдера");
            emptyBtn.setText("＋  Добавить плейлист");
            source.setText("Плейлисты  ▾");
            return;
        }
        if (!narrow) source.setText(s.title() + "  ▾");
        if (vis.isEmpty()) {
            if (st.busy) {
                show(loading);
                loadingText.setText("Загружаю «" + s.title() + "»…");
            } else {
                show(empty);
                emptyText.setText(st.status.isEmpty() ? "В плейлисте нет каналов" : "Не удалось загрузить плейлист\n" + st.status);
                emptyBtn.setText("Изменить плейлист");
            }
            if (narrow) source.setText("Плейлисты  ▾");
            return;
        }
        show(body);
        buildGroups();
        applyFilter();
    }

    private void show(View v) {
        body.setVisibility(v == body ? View.VISIBLE : View.GONE);
        empty.setVisibility(v == empty ? View.VISIBLE : View.GONE);
        loading.setVisibility(v == loading ? View.VISIBLE : View.GONE);
        if (v == empty && !narrow) emptyBtn.requestFocus();
    }

    @SuppressWarnings("NotifyDataSetChanged")
    private void buildGroups() {
        List<Channel> vis = st.visible();
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        int favN = 0;
        for (Channel c : vis) {
            Integer n = m.get(c.group);
            m.put(c.group, n == null ? 1 : n + 1);
            if (st.isFav(c)) favN++;
        }
        groups.clear();
        if (favN > 0) groups.add(new Group(G_FAV, "★  Избранное", favN));
        int recN = recentOf(vis).size();
        if (recN > 0) groups.add(new Group(G_REC, "Недавние", recN));
        groups.add(new Group(G_ALL, "Все каналы", vis.size()));
        for (Map.Entry<String, Integer> e : m.entrySet()) groups.add(new Group(e.getKey(), Channel.groupTitle(e.getKey()), e.getValue()));
        if (indexOfGroup(curGroup) < 0) curGroup = G_ALL;
        ga.notifyDataSetChanged();
        if (narrow) source.setText(titleOf(curGroup) + "  ▾");
    }

    private void applyFilter() {
        String q = norm(search.getText().toString());
        List<Channel> vis = st.visible(), res;
        if (!q.isEmpty()) {
            res = new ArrayList<>();
            for (Channel c : vis) if (norm(c.name).contains(q) || String.valueOf(c.num).equals(q)) res.add(c);
        } else {
            res = channelsFor(curGroup, vis);
        }
        String key = q.isEmpty() ? "g:" + curGroup : "q:" + q;
        boolean same = key.equals(listKey);
        listKey = key;
        ca.set(res);
        if (!same) channelsView.scrollToPosition(0);
        if (focusLast && !res.isEmpty()) {
            focusLast = false;
            int i = indexOfKey(res, st.lastKey);
            if (i >= 0 || !narrow) focusChannel(Math.max(0, i));
        }
    }

    private List<Channel> channelsFor(String g, List<Channel> vis) {
        if (G_ALL.equals(g)) return vis;
        if (G_REC.equals(g)) return recentOf(vis);
        List<Channel> r = new ArrayList<>();
        boolean f = G_FAV.equals(g);
        for (Channel c : vis) if (f ? st.isFav(c) : g.equals(c.group)) r.add(c);
        return r;
    }

    private List<Channel> recentOf(List<Channel> vis) {
        List<Channel> r = new ArrayList<>();
        if (st.recent.isEmpty()) return r;
        Map<String, Channel> m = new HashMap<>();
        for (Channel c : vis) if (!m.containsKey(c.key())) m.put(c.key(), c);
        for (String k : st.recent) {
            Channel c = m.get(k);
            if (c != null) r.add(c);
        }
        return r;
    }

    private static String norm(String s) {
        return s.trim().toLowerCase(Locale.getDefault()).replace('ё', 'е');
    }

    private static int indexOfKey(List<Channel> l, String key) {
        if (key == null || key.isEmpty()) return -1;
        for (int i = 0; i < l.size(); i++) if (l.get(i).key().equals(key)) return i;
        return -1;
    }

    private int indexOfGroup(String id) {
        for (int i = 0; i < groups.size(); i++) if (groups.get(i).id.equals(id)) return i;
        return -1;
    }

    private String titleOf(String id) {
        if (G_ALL.equals(id)) return "Все каналы";
        if (G_FAV.equals(id)) return "Избранное";
        if (G_REC.equals(id)) return "Недавние";
        return Channel.groupTitle(id);
    }

    // ---------- Фокус для пульта ----------

    private void focusChannel(int pos) {
        if (pos < 0 || pos >= ca.getItemCount()) return;
        ((LinearLayoutManager) channelsView.getLayoutManager()).scrollToPositionWithOffset(pos, channelsView.getHeight() / 3);
        focusAt(channelsView, pos, 10);
    }

    private void focusGroup() {
        int i = Math.max(0, indexOfGroup(curGroup));
        ((LinearLayoutManager) groupsView.getLayoutManager()).scrollToPositionWithOffset(i, groupsView.getHeight() / 3);
        focusAt(groupsView, i, 10);
    }

    private void focusAt(RecyclerView rv, int pos, int tries) {
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(pos);
        if (vh != null) vh.itemView.requestFocus();
        else if (tries > 0) rv.postDelayed(() -> focusAt(rv, pos, tries - 1), 40);
    }

    private int firstVisibleChannel() {
        int i = ((LinearLayoutManager) channelsView.getLayoutManager()).findFirstVisibleItemPosition();
        return Math.max(0, i);
    }

    private void selectGroup(String id, boolean moveFocus) {
        boolean changed = !id.equals(curGroup) || search.length() > 0;
        curGroup = id;
        if (search.length() > 0) search.setText("");
        ui.removeCallbacks(filterRun);
        ga.notifyItemRangeChanged(0, groups.size(), "sel");
        if (changed) applyFilter();
        if (narrow) source.setText(titleOf(id) + "  ▾");
        if (moveFocus && !narrow) focusChannel(0);
    }

    // ---------- Действия ----------

    private void play(int pos) {
        List<Channel> l = ca.items();
        if (pos < 0 || pos >= l.size()) return;
        Channel c = l.get(pos);
        st.playList = new ArrayList<>(l);
        st.playIndex = pos;
        st.playTitle = search.length() > 0 ? "Поиск: " + search.getText().toString().trim() : titleOf(curGroup);
        st.played(c);
        focusLast = true;
        startActivity(new Intent(this, PlayerActivity.class).putExtra("key", c.key()));
    }

    private void toggleFav(int pos) {
        List<Channel> l = ca.items();
        if (pos < 0 || pos >= l.size()) return;
        Channel c = l.get(pos);
        boolean on = st.toggleFav(c);
        Ui.toast(this, on ? "«" + c.name + "» добавлен в избранное" : "«" + c.name + "» убран из избранного");
        boolean favList = G_FAV.equals(curGroup) && search.length() == 0;
        buildGroups();
        if (favList) applyFilter();
        else ca.notifyItemChanged(pos, "t");
    }

    private void addSource() {
        startActivity(new Intent(this, SourceActivity.class));
    }

    private void editSource(Source s) {
        startActivity(new Intent(this, SourceActivity.class).putExtra("id", s.id));
    }

    private void pickSource() {
        Source cur = st.current();
        List<String> labels = new ArrayList<>();
        for (Source s : st.sources) labels.add((cur != null && s.id.equals(cur.id) ? "✓  " : "      ") + s.title());
        labels.add("＋  Добавить плейлист");
        if (cur != null) labels.add("✎  Изменить «" + cur.title() + "»");
        new AlertDialog.Builder(this).setTitle("Плейлисты").setItems(labels.toArray(new String[0]), (d, w) -> {
            if (w < st.sources.size()) {
                Source s = st.sources.get(w);
                if (cur == null || !s.id.equals(cur.id)) {
                    st.select(s);
                    curGroup = G_ALL;
                    search.setText("");
                    focusLast = true;
                    st.load(false);
                }
            } else if (w == st.sources.size()) addSource();
            else if (cur != null) editSource(cur);
        }).show();
    }

    private void pickGroup() {
        Source s = st.current();
        if (groups.isEmpty() || s == null || body.getVisibility() != View.VISIBLE) {
            pickSource();
            return;
        }
        String[] labels = new String[groups.size()];
        int sel = 0;
        for (int i = 0; i < groups.size(); i++) {
            Group g = groups.get(i);
            labels[i] = g.title + "  (" + g.count + ")";
            if (g.id.equals(curGroup)) sel = i;
        }
        new AlertDialog.Builder(this).setTitle(s.title()).setSingleChoiceItems(labels, sel, (d, w) -> {
            d.dismiss();
            selectGroup(groups.get(w).id, true);
        }).show();
    }

    private void menu() {
        List<String> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();
        Source s = st.current();
        if (narrow || s == null) {
            items.add("Плейлисты…");
            acts.add(this::pickSource);
        }
        if (s != null) {
            items.add("Обновить плейлист");
            acts.add(() -> {
                st.load(true);
                Ui.toast(this, "Обновляю плейлист…");
            });
            if (!st.epgUrl(s).isEmpty()) {
                items.add("Обновить телепрограмму");
                acts.add(() -> {
                    st.loadEpg(s, true);
                    Ui.toast(this, "Загружаю телепрограмму…");
                });
            }
        }
        if (st.adultCount() > 0) {
            items.add(st.showAdult ? "Скрыть каналы 18+" : "Показать каналы 18+");
            acts.add(this::toggleAdult);
        }
        if (st.hasPin()) {
            items.add("Изменить PIN-код");
            acts.add(this::changePin);
        }
        items.add("О приложении");
        acts.add(this::about);
        new AlertDialog.Builder(this).setItems(items.toArray(new String[0]), (d, w) -> acts.get(w).run()).show();
    }

    private void toggleAdult() {
        if (st.showAdult) {
            st.setShowAdult(false);
            Ui.toast(this, "Каналы 18+ скрыты");
            render();
            return;
        }
        if (!st.hasPin()) {
            askPin("Придумайте PIN-код для каналов 18+ (4–8 цифр)", pin -> {
                if (pin.length() < 4) {
                    Ui.toast(this, "PIN-код — не меньше 4 цифр");
                    return;
                }
                st.setPin(pin);
                st.setShowAdult(true);
                Ui.toast(this, "PIN-код сохранён. Каналы 18+ видны до выхода из приложения");
                render();
            });
        } else {
            askPin("Введите PIN-код", pin -> {
                if (!st.checkPin(pin)) {
                    Ui.toast(this, "Неверный PIN-код");
                    return;
                }
                st.setShowAdult(true);
                Ui.toast(this, "Каналы 18+ видны до выхода из приложения");
                render();
            });
        }
    }

    private void changePin() {
        askPin("Текущий PIN-код", old -> {
            if (!st.checkPin(old)) {
                Ui.toast(this, "Неверный PIN-код");
                return;
            }
            askPin("Новый PIN-код (4–8 цифр)", pin -> {
                if (pin.length() < 4) {
                    Ui.toast(this, "PIN-код — не меньше 4 цифр");
                    return;
                }
                st.setPin(pin);
                Ui.toast(this, "PIN-код изменён");
            });
        });
    }

    private interface PinCallback {
        void got(String pin);
    }

    private void askPin(String title, PinCallback cb) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8)});
        e.setGravity(Gravity.CENTER);
        e.setTextSize(24);
        FrameLayout f = new FrameLayout(this);
        int pad = Ui.dp(this, 24);
        f.setPadding(pad, Ui.dp(this, 8), pad, 0);
        f.addView(e);
        AlertDialog d = new AlertDialog.Builder(this).setTitle(title).setView(f)
                .setPositiveButton("OK", (x, w) -> cb.got(e.getText().toString().trim()))
                .setNegativeButton("Отмена", null).create();
        e.setOnEditorActionListener((v, a, ev) -> {
            d.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            return true;
        });
        if (d.getWindow() != null) d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        e.requestFocus();
    }

    private void about() {
        String v = "";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this).setTitle("ARGEDA TV " + v).setMessage(
                "Проигрыватель IPTV для приставок и телефонов. Своих каналов в приложении нет: "
                        + "оно показывает плейлист вашего провайдера (ссылка M3U, Xtream Codes или файл).\n\n"
                        + "Пульт:\n▲ ▼ — переключение каналов\nOK или ◀ — список каналов\n▶ — сведения и кнопки\n"
                        + "Цифры — номер канала\nДолгое нажатие OK в списке — избранное\n\n"
                        + "Телефон: касание — сведения, свайп вверх/вниз — каналы, свайп вправо — список.\n\n"
                        + "Ссылки и пароли хранятся только на этом устройстве.")
                .setPositiveButton("OK", null).show();
    }

    private void hideKeyboard() {
        InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }

    // ---------- Группы ----------

    private final class GroupAdapter extends RecyclerView.Adapter<GroupAdapter.VH> {
        final class VH extends RecyclerView.ViewHolder {
            final TextView name, count;

            VH(View v) {
                super(v);
                name = v.findViewById(R.id.name);
                count = v.findViewById(R.id.count);
            }
        }

        @Override
        public int getItemCount() {
            return groups.size();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            VH h = new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_group, parent, false));
            h.itemView.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p >= 0) selectGroup(groups.get(p).id, true);
            });
            h.itemView.setOnFocusChangeListener((v, focused) -> {
                int p = h.getBindingAdapterPosition();
                if (!focused || p < 0) return;
                String id = groups.get(p).id;
                ui.removeCallbacks(groupRun);
                if (id.equals(curGroup) && search.length() == 0) return;
                groupRun = () -> selectGroup(id, false);
                ui.postDelayed(groupRun, 250);
            });
            h.itemView.setOnKeyListener((v, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN || code != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                ui.removeCallbacks(groupRun);
                int p = h.getBindingAdapterPosition();
                if (p >= 0 && !groups.get(p).id.equals(curGroup)) selectGroup(groups.get(p).id, false);
                focusChannel(firstVisibleChannel());
                return true;
            });
            return h;
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            Group g = groups.get(pos);
            h.name.setText(g.title);
            h.count.setText(String.valueOf(g.count));
            h.itemView.setSelected(g.id.equals(curGroup));
        }
    }
}
