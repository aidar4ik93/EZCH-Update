package com.example.homeezch.ir

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.homeezch.ai.Chip
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF060C18)
private val Card = Color(0xFF111E33)
private val Muted = Color(0xFF8CA0BD)
private val Violet = Color(0xFF9F8CFF)
private val Green = Color(0xFF7BE07B)
private val Red = Color(0xFFFF6B6B)
private val Cyan = Color(0xFF2EE6C5)
private val Orange = Color(0xFFFF9F43)

/**
 * EVA → Умный дом → Универсальный ИК-пульт (ТЗ §4–6): controllers found in the home network, virtual remotes,
 * learning buttons from the original remote, scenarios and export/import. Every status shown is a real answer
 * of the controller; nothing is displayed as connected or sent without it.
 */
class IrManagerActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Violet, surface = Card, background = Bg)) { IrScreen(onClose = ::finish) }
        }
    }
}

private fun localNetwork(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return BroadlinkDriver.localNetworks().isNotEmpty()
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
        BroadlinkDriver.localNetworks().isNotEmpty()
}

private sealed interface Sheet {
    data object Wizard : Sheet
    data class Editor(val deviceId: String) : Sheet
    data class Learn(val deviceId: String, val buttonId: String?) : Sheet
    data class ButtonMenu(val deviceId: String, val buttonId: String) : Sheet
    data class Scenario(val scenarioId: String?) : Sheet
    data object ManualIp : Sheet
}

@Composable
private fun IrScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by IrManager.data.collectAsState()
    LaunchedEffect(Unit) { IrManager.load(context) }
    var status by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<IrControllerInfo>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var checks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    fun search(host: String? = null) {
        if (searching) return
        if (!localNetwork(context)) { status = "Нет подключения к локальной сети (Wi-Fi или Ethernet). Поиск невозможен."; return }
        searching = true; status = if (host == null) "Ищу ИК-контроллеры в домашней сети…" else "Опрашиваю $host…"
        scope.launch {
            val list = runCatching { BroadlinkDriver.scan(context, host, if (host == null) 4_000 else 3_000) }.getOrDefault(emptyList())
            found = if (host == null) list else (found.filter { f -> list.none { it.mac == f.mac } } + list)
            searching = false
            status = when {
                list.isNotEmpty() -> "Найдено контроллеров: ${list.size}"
                host != null -> "По адресу $host контроллер BroadLink не ответил"
                else -> "Контроллеры не найдены. Проверьте, что контроллер в той же сети, или укажите IP вручную."
            }
        }
    }
    LaunchedEffect(Unit) { search() }

    val first = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF16123A), Bg))).padding(horizontal = 48.dp, vertical = 26.dp)
        .verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Универсальный ИК-пульт", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Text(status, color = Muted, fontSize = 13.sp, maxLines = 2, modifier = Modifier.weight(1f))
            Chip("✕ Закрыть", Muted) { onClose() }
        }
        Spacer(Modifier.height(16.dp))

        // ---- Controllers ----
        Section("Контроллеры")
        if (data.controllers.isEmpty()) Note("Подключённых контроллеров нет.")
        data.controllers.forEach { c ->
            val st = IrManager.status(c.mac)
            ControllerRow(c.name, "${c.model} · ${c.host} · ${c.mac}",
                checks[c.mac] ?: when (st) { true -> "● отвечает"; false -> "● не отвечает"; null -> "○ не проверялся" },
                if (st == false) Red else if (st == true) Green else Muted) {
                Chip("Проверить", Cyan) {
                    checks = checks + (c.mac to "проверяю…")
                    scope.launch {
                        val r = IrManager.check(context, c.mac)
                        checks = checks + (c.mac to if (r is IrResult.Ok) "● ${r.value}" else "● ${(r as IrResult.Fail).message}")
                    }
                }
                Chip("Отключить", Red) { IrManager.forget(context, c.mac); checks = checks - c.mac }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Найденные устройства", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        val fresh = found.filter { f -> data.controllers.none { it.mac == f.mac } }
        if (fresh.isEmpty() && !searching) Note(if (found.isEmpty()) "Ничего не найдено." else "Все найденные контроллеры уже подключены.")
        fresh.forEach { f ->
            val support = when {
                !f.knownModel -> "не поддерживается (тип 0x%04X не проверен)".format(f.devType)
                f.locked -> "заблокирован в приложении BroadLink/BestCon"
                else -> "совместим · не подключён"
            }
            ControllerRow(f.name, "${f.model} · IP ${f.host} · ${f.mac}", checks[f.mac] ?: support,
                if (!f.knownModel || f.locked) Orange else Muted) {
                if (f.knownModel && !f.locked) {
                    Chip("Подключить", Green) {
                        checks = checks + (f.mac to "подключаю…")
                        scope.launch {
                            val r = IrManager.connect(context, f)
                            checks = checks + (f.mac to if (r is IrResult.Ok) "● подключён" else "● ${(r as IrResult.Fail).message}")
                        }
                    }
                    Chip("Проверить", Cyan) {
                        checks = checks + (f.mac to "проверяю авторизацию…")
                        scope.launch {
                            val d = BroadlinkDriver(context.applicationContext)
                            val r = d.connect(f); d.disconnect()
                            checks = checks + (f.mac to if (r is IrResult.Ok) "● авторизация выполнена, контроллер отвечает" else "● ${(r as IrResult.Fail).message}")
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            Chip(if (searching) "Ищу…" else "🔍 Искать снова", Cyan, Modifier.focusRequester(first)) { search() }
            Chip("Указать IP вручную", Violet) { sheet = Sheet.ManualIp }
        }
        LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

        // ---- Remotes ----
        Spacer(Modifier.height(22.dp))
        Section("Мои пульты")
        if (data.devices.isEmpty()) Note("Пультов пока нет. Создайте пульт и обучите кнопки с родного пульта техники.")
        data.devices.groupBy { it.room.ifBlank { "Без комнаты" } }.forEach { (room, list) ->
            Text(room, color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            list.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { d -> Chip("${d.category.icon} ${d.name} · ${d.buttons.size}", Violet) { sheet = Sheet.Editor(d.id) } }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            Chip("＋ Новый пульт", Green) {
                if (data.controllers.isEmpty()) status = "Сначала подключите контроллер" else sheet = Sheet.Wizard
            }
        }

        // ---- Scenarios ----
        Spacer(Modifier.height(22.dp))
        Section("Сценарии")
        if (data.scenarios.isEmpty()) Note("Сценарий — несколько нажатий подряд одной фразой: «Ева, кино» → включить проектор, саундбар, вход HDMI.")
        data.scenarios.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                row.forEach { s -> Chip("✨ ${s.name} · ${s.steps.size}", Orange) { sheet = Sheet.Scenario(s.id) } }
            }
        }
        Chip("＋ Новый сценарий", Green, Modifier.padding(top = 6.dp)) {
            if (data.devices.none { it.buttons.isNotEmpty() }) status = "Сначала обучите хотя бы одну кнопку" else sheet = Sheet.Scenario(null)
        }

        // ---- Export / import ----
        Spacer(Modifier.height(22.dp))
        Section("Резервная копия")
        Note("Файл: ${IrManager.backupFile(context).absolutePath}. Обученные сигналы хранятся на приставке и работают без интернета.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Экспорт", Cyan) {
                status = IrManager.export(context).fold({ "Сохранено: ${it.absolutePath}" }, { "Экспорт не удался: ${it.message}" })
            }
            Chip("Импорт", Violet) {
                status = IrManager.importFile(context).fold({ "Импортировано: пультов ${it.devices.size}, сценариев ${it.scenarios.size}" }, { "Импорт не удался: ${it.message}" })
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    when (val s = sheet) {
        null -> {}
        Sheet.ManualIp -> ManualIpDialog(onDismiss = { sheet = null }) { ip -> sheet = null; search(ip) }
        Sheet.Wizard -> WizardDialog(data, onDismiss = { sheet = null }) { device ->
            IrManager.update(context) { it.copy(devices = it.devices + device) }
            sheet = Sheet.Editor(device.id)
        }
        is Sheet.Editor -> data.device(s.deviceId)?.let { d ->
            EditorDialog(d, data, onDismiss = { sheet = null }, onButton = { sheet = Sheet.ButtonMenu(d.id, it) },
                onAdd = { sheet = Sheet.Learn(d.id, null) }, onStatus = { status = it })
        } ?: run { sheet = null }
        is Sheet.ButtonMenu -> data.device(s.deviceId)?.let { d ->
            d.buttons.firstOrNull { it.id == s.buttonId }?.let { b ->
                ButtonDialog(d, b, onDismiss = { sheet = Sheet.Editor(d.id) }, onRelearn = { sheet = Sheet.Learn(d.id, b.id) })
            }
        } ?: run { sheet = null }
        is Sheet.Learn -> data.device(s.deviceId)?.let { d ->
            LearnDialog(d, d.buttons.firstOrNull { it.id == s.buttonId }, onDone = { sheet = Sheet.Editor(d.id) })
        } ?: run { sheet = null }
        is Sheet.Scenario -> ScenarioDialog(data, data.scenarios.firstOrNull { it.id == s.scenarioId }, onDismiss = { sheet = null })
    }
}

@Composable private fun Section(text: String) = Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
@Composable private fun Note(text: String) = Text(text, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(vertical = 4.dp))

@Composable
private fun ControllerRow(title: String, subtitle: String, state: String, stateColor: Color, actions: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(bottom = 8.dp).widthIn(max = 1100.dp).background(Card, RoundedCornerShape(14.dp))
            .border(1.dp, Violet.copy(alpha = .35f), RoundedCornerShape(14.dp)).padding(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 12.sp)
            Text(state, color = stateColor, fontSize = 12.sp)
        }
        actions()
    }
}

@Composable
private fun Panel(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 760.dp).background(Card, RoundedCornerShape(22.dp)).border(1.dp, Violet.copy(alpha = .6f), RoundedCornerShape(22.dp))
            .padding(22.dp).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable private fun Title(text: String) = Text(text, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))

@Composable
private fun Field(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = modifier.fillMaxWidth().padding(bottom = 8.dp))

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(content: @Composable () -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) { content() }

@Composable
private fun ManualIpDialog(onDismiss: () -> Unit, onSearch: (String) -> Unit) {
    var ip by remember { mutableStateOf("192.168.1.") }
    val valid = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").matchEntire(ip.trim())?.groupValues?.drop(1)?.all { it.toInt() in 0..255 } == true
    Panel(onDismiss) {
        Title("IP-адрес контроллера")
        Note("Адрес виден в приложении BroadLink/BestCon (свойства устройства) или в списке клиентов роутера.")
        Field("IP-адрес", ip) { ip = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Найти", if (valid) Green else Muted) { if (valid) onSearch(ip.trim()) }
            Chip("Отмена", Muted) { onDismiss() }
        }
    }
}

@Composable
private fun WizardDialog(data: IrData, onDismiss: () -> Unit, onCreate: (IrDevice) -> Unit) {
    var category by remember { mutableStateOf(IrCategory.TV) }
    var name by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var controller by remember { mutableStateOf(data.controllers.firstOrNull()?.mac.orEmpty()) }
    Panel(onDismiss) {
        Title("Новый пульт")
        Note("1. Категория")
        ChipFlow { IrCategory.entries.forEach { c -> Chip("${c.icon} ${c.title}", Violet, selected = c == category) { category = c } } }
        Note("2. Название и комната")
        Field("Название (например, Телевизор Haier)", name) { name = it }
        Field("Комната (например, Гостиная)", room) { room = it }
        val roomHints = (data.rooms + listOf("Гостиная", "Спальня", "Кухня", "Детская", "Кабинет")).distinct().take(8)
        ChipFlow { roomHints.forEach { r -> Chip(r, Cyan, selected = r == room) { room = r } } }
        Note("3. Контроллер")
        ChipFlow { data.controllers.forEach { c -> Chip("${c.name} · ${c.host}", Green, selected = c.mac == controller) { controller = c.mac } } }
        val ok = name.isNotBlank() && controller.isNotBlank()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Создать и обучить кнопки", if (ok) Green else Muted) {
                if (ok) onCreate(IrDevice(IrIds.newId(), name.trim(), room.trim(), category, controller))
            }
            Chip("Отмена", Muted) { onDismiss() }
        }
    }
}

@Composable
private fun EditorDialog(d: IrDevice, data: IrData, onDismiss: () -> Unit, onButton: (String) -> Unit, onAdd: () -> Unit, onStatus: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(d.id) { mutableStateOf(d.name) }
    var room by remember(d.id) { mutableStateOf(d.room) }
    var line by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    Panel(onDismiss) {
        Title("${d.category.icon} ${d.name}")
        Note("${d.category.title} · ${d.room.ifBlank { "без комнаты" }} · контроллер ${data.controller(d.controller)?.let { "${it.name} (${it.host})" } ?: "удалён — подключите заново"}")
        if (line.isNotEmpty()) Text(line, color = if (line.startsWith("✓")) Green else Orange, fontSize = 14.sp)
        Note("Нажатие на кнопку — отправить сигнал. Долгое меню кнопки — «⋯».")
        ChipFlow {
            d.buttons.forEach { b ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(b.name, Violet) {
                        line = "Отправляю «${b.name}»…"
                        scope.launch {
                            line = when (val r = IrManager.press(context, d.id, b.id)) {
                                is IrResult.Ok -> "✓ «${b.name}» отправлено, контроллер подтвердил"
                                is IrResult.Fail -> "«${b.name}» не отправлено: ${r.message}"
                            }
                        }
                    }
                    Chip("⋯", Muted) { onButton(b.id) }
                }
            }
            Chip("＋ Добавить кнопку", Green) { onAdd() }
        }
        Field("Название", name) { name = it }
        Field("Комната", room) { room = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Сохранить", Green) {
                if (name.isNotBlank()) {
                    IrManager.update(context) { s -> s.copy(devices = s.devices.map { if (it.id == d.id) it.copy(name = name.trim(), room = room.trim()) else it }) }
                    line = "✓ Сохранено"
                }
            }
            Chip("Копировать", Cyan) {
                val copy = d.copy(id = IrIds.newId(), name = d.name + " (копия)", buttons = d.buttons.map { it.copy(id = IrIds.newId()) })
                IrManager.update(context) { it.copy(devices = it.devices + copy) }
                onStatus("Создана копия «${copy.name}»"); onDismiss()
            }
            Chip(if (confirmDelete) "Точно удалить?" else "Удалить пульт", Red) {
                if (!confirmDelete) confirmDelete = true else {
                    IrManager.update(context) { s ->
                        s.copy(devices = s.devices.filter { it.id != d.id },
                            scenarios = s.scenarios.map { sc -> sc.copy(steps = sc.steps.filter { it.deviceId != d.id }) })
                    }
                    onStatus("Пульт «${d.name}» удалён"); onDismiss()
                }
            }
            Chip("Готово", Muted) { onDismiss() }
        }
    }
}

@Composable
private fun FunctionPicker(selected: IrFunction, onPick: (IrFunction) -> Unit) =
    ChipFlow { IrFunction.entries.forEach { f -> Chip(f.title, Cyan, selected = f == selected) { onPick(f) } } }

@Composable
private fun ButtonDialog(d: IrDevice, b: IrButton, onDismiss: () -> Unit, onRelearn: () -> Unit) {
    val context = LocalContext.current
    var name by remember(b.id) { mutableStateOf(b.name) }
    var function by remember(b.id) { mutableStateOf(b.function) }
    var phrases by remember(b.id) { mutableStateOf(b.phrases.joinToString("; ")) }
    var confirmDelete by remember { mutableStateOf(false) }
    Panel(onDismiss) {
        Title("Кнопка «${b.name}»")
        Note("Сигнал: ${b.code.size} байт · ${d.name}")
        Field("Название", name) { name = it }
        Note("Функция (для голоса Евы и сценариев)")
        FunctionPicker(function) { function = it }
        Field("Свои голосовые команды через «;» (например: громче на саундбаре)", phrases) { phrases = it }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Сохранить", Green) {
                val list = phrases.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                if (name.isNotBlank()) {
                    IrManager.update(context) { s -> s.copy(devices = s.devices.map { dev ->
                        if (dev.id != d.id) dev else dev.copy(buttons = dev.buttons.map { if (it.id == b.id) it.copy(name = name.trim(), function = function, phrases = list) else it })
                    }) }
                    onDismiss()
                }
            }
            Chip("Обучить заново", Orange) { onRelearn() }
            Chip(if (confirmDelete) "Точно удалить?" else "Удалить", Red) {
                if (!confirmDelete) confirmDelete = true else {
                    IrManager.update(context) { s -> s.copy(
                        devices = s.devices.map { dev -> if (dev.id != d.id) dev else dev.copy(buttons = dev.buttons.filter { it.id != b.id }) },
                        scenarios = s.scenarios.map { sc -> sc.copy(steps = sc.steps.filter { it.buttonId != b.id }) }) }
                    onDismiss()
                }
            }
            Chip("Назад", Muted) { onDismiss() }
        }
    }
}

/**
 * Learning (ТЗ §6): name + function → the controller enters learning mode → the owner presses the button on the
 * original remote → the signal is received → test it on the device → save. A relearn keeps the button id,
 * so scenarios and voice phrases stay attached.
 */
@Composable
private fun LearnDialog(d: IrDevice, existing: IrButton?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var function by remember { mutableStateOf(existing?.function ?: IrFunction.CUSTOM) }
    var code by remember { mutableStateOf<ByteArray?>(null) }
    var line by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    fun start() {
        if (busy) return
        busy = true; code = null; line = "Включаю режим обучения…"
        job = scope.launch {
            val ticker = launch { left = 30; while (left > 0) { delay(1_000); left-- } }
            line = "Направьте родной пульт на контроллер (5–10 см) и коротко нажмите кнопку «${name.ifBlank { "…" }}»"
            val r = IrManager.learn(context, d.controller, 30_000)
            ticker.cancel(); left = 0; busy = false
            line = when (r) {
                is IrResult.Ok -> r.value?.let { code = it; "✓ Сигнал принят (${it.size} байт). Проверьте его на технике и сохраните." }
                    ?: "Сигнал не получен за 30 секунд. Нажмите «Обучить» и попробуйте ещё раз."
                is IrResult.Fail -> "Обучение не выполнено: ${r.message}"
            }
        }
    }
    Panel(onDone) {
        Title(if (existing == null) "Новая кнопка · ${d.name}" else "Обучить заново «${existing.name}»")
        Field("Название кнопки (например, Громче)", name) { name = it }
        Note("Функция")
        FunctionPicker(function) { f -> function = f; if (name.isBlank() || IrFunction.entries.any { it.title == name }) name = f.title.takeIf { f != IrFunction.CUSTOM } ?: name }
        if (line.isNotEmpty()) Text(line + if (left > 0) " · $left с" else "", color = if (code != null) Green else Color.White, fontSize = 15.sp, modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(if (busy) "Жду сигнал…" else if (code == null) "Обучить" else "Повторить", Orange) { if (name.isNotBlank()) start() else line = "Сначала назовите кнопку" }
            if (busy) Chip("Остановить", Muted) { job?.cancel(); busy = false; left = 0; line = "Обучение остановлено" }
            val learned = code
            if (learned != null && !busy) {
                Chip("Проверить", Cyan) {
                    line = "Отправляю пробный сигнал…"
                    scope.launch {
                        val r: IrResult<Unit> = when (val drv = IrManager.driver(context, d.controller)) {
                            is IrResult.Ok -> drv.value.sendCommand(learned)
                            is IrResult.Fail -> drv
                        }
                        line = when (r) {
                            is IrResult.Ok -> "✓ Отправлено. Сработала техника? Если да — «Сохранить»."
                            is IrResult.Fail -> "Не отправлено: ${r.message}"
                        }
                    }
                }
                Chip("Сохранить", Green) {
                    val id = existing?.id ?: IrIds.newId()
                    val button = IrButton(id, name.trim(), function, learned, existing?.phrases.orEmpty())
                    IrManager.update(context) { s -> s.copy(devices = s.devices.map { dev ->
                        if (dev.id != d.id) dev
                        else dev.copy(buttons = if (existing != null) dev.buttons.map { if (it.id == id) button else it } else dev.buttons + button)
                    }) }
                    onDone()
                }
            }
            Chip("Отмена", Muted) { job?.cancel(); onDone() }
        }
    }
}

@Composable
private fun ScenarioDialog(data: IrData, existing: IrScenario?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var phrases by remember { mutableStateOf(existing?.phrases?.joinToString("; ") ?: "") }
    var steps by remember { mutableStateOf(existing?.steps ?: emptyList()) }
    var pickDevice by remember { mutableStateOf<String?>(null) }
    var line by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val id = remember { existing?.id ?: IrIds.newId() }
    fun save(): IrScenario? {
        if (name.isBlank() || steps.isEmpty()) { line = "Нужны название и хотя бы один шаг"; return null }
        val sc = IrScenario(id, name.trim(), phrases.split(';').map { it.trim() }.filter { it.isNotEmpty() }, steps)
        IrManager.update(context) { s -> s.copy(scenarios = s.scenarios.filter { it.id != id } + sc) }
        return sc
    }
    Panel(onDismiss) {
        Title(if (existing == null) "Новый сценарий" else "Сценарий «${existing.name}»")
        Field("Название (например, Кино)", name) { name = it }
        Field("Голосовые команды через «;» (например: кино; включи кино)", phrases) { phrases = it }
        Note("Шаги")
        steps.forEachIndexed { i, st ->
            val dev = data.device(st.deviceId); val btn = dev?.buttons?.firstOrNull { it.id == st.buttonId }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                Text("${i + 1}. ${dev?.name ?: "?"} — ${btn?.name ?: "кнопка удалена"} ×${st.repeat}, пауза ${st.delayMs / 1000.0} с",
                    color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Chip("×${st.repeat}", Cyan) { steps = steps.toMutableList().also { it[i] = st.copy(repeat = if (st.repeat >= 5) 1 else st.repeat + 1) } }
                Chip("пауза", Cyan) {
                    val next = listOf(500L, 1_000L, 2_000L, 5_000L, 10_000L).let { l -> l[(l.indexOf(st.delayMs) + 1).mod(l.size)] }
                    steps = steps.toMutableList().also { it[i] = st.copy(delayMs = next) }
                }
                Chip("✕", Red) { steps = steps.toMutableList().also { it.removeAt(i) } }
            }
        }
        Note("Добавить шаг: выберите пульт, затем кнопку")
        ChipFlow { data.devices.filter { it.buttons.isNotEmpty() }.forEach { d -> Chip("${d.category.icon} ${d.name}", Violet, selected = d.id == pickDevice) { pickDevice = d.id } } }
        data.device(pickDevice ?: "")?.let { d ->
            ChipFlow { d.buttons.forEach { b -> Chip(b.name, Green) { steps = steps + IrStep(d.id, b.id) } } }
        }
        if (line.isNotEmpty()) Text(line, color = if (line.startsWith("✓")) Green else Orange, fontSize = 14.sp, modifier = Modifier.padding(vertical = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip("Сохранить", Green) { if (save() != null) onDismiss() }
            Chip("Запустить", Orange) {
                val sc = save() ?: return@Chip
                scope.launch {
                    line = when (val r = IrManager.run(context, sc.id) { line = it }) {
                        is IrResult.Ok -> "✓ Сценарий выполнен — все шаги подтверждены контроллером"
                        is IrResult.Fail -> r.message
                    }
                }
            }
            if (existing != null) Chip(if (confirmDelete) "Точно удалить?" else "Удалить", Red) {
                if (!confirmDelete) confirmDelete = true else {
                    IrManager.update(context) { s -> s.copy(scenarios = s.scenarios.filter { it.id != id }) }; onDismiss()
                }
            }
            Chip("Отмена", Muted) { onDismiss() }
        }
    }
}
