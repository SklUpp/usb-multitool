package usb.operation

import org.junit.Assert.assertTrue
import org.junit.Test
import usb.service.OperationHandle

class OperationLifecyclePolicyTest {
    @Test fun cancelAllInvokesEveryHandle() {
        val cancelled = mutableListOf<String>()
        fun handle(id: String) = object : OperationHandle {
            override val token = usb.service.OperationTokenGenerator.next()
            override var state: OperationState = OperationState.Queued
            override fun cancel() {
                cancelled += id
            }

            override suspend fun await(): OperationState = OperationState.Cancelled
        }
        OperationLifecyclePolicy.cancelAll(listOf(handle("a"), handle("b")))
        assertTrue(cancelled == listOf("a", "b"))
    }
}
