package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassicBluetoothConfirmResolverTest {

    @Test
    fun `stops with no headset when passive confirm target disappears`() {
        val decision = ClassicBluetoothConfirmResolver.resolve(
            input(hasHeadset = false)
        )

        assertEquals(ClassicBluetoothConfirmOutcome.STOP_NO_HEADSET, decision.outcome)
        assertEquals(0, decision.nextHitCount)
    }

    @Test
    fun `stops when passive confirmation conditions change`() {
        val decision = ClassicBluetoothConfirmResolver.resolve(
            input(
                hasHeadset = true,
                passiveConfirmationAllowed = false,
                confirmHitCount = 2
            )
        )

        assertEquals(ClassicBluetoothConfirmOutcome.STOP_CONDITIONS_CHANGED, decision.outcome)
        assertEquals(2, decision.nextHitCount)
    }

    @Test
    fun `stops when communication route recovers to non builtin`() {
        val decision = ClassicBluetoothConfirmResolver.resolve(
            input(
                hasHeadset = true,
                passiveConfirmationAllowed = true,
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL,
                confirmHitCount = 1
            )
        )

        assertEquals(ClassicBluetoothConfirmOutcome.STOP_ROUTE_RECOVERED, decision.outcome)
        assertEquals(0, decision.nextHitCount)
    }

    @Test
    fun `soft guard observation keeps waiting before timeout`() {
        val decision = ClassicBluetoothConfirmResolver.resolve(
            input(
                hasHeadset = true,
                passiveConfirmationAllowed = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                softGuardObservationActive = true,
                timedOut = false,
                confirmHitCount = 1
            )
        )

        assertEquals(
            ClassicBluetoothConfirmOutcome.CONTINUE_SOFT_GUARD_OBSERVATION,
            decision.outcome
        )
        assertEquals(1, decision.nextHitCount)
    }

    @Test
    fun `soft guard timeout wins before hit threshold`() {
        val decision = ClassicBluetoothConfirmResolver.resolve(
            input(
                hasHeadset = true,
                passiveConfirmationAllowed = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                softGuardObservationActive = true,
                timedOut = true,
                confirmHitCount = 2,
                requiredHits = 3
            )
        )

        assertEquals(ClassicBluetoothConfirmOutcome.STOP_SOFT_GUARD_TIMEOUT, decision.outcome)
        assertEquals(2, decision.nextHitCount)
    }

    @Test
    fun `confirms hijack when hit threshold is reached without soft guard observation`() {
        val decision = ClassicBluetoothConfirmResolver.resolve(
            input(
                hasHeadset = true,
                passiveConfirmationAllowed = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                softGuardObservationActive = false,
                timedOut = false,
                confirmHitCount = 2,
                requiredHits = 3
            )
        )

        assertEquals(ClassicBluetoothConfirmOutcome.CONFIRM_HIJACK, decision.outcome)
        assertEquals(3, decision.nextHitCount)
    }

    @Test
    fun `hit threshold still wins over timeout without soft guard observation`() {
        val decision = ClassicBluetoothConfirmResolver.resolve(
            input(
                hasHeadset = true,
                passiveConfirmationAllowed = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                softGuardObservationActive = false,
                timedOut = true,
                confirmHitCount = 2,
                requiredHits = 3
            )
        )

        assertEquals(ClassicBluetoothConfirmOutcome.CONFIRM_HIJACK, decision.outcome)
        assertEquals(3, decision.nextHitCount)
    }

    @Test
    fun `stops on timeout when threshold is not reached`() {
        val decision = ClassicBluetoothConfirmResolver.resolve(
            input(
                hasHeadset = true,
                passiveConfirmationAllowed = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                softGuardObservationActive = false,
                timedOut = true,
                confirmHitCount = 1,
                requiredHits = 3
            )
        )

        assertEquals(ClassicBluetoothConfirmOutcome.STOP_TIMEOUT, decision.outcome)
        assertEquals(2, decision.nextHitCount)
    }

    @Test
    fun `continues confirming while accumulating hits`() {
        val decision = ClassicBluetoothConfirmResolver.resolve(
            input(
                hasHeadset = true,
                passiveConfirmationAllowed = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                softGuardObservationActive = false,
                timedOut = false,
                confirmHitCount = 1,
                requiredHits = 3
            )
        )

        assertEquals(ClassicBluetoothConfirmOutcome.CONTINUE_CONFIRMING, decision.outcome)
        assertEquals(2, decision.nextHitCount)
    }

    private fun input(
        hasHeadset: Boolean = false,
        passiveConfirmationAllowed: Boolean = false,
        communicationDeviceKind: CommunicationDeviceKind = CommunicationDeviceKind.NONE,
        softGuardObservationActive: Boolean = false,
        timedOut: Boolean = false,
        confirmHitCount: Int = 0,
        requiredHits: Int = 3,
    ): ClassicBluetoothConfirmDecisionInput {
        return ClassicBluetoothConfirmDecisionInput(
            hasHeadset = hasHeadset,
            passiveConfirmationAllowed = passiveConfirmationAllowed,
            communicationDeviceKind = communicationDeviceKind,
            softGuardObservationActive = softGuardObservationActive,
            timedOut = timedOut,
            confirmHitCount = confirmHitCount,
            requiredHits = requiredHits,
        )
    }
}
