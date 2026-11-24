package usb.ui

import java.io.EOFException
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException as JavaCancellationException
import kotlinx.coroutines.CancellationException as KotlinCancellationException
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

/**
 * Typed operation failure carried into UI state. Text is mapped once in
 * [OperationPresentation]; never render [Throwable.message]/simpleName to the user.
 */
sealed interface OperationFailure {
    data object Cancelled : OperationFailure
    data object AttachmentChanged : OperationFailure
    data object SizeMismatch : OperationFailure
    data object SafIo : OperationFailure
    data object ProviderRejected : OperationFailure
}

/**
 * Walks the cause chain and maps known markers. Unknown engine/disk/validation
 * failures return null so UI falls back to authored Failed/Partial keys.
 */
fun Throwable?.toOperationFailure(depth: Int = 0): OperationFailure? {
    if (this == null || depth > 8) return null
    return when (this) {
        is ImageCancelledException,
        is JavaCancellationException,
        is KotlinCancellationException,
        -> OperationFailure.Cancelled

        is AttachmentChangedException,
        is EjectedException,
        is EjectStaleCloseException,
        is DiskException.Disconnected,
        -> OperationFailure.AttachmentChanged

        is SizeMismatchException,
        is ImageSizeMismatchException,
        is ImageEmptyException,
        is VentoyImageTooLargeException,
        is RawImageTooLargeException,
        is ImageVerifyException,
        -> OperationFailure.SizeMismatch

        is ProviderRejectedException,
        is SecurityException,
        -> OperationFailure.ProviderRejected

        is FileNotFoundException,
        is EOFException,
        -> OperationFailure.SafIo

        is IOException -> {
            val nested = cause.toOperationFailure(depth + 1)
            nested ?: OperationFailure.SafIo
        }

        is IllegalStateException -> {
            val msg = message.orEmpty()
            when {
                msg.startsWith("cannot open ") ||
                    msg.startsWith("the provider refused") ||
                    msg.startsWith("SAF provider ") ||
                    msg.contains("provider atomic rename") ||
                    msg.contains("provider did not return the bytes")
                -> OperationFailure.ProviderRejected
                msg.contains("size mismatch") ||
                    msg.contains("reports empty destination") ||
                    msg.contains("source size changed")
                -> OperationFailure.SizeMismatch
                else -> cause.toOperationFailure(depth + 1)
            }
        }

        is IllegalArgumentException -> {
            val msg = message.orEmpty()
            when {
                msg.startsWith("SAF provider ") ||
                    msg.contains("provider did not return the bytes") ||
                    msg.contains("did not create a new")
                -> OperationFailure.ProviderRejected
                else -> cause.toOperationFailure(depth + 1)
            }
        }

        else -> cause.toOperationFailure(depth + 1)
    }
}
