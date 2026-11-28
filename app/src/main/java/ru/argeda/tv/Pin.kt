package ru.argeda.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * PIN-код: один на всё приложение (каналы 18+, настройки, детский режим).
 * Каналы 18+ всегда видны в списке, но смотреть, записывать и показывать их на телевизоре
 * можно только после PIN-кода. Код спрашивается один раз — до выхода из приложения.
 */
internal object Pin {
    /** Канал 18+, который ещё не открыт PIN-кодом. */
    @JvmStatic
    fun locked(st: Store, c: Channel?): Boolean = c != null && c.adult && !st.showAdult

    /** Если канал закрыт — спросить PIN-код (или предложить его придумать), потом выполнить [then]. */
    fun guard(a: Activity, c: Channel?, then: () -> Unit) {
        if (locked(Store.get(a), c)) unlock(a, then) else then()
    }

    /** Открыть каналы 18+ до выхода из приложения. */
    fun unlock(a: Activity, then: () -> Unit) {
        val st = Store.get(a)
        if (st.showAdult) {
            then()
            return
        }
        if (st.kids()) {
            Ui.toast(a, "В детском режиме каналы 18+ недоступны")
            return
        }
        if (!st.hasPin()) {
            ask(a, "Канал 18+. Придумайте PIN-код (4–8 цифр)") { pin ->
                if (pin.length < 4) {
                    Ui.toast(a, "PIN-код — не меньше 4 цифр")
                    return@ask
                }
                st.setPin(pin)
                st.setShowAdult(true)
                Ui.toast(a, "PIN-код сохранён. Каналы 18+ открыты до выхода из приложения")
                then()
            }
            return
        }
        ask(a, "Канал 18+. Введите PIN-код") { pin ->
            if (!st.checkPin(pin)) {
                Ui.toast(a, "Неверный PIN-код")
                return@ask
            }
            st.setShowAdult(true)
            then()
        }
    }

    /** Окно ввода PIN-кода: цифры скрыты, OK на клавиатуре нажимает «OK». */
    fun ask(a: Activity, title: String, cb: (String) -> Unit) {
        if (a.isFinishing) return
        val e = EditText(a)
        e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        e.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(8))
        e.gravity = Gravity.CENTER
        e.textSize = 24f
        val f = FrameLayout(a)
        val pad = Ui.dp(a, 24)
        f.setPadding(pad, Ui.dp(a, 8), pad, 0)
        f.addView(e)
        val d = AlertDialog.Builder(a).setTitle(title).setView(f)
            .setPositiveButton("OK") { _, _ -> cb(e.text.toString().trim()) }
            .setNegativeButton("Отмена", null).create()
        e.setOnEditorActionListener { _, _, _ ->
            d.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            true
        }
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        e.requestFocus()
    }
}
