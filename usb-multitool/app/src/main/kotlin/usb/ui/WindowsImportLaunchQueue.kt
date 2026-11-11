package usb.ui

/**
 * Context captured by one SAF launch. The result callback carries no app context, so the
 * exact kind/directory/session and the attachment it belongs to must travel here.
 */
data class PendingWindowsImport(
    val kind: WindowsImportKind,
    val directory: String?,
    /** Pick session for an image; null for the independent XML flow. */
    val pickSessionId: String?,
    /** Attachment the user chose the file for; a different stick must reject it. */
    val identity: String?,
    val attachGeneration: Long,
    /** Legacy result code; the system returns it, so duplicates cannot take a newer context. */
    val requestCode: Int,
)

/** Result of asking the queue to start a launch; busy/exhausted/storage are explicit. */
sealed interface WindowsImportLaunch {
    data class Starting(val context: PendingWindowsImport) : WindowsImportLaunch
    data object Busy : WindowsImportLaunch
    data object Exhausted : WindowsImportLaunch
    /** The durable high-water mark could not be reserved; nothing was started. */
    data object StorageFailed : WindowsImportLaunch
}

/**
 * Process-wide allocator for SAF request codes. [reserve] is the only allocation path:
 * it reads the durable high-water mark, advances it, and writes it back under one lock,
 * before the caller may start the picker. Two Activity instances sharing this allocator
 * (as the host does) therefore cannot receive the same code. Codes never wrap; exhausting
 * the finite range is an explicit failure, not a reuse of a possibly live result.
 */
class WindowsImportRequestCodes(private val storage: Storage) {
    interface Storage {
        fun readNext(): Int
        fun writeNext(next: Int): Boolean
    }

    sealed interface Reservation {
        data class Code(val value: Int) : Reservation
        data object Exhausted : Reservation
        data object StorageFailed : Reservation
    }

    private val lock = Any()

    fun reserve(): Reservation = synchronized(lock) {
        val next = normalize(storage.readNext())
        when {
            next > LAST_REQUEST_CODE -> Reservation.Exhausted
            storage.writeNext(next + 1) -> Reservation.Code(next)
            else -> Reservation.StorageFailed
        }
    }

    /** Raise the durable mark above a restored code; false means it could not be secured. */
    fun raiseTo(minNext: Int): Boolean = synchronized(lock) {
        val current = normalize(storage.readNext())
        val target = minNext.coerceAtMost(LAST_REQUEST_CODE + 1)
        current >= target || storage.writeNext(target)
    }

    private fun normalize(value: Int): Int =
        when {
            value < FIRST_REQUEST_CODE -> FIRST_REQUEST_CODE
            value > LAST_REQUEST_CODE + 1 -> LAST_REQUEST_CODE + 1
            else -> value
        }

    companion object {
        /**
         * Below 65536 while AndroidX ActivityResultRegistry (activity 1.10.1) allocates
         * random codes starting at 65536 (`nextInt(2147418112) + 65536`), so the ranges are
         * disjoint. The finite 0x5700..0xFFFE range is consumed once, never wrapped.
         */
        const val FIRST_REQUEST_CODE = 0x5700
        const val LAST_REQUEST_CODE = 0xFFFE
    }
}

/** Test/default storage; the host replaces it with a durable SharedPreferences adapter. */
internal class InMemoryRequestCodeStorage(
    private var next: Int = WindowsImportRequestCodes.FIRST_REQUEST_CODE,
    var failWrites: Boolean = false,
) : WindowsImportRequestCodes.Storage {
    override fun readNext(): Int = next

    override fun writeNext(next: Int): Boolean {
        if (failWrites) return false
        this.next = next
        return true
    }
}

/**
 * Exactly one SAF launch may be pending per Activity. Results carry their
 * [PendingWindowsImport.requestCode], so a duplicate/late result for an older launch cannot
 * consume a newer context. The allocator is shared process-wide so separate Activity
 * instances draw unique codes from the same durable high-water mark.
 */
class WindowsImportLaunchQueue(
    private val codes: WindowsImportRequestCodes =
        WindowsImportRequestCodes(InMemoryRequestCodeStorage()),
) {
    var pending: PendingWindowsImport? = null
        private set

    /** Returns Starting(context), Busy, Exhausted, or StorageFailed; never reuses a code. */
    fun begin(
        kind: WindowsImportKind,
        directory: String?,
        pickSessionId: String?,
        identity: String?,
        attachGeneration: Long,
    ): WindowsImportLaunch {
        if (pending != null) return WindowsImportLaunch.Busy
        return when (val reserved = codes.reserve()) {
            WindowsImportRequestCodes.Reservation.Exhausted -> WindowsImportLaunch.Exhausted
            WindowsImportRequestCodes.Reservation.StorageFailed ->
                WindowsImportLaunch.StorageFailed
            is WindowsImportRequestCodes.Reservation.Code -> {
                val context =
                    PendingWindowsImport(
                        kind = kind,
                        directory = directory,
                        pickSessionId = pickSessionId,
                        identity = identity,
                        attachGeneration = attachGeneration,
                        requestCode = reserved.value,
                    )
                pending = context
                WindowsImportLaunch.Starting(context)
            }
        }
    }

    /** Consume only the context that matches [requestCode]; duplicates return null. */
    fun take(requestCode: Int): PendingWindowsImport? {
        val context = pending ?: return null
        if (context.requestCode != requestCode) return null
        pending = null
        return context
    }

    /** Timeout path: retire only that request code. */
    fun resetByRequestCode(requestCode: Int): Boolean {
        if (pending?.requestCode != requestCode) return false
        pending = null
        return true
    }

    /** Reducer exit path: retire only a context owned by [pickSessionId]. */
    fun resetByPickSession(pickSessionId: String): Boolean {
        if (pending?.pickSessionId != pickSessionId) return false
        pending = null
        return true
    }

    /**
     * Rotation/process restore. The durable mark must be raised above the restored code;
     * on failure the pending context is not installed, so an unsecured code can never be
     * accepted or reused.
     */
    fun restore(context: PendingWindowsImport?): Boolean {
        if (context == null) {
            pending = null
            return true
        }
        val minNext =
            if (context.requestCode >= WindowsImportRequestCodes.LAST_REQUEST_CODE) {
                WindowsImportRequestCodes.LAST_REQUEST_CODE + 1
            } else {
                context.requestCode + 1
            }
        if (!codes.raiseTo(minNext)) {
            pending = null
            return false
        }
        pending = context
        return true
    }
}
