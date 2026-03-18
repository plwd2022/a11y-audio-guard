package com.plwd.audiochannelguard

internal data class MonitorSnapshot(
    val hasHeadset: Boolean,
    val headsetName: String?,
    val communicationDeviceKind: CommunicationDeviceKind,
    val communicationDeviceName: String?,
    val pollingPhase: RoutePollingPhase,
    val lastReportedStatus: GuardStatus,
    val enhancedModeEnabled: Boolean,
    val enhancedState: EnhancedState,
    val isClassicBluetoothPassiveCandidate: Boolean,
    val hasRecentBuiltinRouteEvidence: Boolean,
    val hasClassicBluetoothStartupObservation: Boolean,
    val hasActiveHeldRoute: Boolean,
    val hasGuardCommunicationHold: Boolean,
    val heldRouteMessage: String?,
    val canManuallyReleaseHeldRoute: Boolean,
    val heldRouteManualReleaseInProgress: Boolean,
)

internal object MonitorSnapshotProjector {
    fun routeSnapshot(snapshot: MonitorSnapshot): RouteSnapshot {
        return RouteSnapshot(
            hasHeadset = snapshot.hasHeadset,
            communicationDeviceKind = snapshot.communicationDeviceKind,
            pollingPhase = snapshot.pollingPhase,
            lastReportedStatus = snapshot.lastReportedStatus,
            isClassicBluetoothPassiveCandidate = snapshot.isClassicBluetoothPassiveCandidate,
            hasRecentBuiltinRouteEvidence = snapshot.hasRecentBuiltinRouteEvidence,
            hasClassicBluetoothStartupObservation = snapshot.hasClassicBluetoothStartupObservation,
            hasActiveHeldRoute = snapshot.hasActiveHeldRoute,
            hasGuardCommunicationHold = snapshot.hasGuardCommunicationHold,
        )
    }

    fun resolvedStatus(snapshot: MonitorSnapshot): GuardStatus {
        return GuardStatusResolver.resolve(routeSnapshot(snapshot))
    }

    fun publicProjectionInput(
        snapshot: MonitorSnapshot,
        statusOverride: GuardStatus? = null,
    ): GuardPublicProjectionInput {
        return GuardPublicProjectionInput(
            status = statusOverride ?: resolvedStatus(snapshot),
            enhancedState = effectiveEnhancedState(snapshot),
            enhancedModeEnabled = snapshot.enhancedModeEnabled,
            headsetName = snapshot.headsetName,
            heldRouteMessage = snapshot.heldRouteMessage,
            canManuallyReleaseHeldRoute = snapshot.canManuallyReleaseHeldRoute,
        )
    }

    fun fixEventSnapshot(snapshot: MonitorSnapshot): FixEventSnapshot {
        return FixEventSnapshot(
            status = resolvedStatus(snapshot),
            enhancedState = effectiveEnhancedState(snapshot),
            pollingPhase = snapshot.pollingPhase,
            communicationDeviceKind = snapshot.communicationDeviceKind,
            headsetName = snapshot.headsetName,
            communicationDeviceName = snapshot.communicationDeviceName,
            hasHeldRoute = snapshot.hasActiveHeldRoute,
            heldRouteManualReleaseInProgress = snapshot.heldRouteManualReleaseInProgress,
        )
    }

    private fun effectiveEnhancedState(snapshot: MonitorSnapshot): EnhancedState {
        return if (snapshot.enhancedModeEnabled) {
            snapshot.enhancedState
        } else {
            EnhancedState.DISABLED
        }
    }
}
