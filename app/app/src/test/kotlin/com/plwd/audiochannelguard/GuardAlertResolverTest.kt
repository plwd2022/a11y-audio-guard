package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardAlertResolverTest {

    @Test
    fun `uses held route alert before all status based alerts`() {
        val spec = GuardAlertResolver.resolveSpec(
            input(
                status = GuardStatus.HIJACKED,
                heldRouteMessage = "正在尝试归还耳机控制权",
                canManuallyReleaseHeldRoute = true
            )
        )

        requireNotNull(spec)
        assertEquals(GuardAlertKind.HELD_ROUTE, spec.kind)
        assertEquals("正在尝试归还耳机控制权", spec.text)
        assertTrue(spec.showReleaseAction)
    }

    @Test
    fun `builds fixed alert with headset name`() {
        val spec = GuardAlertResolver.resolveSpec(
            input(
                status = GuardStatus.FIXED,
                headsetName = "AirPods Pro"
            )
        )

        requireNotNull(spec)
        assertEquals(GuardAlertKind.FIXED, spec.kind)
        assertEquals("fixed:AirPods Pro", spec.key)
        assertEquals("已将读屏声音收回到 AirPods Pro", spec.text)
        assertEquals(6_000L, spec.timeoutMs)
    }

    @Test
    fun `builds fixed speaker alert with relaxed text`() {
        val spec = GuardAlertResolver.resolveSpec(
            input(
                status = GuardStatus.FIXED_BUT_SPEAKER_ROUTE,
                headsetName = "骨传导耳机"
            )
        )

        requireNotNull(spec)
        assertEquals(GuardAlertKind.FIXED, spec.kind)
        assertEquals("已收回到 骨传导耳机，如当前正常请忽略", spec.text)
        assertEquals(8_000L, spec.timeoutMs)
    }

    @Test
    fun `builds delayed recovering alert for hijacked route`() {
        val spec = GuardAlertResolver.resolveSpec(
            input(
                status = GuardStatus.HIJACKED,
                headsetName = "AirPods Pro"
            )
        )

        requireNotNull(spec)
        assertEquals(GuardAlertKind.RECOVERING, spec.kind)
        assertEquals(1_200L, spec.delayMs)
        assertEquals(10_000L, spec.cooldownMs)
    }

    @Test
    fun `does not build disconnect alert when headset was already absent`() {
        val spec = GuardAlertResolver.resolveSpec(
            input(
                status = GuardStatus.NO_HEADSET,
                hasHeadset = false,
                hadHeadsetBefore = false
            )
        )

        assertNull(spec)
    }

    @Test
    fun `builds disconnect alert when headset just disappeared`() {
        val spec = GuardAlertResolver.resolveSpec(
            input(
                status = GuardStatus.NO_HEADSET,
                hasHeadset = false,
                hadHeadsetBefore = true
            )
        )

        requireNotNull(spec)
        assertEquals(GuardAlertKind.HEADSET_DISCONNECTED, spec.kind)
        assertEquals("disconnect", spec.key)
    }

    @Test
    fun `clears recovering alert when route is no longer hijacked`() {
        val shouldClear = GuardAlertResolver.shouldClearActiveAlert(
            activeKind = GuardAlertKind.RECOVERING,
            input = input(status = GuardStatus.FIXED)
        )

        assertTrue(shouldClear)
    }

    @Test
    fun `keeps recovering alert while hijacked state remains`() {
        val shouldClear = GuardAlertResolver.shouldClearActiveAlert(
            activeKind = GuardAlertKind.RECOVERING,
            input = input(status = GuardStatus.HIJACKED)
        )

        assertFalse(shouldClear)
    }

    @Test
    fun `clears held route alert after held message disappears`() {
        val shouldClear = GuardAlertResolver.shouldClearActiveAlert(
            activeKind = GuardAlertKind.HELD_ROUTE,
            input = input(heldRouteMessage = null)
        )

        assertTrue(shouldClear)
    }

    @Test
    fun `clears disconnect alert after headset returns`() {
        val shouldClear = GuardAlertResolver.shouldClearActiveAlert(
            activeKind = GuardAlertKind.HEADSET_DISCONNECTED,
            input = input(hasHeadset = true, status = GuardStatus.NORMAL)
        )

        assertTrue(shouldClear)
    }

    @Test
    fun `never auto clears fixed alert`() {
        val shouldClear = GuardAlertResolver.shouldClearActiveAlert(
            activeKind = GuardAlertKind.FIXED,
            input = input(status = GuardStatus.NORMAL)
        )

        assertFalse(shouldClear)
    }

    private fun input(
        status: GuardStatus = GuardStatus.NORMAL,
        headsetName: String? = null,
        hasHeadset: Boolean = headsetName != null,
        heldRouteMessage: String? = null,
        canManuallyReleaseHeldRoute: Boolean = false,
        hadHeadsetBefore: Boolean = hasHeadset,
    ): GuardAlertDecisionInput {
        return GuardAlertDecisionInput(
            status = status,
            headsetName = headsetName,
            hasHeadset = hasHeadset,
            heldRouteMessage = heldRouteMessage,
            canManuallyReleaseHeldRoute = canManuallyReleaseHeldRoute,
            hadHeadsetBefore = hadHeadsetBefore,
        )
    }
}
