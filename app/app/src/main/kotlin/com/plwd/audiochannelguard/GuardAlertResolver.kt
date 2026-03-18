package com.plwd.audiochannelguard

internal data class GuardAlertDecisionInput(
    val status: GuardStatus,
    val headsetName: String?,
    val hasHeadset: Boolean,
    val heldRouteMessage: String?,
    val canManuallyReleaseHeldRoute: Boolean,
    val hadHeadsetBefore: Boolean,
)

internal enum class GuardAlertKind {
    RECOVERING,
    FIXED,
    HELD_ROUTE,
    HEADSET_DISCONNECTED,
}

internal data class GuardAlertSpec(
    val kind: GuardAlertKind,
    val key: String,
    val text: String,
    val timeoutMs: Long,
    val cooldownMs: Long,
    val delayMs: Long = 0L,
    val showReleaseAction: Boolean = false,
)

internal object GuardAlertResolver {
    fun resolveSpec(input: GuardAlertDecisionInput): GuardAlertSpec? {
        input.heldRouteMessage?.let { message ->
            return GuardAlertSpec(
                kind = GuardAlertKind.HELD_ROUTE,
                key = "held:$message:${input.canManuallyReleaseHeldRoute}",
                text = message,
                timeoutMs = 20_000L,
                cooldownMs = 10_000L,
                showReleaseAction = input.canManuallyReleaseHeldRoute,
            )
        }

        return when (input.status) {
            GuardStatus.FIXED -> GuardAlertSpec(
                kind = GuardAlertKind.FIXED,
                key = "fixed:${input.headsetName.orEmpty()}",
                text = "已将读屏声音收回到 ${input.headsetName ?: "耳机"}",
                timeoutMs = 6_000L,
                cooldownMs = 15_000L,
            )

            GuardStatus.FIXED_BUT_SPEAKER_ROUTE -> GuardAlertSpec(
                kind = GuardAlertKind.FIXED,
                key = "fixed_speaker:${input.headsetName.orEmpty()}",
                text = "已收回到 ${input.headsetName ?: "耳机"}，如当前正常请忽略",
                timeoutMs = 8_000L,
                cooldownMs = 15_000L,
            )

            GuardStatus.HIJACKED -> GuardAlertSpec(
                kind = GuardAlertKind.RECOVERING,
                key = "hijacked:${input.headsetName.orEmpty()}",
                text = "检测到读屏声音可能外放，正在尝试恢复",
                timeoutMs = 15_000L,
                cooldownMs = 10_000L,
                delayMs = 1_200L,
            )

            GuardStatus.NO_HEADSET ->
                if (!input.hadHeadsetBefore) {
                    null
                } else {
                    GuardAlertSpec(
                        kind = GuardAlertKind.HEADSET_DISCONNECTED,
                        key = "disconnect",
                        text = "耳机已断开，保护会在重新接入后恢复",
                        timeoutMs = 5_000L,
                        cooldownMs = 8_000L,
                    )
                }

            GuardStatus.NORMAL -> null
        }
    }

    fun shouldClearActiveAlert(
        activeKind: GuardAlertKind?,
        input: GuardAlertDecisionInput,
    ): Boolean {
        return when (activeKind) {
            GuardAlertKind.RECOVERING -> input.status != GuardStatus.HIJACKED
            GuardAlertKind.HELD_ROUTE -> input.heldRouteMessage == null
            GuardAlertKind.HEADSET_DISCONNECTED -> input.hasHeadset
            GuardAlertKind.FIXED, null -> false
        }
    }
}
