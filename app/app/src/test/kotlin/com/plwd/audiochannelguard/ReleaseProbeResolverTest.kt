package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseProbeResolverTest {

    @Test
    fun `stops when headset disappears during release probe`() {
        val decision = ReleaseProbeResolver.resolve(
            input(hasHeadset = false)
        )

        assertEquals(ReleaseProbeOutcome.STOP_NO_HEADSET, decision.outcome)
    }

    @Test
    fun `handles builtin route immediately during release probe`() {
        val decision = ReleaseProbeResolver.resolve(
            input(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN
            )
        )

        assertEquals(ReleaseProbeOutcome.HANDLE_BUILTIN_ROUTE, decision.outcome)
    }

    @Test
    fun `builtin route still takes precedence when timeout is reached`() {
        val decision = ReleaseProbeResolver.resolve(
            input(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                timedOut = true
            )
        )

        assertEquals(ReleaseProbeOutcome.HANDLE_BUILTIN_ROUTE, decision.outcome)
    }

    @Test
    fun `finishes normally when deadline expires without builtin route`() {
        val decision = ReleaseProbeResolver.resolve(
            input(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL,
                timedOut = true
            )
        )

        assertEquals(ReleaseProbeOutcome.FINISH_NORMAL, decision.outcome)
    }

    @Test
    fun `continues polling while waiting for release probe result`() {
        val decision = ReleaseProbeResolver.resolve(
            input(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL,
                timedOut = false
            )
        )

        assertEquals(ReleaseProbeOutcome.CONTINUE_POLLING, decision.outcome)
    }

    private fun input(
        hasHeadset: Boolean,
        communicationDeviceKind: CommunicationDeviceKind = CommunicationDeviceKind.NONE,
        timedOut: Boolean = false,
    ): ReleaseProbeDecisionInput {
        return ReleaseProbeDecisionInput(
            hasHeadset = hasHeadset,
            communicationDeviceKind = communicationDeviceKind,
            timedOut = timedOut,
        )
    }
}
