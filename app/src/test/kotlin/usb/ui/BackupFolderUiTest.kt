package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.diagnostics.BackupArchive

class BackupFolderUiTest {
    private val device =
        DeviceIdentity(label = "Backup stick", capacityBytes = 8_000_000_000uL)

    private val ready = AppUiState(
        presence = DevicePresence.Ready,
        device = device,
    )

    @Test
    fun chosenFolderIsStoredAndReported() {
        val result = UiReducer.reduce(
            ready,
            UiIntent.SetBackupFolder(
                uri = "content://tree/backups",
                name = "Backups",
                usable = true,
            ),
        )
        assertEquals("content://tree/backups", result.state.backupFolderUri)
        assertEquals("Backups", result.state.backupFolderName)
        assertTrue(result.state.backupFolderUsable)
        assertEquals(
            UiMessage.Key(UiMessageKey.BackupFolderReady, listOf("Backups")),
            result.state.statusMessage,
        )
    }

    @Test
    fun lostGrantKeepsNameButMarksFolderUnusable() {
        val result = UiReducer.reduce(
            ready.copy(
                backupFolderUri = "content://tree/backups",
                backupFolderName = "Backups",
                backupFolderUsable = true,
            ),
            UiIntent.SetBackupFolder(
                uri = "content://tree/backups",
                name = "Backups",
                usable = false,
            ),
        )
        assertEquals("content://tree/backups", result.state.backupFolderUri)
        assertEquals("Backups", result.state.backupFolderName)
        assertFalse(result.state.backupFolderUsable)
        assertEquals(
            UiMessage.Key(UiMessageKey.BackupFolderPermissionLost),
            result.state.statusMessage,
        )
    }

    @Test
    fun clearingFolderDropsUsability() {
        val result = UiReducer.reduce(
            ready.copy(
                backupFolderUri = "content://tree/backups",
                backupFolderName = "Backups",
                backupFolderUsable = true,
            ),
            UiIntent.SetBackupFolder(uri = null),
        )
        assertNull(result.state.backupFolderUri)
        assertNull(result.state.backupFolderName)
        assertFalse(result.state.backupFolderUsable)
        assertEquals(
            UiMessage.Key(UiMessageKey.ChooseBackupFolder),
            result.state.statusMessage,
        )
    }

    @Test
    fun folderProblemsMapToDistinctMessages() {
        assertEquals(
            UiMessage.Key(UiMessageKey.ArchiveEmpty),
            UiReducer.archiveInvalidMessage(
                BackupArchive.Selection.Invalid(BackupArchive.Problem.Empty),
            ),
        )
        assertEquals(
            UiMessage.Key(UiMessageKey.ArchiveMissingImage),
            UiReducer.archiveInvalidMessage(
                BackupArchive.Selection.Invalid(BackupArchive.Problem.MissingImage),
            ),
        )
        assertEquals(
            UiMessage.Key(UiMessageKey.ArchiveMissingMap),
            UiReducer.archiveInvalidMessage(
                BackupArchive.Selection.Invalid(BackupArchive.Problem.MissingMap),
            ),
        )
        assertEquals(
            UiMessage.Key(UiMessageKey.ArchiveUnexpectedFiles, listOf("x.bin")),
            UiReducer.archiveInvalidMessage(
                BackupArchive.Selection.Invalid(
                    BackupArchive.Problem.UnexpectedFiles,
                    listOf("x.bin"),
                ),
            ),
        )
    }

    @Test
    fun createActionsNeedUsableBackupFolder() {
        val missing = UiReducer.reduce(ready, UiIntent.StartRescueBackup)
        assertTrue(missing.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.ChooseBackupFolder),
            missing.state.statusMessage,
        )

        val revoked = ready.copy(
            backupFolderUri = "content://tree/backups",
            backupFolderName = "Backups",
            backupFolderUsable = false,
        )
        val gptMissing = UiReducer.reduce(revoked, UiIntent.CaptureGptBackup)
        assertTrue(gptMissing.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.ChooseBackupFolder),
            gptMissing.state.statusMessage,
        )

        val usable = ready.copy(
            backupFolderUri = "content://tree/backups",
            backupFolderName = "Backups",
            backupFolderUsable = true,
        )
        assertTrue(
            UiReducer.reduce(usable, UiIntent.StartRescueBackup).effects.single()
                is UiEffect.RescueBackup,
        )
        assertTrue(
            UiReducer.reduce(usable, UiIntent.CaptureGptBackup).effects.single()
                is UiEffect.CaptureGptBackup,
        )
    }

    @Test
    fun lostGrantAfterSnapshotPublishesAndBlocksCreate() {
        val usable = ready.copy(
            backupFolderUri = "content://tree/backups",
            backupFolderName = "Backups",
            backupFolderUsable = true,
        )
        assertTrue(
            UiReducer.reduce(usable, UiIntent.StartRescueBackup).effects.single()
                is UiEffect.RescueBackup,
        )

        // The Diagnostics create click republishes a lost live grant before opening Settings.
        val lost = UiReducer.reduce(
            usable,
            UiIntent.SetBackupFolder(
                uri = usable.backupFolderUri,
                name = usable.backupFolderName,
                usable = false,
            ),
        )
        assertEquals("content://tree/backups", lost.state.backupFolderUri)
        assertEquals("Backups", lost.state.backupFolderName)
        assertFalse(lost.state.backupFolderUsable)
        assertEquals(
            UiMessage.Key(UiMessageKey.BackupFolderPermissionLost),
            lost.state.statusMessage,
        )

        val refused = UiReducer.reduce(lost.state, UiIntent.StartRescueBackup)
        assertTrue(refused.effects.isEmpty())
        assertEquals(
            UiMessage.Key(UiMessageKey.ChooseBackupFolder),
            refused.state.statusMessage,
        )
    }

    @Test
    fun folderFreeActionsIgnoreBackupFolderUsability() {
        val withoutFolder = ready.copy(backupFolderUsable = false)
        assertTrue(
            UiReducer.reduce(withoutFolder, UiIntent.StartReadScan).effects.single()
                is UiEffect.ReadScan,
        )
        val wipe = UiReducer.reduce(
            withoutFolder,
            UiIntent.RequestDestructive(DestructiveAction.WIPE),
        )
        assertNotNull(wipe.state.pendingConfirmation)
        assertEquals(DestructiveAction.WIPE, wipe.state.pendingConfirmation!!.action)

        val restoreTarget = withoutFolder.copy(
            rescueImageUri = "content://archive/rescue.img",
            rescueMapUri = "content://archive/rescue.map",
        )
        val restore = UiReducer.reduce(
            restoreTarget,
            UiIntent.RequestDestructive(DestructiveAction.RESCUE_RESTORE),
        )
        assertNotNull(restore.state.pendingConfirmation)
        assertEquals(
            DestructiveAction.RESCUE_RESTORE,
            restore.state.pendingConfirmation!!.action,
        )
    }
}
