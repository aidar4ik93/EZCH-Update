package com.example.homeezch

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Without a computer: ends cached background processes of other apps (Android allows only that). */
internal object QuickCleanup {
    fun run(context: Context): String {
        val am = requireNotNull(context.getSystemService(ActivityManager::class.java))
        fun free() = ActivityManager.MemoryInfo().also(am::getMemoryInfo).availMem / (1024L * 1024L)
        val before = free()
        val keep = setOf(context.packageName, "android", "com.android.systemui", "com.google.android.gms")
        @Suppress("DEPRECATION") val packages = context.packageManager.getInstalledApplications(0)
            .map { it.packageName }.filter { it !in keep }
        var touched = 0
        for (pkg in packages) runCatching { am.killBackgroundProcesses(pkg); touched++ }
        Thread.sleep(1500)
        val after = free()
        EzchDiag.log("HARDWARE", "Быстрая очистка: свободно $before → $after МБ")
        return "Свободная память: $before → $after МБ (+${(after - before).coerceAtLeast(0)} МБ)"
    }
}

class DeviceOptimizationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val guard = Profiles.guard(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                var unlocked by remember { mutableStateOf(guard == null) }
                if (!unlocked && guard != null) PinPrompt(guard, ::finish) { unlocked = true }
                else Content()
            }
        }
    }
    @Composable private fun Content() {
        var result by remember { mutableStateOf<HardwareInspectionResult?>(null) }
        var status by remember { mutableStateOf("Проверяю оборудование…") }
        var busy by remember { mutableStateOf(false) }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var cleanup by remember { mutableStateOf(true) }
        var animations by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) {
            runCatching { withContext(Dispatchers.IO) { HardwareInspection.inspect(this@DeviceOptimizationActivity) } }
                .onSuccess { report ->
                    result = report
                    // Safe items (demo, feedback, ad tracking) are ticked; the owner decides on the rest.
                    selected = report.optional.filter { it.item.recommended }.map { it.item.packageName }.toSet()
                    status = "Проверка завершена. Системные изменения не выполнялись."
                }
                .onFailure { status = "Не удалось завершить проверку: ${it.javaClass.simpleName}" }
        }
        Column(Modifier.fillMaxSize().background(Color(0xFF091322)).padding(32.dp).verticalScroll(rememberScrollState())) {
            Text("Устройство и оптимизация", style = MaterialTheme.typography.headlineMedium)
            Text(status, Modifier.padding(vertical = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = ::finish) { Text("На рабочий стол") }
                Button(enabled = !busy, onClick = {
                    busy = true; status = "Освобождаю память…"
                    scope.launch {
                        status = runCatching { withContext(Dispatchers.IO) { QuickCleanup.run(this@DeviceOptimizationActivity) } }
                            .getOrElse { "Не удалось очистить: ${it.javaClass.simpleName}" }
                        busy = false
                    }
                }) { Text("Освободить память сейчас") }
            }
            result?.let { report ->
                Text(report.text, Modifier.padding(vertical = 16.dp))

                Text("Универсальная чистка", style = MaterialTheme.typography.titleLarge)
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Checkbox(checked = cleanup, enabled = !busy, onCheckedChange = { cleanup = it })
                    Column(Modifier.weight(1f)) {
                        Text("Очистить кэш приложений и фоновые процессы")
                        Text("Кэш (временные файлы) всех приложений и закрытие фоновых процессов. Данные, входы в аккаунты и настройки не удаляются.")
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Checkbox(checked = animations, enabled = !busy && cleanup, onCheckedChange = { animations = it })
                    Column(Modifier.weight(1f)) {
                        Text("Ускорить анимацию системы (0,5×)")
                        Text("Окна и переходы открываются вдвое быстрее. Команда отмены вернёт ваши прежние значения.")
                    }
                }

                Text("Реклама, рекомендации и ненужные службы", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
                if (report.optional.isEmpty()) Text("На этом телевизоре рекламные и необязательные модули из списка EZCH не найдены или уже отключены. Системные службы наугад не отключаются.")
                for (item in report.optional) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Checkbox(checked = item.item.packageName in selected, enabled = !busy, onCheckedChange = { yes ->
                            selected = if (yes) selected + item.item.packageName else selected - item.item.packageName
                        })
                        Column(Modifier.weight(1f)) {
                            Text(item.item.title + if (item.item.recommended) " · рекомендуется" else "")
                            Text(item.item.packageName); Text(item.item.effect)
                        }
                    }
                }
                Text("Android не разрешает приложениям отключать системные модули. Нажмите кнопку ниже — EZCH сохранит готовые команды " +
                    "и команды отмены в папку Download/EZCH. Выполните их через ADB с компьютера или приложение atvTools → Shell:\n" +
                    "sh /sdcard/Download/EZCH/<имя файла>.sh\n" +
                    "Команды работают только на этом телевизоре и не трогают главный экран, ТВ-тюнер, HDMI, звук и Google Play.",
                    Modifier.padding(vertical = 12.dp))
                Button(enabled = !busy && (selected.isNotEmpty() || cleanup), onClick = {
                    busy = true
                    scope.launch {
                        status = runCatching { withContext(Dispatchers.IO) {
                            HardwareInspection.export(this@DeviceOptimizationActivity, report,
                                report.optional.filter { it.item.packageName in selected }, cleanup, cleanup && animations)
                        } }.fold({ "Сохранены отчёт и команды:\n$it" }, { "Не удалось сохранить: ${it.javaClass.simpleName}" })
                        busy = false
                    }
                }) { Text("Сохранить команды чистки и отмены") }
            }
        }
    }
}
