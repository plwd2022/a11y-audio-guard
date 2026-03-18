package com.plwd.audiochannelguard

internal data class ReleaseProbeDecisionInput(
    val hasHeadset: Boolean,
    val communicationDeviceKind: CommunicationDeviceKind,
    val timedOut: Boolean,
)

internal enum class ReleaseProbeOutcome {
    STOP_NO_HEADSET,
    HANDLE_BUILTIN_ROUTE,
    FINISH_NORMAL,
    CONTINUE_POLLING,
}

internal data class ReleaseProbeDecision(
    val outcome: ReleaseProbeOutcome,
)

internal object ReleaseProbeResolver {
    fun resolve(input: ReleaseProbeDecisionInput): ReleaseProbeDecision {
        val outcome = when {
            !input.hasHeadset -> ReleaseProbeOutcome.STOP_NO_HEADSET
            input.communicationDeviceKind == CommunicationDeviceKind.BUILTIN ->
                ReleaseProbeOutcome.HANDLE_BUILTIN_ROUTE

            input.timedOut -> ReleaseProbeOutcome.FINISH_NORMAL
            else -> ReleaseProbeOutcome.CONTINUE_POLLING
        }
        return ReleaseProbeDecision(outcome = outcome)
    }
}
