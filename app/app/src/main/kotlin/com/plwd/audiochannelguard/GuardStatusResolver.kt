package com.plwd.audiochannelguard

internal enum class RoutePollingPhase {
    IDLE,
    CLASSIC_BLUETOOTH_CONFIRM,
    CLEAR_PROBE,
    RECOVERY_WINDOW,
    RELEASE_PROBE,
}

internal enum class CommunicationDeviceKind {
    NONE,
    BUILTIN,
    EXTERNAL,
}

internal data class RouteSnapshot(
    val hasHeadset: Boolean,
    val communicationDeviceKind: CommunicationDeviceKind,
    val pollingPhase: RoutePollingPhase,
    val lastReportedStatus: GuardStatus,
    val isClassicBluetoothPassiveCandidate: Boolean,
    val hasRecentBuiltinRouteEvidence: Boolean,
    val hasClassicBluetoothStartupObservation: Boolean,
    val hasActiveHeldRoute: Boolean,
    val hasGuardCommunicationHold: Boolean,
)

internal object GuardStatusResolver {
    fun resolve(snapshot: RouteSnapshot): GuardStatus {
        if (!snapshot.hasHeadset) {
            return GuardStatus.NO_HEADSET
        }

        if (snapshot.pollingPhase == RoutePollingPhase.CLEAR_PROBE) {
            return GuardStatus.HIJACKED
        }

        if (snapshot.communicationDeviceKind == CommunicationDeviceKind.BUILTIN) {
            if (
                snapshot.isClassicBluetoothPassiveCandidate &&
                (
                    !snapshot.hasRecentBuiltinRouteEvidence ||
                        snapshot.hasClassicBluetoothStartupObservation
                    )
            ) {
                return GuardStatus.NORMAL
            }

            if (
                snapshot.lastReportedStatus == GuardStatus.FIXED &&
                snapshot.pollingPhase == RoutePollingPhase.IDLE
            ) {
                return GuardStatus.FIXED_BUT_SPEAKER_ROUTE
            }

            return GuardStatus.HIJACKED
        }

        if (snapshot.hasActiveHeldRoute && snapshot.hasGuardCommunicationHold) {
            return GuardStatus.FIXED
        }

        if (snapshot.lastReportedStatus == GuardStatus.FIXED) {
            return GuardStatus.FIXED
        }

        return GuardStatus.NORMAL
    }
}
