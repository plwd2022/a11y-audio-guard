package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClassicBluetoothStartupObserveResolverTest {

    @Test
    fun `stops with no headset when startup observation target disappears`() {
        val decision = ClassicBluetoothStartupObserveResolver.resolve(
            input(hasHeadset = false)
        )

        assertEquals(ClassicBluetoothStartupObserveOutcome.STOP_NO_HEADSET, decision.outcome)
    }

    @Test
    fun `stops when communication route has already recovered`() {
        val decision = ClassicBluetoothStartupObserveResolver.resolve(
            input(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.EXTERNAL
            )
        )

        assertEquals(
            ClassicBluetoothStartupObserveOutcome.STOP_ROUTE_RECOVERED,
            decision.outcome
        )
    }

    @Test
    fun `routes builtin soft guard result through evaluator first`() {
        val decision = ClassicBluetoothStartupObserveResolver.resolve(
            input(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                routedDeviceKind = CommunicationDeviceKind.BUILTIN
            )
        )

        assertEquals(
            ClassicBluetoothStartupObserveOutcome.EVALUATE_SOFT_GUARD,
            decision.outcome
        )
    }

    @Test
    fun `stops as unconfirmed when routed device is external`() {
        val decision = ClassicBluetoothStartupObserveResolver.resolve(
            input(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                routedDeviceKind = CommunicationDeviceKind.EXTERNAL
            )
        )

        assertEquals(ClassicBluetoothStartupObserveOutcome.STOP_UNCONFIRMED, decision.outcome)
    }

    @Test
    fun `stops as unconfirmed when routed device is still unknown`() {
        val decision = ClassicBluetoothStartupObserveResolver.resolve(
            input(
                hasHeadset = true,
                communicationDeviceKind = CommunicationDeviceKind.BUILTIN,
                routedDeviceKind = CommunicationDeviceKind.NONE
            )
        )

        assertEquals(ClassicBluetoothStartupObserveOutcome.STOP_UNCONFIRMED, decision.outcome)
    }

    private fun input(
        hasHeadset: Boolean,
        communicationDeviceKind: CommunicationDeviceKind = CommunicationDeviceKind.NONE,
        routedDeviceKind: CommunicationDeviceKind = CommunicationDeviceKind.NONE,
    ): ClassicBluetoothStartupObserveDecisionInput {
        return ClassicBluetoothStartupObserveDecisionInput(
            hasHeadset = hasHeadset,
            communicationDeviceKind = communicationDeviceKind,
            routedDeviceKind = routedDeviceKind,
        )
    }
}
