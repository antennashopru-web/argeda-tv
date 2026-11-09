package ru.argeda.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.Executors

/** Логотипы каналов: память + кэш на диске; пока логотипа нет, видны буквы названия. */
internal object Logos {
    private var mem: LruCache<String, Bitmap>? = null
    private lateinit var dir: File
    private val pool = Executors.newFixedThreadPool(3)
    private val bad: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val main = Handler(Looper.getMainLooper())

    @JvmStatic
    @Synchronized
    fun init(c: Context) {
        if (mem != null) return
        dir = File(c.cacheDir, "logos")
        dir.mkdirs()
        val kb = minOf(24L * 1024, Runtime.getRuntime().maxMemory() / 1024 / 12).toInt()
        mem = object : LruCache<String, Bitmap>(kb) {
            override fun sizeOf(key: String, value: Bitmap): Int = maxOf(1, value.byteCount / 1024)
        }
    }

    @JvmStatic
    fun show(iv: ImageView, initials: TextView, url: String) {
        val cache = mem!!
        iv.tag = url
        val b = if (url.isEmpty()) null else cache.get(url)
        iv.setImageBitmap(b)
        initials.visibility = if (b == null) View.VISIBLE else View.INVISIBLE
        if (b != null || bad.contains(url) || !(url.startsWith("http://") || url.startsWith("https://"))) return
        pool.execute {
            if (url != iv.tag) return@execute // строка уже показывает другой канал
            val bm = fetch(url)
            if (bm == null) {
                bad.add(url)
                return@execute
            }
            cache.put(url, bm)
            main.post {
                if (url == iv.tag) {
                    iv.setImageBitmap(bm)
                    initials.visibility = View.INVISIBLE
                }
            }
        }
    }

    private fun fetch(url: String): Bitmap? {
        val f = File(dir, Integer.toHexString(url.hashCode()) + "_" + url.length)
        return try {
            val b: ByteArray
            if (f.exists()) {
                b = FileInputStream(f).use { Net.readAll(it, 2 shl 20) }
            } else {
                b = Net.get(url, null, 2 shl 20)
                FileOutputStream(f).use { it.write(b) }
            }
            val o = BitmapFactory.Options()
            o.inJustDecodeBounds = true
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) {
                f.delete()
                return null
            }
            var s = 1
            while (o.outWidth / (s * 2) >= 160 && o.outHeight / (s * 2) >= 90) s *= 2
            val d = BitmapFactory.Options()
            d.inSampleSize = s
            BitmapFactory.decodeByteArray(b, 0, b.size, d)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }
}
