package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Test

class HeldRoutePresentationResolverTest {

    @Test
    fun `uses bluetooth subject for classic bluetooth held routes`() {
        assertEquals(
            "蓝牙音频",
            HeldRoutePresentationResolver.subject(HeldRouteKind.CLASSIC_BLUETOOTH)
        )
    }

    @Test
    fun `uses headset subject for wired or generic held routes`() {
        assertEquals(
            "耳机声道",
            HeldRoutePresentationResolver.subject(HeldRouteKind.HEADSET)
        )
    }

    @Test
    fun `classic bluetooth pinned label ignores headset name`() {
        assertEquals(
            "蓝牙音频",
            HeldRoutePresentationResolver.pinnedLabel(
                kind = HeldRouteKind.CLASSIC_BLUETOOTH,
                headsetName = "AirPods Pro"
            )
        )
    }

    @Test
    fun `headset pinned label prefers headset name`() {
        assertEquals(
            "USB耳机",
            HeldRoutePresentationResolver.pinnedLabel(
                kind = HeldRouteKind.HEADSET,
                headsetName = "USB耳机"
            )
        )
    }

    @Test
    fun `headset pinned label falls back to generic subject when name is unavailable`() {
        assertEquals(
            "耳机声道",
            HeldRoutePresentationResolver.pinnedLabel(
                kind = HeldRouteKind.HEADSET,
                headsetName = null
            )
        )
    }

    @Test
    fun `idle message distinguishes classic bluetooth from other headsets`() {
        assertEquals(
            "蓝牙音频已被应用接管，如抢占声道应用已关闭，您可点击尝试解除该限制",
            HeldRoutePresentationResolver.idleMessage(HeldRouteKind.CLASSIC_BLUETOOTH)
        )
        assertEquals(
            "耳机声道已被应用持续占用，如抢占声道应用已关闭，您可点击尝试解除该限制",
            HeldRoutePresentationResolver.idleMessage(HeldRouteKind.HEADSET)
        )
    }

    @Test
    fun `retry message includes retry wording only after manual release`() {
        assertEquals(
            "检测到声道劫持，已重新接管蓝牙音频。如抢占声道应用已关闭，您可尝试解除该限制",
            HeldRoutePresentationResolver.retryMessage(
                kind = HeldRouteKind.CLASSIC_BLUETOOTH,
                wasManualRelease = false
            )
        )
        assertEquals(
            "检测到声道劫持，已重新接管蓝牙音频。如抢占声道应用已关闭，您可再次尝试解除该限制",
            HeldRoutePresentationResolver.retryMessage(
                kind = HeldRouteKind.CLASSIC_BLUETOOTH,
                wasManualRelease = true
            )
        )
    }
}
