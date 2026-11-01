package usb.partition

object PartitionEditor {
    private const val MIB_BYTES = 1024L * 1024L

    fun create(
        table: PartitionTable,
        size: ULong,
        type: PartitionType,
    ): PartitionTable {
        require(size > 0UL) { "partition size must be positive" }
        val align = alignmentSectors(table.sectorSize)
        val region = table.freeRegions.firstOrNull { it.sectorCount >= size }
            ?: throw NoFreeSpaceException("no free region large enough for $size sectors")
        val alignedStart = alignUp(region.startLba, align)
        val available = region.sectorCount - (alignedStart - region.startLba)
        require(available >= size) {
            "aligned free region too small: need $size have $available"
        }
        val created = Partition(
            startLba = alignedStart,
            sectorCount = size,
            type = type.name,
            name = type.name,
        )
        val partitions = table.partitions + created
        return table.copy(
            partitions = partitions,
            freeRegions = recomputeFree(partitions, table, align),
        )
    }

    fun delete(table: PartitionTable, index: Int): PartitionTable {
        require(index in table.partitions.indices) { "partition index out of range" }
        val partitions = table.partitions.toMutableList().also { it.removeAt(index) }
        val align = alignmentSectors(table.sectorSize)
        return table.copy(
            partitions = partitions,
            freeRegions = recomputeFree(partitions, table, align),
        )
    }

    fun setFlags(
        table: PartitionTable,
        index: Int,
        bootable: Boolean? = null,
        esp: Boolean? = null,
    ): PartitionTable {
        require(index in table.partitions.indices) { "partition index out of range" }
        val partitions = table.partitions.mapIndexed { i, p ->
            if (i != index) p
            else p.copy(
                bootable = bootable ?: p.bootable,
                esp = esp ?: p.esp,
            )
        }
        return table.copy(partitions = partitions)
    }

    fun repairGptTail(table: PartitionTable): PartitionTable {
        if (table.scheme == TableScheme.HYBRID || table.hybridAmbiguous) {
            throw HybridAmbiguityException("refusing GPT tail repair on hybrid/ambiguous layout")
        }
        require(table.scheme == TableScheme.GPT) { "repairGptTail requires GPT" }
        // Backup header + entries relocate to current disk end; CRC recomputed by writer.
        return table.copy(
            // Marker field: free trailing sectors reserved for backup structures stay free.
            freeRegions = PartitionTable.recomputeFree(
                table.partitions,
                table.diskSectorCount,
                alignmentSectors(table.sectorSize),
            ),
        )
    }

    private fun alignmentSectors(sectorSize: Int): ULong {
        val bytes = MIB_BYTES
        require(bytes % sectorSize == 0L) { "sector size does not divide 1 MiB" }
        return (bytes / sectorSize).toULong()
    }

    private fun alignUp(value: ULong, align: ULong): ULong {
        if (align == 0UL) return value
        val rem = value % align
        return if (rem == 0UL) value else value + (align - rem)
    }

    private fun recomputeFree(
        partitions: List<Partition>,
        table: PartitionTable,
        align: ULong,
    ): List<FreeRegion> {
        // GPT owns metadata outside the usable range (backup header + entries at the
        // disk end, primary entries at the front); offering it as free space makes
        // CREATE_PARTITION produce entries commitGpt must reject.
        val header = table.gptHeader
        return PartitionTable.recomputeFree(
            partitions,
            table.diskSectorCount,
            align,
            firstUsable = header?.firstUsable ?: align,
            lastUsableExclusive = header?.let { it.lastUsable + 1UL } ?: table.diskSectorCount,
        )
    }
}

