package com.plwd.audiochannelguard

internal data class ClassicBluetoothPassiveCandidateInput(
    val enhancedModeEnabled: Boolean,
    val hasGuardCommunicationHold: Boolean,
    val isClassicBluetoothHeadset: Boolean,
)

internal enum class ClassicBluetoothPassiveCandidateOutcome {
    NOT_ALLOWED,
    ALLOWED,
}

internal data class ClassicBluetoothPassiveCandidateDecision(
    val outcome: ClassicBluetoothPassiveCandidateOutcome,
)

internal object ClassicBluetoothPassiveCandidateResolver {
    fun resolve(input: ClassicBluetoothPassiveCandidateInput): ClassicBluetoothPassiveCandidateDecision {
        if (
            input.enhancedModeEnabled ||
            input.hasGuardCommunicationHold ||
            !input.isClassicBluetoothHeadset
        ) {
            return ClassicBluetoothPassiveCandidateDecision(
                outcome = ClassicBluetoothPassiveCandidateOutcome.NOT_ALLOWED,
            )
        }

        return ClassicBluetoothPassiveCandidateDecision(
            outcome = ClassicBluetoothPassiveCandidateOutcome.ALLOWED,
        )
    }
}
