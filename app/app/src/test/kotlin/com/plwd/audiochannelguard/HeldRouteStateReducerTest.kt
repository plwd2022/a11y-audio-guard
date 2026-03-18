package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldRouteStateReducerTest {

    @Test
    fun `enter creates active held route state with idle message`() {
        val state = HeldRouteStateReducer.enter(
            headsetKey = "8:AA:BB",
            kind = HeldRouteKind.CLASSIC_BLUETOOTH,
            message = "idle"
        )

        assertTrue(state.active)
        assertFalse(state.manualReleaseInProgress)
        assertEquals("8:AA:BB", state.headsetKey)
        assertEquals(HeldRouteKind.CLASSIC_BLUETOOTH, state.kind)
        assertEquals("idle", state.message)
    }

    @Test
    fun `start manual release keeps held route active and marks release in progress`() {
        val state = HeldRouteStateReducer.startManualRelease(
            headsetKey = "7:Headset",
            kind = HeldRouteKind.HEADSET,
            message = "releasing"
        )

        assertTrue(state.active)
        assertTrue(state.manualReleaseInProgress)
        assertEquals("7:Headset", state.headsetKey)
        assertEquals(HeldRouteKind.HEADSET, state.kind)
        assertEquals("releasing", state.message)
    }

    @Test
    fun `clear returns default empty held route state`() {
        val state = HeldRouteStateReducer.clear()

        assertEquals(HeldRouteState(), state)
    }

    @Test
    fun `reclaim restores active held route state after failed release`() {
        val state = HeldRouteStateReducer.reclaim(
            headsetKey = "8:AA:BB",
            kind = HeldRouteKind.CLASSIC_BLUETOOTH,
            message = "retry"
        )

        assertTrue(state.active)
        assertFalse(state.manualReleaseInProgress)
        assertEquals("8:AA:BB", state.headsetKey)
        assertEquals(HeldRouteKind.CLASSIC_BLUETOOTH, state.kind)
        assertEquals("retry", state.message)
    }

    @Test
    fun `sync tracking returns same state when tracking is not needed`() {
        val currentState = HeldRouteState(
            active = false,
            manualReleaseInProgress = false,
            headsetKey = "old",
            kind = HeldRouteKind.HEADSET,
            message = "message"
        )

        val nextState = HeldRouteStateReducer.syncTracking(
            currentState = currentState,
            shouldTrack = false,
            headsetKey = "new",
            kind = HeldRouteKind.CLASSIC_BLUETOOTH
        )

        assertEquals(currentState, nextState)
    }

    @Test
    fun `sync tracking updates headset identity and kind when tracking is active`() {
        val currentState = HeldRouteState(
            active = true,
            manualReleaseInProgress = false,
            headsetKey = "old",
            kind = HeldRouteKind.HEADSET,
            message = "message"
        )

        val nextState = HeldRouteStateReducer.syncTracking(
            currentState = currentState,
            shouldTrack = true,
            headsetKey = "new",
            kind = HeldRouteKind.CLASSIC_BLUETOOTH
        )

        assertEquals("new", nextState.headsetKey)
        assertEquals(HeldRouteKind.CLASSIC_BLUETOOTH, nextState.kind)
        assertEquals("message", nextState.message)
        assertTrue(nextState.active)
    }

    @Test
    fun `current message is visible while manual release is in progress`() {
        val message = HeldRouteStateReducer.currentMessage(
            state = HeldRouteState(message = "releasing", manualReleaseInProgress = true),
            hasActiveHeldRoute = false
        )

        assertEquals("releasing", message)
    }

    @Test
    fun `current message is visible when held route remains active`() {
        val message = HeldRouteStateReducer.currentMessage(
            state = HeldRouteState(message = "idle"),
            hasActiveHeldRoute = true
        )

        assertEquals("idle", message)
    }

    @Test
    fun `current message is hidden when route is inactive and manual release is not running`() {
        val message = HeldRouteStateReducer.currentMessage(
            state = HeldRouteState(message = "idle"),
            hasActiveHeldRoute = false
        )

        assertNull(message)
    }

    @Test
    fun `manual release is only available when headset is active and not already releasing`() {
        assertTrue(
            HeldRouteStateReducer.canManualRelease(
                state = HeldRouteState(manualReleaseInProgress = false),
                hasHeadset = true,
                hasActiveHeldRoute = true
            )
        )
        assertFalse(
            HeldRouteStateReducer.canManualRelease(
                state = HeldRouteState(manualReleaseInProgress = true),
                hasHeadset = true,
                hasActiveHeldRoute = true
            )
        )
        assertFalse(
            HeldRouteStateReducer.canManualRelease(
                state = HeldRouteState(manualReleaseInProgress = false),
                hasHeadset = false,
                hasActiveHeldRoute = true
            )
        )
        assertFalse(
            HeldRouteStateReducer.canManualRelease(
                state = HeldRouteState(manualReleaseInProgress = false),
                hasHeadset = true,
                hasActiveHeldRoute = false
            )
        )
    }
}
