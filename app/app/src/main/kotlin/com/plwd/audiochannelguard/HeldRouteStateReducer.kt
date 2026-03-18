package com.plwd.audiochannelguard

internal enum class HeldRouteKind {
    CLASSIC_BLUETOOTH,
    HEADSET,
}

internal data class HeldRouteState(
    val active: Boolean = false,
    val manualReleaseInProgress: Boolean = false,
    val headsetKey: String? = null,
    val kind: HeldRouteKind? = null,
    val message: String? = null,
)

internal object HeldRouteStateReducer {
    fun enter(headsetKey: String?, kind: HeldRouteKind, message: String): HeldRouteState {
        return HeldRouteState(
            active = true,
            manualReleaseInProgress = false,
            headsetKey = headsetKey,
            kind = kind,
            message = message,
        )
    }

    fun startManualRelease(
        headsetKey: String?,
        kind: HeldRouteKind,
        message: String,
    ): HeldRouteState {
        return HeldRouteState(
            active = true,
            manualReleaseInProgress = true,
            headsetKey = headsetKey,
            kind = kind,
            message = message,
        )
    }

    fun reclaim(headsetKey: String?, kind: HeldRouteKind, message: String): HeldRouteState {
        return enter(headsetKey, kind, message)
    }

    fun clear(): HeldRouteState {
        return HeldRouteState()
    }

    fun syncTracking(
        currentState: HeldRouteState,
        shouldTrack: Boolean,
        headsetKey: String?,
        kind: HeldRouteKind,
    ): HeldRouteState {
        if (!shouldTrack) {
            return currentState
        }

        return currentState.copy(
            headsetKey = headsetKey,
            kind = kind,
        )
    }

    fun currentMessage(
        state: HeldRouteState,
        hasActiveHeldRoute: Boolean,
    ): String? {
        return if (state.manualReleaseInProgress || hasActiveHeldRoute) {
            state.message
        } else {
            null
        }
    }

    fun canManualRelease(
        state: HeldRouteState,
        hasHeadset: Boolean,
        hasActiveHeldRoute: Boolean,
    ): Boolean {
        return hasHeadset &&
            hasActiveHeldRoute &&
            !state.manualReleaseInProgress
    }
}
