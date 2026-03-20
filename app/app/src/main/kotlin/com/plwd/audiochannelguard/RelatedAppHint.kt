package com.plwd.audiochannelguard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RelatedAppHintKind {
    APP,
    EVENT_ONLY,
    PERMISSION_REQUIRED,
}

data class RelatedAppHint(
    val kind: RelatedAppHintKind,
    val packageName: String? = null,
    val appLabel: String? = null,
    val happenedAtMs: Long,
    val behaviorSummary: String? = null,
    val active: Boolean,
    val activeUntilMs: Long,
    val expiresAtMs: Long,
)

data class RelatedAppHintProjection(
    val title: String,
    val summary: String,
    val actionLabel: String? = null,
    val onClickLabel: String? = null,
)

object RelatedAppHintProjectionResolver {
    fun resolve(input: RelatedAppHint): RelatedAppHintProjection {
        return when (input.kind) {
            RelatedAppHintKind.APP -> {
                val displayName = displayName(input)
                val behavior = input.behaviorSummary.orEmpty()
                val time = formatTime(input.happenedAtMs)
                val summary = if (input.active) {
                    "$time $behavior。可打开应用信息进一步处理。这只是排查线索，不代表已直接确认。"
                } else {
                    "$time $behavior。若现在已恢复正常，可先忽略。这只是排查线索，不代表已直接确认。"
                }
                RelatedAppHintProjection(
                    title = displayName,
                    summary = summary,
                    actionLabel = "打开应用信息",
                    onClickLabel = "打开${displayName}的应用信息"
                )
            }

            RelatedAppHintKind.EVENT_ONLY -> {
                val time = formatTime(input.happenedAtMs)
                val summary = if (input.active) {
                    "$time 已捕获到读屏声道异常，但当前还没有更明确的相关应用线索。可继续复现后再观察。"
                } else {
                    "$time 曾捕获到读屏声道异常，但当前还没有更明确的相关应用线索。若现在已恢复正常，可先忽略。"
                }
                RelatedAppHintProjection(
                    title = "已发生异常",
                    summary = summary,
                )
            }

            RelatedAppHintKind.PERMISSION_REQUIRED -> RelatedAppHintProjection(
                title = "可能相关应用",
                summary =
                    "如需显示可能相关应用，请先开启“应用使用情况访问”。应用只会在异常发生时短时读取线索，用于临时排查。",
                actionLabel = "去设置",
                onClickLabel = "打开应用使用情况访问设置"
            )
        }
    }

    private fun displayName(input: RelatedAppHint): String {
        return input.appLabel?.takeIf { it.isNotBlank() }
            ?: input.packageName?.takeIf { it.isNotBlank() }
            ?: "未知应用"
    }

    private fun formatTime(timestampMs: Long): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestampMs))
    }
}
