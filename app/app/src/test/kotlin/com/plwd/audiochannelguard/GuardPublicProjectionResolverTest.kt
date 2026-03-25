package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardPublicProjectionResolverTest {

    @Test
    fun `shows disabled summary when service is stopped`() {
        val projection = GuardPublicProjectionResolver.resolve(
            serviceRunning = false,
            input = input(status = GuardStatus.NORMAL)
        )

        assertEquals("保护已关闭", projection.statusTitle)
        assertEquals(
            "开启后，如果读屏声音误外放，应用会自动把声音拉回耳机。",
            projection.statusSummary
        )
        assertFalse(projection.showQuickFixAction)
    }

    @Test
    fun `uses headset specific text for fixed status`() {
        val projection = GuardPublicProjectionResolver.resolve(
            serviceRunning = true,
            input = input(
                status = GuardStatus.FIXED,
                headsetName = "骨传导耳机"
            )
        )

        assertEquals("已将读屏声音收回耳机", projection.statusTitle)
        assertEquals("最近一次异常已处理，读屏声音已收回到 骨传导耳机。", projection.statusSummary)
        assertEquals("已将读屏声音收回到 骨传导耳机", projection.persistentNotificationText)
        assertEquals("已收回", projection.tileSubtitle)
    }

    @Test
    fun `uses held route message for notification and hides runtime hint when manual release is available`() {
        val projection = GuardPublicProjectionResolver.resolve(
            serviceRunning = true,
            input = input(
                heldRouteMessage = "正在尝试归还耳机控制权",
                canManuallyReleaseHeldRoute = true
            )
        )

        assertEquals("正在尝试归还耳机控制权", projection.persistentNotificationText)
        assertEquals("外放占用", projection.tileSubtitle)
        assertNull(projection.runtimeHintMessage)
    }

    @Test
    fun `shows observation hint when held route exists but manual release is unavailable`() {
        val projection = GuardPublicProjectionResolver.resolve(
            serviceRunning = true,
            input = input(
                heldRouteMessage = "正在尝试归还耳机控制权",
                canManuallyReleaseHeldRoute = false
            )
        )

        assertEquals("观察中", projection.tileSubtitle)
        assertEquals("正在尝试归还耳机控制权", projection.runtimeHintMessage)
    }

    @Test
    fun `uses enhanced active notification while route is stable`() {
        val projection = GuardPublicProjectionResolver.resolve(
            serviceRunning = true,
            input = input(
                status = GuardStatus.NORMAL,
                enhancedState = EnhancedState.ACTIVE,
                enhancedModeEnabled = true
            )
        )

        assertEquals("增强保护中", projection.persistentNotificationText)
    }

    @Test
    fun `uses enhanced fixed notification with headset name`() {
        val projection = GuardPublicProjectionResolver.resolve(
            serviceRunning = true,
            input = input(
                status = GuardStatus.FIXED,
                enhancedState = EnhancedState.ACTIVE,
                enhancedModeEnabled = true,
                headsetName = "AirPods Pro"
            )
        )

        assertEquals("增强保护中，已收回到 AirPods Pro", projection.persistentNotificationText)
    }

    @Test
    fun `uses enhanced waiting notification when enhanced mode is enabled`() {
        val projection = GuardPublicProjectionResolver.resolve(
            serviceRunning = true,
            input = input(
                status = GuardStatus.NO_HEADSET,
                enhancedState = EnhancedState.WAITING_HEADSET,
                enhancedModeEnabled = true
            )
        )

        assertEquals("增强保护等待耳机", projection.persistentNotificationText)
    }

    @Test
    fun `shows quick fix action for hijacked route`() {
        val projection = GuardPublicProjectionResolver.resolve(
            serviceRunning = true,
            input = input(status = GuardStatus.HIJACKED)
        )

        assertTrue(projection.showQuickFixAction)
        assertEquals("检测到读屏声音可能外放", projection.statusTitle)
        assertEquals("疑似外放", projection.tileSubtitle)
    }

    private fun input(
        status: GuardStatus = GuardStatus.NORMAL,
        enhancedState: EnhancedState = EnhancedState.DISABLED,
        enhancedModeEnabled: Boolean = false,
        headsetName: String? = null,
        heldRouteMessage: String? = null,
        canManuallyReleaseHeldRoute: Boolean = false,
    ): GuardPublicProjectionInput {
        return GuardPublicProjectionInput(
            status = status,
            enhancedState = enhancedState,
            enhancedModeEnabled = enhancedModeEnabled,
            headsetName = headsetName,
            heldRouteMessage = heldRouteMessage,
            canManuallyReleaseHeldRoute = canManuallyReleaseHeldRoute,
        )
    }
}
