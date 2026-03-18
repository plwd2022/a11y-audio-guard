package com.plwd.audiochannelguard

internal data class ClassicBluetoothSoftGuardDecisionInput(
    val monitorRunning: Boolean,
    val softGuardEnabled: Boolean,
    val softGuardRunning: Boolean,
    val pollingPhase: RoutePollingPhase,
    val hasGuardCommunicationHold: Boolean,
    val suspendedByCall: Boolean,
    val hasHeadset: Boolean,
    val isClassicBluetoothHeadset: Boolean,
    val routedDeviceKind: CommunicationDeviceKind,
    val hasRecentBuiltinRouteEvidence: Boolean,
    val hasWaitedForVerifyDelay: Boolean,
    val isEscalationCooldownElapsed: Boolean,
)

internal enum class ClassicBluetoothSoftGuardOutcome {
    IGNORE,
    PASSIVE_SKIP,
    WAIT_FOR_VERIFY_DELAY,
    WAIT_FOR_ESCALATION_COOLDOWN,
    ESCALATE,
}

internal data class ClassicBluetoothSoftGuardDecision(
    val outcome: ClassicBluetoothSoftGuardOutcome,
)

internal object ClassicBluetoothSoftGuardResolver {
    fun resolve(input: ClassicBluetoothSoftGuardDecisionInput): ClassicBluetoothSoftGuardDecision {
        if (
            !input.monitorRunning ||
            !input.softGuardEnabled ||
            !input.softGuardRunning ||
            input.pollingPhase != RoutePollingPhase.IDLE ||
            input.hasGuardCommunicationHold ||
            input.suspendedByCall ||
            !input.hasHeadset ||
            !input.isClassicBluetoothHeadset ||
            input.routedDeviceKind != CommunicationDeviceKind.BUILTIN
        ) {
            return ClassicBluetoothSoftGuardDecision(
                outcome = ClassicBluetoothSoftGuardOutcome.IGNORE,
            )
        }

        if (!input.hasRecentBuiltinRouteEvidence) {
            return ClassicBluetoothSoftGuardDecision(
                outcome = ClassicBluetoothSoftGuardOutcome.PASSIVE_SKIP,
            )
        }

        if (!input.hasWaitedForVerifyDelay) {
            return ClassicBluetoothSoftGuardDecision(
                outcome = ClassicBluetoothSoftGuardOutcome.WAIT_FOR_VERIFY_DELAY,
            )
        }

        if (!input.isEscalationCooldownElapsed) {
            return ClassicBluetoothSoftGuardDecision(
                outcome = ClassicBluetoothSoftGuardOutcome.WAIT_FOR_ESCALATION_COOLDOWN,
            )
        }

        return ClassicBluetoothSoftGuardDecision(
            outcome = ClassicBluetoothSoftGuardOutcome.ESCALATE,
        )
    }
}
