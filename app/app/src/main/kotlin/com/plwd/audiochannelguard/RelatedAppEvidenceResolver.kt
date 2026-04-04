package com.plwd.audiochannelguard

internal data class RelatedAppEvidenceInput(
    val resumeDeltaMs: Long? = null,
    val isForegroundAtIncident: Boolean,
    val foregroundServiceStartDeltaMs: Long? = null,
    val isForegroundServiceRunningAtIncident: Boolean,
    val declaresModifyAudioSettings: Boolean,
)

internal data class RelatedAppEvidence(
    val behaviorSummary: String,
    val score: Int,
)

internal object RelatedAppEvidenceResolver {
    private const val STRONG_MATCH_WINDOW_MS = 800L
    private const val MEDIUM_MATCH_WINDOW_MS = 1_500L
    private const val MAX_MATCH_WINDOW_MS = 3_000L
    private const val CURRENT_FOREGROUND_LOOKBACK_WINDOW_MS = 30L * 60_000L
    private const val CURRENT_FOREGROUND_SCORE = 5
    private const val CURRENT_FOREGROUND_SERVICE_SCORE = 3
    private const val RECENT_FOREGROUND_SERVICE_START_SCORE = 2
    private const val MODIFY_AUDIO_SETTINGS_DECLARATION_SCORE = 1

    fun currentForegroundLookbackWindowMs(): Long = CURRENT_FOREGROUND_LOOKBACK_WINDOW_MS

    fun resolve(input: RelatedAppEvidenceInput): RelatedAppEvidence? {
        val summaryParts = mutableListOf<String>()
        var score = 0

        if (input.isForegroundAtIncident) {
            summaryParts += "异常发生时位于前台"
            score += CURRENT_FOREGROUND_SCORE
        }

        input.resumeDeltaMs?.let { resolveRecentResume(it) }?.let { evidence ->
            summaryParts += evidence.behaviorSummary
            score += evidence.score
        }

        if (input.isForegroundServiceRunningAtIncident) {
            summaryParts += "异常发生时前台服务仍在运行"
            score += CURRENT_FOREGROUND_SERVICE_SCORE
        }

        input.foregroundServiceStartDeltaMs?.let { resolveRecentForegroundServiceStart(it) }?.let { evidence ->
            summaryParts += evidence.behaviorSummary
            score += evidence.score
        }

        if (input.declaresModifyAudioSettings) {
            summaryParts += "声明了音频设置权限"
            score += MODIFY_AUDIO_SETTINGS_DECLARATION_SCORE
        }

        if (score == 0) {
            return null
        }

        return RelatedAppEvidence(
            behaviorSummary = summaryParts.joinToString("，且"),
            score = score,
        )
    }

    private fun resolveRecentResume(resumeDeltaMs: Long): RelatedAppEvidence? {
        return when {
            resumeDeltaMs < 0L -> null
            resumeDeltaMs <= STRONG_MATCH_WINDOW_MS ->
                RelatedAppEvidence(
                    behaviorSummary = "在异常前极短时间切到前台",
                    score = 8,
                )

            resumeDeltaMs <= MEDIUM_MATCH_WINDOW_MS ->
                RelatedAppEvidence(
                    behaviorSummary = "在异常前短时间切到前台",
                    score = 6,
                )

            resumeDeltaMs <= MAX_MATCH_WINDOW_MS ->
                RelatedAppEvidence(
                    behaviorSummary = "在异常前切到前台",
                    score = 4,
                )

            else -> null
        }
    }

    private fun resolveRecentForegroundServiceStart(foregroundServiceStartDeltaMs: Long): RelatedAppEvidence? {
        return when {
            foregroundServiceStartDeltaMs < 0L -> null
            foregroundServiceStartDeltaMs <= MAX_MATCH_WINDOW_MS ->
                RelatedAppEvidence(
                    behaviorSummary = "在异常前短时间启动前台服务",
                    score = RECENT_FOREGROUND_SERVICE_START_SCORE,
                )

            else -> null
        }
    }
}
