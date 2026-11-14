package usb.multitool

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowEnvironment
import org.robolectric.shadows.ShadowLooper
import usb.image.ImageStaging
import usb.service.OperationTokenGenerator
import usb.ui.BackupFolderAction
import usb.ui.DevicePresence
import usb.ui.FilesPickLaunchSource
import usb.ui.OperationProgress
import usb.ui.OperationState
import usb.ui.OperationTitle
import usb.ui.PendingBackupFolderAction
import usb.ui.UiIntent
import usb.ui.UiMessage
import usb.ui.UiMessageKey
import usb.ui.WindowsImportKind
import usb.ui.WindowsImportRequestCodes
import usb.ventoy.VentoyReleaseStore
import usb.ventoy.VentoyReleaseStoreState

/**
 * Production seam tests for [MainActivity] saved-state restoration, [MainActivity.onActivityResult]
 * attachment fencing, and [MainViewModel] attachment/backup-pin/staging/async-picker guards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityPickerRecoveryTest {
    @Before
    fun seedEnvironment() {
        ShadowEnvironment.addExternalDir("usb-multitool-test")
        ImageStaging.clearSessionRegistryForTest()
    }

    private fun mockUsbDevice(
        deviceId: Int = 11,
        deviceName: String = "/dev/bus/usb/001/011",
        productName: String? = "SanDisk",
        serialNumber: String? = "SER-11",
    ): UsbDevice = mockk {
        every { this@mockk.deviceId } returns deviceId
        every { this@mockk.deviceName } returns deviceName
        every { this@mockk.productName } returns productName
        every { this@mockk.serialNumber } returns serialNumber
    }
    private fun dispatchActivityResult(
        activity: MainActivity,
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        MainActivity::class.java.getDeclaredMethod(
            "onActivityResult",
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Intent::class.java,
        ).apply { isAccessible = true }.invoke(activity, requestCode, resultCode, data)
    }

    @Test
    fun backupFolderSavedActionFromDeadProcessFailsClosedWithActionLost() {
        val saved = Bundle().apply {
            putString("usb.multitool.PENDING_FOLDER_ACTION", BackupFolderAction.RescueBackup.name)
            putString("usb.multitool.PENDING_FOLDER_IDENTITY", "SanDisk#SER-11")
            putLong("usb.multitool.PENDING_FOLDER_ATTACH", 3L)
            putString("usb.multitool.PENDING_FOLDER_DEVICE_KEY", "11:/dev/bus/usb/001/011")
            putString("usb.multitool.PENDING_FOLDER_SERIAL", "SER-11")
            putString("usb.multitool.PENDING_FOLDER_TOKEN", "dead-process-token")
        }

        val controller = Robolectric.buildActivity(MainActivity::class.java).create(saved)
        try {
            val activity = controller.get()
            val vm = ViewModelProvider(activity)[MainViewModel::class.java]

            assertEquals(UiMessage.Key(UiMessageKey.BackupFolderActionLost), vm.state.statusMessage)

            // Next saveInstanceState keeps the lost flag and never re-serializes the dropped action.
            val outState = Bundle()
            controller.saveInstanceState(outState)
            assertTrue(outState.getBoolean("usb.multitool.PENDING_FOLDER_LOST", false))
            assertNull(outState.getString("usb.multitool.PENDING_FOLDER_ACTION"))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun changeFolderSavedActionFromDeadProcessDropsSilentlyWithoutActionLost() {
        val saved = Bundle().apply {
            putString("usb.multitool.PENDING_FOLDER_ACTION", BackupFolderAction.ChangeFolder.name)
            putString("usb.multitool.PENDING_FOLDER_TOKEN", "dead-process-token")
        }

        val controller = Robolectric.buildActivity(MainActivity::class.java).create(saved)
        try {
            val activity = controller.get()
            val vm = ViewModelProvider(activity)[MainViewModel::class.java]

            assertFalse(vm.state.statusMessage == UiMessage.Key(UiMessageKey.BackupFolderActionLost))
            val outState = Bundle()
            controller.saveInstanceState(outState)
            assertFalse(outState.getBoolean("usb.multitool.PENDING_FOLDER_LOST", false))
            assertNull(outState.getString("usb.multitool.PENDING_FOLDER_ACTION"))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun restoredWindowsImportSurvivesSaveAndRejectsStaleAttachmentOnResultOnce() {
        val requestCode = WindowsImportRequestCodes.FIRST_REQUEST_CODE + 42
        val saved = Bundle().apply {
            putString("usb.multitool.PENDING_WINDOWS_KIND", WindowsImportKind.XmlTemplate.name)
            putString("usb.multitool.PENDING_WINDOWS_LAUNCH_SOURCE", FilesPickLaunchSource.Files.name)
            putString("usb.multitool.PENDING_WINDOWS_IDENTITY", "SanDisk#SER-11")
            putLong("usb.multitool.PENDING_WINDOWS_ATTACH", 1L)
            putInt("usb.multitool.PENDING_WINDOWS_REQUEST", requestCode)
        }

        val controller = Robolectric.buildActivity(MainActivity::class.java).create(saved)
        try {
            val activity = controller.get()
            val vm = ViewModelProvider(activity)[MainViewModel::class.java]

            // Recreation restored the pending import into MainActivity's coordinator.
            val beforeResult = Bundle()
            controller.saveInstanceState(beforeResult)
            assertEquals(requestCode, beforeResult.getInt("usb.multitool.PENDING_WINDOWS_REQUEST", -1))

            // Live state has no matching device (detached / different attachment) -> fail closed.
            val resultIntent = Intent().setData(Uri.parse("content://usb.test/autounattend.xml"))
            dispatchActivityResult(activity, requestCode, Activity.RESULT_OK, resultIntent)

            assertEquals(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid), vm.state.statusMessage)
            assertNull(vm.state.isoImportSafInFlight)

            // Consumed once: saving state now has no pending launch and duplicate callback is ignored.
            val afterResult = Bundle()
            controller.saveInstanceState(afterResult)
            assertFalse(afterResult.containsKey("usb.multitool.PENDING_WINDOWS_REQUEST"))

            vm.onIntent(UiIntent.Status(UiMessage.Raw("sentinel")))
            dispatchActivityResult(activity, requestCode, Activity.RESULT_OK, resultIntent)
            assertEquals(UiMessage.Raw("sentinel"), vm.state.statusMessage)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun partialDownloadBindingInSavedStateIsDiscardedInsteadOfHalfRestored() {
        val partialSaved = Bundle().apply {
            putString("usb.multitool.PENDING_DOWNLOAD_DIRECTORY", "/ISO")
            putString("usb.multitool.PENDING_DOWNLOAD_NAME", "win11.iso")
            putString("usb.multitool.PENDING_DOWNLOAD_SIZE", "4096")
            putString("usb.multitool.PENDING_DOWNLOAD_IDENTITY", "SanDisk#SER-11")
            putString("usb.multitool.PENDING_DOWNLOAD_PARTITION", "1")
            // Intentionally omit PENDING_DOWNLOAD_ATTACH.
        }

        val controller = Robolectric.buildActivity(MainActivity::class.java).create(partialSaved)
        try {
            val outState = Bundle()
            controller.saveInstanceState(outState)

            assertFalse(outState.containsKey("usb.multitool.PENDING_DOWNLOAD_NAME"))
            assertFalse(outState.containsKey("usb.multitool.PENDING_DOWNLOAD_DIRECTORY"))
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun backupPinIsolatesMissingSerialAndMismatchedKeyIdentityAndSerial() {
        val vm = MainViewModel(RuntimeEnvironment.getApplication())

        // Immediate backup request when detached fails closed.
        vm.requestBackupAction(BackupFolderAction.RescueBackup)
        assertEquals(UiMessage.Key(UiMessageKey.BackupFolderTargetChanged), vm.state.statusMessage)

        // 1) Isolate serial.isEmpty(): key and identity match live serial-less device.
        val noSerial = mockUsbDevice(
            deviceId = 1,
            deviceName = "/dev/usb/1",
            productName = "SanDisk",
            serialNumber = null,
        )
        vm.onUsbDeviceAttached(noSerial, hasPermission = false, manager = null)
        val noSerialIdentity = UsbDeviceIdentity.identityString(noSerial)
        vm.requestPinnedBackupAction(
            BackupFolderAction.RescueBackup,
            PendingBackupFolderAction(
                action = BackupFolderAction.RescueBackup,
                identity = noSerialIdentity,
                attachGeneration = vm.attachmentSnapshot().generation,
                deviceKey = "1:/dev/usb/1",
                serialNumber = "",
                sessionToken = vm.backupFolderLaunchToken,
            ),
        )
        assertEquals(
            UiMessage.Key(UiMessageKey.BackupFolderTargetUnverifiable),
            vm.state.statusMessage,
        )

        // 2) Isolate serial != expected.serialNumber: key and identity match live device, only serial differs.
        val liveDevice = mockUsbDevice(
            deviceId = 2,
            deviceName = "/dev/usb/2",
            productName = "SanDisk",
            serialNumber = "SER-LIVE",
        )
        vm.onUsbDeviceAttached(liveDevice, hasPermission = false, manager = null)
        val liveIdentity = UsbDeviceIdentity.identityString(liveDevice)
        val liveGen = vm.attachmentSnapshot().generation
        vm.requestPinnedBackupAction(
            BackupFolderAction.RescueBackup,
            PendingBackupFolderAction(
                action = BackupFolderAction.RescueBackup,
                identity = liveIdentity,
                attachGeneration = liveGen,
                deviceKey = "2:/dev/usb/2",
                serialNumber = "SER-OLD",
                sessionToken = vm.backupFolderLaunchToken,
            ),
        )
        assertEquals(UiMessage.Key(UiMessageKey.BackupFolderTargetChanged), vm.state.statusMessage)

        // 3) Isolate key != expected.deviceKey: identity and serial match.
        vm.onIntent(UiIntent.Status(UiMessage.Raw("reset")))
        vm.requestPinnedBackupAction(
            BackupFolderAction.RescueBackup,
            PendingBackupFolderAction(
                action = BackupFolderAction.RescueBackup,
                identity = liveIdentity,
                attachGeneration = liveGen,
                deviceKey = "99:/dev/usb/99",
                serialNumber = "SER-LIVE",
                sessionToken = vm.backupFolderLaunchToken,
            ),
        )
        assertEquals(UiMessage.Key(UiMessageKey.BackupFolderTargetChanged), vm.state.statusMessage)

        // 4) Isolate identity != expected.identity: key and serial match.
        vm.onIntent(UiIntent.Status(UiMessage.Raw("reset")))
        vm.requestPinnedBackupAction(
            BackupFolderAction.RescueBackup,
            PendingBackupFolderAction(
                action = BackupFolderAction.RescueBackup,
                identity = "OtherBrand#SER-LIVE",
                attachGeneration = liveGen,
                deviceKey = "2:/dev/usb/2",
                serialNumber = "SER-LIVE",
                sessionToken = vm.backupFolderLaunchToken,
            ),
        )
        assertEquals(UiMessage.Key(UiMessageKey.BackupFolderTargetChanged), vm.state.statusMessage)
        assertNull(vm.state.operation)
    }

    @Test
    fun attachGenerationIgnoresWrongDeviceDetachAndBlocksMismatchedOperationBegin() {
        val vm = MainViewModel(RuntimeEnvironment.getApplication())
        val devA = mockUsbDevice(deviceId = 21, deviceName = "/dev/usb/21", serialNumber = "SER-21")
        val devB = mockUsbDevice(deviceId = 22, deviceName = "/dev/usb/22", serialNumber = "SER-22")

        vm.onUsbDeviceAttached(devA, hasPermission = false, manager = null)
        val gen1 = vm.attachmentSnapshot().generation
        assertEquals(devA, vm.attachmentSnapshot().device)

        // Same-device re-probe preserves generation.
        vm.onUsbDeviceAttached(devA, hasPermission = false, manager = null)
        assertEquals(gen1, vm.attachmentSnapshot().generation)

        // Wrong-device detach must not drop the live stick or bump generation.
        vm.onUsbDeviceDetached(devB)
        assertEquals(gen1, vm.attachmentSnapshot().generation)
        assertEquals(devA, vm.attachmentSnapshot().device)

        // Same-generation transient disconnect retains operation via preserveNoDeviceOperation.
        val token1 = OperationTokenGenerator.next()
        vm.onIntent(
            UiIntent.OperationBegan(
                OperationProgress(
                    state = OperationState.Queued,
                    title = OperationTitle.SelfTest,
                    token = token1,
                ),
            ),
        )
        vm.onIntent(UiIntent.DeviceChanged(presence = DevicePresence.Disconnected))
        assertEquals(token1, vm.state.operation?.token)
        vm.onIntent(
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Running,
                    title = OperationTitle.SelfTest,
                    token = token1,
                ),
            ),
        )
        assertEquals(OperationState.Running, vm.state.operation?.state)

        // Real detach bumps generation before clearing state, dropping retained operation.
        vm.onUsbDeviceAttached(devA, hasPermission = false, manager = null)
        vm.onUsbDeviceDetached(devA)
        val gen2 = vm.attachmentSnapshot().generation
        assertTrue(gen2 > gen1)
        assertNull(vm.attachmentSnapshot().device)
        assertEquals(DevicePresence.Disconnected, vm.state.presence)
        assertNull(vm.state.operation)

        // Attach devB and verify MainViewModel's operationGenerationMatches rejects OperationBegan
        // when state.device.attachGeneration disagrees with liveAttachmentGeneration (UiReducer
        // alone would accept OperationBegan).
        vm.onUsbDeviceAttached(devB, hasPermission = false, manager = null)
        val gen3 = vm.attachmentSnapshot().generation
        vm.onIntent(
            UiIntent.DeviceChanged(
                presence = DevicePresence.Ready,
                device = UsbDeviceIdentity.of(devB, attachGeneration = gen3 + 10L),
            ),
        )
        val mismatchedToken = OperationTokenGenerator.next()
        val beforeMismatchedBegin = vm.state.statusMessage
        vm.onIntent(
            UiIntent.OperationBegan(
                OperationProgress(
                    state = OperationState.Running,
                    title = OperationTitle.SelfTest,
                    token = mismatchedToken,
                ),
            ),
        )
        assertNull(vm.state.operation)
        assertEquals(beforeMismatchedBegin, vm.state.statusMessage)
    }

    @Test
    fun staleStagedImagePathIsClearedOnNextIntentWithoutDeletingFile() {
        val app = RuntimeEnvironment.getApplication()
        val vm = MainViewModel(app)
        val stagedFile = ImageStaging.createUnique(app.filesDir, "ubuntu.iso")
        stagedFile.writeText("iso-bytes")

        vm.onIntent(UiIntent.SetStagedRawImage(stagedFile.path, "ubuntu.iso", 9L))
        assertEquals(stagedFile.path, vm.state.stagedRawImagePath)

        // Simulate process restart by dropping the in-memory staging registry.
        ImageStaging.clearSessionRegistryForTest()

        vm.onIntent(UiIntent.ClearLog)
        assertNull(vm.state.stagedRawImagePath)
        assertNull(vm.state.stagedRawImageLabel)
        assertEquals(UiMessage.Key(UiMessageKey.StaleStagedImage), vm.state.statusMessage)
        assertTrue(File(stagedFile.path).exists())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun onGptBackupPickedDropsSlowOlderResultWhenNewerPickWins() {
        val handler = Handler(Looper.getMainLooper())
        val completedDispatches = AtomicInteger(0)
        val trackingMain = object : MainCoroutineDispatcher() {
            override val immediate: MainCoroutineDispatcher get() = this
            override fun isDispatchNeeded(context: CoroutineContext): Boolean =
                Looper.myLooper() != Looper.getMainLooper()

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                handler.post {
                    try {
                        block.run()
                    } finally {
                        completedDispatches.incrementAndGet()
                    }
                }
            }
        }

        val oldOpened = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val oldStreamClosed = CountDownLatch(1)
        Dispatchers.setMain(trackingMain)
        try {
            val app = RuntimeEnvironment.getApplication()
            // Force init's VentoyAssets/VentoyReleaseStore coroutine to transition
            // ventoyStoreState from Absent -> Corrupt so we can await its completion
            // deterministically before starting any GPT pick.
            File(app.filesDir, VentoyReleaseStore.DIR).mkdirs()
            val vm = MainViewModel(app)
            val deadlineInit = System.currentTimeMillis() + 5_000L
            while (vm.state.ventoyStoreState != VentoyReleaseStoreState.Corrupt &&
                System.currentTimeMillis() < deadlineInit
            ) {
                ShadowLooper.idleMainLooper()
                Thread.yield()
            }
            assertEquals(VentoyReleaseStoreState.Corrupt, vm.state.ventoyStoreState)

            val shadowResolver = Shadow.extract<ShadowContentResolver>(app.contentResolver)
            val uriOld = Uri.parse("content://usb.test/gpt-old.bin")
            val uriNew = Uri.parse("content://usb.test/gpt-new.bin")

            shadowResolver.registerInputStreamSupplier(uriOld) {
                oldOpened.countDown()
                releaseOld.await(5, TimeUnit.SECONDS)
                object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                    override fun close() {
                        super.close()
                        oldStreamClosed.countDown()
                    }
                }
            }
            shadowResolver.registerInputStreamSupplier(uriNew) {
                ByteArrayInputStream(byteArrayOf(4, 5, 6))
            }

            vm.onGptBackupPicked(uriOld)
            assertTrue(oldOpened.await(5, TimeUnit.SECONDS))

            vm.onGptBackupPicked(uriNew)

            val deadlineNew = System.currentTimeMillis() + 5_000L
            while (vm.state.backupUri != uriNew.toString() &&
                System.currentTimeMillis() < deadlineNew
            ) {
                ShadowLooper.idleMainLooper()
                Thread.yield()
            }
            assertEquals(uriNew.toString(), vm.state.backupUri)

            // Both init's store coroutine and uriNew are complete. Capture main-thread state
            // right before releasing old stream: at most uriOld (1) and optional upstream check
            // (1, flipping ventoyUpstreamChecking -> false) can still dispatch to Main.
            val beforeReleaseDispatches = completedDispatches.get()
            val wasCheckingUpstream = vm.state.ventoyUpstreamChecking
            releaseOld.countDown()
            assertTrue(oldStreamClosed.await(5, TimeUnit.SECONDS))

            fun requiredDispatches(): Int =
                beforeReleaseDispatches + 1 +
                    if (wasCheckingUpstream && !vm.state.ventoyUpstreamChecking) 1 else 0
            val deadlineOld = System.currentTimeMillis() + 5_000L
            while (completedDispatches.get() < requiredDispatches() &&
                System.currentTimeMillis() < deadlineOld
            ) {
                ShadowLooper.idleMainLooper()
                Thread.yield()
            }
            assertTrue(
                "old coroutine main-thread dispatch must complete before asserting",
                completedDispatches.get() >= requiredDispatches(),
            )
            assertEquals(uriNew.toString(), vm.state.backupUri)
            assertNotNull(vm.state.backupInspection)
            assertEquals(uriNew.toString(), vm.state.backupInspection?.uri)
            assertEquals(1, vm.state.logLines.count { it.contains("GPT backup picked:") })
        } finally {
            releaseOld.countDown()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun revokedOrMissingPersistedBackupFolderGrantRefusesUsability() {
        val app = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABackups")
        app.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("backup_folder_uri", uri.toString())
            .putString("backup_folder_name", "Backups")
            .apply()

        val vm = MainViewModel(app)
        assertEquals(uri.toString(), vm.state.backupFolderUri)
        assertEquals("Backups", vm.state.backupFolderName)
        assertFalse(vm.state.backupFolderUsable)
        assertFalse(vm.hasUsableBackupFolder())

        // Even if state is marked usable, revoking the persisted grant makes hasUsableBackupFolder false.
        vm.onBackupFolderPicked(uri)
        assertTrue(vm.hasUsableBackupFolder())
        app.contentResolver.releasePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        assertFalse(vm.hasUsableBackupFolder())
    }
}
