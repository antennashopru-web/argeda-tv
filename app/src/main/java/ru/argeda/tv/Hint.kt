package ru.argeda.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Context

/** Подсказка при первом запуске: как добавить плейлист и что делают кнопки пульта. Показывается один раз. */
internal object Hint {
    private const val PREF = "hintShown"

    fun maybeShow(a: Activity, hasPlaylist: Boolean, addPlaylist: () -> Unit) {
        val p = a.getSharedPreferences("argeda", Context.MODE_PRIVATE)
        if (p.getBoolean(PREF, false) || a.isFinishing) return
        p.edit().putBoolean(PREF, true).apply()
        show(a, hasPlaylist, addPlaylist)
    }

    fun show(a: Activity, hasPlaylist: Boolean, addPlaylist: () -> Unit) {
        val tv = Ui.isTv(a)
        val s = StringBuilder()
        if (!hasPlaylist) {
            s.append("Как начать\n")
            s.append("• Нажмите «Добавить плейлист» и вставьте ссылку M3U от вашего IPTV-провайдера, ")
            s.append("или введите логин Xtream, или выберите файл плейлиста.\n")
            if (tv) s.append("• Ссылку удобно ввести с телефона: на экране добавления плейлиста есть адрес для этого.\n")
            s.append('\n')
        }
        if (tv) {
            s.append("Кнопки пульта при просмотре\n")
            s.append("• ▲ ▼ — следующий и предыдущий канал\n")
            s.append("• ◀ или OK — список каналов\n")
            s.append("• ▶ — что идёт сейчас и кнопки (избранное, звук, качество)\n")
            s.append("• Цифры — канал по номеру\n")
            s.append("• Кнопка телепрограммы (Guide) — передачи канала и архив\n")
            s.append("• Назад — к списку каналов\n\n")
            s.append("В списке каналов долгое нажатие OK — добавить в «Моё» или скрыть канал. ")
            s.append("Настройки — значок шестерёнки вверху справа.")
        } else {
            s.append("При просмотре\n")
            s.append("• Смахните вверх или вниз — другой канал\n")
            s.append("• Смахните вправо — список каналов, влево — телепрограмма\n")
            s.append("• Коснитесь экрана — что идёт сейчас и кнопки\n\n")
            s.append("Долгое нажатие на канал — добавить в «Моё» или скрыть. ")
            s.append("Настройки — значок шестерёнки вверху справа.")
        }
        val b = AlertDialog.Builder(a).setTitle("Добро пожаловать в ARGEDA TV").setMessage(s.toString())
        if (!hasPlaylist) {
            b.setPositiveButton("Добавить плейлист") { _, _ -> addPlaylist() }
            b.setNegativeButton("Позже", null)
        } else b.setPositiveButton("Понятно", null)
        b.show()
    }
}
