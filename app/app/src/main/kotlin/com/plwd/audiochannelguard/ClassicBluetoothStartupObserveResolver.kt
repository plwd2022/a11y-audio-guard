package com.plwd.audiochannelguard

internal data class ClassicBluetoothStartupObserveDecisionInput(
    val hasHeadset: Boolean,
    val communicationDeviceKind: CommunicationDeviceKind,
    val routedDeviceKind: CommunicationDeviceKind,
)

internal enum class ClassicBluetoothStartupObserveOutcome {
    STOP_NO_HEADSET,
    STOP_ROUTE_RECOVERED,
    EVALUATE_SOFT_GUARD,
    STOP_UNCONFIRMED,
}

internal data class ClassicBluetoothStartupObserveDecision(
    val outcome: ClassicBluetoothStartupObserveOutcome,
)

internal object ClassicBluetoothStartupObserveResolver {
    fun resolve(input: ClassicBluetoothStartupObserveDecisionInput): ClassicBluetoothStartupObserveDecision {
        val outcome = when {
            !input.hasHeadset -> ClassicBluetoothStartupObserveOutcome.STOP_NO_HEADSET
            input.communicationDeviceKind != CommunicationDeviceKind.BUILTIN ->
                ClassicBluetoothStartupObserveOutcome.STOP_ROUTE_RECOVERED

            input.routedDeviceKind == CommunicationDeviceKind.BUILTIN ->
                ClassicBluetoothStartupObserveOutcome.EVALUATE_SOFT_GUARD

            else -> ClassicBluetoothStartupObserveOutcome.STOP_UNCONFIRMED
        }
        return ClassicBluetoothStartupObserveDecision(outcome = outcome)
    }
}
