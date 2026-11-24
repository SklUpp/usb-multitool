package usb.ui

import usb.ventoy.Autounattend
import usb.ventoy.AutounattendParams
import usb.ventoy.AutounattendProblem
import usb.ventoy.AutounattendState
import usb.ventoy.XmlFieldIntent
import usb.ventoy.DiskLayout
import usb.ventoy.VentoyControlCatalog
import usb.ventoy.VentoyControlSetting
import usb.ventoy.XmlPath
import usb.ventoy.normalizeVentoyFilePath
import usb.ventoy.autounattendPlanSha256
import usb.ventoy.diskLayoutInstallTargetPartitionId
import usb.ventoy.diskLayoutRemainingMb
import usb.ventoy.diskLayoutTotalMb

/**
 * Feature reducer extracted from [UiReducer]; behavior preserved verbatim.
 */
internal object VentoyReducer {
    fun reduce(state: AppUiState, intent: UiIntent): ReduceResult? = when (intent) {
        is UiIntent.SetVtoyBypassChecks ->
            VentoyTransitions.setVtoyBypassChecks(state, intent)
        is UiIntent.SetVtoyLocalAccount ->
            VentoyTransitions.setVtoyLocalAccount(state, intent)

        UiIntent.DismissLinkOverwrite ->
            ReduceResult(
                state.copy(
                    linkOverwriteCandidate = null,
                    linkOverwriteCandidateIdentity = null,
                    linkOverwriteCandidateGeneration = null,
                    linkOverwriteCandidatePaths = emptyList(),
                    linkOverwriteCandidateTemplatePath = null,
                    // Carried forward, not a new emission: no sequence bump.
                    statusMessage = state.statusMessage.takeUnless {
                        it == UiMessage.Key(UiMessageKey.LinkOverwriteNeeded)
                    },
                ),
            )
        is UiIntent.RequestLinkOverwrite -> {
            val device = state.device
            val candidateMatches =
                state.linkOverwriteCandidate == intent.fileName &&
                    state.linkOverwriteCandidateIdentity == device?.identity &&
                    state.linkOverwriteCandidateGeneration == device?.attachGeneration
            if (!state.actionsEnabled || device == null) {
                ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.NoDeviceForLink)))
            } else if (!candidateMatches) {
                ReduceResult(state.copy(
                    linkOverwriteCandidate = null,
                    linkOverwriteCandidateIdentity = null,
                    linkOverwriteCandidateGeneration = null,
                    linkOverwriteCandidatePaths = emptyList(),
                    linkOverwriteCandidateTemplatePath = null,
                ).withStatus(UiMessage.Key(UiMessageKey.LinkMismatch)))
            } else {
                val paths =
                    intent.imagePaths.takeIf { it.isNotEmpty() }
                        ?: state.linkOverwriteCandidatePaths.takeIf { it.isNotEmpty() }
                        ?: listOf(intent.fileName)
                ReduceResult(state.copy(
                    pendingConfirmation = DestructiveConfirmation.request(
                        device,
                        DestructiveAction.VENTOY_LINK_XML,
                        isoName = intent.fileName,
                        linkOverwrite = true,
                        imagePaths = paths,
                        templatePath = intent.templatePath ?: state.linkOverwriteCandidateTemplatePath,
                    ),
                    linkOverwriteCandidate = null,
                    linkOverwriteCandidateIdentity = null,
                    linkOverwriteCandidateGeneration = null,
                    linkOverwriteCandidatePaths = emptyList(),
                    linkOverwriteCandidateTemplatePath = null,
                ).withStatus(null))
            }
        }
        is UiIntent.LinkOverwriteAvailable -> {
            val device = state.device
            if (!state.actionsEnabled || device == null) {
                ReduceResult(state.copy(
                    linkOverwriteCandidate = null,
                    linkOverwriteCandidateIdentity = null,
                    linkOverwriteCandidateGeneration = null,
                    linkOverwriteCandidatePaths = emptyList(),
                    linkOverwriteCandidateTemplatePath = null,
                ))
            } else {
                ReduceResult(state.copy(
                    linkOverwriteCandidate = intent.fileName,
                    linkOverwriteCandidateIdentity = device.identity,
                    linkOverwriteCandidateGeneration = device.attachGeneration,
                    linkOverwriteCandidatePaths = intent.imagePaths,
                    linkOverwriteCandidateTemplatePath = intent.templatePath,
                ).withStatus(UiMessage.Key(UiMessageKey.LinkOverwriteNeeded)))
            }
        }

        UiIntent.LoadWindowsIsoCandidates -> {
            val device = state.device
            when {
                !state.actionsEnabled ->
                    ReduceResult(
                        state.withStatus(if (state.isRunning ||
                                    state.operation?.state == OperationState.Queued ||
                                    state.pendingConfirmation != null
                                ) {
                                    UiMessage.Key(UiMessageKey.WaitForOperation)
                                } else {
                                    UiReducer.presenceMessage(state.presence)
                                }),
                    )
                device == null ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                else -> {
                    val generation = state.windowsIsoCandidatesGeneration + 1
                    ReduceResult(
                        state.copy(
                            windowsIsoCandidates = null,
                            windowsIsoCandidatesGeneration = generation,
                            windowsIsoCandidatesIdentity = device.identity,
                            windowsIsoCandidatesError = null,
                        ),
                        effects = listOf(
                            UiEffect.LoadWindowsIsoCandidates(
                                requestGeneration = generation,
                            ),
                        ),
                    )
                }
            }
        }

        is UiIntent.WindowsIsoCandidatesLoaded -> {
            val device = state.device
            if (device == null ||
                intent.generation != state.windowsIsoCandidatesGeneration ||
                intent.identity != device.identity
            ) {
                ReduceResult(state)
            } else {
                val files =
                    intent.candidates
                        .asSequence()
                        .filter { it.path.startsWith("/") && it.path != "/" && it.name.isNotBlank() }
                        .distinctBy { it.path.lowercase() }
                        .sortedBy { it.path.lowercase() }
                        .toList()
                // Only fires while Ventoy tab open (or manual refresh).
                ReduceResult(
                    state.copy(
                        windowsIsoCandidates = files,
                        windowsIsoCandidatesError = intent.error,
                        // Only a new error bumps; null keeps the carried message and sequence.
                        statusMessage = intent.error?.let(UiMessage::Raw) ?: state.statusMessage,
                        statusSequence =
                            if (intent.error != null) state.statusSequence + 1 else state.statusSequence,
                    ),
                )
            }
        }

        is UiIntent.SelectWindowsIso -> {
            val device = state.device
            val selected =
                state.windowsIsoCandidates?.firstOrNull {
                    it.path.equals(intent.isoName, ignoreCase = true) ||
                        it.name.equals(intent.isoName, ignoreCase = true)
                }
            when {
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                device == null ->
                    ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.NoDeviceForLink)))
                selected == null ->
                    ReduceResult(
                        state.withStatus(UiMessage.Raw(
                                "\"${intent.isoName}\" is not on the Ventoy data partition; reload the list or import it",
                            )),
                    )
                else -> {
                    ReduceResult(
                        state.copy(
                            pendingConfirmation =
                                DestructiveConfirmation.request(
                                    device = device,
                                    action = DestructiveAction.VENTOY_LINK_XML,
                                    isoName = selected.path,
                                    linkOverwrite = false,
                                ),
                        ).withStatus(null),
                    )
                }
            }
        }

        is UiIntent.RequestImportWindowsIso -> {
            val uri = intent.uri.trim()
            val pick = state.filesPickMode
            val pickProblem =
                if (pick == null) {
                    // Images exist only inside the simplified picker. A late/duplicate
                    // result after its closure must never fall back to the default
                    // /ISO folder; XML templates keep their independent flow.
                    if (intent.kind == WindowsImportKind.Image) {
                        UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)
                    } else {
                        null
                    }
                } else {
                    val directory = safePickPath(intent.directory ?: state.currentPath)
                    when {
                        pick.purpose != FilesPickPurpose.WindowsImage ||
                            intent.kind != WindowsImportKind.Image ->
                            UiMessage.Key(UiMessageKey.FilesPickActive)
                        intent.pickSessionId != pick.sessionId ->
                            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)
                        !pick.matches(state) -> UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)
                        directory == null || directory != safePickPath(state.currentPath) ->
                            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)
                        else -> null
                    }
                }
            val staleContext =
                intent.expectedAttachGeneration != null &&
                    (
                        state.device?.identity != intent.expectedIdentity ||
                            state.device?.attachGeneration !=
                            intent.expectedAttachGeneration
                    )
            val importDirectory = pick?.let { state.currentPath } ?: intent.directory
            if (staleContext || pickProblem != null) {
                ReduceResult(
                    state.withStatus(pickProblem
                                ?: UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                )
            } else when {
                !state.actionsEnabled ||
                    state.pendingIsoImport != null ||
                    state.isoImportSafInFlight != null ||
                    state.isoImportUsbInFlight != null ->
                    ReduceResult(
                        state.withStatus(if (state.isoImportSafInFlight != null ||
                                    state.isoImportUsbInFlight != null ||
                                    state.isRunning ||
                                    state.operation?.state == OperationState.Queued ||
                                    state.pendingConfirmation != null
                                ) {
                                    UiMessage.Key(UiMessageKey.WaitForOperation)
                                } else {
                                    UiReducer.presenceMessage(state.presence)
                                }),
                    )
                state.device == null ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                uri.isEmpty() ->
                    ReduceResult(state.withStatus(UiMessage.Raw("No file selected for ISO import")))
                else -> {
                    val generation = state.isoImportGeneration + 1
                    ReduceResult(
                        state.copy(
                            pendingIsoImport = null,
                            deferredIsoImport = null,
                            isoImportError = null,
                            isoImportSafInFlight = generation,
                            isoImportGeneration = generation,
                        ),
                        effects = listOf(
                            UiEffect.InspectIsoImport(
                                uri = uri,
                                requestGeneration = generation,
                                importKind = intent.kind,
                                importDirectory = importDirectory,
                                pickSessionId = pick?.sessionId,
                            ),
                        ),
                    )
                }
            }
        }

        is UiIntent.WindowsIsoImportRejected ->
            if (intent.generation != state.isoImportGeneration) {
                // A cancelled/replaced inspection must not overwrite the live result nor
                // release the in-flight guard of the newer one.
                ReduceResult(state)
            } else {
                ReduceResult(
                    state.copy(
                        pendingIsoImport = null,
                        isoImportError = intent.problem,
                    ),
                )
            }

        is UiIntent.WindowsIsoImportSafFinished ->
            if (state.isoImportSafInFlight != intent.generation) {
                ReduceResult(state)
            } else {
                // The blocking read ended; a stuck provider no longer blocks new imports.
                ReduceResult(state.copy(isoImportSafInFlight = null))
            }

        is UiIntent.WindowsIsoImportUsbStarted -> {
            val started = state.isoImportSafInFlight == intent.generation
            if (!started || intent.generation != state.isoImportGeneration) {
                ReduceResult(state)
            } else {
                ReduceResult(
                    state.copy(
                        isoImportSafInFlight = null,
                        isoImportUsbInFlight = intent.generation,
                    ),
                )
            }
        }

        is UiIntent.WindowsIsoImportFinished -> {
            val usbDone = state.isoImportUsbInFlight == intent.generation
            val safDone = state.isoImportSafInFlight == intent.generation
            if (!usbDone && !safDone) {
                ReduceResult(state)
            } else {
                // Disk is closed now; the deferred folder read may be released by the
                // wrapper in this same reduce.
                ReduceResult(
                    state.copy(
                        isoImportUsbInFlight = if (usbDone) null else state.isoImportUsbInFlight,
                        isoImportSafInFlight = if (safDone) null else state.isoImportSafInFlight,
                    ),
                )
            }
        }

        is UiIntent.WindowsIsoImportInspected -> {
            val device = state.device
            val pick = state.filesPickMode
            val pickMismatch =
                if (intent.pickSessionId == null) {
                    // A non-picker inspection must not land inside a live picker.
                    pick != null
                } else {
                    // A stale UI may carry an inspect probe taken after a real disk
                    // change. The probe must fully agree with the original pick pin;
                    // otherwise refuse before any proposal or write.
                    val geometryMismatch =
                        intent.partitionStartLba != pick?.startLba ||
                            intent.partitionSectorCount != pick?.sectorCount
                    pick == null ||
                        pick.sessionId != intent.pickSessionId ||
                        pick.purpose != FilesPickPurpose.WindowsImage ||
                        !pick.matches(state) ||
                        geometryMismatch ||
                        safePickPath(intent.directory ?: state.currentPath) !=
                        safePickPath(state.currentPath)
                }
            if (device == null || intent.generation != state.isoImportGeneration) {
                // Stale result for a cancelled/replaced inspection: keep the live guard.
                ReduceResult(state)
            } else if (pickMismatch) {
                // The pinned folder or attachment moved while the SAF read was in flight.
                // Drop the result and disown its generation; nothing is written.
                ReduceResult(
                    state.copy(
                        pendingIsoImport = null,
                        deferredIsoImport = null,
                        isoImportSafInFlight = null,
                        isoImportGeneration = state.isoImportGeneration + 1,
                    ).withStatus(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                    effects =
                        if (state.isoImportSafInFlight != null ||
                            state.isoImportUsbInFlight != null
                        ) {
                            listOf(UiEffect.CancelIsoImportInspection)
                        } else {
                            emptyList()
                        },
                )
            } else {
                val proposal =
                    IsoImportProposal(
                        uri = intent.uri,
                        name = intent.name,
                        targetName = intent.targetName,
                        sizeBytes = intent.sizeBytes,
                        exists = intent.exists,
                        identity = device.identity,
                        attachGeneration = device.attachGeneration,
                        requestGeneration = intent.generation,
                        kind = intent.kind,
                        directory =
                            intent.directory
                                ?: if (intent.kind == WindowsImportKind.XmlTemplate) {
                                    "/ventoy"
                                } else {
                                    "/ISO"
                                },
                        templateMarkers = intent.templateMarkers,
                        // Pick-owned imports keep the original pick pin: the probe
                        // above already proved agreement, and the host fences the
                        // copy on the same values. Folder-dialog flows have no
                        // pick, so the inspect-time probe is their pin.
                        partitionStartLba = pick?.startLba ?: intent.partitionStartLba,
                        partitionSectorCount = pick?.sectorCount ?: intent.partitionSectorCount,
                        pickSessionId = intent.pickSessionId,
                    )
                if (intent.exists) {
                    // No write until the user explicitly accepts replacement. Kept even
                    // during a same-stick probe: the dialog itself waits for actionsEnabled.
                    ReduceResult(
                        state.copy(
                            pendingIsoImport = proposal,
                            deferredIsoImport = null,
                            isoImportError = null,
                        ).withStatus(UiMessage.Raw(
                                "${importTarget(proposal.directory, intent.targetName)} already exists; confirm overwrite to replace it",
                            )),
                    )
                } else if (!state.actionsEnabled) {
                    // A same-stick probe turned the UI non-actionable after the SAF read
                    // started. Hold the result; the wrapper starts the import on the first
                    // quiet actionable intent instead of losing the user's pick.
                    ReduceResult(
                        state.copy(
                            pendingIsoImport = null,
                            deferredIsoImport = proposal,
                            isoImportError = null,
                        ).withStatus(UiMessage.Raw(
                                "Import of ${intent.targetName} waits for the stick to settle",
                            )),
                    )
                } else {
                    ReduceResult(
                        state.copy(
                            pendingIsoImport = null,
                            deferredIsoImport = null,
                        ).withStatus(UiMessage.Raw(
                                "Importing ${intent.targetName} into ${proposal.directory}",
                            )),
                        effects = listOf(
                            UiEffect.ImportIso(
                                isoImport = proposal,
                                overwrite = false,
                                requestGeneration = intent.generation,
                            ),
                        ),
                    )
                }
            }
        }

        is UiIntent.ConfirmWindowsIsoImport -> {
            val proposal = state.pendingIsoImport
            val device = state.device
            val pick = state.filesPickMode
            val proposalSession = proposal?.pickSessionId
            val pickMoved =
                proposal != null &&
                    when {
                        proposalSession == null -> pick != null
                        pick == null -> true
                        pick.sessionId != proposalSession -> true
                        pick.purpose != FilesPickPurpose.WindowsImage -> true
                        !pick.matches(state) -> true
                        else ->
                            safePickPath(proposal.directory) !=
                                safePickPath(state.currentPath)
                    }
            when {
                proposal == null || device == null ->
                    ReduceResult(state.copy(pendingIsoImport = null))
                pickMoved ->
                    ReduceResult(
                        state.copy(
                            pendingIsoImport = null,
                        ).withStatus(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                    )
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                proposal.identity != device.identity ||
                    proposal.attachGeneration != device.attachGeneration ||
                    proposal.requestGeneration != state.isoImportGeneration ->
                    ReduceResult(
                        state.copy(
                            pendingIsoImport = null,
                        ).withStatus(UiMessage.Raw("USB attachment changed; re-select the ISO")),
                    )
                proposal.exists && !intent.overwrite ->
                    ReduceResult(
                        state.copy(
                            pendingIsoImport = null,
                        ).withStatus(UiMessage.Raw(
                                "Overwrite of ${importTarget(proposal.directory, proposal.targetName)} was not confirmed",
                            )),
                    )
                else -> {
                    val overwrite = proposal.exists && intent.overwrite
                    ReduceResult(
                        state.copy(
                            pendingIsoImport = null,
                        ).withStatus(UiMessage.Raw(
                                "Importing ${proposal.targetName} into ${proposal.directory}",
                            )),
                        effects = listOf(
                            UiEffect.ImportIso(
                                isoImport = proposal,
                                overwrite = overwrite,
                                requestGeneration = proposal.requestGeneration,
                            ),
                        ),
                    )
                }
            }
        }

        UiIntent.CancelWindowsIsoImport ->
            // Cancel invalidates the pending result/dialog and disowns the SAF read, so a
            // stuck provider cannot block a new import. The USB guard stays until the host
            // reports the closed disk (WindowsIsoImportFinished).
            ReduceResult(
                state.copy(
                    pendingIsoImport = null,
                    deferredIsoImport = null,
                    isoImportError = null,
                    isoImportSafInFlight = null,
                    isoImportGeneration = state.isoImportGeneration + 1,
                ),
            )

        is UiIntent.ReopenImportFolderPick -> {
            val device = state.device
            // Detach/permission probe means no attachment owns the failure; a running
            // operation still pins the same attachment and only defers the re-read.
            val attachmentStillShown =
                device != null &&
                    device.identity == intent.identity &&
                    device.attachGeneration == intent.attachGeneration &&
                    intent.requestGeneration == state.isoImportGeneration &&
                    (
                        state.presence == DevicePresence.Ready ||
                            (state.presence == DevicePresence.Busy && state.isRunning)
                    )
            if (!attachmentStillShown) {
                // Another attachment now owns the screen; a stale failure must not
                // reopen the picker over it.
                ReduceResult(state)
            } else {
                // The cached folder list is proven stale: drop it, restart from the root
                // (the old folder itself may be the one that vanished) and invalidate
                // every in-flight answer under a new token. A busy/confirming UI only
                // defers the re-read and the picker demand; neither may start USB work
                // or open a dialog over a destructive confirmation.
                val token = state.ventoyFoldersRequest + 1L
                val path = "/"
                val loadNow = state.actionsEnabled
                if (state.filesPickMode != null) {
                    // The picker's own folder vanished: restart the Files listing from
                    // root and keep the pin. No Ventoy folder cache is involved.
                    ReduceResult(
                        state.copy(
                            currentPath = path,
                            filesListGeneration = state.filesListGeneration + 1L,
                            filesListingState =
                                if (loadNow) FilesListingState.Loading else FilesListingState.Stale,
                            pendingImportFolderPick = if (loadNow) intent.kind else null,
                            deferredImportFolderPick = if (loadNow) null else intent.kind,
                            pendingImportFolderPickLoad = !loadNow,
                        ),
                        effects =
                            if (loadNow) {
                                listOf(UiEffect.LoadFiles(path = path))
                            } else {
                                emptyList()
                            },
                    )
                } else {
                    ReduceResult(
                        state.copy(
                            ventoyFolderPath = path,
                            ventoyFolders = null,
                            ventoyFoldersError = null,
                            ventoyFoldersLoading = loadNow,
                            ventoyFoldersPending = false,
                            ventoyFoldersRequest = token,
                            pendingImportFolderPick = if (loadNow) intent.kind else null,
                            deferredImportFolderPick = if (loadNow) null else intent.kind,
                            pendingImportFolderPickLoad = !loadNow,
                        ),
                        effects =
                            if (loadNow) {
                                listOf(
                                    UiEffect.LoadVentoyFolders(
                                        requestToken = token,
                                        path = path,
                                    ),
                                )
                            } else {
                                emptyList()
                            },
                    )
                }
            }
        }

        UiIntent.ImportFolderPickOpened ->
            // Consume only the UI demand: a deferred folder read keeps its own flag and
            // kind, so a terminal/confirmation release still fires afterwards.
            ReduceResult(state.copy(pendingImportFolderPick = null))

        UiIntent.SaveWindowsControls ->
            if (!state.actionsEnabled) {
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            } else {
                ReduceResult(
                    state,
                    effects = listOf(UiEffect.SaveWindowsControls),
                )
            }

        is UiIntent.ToggleWindowsImage -> {
            if (!state.actionsEnabled) {
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            } else {
                // Same source the bindings UI renders; windowsIsoCandidates is template picker only.
                val image =
                    state.autounattendCandidates?.images?.firstOrNull {
                        it.imagePath == intent.path
                    }
                when {
                    image == null ->
                        ReduceResult(
                            state.withStatus(UiMessage.Raw(
                                    "Selected image is no longer listed; reload the list",
                                )),
                        )
                    // Bound rows use Unbind; problem rows stay untoggleable.
                    image.bound || image.problem != null ->
                        ReduceResult(state)
                    else -> {
                        val already = state.windowsSelectedImages.any { it == image.imagePath }
                        val selected =
                            if (already) {
                                state.windowsSelectedImages.filterNot { it == image.imagePath }
                            } else {
                                state.windowsSelectedImages + image.imagePath
                            }
                        ReduceResult(state.copy(windowsSelectedImages = selected))
                    }
                }
            }
        }

        UiIntent.ClearWindowsSelection ->
            ReduceResult(state.copy(windowsSelectedImages = emptyList()))

        is UiIntent.SetWindowsLinkTarget ->
            ReduceResult(
                state.copy(
                    windowsLinkTargetImage = intent.image
                        ?.trim()
                        ?.takeIf { it.isNotBlank() },
                ),
            )

        UiIntent.SelectWindowsTemplate -> {
            // Atomic consume: the only accepted provenance is the reducer-held confirmed
            // pick. Intent fields cannot inject a path because there are none.
            val picked = state.filesPickSelections[FilesPickPurpose.WindowsTemplate]
            val normalized =
                try {
                    picked?.takeIf { !it.isDirectory }?.let { normalizeVentoyFilePath(it.path) }
                } catch (_: IllegalArgumentException) {
                    null
                }
            val device = state.device
            val livePartition =
                picked?.let { selection ->
                    state.filesVolumes.firstOrNull {
                        it.id == selection.partitionId &&
                            it.startLba == selection.startLba &&
                            it.sectorCount == selection.sectorCount
                    }
                }
            val stalePin =
                picked == null ||
                    picked.purpose != FilesPickPurpose.WindowsTemplate ||
                    picked.isDirectory ||
                    normalized == null ||
                    device == null ||
                    picked.identity != device.identity ||
                    picked.attachGeneration != device.attachGeneration ||
                    livePartition == null ||
                    livePartition.id != "1" ||
                    picked.partitionId != "1"
            if (!state.actionsEnabled) {
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            } else if (stalePin || normalized == null) {
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                )
            } else {
                // Single-use token: clear the consumed entry in the same transition so a
                // replayed intent cannot re-select without a fresh explicit confirm.
                // Content is validated by the XML read (Autounattend.read/inspect); no
                // extension filter is applied.
                val generation = state.autounattendRequestGeneration + 1
                ReduceResult(
                    state.copy(
                        windowsTemplatePath = normalized,
                        windowsTemplatePin = picked,
                        autounattendState = AutounattendState.Missing,
                        autounattendBindings = null,
                        autounattendCandidates = null,
                        autounattendRequestGeneration = generation,
                        autounattendLoadInFlight = true,
                        diskLayoutConfirmation = null,
                        pendingAutounattendGeneration = null,
                        filesPickSelections =
                            state.filesPickSelections - FilesPickPurpose.WindowsTemplate,
                    ),
                    effects = listOf(
                        UiEffect.LoadAutounattend(
                            xmlName = normalized,
                            requestGeneration = generation,
                        ),
                        UiEffect.LoadAutounattendCandidates(
                            xmlName = normalized,
                            requestGeneration = generation,
                        ),
                    ),
                )
            }
        }

        UiIntent.ClearWindowsTemplate ->
            ReduceResult(
                state.copy(
                    windowsTemplatePath = null,
                    windowsTemplatePin = null,
                    autounattendState = AutounattendState.Missing,
                    autounattendBindings = null,
                    autounattendCandidates = null,
                    autounattendRequestGeneration = state.autounattendRequestGeneration + 1,
                    autounattendLoadInFlight = false,
                    diskLayoutConfirmation = null,
                    pendingAutounattendGeneration = null,
                ),
            )

        is UiIntent.WindowsTemplateImported -> {
            // Phone copy (or generated file) landed via host-verified write. Pin is still
            // revalidated: stale attachment or partition-1 geometry refuses, and write
            // errors stay on OperationUpdated — this branch never masks them.
            val normalized =
                try {
                    normalizeVentoyFilePath(intent.path)
                } catch (_: IllegalArgumentException) {
                    null
                }
            val device = state.device
            val livePartition1 =
                state.partitions.firstOrNull { it.id == "1" }
            val stalePin =
                normalized == null ||
                    device == null ||
                    intent.identity != device.identity ||
                    intent.attachGeneration != device.attachGeneration ||
                    livePartition1 == null ||
                    intent.partitionStartLba != livePartition1.startLba ||
                    intent.partitionSectorCount != livePartition1.sectorCount
            if (!state.actionsEnabled) {
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            } else if (stalePin || normalized == null) {
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                )
            } else {
                val generation = state.autounattendRequestGeneration + 1
                // Provenance for every template: retention revalidates this pin against
                // live attachment + part-1 geometry on every DeviceChanged.
                val pin = FilesPickSelection(
                    purpose = FilesPickPurpose.WindowsTemplate,
                    path = normalized,
                    name = normalized.substringAfterLast('/'),
                    sizeBytes = 0uL,
                    isDirectory = false,
                    identity = intent.identity,
                    attachGeneration = intent.attachGeneration,
                    partitionId = "1",
                    startLba = intent.partitionStartLba,
                    sectorCount = intent.partitionSectorCount,
                )
                ReduceResult(
                    state.copy(
                        windowsTemplatePath = normalized,
                        windowsTemplatePin = pin,
                        autounattendState = AutounattendState.Missing,
                        autounattendBindings = null,
                        autounattendCandidates = null,
                        autounattendRequestGeneration = generation,
                        autounattendLoadInFlight = true,
                        diskLayoutConfirmation = null,
                        pendingAutounattendGeneration = null,
                    ),
                    effects = listOf(
                        UiEffect.LoadAutounattend(
                            xmlName = normalized,
                            requestGeneration = generation,
                        ),
                        UiEffect.LoadAutounattendCandidates(
                            xmlName = normalized,
                            requestGeneration = generation,
                        ),
                    ),
                )
            }
        }

        is UiIntent.RequestLinkWindowsImages -> {
            val device = state.device
            val selected = state.windowsSelectedImages
            val known =
                selected.isNotEmpty() &&
                    selected.all { path ->
                        state.autounattendCandidates?.images?.any {
                            it.imagePath == path && !it.bound && it.problem == null
                        } == true
                    }
            when {
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                device == null ->
                    ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.NoDeviceForLink)))
                selected.isEmpty() ->
                    ReduceResult(state.withStatus(UiMessage.Raw("Select at least one image")))
                (intent.templatePath ?: state.windowsTemplatePath) == null ->
                    ReduceResult(
                        state.withStatus(UiMessage.Raw(
                                "Select an existing XML template from the stick or import one first",
                            )),
                    )
                !known ->
                    ReduceResult(
                        state.withStatus(UiMessage.Raw(
                                "The image list changed; reload it before linking",
                            )),
                    )
                else -> {
                    val paths = selected
                    ReduceResult(
                        state.copy(
                            pendingConfirmation =
                                DestructiveConfirmation.request(
                                    device = device,
                                    action = DestructiveAction.VENTOY_LINK_XML,
                                    isoName = paths.first(),
                                    imagePaths = paths,
                                    templatePath = intent.templatePath ?: state.windowsTemplatePath,
                                    allowDestructiveTemplate = intent.allowDestructiveTemplate,
                                    templateSha256 = intent.expectedTemplateSha256,
                                ),
                        ).withStatus(null),
                    )
                }
            }
        }

        UiIntent.LoadWindowsImageLinks -> {
            val device = state.device
            when {
                !state.actionsEnabled ->
                    ReduceResult(
                        state.withStatus(if (state.isRunning ||
                                    state.operation?.state == OperationState.Queued ||
                                    state.pendingConfirmation != null
                                ) {
                                    UiMessage.Key(UiMessageKey.WaitForOperation)
                                } else {
                                    UiReducer.presenceMessage(state.presence)
                                }),
                    )
                device == null ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                else -> {
                    val generation = state.windowsImageLinksGeneration + 1
                    ReduceResult(
                        state.copy(
                            windowsImageLinks = null,
                            windowsImageLinksGeneration = generation,
                            windowsImageLinksIdentity = device.identity,
                            windowsImageLinksSource = null,
                        ),
                        effects = listOf(
                            UiEffect.LoadWindowsLinks(
                                requestGeneration = generation,
                            ),
                        ),
                    )
                }
            }
        }

        is UiIntent.WindowsImageLinksLoaded -> {
            val device = state.device
            if (device == null ||
                intent.generation != state.windowsImageLinksGeneration ||
                intent.identity != device.identity ||
                (intent.sourceKey != null &&
                    intent.sourceKey != state.ventoySettingsSource.cacheKey())
            ) {
                ReduceResult(state)
            } else {
                val links =
                    intent.links.filter {
                        it.image.startsWith("/") &&
                            it.image != "/" &&
                            it.templates.isNotEmpty()
                    }
                val pendingRemoval = state.windowsPendingRemoval
                val removed = if (intent.error == null) {
                    pendingRemoval - links.map { it.image }.toSet()
                } else {
                    emptySet()
                }
                ReduceResult(
                    state.copy(
                        windowsImageLinks = links,
                        windowsImageLinksSource =
                            intent.sourceKey ?: state.ventoySettingsSource.cacheKey(),
                        windowsSelectedImages = state.windowsSelectedImages - removed,
                        windowsLinkTargetImage =
                            state.windowsLinkTargetImage?.takeUnless { it in removed },
                        windowsPendingRemoval =
                            if (intent.error == null) emptySet() else pendingRemoval,
                        // Only a new error bumps; null keeps the carried message and sequence.
                        statusMessage = intent.error?.let(UiMessage::Raw) ?: state.statusMessage,
                        statusSequence =
                            if (intent.error != null) state.statusSequence + 1 else state.statusSequence,
                    ),
                )
            }
        }

        is UiIntent.RequestUnlinkWindowsImages -> {
            val device = state.device
            val known =
                intent.imagePaths.isNotEmpty() &&
                    intent.imagePaths.all { path ->
                        state.windowsImageLinks?.any { it.image == path } == true
                    }
            when {
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                device == null ->
                    ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.NoDeviceForLink)))
                !known ->
                    ReduceResult(
                        state.withStatus(UiMessage.Raw(
                                "These bindings changed; reload the list before removing them",
                            )),
                    )
                else ->
                    ReduceResult(
                        state.copy(
                            pendingWindowsUnlink = intent.imagePaths,
                        ).withStatus(UiMessage.Raw(
                                "Remove ${intent.imagePaths.size} binding(s)? " +
                                    "Image files and XML files are kept",
                            )),
                    )
            }
        }

        UiIntent.ConfirmWindowsUnlink -> {
            val paths = state.pendingWindowsUnlink
            when {
                paths.isNullOrEmpty() ->
                    ReduceResult(state.copy(pendingWindowsUnlink = null))
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                state.device == null ->
                    ReduceResult(
                        state.copy(
                            pendingWindowsUnlink = null,
                        ).withStatus(UiMessage.Key(UiMessageKey.NoDeviceForLink)),
                    )
                else ->
                    ReduceResult(
                        state.copy(
                            pendingWindowsUnlink = null,
                            windowsPendingRemoval =
                                state.windowsPendingRemoval + paths.toSet(),
                        ).withStatus(UiMessage.Raw(
                                "Removing ${paths.size} binding(s); files stay on the stick",
                            )),
                        effects = listOf(
                            UiEffect.UnlinkXml(
                                imagePaths = paths,
                            ),
                        ),
                    )
            }
        }

        UiIntent.CancelWindowsUnlink ->
            ReduceResult(state.copy(pendingWindowsUnlink = null))

        UiIntent.LoadAutounattendCandidatesOnly -> {
            if (!state.actionsEnabled || state.device == null) ReduceResult(state)
            else {
                // Candidates refresh shares the template generation: bumping it here
                // would discard an in-flight AutounattendLoaded result.
                val generation = state.autounattendRequestGeneration
                val xmlName = state.windowsTemplatePath ?: "/ventoy/autounattend.xml"
                ReduceResult(
                    state.copy(autounattendCandidates = null),
                    effects = listOf(
                        UiEffect.LoadAutounattendCandidates(
                            xmlName,
                            generation,
                        ),
                    ),
                )
            }
        }
        is UiIntent.LoadAutounattend ->
            if (!state.actionsEnabled) {
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            } else {
                val generation = state.autounattendRequestGeneration + 1
                // The document and the whole-partition image list are two asynchronous
                // reads of the same state: they must share one generation, or the later
                // result is dropped as stale and the tab claims the file does not exist.
                ReduceResult(
                    state.copy(
                        windowsTemplatePath = intent.xmlName,
                        windowsTemplatePin = state.windowsTemplatePin?.takeIf { it.path.equals(intent.xmlName, true) },
                        autounattendRequestGeneration = generation,
                        autounattendLoadInFlight = true,
                    ),
                    effects = listOf(
                        UiEffect.LoadAutounattend(
                            xmlName = intent.xmlName,
                            requestGeneration = generation,
                        ),
                        UiEffect.LoadAutounattendCandidates(
                            xmlName = intent.xmlName,
                            requestGeneration = generation,
                        ),
                    ),
                )
            }

        is UiIntent.AutounattendLoaded ->
            if (intent.generation != state.autounattendRequestGeneration) {
                ReduceResult(state)
            } else {
                ReduceResult(
                    state.copy(
                        autounattendState = intent.state,
                        autounattendBindings = intent.bindings,
                        autounattendLoadInFlight = false,
                    ),
                )
            }

        is UiIntent.AutounattendCandidatesLoaded ->
            if (intent.generation != state.autounattendRequestGeneration) {
                ReduceResult(state)
            } else {
                val live =
                    intent.candidates.images
                        .asSequence()
                        .filter { !it.bound && it.problem == null }
                        .map { it.imagePath }
                        .toSet()
                ReduceResult(
                    state.copy(
                        autounattendCandidates = intent.candidates,
                        windowsSelectedImages =
                            state.windowsSelectedImages.filter { it in live },
                    ),
                )
            }

        is UiIntent.RequestGenerateAutounattend -> {
            val layout = intent.params.diskLayout
            val device = state.device
            val loadedTarget =
                when (val loaded = state.autounattendState) {
                    is AutounattendState.Parsed -> loaded.document.sourcePath
                    is AutounattendState.Unreadable -> loaded.sourcePath
                    AutounattendState.Missing -> null
                }
            val sameTarget = loadedTarget != null && UiReducer.sameXmlTarget(loadedTarget, intent.xmlName)
            val planSha256 =
                autounattendPlanSha256(intent.xmlName, intent.params, intent.imagePaths)
            val planMatches = intent.planSha256 == null || intent.planSha256 == planSha256
            val layoutProblems =
                if (layout != null && layout.writeLayout && intent.params.intents.diskLayout == XmlFieldIntent.Set) {
                    targetLayoutProblems(layout)
                } else emptyList()
            when {
                device == null ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                !planMatches ->
                    ReduceResult(
                        state.withStatus(UiMessage.Raw(
                                "Generation plan changed; review and confirm it again",
                            )),
                    )
                layoutProblems.isNotEmpty() ->
                    ReduceResult(
                        state.copy(
                            diskLayoutConfirmation = null,
                            pendingAutounattendGeneration = null,
                        ).withStatus(layoutProblemMessage(layoutProblems)),
                    )
                state.autounattendState is AutounattendState.Unreadable && sameTarget ->
                    ReduceResult(
                        state.withStatus(UiMessage.Raw(
                                "Existing XML cannot be safely extended; choose another file",
                            )),
                    )
                state.autounattendState is AutounattendState.Parsed &&
                    sameTarget && !intent.overwriteXml ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.XmlOverwriteRequired)),
                    )
                // F4: replacing an existing Parsed XML needs an explicit destructive confirm
                // (source name on the dialog) before layout ack or generate.
                state.autounattendState is AutounattendState.Parsed &&
                    sameTarget && intent.overwriteXml && device != null -> {
                    val pendingGen =
                        PendingAutounattendGeneration(
                            xmlName = intent.xmlName,
                            params = intent.params,
                            imagePaths = intent.imagePaths,
                            overwriteXml = true,
                            allowDestructiveTemplate = intent.allowDestructiveTemplate,
                            identity = device.identity,
                            attachGeneration = device.attachGeneration,
                            expectedTemplateSha256 = intent.expectedTemplateSha256,
                            expectedPlanSha256 = planSha256,
                        )
                    ReduceResult(
                        state.copy(
                            pendingAutounattendRawWrite = null,
                            pendingAutounattendGeneration = pendingGen,
                            pendingConfirmation =
                                DestructiveConfirmation.request(
                                    device,
                                    DestructiveAction.VENTOY_UNATTEND_XML_WRITE,
                                    xmlTargetName = intent.xmlName,
                                ),
                        ).withStatus(UiMessage.Key(UiMessageKey.ConfirmXmlOverwrite)),
                    )
                }
                device != null && layout != null && layout.writeLayout ->
                    ReduceResult(
                        state.copy(
                            pendingAutounattendRawWrite = null,
                            pendingAutounattendGeneration =
                                PendingAutounattendGeneration(
                                    xmlName = intent.xmlName,
                                    params = intent.params,
                                    imagePaths = intent.imagePaths,
                                    overwriteXml = intent.overwriteXml,
                                    allowDestructiveTemplate = intent.allowDestructiveTemplate,
                                    identity = device.identity,
                                    attachGeneration = device.attachGeneration,
                                    expectedTemplateSha256 = intent.expectedTemplateSha256,
                                    expectedPlanSha256 = planSha256,
                                    renameFromXmlName = intent.renameFromXmlName,
                                ),
                            diskLayoutConfirmation =
                                layoutConfirmation(layout, planSha256),
                        ).withStatus(UiMessage.Raw(
                                "Confirm the target PC disk layout before generating XML",
                            )),
                    )
                else ->
                    ReduceResult(
                        state.copy(
                            pendingAutounattendRawWrite = null,
                        ).withStatus(UiMessage.Raw("Generating unattend XML")),
                        effects = listOf(
                            UiEffect.GenerateAutounattend(
                                xmlName = intent.xmlName,
                                autounattendParams = intent.params,
                                imagePaths = intent.imagePaths,
                                overwriteXml = intent.overwriteXml,
                                allowDestructiveTemplate = intent.allowDestructiveTemplate,
                                expectedTemplateSha256 = intent.expectedTemplateSha256,
                                expectedPlanSha256 = planSha256,
                                renameFromXmlName = intent.renameFromXmlName,
                            ),
                        ),
                    )
            }
        }

        is UiIntent.RequestWriteAutounattendRaw -> {
            val device = state.device
            val loadedTarget =
                when (val loaded = state.autounattendState) {
                    is AutounattendState.Parsed -> loaded.document.sourcePath
                    is AutounattendState.Unreadable -> loaded.sourcePath
                    AutounattendState.Missing -> null
                }
            val sameTarget = loadedTarget != null && UiReducer.sameXmlTarget(loadedTarget, intent.xmlName)
            val attachmentStale =
                intent.identity != null &&
                    (device == null ||
                        intent.identity != device.identity ||
                        intent.attachGeneration != device.attachGeneration)
            when {
                device == null || !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                attachmentStale ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.TargetChanged)),
                    )
                state.autounattendState is AutounattendState.Parsed &&
                    sameTarget && !intent.overwriteXml ->
                    ReduceResult(
                        state.withStatus(UiMessage.Key(UiMessageKey.XmlOverwriteRequired)),
                    )
                state.autounattendState is AutounattendState.Parsed &&
                    sameTarget && intent.overwriteXml ->
                    ReduceResult(
                        state.copy(
                            pendingAutounattendGeneration = null,
                            pendingAutounattendRawWrite =
                                PendingAutounattendRawWrite(
                                    xmlName = intent.xmlName,
                                    rawBytes = intent.rawBytes,
                                    overwriteXml = true,
                                    allowDestructiveTemplate = intent.allowDestructiveTemplate,
                                    identity = intent.identity ?: device.identity,
                                    attachGeneration =
                                        if (intent.identity != null) {
                                            intent.attachGeneration
                                        } else {
                                            device.attachGeneration
                                        },
                                    expectedTemplateSha256 = intent.expectedTemplateSha256,
                                ),
                            pendingConfirmation =
                                DestructiveConfirmation.request(
                                    device,
                                    DestructiveAction.VENTOY_UNATTEND_XML_WRITE,
                                    xmlTargetName = intent.xmlName,
                                ),
                        ).withStatus(UiMessage.Key(UiMessageKey.ConfirmXmlOverwrite)),
                    )
                else ->
                    ReduceResult(
                        state.copy(
                            pendingAutounattendGeneration = null,
                            pendingAutounattendRawWrite = null,
                        ).withStatus(UiMessage.Raw("Writing unattend XML")),
                        effects = listOf(
                            UiEffect.WriteAutounattendRaw(
                                xmlName = intent.xmlName,
                                rawBytes = intent.rawBytes,
                                overwriteXml = intent.overwriteXml,
                                allowDestructiveTemplate = intent.allowDestructiveTemplate,
                                expectedTemplateSha256 = intent.expectedTemplateSha256,
                                renameFromXmlName = intent.renameFromXmlName,
                            ),
                        ),
                    )
            }
        }

        is UiIntent.ConfirmDiskLayout -> {
            val pending = state.pendingAutounattendGeneration
            when {
                !intent.acknowledged ->
                    ReduceResult(
                        state.withStatus(UiMessage.Raw(
                                "The target-PC disk warning must be acknowledged explicitly",
                            )),
                    )
                pending == null ->
                    ReduceResult(state.copy(diskLayoutConfirmation = null))
                pending.identity != state.device?.identity ||
                    pending.attachGeneration != state.device?.attachGeneration ->
                    ReduceResult(
                        state.copy(
                            diskLayoutConfirmation = null,
                            pendingAutounattendGeneration = null,
                        ).withStatus(UiMessage.Raw(
                                "USB attachment changed; re-open the unattend generator",
                            )),
                    )
                !state.actionsEnabled ->
                    ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
                else -> {
                    val layout = pending.params.diskLayout
                    val layoutProblems =
                        if (layout != null && layout.writeLayout && pending.params.intents.diskLayout == XmlFieldIntent.Set) {
                            targetLayoutProblems(layout)
                        } else {
                            emptyList()
                        }
                    val plan =
                        autounattendPlanSha256(
                            pending.xmlName,
                            pending.params,
                            pending.imagePaths,
                        )
                    val snapshotMatches =
                        state.diskLayoutConfirmation?.planSha256 == plan &&
                            (pending.expectedPlanSha256 == null ||
                                pending.expectedPlanSha256 == plan)
                    if (layoutProblems.isNotEmpty()) {
                        ReduceResult(
                            state.copy(
                                diskLayoutConfirmation = null,
                                pendingAutounattendGeneration = null,
                            ).withStatus(layoutProblemMessage(layoutProblems)),
                        )
                    } else if (!snapshotMatches) {
                        ReduceResult(
                            state.copy(
                                diskLayoutConfirmation = null,
                                pendingAutounattendGeneration = null,
                            ).withStatus(UiMessage.Raw(
                                    "Generation plan changed; review and confirm it again",
                                )),
                        )
                    } else {
                        ReduceResult(
                            state.copy(
                                diskLayoutConfirmation = null,
                                pendingAutounattendGeneration = null,
                            ).withStatus(UiMessage.Raw(
                                    "Generating unattend XML with disk layout",
                                )),
                            effects = listOf(
                                UiEffect.GenerateAutounattend(
                                    xmlName = pending.xmlName,
                                    autounattendParams = pending.params,
                                    imagePaths = pending.imagePaths,
                                    overwriteXml = pending.overwriteXml,
                                    allowDestructiveTemplate = pending.allowDestructiveTemplate,
                                    expectedTemplateSha256 = pending.expectedTemplateSha256,
                                    expectedPlanSha256 = pending.expectedPlanSha256,
                                    renameFromXmlName = pending.renameFromXmlName,
                                ),
                            ),
                        )
                    }
                }
            }
        }

        UiIntent.DismissDiskLayout ->
            ReduceResult(
                state.copy(
                    diskLayoutConfirmation = null,
                    pendingAutounattendGeneration = null,
                ),
            )

        UiIntent.LoadVentoyGlobalSettings -> VentoyTransitions.loadVentoyGlobalSettings(state)
        is UiIntent.SelectVentoySettingsUsbFile ->
            VentoyTransitions.selectVentoySettingsUsbFile(state, intent)
        is UiIntent.SelectVentoySettingsDocument ->
            VentoyTransitions.selectVentoySettingsDocument(state, intent)
        UiIntent.UseDefaultVentoySettingsFile ->
            VentoyTransitions.useDefaultVentoySettingsFile(state)
        UiIntent.LoadVentoyFolders -> VentoyTransitions.loadVentoyFolders(state)
        is UiIntent.OpenVentoyFolder ->
            VentoyTransitions.openVentoyFolder(state, intent)
        UiIntent.VentoyFolderUp -> VentoyTransitions.ventoyFolderUp(state)
        is UiIntent.VentoyFoldersLoaded ->
            VentoyTransitions.ventoyFoldersLoaded(state, intent)
        is UiIntent.VentoyGlobalSettingsLoaded ->
            VentoyTransitions.ventoyGlobalSettingsLoaded(state, intent)
        is UiIntent.StageVentoyControl ->
            VentoyTransitions.stageVentoyControl(state, intent)
        is UiIntent.DiscardStagedVentoyControl ->
            VentoyTransitions.discardStagedVentoyControl(state, intent)
        is UiIntent.CommitAndApplyVentoyControls -> {
            if (intent.duplicateKeys.isNotEmpty()) {
                ReduceResult(state.withStatus(UiMessage.Raw("Cannot edit duplicate Ventoy keys safely: ${intent.duplicateKeys.joinToString()}")))
            } else {
                mergeCommittedVentoyControls(
                    state,
                    intent.removedKeys,
                    intent.settings,
                    intent.discardedKeys,
                )?.let { problem ->
                    ReduceResult(state.withStatus(UiMessage.Raw(problem)))
                } ?: VentoyTransitions.requestApplyVentoyControls(
                    state.copy(
                        pendingVentoyControls = mergeVentoyControlPending(
                            state.pendingVentoyControls,
                            intent.removedKeys,
                            intent.settings,
                            intent.discardedKeys,
                        ),
                    ).withStatus(null),
                )
            }
        }
        is UiIntent.CommitVentoyControls -> {
            if (intent.duplicateKeys.isNotEmpty()) {
                ReduceResult(state.withStatus(UiMessage.Raw("Cannot edit duplicate Ventoy keys safely: ${intent.duplicateKeys.joinToString()}")))
            } else {
                mergeCommittedVentoyControls(
                    state,
                    intent.removedKeys,
                    intent.settings,
                    intent.discardedKeys,
                )?.let { problem ->
                    ReduceResult(state.withStatus(UiMessage.Raw(problem)))
                } ?: ReduceResult(
                    state.copy(
                        pendingVentoyControls = mergeVentoyControlPending(
                            state.pendingVentoyControls,
                            intent.removedKeys,
                            intent.settings,
                            intent.discardedKeys,
                        ),
                    ).withStatus(null),
                )
            }
        }
        is UiIntent.RestoreVentoyControls ->
            ReduceResult(state.copy(pendingVentoyControls = intent.settings).withStatus(null))
        UiIntent.RequestApplyVentoyControls -> VentoyTransitions.requestApplyVentoyControls(state)

        UiIntent.RefreshVentoyControlKeyCatalog -> {
            if (state.ventoyControlKeyCatalogLoading) {
                ReduceResult(state)
            } else {
                val token = state.ventoyControlKeyCatalogRequest + 1L
                ReduceResult(
                    state.copy(
                        ventoyControlKeyCatalogRequest = token,
                        ventoyControlKeyCatalogLoading = true,
                        // Keep prior catalog; failure must not look like empty keys.
                        ventoyControlKeyCatalogError = null,
                    ),
                    effects = listOf(
                        UiEffect.FetchVentoyControlKeyCatalog(
                            requestToken = token,
                            release = state.upstreamVentoyRelease
                                ?: usb.ventoy.VentoyAssets.PINNED_RELEASE,
                        ),
                    ),
                )
            }
        }

        is UiIntent.SetVentoyControlKeyCatalog ->
            if (intent.requestToken != state.ventoyControlKeyCatalogRequest) {
                ReduceResult(state)
            } else {
                ReduceResult(
                    state.copy(
                        ventoyControlKeyCatalog = intent.catalog ?: state.ventoyControlKeyCatalog,
                        ventoyControlKeyCatalogError =
                            intent.error?.trim()?.takeIf { it.isNotEmpty() },
                        ventoyControlKeyCatalogLoading = false,
                    ),
                )
            }


        UiIntent.CheckVentoyUpstream -> {
            val token = state.ventoyUpstreamRequest + 1L
            ReduceResult(
                state.copy(
                    ventoyUpstreamRequest = token,
                    ventoyUpstreamChecking = true,
                    // A failed re-check must end as n/a, never as the previous answer.
                    upstreamVentoyRelease = null,
                    upstreamVentoyPinned = null,
                ),
                effects = listOf(UiEffect.CheckVentoyUpstream(requestToken = token)),
            )
        }

        is UiIntent.SetVentoyUpstreamRelease ->
            if (intent.requestToken != state.ventoyUpstreamRequest) {
                ReduceResult(state)
            } else {
                ReduceResult(
                    state.copy(
                        upstreamVentoyRelease = intent.tag?.trim()?.takeIf { it.isNotEmpty() },
                        upstreamVentoyError = intent.error?.trim()?.takeIf { it.isNotEmpty() },
                        upstreamVentoyPinned =
                            intent.tag?.trim()?.takeIf { it.isNotEmpty() }
                                ?.let { intent.pinned },
                        ventoyUpstreamChecking = false,
                    ),
                )
            }

        UiIntent.RequestDownloadVentoyRelease ->
            if (state.ventoyDownloadInFlight || state.upstreamVentoyPinned == false) {
                ReduceResult(state)
            } else {
                val token = state.ventoyDownloadRequest + 1L
                ReduceResult(
                    state.copy(
                        ventoyDownloadRequest = token,
                        ventoyDownloadInFlight = true,
                        ventoyDownloadError = null,
                    ),
                    effects = listOf(
                        UiEffect.DownloadVentoyRelease(
                            requestToken = token,
                            upstreamVersion = state.upstreamVentoyRelease,
                        ),
                    ),
                )
            }

        is UiIntent.ConfirmVentoyReleaseDownload -> {
            val current = state.upstreamVentoyRelease
            val currentPinned = state.upstreamVentoyPinned
            if (state.ventoyDownloadInFlight ||
                intent.upstreamRequest != state.ventoyUpstreamRequest ||
                current == null ||
                current != intent.release ||
                currentPinned != intent.pinned
            ) {
                ReduceResult(state)
            } else {
                val token = state.ventoyDownloadRequest + 1L
                ReduceResult(
                    state.copy(
                        ventoyDownloadRequest = token,
                        ventoyDownloadInFlight = true,
                        ventoyDownloadError = null,
                    ),
                    effects = listOf(
                        UiEffect.DownloadVentoyRelease(
                            requestToken = token,
                            upstreamVersion = intent.release,
                        ),
                    ),
                )
            }
        }

        is UiIntent.VentoyReleaseDownloaded ->
            if (intent.requestToken != state.ventoyDownloadRequest) {
                ReduceResult(state)
            } else {
                ReduceResult(
                    state.copy(
                        ventoyStoreState =
                            usb.ventoy.VentoyReleaseStoreState.Verified(intent.provenance),
                        ventoyStoreRevision = state.ventoyStoreRevision + 1L,
                        ventoyDownloadInFlight = false,
                        ventoyDownloadError = null,
                    ),
                )
            }

        is UiIntent.VentoyReleaseDownloadFailed ->
            if (intent.requestToken != state.ventoyDownloadRequest) {
                ReduceResult(state)
            } else {
                ReduceResult(
                    state.copy(
                        ventoyDownloadInFlight = false,
                        ventoyDownloadError = intent.message,
                    ),
                )
            }

        is UiIntent.VentoyReleaseStoreChanged ->
            if (intent.requestToken != state.ventoyDownloadRequest) {
                ReduceResult(state)
            } else {
                ReduceResult(
                    state.copy(
                        ventoyStoreState =
                            usb.ventoy.VentoyReleaseStoreState.Verified(intent.provenance),
                        ventoyStoreRevision = state.ventoyStoreRevision + 1L,
                        ventoyDownloadInFlight = false,
                        ventoyDownloadError = intent.message,
                    ),
                )
            }

        is UiIntent.VentoyReleaseStoreUnknown ->
            if (intent.requestToken != state.ventoyDownloadRequest) {
                ReduceResult(state)
            } else {
                ReduceResult(
                    state.copy(
                        ventoyStoreState = usb.ventoy.VentoyReleaseStoreState.Unknown,
                        ventoyStoreRevision = state.ventoyStoreRevision + 1L,
                        ventoyDownloadInFlight = false,
                        ventoyDownloadError = intent.message,
                    ),
                )
            }

        is UiIntent.SetVentoyBundledRelease ->
            ReduceResult(
                state.copy(
                    bundledVentoyRelease =
                        intent.release?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )

        is UiIntent.SetVentoyStoreState ->
            if (intent.revision != state.ventoyStoreRevision) {
                ReduceResult(state)
            } else {
                ReduceResult(state.copy(ventoyStoreState = intent.storeState))
            }

        is UiIntent.SetVentoyInstalledRelease -> {
            val device = state.device
            val matches =
                device != null &&
                    device.attachGeneration == intent.attachGeneration &&
                    device.identity == intent.identity
            if (!matches) {
                ReduceResult(state)
            } else {
                val error = intent.error?.trim()?.takeIf { it.isNotEmpty() }
                ReduceResult(
                    state.copy(
                        installedVentoyRelease =
                            if (error == null) {
                                intent.release?.trim()?.takeIf { it.isNotEmpty() }
                            } else {
                                null
                            },
                        installedVentoyReadError = error,
                    ),
                )
            }
        }

        is UiIntent.RequestBindAutounattend ->
            if (!state.actionsEnabled) {
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            } else {
                ReduceResult(
                    state,
                    effects = listOf(
                        UiEffect.BindAutounattend(
                            xmlName = intent.xmlName,
                            imagePaths = intent.imagePaths,
                            allowDestructiveTemplate = intent.allowDestructiveTemplate,
                            expectedTemplateSha256 = intent.expectedTemplateSha256,
                            overwrite = intent.overwrite,
                        ),
                    ),
                )
            }

        is UiIntent.RequestUnbindAutounattend ->
            if (!state.actionsEnabled) {
                ReduceResult(state.withStatus(UiReducer.presenceMessage(state.presence)))
            } else {
                ReduceResult(
                    state,
                    effects = listOf(
                        UiEffect.UnbindAutounattend(
                            xmlName = intent.xmlName,
                            imagePaths = listOf(intent.imagePath),
                        ),
                    ),
                )
            }

        is UiIntent.AutounattendBindingsLoaded ->
            ReduceResult(
                state.copy(
                    autounattendBindings = intent.bindings,
                    // Only a new error bumps; null keeps the carried message and sequence.
                    statusMessage = intent.error?.let(UiMessage::Raw) ?: state.statusMessage,
                    statusSequence =
                        if (intent.error != null) state.statusSequence + 1 else state.statusSequence,
                ),
            )

        else -> null
    }

    /**
     * Bare names resolve under /ventoy; a full path is itself. Display-only path join —
     * rewrite gate uses [sameXmlTarget] / [XmlPath.resolve].
     */
    internal fun importTarget(directory: String, name: String): String =
        if (directory == "/") "/$name" else "$directory/$name"

    /**
     * Editor policy: a target layout must carry the declared disk size, so the sum can be
     * checked before any confirmation. The XML writer keeps the weaker default because the
     * declared size is not part of the Windows layout.
     */
    internal fun targetLayoutProblems(layout: DiskLayout): List<AutounattendProblem> =
        Autounattend.validate(
            AutounattendParams(diskLayout = layout),
            requireDeclaredDiskSize = true,
        ).problems

    internal fun layoutProblemMessage(
        problems: List<AutounattendProblem>,
    ): UiMessage.InvalidTargetLayout =
        UiMessage.InvalidTargetLayout(problems.distinct())

    /** Immutable snapshot of exactly what the acknowledgement dialog shows. */
    internal fun layoutConfirmation(
        layout: DiskLayout,
        planSha256: String,
    ): DiskLayoutConfirmation =
        DiskLayoutConfirmation(
            diskId = layout.diskId,
            partitionCount = layout.partitions.size,
            writeLayout = layout.writeLayout,
            planSha256 = planSha256,
            partitions = layout.partitions.toList(),
            declaredSizeMb = layout.declaredSizeMb,
            totalMb = diskLayoutTotalMb(layout.partitions),
            remainingMb = diskLayoutRemainingMb(layout),
            installTargetPartitionId = diskLayoutInstallTargetPartitionId(layout.partitions),
            wipe = layout.wipe,
        )

    /**
     * Validate editor-committed rows before they merge into [AppUiState.pendingVentoyControls].
     * Returns a problem string, or null when the merge is safe.
     */
    internal fun mergeCommittedVentoyControls(
        state: AppUiState,
        removedKeys: Set<String>,
        settings: List<VentoyControlSetting>,
        discardedKeys: Set<String> = emptySet(),
    ): String? {
        val merged = mergeVentoyControlPending(
            state.pendingVentoyControls,
            removedKeys,
            settings,
            discardedKeys,
        )
        return VentoyControlCatalog.problem(merged)
    }

    /**
     * Merge editor drafts into existing staged controls instead of replacing the whole list.
     * Known keys staged from the card (StageVentoyControl) stay unless the editor renames,
     * removes, or overrides them. Empty Apply therefore no longer wipes pending known keys.
     * [discardedKeys] erase pending-only rows (never written as stick removals).
     */
    internal fun mergeVentoyControlPending(
        existing: List<VentoyControlSetting>,
        removedKeys: Set<String>,
        settings: List<VentoyControlSetting>,
        discardedKeys: Set<String> = emptySet(),
    ): List<VentoyControlSetting> {
        val staged = LinkedHashMap<String, VentoyControlSetting>()
        existing.forEach { staged[it.key] = it }
        discardedKeys.forEach { staged.remove(it) }
        removedKeys.forEach { key ->
            staged[key] = VentoyControlSetting(key, null)
        }
        settings.forEach { setting ->
            staged[setting.key] = setting
        }
        return staged.values.toList()
    }
}
