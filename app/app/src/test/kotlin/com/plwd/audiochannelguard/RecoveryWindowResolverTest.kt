package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryWindowResolverTest {

    @Test
    fun `stops immediately when recovery window timed out`() {
        val decision = RecoveryWindowResolver.resolve(
            input(timedOut = true, stableHitCount = 2)
        )

        assertEquals(RecoveryWindowOutcome.STOP_TIMEOUT, decision.outcome)
        assertEquals(0, decision.nextStableHitCount)
    }

    @Test
    fun `stops with no headset when headset disappears`() {
        val decision = RecoveryWindowResolver.resolve(
            input(hasHeadset = false, stableHitCount = 1)
        )

        assertEquals(RecoveryWindowOutcome.STOP_NO_HEADSET, decision.outcome)
        assertEquals(0, decision.nextStableHitCount)
    }

    @Test
    fun `switches to classic bluetooth confirm for builtin route on passive candidate`() {
        val decision = RecoveryWindowResolver.resolve(
            input(
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                isClassicBluetoothPassiveCandidate = true,
                stableHitCount = 1
            )
        )

        assertEquals(RecoveryWindowOutcome.START_CLASSIC_BLUETOOTH_CONFIRM, decision.outcome)
        assertEquals(0, decision.nextStableHitCount)
    }

    @Test
    fun `restores immediately for builtin route without passive classic bluetooth allowance`() {
        val decision = RecoveryWindowResolver.resolve(
            input(
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                isClassicBluetoothPassiveCandidate = false,
                stableHitCount = 1
            )
        )

        assertEquals(RecoveryWindowOutcome.RESTORE_BUILTIN_ROUTE, decision.outcome)
        assertEquals(0, decision.nextStableHitCount)
    }

    @Test
    fun `increments stable hit count while route stays stable below threshold`() {
        val decision = RecoveryWindowResolver.resolve(
            input(
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL,
                communicationDeviceMatchesHeadset = true,
                stableHitCount = 1,
                requiredStableHits = 3
            )
        )

        assertEquals(RecoveryWindowOutcome.CONTINUE_POLLING, decision.outcome)
        assertEquals(2, decision.nextStableHitCount)
    }

    @Test
    fun `enters held route when stable threshold is reached and route should remain pinned`() {
        val decision = RecoveryWindowResolver.resolve(
            input(
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL,
                communicationDeviceMatchesHeadset = true,
                stableHitCount = 2,
                requiredStableHits = 3,
                shouldKeepHeldRouteAfterRecovery = true,
                hasGuardCommunicationHold = true
            )
        )

        assertEquals(RecoveryWindowOutcome.ENTER_HELD_ROUTE, decision.outcome)
        assertEquals(3, decision.nextStableHitCount)
    }

    @Test
    fun `starts release probe when stable threshold is reached and guard still owns route`() {
        val decision = RecoveryWindowResolver.resolve(
            input(
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL,
                communicationDeviceMatchesHeadset = true,
                stableHitCount = 2,
                requiredStableHits = 3,
                hasGuardCommunicationHold = true
            )
        )

        assertEquals(RecoveryWindowOutcome.START_RELEASE_PROBE, decision.outcome)
        assertEquals(3, decision.nextStableHitCount)
    }

    @Test
    fun `finishes as normal when stable threshold is reached without guard hold`() {
        val decision = RecoveryWindowResolver.resolve(
            input(
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL,
                communicationDeviceMatchesHeadset = true,
                stableHitCount = 2,
                requiredStableHits = 3,
                hasGuardCommunicationHold = false
            )
        )

        assertEquals(RecoveryWindowOutcome.FINISH_NORMAL, decision.outcome)
        assertEquals(3, decision.nextStableHitCount)
    }

    @Test
    fun `resets stable hit count when route is not yet stable`() {
        val decision = RecoveryWindowResolver.resolve(
            input(
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL,
                communicationDeviceMatchesHeadset = false,
                stableHitCount = 2
            )
        )

        assertEquals(RecoveryWindowOutcome.CONTINUE_POLLING, decision.outcome)
        assertEquals(0, decision.nextStableHitCount)
    }

    private fun input(
        timedOut: Boolean = false,
        hasHeadset: Boolean = true,
        communicationDeviceKind: CommunicationDeviceKind = CommunicationDeviceKind.NONE,
        communicationDeviceMatchesHeadset: Boolean = false,
        stableHitCount: Int = 0,
        requiredStableHits: Int = 3,
        isClassicBluetoothPassiveCandidate: Boolean = false,
        shouldKeepHeldRouteAfterRecovery: Boolean = false,
        hasGuardCommunicationHold: Boolean = false,
    ): RecoveryWindowDecisionInput {
        return RecoveryWindowDecisionInput(
            timedOut = timedOut,
            hasHeadset = hasHeadset,
            communicationDeviceKind = communicationDeviceKind,
            communicationDeviceMatchesHeadset = communicationDeviceMatchesHeadset,
            stableHitCount = stableHitCount,
            requiredStableHits = requiredStableHits,
            isClassicBluetoothPassiveCandidate = isClassicBluetoothPassiveCandidate,
            shouldKeepHeldRouteAfterRecovery = shouldKeepHeldRouteAfterRecovery,
            hasGuardCommunicationHold = hasGuardCommunicationHold,
        )
    }
}
