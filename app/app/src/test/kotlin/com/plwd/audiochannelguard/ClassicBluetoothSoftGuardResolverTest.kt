package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassicBluetoothSoftGuardResolverTest {

    @Test
    fun `ignores when monitor is not in idle classic bluetooth observe context`() {
        val decision = ClassicBluetoothSoftGuardResolver.resolve(
            input(
                monitorRunning = true,
                softGuardEnabled = true,
                softGuardRunning = true,
                pollingPhase = RoutePollingPhase.RECOVERY_WINDOW,
                hasHeadset = true,
                isClassicBluetoothHeadset = true,
                routedDeviceKind = CommunicationDeviceKind.BUILTIN
            )
        )

        assertEquals(ClassicBluetoothSoftGuardOutcome.IGNORE, decision.outcome)
    }

    @Test
    fun `ignores when routed device is not builtin`() {
        val decision = ClassicBluetoothSoftGuardResolver.resolve(
            input(
                monitorRunning = true,
                softGuardEnabled = true,
                softGuardRunning = true,
                hasHeadset = true,
                isClassicBluetoothHeadset = true,
                routedDeviceKind = CommunicationDeviceKind.EXTERNAL
            )
        )

        assertEquals(ClassicBluetoothSoftGuardOutcome.IGNORE, decision.outcome)
    }

    @Test
    fun `returns passive skip when builtin route has no recent hijack evidence`() {
        val decision = ClassicBluetoothSoftGuardResolver.resolve(
            input(
                monitorRunning = true,
                softGuardEnabled = true,
                softGuardRunning = true,
                hasHeadset = true,
                isClassicBluetoothHeadset = true,
                routedDeviceKind = CommunicationDeviceKind.BUILTIN,
                hasRecentBuiltinRouteEvidence = false
            )
        )

        assertEquals(ClassicBluetoothSoftGuardOutcome.PASSIVE_SKIP, decision.outcome)
    }

    @Test
    fun `waits for verify delay before escalating`() {
        val decision = ClassicBluetoothSoftGuardResolver.resolve(
            input(
                monitorRunning = true,
                softGuardEnabled = true,
                softGuardRunning = true,
                hasHeadset = true,
                isClassicBluetoothHeadset = true,
                routedDeviceKind = CommunicationDeviceKind.BUILTIN,
                hasRecentBuiltinRouteEvidence = true,
                hasWaitedForVerifyDelay = false
            )
        )

        assertEquals(ClassicBluetoothSoftGuardOutcome.WAIT_FOR_VERIFY_DELAY, decision.outcome)
    }

    @Test
    fun `waits for escalation cooldown after recent hard reclaim`() {
        val decision = ClassicBluetoothSoftGuardResolver.resolve(
            input(
                monitorRunning = true,
                softGuardEnabled = true,
                softGuardRunning = true,
                hasHeadset = true,
                isClassicBluetoothHeadset = true,
                routedDeviceKind = CommunicationDeviceKind.BUILTIN,
                hasRecentBuiltinRouteEvidence = true,
                hasWaitedForVerifyDelay = true,
                isEscalationCooldownElapsed = false
            )
        )

        assertEquals(
            ClassicBluetoothSoftGuardOutcome.WAIT_FOR_ESCALATION_COOLDOWN,
            decision.outcome
        )
    }

    @Test
    fun `escalates when classic bluetooth observe context confirms builtin route`() {
        val decision = ClassicBluetoothSoftGuardResolver.resolve(
            input(
                monitorRunning = true,
                softGuardEnabled = true,
                softGuardRunning = true,
                hasHeadset = true,
                isClassicBluetoothHeadset = true,
                routedDeviceKind = CommunicationDeviceKind.BUILTIN,
                hasRecentBuiltinRouteEvidence = true,
                hasWaitedForVerifyDelay = true,
                isEscalationCooldownElapsed = true
            )
        )

        assertEquals(ClassicBluetoothSoftGuardOutcome.ESCALATE, decision.outcome)
    }

    private fun input(
        monitorRunning: Boolean = false,
        softGuardEnabled: Boolean = false,
        softGuardRunning: Boolean = false,
        pollingPhase: RoutePollingPhase = RoutePollingPhase.IDLE,
        hasGuardCommunicationHold: Boolean = false,
        suspendedByCall: Boolean = false,
        hasHeadset: Boolean = false,
        isClassicBluetoothHeadset: Boolean = false,
        routedDeviceKind: CommunicationDeviceKind = CommunicationDeviceKind.NONE,
        hasRecentBuiltinRouteEvidence: Boolean = false,
        hasWaitedForVerifyDelay: Boolean = true,
        isEscalationCooldownElapsed: Boolean = true,
    ): ClassicBluetoothSoftGuardDecisionInput {
        return ClassicBluetoothSoftGuardDecisionInput(
            monitorRunning = monitorRunning,
            softGuardEnabled = softGuardEnabled,
            softGuardRunning = softGuardRunning,
            pollingPhase = pollingPhase,
            hasGuardCommunicationHold = hasGuardCommunicationHold,
            suspendedByCall = suspendedByCall,
            hasHeadset = hasHeadset,
            isClassicBluetoothHeadset = isClassicBluetoothHeadset,
            routedDeviceKind = routedDeviceKind,
            hasRecentBuiltinRouteEvidence = hasRecentBuiltinRouteEvidence,
            hasWaitedForVerifyDelay = hasWaitedForVerifyDelay,
            isEscalationCooldownElapsed = isEscalationCooldownElapsed,
        )
    }
}
