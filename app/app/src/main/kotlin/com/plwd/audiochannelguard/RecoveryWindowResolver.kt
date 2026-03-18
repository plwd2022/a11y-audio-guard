package com.plwd.audiochannelguard

internal data class RecoveryWindowDecisionInput(
    val timedOut: Boolean,
    val hasHeadset: Boolean,
    val communicationDeviceKind: CommunicationDeviceKind,
    val communicationDeviceMatchesHeadset: Boolean,
    val stableHitCount: Int,
    val requiredStableHits: Int,
    val isClassicBluetoothPassiveCandidate: Boolean,
    val shouldKeepHeldRouteAfterRecovery: Boolean,
    val hasGuardCommunicationHold: Boolean,
)

internal enum class RecoveryWindowOutcome {
    STOP_TIMEOUT,
    STOP_NO_HEADSET,
    START_CLASSIC_BLUETOOTH_CONFIRM,
    RESTORE_BUILTIN_ROUTE,
    ENTER_HELD_ROUTE,
    START_RELEASE_PROBE,
    FINISH_NORMAL,
    CONTINUE_POLLING,
}

internal data class RecoveryWindowDecision(
    val outcome: RecoveryWindowOutcome,
    val nextStableHitCount: Int,
)

internal object RecoveryWindowResolver {
    fun resolve(input: RecoveryWindowDecisionInput): RecoveryWindowDecision {
        if (input.timedOut) {
            return RecoveryWindowDecision(
                outcome = RecoveryWindowOutcome.STOP_TIMEOUT,
                nextStableHitCount = 0,
            )
        }

        if (!input.hasHeadset) {
            return RecoveryWindowDecision(
                outcome = RecoveryWindowOutcome.STOP_NO_HEADSET,
                nextStableHitCount = 0,
            )
        }

        if (input.communicationDeviceKind == CommunicationDeviceKind.BUILTIN) {
            return RecoveryWindowDecision(
                outcome =
                    if (input.isClassicBluetoothPassiveCandidate) {
                        RecoveryWindowOutcome.START_CLASSIC_BLUETOOTH_CONFIRM
                    } else {
                        RecoveryWindowOutcome.RESTORE_BUILTIN_ROUTE
                    },
                nextStableHitCount = 0,
            )
        }

        if (!input.communicationDeviceMatchesHeadset) {
            return RecoveryWindowDecision(
                outcome = RecoveryWindowOutcome.CONTINUE_POLLING,
                nextStableHitCount = 0,
            )
        }

        val nextStableHitCount = input.stableHitCount + 1
        if (nextStableHitCount < input.requiredStableHits) {
            return RecoveryWindowDecision(
                outcome = RecoveryWindowOutcome.CONTINUE_POLLING,
                nextStableHitCount = nextStableHitCount,
            )
        }

        val outcome = when {
            input.shouldKeepHeldRouteAfterRecovery -> RecoveryWindowOutcome.ENTER_HELD_ROUTE
            input.hasGuardCommunicationHold -> RecoveryWindowOutcome.START_RELEASE_PROBE
            else -> RecoveryWindowOutcome.FINISH_NORMAL
        }
        return RecoveryWindowDecision(
            outcome = outcome,
            nextStableHitCount = nextStableHitCount,
        )
    }
}
