package com.plwd.audiochannelguard

internal data class ClassicBluetoothWidebandAttemptInput(
    val monitorRunning: Boolean,
    val widebandEnabled: Boolean,
    val isClassicBluetoothTarget: Boolean,
    val suspendedByCall: Boolean,
    val previousDeviceKind: CommunicationDeviceKind,
    val previousDeviceMatchesTarget: Boolean,
    val hasRecentAttempt: Boolean,
)

internal enum class ClassicBluetoothWidebandAttemptOutcome {
    SKIP,
    APPLY_HINT,
}

internal data class ClassicBluetoothWidebandAttemptDecision(
    val outcome: ClassicBluetoothWidebandAttemptOutcome,
)

internal object ClassicBluetoothWidebandAttemptResolver {
    fun resolve(input: ClassicBluetoothWidebandAttemptInput): ClassicBluetoothWidebandAttemptDecision {
        if (
            !input.monitorRunning ||
            !input.widebandEnabled ||
            !input.isClassicBluetoothTarget ||
            input.suspendedByCall ||
            input.hasRecentAttempt
        ) {
            return ClassicBluetoothWidebandAttemptDecision(
                outcome = ClassicBluetoothWidebandAttemptOutcome.SKIP,
            )
        }

        if (
            input.previousDeviceKind == CommunicationDeviceKind.EXTERNAL &&
            input.previousDeviceMatchesTarget
        ) {
            return ClassicBluetoothWidebandAttemptDecision(
                outcome = ClassicBluetoothWidebandAttemptOutcome.SKIP,
            )
        }

        return ClassicBluetoothWidebandAttemptDecision(
            outcome = ClassicBluetoothWidebandAttemptOutcome.APPLY_HINT,
        )
    }
}
