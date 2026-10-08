package com.example.homeezch
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FileBrowserActivity : ComponentActivity() {
    private var revision by mutableIntStateOf(0)
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { revision++ }
    override fun onResume() { super.onResume(); immersiveDesktop(); revision++ }
    private fun allowed()=if(Build.VERSION.SDK_INT>=30) Environment.isExternalStorageManager() else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
    private fun grantAccess() {
        if(Build.VERSION.SDK_INT>=30) SettingsRouter.open(this,listOf(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:$packageName")),Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)))
        else permissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }
    private fun drives(): List<File> {
        val result=mutableListOf(Environment.getExternalStorageDirectory())
        if(Build.VERSION.SDK_INT>=30) getSystemService(StorageManager::class.java).storageVolumes.mapNotNullTo(result) { it.directory }
        else getExternalFilesDirs(null).filterNotNull().mapTo(result) { File(it.path.substringBefore("/Android/")) }
        File("/storage").listFiles()?.filter { it.isDirectory && it.name !in listOf("emulated","self") }?.let(result::addAll)
        return result.distinctBy { it.absolutePath }
    }
    private fun open(file: File) {
        if (file.extension.equals("apk", true)) {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            startActivity(Intent(this, DocumentBrowserActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return
        }
        val uri=FileProvider.getUriForFile(this,"$packageName.files",file)
        val mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF59DAFF))) {
            val scope=rememberCoroutineScope();val list=rememberLazyListState();val first=remember { FocusRequester() }
            var folder by rememberSaveable { mutableStateOf(intent.getStringExtra("path")) }
            var files by remember { mutableStateOf<List<File>>(emptyList()) }
            var selected by remember { mutableStateOf<File?>(null) };var command by remember { mutableStateOf<String?>(null) }
            var name by remember { mutableStateOf("") };var clipboard by remember { mutableStateOf<Pair<File,Boolean>?>(null) }
            var busy by remember { mutableStateOf(false) };var message by remember { mutableStateOf("") }
            val granted=remember(revision) { allowed() }; var roots by remember { mutableStateOf<List<File>>(emptyList()) }
            LaunchedEffect(revision) { roots = withContext(Dispatchers.IO) { drives() } }
            fun perform(action: ()->Unit) {
                if(busy)return
                busy=true
                scope.launch {
                    val failure=withContext(Dispatchers.IO) { runCatching(action).exceptionOrNull() }
                    message=failure?.message ?: "Готово";busy=false;command=null;selected=null;revision++
                }
            }
            fun back() {
                val current=folder?.let(::File)
                folder=if(current==null || roots.any { it.absolutePath==current.absolutePath }) null else current.parent
            }
            LaunchedEffect(folder,revision,granted) {
                files=emptyList()
                if(folder!=null && granted) {
                    val result=withContext(Dispatchers.IO) { runCatching {
                        File(folder!!).listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: error("Не удалось прочитать папку. Проверьте накопитель и разрешение.")
                    } }
                    files=result.getOrDefault(emptyList());message=result.exceptionOrNull()?.message ?: ""
                }
            }
            LaunchedEffect(folder,files,granted,revision) { list.scrollToItem(0);withFrameNanos { };runCatching { first.requestFocus() } }
            BackHandler(folder!=null) { if(!busy)back() }
            Column(Modifier.fillMaxSize().background(Color(0xFF08111D)).padding(24.dp)) {
                Text("Файлы EZCH",style=MaterialTheme.typography.headlineMedium);Text(folder ?: "Выберите накопитель")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FileButton({ startActivity(Intent(this@FileBrowserActivity, DocumentBrowserActivity::class.java)) },enabled=!busy) { Text("Выбрать папку / USB") }
                    FileButton({ if(folder==null)finish() else back() },enabled=!busy) { Text("Назад") }
                    FileButton({ folder=null },enabled=!busy) { Text("Накопители") }
                    FileButton({ revision++ },enabled=!busy) { Text("Обновить") }
                    if(folder!=null && granted) {
                        FileButton({ name="";command="mkdir" },modifier=if(files.isEmpty())Modifier.focusRequester(first) else Modifier,enabled=!busy) { Text("Новая папка") }
                        clipboard?.let { clip -> FileButton({ val dest=folder!!;perform { FileOperations.copy(clip.first,File(dest),clip.second) };clipboard=null },enabled=!busy) { Text("Вставить") } }
                    }
                }
                if(!granted) {
                    Text("Разрешите встроенному проводнику доступ к файлам в настройках Android.")
                    FileButton({ grantAccess() },Modifier.focusRequester(first)) { Text("Разрешить доступ к файлам") }
                }
                Text(if(busy) "Выполняется…" else message,color=Color(0xFF59DAFF))
                LazyColumn(Modifier.weight(1f),state=list) {
                    if(folder==null) items(roots,key={ it.path }) { root ->
                        FileButton({ folder=root.path },Modifier.fillMaxWidth().then(if(granted && root==roots.firstOrNull())Modifier.focusRequester(first) else Modifier),!busy) {
                            Text((if(root==Environment.getExternalStorageDirectory()) "Внутренняя память" else "USB / накопитель")+" · ${root.path}")
                        }
                    } else items(files,key={it.path}) { file ->
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            FileButton({
                                if(java.nio.file.Files.isSymbolicLink(file.toPath()))message="Символические ссылки не поддерживаются"
                                else if(file.isDirectory)folder=file.path
                                else runCatching { open(file) }.onFailure { message="Не удалось открыть файл: ${it.message ?: "нет подходящего приложения"}" }
                            },Modifier.weight(1f).then(if(file==files.firstOrNull())Modifier.focusRequester(first) else Modifier),!busy) { Text((if(file.isDirectory) "Папка · " else "Файл · ")+file.name) }
                            FileButton({ selected=file },enabled=!busy) { Text("Действия") }
                        }
                    }
                }
            }
            selected?.let { file -> if(command==null) AlertDialog(onDismissRequest={selected=null},title={Text(file.name)},text={Column {
                Text(if(file.isDirectory) "Папка" else "${file.length()} байт")
                TextButton({clipboard=file to false;selected=null}) { Text("Копировать") }
                TextButton({clipboard=file to true;selected=null}) { Text("Вырезать") }
                TextButton({name=file.name;command="rename"}) { Text("Переименовать") }
                TextButton({command="delete"}) { Text("Удалить") }
            }},confirmButton={TextButton({selected=null}) {Text("Закрыть")} }) }
            command?.let { action -> AlertDialog(onDismissRequest={if(!busy)command=null},title={Text(when(action) {"mkdir"->"Новая папка";"rename"->"Переименовать";else->"Удалить ${selected?.name}?"})},text={
                if(action=="delete")Text("Файл или папка будут удалены без возможности восстановления.")
                else OutlinedTextField(name,{name=it},singleLine=true,label={Text("Имя")})
            },confirmButton={TextButton(enabled=!busy,onClick={
                val file=selected;val parent=folder?.let(::File);val confirmed=name
                perform { when(action) {
                    "mkdir"->{val dest=FileOperations.child(requireNotNull(parent),confirmed);require(!dest.exists()) {"Такое имя уже существует"};check(dest.mkdir()) {"Не удалось создать папку"}}
                    "rename"->FileOperations.rename(requireNotNull(file),confirmed)
                    "delete"->FileOperations.delete(requireNotNull(file))
                } }
            }) {Text("Подтвердить")}},dismissButton={TextButton(enabled=!busy,onClick={command=null}) {Text("Отмена")} }) }
        } }
    }
}
@Composable private fun FileButton(onClick: ()->Unit,modifier: Modifier=Modifier,enabled: Boolean=true,content: @Composable ()->Unit) {
    var focused by remember { mutableStateOf(false) }
    Button(onClick=onClick,enabled=enabled,modifier=modifier.onFocusChanged { focused=it.isFocused }.border(if(focused)3.dp else 0.dp,if(focused)Color(0xFF9DF2FF) else Color.Transparent,RoundedCornerShape(24.dp))) { content() }
}
