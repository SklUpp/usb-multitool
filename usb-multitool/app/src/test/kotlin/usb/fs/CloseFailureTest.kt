package usb.fs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.operation.DiskOperation
import usb.operation.OperationState

class CloseFailureTest {
    @Test
    fun closeFailureSuppressedOntoPrimary() {
        val primary = IllegalStateException("upload failed")
        val closeErr = ExtException("EXT close/sync failed (rc=-5)")
        val merged = mergeCloseFailure(primary, closeErr)
        assertSame(primary, merged)
        assertEquals(1, primary.suppressed.size)
        assertSame(closeErr, primary.suppressed[0])
    }

    @Test
    fun cleanupFailureAddedWithoutMaskingPrimaryOrClose() {
        val primary = IllegalStateException("staging failed")
        val closeErr = ExtException("EXT close/sync failed (rc=-5)")
        val cleanupErr = java.io.IOException("delete staged copy failed")
        val merged = mergeCloseFailure(primary, closeErr, cleanupErr)
        assertSame(primary, merged)
        assertEquals(2, primary.suppressed.size)
        assertSame(closeErr, primary.suppressed[0])
        assertSame(cleanupErr, primary.suppressed[1])
    }

    @Test
    fun cleanupFailureSuppressedOnCloseErrorWhenNoPrimary() {
        val closeErr = ExtException("EXT close/sync failed (rc=-5)")
        val cleanupErr = java.io.IOException("delete staged copy failed")
        val merged = mergeCloseFailure(null, closeErr, cleanupErr)
        assertSame(closeErr, merged)
        assertEquals(1, closeErr.suppressed.size)
        assertSame(cleanupErr, closeErr.suppressed[0])
    }

    @Test
    fun closeFailureAloneReturnedWhenNoPrimary() {
        val closeErr = ExtException("EXT close/sync failed (rc=-1)")
        val merged = mergeCloseFailure(null, closeErr)
        assertSame(closeErr, merged)
        assertEquals(0, closeErr.suppressed.size)
    }

    @Test
    fun closeAfterSuccessMessageIsExplicit() {
        val closeErr = ExtException("EXT close/sync failed (rc=-5)")
        val wrapped = CloseAfterSuccessException(closeErr)
        assertTrue(wrapped.message!!.contains("Change applied"))
        assertTrue(wrapped.message!!.contains("close/sync was not confirmed"))
        assertTrue(wrapped.message!!.contains("e2fsck"))
        assertSame(closeErr, wrapped.cause)
    }

    /**
     * Mirrors MainActivity.withWritableVolume finally when body succeeded:
     * Partial + CloseAfterSuccessException thrown; never Completed.
     */
    @Test
    fun successPlusCloseFailMarksPartialNotCompleted() {
        val op = DiskOperation(name = "upload")
        op.state = OperationState.Running
        val closeErr = ExtException("EXT close/sync failed (rc=-5)")
        try {
            simulateWritableVolumeFinally(op, primary = null, closeErr = closeErr)
            fail("expected CloseAfterSuccessException")
        } catch (e: CloseAfterSuccessException) {
            assertEquals(OperationState.Partial, op.state)
            assertSame(e, op.failureCause)
            assertTrue(e.message!!.contains("Change applied"))
            assertTrue(e.message!!.contains("not confirmed"))
        }
        assertTrue(op.state != OperationState.Completed)
    }

    @Test
    fun bodyFailureKeepsPrimaryAndSuppressesClose() {
        val op = DiskOperation(name = "upload")
        op.state = OperationState.Running
        val primary = IllegalStateException("write failed")
        val closeErr = ExtException("EXT close/sync failed (rc=-5)")
        try {
            simulateWritableVolumeFinally(op, primary = primary, closeErr = closeErr)
            fail("expected primary")
        } catch (e: IllegalStateException) {
            assertSame(primary, e)
            assertEquals(1, e.suppressed.size)
            assertSame(closeErr, e.suppressed[0])
        }
        // Body failure path does not flip to Partial via close.
        assertEquals(OperationState.Running, op.state)
    }

    /**
     * B1 probe/refresh finally: table failure already stamped Failed + failureCause;
     * disk.close throw must addSuppressed on primary, never replace Failed.
     */
    @Test
    fun probeTableFailureCloseIsSuppressedNotRethrown() {
        val op = DiskOperation(name = "Probe device")
        val tableErr = IllegalStateException("partition table unreadable")
        op.state = OperationState.Failed(tableErr)
        op.failureCause = tableErr

        val closeErr = ExtException("disk close failed")
        try {
            // Mirrors MainActivity probe/refresh finally around disk.close().
            try {
                throw closeErr
            } catch (thrown: Throwable) {
                val primary = op.failureCause
                if (primary != null) {
                    if (primary !== thrown) primary.addSuppressed(thrown)
                } else {
                    throw thrown
                }
            }
        } catch (e: Throwable) {
            fail("close must not escape when primary failureCause is set: $e")
        }

        assertTrue(op.state is OperationState.Failed)
        assertSame(tableErr, op.failureCause)
        assertEquals(1, tableErr.suppressed.size)
        assertSame(closeErr, tableErr.suppressed[0])
    }

    /**
     * B2 destructive path: after successful final disk.sync, commitReached flips unless
     * the body already left Partial/Failed.
     */
    @Test
    fun destructiveSyncCommitReachedUnlessPartialOrFailed() {
        fun afterSync(state: OperationState): Boolean {
            // Same guard as MainActivity destructive runner post-sync.
            return state != OperationState.Partial && state !is OperationState.Failed
        }

        val clean = DiskOperation(name = "wipe")
        clean.mutationAttempted = true
        if (afterSync(clean.state)) clean.commitReached = true
        assertTrue(clean.commitReached)

        val partial = DiskOperation(name = "wipe")
        partial.mutationAttempted = true
        partial.state = OperationState.Partial
        if (afterSync(partial.state)) partial.commitReached = true
        assertTrue(!partial.commitReached)

        val failed = DiskOperation(name = "wipe")
        failed.mutationAttempted = true
        val boom = IllegalStateException("write failed")
        failed.state = OperationState.Failed(boom)
        if (afterSync(failed.state)) failed.commitReached = true
        assertTrue(!failed.commitReached)
    }


    /** Same control flow as MainActivity.withWritableVolume finally. */
    private fun simulateWritableVolumeFinally(
        operation: DiskOperation,
        primary: Throwable?,
        closeErr: Throwable?,
    ) {
        try {
            if (primary != null) throw primary
        } finally {
            if (closeErr != null) {
                if (primary != null) {
                    mergeCloseFailure(primary, closeErr)
                } else if (operation.state != OperationState.Partial) {
                    val wrapped = CloseAfterSuccessException(closeErr)
                    operation.state = OperationState.Partial
                    operation.failureCause = wrapped
                    throw wrapped
                }
            }
        }
    }
}
