package com.plwd.audiochannelguard

internal object HeldRoutePresentationResolver {
    fun subject(kind: HeldRouteKind): String {
        return if (kind == HeldRouteKind.CLASSIC_BLUETOOTH) {
            "蓝牙音频"
        } else {
            "耳机声道"
        }
    }

    fun pinnedLabel(kind: HeldRouteKind, headsetName: String?): String {
        return if (kind == HeldRouteKind.CLASSIC_BLUETOOTH) {
            subject(kind)
        } else {
            headsetName ?: subject(kind)
        }
    }

    fun idleMessage(kind: HeldRouteKind): String {
        return if (kind == HeldRouteKind.CLASSIC_BLUETOOTH) {
            "蓝牙音频已被应用接管，如抢占声道应用已关闭，您可点击尝试解除该限制"
        } else {
            "耳机声道已被应用持续占用，如抢占声道应用已关闭，您可点击尝试解除该限制"
        }
    }

    fun retryMessage(kind: HeldRouteKind, wasManualRelease: Boolean): String {
        val retryWord = if (wasManualRelease) "再次" else ""
        return "检测到声道劫持，已重新接管${subject(kind)}。如抢占声道应用已关闭，您可${retryWord}尝试解除该限制"
    }
}
