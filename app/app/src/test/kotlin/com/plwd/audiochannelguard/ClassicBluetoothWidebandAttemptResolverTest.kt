package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassicBluetoothWidebandAttemptResolverTest {

    @Test
    fun `skips when monitor is not running`() {
        val decision = ClassicBluetoothWidebandAttemptResolver.resolve(
            input(monitorRunning = false)
        )

        assertEquals(ClassicBluetoothWidebandAttemptOutcome.SKIP, decision.outcome)
    }

    @Test
    fun `skips when wideband feature is disabled`() {
        val decision = ClassicBluetoothWidebandAttemptResolver.resolve(
            input(widebandEnabled = false)
        )

        assertEquals(ClassicBluetoothWidebandAttemptOutcome.SKIP, decision.outcome)
    }

    @Test
    fun `skips when target device is not classic bluetooth`() {
        val decision = ClassicBluetoothWidebandAttemptResolver.resolve(
            input(isClassicBluetoothTarget = false)
        )

        assertEquals(ClassicBluetoothWidebandAttemptOutcome.SKIP, decision.outcome)
    }

    @Test
    fun `skips when suspended by call`() {
        val decision = ClassicBluetoothWidebandAttemptResolver.resolve(
            input(suspendedByCall = true)
        )

        assertEquals(ClassicBluetoothWidebandAttemptOutcome.SKIP, decision.outcome)
    }

    @Test
    fun `applies hint on first classic bluetooth acquisition`() {
        val decision = ClassicBluetoothWidebandAttemptResolver.resolve(
            input(previousDeviceKind = CommunicationDeviceKind.NONE)
        )

        assertEquals(ClassicBluetoothWidebandAttemptOutcome.APPLY_HINT, decision.outcome)
    }

    @Test
    fun `applies hint when switching from builtin route to classic bluetooth`() {
        val decision = ClassicBluetoothWidebandAttemptResolver.resolve(
            input(previousDeviceKind = CommunicationDeviceKind.BUILTIN)
        )

        assertEquals(ClassicBluetoothWidebandAttemptOutcome.APPLY_HINT, decision.outcome)
    }

    @Test
    fun `skips hint when classic bluetooth target is already the active external route`() {
        val decision = ClassicBluetoothWidebandAttemptResolver.resolve(
            input(
                previousDeviceKind = CommunicationDeviceKind.EXTERNAL,
                previousDeviceMatchesTarget = true
            )
        )

        assertEquals(ClassicBluetoothWidebandAttemptOutcome.SKIP, decision.outcome)
    }

    @Test
    fun `applies hint when external route changes to a different classic bluetooth device`() {
        val decision = ClassicBluetoothWidebandAttemptResolver.resolve(
            input(
                previousDeviceKind = CommunicationDeviceKind.EXTERNAL,
                previousDeviceMatchesTarget = false
            )
        )

        assertEquals(ClassicBluetoothWidebandAttemptOutcome.APPLY_HINT, decision.outcome)
    }

    @Test
    fun `skips hint during cooldown window`() {
        val decision = ClassicBluetoothWidebandAttemptResolver.resolve(
            input(hasRecentAttempt = true)
        )

        assertEquals(ClassicBluetoothWidebandAttemptOutcome.SKIP, decision.outcome)
    }

    private fun input(
        monitorRunning: Boolean = true,
        widebandEnabled: Boolean = true,
        isClassicBluetoothTarget: Boolean = true,
        suspendedByCall: Boolean = false,
        previousDeviceKind: CommunicationDeviceKind = CommunicationDeviceKind.NONE,
        previousDeviceMatchesTarget: Boolean = false,
        hasRecentAttempt: Boolean = false,
    ): ClassicBluetoothWidebandAttemptInput {
        return ClassicBluetoothWidebandAttemptInput(
            monitorRunning = monitorRunning,
            widebandEnabled = widebandEnabled,
            isClassicBluetoothTarget = isClassicBluetoothTarget,
            suspendedByCall = suspendedByCall,
            previousDeviceKind = previousDeviceKind,
            previousDeviceMatchesTarget = previousDeviceMatchesTarget,
            hasRecentAttempt = hasRecentAttempt,
        )
    }
}
