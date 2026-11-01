package usb.device

sealed class DiskException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Disconnected(cause: Throwable? = null) :
        DiskException("USB mass-storage device disconnected", cause) {
        override fun equals(other: Any?) = other is Disconnected
        override fun hashCode() = javaClass.hashCode()
    }

    class IoFailure(message: String, cause: Throwable? = null) :
        DiskException(message, cause) {
        override fun equals(other: Any?) =
            other is IoFailure && other.message == message

        override fun hashCode() = message.hashCode()
    }

    class Closed : DiskException("disk session closed") {
        override fun equals(other: Any?) = other is Closed
        override fun hashCode() = javaClass.hashCode()
    }

    class UnsupportedCommand(message: String) : DiskException(message) {
        override fun equals(other: Any?) =
            other is UnsupportedCommand && other.message == message

        override fun hashCode() = message.hashCode()
    }

    class SenseError(
        val responseCode: Int,
        val senseKey: Int,
        val asc: Int,
        val ascq: Int,
    ) : DiskException(
        "SCSI sense key=0x${senseKey.toString(16)} asc=0x${asc.toString(16)} ascq=0x${ascq.toString(16)}",
    ) {
        val isIllegalRequest: Boolean get() = senseKey == 0x05
        val isUnitAttention: Boolean get() = senseKey == 0x06
    }
}
