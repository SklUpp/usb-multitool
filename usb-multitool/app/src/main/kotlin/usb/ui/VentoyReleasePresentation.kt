package usb.ui

/**
 * Pure version facts for the Ventoy header. The downloaded release is what Install/Update
 * will use, so it shadows the bundled release for the target side of the comparison while
 * the installed side stays the real version read from the stick.
 */
data class VentoyReleaseComparison(
    val installed: String?,
    val target: String?,
    /** Null when either side is absent or not dotted-numeric; 0 means identical. */
    val order: Int?,
) {
    val same: Boolean get() = order == 0
}

fun ventoyTargetRelease(bundled: String?, downloaded: String?): String? =
    downloaded?.trim()?.takeIf { it.isNotEmpty() }
        ?: bundled?.trim()?.takeIf { it.isNotEmpty() }

fun ventoyReleaseComparison(installed: String?, target: String?): VentoyReleaseComparison {
    val left = installed?.trim()?.takeIf { it.isNotEmpty() }
    val right = target?.trim()?.takeIf { it.isNotEmpty() }
    if (left == null || right == null) {
        return VentoyReleaseComparison(left, right, order = null)
    }
    if (left == right) {
        return VentoyReleaseComparison(left, right, order = 0)
    }
    val leftParts = dotNumbers(left)
    val rightParts = dotNumbers(right)
    if (leftParts == null || rightParts == null) {
        return VentoyReleaseComparison(left, right, order = null)
    }
    val size = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until size) {
        val a = leftParts.getOrElse(index) { 0L }
        val b = rightParts.getOrElse(index) { 0L }
        if (a != b) return VentoyReleaseComparison(left, right, order = a.compareTo(b))
    }
    return VentoyReleaseComparison(left, right, order = 0)
}

private fun dotNumbers(value: String): List<Long>? {
    val parts = value.split('.')
    if (parts.isEmpty() || parts.any { it.isEmpty() || it.length > 9 || it.any { c -> c !in '0'..'9' } }) {
        return null
    }
    return parts.map { it.toLong() }
}
