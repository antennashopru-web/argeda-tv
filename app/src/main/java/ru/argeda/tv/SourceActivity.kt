package ru.argeda.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/** Добавление и изменение плейлиста: ссылка M3U, Xtream Codes или файл; на ТВ — ввод с телефона. */
class SourceActivity : Activity() {
    private lateinit var st: Store
    private lateinit var src: Source
    private var isNew = false
    private var type = "m3u"
    private var archive = "auto"
    private lateinit var name: EditText
    private lateinit var url: EditText
    private lateinit var server: EditText
    private lateinit var user: EditText
    private lateinit var pass: EditText
    private lateinit var epg: EditText
    private lateinit var ua: EditText
    private lateinit var tM3u: TextView
    private lateinit var tXtream: TextView
    private lateinit var tFile: TextView
    private lateinit var fileInfo: TextView
    private lateinit var phone: TextView
    private lateinit var gM3u: View
    private lateinit var gXtream: View
    private lateinit var gFile: View
    private lateinit var picked: File
    private var link: LinkServer? = null
    private val io = Executors.newSingleThreadExecutor()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        Themes.apply(this, base, false)
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_source)
        Ui.tvSafeArea(this)
        st = Store.get(this)
        name = findViewById(R.id.name)
        url = findViewById(R.id.url)
        server = findViewById(R.id.server)
        user = findViewById(R.id.user)
        pass = findViewById(R.id.pass)
        epg = findViewById(R.id.epg)
        ua = findViewById(R.id.ua)
        tM3u = findViewById(R.id.tM3u)
        tXtream = findViewById(R.id.tXtream)
        tFile = findViewById(R.id.tFile)
        fileInfo = findViewById(R.id.fileInfo)
        phone = findViewById(R.id.phone)
        gM3u = findViewById(R.id.gM3u)
        gXtream = findViewById(R.id.gXtream)
        gFile = findViewById(R.id.gFile)

        if (resources.configuration.screenWidthDp > 760) {
            val form = findViewById<View>(R.id.form)
            val lp = form.layoutParams
            lp.width = Ui.dp(this, 700)
            form.layoutParams = lp
        }

        val existing = st.byId(intent.getStringExtra("id"))
        isNew = existing == null
        src = existing ?: Source().apply { id = System.currentTimeMillis().toString(36) }
        if (!isNew && b == null) {
            name.setText(src.name)
            url.setText(src.url)
            server.setText(src.server)
            user.setText(src.user)
            pass.setText(src.pass)
            epg.setText(src.epg)
            ua.setText(src.ua)
        }
        if (b != null) src.id = b.getString("sid", src.id)
        picked = File(filesDir, "pick_" + src.id + ".tmp")
        if (b == null) Store.delete(picked)
        else if (picked.exists()) fileInfo.text = "Файл выбран — нажмите «Сохранить»"
        if (!isNew && src.type == "file" && !picked.exists()) fileInfo.text = "Файл загружен ранее. Можно выбрать другой."

        archive = b?.getString("archive", src.archive) ?: src.archive
        val arch = findViewById<TextView>(R.id.archive)
        arch.text = archName(archive)
        arch.setOnClickListener {
            val sel = maxOf(0, ARCH_IDS.indexOf(archive))
            AlertDialog.Builder(this).setTitle("Тип архива")
                .setSingleChoiceItems(ARCH_NAMES, sel) { d, w ->
                    archive = ARCH_IDS[w]
                    arch.text = archName(archive)
                    d.dismiss()
                }.show()
        }
        findViewById<TextView>(R.id.title).text = if (isNew) "Новый плейлист" else "Плейлист «" + src.title() + "»"
        setType(b?.getString("type", src.type) ?: src.type)

        tM3u.setOnClickListener { setType("m3u") }
        tXtream.setOnClickListener { setType("xtream") }
        tFile.setOnClickListener { setType("file") }
        findViewById<View>(R.id.pick).setOnClickListener { pickFile() }
        findViewById<View>(R.id.save).setOnClickListener { save() }
        findViewById<View>(R.id.cancel).setOnClickListener { finish() }
        val del = findViewById<View>(R.id.delete)
        del.visibility = if (isNew) View.GONE else View.VISIBLE
        del.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage("Удалить плейлист «" + src.title() + "» с этого устройства?")
                .setPositiveButton("Удалить") { _, _ ->
                    st.removeSource(src)
                    Store.delete(picked)
                    finish()
                }
                .setNegativeButton("Отмена", null).show()
        }
        if (isNew && Ui.isTv(this)) url.requestFocus()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("type", type)
        out.putString("archive", archive)
        out.putString("sid", src.id)
    }

    override fun onResume() {
        super.onResume()
        if (!Ui.isTv(this)) return
        val ip = LinkServer.localIp() ?: return
        val l = LinkServer()
        if (!l.start { f -> runOnUiThread { received(f) } }) return
        link = l
        phone.text = "📱 Удобнее ввести с телефона. Подключите телефон к той же сети Wi-Fi и откройте в браузере:\n\n" +
            "http://" + ip + ":" + l.port + "/" + l.code +
            "\n\nСсылка или логин появятся на этом экране — останется нажать «Сохранить»."
        phone.visibility = View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        link?.stop()
        link = null
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    private fun setType(t: String) {
        type = if (t == "xtream" || t == "file") t else "m3u"
        tM3u.isSelected = type == "m3u"
        tXtream.isSelected = type == "xtream"
        tFile.isSelected = type == "file"
        gM3u.visibility = if (type == "m3u") View.VISIBLE else View.GONE
        gXtream.visibility = if (type == "xtream") View.VISIBLE else View.GONE
        gFile.visibility = if (type == "file") View.VISIBLE else View.GONE
    }

    private fun received(f: Map<String, String>) {
        val u = f["url"]?.trim() ?: ""
        val sv = f["server"]?.trim() ?: ""
        if (u.isNotEmpty()) {
            setType("m3u")
            url.setText(u)
        } else if (sv.isNotEmpty()) {
            setType("xtream")
            server.setText(sv)
            user.setText(f["user"]?.trim() ?: "")
            pass.setText(f["pass"]?.trim() ?: "")
        }
        val e = f["epg"]?.trim() ?: ""
        val n = f["name"]?.trim() ?: ""
        if (e.isNotEmpty()) epg.setText(e)
        if (n.isNotEmpty()) name.setText(n)
        Ui.toast(this, "Данные получены с телефона. Проверьте и нажмите «Сохранить»")
        findViewById<View>(R.id.save).requestFocus()
    }

    // ---------- Файл ----------

    @Suppress("DEPRECATION")
    private fun pickFile() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK)
            return
        } catch (_: ActivityNotFoundException) {
        }
        try {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK)
        } catch (_: ActivityNotFoundException) {
            Ui.toast(this, "На этом устройстве нет выбора файлов. Используйте ссылку M3U")
        }
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val u = data?.data
        if (req != PICK || res != RESULT_OK || u == null) return
        fileInfo.text = "Читаю файл…"
        io.execute {
            val msg = try {
                (contentResolver.openInputStream(u) ?: throw IOException("Файл недоступен")).use { input ->
                    val t = Net.text(Net.readAll(input, 64 shl 20))
                    val n = M3u.parse(t).list.size
                    if (n == 0) "В файле нет каналов — нужен плейлист .m3u или .m3u8"
                    else {
                        Store.writeFile(picked, t)
                        "Файл выбран: каналов — $n. Нажмите «Сохранить»."
                    }
                }
            } catch (e: Exception) {
                "Не удалось прочитать файл: " + Store.describe(e)
            }
            runOnUiThread { fileInfo.text = msg }
        }
    }

    // ---------- Сохранение ----------

    private fun save() {
        val n = name.text.toString().trim()
        val u = url.text.toString().trim()
        val sv = server.text.toString().trim()
        val us = user.text.toString().trim()
        val pw = pass.text.toString().trim()
        val ep = epg.text.toString().trim()
        val ag = ua.text.toString().trim()
        val cache = st.cacheFile(src)
        when (type) {
            "m3u" -> if (!u.matches(Regex("(?i)https?://\\S+"))) {
                Ui.toast(this, "Ссылка должна начинаться с http:// или https:// и быть без пробелов")
                url.requestFocus()
                return
            }
            "xtream" -> if (sv.isEmpty() || us.isEmpty() || pw.isEmpty()) {
                Ui.toast(this, "Заполните адрес сервера, логин и пароль")
                (if (sv.isEmpty()) server else if (us.isEmpty()) user else pass).requestFocus()
                return
            }
            else -> {
                val hadFile = !isNew && src.type == "file" && cache.exists()
                if (!picked.exists() && !hadFile) {
                    Ui.toast(this, "Выберите файл плейлиста")
                    return
                }
            }
        }
        if (ep.isNotEmpty() && !ep.matches(Regex("(?i)https?://\\S+([,;\\s]+https?://\\S+)*"))) {
            Ui.toast(this, "Адрес телепрограммы должен начинаться с http:// или https://")
            epg.requestFocus()
            return
        }

        val oldKey = src.type + "\n" + src.url + "\n" + src.server + "\n" + src.user + "\n" + src.pass + "\n" + src.ua
        val oldEpg = src.epg
        src.type = type
        src.name = n
        src.url = if (type == "m3u") u else ""
        src.server = if (type == "xtream") sv else ""
        src.user = if (type == "xtream") us else ""
        src.pass = if (type == "xtream") pw else ""
        src.epg = ep
        src.ua = ag
        src.archive = archive
        val newKey = src.type + "\n" + src.url + "\n" + src.server + "\n" + src.user + "\n" + src.pass + "\n" + src.ua

        if (type == "file") {
            if (picked.exists()) {
                Store.delete(cache)
                if (!picked.renameTo(cache)) {
                    Ui.toast(this, "Не удалось сохранить файл")
                    return
                }
            }
        } else if (isNew || oldKey != newKey) {
            Store.delete(cache) // новая ссылка — скачаем каналы заново
        }
        if (ep != oldEpg) for (i in 0 until 5) Store.delete(st.epgFile(src, i))
        st.putSource(src)
        finish()
    }

    private companion object {
        const val PICK = 7
        val ARCH_IDS = arrayOf("auto", "shift", "flussonic", "xc", "none")
        val ARCH_NAMES = arrayOf("Автоматически", "Сдвиг (?utc=…&lutc=…)", "Flussonic", "Xtream Codes", "Не использовать")

        fun archName(id: String): String {
            val i = ARCH_IDS.indexOf(id)
            return ARCH_NAMES[if (i >= 0) i else 0]
        }
    }
}
