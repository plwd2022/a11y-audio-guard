package com.plwd.audiochannelguard

internal data class RelatedAppEvidenceInput(
    val resumeDeltaMs: Long? = null,
    val isForegroundAtIncident: Boolean,
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

    fun currentForegroundLookbackWindowMs(): Long = CURRENT_FOREGROUND_LOOKBACK_WINDOW_MS

    fun resolve(input: RelatedAppEvidenceInput): RelatedAppEvidence? {
        val recentResumeEvidence = input.resumeDeltaMs?.let { resolveRecentResume(it) }
        val currentForegroundEvidence =
            if (input.isForegroundAtIncident) {
                RelatedAppEvidence(
                    behaviorSummary = "异常发生时位于前台，且具备音频设置权限",
                    score = CURRENT_FOREGROUND_SCORE,
                )
            } else {
                null
            }

        return when {
            currentForegroundEvidence != null && recentResumeEvidence != null ->
                RelatedAppEvidence(
                    behaviorSummary = buildCombinedSummary(input.resumeDeltaMs),
                    score = currentForegroundEvidence.score + recentResumeEvidence.score,
                )

            currentForegroundEvidence != null -> currentForegroundEvidence
            else -> recentResumeEvidence
        }
    }

    private fun resolveRecentResume(resumeDeltaMs: Long): RelatedAppEvidence? {
        return when {
            resumeDeltaMs < 0L -> null
            resumeDeltaMs <= STRONG_MATCH_WINDOW_MS ->
                RelatedAppEvidence(
                    behaviorSummary = "在异常前极短时间切到前台，且具备音频设置权限",
                    score = 8,
                )

            resumeDeltaMs <= MEDIUM_MATCH_WINDOW_MS ->
                RelatedAppEvidence(
                    behaviorSummary = "在异常前短时间切到前台，且具备音频设置权限",
                    score = 6,
                )

            resumeDeltaMs <= MAX_MATCH_WINDOW_MS ->
                RelatedAppEvidence(
                    behaviorSummary = "在异常前切到前台，且具备音频设置权限",
                    score = 4,
                )

            else -> null
        }
    }

    private fun buildCombinedSummary(resumeDeltaMs: Long?): String {
        return when {
            resumeDeltaMs == null -> "异常发生时位于前台，且具备音频设置权限"
            resumeDeltaMs <= STRONG_MATCH_WINDOW_MS ->
                "异常发生时位于前台，且在异常前极短时间切到前台，具备音频设置权限"

            resumeDeltaMs <= MEDIUM_MATCH_WINDOW_MS ->
                "异常发生时位于前台，且在异常前短时间切到前台，具备音频设置权限"

            else ->
                "异常发生时位于前台，且此前短时切到前台，具备音频设置权限"
        }
    }
}
