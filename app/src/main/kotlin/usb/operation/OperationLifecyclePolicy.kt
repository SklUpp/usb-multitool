package usb.operation

import usb.service.OperationHandle

/** Centralizes cancellation ownership rules without owning USB sessions or coroutine scope. */
object OperationLifecyclePolicy {
    fun cancelAll(handles: Iterable<OperationHandle>) {
        handles.toList().forEach { it.cancel() }
    }
}
