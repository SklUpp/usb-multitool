package usb.partition

object PartitionEditor {
    private const val MIB_BYTES = 1024L * 1024L
    private const val MBR_SLOTS = 4
    private const val DEFAULT_GPT_SLOTS = 128

    fun create(
        table: PartitionTable,
        size: ULong,
        type: PartitionType,
        startLba: ULong? = null,
    ): PartitionTable {
        require(size > 0UL) { "partition size must be positive" }
        val align = alignmentSectors(table.sectorSize)
        val region = table.freeRegions.firstOrNull { candidate ->
            if (startLba == null) alignedCapacity(candidate, align) >= size
            else startLba >= candidate.startLba && startLba - candidate.startLba < candidate.sectorCount &&
                size <= candidate.sectorCount - (startLba - candidate.startLba)
        } ?: throw NoFreeSpaceException("no free region large enough for $size sectors")
        val alignedStart = startLba ?: alignUp(region.startLba, align)
        require(alignedStart % align == 0UL) { "partition start must be aligned to $align sectors" }
        val skip = alignedStart - region.startLba
        val available = if (skip < region.sectorCount) region.sectorCount - skip else 0UL
        require(available >= size) {
            "aligned free region too small: need $size have $available"
        }
        val created = Partition(
            startLba = alignedStart,
            sectorCount = size,
            type = type.name,
            name = type.name,
            slot = allocateSlot(table),
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
            if (i != index) {
                p
            } else {
                val nextBoot = bootable ?: p.bootable
                val nextFlags = if (bootable == null) {
                    p.flags
                } else if (table.scheme == TableScheme.GPT) {
                    // GPT carries the boot flag in the attribute word; keep other bits.
                    if (nextBoot) p.flags or GPT_LEGACY_BOOTABLE_FLAG
                    else p.flags and GPT_LEGACY_BOOTABLE_FLAG.inv()
                } else {
                    if (nextBoot) 1L else 0L
                }
                p.copy(
                    bootable = nextBoot,
                    esp = esp ?: p.esp,
                    flags = nextFlags,
                )
            }
        }
        return table.copy(partitions = partitions)
    }

    /** First unused entry slot; existing records keep their original slot. */
    private fun allocateSlot(table: PartitionTable): Int {
        val slots = when (table.scheme) {
            TableScheme.MBR -> MBR_SLOTS
            TableScheme.GPT -> table.gptHeader?.entryCount ?: DEFAULT_GPT_SLOTS
            TableScheme.HYBRID -> throw HybridAmbiguityException(
                "hybrid MBR/GPT layout cannot be edited safely; repair it explicitly",
            )
        }
        val used = table.partitions.map { it.slot }.filter { it >= 0 }.toHashSet()
        return (0 until slots).firstOrNull { it !in used }
            ?: throw NoFreeSpaceException("no free partition slot")
    }

    private fun alignmentSectors(sectorSize: Int): ULong {
        val bytes = MIB_BYTES
        require(bytes % sectorSize == 0L) { "sector size does not divide 1 MiB" }
        return (bytes / sectorSize).toULong()
    }

    private fun alignUp(value: ULong, align: ULong): ULong {
        if (align <= 1UL) return value
        val rem = value % align
        if (rem == 0UL) return value
        val gap = align - rem
        // ponytail: a start within one alignment of ULong.MAX cannot be rounded up; callers use disk LBAs.
        return if (value > ULong.MAX_VALUE - gap) value else value + gap
    }

    fun alignedCapacity(region: FreeRegion, align: ULong): ULong {
        val alignedStart = alignUp(region.startLba, align)
        val skip = alignedStart - region.startLba
        return if (skip < region.sectorCount) region.sectorCount - skip else 0UL
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

