package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Test

class HeldRouteRecoveryResolverTest {

    @Test
    fun `does not keep held route when guard no longer owns communication control`() {
        val decision = HeldRouteRecoveryResolver.resolve(
            input(hasGuardCommunicationHold = false, hasActiveHeldRoute = true)
        )

        assertEquals(HeldRouteRecoveryOutcome.DO_NOT_KEEP_HELD_ROUTE, decision.outcome)
    }

    @Test
    fun `keeps held route when it is already active and guard still owns communication control`() {
        val decision = HeldRouteRecoveryResolver.resolve(
            input(hasActiveHeldRoute = true)
        )

        assertEquals(HeldRouteRecoveryOutcome.KEEP_HELD_ROUTE, decision.outcome)
    }

    @Test
    fun `does not keep held route for non classic bluetooth communication routes`() {
        val decision = HeldRouteRecoveryResolver.resolve(
            input(communicationDeviceIsClassicBluetooth = false)
        )

        assertEquals(HeldRouteRecoveryOutcome.DO_NOT_KEEP_HELD_ROUTE, decision.outcome)
    }

    @Test
    fun `does not keep held route when classic bluetooth route does not match available headset`() {
        val decision = HeldRouteRecoveryResolver.resolve(
            input(
                communicationDeviceIsClassicBluetooth = true,
                communicationDeviceMatchesAvailableHeadset = false
            )
        )

        assertEquals(HeldRouteRecoveryOutcome.DO_NOT_KEEP_HELD_ROUTE, decision.outcome)
    }

    @Test
    fun `keeps held route when classic bluetooth communication route matches available headset`() {
        val decision = HeldRouteRecoveryResolver.resolve(
            input(
                communicationDeviceIsClassicBluetooth = true,
                communicationDeviceMatchesAvailableHeadset = true
            )
        )

        assertEquals(HeldRouteRecoveryOutcome.KEEP_HELD_ROUTE, decision.outcome)
    }

    private fun input(
        hasGuardCommunicationHold: Boolean = true,
        hasActiveHeldRoute: Boolean = false,
        communicationDeviceIsClassicBluetooth: Boolean = false,
        communicationDeviceMatchesAvailableHeadset: Boolean = false,
    ): HeldRouteRecoveryDecisionInput {
        return HeldRouteRecoveryDecisionInput(
            hasGuardCommunicationHold = hasGuardCommunicationHold,
            hasActiveHeldRoute = hasActiveHeldRoute,
            communicationDeviceIsClassicBluetooth = communicationDeviceIsClassicBluetooth,
            communicationDeviceMatchesAvailableHeadset = communicationDeviceMatchesAvailableHeadset,
        )
    }
}
