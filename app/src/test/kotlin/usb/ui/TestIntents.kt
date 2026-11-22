package usb.ui

/** Build the exact pinned intent the Files dialog now sends. */
internal fun createFolderIntent(state: AppUiState, name: String): UiIntent.CreateFolder {
    val device = requireNotNull(state.device) { "folder intent needs a device" }
    val partition = requireNotNull(state.selectedPartition()) { "folder intent needs a partition" }
    return UiIntent.CreateFolder(
        name = name,
        path = state.currentPath,
        binding = PartitionBinding(
            device = device,
            partitionId = partition.id,
            startLba = partition.startLba,
            sectorCount = partition.sectorCount,
        ),
    )
}
