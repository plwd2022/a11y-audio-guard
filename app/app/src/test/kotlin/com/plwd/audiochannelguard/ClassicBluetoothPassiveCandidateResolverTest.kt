package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassicBluetoothPassiveCandidateResolverTest {

    @Test
    fun `allows passive confirmation for classic bluetooth headset when guard is idle`() {
        val decision = ClassicBluetoothPassiveCandidateResolver.resolve(
            input(isClassicBluetoothHeadset = true)
        )

        assertEquals(ClassicBluetoothPassiveCandidateOutcome.ALLOWED, decision.outcome)
    }

    @Test
    fun `rejects passive confirmation when enhanced mode is enabled`() {
        val decision = ClassicBluetoothPassiveCandidateResolver.resolve(
            input(
                enhancedModeEnabled = true,
                isClassicBluetoothHeadset = true
            )
        )

        assertEquals(ClassicBluetoothPassiveCandidateOutcome.NOT_ALLOWED, decision.outcome)
    }

    @Test
    fun `rejects passive confirmation when guard still holds communication control`() {
        val decision = ClassicBluetoothPassiveCandidateResolver.resolve(
            input(
                hasGuardCommunicationHold = true,
                isClassicBluetoothHeadset = true
            )
        )

        assertEquals(ClassicBluetoothPassiveCandidateOutcome.NOT_ALLOWED, decision.outcome)
    }

    @Test
    fun `rejects passive confirmation for non classic bluetooth headsets`() {
        val decision = ClassicBluetoothPassiveCandidateResolver.resolve(
            input(isClassicBluetoothHeadset = false)
        )

        assertEquals(ClassicBluetoothPassiveCandidateOutcome.NOT_ALLOWED, decision.outcome)
    }

    private fun input(
        enhancedModeEnabled: Boolean = false,
        hasGuardCommunicationHold: Boolean = false,
        isClassicBluetoothHeadset: Boolean = false,
    ): ClassicBluetoothPassiveCandidateInput {
        return ClassicBluetoothPassiveCandidateInput(
            enhancedModeEnabled = enhancedModeEnabled,
            hasGuardCommunicationHold = hasGuardCommunicationHold,
            isClassicBluetoothHeadset = isClassicBluetoothHeadset,
        )
    }
}
