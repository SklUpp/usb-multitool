package usb.device

object BotConstants {
    const val CBW_SIGNATURE = 0x43425355
    const val CSW_SIGNATURE = 0x53425355
    const val CBW_SIZE = 31
    const val CSW_SIZE = 13
    const val REQUEST_TYPE_CLASS_INTERFACE_OUT = 0x21
    const val REQUEST_BULK_ONLY_RESET = 0xFF
    const val REQUEST_GET_MAX_LUN = 0xFE
    const val REQUEST_TYPE_CLASS_INTERFACE_IN = 0xA1
    const val CLEAR_FEATURE = 0x01
    const val ENDPOINT_HALT = 0x00
    const val REQUEST_TYPE_ENDPOINT_OUT = 0x02
    const val TRANSFER_TIMEOUT_MS = 5_000
    const val SYNCHRONIZE_CACHE_TIMEOUT_MS = 30_000
    const val MAX_BULK_REQUEST = 64 * 1024
}
