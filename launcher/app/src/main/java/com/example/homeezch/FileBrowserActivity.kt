package com.example.homeezch

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.homeezch.install.AppInstaller
import com.example.homeezch.install.InstallEvents
import com.example.homeezch.install.InstallSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileBrowserActivity : ComponentActivity() {
    private var root by mutableStateOf<Uri?>(null)
    private var message by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var pendingApk: File? = null
    private var ownSession: Int? = null
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree(), ::folderPicked)
    private fun folderPicked(uri: Uri?) {
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                getSharedPreferences("files", Context.MODE_PRIVATE).edit().putString("root", uri.toString()).apply()
                root = uri
            }.onFailure { message = "Не удалось получить доступ к папке: ${it.message}" }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = getSharedPreferences("files", Context.MODE_PRIVATE).getString("root", null)?.let(Uri::parse)
        ownSession = savedInstanceState?.getInt("session", -1)?.takeIf { it >= 0 }
            ?: getSharedPreferences("files", Context.MODE_PRIVATE).getInt("session", -1).takeIf { it >= 0 }
        InstallEvents.restore(this)
        savedInstanceState?.getString("pending")?.let { name ->
            val candidate = File(cacheDir, "local-apk/$name")
            if (name.matches(Regex("[a-zA-Z0-9-]+\\.apk")) && candidate.isFile) pendingApk = candidate
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                InstallEvents.snapshot.collect { result ->
                    handleResult(result)
                }
            }
        }
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Ice)) { Browser() } }
    }
    private fun handleResult(result: InstallSnapshot?) {
        if (result == null || result.sessionId != ownSession) return
        if (result.status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            result.confirmationIntent?.let { confirmation ->
                runCatching { startActivity(Intent(confirmation)) }
                    .onFailure { message = "Android не открыл окно установки"; busy = false }
                InstallEvents.consumeConfirmation(result.sessionId)
            }
        } else {
            message = if (result.status == PackageInstaller.STATUS_SUCCESS) "Приложение установлено" else "Установка отклонена: ${result.message.orEmpty()}"
            busy = false; ownSession = null
            getSharedPreferences("files", Context.MODE_PRIVATE).edit().remove("session").apply()
            pendingApk?.delete(); pendingApk = null
            InstallEvents.consume(this, result.sessionId)
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending", pendingApk?.name); ownSession?.let { outState.putInt("session", it) }
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        if (pendingApk != null && ownSession == null) {
            if (AppInstaller().canRequestPackageInstalls(this)) commitPending()
            else { busy = false; message = "Разрешение на установку не предоставлено. Можно повторить выбор APK."; pendingApk?.delete(); pendingApk = null }
        }
    }
    private fun commitPending() {
        val file = pendingApk ?: return
        busy = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AppInstaller().installLocalFile(this@FileBrowserActivity, file) }
            result.onSuccess {
                ownSession = it
                getSharedPreferences("files", Context.MODE_PRIVATE).edit().putInt("session", it).apply()
                message = "Подтвердите установку в окне Android"
                handleResult(InstallEvents.snapshot.value)
            }
                .onFailure { busy = false; message = it.message; file.delete(); pendingApk = null }
        }
    }
    private fun install(file: DocumentFile) {
        if (busy) return
        busy = true
        lifecycleScope.launch {
            runCatching {
                pendingApk = withContext(Dispatchers.IO) {
                    val copy = File(File(cacheDir, "local-apk").apply { mkdirs() }, "${java.util.UUID.randomUUID()}.apk")
                    try {
                        val input = contentResolver.openInputStream(file.uri) ?: error("Не удалось открыть APK")
                        input.use { stream -> copy.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024); var bytes = 0L
                            while (true) { val count = stream.read(buffer); if (count < 0) break; bytes += count; check(bytes <= 1024L * 1024 * 1024) { "APK превышает 1 ГБ" }; output.write(buffer, 0, count) }
                        } }
                        copy
                    } catch (failure: Exception) { copy.delete(); throw failure }
                }
                if (AppInstaller().canRequestPackageInstalls(this@FileBrowserActivity)) commitPending()
                else startActivity(AppInstaller().unknownSourcesSettingsIntent(this@FileBrowserActivity))
            }.onFailure { busy = false; message = it.message; pendingApk?.delete(); pendingApk = null }
        }
    }
    @Composable private fun Browser() {
        var path by remember(root) { mutableStateOf<List<DocumentFile>>(root?.let { DocumentFile.fromTreeUri(this, it) }?.let(::listOf).orEmpty()) }
        var revision by remember { mutableIntStateOf(0) }
        var selected by remember { mutableStateOf<DocumentFile?>(null) }
        var clipboard by remember { mutableStateOf<Pair<DocumentFile, Boolean>?>(null) }
        var action by remember { mutableStateOf<String?>(null) }
        var name by remember { mutableStateOf("") }
        val folder = path.lastOrNull()
        val files by produceState<List<DocumentFile>>(emptyList(), folder?.uri, revision) {
            value = withContext(Dispatchers.IO) { runCatching { folder?.listFiles()?.sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() }).orEmpty() }
                .getOrElse { message = "Папка недоступна. Подключите накопитель или выберите папку заново."; emptyList() } }
        }
        BackHandler(path.size > 1 && !busy) { path = path.dropLast(1) }
        fun operate(block: () -> Unit) {
            busy = true
            lifecycleScope.launch {
                runCatching { withContext(Dispatchers.IO) { block() } }.onSuccess { message = "Готово" }.onFailure { message = it.message }
                busy = false; revision++; selected = null; action = null
            }
        }
        Surface(Modifier.fillMaxSize(), color = Night) {
            Column(Modifier.fillMaxSize().padding(28.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton(onClick = { finish() }) { Text("Рабочий стол") }
                    TextButton(enabled = !busy, onClick = { runCatching { pickFolder.launch(null) }.onFailure { message = "В прошивке нет окна выбора папки. Обратитесь к производителю ТВ." } }) { Text("Выбрать папку / USB") }
                    TextButton(enabled = path.size > 1 && !busy, onClick = { path = path.dropLast(1) }) { Text("Вверх") }
                    TextButton(enabled = !busy, onClick = { revision++ }) { Text("Обновить") }
                    TextButton(enabled = clipboard != null && folder != null && !busy, onClick = {
                        val clip = clipboard ?: return@TextButton; val target = folder ?: return@TextButton
                        operate { if (clip.second) FileOperations.move(this@FileBrowserActivity, clip.first, target) else FileOperations.copy(this@FileBrowserActivity, clip.first, target) }
                        clipboard = null
                    }) { Text("Вставить") }
                }
                Text(path.joinToString(" / ") { it.name ?: "Папка" }, color = Muted)
                if (folder == null) InfoPanel("Нажмите «Выбрать папку / USB». Android один раз запросит доступ, затем файлы будут доступны здесь.")
                message?.let { Text(it, color = Ice, modifier = Modifier.padding(vertical = 8.dp)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(files, key = { it.uri.toString() }) { file ->
                        TvAction((if (file.isDirectory) "▸ " else "") + (file.name ?: "Файл"), Modifier.fillMaxWidth()) {
                            if (!busy) { if (file.isDirectory) path = path + file else { selected = file; name = file.name.orEmpty() } }
                        }
                    }
                }
            }
        }
        selected?.let { file ->
            AlertDialog(onDismissRequest = { if (!busy) { selected = null; action = null } }, title = { Text(file.name.orEmpty()) },
                text = { Column {
                    when (action) {
                        "delete" -> Text("Удалить этот файл? Это действие нельзя отменить.")
                        "rename" -> OutlinedTextField(name, { name = it }, label = { Text("Новое имя") })
                        else -> {
                            if (file.name?.endsWith(".apk", true) == true) TextButton(enabled = !busy, onClick = { selected = null; install(file) }) { Text("Установить APK") }
                            TextButton(onClick = { clipboard = file to false; selected = null; message = "Откройте папку назначения и нажмите «Вставить»" }) { Text("Копировать") }
                            TextButton(onClick = { clipboard = file to true; selected = null; message = "Откройте папку назначения и нажмите «Вставить»" }) { Text("Переместить") }
                            TextButton(onClick = { action = "rename" }) { Text("Переименовать") }
                            TextButton(onClick = { action = "delete" }) { Text("Удалить") }
                            TextButton(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.type ?: "application/octet-stream").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { message = "Нет приложения для открытия этого файла" }; selected = null }) { Text("Открыть") }
                        }
                    }
                } }, confirmButton = { if (action != null) TextButton(enabled = !busy, onClick = {
                    val requestedAction = action; val requestedName = name
                    operate { if (requestedAction == "delete") check(file.delete()) { "Не удалось удалить файл" } else FileOperations.rename(file, requestedName) }
                }) { Text("Подтвердить") } }, dismissButton = { TextButton(onClick = { selected = null; action = null }) { Text("Отмена") } })
        }
    }
}
