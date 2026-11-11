package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
