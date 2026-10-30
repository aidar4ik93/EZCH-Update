package com.example.homeezch

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Learns the codes Android actually delivers, without assuming vendor HID mappings. */
class RemoteSetupActivity : Activity() {
    private lateinit var report: TextView
    private lateinit var summary: TextView
    private var target: RemoteAction? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(36, 20, 36, 20)
            setBackgroundColor(0xff101b2b.toInt())
        }
        fun text(value: String): TextView = TextView(this).apply {
            text = value; textSize = 17f; setTextColor(0xffeeeeee.toInt()); column.addView(this)
        }
        text("Проверка и настройка пульта").textSize = 24f
        text("Выберите действие, затем нажмите нужную специальную кнопку. Стрелки, OK, Home, Назад, громкость и голос сохраняют своё назначение. Назад отменяет обучение.")
        report = text("Нажмите кнопку пульта: здесь появится её код Android.")
        summary = text(RemoteButtons.summary(this))
        RemoteAction.entries.forEach { action ->
            column.addView(Button(this).apply {
                text = "Назначить: ${action.label}"
                setOnClickListener { target = action; report.text = "Нажмите кнопку для действия «${action.label}»." }
            })
        }
        column.addView(Button(this).apply {
            text = "Сбросить назначения"
            setOnClickListener { target = null; RemoteButtons.clear(this@RemoteSetupActivity); summary.text = RemoteButtons.summary(this@RemoteSetupActivity) }
        })
        text("Для работы назначений поверх других приложений включите службу «Кнопка Home» в настройках EZCH. Если нажатие не появляется здесь или разные кнопки дают одинаковые коды, их различение требует поддержки прошивки. Ракете можно назначить управление приложениями; это не очистка памяти и не ускорение.")
        column.addView(Button(this).apply { text = "Закрыть"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(column) })
    }
    override fun onResume() { super.onResume(); RemoteButtons.learning = true }
    override fun onPause() { RemoteButtons.learning = false; target = null; super.onPause() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && target != null) {
            if (event.action == KeyEvent.ACTION_UP) { target = null; report.text = "Назначение отменено." }
            return true
        }
        val id = RemoteButtons.identity(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            report.text = "${event.device?.name ?: "Устройство"}: ${KeyEvent.keyCodeToString(event.keyCode)}, scan=${event.scanCode}"
            val action = target
            if (action != null && id?.key == 0 && id.scan == 240) {
                report.append("\nПрошивка объединила несколько кнопок пульта в этот неизвестный код. Назначение недоступно: требуется поддержка пульта прошивкой.")
            }
            if (action != null && id?.assignable() == true) {
                val saved = RemoteButtons.save(this, id, action)
                report.append(if (saved) "\nНазначено: ${action.label}" else
                    "\nЭтот код уже назначен другому действию. Возможно, прошивка передаёт разные кнопки одинаково. Назначения не изменены.")
                summary.text = RemoteButtons.summary(this)
                target = null
            }
        }
        if (id?.assignable() == true) return true
        return super.dispatchKeyEvent(event)
    }
}
