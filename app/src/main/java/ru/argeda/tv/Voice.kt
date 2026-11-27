package ru.argeda.tv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import java.util.Locale

/** Голосовой поиск канала: «Первый канал», «Матч ТВ», «пятый» не нужен — номер можно сказать цифрами. */
internal object Voice {
    private const val REQ = 7301

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите название канала")
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)

    private var avail: Boolean? = null

    /** Есть ли на устройстве распознавание речи (на части приставок его нет). */
    fun available(c: Context): Boolean = avail ?: try {
        @Suppress("DEPRECATION")
        c.packageManager.queryIntentActivities(intent(), 0).isNotEmpty()
    } catch (_: Exception) {
        false
    }.also { avail = it }

    fun listen(a: Activity) {
        try {
            @Suppress("DEPRECATION")
            a.startActivityForResult(intent(), REQ)
        } catch (_: Exception) {
            Ui.toast(a, "Голосовой поиск на этом устройстве недоступен")
        }
    }

    /** Что сказали (первый вариант распознавания); null — это не наш ответ или ничего не расслышали. */
    fun result(req: Int, res: Int, data: Intent?): String? {
        if (req != REQ || res != Activity.RESULT_OK) return null
        val l = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) ?: return null
        last = l.map { it.trim() }.filter { it.isNotEmpty() }
        return last.firstOrNull()
    }

    /** Все варианты распознавания последнего ответа: «Россия один» и «Россия 1» — оба пробуем. */
    private var last: List<String> = emptyList()

    /** Канал, название которого совпало со сказанным (без «HD», «канал», регистра); null — точного нет. */
    fun best(said: String, vis: List<Channel>): Channel? {
        val variants = (listOf(said) + last).distinct()
        for (v in variants) {
            val k = key(v, true)
            if (k.isEmpty()) continue
            vis.firstOrNull { key(it.name, true) == k }?.let { return it }
        }
        for (v in variants) {
            val k = key(v, false)
            if (k.length < 4) continue
            val hits = vis.filter { key(it.name, false).startsWith(k) }
            if (hits.size == 1) return hits[0]
        }
        return null
    }

    private val WORDS = mapOf(
        "один" to "1", "одна" to "1", "два" to "2", "три" to "3", "четыре" to "4", "пять" to "5",
        "шесть" to "6", "семь" to "7", "восемь" to "8", "девять" to "9", "десять" to "10"
    )

    /** loose — без «канал», «ТВ»: «Первый» = «Первый канал HD». */
    private fun key(s: String, loose: Boolean): String {
        val w = s.lowercase(Locale.ROOT).replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
            .map { WORDS[it] ?: it }
            .filter { it !in QUALITY && !(loose && it in GENERIC) }
        return w.joinToString("")
    }

    private val QUALITY = setOf("hd", "fhd", "uhd", "sd", "4k", "1080p", "720p", "576p", "480p", "orig")
    private val GENERIC = setOf("канал", "тв", "tv", "телеканал")
}
