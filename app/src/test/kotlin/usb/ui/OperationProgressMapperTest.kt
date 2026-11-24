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

    private fun mapProgress(
        outcome: ServiceOperationState,
        title: OperationTitle,
        bytesDone: Long,
        bytesTotal: Long,
        resultMessage: String?,
        failureCause: Throwable?,
        cancelable: Boolean,
        bytesPerSecond: Long = 0L,
        readScanResult: ReadScanOutcome? = null,
        smartResult: SmartOutcome? = null,
    ) = outcome.toOperationResult(failureCause).toProgress(
        token = token,
        title = title,
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        bytesPerSecond = bytesPerSecond,
        cancelable = cancelable,
        resultMessage = resultMessage,
        readScanResult = readScanResult,
        smartResult = smartResult,
    )

    @Test fun partialPreservesProgressAndMessages() {
        val progress = mapProgress(
            ServiceOperationState.Partial, OperationTitle.ReadScan, 4L, 10L, "saved", IllegalStateException("incomplete"), false,
        )
        assertEquals(OperationState.Partial, progress.state)
        assertEquals(4L, progress.bytesDone)
        assertEquals("saved", progress.resultMessage)
        assertEquals("incomplete", progress.errorMessage)
        assertEquals(false, progress.cancelable)
        assertNull(progress.readScanResult)
    }

    @Test fun failedUsesOutcomeCauseMessage() {
        val progress = mapProgress(
            ServiceOperationState.Failed(IllegalArgumentException("bad disk")), OperationTitle.ReadScan, 4L, 10L, null, null, true,
        )
        assertEquals(OperationState.Failed, progress.state)
        assertEquals("bad disk", progress.errorMessage)
        assertNull(progress.readScanResult)
    }

    @Test fun failedFallsBackToOperationFailureWhenCauseMessageBlank() {
        val progress = mapProgress(
            ServiceOperationState.Failed(IllegalStateException()),
            OperationTitle.ReadScan,
            0L,
            0L,
            null,
            IllegalArgumentException("host failure"),
            true,
        )
        assertEquals(OperationState.Failed, progress.state)
        assertEquals("host failure", progress.errorMessage)
        assertEquals(0L, progress.bytesDone)
        assertNull(progress.resultMessage)
        assertNull(progress.readScanResult)
    }

    @Test fun queuedAndRunningKeepTheirState() {
        val queued = mapProgress(
            ServiceOperationState.Queued, OperationTitle.ReadScan, 0L, 10L, "stale", null, true, readScanResult = scan,
        )
        assertEquals(OperationState.Queued, queued.state)
        assertEquals(0L, queued.bytesDone)
        assertEquals(10L, queued.bytesTotal)
        assertNull(queued.resultMessage)
        // Non-terminal must not carry a finished scan summary.
        assertNull(queued.readScanResult)

        val running = mapProgress(
            ServiceOperationState.Running, OperationTitle.ReadScan, 4L, 10L, "stale", null, true, readScanResult = scan,
        )
        assertEquals(OperationState.Running, running.state)
        assertEquals(4L, running.bytesDone)
        assertNull(running.resultMessage)
        assertNull(running.readScanResult)
    }

    @Test fun cancelledKeepsProgressAndStructuredScan() {
        val progress = mapProgress(
            ServiceOperationState.Cancelled, OperationTitle.ReadScan, 4L, 10L, null, null, false, readScanResult = scan,
        )
        assertEquals(OperationState.Cancelled, progress.state)
        assertEquals(false, progress.cancelable)
        assertEquals(4L, progress.bytesDone)
        assertEquals(10L, progress.bytesTotal)
        assertEquals(scan, progress.readScanResult)
    }

    @Test fun completedPreservesResultProgressAndScan() {
        val progress = mapProgress(
            ServiceOperationState.Completed, OperationTitle.ReadScan, 10L, 10L, null, null, true, readScanResult = scan,
        )
        assertEquals(OperationState.Completed, progress.state)
        assertEquals(10L, progress.bytesDone)
        assertNull(progress.resultMessage)
        assertEquals(scan, progress.readScanResult)
    }

    @Test fun rawWriteResultSurvivesPartialAndCompleted() {
        val raw = usb.image.RawWriteResult(
            range = usb.partition.RawWriteRange(startLba = 10UL, sectorCount = 2UL),
            writtenBytes = 1_024L,
            readback = usb.image.ReadbackVerdict.Verified,
            state = usb.image.RawWriteStatus.Partial,
        )
        val title = OperationTitle.Destructive(DestructiveAction.RAW_WRITE)
        val partial = ServiceOperationState.Partial.toOperationResult(
            IllegalStateException("incomplete"),
        ).toProgress(
            token = token,
            title = title,
            bytesDone = 512L,
            bytesTotal = 1_024L,
            cancelable = false,
            resultMessage = "partial write",
            rawWriteResult = raw,
        )
        assertEquals(OperationState.Partial, partial.state)
        assertEquals(raw, partial.rawWriteResult)
        assertEquals("incomplete", partial.errorMessage)

        val completed = ServiceOperationState.Completed.toOperationResult().toProgress(
            token = token,
            title = title,
            bytesDone = 1_024L,
            bytesTotal = 1_024L,
            cancelable = false,
            resultMessage = "ok",
            rawWriteResult = raw.copy(state = usb.image.RawWriteStatus.Completed),
        )
        assertEquals(OperationState.Completed, completed.state)
        assertEquals(usb.image.RawWriteStatus.Completed, completed.rawWriteResult?.state)
        assertEquals(raw.range, completed.rawWriteResult?.range)
    }

    @Test fun preservesTypedTitleAndLogLabel() {
        val title = OperationTitle.ListPath("/ISO")
        val progress = mapProgress(
            ServiceOperationState.Completed, title, 10L, 10L, "ok", null, true,
        )
        assertEquals(title, progress.title)
        assertEquals("List /ISO", progress.label)
        assertEquals(title.logLabel, progress.label)
    }

    @Test fun destructiveTitleKeepsDisplayNameAsLogLabel() {
        val title = OperationTitle.Destructive(DestructiveAction.GPT_RESTORE)
        assertEquals("restore GPT backup", title.logLabel)
        val progress = mapProgress(
            ServiceOperationState.Running, title, 0L, 0L, null, null, true,
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

    @Test fun cancelableOnlyWhileRunningOrQueued() {
        val cases = listOf(
            Pair<OperationProgress?, Boolean>(null, false),
            Pair(OperationProgress(OperationState.Running, OperationTitle.Upload, token, cancelable = false), false),
            Pair(OperationProgress(OperationState.Completed, OperationTitle.Upload, token, cancelable = true), false),
            Pair(OperationProgress(OperationState.Failed, OperationTitle.Upload, token, cancelable = true), false),
            Pair(OperationProgress(OperationState.Cancelled, OperationTitle.Upload, token, cancelable = true), false),
            Pair(OperationProgress(OperationState.Queued, OperationTitle.Upload, token, cancelable = true), true),
            Pair(OperationProgress(OperationState.Running, OperationTitle.Upload, token, cancelable = true), true),
        )
        for ((progress, expected) in cases) {
            assertEquals("state=${progress?.state}, cancelable=${progress?.cancelable}", expected, OperationPresentation.isCancelable(progress))
        }
    }

    @Test fun cancelTokenFencesOwnerTabAndTitle() {
        val running = OperationProgress(OperationState.Running, OperationTitle.UploadBatch(2), token, cancelable = true)
        val queued = OperationProgress(OperationState.Queued, OperationTitle.RescueBackup, token, cancelable = true)
        val completed = OperationProgress(OperationState.Completed, OperationTitle.UploadBatch(2), token, cancelable = true)
        val notCancelable = OperationProgress(OperationState.Running, OperationTitle.UploadBatch(2), token, cancelable = false)
        assertNull(OperationPresentation.cancelToken(null))
        assertNull(OperationPresentation.cancelToken(notCancelable))
        assertNull(OperationPresentation.cancelToken(completed))
        assertNull(OperationPresentation.cancelToken(running, ownerTab = ShellTab.Ventoy))
        assertNull(OperationPresentation.cancelToken(running, matchesTitle = { false }))
        assertEquals(token, OperationPresentation.cancelToken(running))
        assertEquals(token, OperationPresentation.cancelToken(running, ownerTab = ShellTab.Files))
        assertEquals(token, OperationPresentation.cancelToken(queued, ownerTab = ShellTab.Diagnostics))
        assertEquals(
            token,
            OperationPresentation.cancelToken(queued, matchesTitle = { it == OperationTitle.RescueBackup }),
        )
    }

    @Test fun originTabMatchesOwningScreen() {
        assertEquals(ShellTab.Files, OperationTitle.DownloadEntry("x.iso").originTab())
        assertEquals(ShellTab.Files, OperationTitle.Upload.originTab())
        assertEquals(ShellTab.Ventoy, OperationTitle.SetVentoyGlobalSetting.originTab())
        assertEquals(ShellTab.Ventoy, OperationTitle.Destructive(DestructiveAction.VENTOY_INSTALL).originTab())
        assertEquals(ShellTab.Diagnostics, OperationTitle.RescueBackup.originTab())
        assertEquals(ShellTab.Diagnostics, OperationTitle.Destructive(DestructiveAction.GPT_RESTORE).originTab())
        assertEquals(ShellTab.Partitions, OperationTitle.Destructive(DestructiveAction.RAW_WRITE).originTab())
        assertEquals(ShellTab.Partitions, OperationTitle.ProbeDevice.originTab())
        assertNull(OperationTitle.EjectDevice.originTab())
    }
}
