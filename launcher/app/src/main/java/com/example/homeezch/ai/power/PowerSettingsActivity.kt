package com.example.homeezch.ai.power

import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.example.homeezch.*
import com.example.homeezch.ai.SmartHomeActivity
import com.example.homeezch.ai.AiSettingsTicket
import com.example.homeezch.home.SmartHub
import kotlinx.coroutines.launch

/** D-pad friendly, guarded just like the assistant settings. No permission is enabled automatically. */
class PowerSettingsActivity : ComponentActivity() {
    private var unlocked by mutableStateOf(false)
    private var column: LinearLayout? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val profile = Profiles.guard(this)
        unlocked = profile == null || AiSettingsTicket.consume(this, intent.getStringExtra("authorization"))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                if (unlocked || profile == null) AndroidView(factory = { build() })
                else PinPrompt(profile, ::finish) { unlocked = true }
            }
        }
    }
    override fun onResume() { super.onResume(); if (column != null) populate() }
    private fun build(): android.view.View = ScrollView(this).apply {
        setBackgroundColor(Color.rgb(6, 12, 24))
        addView(LinearLayout(this@PowerSettingsActivity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(40, 24, 40, 28); column = this
        }); populate()
    }
    private fun populate() {
        val layout = column ?: return
        layout.removeAllViews()
        val prefs = getSharedPreferences("eva_power", MODE_PRIVATE)
        fun text(value: String, size: Float = 17f) { layout.addView(TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE); setPadding(10, 12, 10, 12) }) }
        fun button(value: String, action: () -> Unit) { layout.addView(Button(this).apply { text = value; isAllCaps = false; textSize = 17f; minHeight = 64; setOnClickListener { action() } }) }
        text("Питание телевизора", 27f)
        text("«Ева, выключи телевизор» и «перезагрузись» — только после «да».\n«Выключи через полчаса», «отмени таймер», «сколько осталось до выключения».")
        val cap = PowerController(this).capabilities()
        text("Специальные возможности: ${if (cap.accessibility) "✓ доступны" else "не включены"}   •   Администратор: ${if (cap.admin) "✓ доступен" else "не включён"}\nИК-устройство: ${if (cap.infrared) "выбрано" else "не выбрано"}   •   root: ${if (cap.root) "найден su (права не проверялись)" else "не обнаружен"}\nЛокальный ADB: не подключён")
        text("Как выключать телевизор", 21f)
        val ir = prefs.getBoolean("ir_mode", false)
        button("${if (!ir) "✓ " else ""}Сон устройства (по умолчанию)") { prefs.edit().putBoolean("ir_mode", false).apply(); populate() }
        button("${if (ir) "✓ " else ""}Через ИК-пульт · приставка остаётся включённой") { prefs.edit().putBoolean("ir_mode", true).apply(); populate() }
        text("В режиме сна микрофон выключен: разбудить встроенный в Haier лаунчер голосом нельзя. На Homatics внешний ИК-пульт управляет телевизором, а приставка остаётся включённой. Чтобы затем включить ТВ голосом, оставьте открытым рабочий стол EZCH и включите отклик на «Ева». Яндекс/Tuya требуют сеть и подключённый аккаунт.")
        button("Выбрать телевизор из «Умного дома»") {
            val loading = TextView(this).apply { text = "Ищу телевизоры…"; setTextColor(Color.CYAN) }; layout.addView(loading)
            lifecycleScope.launch {
                val devices = runCatching { SmartHub.devices(this@PowerSettingsActivity).filter(PowerController::eligible) }.getOrDefault(emptyList())
                loading.text = if (devices.isEmpty()) "Телевизоры не найдены. Сначала подключите Яндекс, Tuya или Home Assistant в «Умном доме»." else "Выберите телевизор (команда питания сейчас не отправляется):"
                devices.forEach { d -> button("${d.name} · ${d.provider}") { prefs.edit().putString("ir_device", "${d.provider}|${d.id}").apply(); populate() } }
            }
        }
        button("Открыть «Умный дом»") { startActivity(Intent(this, SmartHomeActivity::class.java)) }
        text("Таймер: ${SleepTimer.minutesLeft(this)?.let { "осталось примерно $it мин." } ?: "не установлен"}. После перезагрузки устройства таймер отменяется.")
        button("Отменить таймер сна") { SleepTimer.cancel(this); populate() }
        button("Разрешить точные таймеры в Android") {
            if (android.os.Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName")))
            else Toast.makeText(this, "Точные таймеры уже доступны", Toast.LENGTH_SHORT).show()
        }
        button("Специальные возможности · включить Ai EvA") { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("Администратор · запасной способ сна") {
            open(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, EvaDeviceAdminReceiver::class.java))
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Ева сможет блокировать экран по вашей голосовой команде или таймеру"))
        }
        layout.addView(Switch(this).apply {
            text = "Запасной способ через root (только на устройстве с su)"; setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean("root_fallback", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("root_fallback", checked).apply() }
        })
        button("Назад", ::finish)
    }
    private fun open(intent: Intent) { runCatching { startActivity(intent) }.onFailure { Toast.makeText(this, "В прошивке нет этого экрана настроек", Toast.LENGTH_LONG).show() } }
}
