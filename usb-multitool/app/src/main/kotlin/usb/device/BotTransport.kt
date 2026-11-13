package usb.device

import java.nio.ByteBuffer

/** Raw USB bulk/control primitives for one claimed BOT interface. */
interface UsbBulkChannel {
    val interfaceId: Int

    fun bulkIn(
        destination: ByteBuffer,
        length: Int,
        timeoutMs: Int = BotConstants.TRANSFER_TIMEOUT_MS,
    ): Int

    fun bulkOut(
        source: ByteBuffer,
        length: Int,
        timeoutMs: Int = BotConstants.TRANSFER_TIMEOUT_MS,
    ): Int

    /**
     * Endpoint-zero control transfer.
     * @return transferred byte count, or -1 on failure.
     */
    fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
    ): Int

    /** Clear Feature HALT on the bulk-IN pipe. */
    fun clearHaltIn()

    /** Clear Feature HALT on the bulk-OUT pipe. */
    fun clearHaltOut()

    fun isConnected(): Boolean

    fun close()
}

enum class CswStatus(val code: Int) {
    PASSED(0),
    FAILED(1),
    PHASE_ERROR(2),
    ;

    companion object {
        fun from(code: Int): CswStatus =
            entries.firstOrNull { it.code == code }
                ?: throw DiskException.NonMeaningfulCsw("reserved CSW status $code")
    }
}

data class CswResult(
    val status: CswStatus,
    val tag: Int,
    val residue: Int,
)

/**
 * BOT command / data / status phases.
 * Implementations must loop on short bulkTransfer results (API 28 max request 64 KiB).
 */
interface BotSession {
    fun transfer(
        cdb: ScsiCdb,
        data: ByteBuffer?,
        timeoutMs: Int = BotConstants.TRANSFER_TIMEOUT_MS,
    ): CswResult

    /** Spec reset recovery: Mass Storage Reset, Clear Halt IN, Clear Halt OUT. */
    fun bulkOnlyReset()

    fun isConnected(): Boolean

    fun close()
}


object Recovery {
    /**
     * USB Mass Storage Bulk-Only Transport reset recovery, in spec order:
     * 1) Bulk-Only Mass Storage Reset
     * 2) Clear Feature HALT on Bulk-IN
     * 3) Clear Feature HALT on Bulk-OUT
     */
    fun bulkOnlyReset(channel: UsbBulkChannel) {
        val transferred = channel.controlTransfer(
            BotConstants.REQUEST_TYPE_CLASS_INTERFACE_OUT,
            BotConstants.REQUEST_BULK_ONLY_RESET,
            0,
            channel.interfaceId,
            ByteArray(0),
            0,
        )
        if (transferred < 0) {
            throw DiskException.IoFailure("Bulk-Only Mass Storage Reset failed")
        }
        channel.clearHaltIn()
        channel.clearHaltOut()
    }
}


/** Recover untrusted CSW without owning session lifetime; caller propagates for outer cleanup. */
fun recoverAfterInvalidCsw(session: BotSession, primary: Throwable): Nothing {
    if (session.isConnected()) {
        try {
            session.bulkOnlyReset()
        } catch (recovery: Throwable) {
            if (recovery !== primary) primary.addSuppressed(recovery)
        }
    }
    throw primary
}
