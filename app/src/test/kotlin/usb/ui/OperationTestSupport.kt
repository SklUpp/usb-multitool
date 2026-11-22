package usb.ui

/** Deliver terminal update through same-token begin/update path used by host. */
internal fun reduceTerminalOperation(
    state: AppUiState,
    terminal: OperationProgress,
): ReduceResult {
    val begun = UiReducer.reduce(
        state,
        UiIntent.OperationBegan(terminal.copy(state = OperationState.Running)),
    ).state
    val completed = UiReducer.reduce(begun, UiIntent.OperationUpdated(terminal))
    val deviceGeneration = completed.state.device?.attachGeneration ?: return completed
    return UiReducer.reduce(
        completed.state,
        UiIntent.OperationCompletionEffects(
            token = terminal.token,
            attachmentGeneration = deviceGeneration,
            title = terminal.title,
            state = terminal.state,
        ),
    )
}
