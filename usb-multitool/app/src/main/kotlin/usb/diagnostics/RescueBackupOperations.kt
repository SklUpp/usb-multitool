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
            Rescue.copy(disk, object : RescueSink {
                override fun writeSector(lba: ULong, sector: ByteArray) = writeBatch(lba, sector, sector.size, disk.sectorSize)
                override fun writeBatch(lba: ULong, bytes: ByteArray, length: Int, sectorSize: Int) {
                    file.seek((lba * sectorSize.toULong()).toLong())
                    file.write(bytes, 0, length)
                }
            }, map, checkpoint = { snapshot ->
                file.fd.sync()
                RescueCheckpoint.save(snapshot, mapFile)
            }, operation = operation)
            file.fd.sync()
        }
        RescueCheckpoint.save(map, mapFile)
    }
}
