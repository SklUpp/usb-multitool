package usb.fs

enum class FilesystemId {
    FAT,
    EXFAT,
    NTFS,
    EXT,

    /** Read-only ISO9660/Joliet, suitable for images written by Rufus or raw sector copy. */
    ISO,
}
