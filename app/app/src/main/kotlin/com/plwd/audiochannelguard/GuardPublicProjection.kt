package com.plwd.audiochannelguard

data class GuardPublicProjectionInput(
    val status: GuardStatus,
    val enhancedState: EnhancedState,
    val enhancedModeEnabled: Boolean,
    val headsetName: String?,
    val heldRouteMessage: String?,
    val canManuallyReleaseHeldRoute: Boolean,
)

data class GuardPublicProjection(
    val statusTitle: String,
    val statusSummary: String,
    val persistentNotificationText: String,
    val tileSubtitle: String,
    val runtimeHintMessage: String?,
    val showQuickFixAction: Boolean,
)

object GuardPublicProjectionResolver {
    fun resolve(
        serviceRunning: Boolean,
        input: GuardPublicProjectionInput,
    ): GuardPublicProjection {
        return GuardPublicProjection(
            statusTitle = statusTitle(serviceRunning, input.status),
            statusSummary = statusSummary(serviceRunning, input.status, input.headsetName),
            persistentNotificationText = persistentNotificationText(input),
            tileSubtitle = tileSubtitle(input),
            runtimeHintMessage = runtimeHintMessage(input),
            showQuickFixAction =
                serviceRunning &&
                    (
                        input.status == GuardStatus.HIJACKED ||
                            input.status == GuardStatus.FIXED_BUT_SPEAKER_ROUTE
                        ),
        )
    }

    private fun statusTitle(serviceRunning: Boolean, status: GuardStatus): String {
        return when {
            !serviceRunning -> "保护已关闭"
            status == GuardStatus.HIJACKED -> "检测到读屏声音可能外放"
            status == GuardStatus.FIXED || status == GuardStatus.FIXED_BUT_SPEAKER_ROUTE ->
                "已将读屏声音收回耳机"
            status == GuardStatus.NO_HEADSET -> "当前未接入耳机"
            else -> "读屏声音正在耳机中"
        }
    }

    private fun statusSummary(
        serviceRunning: Boolean,
        status: GuardStatus,
        headsetName: String?,
    ): String {
        if (!serviceRunning) {
            return "开启后，如果读屏声音误外放，应用会自动把声音拉回耳机。"
        }

        val namedHeadset = namedHeadset(headsetName)
        return when (status) {
            GuardStatus.NORMAL ->
                if (namedHeadset != null) {
                    "当前读屏声音在 $namedHeadset 中，保护正在后台运行。"
                } else {
                    "保护已开启，正在后台观察读屏声音是否误外放。"
                }

            GuardStatus.FIXED ->
                if (namedHeadset != null) {
                    "最近一次异常已处理，读屏声音已收回到 $namedHeadset。"
                } else {
                    "最近一次异常已处理，读屏声音已收回耳机。"
                }

            GuardStatus.FIXED_BUT_SPEAKER_ROUTE ->
                "读屏声音已经收回耳机；如果现在听起来正常，可以先忽略。"

            GuardStatus.HIJACKED ->
                "读屏声音可能还在扬声器外放；如果你现在确实听到外放，可以立即修复。"

            GuardStatus.NO_HEADSET ->
                "接入耳机后会自动开始保护。"
        }
    }

    private fun persistentNotificationText(input: GuardPublicProjectionInput): String {
        input.heldRouteMessage?.let { return it }

        return when (input.enhancedState) {
            EnhancedState.CLEAR_PROBE -> "增强保护观察中"
            EnhancedState.SUSPENDED_BY_CALL -> "增强保护已暂停（通话中）"
            EnhancedState.WAITING_HEADSET ->
                if (input.enhancedModeEnabled) {
                    "增强保护等待耳机"
                } else {
                    defaultStatusText(input.status, input.headsetName)
                }

            EnhancedState.ACTIVE ->
                if (input.status == GuardStatus.FIXED || input.status == GuardStatus.FIXED_BUT_SPEAKER_ROUTE) {
                    "增强保护中，已收回到 ${namedHeadset(input.headsetName) ?: "耳机"}"
                } else {
                    "增强保护中"
                }

            EnhancedState.DISABLED ->
                defaultStatusText(input.status, input.headsetName)
        }
    }

    private fun defaultStatusText(status: GuardStatus, headsetName: String?): String {
        return when (status) {
            GuardStatus.NORMAL -> "正在保护读屏声音"
            GuardStatus.FIXED -> "已将读屏声音收回到 ${namedHeadset(headsetName) ?: "耳机"}"
            GuardStatus.FIXED_BUT_SPEAKER_ROUTE ->
                "已收回到 ${namedHeadset(headsetName) ?: "耳机"}，如当前正常请忽略"
            GuardStatus.HIJACKED -> "检测到读屏声音可能外放"
            GuardStatus.NO_HEADSET -> "未检测到耳机"
        }
    }

    private fun tileSubtitle(input: GuardPublicProjectionInput): String {
        return when {
            input.canManuallyReleaseHeldRoute -> "外放占用"
            input.heldRouteMessage != null -> "观察中"
            else -> when (input.status) {
                GuardStatus.NORMAL -> namedHeadset(input.headsetName) ?: "保护中"
                GuardStatus.FIXED -> "已收回"
                GuardStatus.FIXED_BUT_SPEAKER_ROUTE -> "已收回"
                GuardStatus.HIJACKED -> "疑似外放"
                GuardStatus.NO_HEADSET -> "未接耳机"
            }
        }
    }

    private fun runtimeHintMessage(input: GuardPublicProjectionInput): String? {
        return if (input.canManuallyReleaseHeldRoute) {
            null
        } else {
            input.heldRouteMessage
        }
    }

    private fun namedHeadset(headsetName: String?): String? {
        return headsetName?.takeIf { it.isNotBlank() }
    }
}
