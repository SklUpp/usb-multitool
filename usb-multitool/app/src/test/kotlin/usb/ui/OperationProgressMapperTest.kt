package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import usb.service.OperationTokenGenerator
import usb.operation.OperationState as ServiceOperationState

class OperationProgressMapperTest {
    private val token = OperationTokenGenerator.next()
    private val scan = ReadScanOutcome(
        sectorsRead = 3UL,
        totalSectors = 16UL,
        unreadableSectors = 0UL,
        failedRangeCount = 0,
        averageBytesPerSecond = 512L,
    )

    @Test fun partialPreservesProgressAndMessages() {
        val progress = readOnlyOperationProgress(
            token, ServiceOperationState.Partial, OperationTitle.ReadScan, 4L, 10L, "saved", IllegalStateException("incomplete"), false,
        )
        assertEquals(OperationState.Partial, progress.state)
        assertEquals(4L, progress.bytesDone)
        assertEquals("saved", progress.resultMessage)
        assertEquals("incomplete", progress.errorMessage)
        assertEquals(false, progress.cancelable)
        assertNull(progress.readScanResult)
    }

    @Test fun failedUsesOutcomeCauseMessage() {
        val progress = readOnlyOperationProgress(
            token, ServiceOperationState.Failed(IllegalArgumentException("bad disk")), OperationTitle.ReadScan, 4L, 10L, null, null, true,
        )
        assertEquals(OperationState.Failed, progress.state)
        assertEquals("bad disk", progress.errorMessage)
        assertNull(progress.readScanResult)
    }

    @Test fun queuedAndRunningKeepTheirState() {
        val queued = readOnlyOperationProgress(
            token, ServiceOperationState.Queued, OperationTitle.ReadScan, 0L, 10L, "stale", null, true, readScanResult = scan,
        )
        assertEquals(OperationState.Queued, queued.state)
        assertEquals(0L, queued.bytesDone)
        assertEquals(10L, queued.bytesTotal)
        assertNull(queued.resultMessage)
        // Non-terminal must not carry a finished scan summary.
        assertNull(queued.readScanResult)

        val running = readOnlyOperationProgress(
            token, ServiceOperationState.Running, OperationTitle.ReadScan, 4L, 10L, "stale", null, true, readScanResult = scan,
        )
        assertEquals(OperationState.Running, running.state)
        assertEquals(4L, running.bytesDone)
        assertNull(running.resultMessage)
        assertNull(running.readScanResult)
    }

    @Test fun cancelledKeepsProgressAndStructuredScan() {
        val progress = readOnlyOperationProgress(
            token, ServiceOperationState.Cancelled, OperationTitle.ReadScan, 4L, 10L, null, null, false, readScanResult = scan,
        )
        assertEquals(OperationState.Cancelled, progress.state)
        assertEquals(false, progress.cancelable)
        assertEquals(4L, progress.bytesDone)
        assertEquals(10L, progress.bytesTotal)
        assertEquals(scan, progress.readScanResult)
    }

    @Test fun completedPreservesResultProgressAndScan() {
        val progress = readOnlyOperationProgress(
            token, ServiceOperationState.Completed, OperationTitle.ReadScan, 10L, 10L, null, null, true, readScanResult = scan,
        )
        assertEquals(OperationState.Completed, progress.state)
        assertEquals(10L, progress.bytesDone)
        assertNull(progress.resultMessage)
        assertEquals(scan, progress.readScanResult)
    }

    @Test fun preservesTypedTitleAndLogLabel() {
        val title = OperationTitle.ListPath("/ISO")
        val progress = readOnlyOperationProgress(
            token, ServiceOperationState.Completed, title, 10L, 10L, "ok", null, true,
        )
        assertEquals(title, progress.title)
        assertEquals("List /ISO", progress.label)
        assertEquals(title.logLabel, progress.label)
    }

    @Test fun destructiveTitleKeepsDisplayNameAsLogLabel() {
        val title = OperationTitle.Destructive(DestructiveAction.GPT_RESTORE)
        assertEquals("restore GPT backup", title.logLabel)
        val progress = readOnlyOperationProgress(
            token, ServiceOperationState.Running, title, 0L, 0L, null, null, true,
        )
        assertEquals(title, progress.title)
        assertEquals("restore GPT backup", progress.label)
    }

    @Test fun parameterizedTitlesDoNotNeedEnglishPrefixParsing() {
        assertEquals("New folder docs", OperationTitle.NewFolder("docs").logLabel)
        assertEquals("Delete a.bin", OperationTitle.DeleteEntry("a.bin").logLabel)
        assertEquals("Rename old", OperationTitle.RenameEntry("old").logLabel)
        assertEquals("Download x.iso", OperationTitle.DownloadEntry("x.iso").logLabel)
        assertEquals(
            "Stage img.iso for raw write",
            OperationTitle.StageForRawWrite("img.iso").logLabel,
        )
        assertEquals("Import win.iso", OperationTitle.ImportTarget("win.iso").logLabel)
        assertEquals("Format DATA", OperationTitle.FormatTarget("DATA").logLabel)
    }
}
