package com.plwd.audiochannelguard

internal data class ClearProbeDecisionInput(
    val hasHeadset: Boolean,
    val restoredNaturally: Boolean,
    val communicationDeviceKind: CommunicationDeviceKind,
    val timedOut: Boolean,
)

internal enum class ClearProbeOutcome {
    STOP_NO_HEADSET,
    FINISH_RESTORED_NATURALLY,
    FORCE_RESTORE_BUILTIN_ROUTE,
    FINISH_NORMAL,
    CONTINUE_POLLING,
}

internal data class ClearProbeDecision(
    val outcome: ClearProbeOutcome,
)

internal object ClearProbeResolver {
    fun resolve(input: ClearProbeDecisionInput): ClearProbeDecision {
        val outcome = when {
            !input.hasHeadset -> ClearProbeOutcome.STOP_NO_HEADSET
            input.restoredNaturally -> ClearProbeOutcome.FINISH_RESTORED_NATURALLY
            input.communicationDeviceKind == CommunicationDeviceKind.BUILTIN ->
                ClearProbeOutcome.FORCE_RESTORE_BUILTIN_ROUTE

            input.timedOut -> ClearProbeOutcome.FINISH_NORMAL
            else -> ClearProbeOutcome.CONTINUE_POLLING
        }
        return ClearProbeDecision(outcome = outcome)
    }
}
