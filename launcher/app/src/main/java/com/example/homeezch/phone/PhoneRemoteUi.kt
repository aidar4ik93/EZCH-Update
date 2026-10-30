package com.example.homeezch.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Top-bar entry: «📱 Телефон». */
@Composable
fun PhoneRemoteButton(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    Text("📱 Телефон", color = if (focused) Color.White else Color(0xFF80C8FF), fontSize = 14.sp,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color(0xFF1E6FD9) else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable { open = true }.padding(horizontal = 12.dp, vertical = 6.dp))
    if (open) PhoneRemoteDialog { open = false }
}

/** QR code + address. The first open turns the phone remote on; it stays on until switched off here. */
@Composable
fun PhoneRemoteDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val running by PhoneRemote.state.collectAsState()
    LaunchedEffect(Unit) { PhoneRemote.enable(context) }
    val url = remember(running, revision) { PhoneRemote.url(context) }
    val qr = remember(url) { url?.let { runCatching { QrCode.encode(it) }.getOrNull() } }
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.background(Color(0xFF0B1628), RoundedCornerShape(22.dp)).border(1.dp, Color(0xFF2B4A75), RoundedCornerShape(22.dp))
            .padding(24.dp).widthIn(max = 640.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Телефон как пульт и микрофон", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Наведите камеру телефона на код. Откроется страница: говорите через микрофон клавиатуры — Ева поймёт точнее, " +
                "печатайте прямо в поля ТВ, отправляйте ссылки. Приложение ставить не нужно; работает только в вашей Wi-Fi сети.",
                color = Color(0xFFB6C6DA), fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
            if (qr != null && url != null) {
                Box(Modifier.size(260.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(14.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val cell = size.minDimension / qr.size
                        for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr.modules[y][x])
                            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(url.substringBefore("?k="), color = Color(0xFF80C8FF), fontSize = 15.sp)
            } else Text(if (!running) "Запускаю…" else "ТВ не подключён к домашней сети (Wi-Fi / Ethernet)", color = Color(0xFFFFB0A0), fontSize = 15.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogChip("Готово", Modifier.focusRequester(first)) { onClose() }
                DialogChip("Новый код") { PhoneRemote.newKey(context); revision++ }
                DialogChip("Выключить") { PhoneRemote.disable(context); onClose() }
            }
            Text("«Новый код» отключает все телефоны, подключённые раньше.", color = Color(0xFF7F93AD), fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun DialogChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color(0xFF2F7BEA) else Color(0xFF1A2C48), RoundedCornerShape(12.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF34557F), RoundedCornerShape(12.dp))
            .clickable { onClick() }.padding(horizontal = 18.dp, vertical = 10.dp))
}
