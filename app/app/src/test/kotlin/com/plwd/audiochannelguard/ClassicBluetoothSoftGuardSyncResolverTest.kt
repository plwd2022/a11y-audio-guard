package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicBluetoothSoftGuardSyncResolverTest {

    @Test
    fun `runs in passive confirm window for classic bluetooth headset`() {
        val decision = ClassicBluetoothSoftGuardSyncResolver.resolve(
            input(
                monitorRunning = true,
                softGuardEnabled = true,
                hasHeadset = true,
                isClassicBluetoothHeadset = true,
                pollingPhase = RoutePollingPhase.CLASSIC_BLUETOOTH_CONFIRM
            )
        )

        assertTrue(decision.shouldRun)
        assertEquals(ClassicBluetoothSoftGuardPurpose.PASSIVE_CONFIRM, decision.purpose)
    }

    @Test
    fun `runs during startup observation even when polling phase is idle`() {
        val decision = ClassicBluetoothSoftGuardSyncResolver.resolve(
            input(
                monitorRunning = true,
                softGuardEnabled = true,
                hasHeadset = true,
                isClassicBluetoothHeadset = true,
                pollingPhase = RoutePollingPhase.IDLE,
                hasStartupObservation = true
            )
        )

        assertTrue(decision.shouldRun)
        assertEquals(ClassicBluetoothSoftGuardPurpose.STARTUP_OBSERVE, decision.purpose)
    }

    @Test
    fun `runs during manual release observation when guard has let go`() {
        val decision = ClassicBluetoothSoftGuardSyncResolver.resolve(
            input(
                monitorRunning = true,
                softGuardEnabled = true,
                hasHeadset = true,
                isClassicBluetoothHeadset = true,
                pollingPhase = RoutePollingPhase.RELEASE_PROBE,
                manualReleaseInProgress = true
            )
        )

        assertTrue(decision.shouldRun)
        assertEquals(ClassicBluetoothSoftGuardPurpose.MANUAL_RELEASE, decision.purpose)
    }

    @Test
    fun `does not run when guard still owns communication route`() {
        val decision = ClassicBluetoothSoftGuardSyncResolver.resolve(
            input(
                monitorRunning = true,
                softGuardEnabled = true,
                hasHeadset = true,
                isClassicBluetoothHeadset = true,
                pollingPhase = RoutePollingPhase.CLASSIC_BLUETOOTH_CONFIRM,
                hasGuardCommunicationHold = true
            )
        )

        assertFalse(decision.shouldRun)
        assertEquals(null, decision.purpose)
    }

    @Test
    fun `does not run for non classic bluetooth headset`() {
        val decision = ClassicBluetoothSoftGuardSyncResolver.resolve(
            input(
                monitorRunning = true,
                softGuardEnabled = true,
                hasHeadset = true,
                isClassicBluetoothHeadset = false,
                pollingPhase = RoutePollingPhase.CLASSIC_BLUETOOTH_CONFIRM
            )
        )

        assertFalse(decision.shouldRun)
        assertEquals(null, decision.purpose)
    }

    @Test
    fun `does not run while suspended by call`() {
        val decision = ClassicBluetoothSoftGuardSyncResolver.resolve(
            input(
                monitorRunning = true,
                softGuardEnabled = true,
                hasHeadset = true,
                isClassicBluetoothHeadset = true,
                pollingPhase = RoutePollingPhase.CLASSIC_BLUETOOTH_CONFIRM,
                suspendedByCall = true
            )
        )

        assertFalse(decision.shouldRun)
        assertEquals(null, decision.purpose)
    }

    private fun input(
        monitorRunning: Boolean = false,
        softGuardEnabled: Boolean = false,
        hasHeadset: Boolean = false,
        isClassicBluetoothHeadset: Boolean = false,
        pollingPhase: RoutePollingPhase = RoutePollingPhase.IDLE,
        hasStartupObservation: Boolean = false,
        manualReleaseInProgress: Boolean = false,
        hasGuardCommunicationHold: Boolean = false,
        suspendedByCall: Boolean = false,
    ): ClassicBluetoothSoftGuardSyncInput {
        return ClassicBluetoothSoftGuardSyncInput(
            monitorRunning = monitorRunning,
            softGuardEnabled = softGuardEnabled,
            hasHeadset = hasHeadset,
            isClassicBluetoothHeadset = isClassicBluetoothHeadset,
            pollingPhase = pollingPhase,
            hasStartupObservation = hasStartupObservation,
            manualReleaseInProgress = manualReleaseInProgress,
            hasGuardCommunicationHold = hasGuardCommunicationHold,
            suspendedByCall = suspendedByCall,
        )
    }
}
