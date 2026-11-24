package usb.service

import usb.operation.DiskOperation

/** Submit disk work; [DiskController] is the production implementation. */
fun interface OperationSubmitter {
    fun submit(operation: DiskOperation, token: OperationToken): OperationHandle
}
