package com.example.homeezch.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.example.homeezch.home.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Bg = Color(0xFF060C18)
private val Card = Color(0xFF111E33)
private val Line = Color(0xFF243A5C)
private val Muted = Color(0xFF8CA0BD)

/**
 * «Умный дом»: every device from Яндекс (incl. Яндекс Пульт IR remotes and Xiaomi/Tuya linked there),
 * Tuya / Smart Life directly and Home Assistant — in one colourful, remote-friendly screen.
 * Locks/alarms never act on a single press; IR and AC controls open a dedicated panel.
 */
class SmartHomeActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF5AA9FF), surface = Card, background = Bg)) {
                SmartHomeScreen(onClose = ::finish)
            }
        }
    }
}

@Composable
private fun SmartHomeScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(if (SmartHub.anyConnected(context)) 0 else 2) }
    var devices by remember { mutableStateOf<List<SmartDevice>>(SmartHub.cache) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<SmartDevice?>(null) }
    fun reload() {
        if (!SmartHub.anyConnected(context)) { devices = emptyList(); status = "Ничего не подключено. Откройте «Подключения»."; return }
        loading = true; status = "Загружаю устройства…"
        scope.launch {
            devices = SmartHub.devices(context)
            loading = false
            status = if (devices.isEmpty()) "Устройства не найдены. Проверьте подключение." else "Устройств: ${devices.count { it.type != DeviceType.SCENE }} · сценариев: ${devices.count { it.type == DeviceType.SCENE }}"
        }
    }
    LaunchedEffect(Unit) { reload() }
    fun act(d: SmartDevice, c: DeviceCommand) {
        scope.launch {
            status = "${d.name}: отправляю…"
            val ok = SmartHub.send(context, d, c)
            status = if (ok) "${d.name}: готово" else "${d.name}: устройство не подтвердило команду"
            if (ok && c is DeviceCommand.Power) devices = devices.map { if (it.id == d.id && it.provider == d.provider) it.copy(on = c.on) else it }
        }
    }
    val tabs = listOf("🏠 Устройства", "🎛️ Умный пульт", "🔗 Подключения", "🎬 Кино-режим")
    val firstTab = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1B36), Bg))).padding(horizontal = 48.dp, vertical = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Умный дом", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(14.dp))
            Text(status, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Chip(if (loading) "Обновляю…" else "⟳ Обновить", Color(0xFF2EE6C5)) { reload() }
            Spacer(Modifier.width(8.dp))
            Chip("✕ Закрыть", Color(0xFFFF6F6F)) { onClose() }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            tabs.forEachIndexed { i, t ->
                Chip(t, if (tab == i) Color(0xFF5AA9FF) else Color(0xFF3A5478), selected = tab == i,
                    modifier = if (i == 0) Modifier.focusRequester(firstTab) else Modifier) { tab = i }
            }
        }
        Spacer(Modifier.height(16.dp))
        when (tab) {
            0 -> DevicesTab(devices.filter { it.type != DeviceType.REMOTE }, onToggle = { d ->
                when {
                    d.type == DeviceType.SCENE -> act(d, DeviceCommand.Power(true))
                    d.type in setOf(DeviceType.AC, DeviceType.VACUUM, DeviceType.TV, DeviceType.CURTAIN) || !d.canPower -> open = d
                    else -> act(d, DeviceCommand.Power(d.on != true))
                }
            }, onOpen = { open = it })
            1 -> RemotesTab(devices.filter { it.type in setOf(DeviceType.REMOTE, DeviceType.TV, DeviceType.AC) && (it.buttons.isNotEmpty() || it.infrared != null || it.provider == "yandex") }) { open = it }
            2 -> ConnectionsTab { reload() }
            else -> CinemaTab(devices.filter { it.type == DeviceType.LIGHT })
        }
    }
    LaunchedEffect(Unit) { delay(150); runCatching { firstTab.requestFocus() } }
    open?.let { d -> DevicePanel(d, onDismiss = { open = null }) { c -> act(d, c) } }
}

@Composable
internal fun Chip(text: String, color: Color, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s }
            .background(if (focused || selected) color.copy(alpha = .85f) else color.copy(alpha = .22f), shape)
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else color.copy(alpha = .6f), shape)
            .clickable { onClick() }.padding(horizontal = 16.dp, vertical = 9.dp))
}

private fun stateText(d: SmartDevice): String = when {
    !d.online -> "нет связи"
    d.type == DeviceType.SCENE -> "запустить"
    d.type == DeviceType.SENSOR -> d.reading ?: "—"
    d.type == DeviceType.AC -> listOfNotNull(if (d.on == true) "вкл" else if (d.on == false) "выкл" else null, d.temperature?.let { "${it.toInt()}°" }, d.mode).joinToString(" · ").ifEmpty { "пульт" }
    d.on == true -> "вкл" + (d.brightness?.let { " · $it %" } ?: "")
    d.on == false -> "выкл"
    d.buttons.isNotEmpty() -> "кнопок: ${d.buttons.size}"
    else -> d.reading ?: "открыть"
}
private fun providerLabel(p: String) = when (p) { "yandex" -> "Яндекс"; "tuya" -> "Tuya"; else -> "HA" }

@Composable
private fun DeviceTile(d: SmartDevice, onClick: () -> Unit, onLong: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val tint = Color(d.type.color)
    val active = d.on == true
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.height(118.dp).onFocusChanged { focused = it.isFocused }
        .graphicsLayer { val s = if (focused) 1.05f else 1f; scaleX = s; scaleY = s; alpha = if (d.online) 1f else .55f }
        .background(if (active) Brush.linearGradient(listOf(tint.copy(alpha = .45f), Card)) else Brush.linearGradient(listOf(Card, Card)), shape)
        .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else tint.copy(alpha = if (active) .8f else .3f), shape)
        .clickable { onClick() }.padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(tint.copy(alpha = if (active) .9f else .25f), CircleShape), contentAlignment = Alignment.Center) {
                Text(d.type.icon, fontSize = 20.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(providerLabel(d.provider), color = Muted, fontSize = 10.sp)
            if (d.type != DeviceType.SCENE && d.type != DeviceType.SENSOR) {
                Spacer(Modifier.width(6.dp))
                Text("⋯", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable { onLong() }.padding(horizontal = 6.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(d.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(stateText(d), color = if (active) tint else Muted, fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun DevicesTab(devices: List<SmartDevice>, onToggle: (SmartDevice) -> Unit, onOpen: (SmartDevice) -> Unit) {
    if (devices.isEmpty()) { Hint("Здесь появятся свет, розетки, кондиционеры, пылесосы и сценарии после подключения на вкладке «Подключения»."); return }
    val groups = devices.groupBy { it.room.ifBlank { if (it.type == DeviceType.SCENE) "Сценарии" else it.type.title } }
        .toSortedMap(compareBy<String> { it == "Сценарии" }.thenBy { it })
    LazyVerticalGrid(GridCells.Adaptive(210.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)) {
        groups.forEach { (room, list) ->
            item(span = { GridItemSpan(maxLineSpan) }) { Text(room, color = Color(0xFFFFC94D), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp)) }
            items(list, key = { it.provider + it.id }) { d -> DeviceTile(d, onClick = { onToggle(d) }, onLong = { onOpen(d) }) }
        }
    }
}

@Composable
private fun RemotesTab(remotes: List<SmartDevice>, onOpen: (SmartDevice) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Универсальные ИК-пульты: телевизоры, кондиционеры, ресиверы, вентиляторы — всё, что управляется инфракрасным пультом.",
            color = Muted, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProtocolCard("Яндекс Пульт", "Протокол: Яндекс ИК через «Дом с Алисой». Пульты добавляются в приложении «Дом с Алисой» → Устройство → Пульт. Подходят все версии Яндекс Пульта.",
                Color(0xFFFF9F43), YandexProvider.connected(LocalContext.current))
            ProtocolCard("Tuya ИК-хаб", "Протокол: Tuya IR (Smart Life). Пульты, обученные в Smart Life, появятся здесь со всеми кнопками.",
                Color(0xFF2EE6C5), TuyaProvider.connected(LocalContext.current))
        }
        Spacer(Modifier.height(14.dp))
        if (remotes.isEmpty()) Hint("Пульты не найдены. Подключите Яндекс или Tuya на вкладке «Подключения».")
        remotes.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                row.forEach { d -> Box(Modifier.width(220.dp)) { DeviceTile(d, onClick = { onOpen(d) }, onLong = { onOpen(d) }) } }
            }
        }
    }
}

@Composable
private fun ProtocolCard(title: String, text: String, color: Color, connected: Boolean) {
    Column(Modifier.width(420.dp).background(Brush.linearGradient(listOf(color.copy(alpha = .28f), Card)), RoundedCornerShape(16.dp))
        .border(1.dp, color.copy(alpha = .5f), RoundedCornerShape(16.dp)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (connected) "● подключено" else "○ не подключено", color = if (connected) Color(0xFF7BE07B) else Muted, fontSize = 12.sp)
        }
        Text(text, color = Color(0xFFC9D6EA), fontSize = 12.sp)
    }
}

@Composable private fun Hint(text: String) = Text(text, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(vertical = 18.dp))

/** Controls for one device: power, brightness, temperature/mode, vacuum, curtains and every IR button. */
@Composable
private fun DevicePanel(d: SmartDevice, onDismiss: () -> Unit, send: (DeviceCommand) -> Unit) {
    val tint = Color(d.type.color)
    var level by remember { mutableIntStateOf(d.brightness ?: 50) }
    var temp by remember { mutableIntStateOf(d.temperature?.toInt() ?: 24) }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.widthIn(max = 720.dp).background(Card, RoundedCornerShape(22.dp)).border(1.dp, tint.copy(alpha = .6f), RoundedCornerShape(22.dp))
            .padding(22.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).background(tint, CircleShape), contentAlignment = Alignment.Center) { Text(d.type.icon, fontSize = 24.sp) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("${d.type.title} · ${providerLabel(d.provider)}${if (d.room.isNotBlank()) " · " + d.room else ""}", color = Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            if (d.canPower || d.type == DeviceType.REMOTE) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("⏻ Включить", Color(0xFF7BE07B), Modifier.focusRequester(first)) { send(DeviceCommand.Power(true)) }
                Chip("⏻ Выключить", Color(0xFFFF6F6F)) { send(DeviceCommand.Power(false)) }
            }
            if (d.type == DeviceType.LIGHT) Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Яркость $level %", color = Color.White, fontSize = 15.sp, modifier = Modifier.width(150.dp))
                Chip("−", tint) { level = (level - 10).coerceAtLeast(1); send(DeviceCommand.Brightness(level)) }
                Chip("+", tint) { level = (level + 10).coerceAtMost(100); send(DeviceCommand.Brightness(level)) }
                listOf(10, 50, 100).forEach { p -> Chip("$p %", tint) { level = p; send(DeviceCommand.Brightness(p)) } }
            }
            if (d.type == DeviceType.AC || d.type == DeviceType.HEATER) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Температура $temp°", color = Color.White, fontSize = 15.sp, modifier = Modifier.width(150.dp))
                    Chip("−", tint) { temp = (temp - 1).coerceAtLeast(16); send(DeviceCommand.Temperature(temp)) }
                    Chip("+", tint) { temp = (temp + 1).coerceAtMost(30); send(DeviceCommand.Temperature(temp)) }
                }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("cool" to "❄️ Холод", "heat" to "🔥 Тепло", "auto" to "♻️ Авто", "dry" to "💧 Осушение", "fan_only" to "🌀 Вентилятор").forEach { (m, t) ->
                        Chip(t, tint) { send(DeviceCommand.Mode(m)) }
                    }
                }
            }
            if (d.type == DeviceType.VACUUM) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("▶ Начать уборку", tint, Modifier.focusRequester(first)) { send(DeviceCommand.Vacuum("start")) }
                Chip("⏸ Пауза", tint) { send(DeviceCommand.Vacuum("pause")) }
                Chip("⌂ На базу", tint) { send(DeviceCommand.Vacuum("dock")) }
            }
            if (d.type == DeviceType.CURTAIN) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip("▲ Открыть", tint) { send(DeviceCommand.Curtain(true)) }
                Chip("▼ Закрыть", tint) { send(DeviceCommand.Curtain(false)) }
            }
            if (d.buttons.isNotEmpty()) {
                Text("Кнопки пульта", color = Color(0xFFFFC94D), fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                d.buttons.entries.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                        row.forEach { (name, id) -> Chip(name, Color(0xFFFF9F43)) { send(DeviceCommand.Button(id)) } }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Chip("Готово", Color(0xFF5AA9FF)) { onDismiss() }
        }
    }
    LaunchedEffect(Unit) { delay(100); runCatching { first.requestFocus() } }
}

/** Connections: Яндекс (code on screen), Tuya (cloud keys), Home Assistant, and how Xiaomi joins. */
@Composable
private fun ConnectionsTab(onChanged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var yandexCode by remember { mutableStateOf<YandexProvider.DeviceCode?>(null) }
    var message by remember { mutableStateOf("") }
    var form by remember { mutableStateOf<String?>(null) }
    key(revision) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (message.isNotBlank()) Text(message, color = Color(0xFFFFC94D), fontSize = 14.sp)
            ConnectionCard("🟡 Яндекс · Дом с Алисой", "Самый простой путь: свет и розетки Яндекса, Яндекс Пульт (ИК), а также устройства Xiaomi и Tuya/Smart Life, привязанные в приложении «Дом с Алисой».",
                Color(0xFFFFCC00), YandexProvider.connected(context)) {
                if (YandexProvider.connected(context)) {
                    Chip("Отключить", Color(0xFFFF6F6F)) { YandexProvider.disconnect(context); revision++; onChanged() }
                } else {
                    Chip("Войти по коду на экране", Color(0xFFFFCC00)) {
                        scope.launch {
                            YandexProvider.startSignIn(context).onSuccess { code ->
                                yandexCode = code; message = ""
                                val until = System.currentTimeMillis() + code.expiresIn * 1000L
                                while (yandexCode === code && System.currentTimeMillis() < until) {
                                    delay(code.interval * 1000L)
                                    val r = YandexProvider.pollToken(context, code)
                                    if (r.isFailure) { message = r.exceptionOrNull()?.message.orEmpty(); yandexCode = null; break }
                                    if (r.getOrNull() != null) { yandexCode = null; message = "Яндекс подключён"; revision++; onChanged(); break }
                                }
                            }.onFailure { message = (it.message ?: "Ошибка") + ". Можно вставить токен вручную или задать приложение OAuth." }
                        }
                    }
                    Chip("Вставить токен", Color(0xFF5AA9FF)) { form = "yandex-token" }
                    Chip("Приложение OAuth", Color(0xFF3A5478)) { form = "yandex-app" }
                }
            }
            yandexCode?.let { c ->
                Row(Modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    QrBox(c.url, Modifier.size(180.dp))
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Text("Откройте на телефоне ${c.url}", color = Color.Black, fontSize = 16.sp)
                        Text("и введите код:", color = Color.Black, fontSize = 16.sp)
                        Text(c.userCode, color = Color.Black, fontSize = 42.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                        Text("Ждём подтверждения…", color = Color.DarkGray, fontSize = 13.sp)
                    }
                }
            }
            ConnectionCard("🟢 Tuya · Smart Life", "Для домов без Яндекса: розетки, лампы, пылесосы и ИК-хабы Tuya. Нужны ключи облачного проекта Tuya (iot.tuya.com) и UID аккаунта Smart Life. Длинные ключи удобно вводить с телефона: Настройки → «Телефон как пульт» → «Напечатать на ТВ».",
                Color(0xFF2EE6C5), TuyaProvider.connected(context)) {
                Chip(if (TuyaProvider.connected(context)) "Изменить ключи" else "Подключить", Color(0xFF2EE6C5)) { form = "tuya" }
                if (TuyaProvider.connected(context)) Chip("Отключить", Color(0xFFFF6F6F)) { TuyaProvider.disconnect(context); revision++; onChanged() }
            }
            ConnectionCard("🟠 Xiaomi · Mi Home", "Пылесосы, кондиционеры, лампы Xiaomi подключаются через «Дом с Алисой» (Яндекс → Добавить устройство → Xiaomi) или через Home Assistant (интеграция Xiaomi Home). После этого они сразу появятся здесь.",
                Color(0xFFFF7A00), false) {}
            ConnectionCard("🔵 Home Assistant", "Для продвинутых: локальный сервер умного дома в вашей сети. Адрес находится автоматически.",
                Color(0xFF41BDF5), HomeAssistantProvider.connected(context)) {
                Chip(if (HomeAssistantProvider.connected(context)) "Изменить" else "Подключить", Color(0xFF41BDF5)) { form = "ha" }
                if (HomeAssistantProvider.connected(context)) Chip("Отключить", Color(0xFFFF6F6F)) { SmartHomeConnection.clear(context); revision++; onChanged() }
            }
        }
    }
    form?.let { f -> ConnectForm(f, onDone = { result -> form = null; result?.let { message = it }; revision++; onChanged() }) }
}

@Composable
private fun ConnectionCard(title: String, text: String, color: Color, connected: Boolean, actions: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(color.copy(alpha = .22f), Card)), RoundedCornerShape(18.dp))
        .border(1.dp, color.copy(alpha = if (connected) .9f else .4f), RoundedCornerShape(18.dp)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (connected) "● подключено" else "○ не подключено", color = if (connected) Color(0xFF7BE07B) else Muted, fontSize = 13.sp)
        }
        Text(text, color = Color(0xFFC9D6EA), fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

@Composable
private fun ConnectForm(kind: String, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tuya = remember { TuyaProvider.keys(context) }
    var a by remember { mutableStateOf(when (kind) { "tuya" -> tuya.accessId; "ha" -> SmartHomeConnection.url(context); else -> "" }) }
    var b by remember { mutableStateOf(when (kind) { "tuya" -> tuya.secret; else -> "" }) }
    var c by remember { mutableStateOf(tuya.uid) }
    var region by remember { mutableStateOf(TuyaProvider.REGIONS.entries.firstOrNull { it.value == tuya.endpoint }?.key ?: "Европа") }
    var busy by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { onDone(null) }) {
        Column(Modifier.widthIn(max = 640.dp).background(Card, RoundedCornerShape(20.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(when (kind) { "tuya" -> "Tuya · Smart Life"; "ha" -> "Home Assistant"; "yandex-app" -> "Приложение Яндекс OAuth"; else -> "Токен Яндекса" },
                color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            when (kind) {
                "tuya" -> {
                    OutlinedTextField(a, { a = it }, label = { Text("Access ID") }, singleLine = true)
                    OutlinedTextField(b, { b = it }, label = { Text("Access Secret") }, singleLine = true)
                    OutlinedTextField(c, { c = it }, label = { Text("UID аккаунта Smart Life") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TuyaProvider.REGIONS.keys.forEach { r -> Chip(r, if (r == region) Color(0xFF2EE6C5) else Color(0xFF3A5478), selected = r == region) { region = r } }
                    }
                }
                "ha" -> {
                    OutlinedTextField(a, { a = it }, label = { Text("Адрес (найдём сами)") }, singleLine = true)
                    OutlinedTextField(b, { b = it }, label = { Text("Долгосрочный токен") }, singleLine = true)
                    Chip("🔍 Найти в сети", Color(0xFF41BDF5)) { scope.launch { SmartHomeConnection.discover()?.let { a = it } } }
                }
                "yandex-app" -> {
                    OutlinedTextField(a, { a = it }, label = { Text("Client ID") }, singleLine = true)
                    OutlinedTextField(b, { b = it }, label = { Text("Client Secret") }, singleLine = true)
                    Text("oauth.yandex.ru → Создать приложение → доступы «Умный дом: просмотр и управление», вход по коду на устройстве.", color = Muted, fontSize = 12.sp)
                }
                else -> {
                    OutlinedTextField(a, { a = it }, label = { Text("OAuth-токен") }, singleLine = true)
                    Text("Удобно отправить с телефона: Настройки → «Телефон как пульт» → «Напечатать на ТВ».", color = Muted, fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(if (busy) "Проверяю…" else "Сохранить", Color(0xFF7BE07B)) {
                    if (busy) return@Chip
                    when (kind) {
                        "tuya" -> { TuyaProvider.save(context, TuyaProvider.Keys(a, b, TuyaProvider.REGIONS[region]!!, c)); busy = true
                            scope.launch { val r = TuyaProvider.test(context); busy = false; onDone(r) } }
                        "ha" -> if (SmartHomeConnection.validUrl(a.trim())) { SmartHomeConnection.save(context, a.trim(), b.trim().ifBlank { SmartHomeConnection.token(context) }); onDone("Home Assistant сохранён") }
                            else onDone("Адрес должен быть https:// или http:// домашней сети")
                        "yandex-app" -> { YandexProvider.setApp(context, a, b); onDone("Приложение OAuth сохранено — теперь «Войти по коду»") }
                        else -> { YandexProvider.setToken(context, a); onDone(if (YandexProvider.connected(context)) "Токен сохранён" else "Токен слишком короткий") }
                    }
                }
                Chip("Отмена", Color(0xFF3A5478)) { onDone(null) }
            }
        }
    }
}

/** «Кино-режим» for any ecosystem: the chosen lights dim while a film plays. */
@Composable
private fun CinemaTab(lights: List<SmartDevice>) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(CinemaMode.enabled(context)) }
    var level by remember { mutableIntStateOf(CinemaMode.level(context)) }
    var chosen by remember { mutableStateOf(CinemaMode.lights(context)) }
    fun save() = CinemaMode.configure(context, enabled && chosen.isNotEmpty(), level, chosen)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Фильм начался — выбранный свет плавно гаснет. Пауза или конец — свет возвращается как был. Музыка и меню свет не трогают.",
            color = Color(0xFFC9D6EA), fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(if (enabled) "● Кино-режим включён" else "○ Кино-режим выключен", if (enabled) Color(0xFF7BE07B) else Color(0xFF3A5478), selected = enabled) { enabled = !enabled; save() }
            listOf(5, 10, 15, 25, 40).forEach { p -> Chip("$p %", if (p == level) Color(0xFFFFC94D) else Color(0xFF3A5478), selected = p == level) { level = p; save() } }
        }
        if (lights.isEmpty()) Hint("Светильники не найдены. Подключите умный дом на вкладке «Подключения».")
        lights.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { d ->
                    val key = d.provider + "|" + d.id
                    Chip((if (key in chosen) "✓ " else "+ ") + d.name, if (key in chosen) Color(0xFFFFC94D) else Color(0xFF3A5478), selected = key in chosen) {
                        chosen = if (key in chosen) chosen - key else chosen + key; save()
                    }
                }
            }
        }
    }
}

@Composable
private fun QrBox(text: String, modifier: Modifier) {
    val qr = remember(text) { runCatching { com.example.homeezch.phone.QrCode.encode(text) }.getOrNull() } ?: return
    Canvas(modifier) {
        val cell = size.minDimension / qr.size
        for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr.modules[y][x])
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + .5f, cell + .5f))
    }
}
