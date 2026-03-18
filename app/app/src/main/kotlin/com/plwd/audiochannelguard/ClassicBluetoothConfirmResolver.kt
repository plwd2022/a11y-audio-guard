package com.plwd.audiochannelguard

internal data class ClassicBluetoothConfirmDecisionInput(
    val hasHeadset: Boolean,
    val passiveConfirmationAllowed: Boolean,
    val communicationDeviceKind: CommunicationDeviceKind,
    val softGuardObservationActive: Boolean,
    val timedOut: Boolean,
    val confirmHitCount: Int,
    val requiredHits: Int,
)

internal enum class ClassicBluetoothConfirmOutcome {
    STOP_NO_HEADSET,
    STOP_CONDITIONS_CHANGED,
    STOP_ROUTE_RECOVERED,
    STOP_SOFT_GUARD_TIMEOUT,
    CONTINUE_SOFT_GUARD_OBSERVATION,
    CONFIRM_HIJACK,
    STOP_TIMEOUT,
    CONTINUE_CONFIRMING,
}

internal data class ClassicBluetoothConfirmDecision(
    val outcome: ClassicBluetoothConfirmOutcome,
    val nextHitCount: Int,
)

internal object ClassicBluetoothConfirmResolver {
    fun resolve(input: ClassicBluetoothConfirmDecisionInput): ClassicBluetoothConfirmDecision {
        if (!input.hasHeadset) {
            return ClassicBluetoothConfirmDecision(
                outcome = ClassicBluetoothConfirmOutcome.STOP_NO_HEADSET,
                nextHitCount = 0,
            )
        }

        if (!input.passiveConfirmationAllowed) {
            return ClassicBluetoothConfirmDecision(
                outcome = ClassicBluetoothConfirmOutcome.STOP_CONDITIONS_CHANGED,
                nextHitCount = input.confirmHitCount,
            )
        }

        if (input.communicationDeviceKind != CommunicationDeviceKind.BUILTIN) {
            return ClassicBluetoothConfirmDecision(
                outcome = ClassicBluetoothConfirmOutcome.STOP_ROUTE_RECOVERED,
                nextHitCount = 0,
            )
        }

        if (input.softGuardObservationActive) {
            return ClassicBluetoothConfirmDecision(
                outcome =
                    if (input.timedOut) {
                        ClassicBluetoothConfirmOutcome.STOP_SOFT_GUARD_TIMEOUT
                    } else {
                        ClassicBluetoothConfirmOutcome.CONTINUE_SOFT_GUARD_OBSERVATION
                    },
                nextHitCount = input.confirmHitCount,
            )
        }

        val nextHitCount = input.confirmHitCount + 1
        if (nextHitCount >= input.requiredHits) {
            return ClassicBluetoothConfirmDecision(
                outcome = ClassicBluetoothConfirmOutcome.CONFIRM_HIJACK,
                nextHitCount = nextHitCount,
            )
        }

        if (input.timedOut) {
            return ClassicBluetoothConfirmDecision(
                outcome = ClassicBluetoothConfirmOutcome.STOP_TIMEOUT,
                nextHitCount = nextHitCount,
            )
        }

        return ClassicBluetoothConfirmDecision(
            outcome = ClassicBluetoothConfirmOutcome.CONTINUE_CONFIRMING,
            nextHitCount = nextHitCount,
        )
    }
}
