package usb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupFolderLaunchTest {
    private fun queue(token: String = "session-a") = BackupFolderLaunchQueue(token)

    private fun start(
        queue: BackupFolderLaunchQueue,
        action: BackupFolderAction,
        identity: String? = "stick-a",
        generation: Long = 1L,
        key: String? = "1:/dev/bus/usb/001/002",
        serial: String? = "SERIAL-A",
    ): PendingBackupFolderAction =
        when (val launched = queue.begin(action, identity, generation, key, serial)) {
            is BackupFolderLaunch.Starting -> launched.pending
            else -> throw AssertionError("expected Starting, got $launched")
        }

    @Test
    fun beginPinsActionToAttachmentAndSession() {
        val queue = queue()
        val start =
            queue.begin(
                BackupFolderAction.RescueBackup,
                identity = "stick-a#serial",
                attachGeneration = 7L,
                deviceKey = "1:/dev/bus/usb/001/002",
                serialNumber = " serial ",
            )
        val pending = (start as BackupFolderLaunch.Starting).pending
        assertEquals(BackupFolderAction.RescueBackup, pending.action)
        assertEquals("stick-a#serial", pending.identity)
        assertEquals(7L, pending.attachGeneration)
        assertEquals("1:/dev/bus/usb/001/002", pending.deviceKey)
        assertEquals("serial", pending.serialNumber)
        assertEquals("session-a", pending.sessionToken)
        assertEquals(pending, queue.pending)
    }

    @Test
    fun secondLaunchIsBusyAndKeepsTheFirstContext() {
        val queue = queue()
        val first = start(queue, BackupFolderAction.RescueBackup)
        assertTrue(
            queue.begin(BackupFolderAction.GptCopy, "stick-b", 2L, "2:/dev/b", "SERIAL-B") is
                BackupFolderLaunch.Busy,
        )
        assertEquals(first, queue.pending)
        assertEquals(first, queue.take())
        assertNull(queue.pending)
    }

    @Test
    fun takeConsumesExactlyOnce() {
        val queue = queue()
        start(queue, BackupFolderAction.GptCopy, generation = 3L)
        assertTrue(queue.take() != null)
        assertNull(queue.take())
    }

    @Test
    fun aConsumedLaunchAllowsTheNextOne() {
        val queue = queue()
        start(queue, BackupFolderAction.RescueBackup)
        assertTrue(queue.take() != null)
        val next = start(queue, BackupFolderAction.GptCopy)
        assertEquals(BackupFolderAction.GptCopy, next.action)
    }

    @Test
    fun cancelDropsTheAction() {
        val queue = queue()
        start(queue, BackupFolderAction.RescueBackup)
        queue.cancel()
        assertNull(queue.pending)
        assertNull(queue.take())
    }

    @Test
    fun changedIdentityRejectsTheContext() {
        val queue = queue()
        val pending = start(queue, BackupFolderAction.RescueBackup)
        val key = pending.deviceKey
        val serial = pending.serialNumber
        assertFalse(pending.accepts("stick-b", 1L, key, serial))
        assertTrue(pending.accepts("stick-a", 1L, key, serial))
        queue.cancel()
        assertNull(queue.pending)
    }

    @Test
    fun changedDeviceKeyRejectsEvenWhenStateIsStale() {
        val queue = queue()
        val pending = start(queue, BackupFolderAction.GptCopy)
        // Same label, generation and serial: only the live device key proves the swap.
        assertFalse(
            pending.accepts("stick-a", 1L, "9:/dev/bus/usb/001/009", "SERIAL-A"),
        )
        assertTrue(pending.accepts("stick-a", 1L, pending.deviceKey, "SERIAL-A"))
    }

    @Test
    fun changedSerialRejectsSameKeyReplug() {
        val queue = queue()
        val pending = start(queue, BackupFolderAction.GptCopy)
        assertFalse(pending.accepts("stick-a", 1L, pending.deviceKey, "SERIAL-B"))
        assertFalse(pending.accepts("stick-a", 1L, pending.deviceKey, null))
    }

    @Test
    fun detachedTargetNeverMatches() {
        val queue = queue()
        val pending = start(queue, BackupFolderAction.GptCopy, generation = 4L)
        assertFalse(pending.accepts(null, 0L, null, null))
        assertFalse(pending.accepts(null, 4L, null, null))
    }

    @Test
    fun replugOfSameModelIsRejectedByGeneration() {
        val queue = queue()
        val pending = start(queue, BackupFolderAction.RescueBackup, generation = 5L)
        assertFalse(pending.accepts("stick-a", 6L, pending.deviceKey, "SERIAL-A"))
    }

    @Test
    fun backupActionWithoutIdentityKeyOrSerialIsNotLaunchable() {
        val queue = queue()
        assertTrue(
            queue.begin(BackupFolderAction.RescueBackup, null, 0L, "1:/dev/b", "SERIAL-A") is
                BackupFolderLaunch.NoTarget,
        )
        assertTrue(
            queue.begin(BackupFolderAction.RescueBackup, "stick-a", 0L, null, "SERIAL-A") is
                BackupFolderLaunch.NoTarget,
        )
        assertTrue(
            queue.begin(BackupFolderAction.RescueBackup, "stick-a", 0L, "1:/dev/b", null) is
                BackupFolderLaunch.Unverifiable,
        )
        assertTrue(
            queue.begin(BackupFolderAction.GptCopy, "stick-a", 0L, "1:/dev/b", "  ") is
                BackupFolderLaunch.Unverifiable,
        )
        assertNull(queue.pending)
    }

    @Test
    fun manualFolderChangeHasNoTargetAndSurvivesTargetCheck() {
        val queue = queue()
        val pending = start(queue, BackupFolderAction.ChangeFolder, null, 0L, null, null)
        assertTrue(pending.accepts(null, 0L, null, null))
        assertTrue(pending.accepts("stick-a", 9L, "1:/dev/b", "SERIAL-A"))
        assertEquals(pending, queue.pending)
    }

    @Test
    fun rotationRestoresInTheSameProcessSession() {
        val saved = start(queue("session-a"), BackupFolderAction.RescueBackup, generation = 2L)
        val recreated = queue("session-a")
        assertTrue(recreated.restore(saved))
        assertEquals(saved, recreated.pending)
        assertEquals(saved, recreated.take())
    }

    @Test
    fun processDeathNeverRestoresTheOldContext() {
        val saved = start(queue("session-old"), BackupFolderAction.RescueBackup, generation = 2L)
        val newProcess = queue("session-new")
        assertFalse(newProcess.restore(saved))
        assertNull(newProcess.pending)
        assertNull(newProcess.take())
    }

    @Test
    fun restoreRejectsAnIncompleteWritePin() {
        val missingSerial =
            PendingBackupFolderAction(
                action = BackupFolderAction.RescueBackup,
                identity = "stick-a",
                attachGeneration = 1L,
                deviceKey = "1:/dev/b",
                serialNumber = null,
                sessionToken = "session-a",
            )
        val queue = queue("session-a")
        assertFalse(queue.restore(missingSerial))
        assertNull(queue.pending)
    }

    @Test
    fun restoreDoesNotReplaceALiveContext() {
        val queue = queue()
        val live = start(queue, BackupFolderAction.GptCopy)
        val stale =
            PendingBackupFolderAction(
                action = BackupFolderAction.RescueBackup,
                identity = "stick-b",
                attachGeneration = 2L,
                deviceKey = "2:/dev/b",
                serialNumber = "SERIAL-B",
                sessionToken = "session-a",
            )
        assertFalse(queue.restore(stale))
        assertEquals(live, queue.pending)
    }
}
