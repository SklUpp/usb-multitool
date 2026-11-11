package usb.files

import java.io.IOException
import java.util.concurrent.CountDownLatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import usb.device.BlockGeometry
import usb.device.FakeBotSession
import usb.device.UsbDisk
import usb.device.UsbExclusiveClaim
import usb.fs.CloseAfterSuccessException
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.operation.DiskOperation
import usb.operation.OperationState

/**
 * Session owns one mounted volume + coarse lock. Behavioral contracts:
 * close failure refuses remount and fail-closed gate; volume closes before disk;
 * writable mutation + close fail → Partial; body fail keeps primary.
 * Detach-style close is epoch-fenced: it drops the mount that existed at the fence and
 * never a mount opened by a later attach, while a close failure stays latched.
 */
class FilesVolumeSessionTest {
    private val pinA = AttachmentPin(1L, "1:stick-a")
    private val pinB = AttachmentPin(2L, "1:stick-b")

    /** Session whose live attachment is [current]; [open] ignores the pin it was called with. */
    private fun pinnedSession(
        current: () -> AttachmentPin? = { pinA },
        open: (String, Boolean) -> MountedFilesVolume,
    ): FilesVolumeSession =
        FilesVolumeSession(
            currentAttachment = current,
            openMounted = { partitionId, writable, _, _ -> open(partitionId, writable) },
        )

    @Test
    fun closeReturnsNullWhenNothingOpen() {
        val session = pinnedSession { _, _ -> error("must not open") }
        assertNull(session.close())
    }

    @Test
    fun closeOrderIsVolumeThenDisk() {
        val events = mutableListOf<String>()
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, events, volumeClose = null, diskClose = null)
            }
        session.withLock { session.volumeForLocked(pinA, "1") }
        assertNull(session.close())
        assertEquals(listOf("volume", "disk"), events)
    }

    @Test
    fun closeFailureReturnedAndGateRefusesContinuation() {
        val closeErr = IOException("volume sync unconfirmed")
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        session.withLock { session.volumeForLocked(pinA, "1") }
        val err = session.close()
        assertSame(closeErr, err)

        var continued = false
        try {
            requireFilesVolumeClosed(err)
            continued = true
            fail("gate must throw")
        } catch (t: Throwable) {
            assertSame(closeErr, t)
        }
        assertEquals(false, continued)
    }

    @Test
    fun remountRefusedAfterUnconfirmedClose() {
        val closeErr = IOException("umount failed")
        var opens = 0
        val session =
            pinnedSession { partitionId, writable ->
                opens++
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        session.withLock {
            session.volumeForLocked(pinA, "1", writable = false)
            val thrown =
                assertThrows(IllegalStateException::class.java) {
                    // RO → RW forces close of current before reopen.
                    session.volumeForLocked(pinA, "1", writable = true)
                }
            assertTrue(thrown.message!!.contains("refusing remount"))
            assertSame(closeErr, thrown.cause)
        }
        assertEquals("second open must not run after refuse", 1, opens)
        // Latched: second close still reports the same error (never null-success).
        assertSame(closeErr, session.close())
        assertSame(closeErr, session.close())
        // Fresh mount after latch still refused (process-lifetime fail-closed).
        session.withLock {
            val again =
                assertThrows(IllegalStateException::class.java) {
                    session.volumeForLocked(pinA, "2", writable = false)
                }
            assertSame(closeErr, again.cause)
        }
        assertEquals(1, opens)
    }

    @Test
    fun explicitCloseFailureLatchedAndBlocksRemount() {
        val closeErr = IOException("EXT close/sync failed")
        var opens = 0
        val session =
            pinnedSession { partitionId, writable ->
                opens++
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        session.withLock { session.volumeForLocked(pinA, "1") }
        assertSame(closeErr, session.close())
        // Gate sees latched error on every subsequent close.
        assertSame(closeErr, session.close())
        var continued = false
        try {
            requireFilesVolumeClosed(session.close())
            continued = true
            fail("gate must throw latched close")
        } catch (t: Throwable) {
            assertSame(closeErr, t)
        }
        assertFalse(continued)
        session.withLock {
            val thrown =
                assertThrows(IllegalStateException::class.java) {
                    session.volumeForLocked(pinA, "1")
                }
            assertTrue(thrown.message!!.contains("refusing remount"))
            assertSame(closeErr, thrown.cause)
        }
        assertEquals(1, opens)
    }

    @Test
    fun writableCloseFailLatchesAgainstNextWritable() {
        val closeErr = IOException("umount failed")
        var opens = 0
        val session =
            pinnedSession { partitionId, writable ->
                opens++
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        val op = DiskOperation(name = "mkdir")
        op.state = OperationState.Running
        session.withLock {
            try {
                session.withWritableVolume(pinA, "1", op) { /* ok body */ }
                fail("expected CloseAfterSuccessException")
            } catch (_: CloseAfterSuccessException) {
                assertEquals(OperationState.Partial, op.state)
            }
            val thrown =
                assertThrows(IllegalStateException::class.java) {
                    session.withWritableVolume(pinA, "1", DiskOperation(name = "retry")) { }
                }
            assertSame(closeErr, thrown.cause)
        }
        assertEquals(1, opens)
        assertSame(closeErr, session.close())
    }

    @Test
    fun writableSuccessPlusCloseFailMarksPartial() {
        val closeErr = IOException("EXT close/sync failed")
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        val op = DiskOperation(name = "mkdir")
        op.state = OperationState.Running
        var bodyRan = false
        session.withLock {
            try {
                session.withWritableVolume(pinA, "1", op) {
                    bodyRan = true
                }
                fail("expected CloseAfterSuccessException")
            } catch (e: CloseAfterSuccessException) {
                assertSame(closeErr, e.cause)
                assertEquals(OperationState.Partial, op.state)
                assertSame(e, op.failureCause)
                assertTrue(e.message!!.contains("Change applied"))
            }
        }
        assertTrue(bodyRan)
        assertTrue(op.state != OperationState.Completed)
    }

    @Test
    fun writableBodyFailureKeepsPrimaryAndSuppressesClose() {
        val closeErr = IOException("close failed")
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        val op = DiskOperation(name = "upload")
        op.state = OperationState.Running
        val primary = IllegalStateException("write failed")
        session.withLock {
            try {
                session.withWritableVolume(pinA, "1", op) {
                    throw primary
                }
                fail("expected primary")
            } catch (e: IllegalStateException) {
                assertSame(primary, e)
                assertEquals(1, e.suppressed.size)
                assertSame(closeErr, e.suppressed[0])
            }
        }
        // Close path must not flip Running → Partial when body already failed.
        assertEquals(OperationState.Running, op.state)
    }

    @Test
    fun diskCloseFailureSurfacesWhenVolumeCloseOk() {
        val diskErr = IOException("disk close failed")
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = null, diskClose = diskErr)
            }
        session.withLock { session.volumeForLocked(pinA, "2") }
        val err = session.close()
        assertSame(diskErr, err)
    }

    @Test
    fun volumeCloseFailureSuppressesDiskCloseFailure() {
        val volumeErr = IOException("volume close failed")
        val diskErr = IOException("disk close failed")
        val events = mutableListOf<String>()
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, events, volumeClose = volumeErr, diskClose = diskErr)
            }
        session.withLock { session.volumeForLocked(pinA, "1") }
        val err = session.close()
        assertSame(volumeErr, err)
        assertEquals(1, volumeErr.suppressed.size)
        assertSame(diskErr, volumeErr.suppressed[0])
        assertEquals(listOf("volume", "disk"), events)
    }

    @Test
    fun cachedVolumeReusedForSamePartitionAndMode() {
        var opens = 0
        val session =
            pinnedSession { partitionId, writable ->
                opens++
                mounted(partitionId, writable, volumeClose = null, diskClose = null)
            }
        session.withLock {
            val a = session.volumeForLocked(pinA, "1", writable = false)
            val b = session.volumeForLocked(pinA, "1", writable = false)
            assertSame(a, b)
        }
        assertEquals(1, opens)
    }




    @Test
    fun o2FailBeforeAcquireDoesNotCloseForeignMount() {
        val events = mutableListOf<String>()
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, events, volumeClose = null, diskClose = null)
            }
        // B already holds RO cache.
        session.withLock { session.volumeForLocked(pinA, "B") }
        assertTrue(events.isEmpty())
        session.withLock {
            var token = 0L
            var primary: Throwable? = null
            try {
                session.withAcquireRecorder({ token = it }) {
                    error("binding changed before volume")
                }
            } catch (t: IllegalStateException) {
                primary = t
            } finally {
                if (primary != null && token != 0L) {
                    session.releaseAcquiredAfterErrorLocked(token, primary)
                }
            }
            assertEquals(0L, token)
            // Foreign mount B still open — never closed by fail-before-acquire.
            assertTrue(session.mountEpochLocked() != null)
        }
        assertTrue(events.isEmpty()) // no close
    }

    @Test
    fun o2FailAfterAcquireClosesBeforeNextOpSameMountReuse() {
        val events = mutableListOf<String>()
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, events, volumeClose = null, diskClose = null)
            }
        // Op A: acquire then fail — O2 closes under same lock.
        session.withLock {
            var token = 0L
            var primary: Throwable? = null
            try {
                session.withAcquireRecorder({ token = it }) {
                    session.volumeForLocked(pinA, "1")
                    error("list failed")
                }
            } catch (t: IllegalStateException) {
                primary = t
            } finally {
                if (primary != null && token != 0L) {
                    session.releaseAcquiredAfterErrorLocked(token, primary)
                }
            }
            assertTrue(token != 0L)
            assertNull(session.mountEpochLocked())
        }
        assertEquals(listOf("volume", "disk"), events)

        // Op B: same partition — fresh open, not poisoned reuse of A's failed handle.
        events.clear()
        session.withLock {
            session.volumeForLocked(pinA, "1")
            assertTrue(session.mountEpochLocked() != null)
        }
        assertTrue(events.isEmpty()) // open only, no prior close in this phase
    }

    @Test
    fun o2SuccessKeepsRoCacheForNextBrowse() {
        val events = mutableListOf<String>()
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, events, volumeClose = null, diskClose = null)
            }
        session.withLock {
            var token = 0L
            session.withAcquireRecorder({ token = it }) {
                session.volumeForLocked(pinA, "1")
            }
            // Success: do NOT release — RO cache stays.
            assertTrue(token != 0L)
            assertTrue(session.mountEpochLocked() != null)
        }
        assertTrue(events.isEmpty())
        session.withLock {
            val a = session.volumeForLocked(pinA, "1")
            val b = session.volumeForLocked(pinA, "1")
            assertSame(a, b)
        }
        assertEquals(0, events.size)
    }

    @Test
    fun o2CloseErrorSuppressedOntoPrimaryNotReplacing() {
        val closeErr = IOException("volume sync unconfirmed")
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        val primary = IOException("list failed: 5")
        session.withLock {
            var token = 0L
            session.withAcquireRecorder({ token = it }) {
                session.volumeForLocked(pinA, "1")
            }
            val result = session.releaseAcquiredAfterErrorLocked(token, primary)
            assertTrue(result is CloseEpochResult.ClosedFailed)
            assertSame(closeErr, (result as CloseEpochResult.ClosedFailed).error)
            assertEquals(1, primary.suppressed.size)
            assertSame(closeErr, primary.suppressed[0])
            assertEquals("list failed: 5", primary.message)
            assertNull(session.mountEpochLocked())
            // Latch blocks any later mount in this session.
            val blocked =
                assertThrows(IllegalStateException::class.java) {
                    session.volumeForLocked(pinA, "1")
                }
            assertSame(closeErr, blocked.cause)
        }
        val publish =
            FilesOperationPolicy.planCloseDiagnostic(
                primary = primary,
                quiet = true,
                hasBoundFailure = true,
                opStillCurrent = true,
            )
        assertTrue(publish is CloseDiagnosticPublish.LogOnly)
        assertEquals(
            "list failed: 5",
            FilesOperationPolicy.primaryErrorMessage(primary),
        )
        assertSame(closeErr, session.close())
    }

    @Test
    fun o2PartialWritableCloseUnchangedSemantics() {
        val closeErr = IOException("EXT close/sync failed")
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        val op = DiskOperation(name = "mkdir")
        op.state = OperationState.Running
        session.withLock {
            try {
                session.withWritableVolume(pinA, "1", op) { /* body ok */ }
                fail("expected CloseAfterSuccessException")
            } catch (e: CloseAfterSuccessException) {
                assertSame(closeErr, e.cause)
                assertEquals(OperationState.Partial, op.state)
            }
        }
    }

    @Test
    fun o2ReleaseNotOwnedWhenAlreadyClosedIsHarmless() {
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = null, diskClose = null)
            }
        val primary = IOException("x")
        session.withLock {
            var token = 0L
            session.withAcquireRecorder({ token = it }) {
                session.volumeForLocked(pinA, "1")
            }
            assertEquals(CloseEpochResult.ClosedOk, session.closeIfEpochLocked(token))
            assertEquals(
                CloseEpochResult.NotOwned,
                session.releaseAcquiredAfterErrorLocked(token, primary),
            )
            assertEquals(0, primary.suppressed.size)
        }
    }


    @Test
    fun o2CancelPathCloseFailureSurvivesAsExplicitChannel() {
        // Mirrors submitFilesOperation: body throws cancel-like error after acquire;
        // close fails; host keeps ClosedFailed aside so await Cancelled can still log it
        // even when executor drops failureCause for RO cancel.
        val closeErr = IOException("volume sync unconfirmed")
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        val cancel = RuntimeException("cancelled")
        var explicit: Throwable? = null
        session.withLock {
            var token = 0L
            var bodyError: Throwable? = null
            try {
                session.withAcquireRecorder({ token = it }) {
                    session.volumeForLocked(pinA, "1")
                    throw cancel
                }
            } catch (t: Throwable) {
                bodyError = t
            } finally {
                val err = bodyError
                if (err != null && token != 0L) {
                    when (val r = session.releaseAcquiredAfterErrorLocked(token, err)) {
                        is CloseEpochResult.ClosedFailed -> explicit = r.error
                        else -> Unit
                    }
                }
            }
        }
        assertSame(closeErr, explicit)
        assertSame(closeErr, cancel.suppressed.single())
        // Terminal would stay Cancelled; diagnostic still publishable:
        val d =
            FilesOperationPolicy.planCloseDiagnostic(
                primary = null, // executor cleared
                quiet = true,
                hasBoundFailure = true,
                opStillCurrent = true,
                explicitClose = explicit,
            )
        assertTrue(d is CloseDiagnosticPublish.LogOnly)
        assertNull(session.mountEpochLocked())
    }

    @Test
    fun mountFenceCoversVolumeStillOpening() {
        // The epoch is allocated before openMounted runs, so a detach fence taken while the
        // mount is in flight already covers it and its later close drops the stale mount.
        var fenceDuringOpen = -1L
        lateinit var session: FilesVolumeSession
        session =
            pinnedSession { partitionId, writable ->
                fenceDuringOpen = session.mountFence()
                mounted(partitionId, writable, volumeClose = null, diskClose = null)
            }
        session.withLock { session.volumeForLocked(pinA, "1") }
        assertEquals(1L, fenceDuringOpen)
        assertEquals(
            CloseEpochResult.ClosedOk,
            session.withLock { session.closeIfEpochAtMostLocked(fenceDuringOpen) },
        )
        assertNull(session.mountEpochLocked())
    }

    @Test
    fun mountFenceTracksAllocatedEpochsWithoutLock() {
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, volumeClose = null, diskClose = null)
            }
        assertEquals(0L, session.mountFence())
        assertNull(session.mountEpochLocked())
        session.withLock { session.volumeForLocked(pinA, "1") }
        val first = session.mountFence()
        assertTrue(first > 0L)
        assertEquals(session.withLock { session.mountEpochLocked() }, first)
        assertEquals(CloseEpochResult.ClosedOk, session.withLock { session.closeIfEpochAtMostLocked(first) })
        assertNull(session.mountEpochLocked())

        session.withLock { session.volumeForLocked(pinA, "1") }
        assertTrue(session.mountFence() > first)
    }

    @Test
    fun detachFenceCloseDropsMountWhenNewAttachWaitsForPermission() {
        // Detach captures the fence; the new attach stays in PermissionRequired so it never
        // mounts. The delayed detach close must still drop the old mount.
        val events = mutableListOf<String>()
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, events, volumeClose = null, diskClose = null)
            }
        session.withLock { session.volumeForLocked(pinA, "1") }
        val detachedFence = session.mountFence()
        assertTrue(events.isEmpty())

        assertEquals(
            CloseEpochResult.ClosedOk,
            session.withLock { session.closeIfEpochAtMostLocked(detachedFence) },
        )
        assertEquals(listOf("volume", "disk"), events)
        assertNull(session.mountEpochLocked())

        // Permission granted afterwards: a fresh mount is allowed, not blocked by the close.
        session.withLock { session.volumeForLocked(pinA, "1") }
        assertTrue(session.mountEpochLocked() != null)
        assertEquals(listOf("volume", "disk"), events)
    }

    @Test
    fun staleDetachFenceNeverClosesMountOpenedByLaterAttach() {
        val events = mutableListOf<String>()
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, events, volumeClose = null, diskClose = null)
            }
        session.withLock { session.volumeForLocked(pinA, "1") }
        val detachedFence = session.mountFence()
        // Later attach mounts partition 2 before the detach coroutine runs; the session
        // itself closes the replaced mount.
        session.withLock { session.volumeForLocked(pinA, "2") }
        assertEquals(listOf("volume", "disk"), events)

        assertEquals(
            CloseEpochResult.NotOwned,
            session.withLock { session.closeIfEpochAtMostLocked(detachedFence) },
        )
        assertEquals("stale fence must not close the newer mount", listOf("volume", "disk"), events)
        assertEquals(
            CloseEpochResult.ClosedOk,
            session.withLock { session.closeIfEpochAtMostLocked(session.mountFence()) },
        )
        assertEquals(listOf("volume", "disk", "volume", "disk"), events)
    }

    @Test
    fun staleDetachFenceIsNotOwnedAfterRemountOfSamePartition() {
        val events = mutableListOf<String>()
        val session =
            pinnedSession { partitionId, writable ->
                mounted(partitionId, writable, events, volumeClose = null, diskClose = null)
            }
        session.withLock { session.volumeForLocked(pinA, "1") }
        val detachedFence = session.mountFence()
        session.withLock { session.closeIfEpochAtMostLocked(detachedFence) }
        session.withLock { session.volumeForLocked(pinA, "1") }
        assertTrue("remount must advance the fence", session.mountFence() > detachedFence)
        events.clear()

        assertEquals(
            CloseEpochResult.NotOwned,
            session.withLock { session.closeIfEpochAtMostLocked(detachedFence) },
        )
        assertTrue(events.isEmpty())
        assertTrue(session.mountEpochLocked() != null)
    }

    @Test
    fun detachFenceCloseFailureStaysLatchedAndRepelsRemount() {
        val closeErr = IOException("umount failed on detach")
        var opens = 0
        val session =
            pinnedSession { partitionId, writable ->
                opens++
                mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
            }
        session.withLock { session.volumeForLocked(pinA, "1") }
        val detachedFence = session.mountFence()
        val first = session.withLock { session.closeIfEpochAtMostLocked(detachedFence) }
        assertTrue(first is CloseEpochResult.ClosedFailed)
        assertSame(closeErr, (first as CloseEpochResult.ClosedFailed).error)

        // A later detach-style retry finds nothing live (NotOwned) but must not clear the
        // latch: close() and any remount still see the same failure, never null-success.
        assertEquals(
            CloseEpochResult.NotOwned,
            session.withLock { session.closeIfEpochAtMostLocked(detachedFence) },
        )
        assertSame(closeErr, session.close())
        assertNull(session.mountEpochLocked())

        session.withLock {
            val thrown =
                assertThrows(IllegalStateException::class.java) {
                    session.volumeForLocked(pinA, "1")
                }
            assertSame(closeErr, thrown.cause)
        }
        assertEquals(1, opens)
    }

    @Test
    fun pausedReadOperationCannotMountNewAttachment() {
        // Upload/list captures the pin, then pauses (contentInfo / SAF query). Detach + attach
        // happens meanwhile; the paused operation must not open the new stick's partition.
        var live: AttachmentPin? = pinA
        var opens = 0
        val session =
            FilesVolumeSession(
                currentAttachment = { live },
                openMounted = { partitionId, writable, _, _ ->
                    opens++
                    mounted(partitionId, writable, volumeClose = null, diskClose = null)
                },
            )
        live = pinB
        val thrown =
            assertThrows(AttachmentChangedException::class.java) {
                session.withLock { session.volumeForLocked(pinA, "1") }
            }
        assertEquals("USB attachment changed", thrown.message)
        assertEquals("must not open a foreign stick", 0, opens)
        assertNull(session.mountEpochLocked())

        // The operation accepted for the new attachment works normally.
        session.withLock { session.volumeForLocked(pinB, "1") }
        assertEquals(1, opens)
    }

    @Test
    fun pausedWritableOperationCannotMutateNewAttachment() {
        var live: AttachmentPin? = pinA
        var mutated = false
        var opens = 0
        val session =
            FilesVolumeSession(
                currentAttachment = { live },
                openMounted = { partitionId, writable, _, _ ->
                    opens++
                    mounted(partitionId, writable, volumeClose = null, diskClose = null)
                },
            )
        live = pinB
        val op = DiskOperation(name = "mkdir")
        op.state = OperationState.Running
        val thrown =
            assertThrows(AttachmentChangedException::class.java) {
                session.withLock {
                    session.withWritableVolume(pinA, "1", op) {
                        mutated = true
                    }
                }
            }
        assertEquals("USB attachment changed", thrown.message)
        assertFalse(mutated)
        assertEquals(0, opens)
        assertEquals(OperationState.Running, op.state)
    }

    @Test
    fun attachmentSwapDuringMountClosesOpenedVolumeAndRefusesPublish() {
        // The stick can be swapped while openMounted runs (USB claim / table read). The
        // freshly opened volume must be closed and never published for the stale pin.
        val events = mutableListOf<String>()
        var live: AttachmentPin? = pinA
        val openedPins = mutableListOf<AttachmentPin>()
        val session =
            FilesVolumeSession(
                currentAttachment = { live },
                openMounted = { partitionId, writable, pin, _ ->
                    openedPins += pin
                    live = pinB
                    mounted(partitionId, writable, events, volumeClose = null, diskClose = null)
                },
            )
        val thrown =
            assertThrows(AttachmentChangedException::class.java) {
                session.withLock { session.volumeForLocked(pinA, "1") }
            }
        assertEquals("USB attachment changed", thrown.message)
        assertEquals(listOf(pinA), openedPins)
        assertEquals(listOf("volume", "disk"), events)
        assertNull(session.mountEpochLocked())
    }

    @Test
    fun attachmentSwapDuringMountWithCloseFailureLatchesAndRepelsRemount() {
        val closeErr = IOException("umount failed after attachment swap")
        var live: AttachmentPin? = pinA
        var opened = false
        val session =
            FilesVolumeSession(
                currentAttachment = { live },
                openMounted = { partitionId, writable, _, _ ->
                    opened = true
                    live = pinB
                    mounted(partitionId, writable, volumeClose = closeErr, diskClose = null)
                },
            )
        assertThrows(AttachmentChangedException::class.java) {
            session.withLock { session.volumeForLocked(pinA, "1") }
        }
        assertTrue(opened)
        // Unconfirmed close of the discarded volume stays latched: no remount under any pin.
        assertSame(closeErr, session.close())
        val thrown =
            assertThrows(IllegalStateException::class.java) {
                session.withLock { session.volumeForLocked(pinB, "1") }
            }
        assertSame(closeErr, thrown.cause)
    }

    @Test
    fun cacheHitRequiresMatchingAttachmentPin() {
        val events = mutableListOf<String>()
        var live: AttachmentPin? = pinA
        val openedPins = mutableListOf<AttachmentPin>()
        val session =
            FilesVolumeSession(
                currentAttachment = { live },
                openMounted = { partitionId, writable, pin, _ ->
                    openedPins += pin
                    mounted(partitionId, writable, events, volumeClose = null, diskClose = null)
                },
            )
        val volumeA = session.withLock { session.volumeForLocked(pinA, "1") }
        assertEquals(listOf(pinA), openedPins)

        // Attach swapped before the detach close ran: a pinB op must close A and open fresh.
        live = pinB
        val volumeB = session.withLock { session.volumeForLocked(pinB, "1") }
        assertTrue("cache must not cross attachments", volumeA !== volumeB)
        assertEquals(listOf(pinA, pinB), openedPins)
        assertEquals(listOf("volume", "disk"), events)

        // The pinned mount is reused only by the matching pin.
        assertSame(volumeB, session.withLock { session.volumeForLocked(pinB, "1") })
        assertEquals(listOf(pinA, pinB), openedPins)
    }

    @Test
    fun oldPinCannotReuseOrCloseMountOfNewerAttachment() {
        val events = mutableListOf<String>()
        var live: AttachmentPin? = pinA
        var opens = 0
        val session =
            FilesVolumeSession(
                currentAttachment = { live },
                openMounted = { partitionId, writable, _, _ ->
                    opens++
                    mounted(partitionId, writable, events, volumeClose = null, diskClose = null)
                },
            )
        session.withLock { session.volumeForLocked(pinA, "1") }
        live = pinB
        val volumeB = session.withLock { session.volumeForLocked(pinB, "1") }
        assertEquals(listOf("volume", "disk"), events)

        // Late op of the old attachment: refused without touching the new mount.
        assertThrows(AttachmentChangedException::class.java) {
            session.withLock { session.volumeForLocked(pinA, "1") }
        }
        assertEquals(listOf("volume", "disk"), events)
        assertSame(volumeB, session.withLock { session.volumeForLocked(pinB, "1") })
        assertEquals(2, opens)
    }

    @Test
    fun detachedPinCannotReuseStaleMountAndFenceCloseStillWorks() {
        val events = mutableListOf<String>()
        var live: AttachmentPin? = pinA
        val session =
            FilesVolumeSession(
                currentAttachment = { live },
                openMounted = { partitionId, writable, _, _ ->
                    mounted(partitionId, writable, events, volumeClose = null, diskClose = null)
                },
            )
        session.withLock { session.volumeForLocked(pinA, "1") }
        val fence = session.mountFence()
        live = null

        // A files op that survived the detach must not touch the stale mount.
        assertThrows(AttachmentChangedException::class.java) {
            session.withLock { session.volumeForLocked(pinA, "1") }
        }
        assertTrue("refusal must not close the mount", events.isEmpty())

        // Detach close (epoch fence) still owns and drops it.
        assertEquals(
            CloseEpochResult.ClosedOk,
            session.withLock { session.closeIfEpochAtMostLocked(fence) },
        )
        assertEquals(listOf("volume", "disk"), events)
    }

    @Test
    fun sameGenerationDifferentDeviceKeyIsForeign() {
        val pinC = AttachmentPin(pinA.attachGeneration, "1:other-stick")
        val live: AttachmentPin? = pinC
        var opens = 0
        val session =
            FilesVolumeSession(
                currentAttachment = { live },
                openMounted = { partitionId, writable, _, _ ->
                    opens++
                    mounted(partitionId, writable, volumeClose = null, diskClose = null)
                },
            )
        assertThrows(AttachmentChangedException::class.java) {
            session.withLock { session.volumeForLocked(pinA, "1") }
        }
        assertEquals(0, opens)
        session.withLock { session.volumeForLocked(pinC, "1") }
        assertEquals(1, opens)
    }

    // ------------------------------------------------------------------
    // Exclusive USB ownership: one claim across close→open→operation→close
    // ------------------------------------------------------------------

    @Test
    fun mountedVolumeHoldsClaimUntilSessionClose() {
        val session =
            FilesVolumeSession(
                currentAttachment = { pinA },
                openMounted = { partitionId, writable, _, lease ->
                    mounted(partitionId, writable, volumeClose = null, diskClose = null, lease = lease)
                },
            )
        assertFalse("nothing open, nothing claimed", session.claim.isHeld())
        session.withLock { session.volumeForLocked(pinA, "1") }
        assertTrue("cache keeps the claim", session.claim.isHeld())
        assertNull(session.close())
        assertFalse("close releases the claim", session.claim.isHeld())
    }

    @Test
    fun rawOpenClosesMountThenClaimsSoAMountAttemptIsRefused() {
        // Same-thread ordering of the primitive: the raw open closes the mount, then takes
        // the claim, and a mount attempted at that point (the probe's code path) is refused.
        // The concurrent variant with a real probe thread is
        // probeThreadLosesTheDeviceWhileRawOpenHoldsTheClaim.
        val events = mutableListOf<String>()
        val session =
            FilesVolumeSession(
                currentAttachment = { pinA },
                openMounted = { partitionId, writable, _, lease ->
                    mounted(partitionId, writable, events, volumeClose = null, diskClose = null, lease = lease)
                },
            )
        session.withLock { session.volumeForLocked(pinA, "1") }
        val rawLease =
            session.openAfterCloseClaimed { lease ->
                // Ordering under the old race: the mount is already closed and the claim is
                // held *before* the raw open starts.
                assertNull("mount closed before the raw claim", session.mountEpochLocked())
                assertTrue("claim is held for the raw open", session.claim.isHeld())
                // A mount attempt at this exact point must lose the claim instead of
                // opening a second session on the same stick.
                assertThrows(UsbExclusiveClaim.BusyException::class.java) {
                    session.volumeForLocked(pinA, "1")
                }
                lease
            }

        assertEquals("mount is closed before the raw claim", listOf("volume", "disk"), events)
        assertTrue("raw session owns the device", session.claim.isHeld())
        assertThrows(UsbExclusiveClaim.BusyException::class.java) {
            session.withLock { session.volumeForLocked(pinA, "1") }
        }

        rawLease.close()
        assertFalse("raw close releases the claim", session.claim.isHeld())
        session.withLock { session.volumeForLocked(pinA, "1") }
        assertTrue(session.claim.isHeld())
    }

    @Test
    fun rawOpenRefusesWhenMountCloseWasNotConfirmed() {
        val closeErr = IOException("umount failed")
        val session =
            FilesVolumeSession(
                currentAttachment = { pinA },
                openMounted = { partitionId, writable, _, lease ->
                    mounted(partitionId, writable, volumeClose = closeErr, diskClose = null, lease = lease)
                },
            )
        session.withLock { session.volumeForLocked(pinA, "1") }
        var opened = false
        val thrown =
            assertThrows(IOException::class.java) {
                session.openAfterCloseClaimed { lease ->
                    opened = true
                    lease.close()
                }
            }
        assertSame("the unconfirmed close error is what aborts the open", closeErr, thrown)
        assertFalse("no raw open after an unconfirmed close", opened)
    }

    @Test
    fun rawOpenFailureReleasesClaim() {
        val session =
            FilesVolumeSession(
                currentAttachment = { pinA },
                openMounted = { _, _, _, _ -> error("must not mount") },
            )
        val boom = IOException("usb open failed")
        assertSame(
            boom,
            assertThrows(IOException::class.java) {
                session.openAfterCloseClaimed { throw boom }
            },
        )
        assertFalse(session.claim.isHeld())
    }

    @Test
    fun failedMountOpenReleasesClaim() {
        var attempts = 0
        val boom = IOException("partition gone")
        val session =
            FilesVolumeSession(
                currentAttachment = { pinA },
                openMounted = { _, _, _, _ ->
                    attempts++
                    throw boom
                },
            )
        assertSame(
            boom,
            assertThrows(IOException::class.java) {
                session.withLock { session.volumeForLocked(pinA, "1") }
            },
        )
        assertEquals(1, attempts)
        assertFalse("a failed open must not keep the claim", session.claim.isHeld())
    }

    @Test
    fun attachmentSwapAfterMountOpenClosesVolumeAndReleasesClaim() {
        var live: AttachmentPin? = pinA
        val events = mutableListOf<String>()
        val session =
            FilesVolumeSession(
                currentAttachment = { live },
                openMounted = { partitionId, writable, _, lease ->
                    live = pinB
                    mounted(partitionId, writable, events, volumeClose = null, diskClose = null, lease = lease)
                },
            )
        assertThrows(AttachmentChangedException::class.java) {
            session.withLock { session.volumeForLocked(pinA, "1") }
        }
        assertEquals(listOf("volume", "disk"), events)
        assertFalse("discarded volume must release the claim", session.claim.isHeld())
    }

    @Test
    fun rawOpenCallbackRunsAfterMountCloseAndAbortReleasesClaim() {
        // Primitive ordering only: the mount close commits (including an attachment change
        // made while closing) before the open callback runs, and throwing there aborts with
        // the claim released. This does NOT exercise MainViewModel.openRawDisk's revalidate
        // and post-open fences (Android types, not unit-testable here); it asserts the
        // session-level ordering those fences rely on.
        var live: AttachmentPin? = pinA
        val session =
            FilesVolumeSession(
                currentAttachment = { live },
                openMounted = { partitionId, writable, _, lease ->
                    mounted(
                        partitionId,
                        writable,
                        volumeClose = null,
                        diskClose = null,
                        lease = lease,
                        beforeVolumeClose = { live = pinB },
                    )
                },
            )
        session.withLock { session.volumeForLocked(pinA, "1") }
        var opened = false
        val thrown =
            assertThrows(AttachmentChangedException::class.java) {
                session.openAfterCloseClaimed { lease ->
                    assertNull("mount close already committed", session.mountEpochLocked())
                    if (live != pinA) {
                        lease.close()
                        throw AttachmentChangedException()
                    }
                    opened = true
                    throw AssertionError("stale attachment must not be opened")
                }
            }
        assertEquals("USB attachment changed", thrown.message)
        assertFalse("stale attachment must not be opened", opened)
        assertEquals(pinB, live)
        assertFalse("aborted open releases the claim", session.claim.isHeld())
    }

    @Test
    fun probeThreadLosesTheDeviceWhileRawOpenHoldsTheClaim() {
        // Real transition, not an inline simulation: a probe on another thread enters the
        // files lock while the raw open callback runs. The open must not hold the lock (or
        // this test times out) and the probe must lose the claim, never mount.
        val session =
            FilesVolumeSession(
                currentAttachment = { pinA },
                openMounted = { partitionId, writable, _, lease ->
                    mounted(partitionId, writable, volumeClose = null, diskClose = null, lease = lease)
                },
            )
        session.withLock { session.volumeForLocked(pinA, "1") }
        val rawOpenEntered = CountDownLatch(1)
        val probeDone = CountDownLatch(1)
        val probeFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val probe =
            Thread {
                rawOpenEntered.await()
                try {
                    session.withLock { session.volumeForLocked(pinA, "1") }
                } catch (t: Throwable) {
                    probeFailure.set(t)
                } finally {
                    probeDone.countDown()
                }
            }
        probe.start()
        val lease =
            session.openAfterCloseClaimed { held ->
                rawOpenEntered.countDown()
                assertTrue(
                    "probe must finish during the raw open: the lock must be free",
                    probeDone.await(5, java.util.concurrent.TimeUnit.SECONDS),
                )
                held
            }
        probe.join()
        assertTrue(
            "probe must be refused the device",
            probeFailure.get() is UsbExclusiveClaim.BusyException,
        )
        assertNull("no mount exists while the raw claim is held", session.mountEpochLocked())
        lease.close()
        assertFalse(session.claim.isHeld())
    }

    @Test
    fun sessionsSharingTheProcessClaimCannotMountTheSameStickTwice() {
        // Two sessions = two ViewModels in one process; DiskController hands both the same
        // claim, so the second cannot open a second BOT session on the same stick.
        val claim = UsbExclusiveClaim()
        fun session(): FilesVolumeSession =
            FilesVolumeSession(
                currentAttachment = { pinA },
                openMounted = { partitionId, writable, _, lease ->
                    mounted(partitionId, writable, volumeClose = null, diskClose = null, lease = lease)
                },
                claim = claim,
            )
        val first = session()
        val second = session()
        first.withLock { first.volumeForLocked(pinA, "1") }
        assertThrows(UsbExclusiveClaim.BusyException::class.java) {
            second.withLock { second.volumeForLocked(pinA, "1") }
        }
        assertNull(first.close())
        second.withLock { second.volumeForLocked(pinA, "1") }
        assertTrue(claim.isHeld())
        assertNull(second.close())
        assertFalse(claim.isHeld())
    }

    private fun mounted(
        partitionId: String,
        writable: Boolean,
        events: MutableList<String> = mutableListOf(),
        volumeClose: Throwable?,
        diskClose: Throwable?,
        lease: UsbExclusiveClaim.Lease? = null,
        beforeVolumeClose: (() -> Unit)? = null,
    ): MountedFilesVolume {
        val bot =
            object : FakeBotSession() {
                override fun close() {
                    events += "disk"
                    if (diskClose != null) throw diskClose
                    super.close()
                }
            }
        val disk = UsbDisk(session = bot, geometry = BlockGeometry(512, 64UL), lease = lease)
        val volume =
            object : UsbFsVolume {
                override fun list(path: String) = emptyList<UsbFsDirEntry>()
                override fun stat(path: String) = error("unused")
                override fun statOrNull(path: String) = null
                override fun mkdir(path: String) = Unit
                override fun delete(path: String) = Unit
                override fun rename(from: String, to: String) = Unit
                override fun read(path: String, offset: Long, length: Int) = ByteArray(0)
                override fun write(
                    path: String,
                    data: ByteArray,
                    offset: Long,
                    create: Boolean,
                    plannedTotalSize: Long,
                ) = Unit
                override fun sync() = Unit
                override fun close() {
                    events += "volume"
                    beforeVolumeClose?.invoke()
                    if (volumeClose != null) throw volumeClose
                }
            }
        return MountedFilesVolume(partitionId, disk, volume, writable)
    }
}
