package usb.operation

import java.io.IOException

/** Byte-count contract broken on a SAF/image transfer (expected ≠ written/reported). */
class SizeMismatchException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * SAF provider refused create/rename/ownership/readback. Extends [IllegalStateException]
 * so existing `catch (IllegalStateException)` / assertThrows contracts still match.
 */
class ProviderRejectedException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
