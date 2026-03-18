package com.plwd.audiochannelguard

enum class FixEventLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR,
}

enum class FixEventCode {
    GENERAL,
    GUARD_STARTED,
    GUARD_STOPPED,
    MODE_SUSPENDED_BY_CALL,
    HEADSET_CONNECTED,
    HEADSET_DISCONNECTED,
    CLASSIC_BLUETOOTH_OBSERVING,
    CLASSIC_BLUETOOTH_OBSERVE_STOPPED,
    CLEAR_PROBE_STARTED,
    CLEAR_PROBE_STOPPED,
    RECOVERY_WINDOW_STARTED,
    RECOVERY_WINDOW_STOPPED,
    RELEASE_PROBE_STARTED,
    RELEASE_PROBE_STOPPED,
    HELD_ROUTE_ENTERED,
    HELD_ROUTE_RECLAIMED,
    COMMUNICATION_MODE_ACQUIRED,
    COMMUNICATION_MODE_RELEASED,
    COMMUNICATION_MODE_FAILED,
    RESTORE_REQUESTED,
    RESTORE_SKIPPED,
    RESTORE_SUCCEEDED,
    RESTORE_REJECTED,
    RESTORE_FAILED,
    WIDEBAND_HINT_SUCCEEDED,
    WIDEBAND_HINT_FAILED,
    SOFT_GUARD_STARTED,
    SOFT_GUARD_STOPPED,
    SOFT_GUARD_START_FAILED,
    SOFT_GUARD_SKIPPED,
    SOFT_GUARD_ESCALATED,
}

data class FixEventSnapshot(
    val status: GuardStatus,
    val enhancedState: EnhancedState,
    val pollingPhase: RoutePollingPhase,
    val communicationDeviceKind: CommunicationDeviceKind,
    val headsetName: String?,
    val communicationDeviceName: String?,
    val hasHeldRoute: Boolean,
    val heldRouteManualReleaseInProgress: Boolean,
)

data class FixEvent(
    val timestamp: Long,
    val code: FixEventCode,
    val level: FixEventLevel,
    val message: String,
    val snapshot: FixEventSnapshot? = null,
)
