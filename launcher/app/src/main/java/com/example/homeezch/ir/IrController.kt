package com.example.homeezch.ir

/**
 * EVA Universal IR Manager (ТЗ §3.2). One driver per controller family; the manager talks only to this interface,
 * so another family (a different IR hub) is added as one more driver without touching the screens or Eva's voice.
 */
enum class IrState { DISCONNECTED, DISCOVERING, CONNECTING, CONNECTED, LEARNING, SENDING, ERROR, UNSUPPORTED }

/** A controller as found on the network. [mac] is the controller's own identifier; nothing here is secret. */
data class IrControllerInfo(
    val driver: String, val host: String, val mac: String, val devType: Int, val name: String,
    val model: String, val locked: Boolean, val knownModel: Boolean,
)

/** Result of one operation: honest about what actually happened on the wire. */
sealed interface IrResult<out T> {
    data class Ok<T>(val value: T) : IrResult<T>
    data class Fail(val message: String, val state: IrState = IrState.ERROR) : IrResult<Nothing>
}

interface IrControllerDriver {
    val id: String
    val state: IrState
    /** Finds controllers of this family in the local network (broadcast, [timeoutMs]). */
    suspend fun discover(timeoutMs: Long = 3_000): List<IrControllerInfo>
    /** Opens the link to one controller (resolves the address, checks the family protocol answers). */
    suspend fun connect(info: IrControllerInfo): IrResult<Unit>
    /** Runs the controller's own authorization and returns the session secret to be stored encrypted. */
    suspend fun authenticate(): IrResult<ByteArray>
    /** Puts the controller into learning mode; the user then presses a button on the original remote. */
    suspend fun startLearning(): IrResult<Unit>
    /** Polls for the learned signal until [timeoutMs]; null value = nothing received in time. */
    suspend fun stopLearning(timeoutMs: Long = 30_000): IrResult<ByteArray?>
    /** Sends a learned signal; Ok only when the controller acknowledged the transmission. */
    suspend fun sendCommand(code: ByteArray): IrResult<Unit>
    /** Cheap end-to-end check: authorization + a harmless query. */
    suspend fun getStatus(): IrResult<String>
    fun disconnect()
}
