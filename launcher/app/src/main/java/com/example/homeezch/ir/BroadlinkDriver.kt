package com.example.homeezch.ir

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.Calendar
import java.util.TimeZone
import kotlin.coroutines.coroutineContext

/**
 * BroadLink driver (RM mini 3 B / RM4 / RM4C family) over the local UDP protocol — no cloud, no account.
 * One instance per controller. Everything that reports success is backed by a real answer of the controller:
 * a send is Ok only when the controller acknowledged the frame with error code 0.
 *
 * Nothing secret is stored: the session key is obtained by the protocol's authorization on every connect
 * and lives only in memory.
 */
class BroadlinkDriver(private val context: Context) : IrControllerDriver {
    override val id = DRIVER_ID
    @Volatile override var state: IrState = IrState.DISCONNECTED; private set
    @Volatile var info: IrControllerInfo? = null; private set
    @Volatile var lastError: String? = null; private set

    private val io = Mutex()
    private var address: InetAddress? = null
    private var mac = ByteArray(6)
    private var devType = 0
    private var sessionId = 0
    private var key: ByteArray = BroadlinkProtocol.INIT_KEY
    private var authorized = false
    private var count = (Math.random() * 0xFFFF).toInt()

    override suspend fun discover(timeoutMs: Long): List<IrControllerInfo> = scan(context, null, timeoutMs)

    override suspend fun connect(info: IrControllerInfo): IrResult<Unit> {
        if (!info.knownModel) {
            state = IrState.UNSUPPORTED
            return fail("Модель ${info.model} (тип 0x%04X) не входит в список проверенных — команды не отправляются".format(info.devType), IrState.UNSUPPORTED)
        }
        if (info.locked) return fail("Контроллер заблокирован в приложении BroadLink/BestCon. Откройте его настройки и выключите «Блокировку устройства»")
        state = IrState.CONNECTING
        this.info = info
        address = runCatching { withContext(Dispatchers.IO) { InetAddress.getByName(info.host) } }.getOrNull()
            ?: return fail("Неверный адрес ${info.host}")
        mac = BroadlinkProtocol.hex(info.mac.split(":").reversed().joinToString(""))
        devType = info.devType
        authorized = false
        return when (val a = authenticate()) {
            is IrResult.Ok -> { state = IrState.CONNECTED; IrResult.Ok(Unit) }
            is IrResult.Fail -> a
        }
    }

    override suspend fun authenticate(): IrResult<ByteArray> = io.withLock { authLocked() }

    private suspend fun authLocked(): IrResult<ByteArray> {
        if (address == null) return fail("Контроллер не выбран", IrState.DISCONNECTED)
        sessionId = 0; key = BroadlinkProtocol.INIT_KEY; authorized = false
        val resp = exchange(BroadlinkProtocol.CMD_AUTH, BroadlinkProtocol.authPayload()) ?: return fail(lastError ?: "Контроллер не ответил")
        val err = BroadlinkProtocol.errorCode(resp)
        if (err != 0) return fail("Авторизация отклонена: ${BroadlinkProtocol.errorText(err)}")
        val parsed = BroadlinkProtocol.parseAuth(BroadlinkProtocol.decrypt(BroadlinkProtocol.INIT_KEY, resp.copyOfRange(0x38, resp.size)))
            ?: return fail("Неполный ответ авторизации")
        sessionId = parsed.first; key = parsed.second; authorized = true
        if (state != IrState.LEARNING && state != IrState.SENDING) state = IrState.CONNECTED
        lastError = null
        return IrResult.Ok(parsed.second.copyOf())
    }

    /** One RM framed command; re-authorizes once when the controller says the session is gone. Returns the answer data. */
    private suspend fun rm(command: Int, data: ByteArray = ByteArray(0)): Pair<Int, ByteArray?> = io.withLock {
        if (!authorized) (authLocked() as? IrResult.Fail)?.let { return@withLock Int.MIN_VALUE to null }
        for (attempt in 0..1) {
            val resp = exchange(BroadlinkProtocol.CMD_RM, BroadlinkProtocol.rmPayload(command, data)) ?: return@withLock Int.MIN_VALUE to null
            val err = BroadlinkProtocol.errorCode(resp)
            if (attempt == 0 && (err == -1 || err == -2 || err == -7)) {
                if (authLocked() is IrResult.Fail) return@withLock err to null
                continue
            }
            if (err != 0) return@withLock err to null
            return@withLock 0 to BroadlinkProtocol.rmData(BroadlinkProtocol.decrypt(key, resp.copyOfRange(0x38, resp.size)))
        }
        Int.MIN_VALUE to null
    }

    override suspend fun startLearning(): IrResult<Unit> {
        if (!ready()) return notReady()
        state = IrState.LEARNING
        val (err, _) = rm(BroadlinkProtocol.RM_ENTER_LEARNING)
        return if (err == 0) IrResult.Ok(Unit) else { state = IrState.CONNECTED; fail("Режим обучения не включился: ${errText(err)}") }
    }

    override suspend fun stopLearning(timeoutMs: Long): IrResult<ByteArray?> {
        if (!ready()) return notReady()
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                coroutineContext.ensureActive()
                delay(1_000)
                val (err, data) = rm(BroadlinkProtocol.RM_CHECK_DATA)
                when {
                    err == 0 && data != null && data.isNotEmpty() -> return IrResult.Ok(data)
                    err == 0 || BroadlinkProtocol.isNoDataYet(err) -> continue
                    else -> return fail("Ошибка при приёме сигнала: ${errText(err)}")
                }
            }
            return IrResult.Ok(null)
        } finally { if (state == IrState.LEARNING) state = IrState.CONNECTED }
    }

    override suspend fun sendCommand(code: ByteArray): IrResult<Unit> {
        if (!ready()) return notReady()
        if (code.isEmpty()) return fail("Пустой сигнал — кнопку нужно обучить заново", IrState.CONNECTED)
        state = IrState.SENDING
        val (err, _) = rm(BroadlinkProtocol.RM_SEND, code)
        state = if (err == 0) IrState.CONNECTED else IrState.ERROR
        return if (err == 0) IrResult.Ok(Unit) else fail("Контроллер не подтвердил отправку: ${errText(err)}")
    }

    override suspend fun getStatus(): IrResult<String> {
        val i = info ?: return fail("Контроллер не выбран", IrState.DISCONNECTED)
        if (!i.knownModel) return fail("Не поддерживается", IrState.UNSUPPORTED)
        return when (val a = authenticate()) {
            is IrResult.Ok -> IrResult.Ok("${i.model} · ${i.host} · авторизация выполнена, контроллер отвечает")
            is IrResult.Fail -> a
        }
    }

    override fun disconnect() {
        authorized = false; sessionId = 0; key = BroadlinkProtocol.INIT_KEY
        state = IrState.DISCONNECTED
    }

    private fun ready() = info?.knownModel == true && address != null && state != IrState.UNSUPPORTED
    private fun notReady(): IrResult.Fail =
        if (info?.knownModel == false) IrResult.Fail("Модель не поддерживается", IrState.UNSUPPORTED) else IrResult.Fail("Контроллер не подключён", IrState.DISCONNECTED)
    private fun errText(err: Int) = if (err == Int.MIN_VALUE) (lastError ?: "нет ответа") else BroadlinkProtocol.errorText(err)
    private fun fail(message: String, s: IrState = IrState.ERROR): IrResult.Fail { lastError = message; state = s; return IrResult.Fail(message, s) }

    /** Sends one encrypted packet and waits for a valid answer (1 s per try, up to [TRIES] tries). Null = no answer. */
    private suspend fun exchange(command: Int, payload: ByteArray): ByteArray? = withContext(Dispatchers.IO) {
        val target = address ?: return@withContext null
        count = ((count + 1) or 0x8000) and 0xFFFF
        val packet = BroadlinkProtocol.commandPacket(devType, command, count, mac, sessionId, key, payload)
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 1_000
                val buf = ByteArray(2048)
                repeat(TRIES) {
                    ensureActive()
                    socket.send(DatagramPacket(packet, packet.size, target, BroadlinkProtocol.PORT))
                    val deadline = System.currentTimeMillis() + 1_000
                    while (System.currentTimeMillis() < deadline) {
                        val answer = DatagramPacket(buf, buf.size)
                        try { socket.receive(answer) } catch (_: SocketTimeoutException) { break }
                        val r = buf.copyOf(answer.length)
                        if (answer.address == target && BroadlinkProtocol.responseValid(r) && r.size >= 0x38) return@withContext r
                    }
                }
            }
            lastError = "Контроллер ${target.hostAddress} не ответил"
            null
        } catch (e: java.io.IOException) {
            lastError = "Сеть недоступна: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    companion object {
        const val DRIVER_ID = "broadlink"
        private const val TRIES = 3

        /** Local IPv4 addresses with their broadcast addresses (Wi-Fi and Ethernet). */
        fun localNetworks(): List<Pair<Inet4Address, InetAddress?>> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.flatMap { n ->
                n.interfaceAddresses.mapNotNull { a -> (a.address as? Inet4Address)?.takeIf { it.isSiteLocalAddress }?.let { it to a.broadcast } }
            }
        }.getOrDefault(emptyList())

        /**
         * Discovery: the hello broadcast on every local network (or a hello sent straight to [host] for the manual IP).
         * Every answer is a real device; nothing is invented. Unknown device types are returned as not supported.
         */
        suspend fun scan(context: Context, host: String?, timeoutMs: Long): List<IrControllerInfo> = withContext(Dispatchers.IO) {
            val networks = localNetworks()
            if (networks.isEmpty()) return@withContext emptyList()
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = runCatching { wifi?.createMulticastLock("ezch-ir")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
            val found = LinkedHashMap<String, IrControllerInfo>()
            try {
                val directTarget = host?.let { runCatching { InetAddress.getByName(it.trim()) }.getOrNull() ?: return@withContext emptyList() }
                val sockets = networks.mapNotNull { (ip, bcast) ->
                    runCatching {
                        DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 300; bind(InetSocketAddress(ip, 0)) } to (ip to bcast)
                    }.getOrNull()
                }
                try {
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        ensureActive()
                        for ((socket, net) in sockets) {
                            val (ip, bcast) = net
                            val hello = helloNow(ip.address, socket.localPort)
                            val targets = if (directTarget != null) listOf(directTarget)
                            else listOfNotNull(bcast, runCatching { InetAddress.getByName("255.255.255.255") }.getOrNull()).distinct()
                            for (t in targets) runCatching { socket.send(DatagramPacket(hello, hello.size, t, BroadlinkProtocol.PORT)) }
                        }
                        val roundEnd = minOf(deadline, System.currentTimeMillis() + 1_000)
                        while (System.currentTimeMillis() < roundEnd) {
                            for ((socket, _) in sockets) {
                                val buf = ByteArray(1024); val p = DatagramPacket(buf, buf.size)
                                try { socket.receive(p) } catch (_: SocketTimeoutException) { continue } catch (_: java.io.IOException) { continue }
                                val h = BroadlinkProtocol.parseHello(buf.copyOf(p.length)) ?: continue
                                val macText = BroadlinkProtocol.macText(h.mac)
                                val model = BroadlinkProtocol.RM_FRAMED[h.devType]
                                found[macText] = IrControllerInfo(DRIVER_ID, p.address.hostAddress.orEmpty(), macText, h.devType,
                                    h.name.ifBlank { model ?: "BroadLink" }, model ?: "BroadLink 0x%04X".format(h.devType), h.locked, model != null)
                            }
                        }
                        if (directTarget != null && found.isNotEmpty()) break
                    }
                } finally { sockets.forEach { runCatching { it.first.close() } } }
            } finally { runCatching { lock?.release() } }
            found.values.toList()
        }

        private fun helloNow(ip: ByteArray, port: Int): ByteArray {
            val c = Calendar.getInstance()
            val tz = TimeZone.getDefault().getOffset(c.timeInMillis) / 3_600_000
            val iso = ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1 // Calendar: Sunday=1 … ISO: Monday=1 … Sunday=7
            return BroadlinkProtocol.helloPacket(ip, port, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), iso, tz)
        }
    }
}
