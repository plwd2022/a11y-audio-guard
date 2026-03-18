package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClearProbeResolverTest {

    @Test
    fun `stops when headset disappears during clear probe`() {
        val decision = ClearProbeResolver.resolve(
            input(hasHeadset = false)
        )

        assertEquals(ClearProbeOutcome.STOP_NO_HEADSET, decision.outcome)
    }

    @Test
    fun `finishes successfully when system restores headset naturally`() {
        val decision = ClearProbeResolver.resolve(
            input(hasHeadset = true, restoredNaturally = true)
        )

        assertEquals(ClearProbeOutcome.FINISH_RESTORED_NATURALLY, decision.outcome)
    }

    @Test
    fun `forces restore when route remains on builtin device`() {
        val decision = ClearProbeResolver.resolve(
            input(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN
            )
        )

        assertEquals(ClearProbeOutcome.FORCE_RESTORE_BUILTIN_ROUTE, decision.outcome)
    }

    @Test
    fun `builtin route takes precedence over timeout`() {
        val decision = ClearProbeResolver.resolve(
            input(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                timedOut = true
            )
        )

        assertEquals(ClearProbeOutcome.FORCE_RESTORE_BUILTIN_ROUTE, decision.outcome)
    }

    @Test
    fun `finishes as normal when observation window expires without builtin route`() {
        val decision = ClearProbeResolver.resolve(
            input(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.NONE,
                timedOut = true
            )
        )

        assertEquals(ClearProbeOutcome.FINISH_NORMAL, decision.outcome)
    }

    @Test
    fun `continues polling while waiting for route to settle`() {
        val decision = ClearProbeResolver.resolve(
            input(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.NONE,
                timedOut = false
            )
        )

        assertEquals(ClearProbeOutcome.CONTINUE_POLLING, decision.outcome)
    }

    private fun input(
        hasHeadset: Boolean,
        restoredNaturally: Boolean = false,
        communicationDeviceKind: CommunicationDeviceKind = CommunicationDeviceKind.NONE,
        timedOut: Boolean = false,
    ): ClearProbeDecisionInput {
        return ClearProbeDecisionInput(
            hasHeadset = hasHeadset,
            restoredNaturally = restoredNaturally,
            communicationDeviceKind = communicationDeviceKind,
            timedOut = timedOut,
        )
    }
}
