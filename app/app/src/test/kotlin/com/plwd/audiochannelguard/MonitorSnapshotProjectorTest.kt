package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitorSnapshotProjectorTest {

    @Test
    fun `route snapshot projection preserves routing-related fields`() {
        val routeSnapshot = MonitorSnapshotProjector.routeSnapshot(
            snapshot(
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                pollingPhase = RoutePollingPhase.CLEAR_PROBE,
                lastReportedStatus = GuardStatus.FIXED,
                isClassicBluetoothPassiveCandidate = true,
                hasRecentBuiltinRouteEvidence = true,
                hasClassicBluetoothStartupObservation = true,
                hasActiveHeldRoute = true,
                hasGuardCommunicationHold = true
            )
        )

        assertEquals(CommunicationDeviceKind.BUILTIN, routeSnapshot.communicationDeviceKind)
        assertEquals(RoutePollingPhase.CLEAR_PROBE, routeSnapshot.pollingPhase)
        assertEquals(GuardStatus.FIXED, routeSnapshot.lastReportedStatus)
        assertTrue(routeSnapshot.isClassicBluetoothPassiveCandidate)
        assertTrue(routeSnapshot.hasRecentBuiltinRouteEvidence)
        assertTrue(routeSnapshot.hasClassicBluetoothStartupObservation)
        assertTrue(routeSnapshot.hasActiveHeldRoute)
        assertTrue(routeSnapshot.hasGuardCommunicationHold)
    }

    @Test
    fun `public projection input uses disabled enhanced state when enhanced mode is off`() {
        val input = MonitorSnapshotProjector.publicProjectionInput(
            snapshot(
                enhancedModeEnabled = false,
                enhancedState = EnhancedState.ACTIVE,
                heldRouteMessage = "message",
                canManuallyReleaseHeldRoute = true
            )
        )

        assertEquals(EnhancedState.DISABLED, input.enhancedState)
        assertEquals("message", input.heldRouteMessage)
        assertTrue(input.canManuallyReleaseHeldRoute)
    }

    @Test
    fun `public projection input respects explicit status override`() {
        val input = MonitorSnapshotProjector.publicProjectionInput(
            snapshot(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL
            ),
            statusOverride = GuardStatus.HIJACKED
        )

        assertEquals(GuardStatus.HIJACKED, input.status)
    }

    @Test
    fun `alert snapshot preserves headset presence and held route controls`() {
        val alertSnapshot = MonitorSnapshotProjector.alertSnapshot(
            snapshot(
                hasHeadset = true,
                headsetName = "USB耳机",
                heldRouteMessage = "正在尝试归还耳机控制权",
                canManuallyReleaseHeldRoute = true
            )
        )

        assertEquals(GuardStatus.NORMAL, alertSnapshot.status)
        assertEquals("USB耳机", alertSnapshot.headsetName)
        assertTrue(alertSnapshot.hasHeadset)
        assertEquals("正在尝试归还耳机控制权", alertSnapshot.heldRouteMessage)
        assertTrue(alertSnapshot.canManuallyReleaseHeldRoute)
    }

    @Test
    fun `notification snapshot inputs reuse the same status override for both projections`() {
        val inputs = MonitorSnapshotProjector.notificationSnapshotInputs(
            snapshot(hasHeadset = true),
            statusOverride = GuardStatus.FIXED
        )

        assertEquals(GuardStatus.FIXED, inputs.publicProjectionInput.status)
        assertEquals(GuardStatus.FIXED, inputs.alertSnapshot.status)
    }

    @Test
    fun `fix event snapshot derives resolved status and effective enhanced state`() {
        val fixSnapshot = MonitorSnapshotProjector.fixEventSnapshot(
            snapshot(
                hasHeadset = true,
                headsetName = "AirPods Pro",
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL,
                communicationDeviceName = "AirPods Pro",
                enhancedModeEnabled = false,
                enhancedState = EnhancedState.ACTIVE,
                hasActiveHeldRoute = true,
                heldRouteManualReleaseInProgress = true
            )
        )

        assertEquals(GuardStatus.NORMAL, fixSnapshot.status)
        assertEquals(EnhancedState.DISABLED, fixSnapshot.enhancedState)
        assertEquals("AirPods Pro", fixSnapshot.headsetName)
        assertEquals("AirPods Pro", fixSnapshot.communicationDeviceName)
        assertTrue(fixSnapshot.hasHeldRoute)
        assertTrue(fixSnapshot.heldRouteManualReleaseInProgress)
    }

    @Test
    fun `resolved status delegates to guard status resolver semantics`() {
        val status = MonitorSnapshotProjector.resolvedStatus(
            snapshot(
                hasHeadset = false,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                pollingPhase = RoutePollingPhase.CLEAR_PROBE
            )
        )

        assertEquals(GuardStatus.NO_HEADSET, status)
    }

    private fun snapshot(
        hasHeadset: Boolean = true,
        headsetName: String? = null,
        communicationDeviceKind: CommunicationDeviceKind = CommunicationDeviceKind.NONE,
        communicationDeviceName: String? = null,
        pollingPhase: RoutePollingPhase = RoutePollingPhase.IDLE,
        lastReportedStatus: GuardStatus = GuardStatus.NORMAL,
        enhancedModeEnabled: Boolean = false,
        enhancedState: EnhancedState = EnhancedState.DISABLED,
        isClassicBluetoothPassiveCandidate: Boolean = false,
        hasRecentBuiltinRouteEvidence: Boolean = false,
        hasClassicBluetoothStartupObservation: Boolean = false,
        hasActiveHeldRoute: Boolean = false,
        hasGuardCommunicationHold: Boolean = false,
        heldRouteMessage: String? = null,
        canManuallyReleaseHeldRoute: Boolean = false,
        heldRouteManualReleaseInProgress: Boolean = false,
    ): MonitorSnapshot {
        return MonitorSnapshot(
            hasHeadset = hasHeadset,
            headsetName = headsetName,
            communicationDeviceKind = communicationDeviceKind,
            communicationDeviceName = communicationDeviceName,
            pollingPhase = pollingPhase,
            lastReportedStatus = lastReportedStatus,
            enhancedModeEnabled = enhancedModeEnabled,
            enhancedState = enhancedState,
            isClassicBluetoothPassiveCandidate = isClassicBluetoothPassiveCandidate,
            hasRecentBuiltinRouteEvidence = hasRecentBuiltinRouteEvidence,
            hasClassicBluetoothStartupObservation = hasClassicBluetoothStartupObservation,
            hasActiveHeldRoute = hasActiveHeldRoute,
            hasGuardCommunicationHold = hasGuardCommunicationHold,
            heldRouteMessage = heldRouteMessage,
            canManuallyReleaseHeldRoute = canManuallyReleaseHeldRoute,
            heldRouteManualReleaseInProgress = heldRouteManualReleaseInProgress,
        )
    }
}
