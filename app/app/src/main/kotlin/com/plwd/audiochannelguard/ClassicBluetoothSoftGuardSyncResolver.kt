package com.plwd.audiochannelguard

internal enum class ClassicBluetoothSoftGuardPurpose {
    PASSIVE_CONFIRM,
    STARTUP_OBSERVE,
    MANUAL_RELEASE,
}

internal data class ClassicBluetoothSoftGuardSyncInput(
    val monitorRunning: Boolean,
    val softGuardEnabled: Boolean,
    val hasHeadset: Boolean,
    val isClassicBluetoothHeadset: Boolean,
    val pollingPhase: RoutePollingPhase,
    val hasStartupObservation: Boolean,
    val manualReleaseInProgress: Boolean,
    val hasGuardCommunicationHold: Boolean,
    val suspendedByCall: Boolean,
)

internal data class ClassicBluetoothSoftGuardSyncDecision(
    val shouldRun: Boolean,
    val purpose: ClassicBluetoothSoftGuardPurpose? = null,
)

internal object ClassicBluetoothSoftGuardSyncResolver {
    fun resolve(input: ClassicBluetoothSoftGuardSyncInput): ClassicBluetoothSoftGuardSyncDecision {
        if (
            !input.monitorRunning ||
            !input.softGuardEnabled ||
            !input.hasHeadset ||
            !input.isClassicBluetoothHeadset ||
            input.hasGuardCommunicationHold ||
            input.suspendedByCall
        ) {
            return ClassicBluetoothSoftGuardSyncDecision(shouldRun = false)
        }

        val purpose = when {
            input.pollingPhase == RoutePollingPhase.CLASSIC_BLUETOOTH_CONFIRM ->
                ClassicBluetoothSoftGuardPurpose.PASSIVE_CONFIRM

            input.hasStartupObservation ->
                ClassicBluetoothSoftGuardPurpose.STARTUP_OBSERVE

            input.pollingPhase == RoutePollingPhase.RELEASE_PROBE &&
                input.manualReleaseInProgress ->
                ClassicBluetoothSoftGuardPurpose.MANUAL_RELEASE

            else -> null
        }

        return ClassicBluetoothSoftGuardSyncDecision(
            shouldRun = purpose != null,
            purpose = purpose,
        )
    }
}
