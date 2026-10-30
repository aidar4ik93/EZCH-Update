package com.example.homeezch

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class HardwareInspectionResult(val text: String, val optional: List<OptionalPackageState>, val protected: Set<String>)

/** Local read-only startup scan. Optional system changes are exported for the owner's ADB connection. */
internal object HardwareInspection {
    private val scheduled = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "hardware-inspection.json"))
    fun cached(context: Context): String? = runCatching {
        JSONObject(file(context).openRead().use { it.readBytes().toString(Charsets.UTF_8) }).getString("report")
    }.getOrNull()
    fun schedule(context: Context) {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 28 && android.app.Application.getProcessName() != app.packageName) return
        val prefs = app.getSharedPreferences("ezch_hardware_inspection", Context.MODE_PRIVATE)
        if (prefs.getInt("version", -1) == BuildConfig.VERSION_CODE && prefs.getString("build", null) == Build.FINGERPRINT && file(app).baseFile.exists()) return
        if (!scheduled.compareAndSet(false, true)) return
        worker.execute {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            runCatching { inspect(app) }.onFailure { EzchDiag.error("HARDWARE", "Автодиагностика не завершена", it); scheduled.set(false) }
        }
    }
    @Synchronized fun inspect(context: Context): HardwareInspectionResult {
        val pm = context.packageManager
        val am = requireNotNull(context.getSystemService(ActivityManager::class.java))
        val ram = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val storage = StatFs(Environment.getDataDirectory().path)
        val metrics = context.resources.displayMetrics
        val display = requireNotNull(context.getSystemService(android.hardware.display.DisplayManager::class.java)).getDisplay(0)
        val audio = requireNotNull(context.getSystemService(AudioManager::class.java))
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val protected = pm.queryIntentActivities(homeIntent, 0).map { it.activityInfo.packageName }.toSet() + context.packageName + setOf(
            "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings", "com.google.android.tv.settings",
            "com.google.android.gms", "com.google.android.gsf", "com.android.bluetooth", "com.android.adservices.api")
        val installed = OptimizationPolicy.catalog.mapNotNull { item -> runCatching {
            @Suppress("DEPRECATION") val info = pm.getApplicationInfo(item.packageName, 0)
            OptionalPackageState(item, pm.getApplicationEnabledSetting(item.packageName), info.enabled)
        }.getOrNull() }
        val candidates = OptimizationPolicy.selectable(installed, protected)
        val mb = 1024L * 1024L
        val report = buildString {
            appendLine("EZCH · автоматическая диагностика оборудования")
            appendLine(java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()))
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
            appendLine("Чип: ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE}; ядер: ${Runtime.getRuntime().availableProcessors()}")
            appendLine("Архитектуры: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("ОЗУ: ${ram.totalMem / mb} МБ; свободно: ${ram.availMem / mb} МБ")
            appendLine("Накопитель: свободно ${storage.availableBytes / mb} из ${storage.totalBytes / mb} МБ")
            appendLine("Интерфейс: ${metrics.widthPixels}×${metrics.heightPixels}; ${metrics.densityDpi} dpi")
            appendLine("Режим экрана: ${display?.mode?.physicalWidth ?: 0}×${display?.mode?.physicalHeight ?: 0}; ${display?.mode?.refreshRate ?: 0f} Гц")
            appendLine("HDR: ${if (display?.hdrCapabilities?.supportedHdrTypes?.isNotEmpty() == true) "поддерживается" else "не заявлен"}")
            appendLine("Поддержка графики: OpenGL ES ${am.deviceConfigurationInfo?.glEsVersion ?: "неизвестно"}")
            appendLine("Bluetooth: ${if (pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) "поддерживается" else "не заявлен"}")
            appendLine("Микрофон: ${if (pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) "есть" else "не заявлен прошивкой"}")
            appendLine("Фиксированная громкость: ${if (audio.isVolumeFixed) "да, управляет внешнее устройство" else "нет"}")
            appendLine("Рекомендуемый профиль: ${if (am.isLowRamDevice || ram.totalMem < 2L*1024*mb) "облегчённый; для местной нейросети предпочтительна модель 0,5B" else "обычный; размер модели выбирается владельцем"}")
            appendLine("Необязательных модулей для рассмотрения: ${candidates.size}")
            installed.forEach { appendLine("${it.item.title}: ${it.item.packageName} — ${if (it.enabled) "включён" else "выключен"}") }
            appendLine("Основной и резервные HOME, звук, Bluetooth, системные настройки и системные рекламные API автоматически не отключаются.")
            appendLine("Реклама внутри приложений и все системные рекомендации этим планом не гарантированно устраняются.")
        }
        val data = JSONObject().put("version", BuildConfig.VERSION_CODE).put("report", report).toString(2).toByteArray(Charsets.UTF_8)
        val target = file(context); val stream = target.startWrite()
        try { stream.write(data); target.finishWrite(stream) } catch (e: Exception) { target.failWrite(stream); throw e }
        context.getSharedPreferences("ezch_hardware_inspection", Context.MODE_PRIVATE).edit()
            .putInt("version", BuildConfig.VERSION_CODE).putString("build", Build.FINGERPRINT).apply()
        EzchDiag.log("HARDWARE", "Проверка оборудования завершена; необязательных модулей: ${candidates.size}; системные изменения не выполнялись")
        return HardwareInspectionResult(report, candidates, protected)
    }
    fun export(context: Context, result: HardwareInspectionResult, selected: List<OptionalPackageState>,
               cleanup: Boolean = false, animations: Boolean = false): String {
        val prefix = "EZCH_${BuildConfig.VERSION_CODE}_${System.currentTimeMillis()}"
        // R25: returns the name actually stored. MediaStore appends ".txt" to a .sh file saved as text/plain
        // (GPT R24 defect 1), so scripts go as octet-stream and the real DISPLAY_NAME is read back.
        fun save(name: String, text: String): String {
            if (Build.VERSION.SDK_INT < 29) {
                val folder = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS))
                File(folder, name).writeText(text, Charsets.UTF_8)
                return name
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".sh")) "application/octet-stream" else "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/EZCH")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Не удалось создать файл")
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("Файл недоступен")
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch(e: Exception) { context.contentResolver.delete(uri,null,null); throw e }
            return runCatching {
                context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull() ?: name
        }
        val report = save("${prefix}_hardware.txt", result.text)
        val disable = save("${prefix}_disable.sh", OptimizationPolicy.script(selected, result.protected, Build.FINGERPRINT, false))
        val restore = save("${prefix}_restore.sh", OptimizationPolicy.script(selected, result.protected, Build.FINGERPRINT, true))
        val names = mutableListOf(report, disable, restore)
        if (cleanup) {
            val previous = OptimizationPolicy.animationKeys.map { key ->
                runCatching { android.provider.Settings.Global.getString(context.contentResolver, key) }.getOrNull()
                    ?.trim()?.takeIf { it.matches(Regex("[0-9]{1,2}(\\.[0-9]{1,3})?")) }
            }
            names += save("${prefix}_cleanup.sh", OptimizationPolicy.cleanup(Build.FINGERPRINT, animations, previous, false))
            names += save("${prefix}_cleanup_restore.sh", OptimizationPolicy.cleanup(Build.FINGERPRINT, animations, previous, true))
        }
        val location = if (Build.VERSION.SDK_INT >= 29) "Download/EZCH" else context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)?.absolutePath.orEmpty()
        return "$location/" + names.joinToString("\n")
    }
}
