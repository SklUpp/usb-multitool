package usb.multitool

/**
 * Pure gate for USB permission re-requests after an explicit denial.
 * One retry per key while the device stays attached; detach/replug clears state.
 */
internal object UsbPermissionRetryGate {
    data class State(
        val pendingKey: String? = null,
        val deniedKey: String? = null,
        /** Key that already consumed its single post-denial retry. */
        val retriedKey: String? = null,
    )

    data class Decision(
        val shouldRequest: Boolean,
        val state: State,
    )

    /** Decide whether to call [UsbManager.requestPermission] for [key]. */
    fun prepareRequest(key: String, state: State): Decision {
        if (state.pendingKey == key) {
            return Decision(shouldRequest = false, state = state)
        }
        if (state.deniedKey == key) {
            if (state.retriedKey == key) {
                return Decision(shouldRequest = false, state = state)
            }
            // One shot: clear denial, mark retry consumed, allow the request.
            return Decision(
                shouldRequest = true,
                state = state.copy(deniedKey = null, retriedKey = key),
            )
        }
        return Decision(shouldRequest = true, state = state)
    }

    fun onDenied(key: String?, state: State): State =
        if (key == null) state else state.copy(pendingKey = null, deniedKey = key)

    fun onGranted(state: State): State =
        state.copy(pendingKey = null, deniedKey = null, retriedKey = null)

    fun onDetachedOrReselect(state: State): State = State()
}
