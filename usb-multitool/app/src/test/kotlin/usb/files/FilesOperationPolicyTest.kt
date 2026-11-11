package usb.files

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.fs.mergeCloseFailure

class FilesOperationPolicyTest {
    @Test
    fun requireChildNameAcceptsSingleEntryName() {
        assertEquals("ubuntu.iso", FilesOperationPolicy.requireChildName("ubuntu.iso", "upload"))
        assertEquals("ubuntu.iso", FilesOperationPolicy.requireChildName("dir/ubuntu.iso", "upload"))
        assertEquals("ubuntu.iso", FilesOperationPolicy.requireChildName(" ubuntu.iso ", "upload"))
    }

    @Test
    fun requireChildNameRejectsTraversalSeparatorAndNul() {
        // Same rule set as UiReducer.validEntryName, plus NUL (illegal in FAT names).
        for (raw in listOf("", "   ", ".", "..", "/", "dir/", "a\\b", "..\\x", "a\u0000b")) {
            val error =
                assertThrows(IllegalArgumentException::class.java) {
                    FilesOperationPolicy.requireChildName(raw, "upload")
                }
            assertTrue(error.message!!.contains("invalid file name"))
        }
    }

    @Test
    fun nonemptyDirectoryMessageIsUserFacing() {
        val message =
            FilesOperationPolicy.deleteError(
                name = "ISO",
                target = "/ISO",
                message = "Directory not empty",
            )
        assertEquals(
            "Cannot delete \"ISO\": directory is not empty — remove its contents first",
            message,
        )
    }

    @Test
    fun quietWorkNeverPublishesGlobalOperationSlot() {
        assertFalse(FilesOperationPolicy.publishOperationProgress(quiet = true))
        assertTrue(FilesOperationPolicy.publishOperationProgress(quiet = false))
    }

    @Test
    fun quietBoundTerminalPostsBoundNotStatusNotOpSlot() {
        val plan =
            FilesOperationPolicy.planTerminal(quiet = true, hasBoundFailure = true)
        assertTrue(plan.postBoundFailure)
        assertFalse(plan.postStatus)
        assertFalse(plan.postOperationProgress)
    }

    @Test
    fun nonQuietUnboundPostsStatusAndOp() {
        val plan =
            FilesOperationPolicy.planTerminal(quiet = false, hasBoundFailure = false)
        assertFalse(plan.postBoundFailure)
        assertTrue(plan.postStatus)
        assertTrue(plan.postOperationProgress)
    }

    @Test
    fun primaryErrorMessageKeepsPrimaryNotClose() {
        val primary = IOException("list failed: 5")
        mergeCloseFailure(primary, IOException("volume sync unconfirmed"))
        assertEquals("list failed: 5", FilesOperationPolicy.primaryErrorMessage(primary))
        assertEquals(1, primary.suppressed.size)
    }

    @Test
    fun closeDiagnosticFromSuppressedAlwaysLog() {
        val primary = IOException("disk read failed")
        mergeCloseFailure(primary, IOException("umount failed"))
        val d =
            FilesOperationPolicy.planCloseDiagnostic(
                primary = primary,
                quiet = true,
                hasBoundFailure = true,
                opStillCurrent = false,
            )
        assertTrue(d is CloseDiagnosticPublish.LogOnly)
        assertTrue((d as CloseDiagnosticPublish.LogOnly).message.contains("umount failed"))
    }

    @Test
    fun cancelWithNullFailureCauseUsesExplicitCloseChannel() {
        // RO cancel: executor clears failureCause; host keeps ClosedFailed on explicit channel.
        val close = IOException("volume sync unconfirmed")
        val d =
            FilesOperationPolicy.planCloseDiagnostic(
                primary = null,
                quiet = true,
                hasBoundFailure = true,
                opStillCurrent = true,
                explicitClose = close,
            )
        assertTrue(d is CloseDiagnosticPublish.LogOnly)
        assertTrue(
            (d as CloseDiagnosticPublish.LogOnly).message.contains("volume sync unconfirmed"),
        )
    }

    @Test
    fun cancellationPrimaryWithSuppressedCloseStillLogs() {
        val cancel = RuntimeException("cancelled") // stand-in for CancellationException
        mergeCloseFailure(cancel, IOException("umount failed"))
        val d =
            FilesOperationPolicy.planCloseDiagnostic(
                primary = cancel,
                quiet = true,
                hasBoundFailure = true,
                opStillCurrent = true,
            )
        assertTrue(d is CloseDiagnosticPublish.LogOnly)
        assertEquals("cancelled", FilesOperationPolicy.primaryErrorMessage(cancel))
    }

    @Test
    fun noCloseMeansNone() {
        val none =
            FilesOperationPolicy.planCloseDiagnostic(
                primary = IOException("no close"),
                quiet = true,
                hasBoundFailure = true,
                opStillCurrent = true,
            )
        assertEquals(CloseDiagnosticPublish.None, none)
    }
}
