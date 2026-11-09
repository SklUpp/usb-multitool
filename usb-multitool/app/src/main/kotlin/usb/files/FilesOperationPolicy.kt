package usb.files

/** Boundary checks shared by file mutations; filesystem engines remain responsible for I/O. */
object FilesOperationPolicy {
    fun requireChildName(rawName: String, source: String): String {
        val name = rawName.substringAfterLast('/').trim()
        require(name.isNotEmpty() && name != "." && name != "..") {
            "invalid file name \"$rawName\" for $source"
        }
        return name
    }

    fun duplicateMessage(target: String): String =
        "$target already exists: delete it first (delete asks for confirmation)"

    fun deleteError(name: String, target: String, message: String): String? =
        if (message.contains("Directory not empty")) {
            "Cannot delete \"$name\": directory is not empty — remove its contents first"
        } else null
}
