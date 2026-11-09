package usb.diagnostics

import java.io.File
import java.io.RandomAccessFile
import usb.device.UsbDisk
import usb.service.DiskOperation

object RescueBackupOperations {
    fun copy(
        disk: UsbDisk,
        image: File,
        mapFile: File,
        map: RescueMap,
        operation: DiskOperation,
    ) {
        RandomAccessFile(image, "rw").use { file ->
            Rescue.copy(disk, RescueSink { lba, sector ->
                file.seek((lba * disk.geometry.sectorSize.toULong()).toLong())
                file.write(sector)
            }, map, checkpoint = { snapshot ->
                file.fd.sync()
                RescueCheckpoint.save(snapshot, mapFile)
            }, operation = operation)
            file.fd.sync()
        }
        RescueCheckpoint.save(map, mapFile)
    }
}
