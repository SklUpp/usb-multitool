package usb.operation

import usb.service.OperationHandle

/** Centralizes cancellation ownership rules without owning USB sessions or coroutine scope. */
object OperationLifecyclePolicy {
    fun cancelAll(handles: Iterable<OperationHandle>) {
        handles.toList().forEach { it.cancel() }
    }

    fun isCooperative(action: usb.ui.DestructiveAction?): Boolean =
        action == usb.ui.DestructiveAction.RAW_WRITE ||
            action == usb.ui.DestructiveAction.VENTOY_DOWNLOAD_INSTALL ||
            action == usb.ui.DestructiveAction.RESCUE_RESTORE
}
