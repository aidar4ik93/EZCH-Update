package com.example.homeezch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun PinPrompt(profile: DesktopProfile, onDismiss: () -> Unit, onVerified: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("PIN · ${profile.name}") },
        text = { Column {
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8); error = false }, label = { Text("PIN") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            if (error) Text("Неверный PIN или пауза после 5 попыток (30 секунд)")
        } }, confirmButton = { TextButton(enabled = !busy && pin.length >= 4, onClick = {
            busy = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) { Profiles.check(context, profile, pin) }
                busy = false; pin = ""
                if (ok) onVerified() else error = true
            }
        }) { Text("Продолжить") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Отмена") } })
}

@Composable internal fun ProfilePicker(onDismiss: () -> Unit, onChange: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<DesktopProfile?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    fun switch(profile: DesktopProfile) {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Profiles.switch(context, profile.id) }
            busy = false
            if (ok) { onChange(); onDismiss() } else error = true
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Профили") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            Profiles.list(context).forEach { profile -> TextButton(enabled = !busy, onClick = {
                if (profile.credential.isEmpty()) switch(profile) else target = profile
            }) { Text(profile.name + if (profile.credential.isNotEmpty()) " · PIN" else "") } }
            if (error) Text("Не удалось переключить профиль")
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Закрыть") } })
    target?.let { profile -> PinPrompt(profile, { target = null }) { target = null; switch(profile) } }
}

@Composable internal fun ProfileControls(onChange: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(Profiles.list(context)) }
    var creating by remember { mutableStateOf(false) }
    var settingPin by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    SettingsItem("Профили", "Добавить профиль", "Отдельные ряды, папки и скрытые приложения. Максимум 8 профилей.", enabled = !busy && profiles.size < 8) {
        name = ""; creating = true
    }
    SettingsItem("Профили", "PIN текущего профиля", "4–8 цифр. PIN владельца защищает изменение настроек во всех профилях.", enabled = !busy) {
        pin = ""; confirmation = ""; settingPin = true
    }
    Text("PIN ограничивает настройки и вход в профиль внутри EZCH. Системные экраны Android и список приложений доступны по правилам ОС.", color = Muted)
    if (message.isNotEmpty()) Text(message)
    if (creating || settingPin) AlertDialog(onDismissRequest = { if (!busy) { creating = false; settingPin = false } },
        title = { Text(if (creating) "Новый профиль" else "Новый PIN") }, text = { Column {
            if (creating) OutlinedTextField(name, { name = it.take(40) }, label = { Text("Имя профиля") }, singleLine = true)
            else {
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("Новый PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                OutlinedTextField(confirmation, { confirmation = it.filter(Char::isDigit).take(8) }, label = { Text("Повторите PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
            }
        } }, confirmButton = { TextButton(enabled = !busy && if (creating) name.isNotBlank() else pin.length in 4..8 && pin == confirmation, onClick = {
            busy = true
            val makeProfile = creating; val enteredPin = pin; val enteredName = name.trim()
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    val updated = if (makeProfile) Profiles.list(context) + DesktopProfile(java.util.UUID.randomUUID().toString(), enteredName)
                    else Profiles.list(context).map { if (it.id == Profiles.active(context)) it.copy(credential = PinCredential.create(enteredPin)) else it }
                    Profiles.save(context, updated)
                }
                busy = false; pin = ""; confirmation = ""
                if (ok) { creating = false; settingPin = false; profiles = Profiles.list(context); message = "Сохранено"; onChange() }
                else message = "Не удалось сохранить"
            }
        }) { Text("Сохранить") } }, dismissButton = { TextButton(enabled = !busy, onClick = { creating = false; settingPin = false }) { Text("Отмена") } })
}

@Composable internal fun BackupControls(onRestored: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var copies by remember { mutableStateOf<List<java.io.File>?>(null) }
    fun saveLocal() {
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { DesktopBackup.saveLocal(context) } }
            busy = false
            message = result.fold({ "Копия сохранена: ${it.absolutePath}" }, { "Не удалось записать копию" })
        }
    }
    fun showCopies() {
        busy = true
        scope.launch {
            copies = withContext(Dispatchers.IO) { DesktopBackup.localCopies(context) }; busy = false
        }
    }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) { busy = true; scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val bytes = DesktopBackup.export(context).toByteArray(Charsets.UTF_8)
                require(bytes.size <= DesktopBackup.MAX_BYTES)
                checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { it.write(bytes) }
            } }
            busy = false; message = if (result.isSuccess) "Резервная копия сохранена" else "Не удалось записать копию"
        } }
    }
    val load = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { busy = true; scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytesBounded(DesktopBackup.MAX_BYTES) }
                val text = bytes.toString(Charsets.UTF_8); DesktopBackup.validate(text); text
            } }
            busy = false
            result.onSuccess { pending = it }.onFailure { message = "Копия повреждена или имеет неподдерживаемый формат" }
        } }
    }
    SettingsItem("Копия", "Сохранить резервную копию", "Ряды, папки, профили, PIN, оформление и источники. Без системного выбора файлов копия сохраняется в папку EZCH.", enabled = !busy) {
        if (DesktopBackup.documentPickerAvailable(context, true)) runCatching { save.launch("EZCH-Desktop-${java.time.LocalDate.now()}.json") }.onFailure { saveLocal() }
        else saveLocal()
    }
    SettingsItem("Копия", "Восстановить резервную копию", "Выберите JSON во внутренней памяти или на USB.", enabled = !busy) {
        if (DesktopBackup.documentPickerAvailable(context, false)) runCatching { load.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }.onFailure { showCopies() }
        else showCopies()
    }
    SettingsItem("Копия", "Копии в папке EZCH", "Восстановить локальную копию без системного выбора файлов.", enabled = !busy, onClick = ::showCopies)
    if (message.isNotEmpty()) Text(message)
    copies?.let { files -> AlertDialog(onDismissRequest = { if (!busy) copies = null }, title = { Text("Локальные копии EZCH") },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            if (files.isEmpty()) Text("Копий пока нет")
            files.forEach { file -> TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val text = file.inputStream().use { it.readBytesBounded(DesktopBackup.MAX_BYTES) }.toString(Charsets.UTF_8)
                    DesktopBackup.validate(text); text
                } }
                busy = false; copies = null
                result.onSuccess { pending = it }.onFailure { message = "Копия повреждена" }
            } }) { Text(file.name) } }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = { copies = null }) { Text("Закрыть") } }) }
    pending?.let { data -> AlertDialog(onDismissRequest = { if (!busy) pending = null }, title = { Text("Восстановить настройки?") },
        text = { Text("Настройки всех профилей будут заменены. Видео и изображения на другом устройстве нужно выбрать заново.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { DesktopBackup.restore(context, data) }.getOrDefault(false) }
            busy = false; pending = null; message = if (ok) "Настройки восстановлены" else "Восстановление не завершено"
            if (ok) onRestored()
        } }) { Text("Восстановить") } }, dismissButton = { TextButton(enabled = !busy, onClick = { pending = null }) { Text("Отмена") } }) }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(out.size() + count <= limit) { "Резервная копия слишком велика" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
