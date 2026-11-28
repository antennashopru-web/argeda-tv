package ru.argeda.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/**
 * Где хранятся записи: в памяти устройства или на флешке (USB-накопителе, карте памяти).
 * Флешку приложение видит двумя способами:
 *  - своя папка на флешке, которую система даёт без всяких разрешений (на большинстве приставок);
 *  - папка, выбранная в системном окне выбора папок (если флешки нет в списке).
 * Если выбранной флешки нет (вынули), на ней мало места — запись идёт в память устройства.
 */
internal object RecStore {
    /** "" — память устройства; "v:<путь>" — своя папка на флешке; "t:<uri>" — выбранная папка. */
    private const val PREF = "recPlace"
    private const val TREE = "recTree"
    const val REQ_TREE = 7201

    /** На флешках с FAT32 файл не может быть больше 4 ГБ — длинная запись делится на части. Кратно 188 (пакет MPEG-TS). */
    const val PART = 188L * 21_000_000L
    /** Меньше — на флешку не пишем, пишем в память устройства. */
    const val MIN_FREE = 200L shl 20
    /** Меньше — запись останавливается, чтобы не забить память до конца. */
    const val STOP_FREE = 30L shl 20

    class Place(@JvmField val id: String, @JvmField val title: String, @JvmField val usb: Boolean,
                @JvmField val dir: File?, @JvmField val tree: Uri?)

    class Rec(@JvmField val name: String, @JvmField val size: Long, @JvmField val time: Long,
              @JvmField val uri: String, @JvmField val usb: Boolean, @JvmField val file: File?, @JvmField val doc: Uri?)

    /** Открытый файл записи. free — сколько осталось места (-1 — неизвестно). */
    class Part(@JvmField val out: OutputStream, @JvmField val uri: String, val free: () -> Long, val delete: () -> Unit)

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("argeda", Context.MODE_PRIVATE)

    fun internalDir(c: Context): File {
        val d = File(c.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: c.filesDir, "ARGEDA")
        d.mkdirs()
        return d
    }

    fun internal(c: Context) = Place("", "Память устройства", false, internalDir(c), null)

    /** Флешки и карты памяти, на которых у приложения есть своя папка. */
    fun volumes(c: Context): List<Place> {
        val r = ArrayList<Place>()
        val dirs = try { c.getExternalFilesDirs(Environment.DIRECTORY_MOVIES) } catch (_: Exception) { emptyArray<File?>() }
        for (i in 1 until dirs.size) {
            val d = dirs[i] ?: continue
            try {
                if (Environment.getExternalStorageState(d) != Environment.MEDIA_MOUNTED) continue
                val dir = File(d, "ARGEDA")
                dir.mkdirs()
                if (!dir.isDirectory || !dir.canWrite()) continue
                r.add(Place("v:" + dir.absolutePath, label(c, d), true, dir, null))
            } catch (_: Exception) {
            }
        }
        return r
    }

    private fun label(c: Context, d: File): String {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                val sm = c.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                val s = sm.getStorageVolume(d)?.getDescription(c)
                if (!s.isNullOrBlank()) return "Флешка: " + s.trim()
            } catch (_: Exception) {
            }
        }
        return "Флешка"
    }

    private fun savedTree(c: Context): Uri? = prefs(c).getString(TREE, null)?.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }

    private fun treeDoc(t: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))

    /** Название выбранной папки или null, если её сейчас нет (флешку вынули). */
    private fun treeName(c: Context, t: Uri): String? = try {
        c.contentResolver.query(treeDoc(t), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) (it.getString(0) ?: "") else null
        }
    } catch (_: Exception) {
        null
    }

    private fun treePlace(c: Context): Place? {
        val t = savedTree(c) ?: return null
        val n = treeName(c, t) ?: return null
        return Place("t:$t", if (n.isEmpty()) "Выбранная папка на флешке" else "Папка «$n» на флешке", true, null, t)
    }

    /** Все места, куда сейчас можно записывать. */
    fun places(c: Context): List<Place> {
        val r = ArrayList<Place>()
        r.add(internal(c))
        r.addAll(volumes(c))
        treePlace(c)?.let { r.add(it) }
        return r
    }

    fun chosenId(c: Context): String = prefs(c).getString(PREF, "") ?: ""

    fun choose(c: Context, p: Place) {
        prefs(c).edit().putString(PREF, p.id).apply()
    }

    /** Куда записывать сейчас; null — выбрана флешка, но её нет. */
    fun target(c: Context): Place? {
        val id = chosenId(c)
        if (id.isEmpty()) return internal(c)
        if (id.startsWith("t:")) return treePlace(c)?.takeIf { it.id == id }
        return volumes(c).firstOrNull { it.id == id }
    }

    /** Для меню: куда идут записи. */
    fun chosenTitle(c: Context): String {
        if (chosenId(c).isEmpty()) return "Память устройства"
        return target(c)?.title ?: "Флешка (сейчас не вставлена)"
    }

    fun available(c: Context, p: Place): Boolean {
        val d = p.dir
        if (d != null) return try {
            (!p.usb || Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED) && d.isDirectory
        } catch (_: Exception) {
            false
        }
        val t = p.tree ?: return false
        return treeName(c, t) != null
    }

    fun free(p: Place): Long = p.dir?.let { try { it.usableSpace } catch (_: Exception) { -1L } } ?: -1L

    /** Создаёт файл записи с именем name в месте p. */
    @Throws(IOException::class)
    fun open(c: Context, p: Place, name: String): Part {
        val d = p.dir
        if (d != null) {
            d.mkdirs()
            val f = File(d, name)
            val out = FileOutputStream(f)
            return Part(out, Uri.fromFile(f).toString(), { try { d.usableSpace } catch (_: Exception) { -1L } }, { f.delete() })
        }
        val t = p.tree ?: throw IOException("флешка не найдена")
        val cr = c.contentResolver
        val made: Uri? = try {
            DocumentsContract.createDocument(cr, treeDoc(t), "application/octet-stream", name)
        } catch (e: Exception) {
            null
        }
        val doc = made ?: throw IOException("не удалось создать файл на флешке")
        val pfd = try {
            cr.openFileDescriptor(doc, "w")
        } catch (e: Exception) {
            null
        }
        if (pfd == null) {
            try { DocumentsContract.deleteDocument(cr, doc) } catch (_: Exception) {}
            throw IOException("не удалось открыть файл на флешке")
        }
        val out = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        val free = {
            try {
                val s = Os.fstatvfs(pfd.fileDescriptor)
                s.f_bavail * s.f_frsize
            } catch (_: Exception) {
                -1L
            }
        }
        return Part(out, doc.toString(), free, { try { DocumentsContract.deleteDocument(cr, doc) } catch (_: Exception) {} })
    }

    /** Все записи: в памяти устройства и на флешках. Новые сверху. */
    fun all(c: Context): List<Rec> {
        val r = ArrayList<Rec>()
        val inner = internalDir(c)
        val dirs = LinkedHashSet<File>()
        dirs.add(inner)
        for (v in volumes(c)) v.dir?.let { dirs.add(it) }
        for (d in dirs) {
            val fs = d.listFiles { f -> f.isFile && f.name.endsWith(".ts") } ?: continue
            for (f in fs) r.add(Rec(f.nameWithoutExtension, f.length(), f.lastModified(), Uri.fromFile(f).toString(), d != inner, f, null))
        }
        val t = savedTree(c)
        if (t != null) {
            try {
                val kids = DocumentsContract.buildChildDocumentsUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
                val cols = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
                )
                c.contentResolver.query(kids, cols, null, null, null)?.use { q ->
                    while (q.moveToNext()) {
                        val n = q.getString(1) ?: continue
                        if (!n.lowercase(Locale.ROOT).endsWith(".ts")) continue
                        val doc = DocumentsContract.buildDocumentUriUsingTree(t, q.getString(0))
                        val size = if (q.isNull(2)) 0L else q.getLong(2)
                        val time = if (q.isNull(3)) 0L else q.getLong(3)
                        r.add(Rec(n.substring(0, n.length - 3), size, time, doc.toString(), true, null, doc))
                    }
                }
            } catch (_: Exception) {
                // флешку вынули — её записей сейчас просто нет в списке
            }
        }
        return r.sortedByDescending { it.time }
    }

    fun delete(c: Context, r: Rec): Boolean {
        r.file?.let { return it.delete() }
        val d = r.doc ?: return false
        return try { DocumentsContract.deleteDocument(c.contentResolver, d) } catch (_: Exception) { false }
    }

    /** Системное окно выбора папки на флешке. false — на устройстве такого окна нет. */
    fun pick(a: Activity): Boolean {
        var i: Intent? = null
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val sm = a.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                i = sm.storageVolumes.firstOrNull { it.isRemovable && it.state == Environment.MEDIA_MOUNTED }?.createOpenDocumentTreeIntent()
            } catch (_: Exception) {
            }
        }
        if (i == null) i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        return try {
            @Suppress("DEPRECATION")
            a.startActivityForResult(i, REQ_TREE)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Вызывать из onActivityResult; true — это был ответ на наш запрос. */
    fun result(a: Activity, req: Int, res: Int, data: Intent?): Boolean {
        if (req != REQ_TREE) return false
        val t = data?.data
        if (res != Activity.RESULT_OK || t == null) return true
        try {
            a.contentResolver.takePersistableUriPermission(t, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {
        }
        val old = savedTree(a)
        if (old != null && old != t) {
            try {
                a.contentResolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        prefs(a).edit().putString(TREE, t.toString()).apply()
        val p = treePlace(a)
        if (p == null) {
            Ui.toast(a, "В эту папку записывать нельзя. Выберите папку на флешке")
            return true
        }
        choose(a, p)
        Ui.toast(a, "Записи будут сохраняться: " + p.title)
        return true
    }

    fun gb(bytes: Long): String = String.format(Locale.ROOT, "%.1f", bytes / 1073741824.0).replace('.', ',') + " ГБ"
}

/**
 * Файл записи на выбранном месте. Сам делит запись на части меньше 4 ГБ (FAT32) и следит,
 * чтобы не кончилось место: если места почти нет, запись останавливается, записанное остаётся.
 */
internal class RecSink private constructor(private val c: Context, @JvmField val place: RecStore.Place,
                                           private val base: String, private val job: Recorder.Job) : OutputStream() {
    private var part: RecStore.Part? = null
    private var n = 1
    private var partBytes = 0L
    private var sinceCheck = 0L
    @JvmField var full = false

    @Throws(IOException::class)
    private fun next() {
        part?.out?.close()
        val name = if (n == 1) "$base.ts" else "$base (часть $n).ts"
        val p = RecStore.open(c, place, name)
        n++
        part = p
        partBytes = 0
        job.uri = p.uri
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var l = len
        while (l > 0) {
            if (partBytes >= RecStore.PART) next()
            val k = minOf(l.toLong(), RecStore.PART - partBytes).toInt()
            part!!.out.write(b, o, k)
            partBytes += k
            o += k
            l -= k
        }
        sinceCheck += len
        if (sinceCheck >= (16 shl 20)) {
            sinceCheck = 0
            val f = part!!.free()
            if (f in 0 until RecStore.STOP_FREE) {
                full = true
                throw IOException("кончилось место")
            }
        }
    }

    override fun flush() {
        part?.out?.flush()
    }

    override fun close() {
        val p = part ?: return
        try {
            p.out.close()
        } catch (_: IOException) {
        }
        if (partBytes == 0L) p.delete()
        part = null
    }

    /** Понятная причина остановки. */
    fun why(e: Exception): String {
        val m = (e.message ?: "").lowercase(Locale.ROOT)
        val nospace = full || m.contains("enospc") || m.contains("no space")
        if (place.usb && !RecStore.available(c, place)) return "флешку вынули"
        if (nospace) return if (place.usb) "на флешке кончилось место" else "в памяти устройства кончилось место"
        if (place.usb && (m.contains("eio") || m.contains("i/o error") || m.contains("erofs") || m.contains("read-only"))) return "флешка не отвечает или защищена от записи"
        return Store.describe(e)
    }

    companion object {
        /** Открывает запись на выбранном месте; если флешки нет или на ней мало места — в памяти устройства. */
        @Throws(IOException::class)
        fun open(c: Context, base: String, job: Recorder.Job, warn: (String) -> Unit): RecSink {
            val t = RecStore.target(c)
            if (t == null) {
                warn("Флешка не найдена — запись идёт в память устройства")
            } else if (t.usb) {
                val s = RecSink(c, t, base, job)
                try {
                    s.next()
                    val f = s.part!!.free()
                    if (f !in 0 until RecStore.MIN_FREE) return s
                    s.close()
                    warn("На флешке мало места (" + RecStore.gb(f) + ") — запись идёт в память устройства")
                } catch (e: Exception) {
                    try { s.close() } catch (_: Exception) {}
                    warn("На флешку записать не удалось — запись идёт в память устройства")
                }
            }
            val p = RecStore.internal(c)
            val f = RecStore.free(p)
            if (f in 0 until RecStore.MIN_FREE) {
                throw IOException(
                    "в памяти устройства мало места (" + RecStore.gb(f) + "). " +
                        "Вставьте флешку и выберите её: Настройки → «Записи» → «Куда записывать»"
                )
            }
            val s = RecSink(c, p, base, job)
            s.next()
            return s
        }
    }
}
