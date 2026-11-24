package usb.ui

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException as JavaCancellationException
import org.junit.Assert.assertNotEquals
import kotlinx.coroutines.CancellationException as KotlinCancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.DiskException
import usb.files.AttachmentChangedException
import usb.files.EjectedException
import usb.image.ImageCancelledException
import usb.image.ImageTooLargeException as RawImageTooLargeException
import usb.image.ImageVerifyException
import usb.multitool.EjectStaleCloseException
import usb.operation.ProviderRejectedException
import usb.operation.SizeMismatchException
import usb.ventoy.ImageEmptyException
import usb.ventoy.ImageSizeMismatchException
import usb.ventoy.ImageTooLargeException as VentoyImageTooLargeException
import java.io.File

class OperationFailureTest {
    private fun resource(relative: String): File {
        var dir: File? = File("").absoluteFile
        repeat(6) {
            val current = dir ?: return@repeat
            for (candidate in listOf(File(current, relative))) {
                if (candidate.isFile) return candidate
            }
            dir = current.parentFile
        }
        throw AssertionError("cannot locate $relative from ${File("").absolutePath}")
    }

    private fun string(relative: String, name: String): String {
        val match = Regex("<string name=\"$name\">([^<]*)</string>")
            .find(resource(relative).readText())
        return match?.groupValues?.get(1)
            ?: throw AssertionError("missing string $name in $relative")
    }

    @Test
    fun classifiesCancelMarkers() {
        assertEquals(OperationFailure.Cancelled, ImageCancelledException().toOperationFailure())
        assertEquals(OperationFailure.Cancelled, JavaCancellationException().toOperationFailure())
        assertEquals(OperationFailure.Cancelled, KotlinCancellationException().toOperationFailure())
    }

    @Test
    fun classifiesAttachmentMarkers() {
        assertEquals(OperationFailure.AttachmentChanged, AttachmentChangedException().toOperationFailure())
        assertEquals(OperationFailure.AttachmentChanged, EjectedException().toOperationFailure())
        assertEquals(
            OperationFailure.AttachmentChanged,
            EjectStaleCloseException(IOException("close")).toOperationFailure(),
        )
        assertEquals(
            OperationFailure.AttachmentChanged,
            DiskException.Disconnected().toOperationFailure(),
        )
    }

    @Test
    fun classifiesSizeMismatchMarkers() {
        assertEquals(
            OperationFailure.SizeMismatch,
            SizeMismatchException("got 1 expected 2").toOperationFailure(),
        )
        assertEquals(
            OperationFailure.SizeMismatch,
            ImageSizeMismatchException("size").toOperationFailure(),
        )
        assertEquals(OperationFailure.SizeMismatch, ImageEmptyException("empty").toOperationFailure())
        assertEquals(
            OperationFailure.SizeMismatch,
            VentoyImageTooLargeException("too big").toOperationFailure(),
        )
        assertEquals(
            OperationFailure.SizeMismatch,
            RawImageTooLargeException("too big").toOperationFailure(),
        )
        assertEquals(
            OperationFailure.SizeMismatch,
            ImageVerifyException("verify").toOperationFailure(),
        )
    }

    @Test
    fun classifiesSafIoMarkers() {
        assertEquals(OperationFailure.SafIo, FileNotFoundException("gone").toOperationFailure())
        assertEquals(OperationFailure.SafIo, EOFException("eof").toOperationFailure())
        assertEquals(OperationFailure.SafIo, IOException("disk").toOperationFailure())
    }

    @Test
    fun classifiesProviderRejectedMarkers() {
        assertEquals(
            OperationFailure.ProviderRejected,
            ProviderRejectedException("refused").toOperationFailure(),
        )
        assertEquals(OperationFailure.ProviderRejected, SecurityException("denied").toOperationFailure())
        assertEquals(
            OperationFailure.ProviderRejected,
            IllegalStateException("cannot open tree").toOperationFailure(),
        )
        assertEquals(
            OperationFailure.ProviderRejected,
            IllegalStateException("the provider refused create").toOperationFailure(),
        )
        assertEquals(
            OperationFailure.ProviderRejected,
            IllegalArgumentException("SAF provider atomic rename failed").toOperationFailure(),
        )
    }

    @Test
    fun walksWrappedCauseChain() {
        assertEquals(
            OperationFailure.Cancelled,
            RuntimeException(ImageCancelledException()).toOperationFailure(),
        )
        assertEquals(
            OperationFailure.SizeMismatch,
            IOException(SizeMismatchException("nested")).toOperationFailure(),
        )
        assertEquals(
            OperationFailure.ProviderRejected,
            IllegalStateException("other", SecurityException("denied")).toOperationFailure(),
        )
    }

    @Test
    fun unknownEngineFailuresStayNull() {
        assertNull(null.toOperationFailure())
        assertNull(RuntimeException("engine boom").toOperationFailure())
        assertNull(IllegalStateException("unrelated state").toOperationFailure())
        assertNull(IllegalArgumentException("bad arg").toOperationFailure())
        assertNull(Exception("top", IllegalStateException("mid")).toOperationFailure())
    }

    @Test
    fun failureTextResAreDistinctAndHaveNoFormatArgs() {
        val variants = listOf(
            OperationFailure.Cancelled,
            OperationFailure.AttachmentChanged,
            OperationFailure.SizeMismatch,
            OperationFailure.SafIo,
            OperationFailure.ProviderRejected,
        )
        val resIds = variants.map { OperationPresentation.failureTextRes(it) }
        assertEquals(variants.size, resIds.toSet().size)

        val names = listOf(
            "msg_failure_cancelled",
            "msg_failure_attachment_changed",
            "msg_failure_size_mismatch",
            "msg_failure_io",
            "msg_failure_provider_rejected",
        )
        for (name in names) {
            val english = string("app/src/main/res/values/strings.xml", name)
            val russian = string("app/src/main/res/values-ru/strings.xml", name)
            assertFalse("$name en has format arg: $english", "%1\$s" in english)
            assertFalse("$name ru has format arg: $russian", "%1\$s" in russian)
            assertTrue(english.isNotBlank())
            assertTrue(russian.isNotBlank())
        }
        assertNotEquals(
            OperationPresentation.failureTextRes(OperationFailure.Cancelled),
            OperationPresentation.failureTextRes(OperationFailure.SafIo),
        )
    }
}
