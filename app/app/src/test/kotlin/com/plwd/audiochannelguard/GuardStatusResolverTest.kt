package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Test

class GuardStatusResolverTest {

    @Test
    fun `returns no headset when headset is unavailable`() {
        val status = GuardStatusResolver.resolve(
            snapshot(hasHeadset = false, pollingPhase = RoutePollingPhase.CLEAR_PROBE)
        )

        assertEquals(GuardStatus.NO_HEADSET, status)
    }

    @Test
    fun `returns hijacked while clear probe is active`() {
        val status = GuardStatusResolver.resolve(
            snapshot(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL,
                pollingPhase = RoutePollingPhase.CLEAR_PROBE
            )
        )

        assertEquals(GuardStatus.HIJACKED, status)
    }

    @Test
    fun `treats classic bluetooth builtin route as normal before hijack evidence is confirmed`() {
        val status = GuardStatusResolver.resolve(
            snapshot(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                isClassicBluetoothPassiveCandidate = true,
                hasRecentBuiltinRouteEvidence = false
            )
        )

        assertEquals(GuardStatus.NORMAL, status)
    }

    @Test
    fun `treats startup observation window as normal even with recent builtin evidence`() {
        val status = GuardStatusResolver.resolve(
            snapshot(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                isClassicBluetoothPassiveCandidate = true,
                hasRecentBuiltinRouteEvidence = true,
                hasClassicBluetoothStartupObservation = true
            )
        )

        assertEquals(GuardStatus.NORMAL, status)
    }

    @Test
    fun `returns fixed but speaker route after recent fix on builtin route`() {
        val status = GuardStatusResolver.resolve(
            snapshot(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                lastReportedStatus = GuardStatus.FIXED
            )
        )

        assertEquals(GuardStatus.FIXED_BUT_SPEAKER_ROUTE, status)
    }

    @Test
    fun `returns hijacked for builtin route without passive classic bluetooth allowance`() {
        val status = GuardStatusResolver.resolve(
            snapshot(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                hasRecentBuiltinRouteEvidence = true
            )
        )

        assertEquals(GuardStatus.HIJACKED, status)
    }

    @Test
    fun `returns fixed when held route is active and guard still owns the route`() {
        val status = GuardStatusResolver.resolve(
            snapshot(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL,
                hasActiveHeldRoute = true,
                hasGuardCommunicationHold = true
            )
        )

        assertEquals(GuardStatus.FIXED, status)
    }

    @Test
    fun `keeps fixed status after restore when route stays stable`() {
        val status = GuardStatusResolver.resolve(
            snapshot(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL,
                lastReportedStatus = GuardStatus.FIXED
            )
        )

        assertEquals(GuardStatus.FIXED, status)
    }

    @Test
    fun `returns normal for stable external route with no held state`() {
        val status = GuardStatusResolver.resolve(
            snapshot(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL
            )
        )

        assertEquals(GuardStatus.NORMAL, status)
    }

    private fun snapshot(
        hasHeadset: Boolean = true,
        communicationDeviceKind: CommunicationDeviceKind = CommunicationDeviceKind.NONE,
        pollingPhase: RoutePollingPhase = RoutePollingPhase.IDLE,
        lastReportedStatus: GuardStatus = GuardStatus.NORMAL,
        isClassicBluetoothPassiveCandidate: Boolean = false,
        hasRecentBuiltinRouteEvidence: Boolean = false,
        hasClassicBluetoothStartupObservation: Boolean = false,
        hasActiveHeldRoute: Boolean = false,
        hasGuardCommunicationHold: Boolean = false,
    ): RouteSnapshot {
        return RouteSnapshot(
            hasHeadset = hasHeadset,
            communicationDeviceKind = communicationDeviceKind,
            pollingPhase = pollingPhase,
            lastReportedStatus = lastReportedStatus,
            isClassicBluetoothPassiveCandidate = isClassicBluetoothPassiveCandidate,
            hasRecentBuiltinRouteEvidence = hasRecentBuiltinRouteEvidence,
            hasClassicBluetoothStartupObservation = hasClassicBluetoothStartupObservation,
            hasActiveHeldRoute = hasActiveHeldRoute,
            hasGuardCommunicationHold = hasGuardCommunicationHold,
        )
    }
}
