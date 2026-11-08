package com.example.homeezch.ir

import android.content.Context
import android.os.Environment
import com.example.homeezch.home.DeviceCommand
import com.example.homeezch.home.DeviceType
import com.example.homeezch.home.SmartDevice
import com.example.homeezch.home.SmartProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * EVA Universal IR Manager: local storage, one driver per controller, sending, learning, scenarios, export/import.
 * Works fully offline — the learned signals live on the TV and go straight to the controller in the home network.
 */
object IrManager {
    private const val PREFS = "ezch_ir_manager"
    private const val KEY = "data"

    private val _data = MutableStateFlow(IrData())
    val data: StateFlow<IrData> get() = _data
    @Volatile private var loaded = false
    private val drivers = HashMap<String, BroadlinkDriver>()
    /** Result of the last real exchange per controller (mac → ok). Unknown until the controller is used. */
    private val lastOk = HashMap<String, Boolean>()

    fun load(context: Context): IrData {
        if (!loaded) synchronized(this) {
            if (!loaded) {
                val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                _data.value = raw?.let { runCatching { IrJson.read(JSONObject(it)) }.getOrNull() } ?: IrData()
                loaded = true
            }
        }
        return _data.value
    }

    @Synchronized fun update(context: Context, change: (IrData) -> IrData): IrData {
        val next = change(load(context))
        // commit(): a learned signal must survive an immediate power cut of the box.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, IrJson.write(next).toString()).commit()
        _data.value = next
        return next
    }

    fun status(mac: String): Boolean? = synchronized(lastOk) { lastOk[mac] }
    private fun mark(mac: String, ok: Boolean) = synchronized(lastOk) { lastOk[mac] = ok }

    fun info(rec: IrControllerRec): IrControllerInfo {
        val model = BroadlinkProtocol.RM_FRAMED[rec.devType]
        return IrControllerInfo(rec.driver, rec.host, rec.mac, rec.devType, rec.name, model ?: rec.model, false, model != null)
    }

    /** The driver of one connected controller; connects (authorizes) on first use. */
    suspend fun driver(context: Context, mac: String): IrResult<BroadlinkDriver> {
        val rec = load(context).controller(mac) ?: return IrResult.Fail("Контроллер для этого пульта не найден — подключите его заново", IrState.DISCONNECTED)
        val d = synchronized(drivers) { drivers.getOrPut(mac) { BroadlinkDriver(context.applicationContext) } }
        if (d.state == IrState.CONNECTED || d.state == IrState.SENDING || d.state == IrState.LEARNING) return IrResult.Ok(d)
        return when (val r = d.connect(info(rec))) {
            is IrResult.Ok -> { mark(mac, true); IrResult.Ok(d) }
            is IrResult.Fail -> {
                // The address may have changed (DHCP): look for the same controller by its MAC and retry once.
                val moved = runCatching { BroadlinkDriver.scan(context, null, 3_000) }.getOrDefault(emptyList()).firstOrNull { it.mac == mac && it.host != rec.host }
                if (moved != null) {
                    update(context) { s -> s.copy(controllers = s.controllers.map { if (it.mac == mac) it.copy(host = moved.host) else it }) }
                    when (val again = d.connect(moved)) {
                        is IrResult.Ok -> { mark(mac, true); IrResult.Ok(d) }
                        is IrResult.Fail -> { mark(mac, false); again }
                    }
                } else { mark(mac, false); r }
            }
        }
    }

    /** Connects a found controller and stores it (identifiers only). Fails honestly when the protocol does not answer. */
    suspend fun connect(context: Context, info: IrControllerInfo): IrResult<Unit> {
        val d = synchronized(drivers) { drivers.getOrPut(info.mac) { BroadlinkDriver(context.applicationContext) } }
        val r = d.connect(info)
        mark(info.mac, r is IrResult.Ok)
        if (r is IrResult.Ok) update(context) { s ->
            val rec = IrControllerRec(info.driver, info.mac, info.host, info.devType, info.name, info.model)
            s.copy(controllers = s.controllers.filter { it.mac != info.mac } + rec)
        }
        return r
    }

    suspend fun check(context: Context, mac: String): IrResult<String> = when (val d = driver(context, mac)) {
        is IrResult.Fail -> d
        is IrResult.Ok -> d.value.getStatus().also { mark(mac, it is IrResult.Ok) }
    }

    fun forget(context: Context, mac: String) {
        synchronized(drivers) { drivers.remove(mac)?.disconnect() }
        update(context) { s -> s.copy(controllers = s.controllers.filter { it.mac != mac }) }
    }

    /** Learning: enter the mode, wait up to [timeoutMs] for the signal. Ok(null) = nothing was received. */
    suspend fun learn(context: Context, mac: String, timeoutMs: Long = 30_000): IrResult<ByteArray?> {
        val d = when (val r = driver(context, mac)) { is IrResult.Fail -> return r; is IrResult.Ok -> r.value }
        (d.startLearning() as? IrResult.Fail)?.let { mark(mac, false); return it }
        return d.stopLearning(timeoutMs).also { mark(mac, it is IrResult.Ok) }
    }

    /** One real transmission. Ok only after the controller acknowledged it. */
    suspend fun press(context: Context, deviceId: String, buttonId: String): IrResult<Unit> {
        val s = load(context)
        val device = s.device(deviceId) ?: return IrResult.Fail("Пульт удалён")
        val button = device.buttons.firstOrNull { it.id == buttonId } ?: return IrResult.Fail("Кнопка удалена")
        val d = when (val r = driver(context, device.controller)) { is IrResult.Fail -> return r; is IrResult.Ok -> r.value }
        return d.sendCommand(button.code).also { mark(device.controller, it is IrResult.Ok) }
    }

    /** Runs a scenario step by step; stops at the first step the controller did not confirm and says which one. */
    suspend fun run(context: Context, scenarioId: String, progress: (String) -> Unit = {}): IrResult<Unit> {
        val s = load(context)
        val sc = s.scenarios.firstOrNull { it.id == scenarioId } ?: return IrResult.Fail("Сценарий удалён")
        if (sc.steps.isEmpty()) return IrResult.Fail("В сценарии «${sc.name}» нет шагов")
        sc.steps.forEachIndexed { i, step ->
            val dev = s.device(step.deviceId); val btn = dev?.buttons?.firstOrNull { it.id == step.buttonId }
            if (dev == null || btn == null) return IrResult.Fail("Шаг ${i + 1}: пульт или кнопка удалены")
            repeat(step.repeat) {
                progress("Шаг ${i + 1}/${sc.steps.size}: ${dev.name} — ${btn.name}")
                (press(context, dev.id, btn.id) as? IrResult.Fail)?.let { return IrResult.Fail("Шаг ${i + 1} («${dev.name}: ${btn.name}») не выполнен: ${it.message}") }
                if (step.repeat > 1) delay(350)
            }
            if (i < sc.steps.lastIndex) delay(step.delayMs)
        }
        return IrResult.Ok(Unit)
    }

    /** Ai EvA voice hook: handles only phrases that name the owner's IR remotes / scenarios. Null = not an IR command. */
    suspend fun voice(context: Context, text: String): Pair<Boolean, String>? {
        val s = load(context)
        if (s.devices.isEmpty() && s.scenarios.isEmpty()) return null
        return when (val m = IrVoice.match(text, s) ?: return null) {
            is IrVoice.Match.Press -> when (val r = press(context, m.deviceId, m.buttonId)) {
                is IrResult.Ok -> true to m.say
                is IrResult.Fail -> false to "${m.say} — не отправлено: ${r.message}"
            }
            is IrVoice.Match.Scenario -> when (val r = run(context, m.scenarioId)) {
                is IrResult.Ok -> true to "${m.say} выполнен"
                is IrResult.Fail -> false to r.message
            }
        }
    }

    // ---- export / import (ТЗ §1.10) ----
    fun backupFile(context: Context): File {
        val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "EZCH")
        val dir = if (runCatching { pub.mkdirs() || pub.isDirectory }.getOrDefault(false) && pub.canWrite()) pub else (context.getExternalFilesDir(null) ?: context.filesDir)
        return File(dir, "ezch-ir-pults.json")
    }
    fun export(context: Context): Result<File> = runCatching {
        val f = backupFile(context)
        f.writeText(IrJson.write(load(context)).toString(2))
        f
    }
    fun importFile(context: Context, file: File = backupFile(context)): Result<IrData> = runCatching {
        require(file.isFile) { "Файл ${file.absolutePath} не найден" }
        val imported = IrJson.read(JSONObject(file.readText()))
        update(context) { IrJson.merge(it, imported) }
    }
}

/** IR remotes as a smart-home provider: they appear in «Умный дом», on desk tiles and in Eva's «включи …». */
object IrProvider : SmartProvider {
    override val id = "ir"
    override val title = "Универсальный ИК-пульт"
    override fun connected(context: Context) = IrManager.load(context).devices.isNotEmpty()

    fun type(c: IrCategory) = when (c) {
        IrCategory.TV, IrCategory.PROJECTOR -> DeviceType.TV
        IrCategory.AC -> DeviceType.AC
        IrCategory.LIGHT -> DeviceType.LIGHT
        IrCategory.FAN -> DeviceType.FAN
        else -> DeviceType.REMOTE
    }

    override suspend fun devices(context: Context): List<SmartDevice> = IrManager.load(context).devices.map { d ->
        SmartDevice("ir", d.id, d.name, d.room, type(d.category), online = IrManager.status(d.controller) != false,
            buttons = d.buttons.associate { it.name to it.id },
            canPower = d.buttons.any { it.function in setOf(IrFunction.POWER, IrFunction.POWER_ON, IrFunction.POWER_OFF) },
            infrared = d.controller)
    }

    override suspend fun send(context: Context, device: SmartDevice, command: DeviceCommand): Boolean {
        val d = IrManager.load(context).device(device.id) ?: return false
        val button = when (command) {
            is DeviceCommand.Button -> d.buttons.firstOrNull { it.id == command.id }
            is DeviceCommand.Power -> IrVoice.button(d, if (command.on) IrFunction.POWER_ON else IrFunction.POWER_OFF)
            else -> null
        } ?: return false
        return IrManager.press(context, d.id, button.id) is IrResult.Ok
    }
}
