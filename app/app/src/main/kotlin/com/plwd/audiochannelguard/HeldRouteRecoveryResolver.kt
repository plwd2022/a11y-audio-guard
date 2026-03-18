package com.plwd.audiochannelguard

internal data class HeldRouteRecoveryDecisionInput(
    val hasGuardCommunicationHold: Boolean,
    val hasActiveHeldRoute: Boolean,
    val communicationDeviceIsClassicBluetooth: Boolean,
    val communicationDeviceMatchesAvailableHeadset: Boolean,
)

internal enum class HeldRouteRecoveryOutcome {
    DO_NOT_KEEP_HELD_ROUTE,
    KEEP_HELD_ROUTE,
}

internal data class HeldRouteRecoveryDecision(
    val outcome: HeldRouteRecoveryOutcome,
)

internal object HeldRouteRecoveryResolver {
    fun resolve(input: HeldRouteRecoveryDecisionInput): HeldRouteRecoveryDecision {
        if (!input.hasGuardCommunicationHold) {
            return HeldRouteRecoveryDecision(
                outcome = HeldRouteRecoveryOutcome.DO_NOT_KEEP_HELD_ROUTE,
            )
        }

        if (input.hasActiveHeldRoute) {
            return HeldRouteRecoveryDecision(
                outcome = HeldRouteRecoveryOutcome.KEEP_HELD_ROUTE,
            )
        }

        if (
            input.communicationDeviceIsClassicBluetooth &&
            input.communicationDeviceMatchesAvailableHeadset
        ) {
            return HeldRouteRecoveryDecision(
                outcome = HeldRouteRecoveryOutcome.KEEP_HELD_ROUTE,
            )
        }

        return HeldRouteRecoveryDecision(
            outcome = HeldRouteRecoveryOutcome.DO_NOT_KEEP_HELD_ROUTE,
        )
    }
}
