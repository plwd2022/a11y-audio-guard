package com.plwd.audiochannelguard

internal data class RelatedAppEvidenceInput(
    val resumeDeltaMs: Long,
)

internal data class RelatedAppEvidence(
    val behaviorSummary: String,
    val score: Int,
)

internal object RelatedAppEvidenceResolver {
    private const val STRONG_MATCH_WINDOW_MS = 800L
    private const val MEDIUM_MATCH_WINDOW_MS = 1_500L
    private const val MAX_MATCH_WINDOW_MS = 3_000L

    fun maxLookbackWindowMs(): Long = MAX_MATCH_WINDOW_MS

    fun resolve(input: RelatedAppEvidenceInput): RelatedAppEvidence? {
        return when {
            input.resumeDeltaMs < 0L -> null
            input.resumeDeltaMs <= STRONG_MATCH_WINDOW_MS ->
                RelatedAppEvidence(
                    behaviorSummary = "在异常前极短时间切到前台，且具备音频设置权限",
                    score = 8,
                )

            input.resumeDeltaMs <= MEDIUM_MATCH_WINDOW_MS ->
                RelatedAppEvidence(
                    behaviorSummary = "在异常前短时间切到前台，且具备音频设置权限",
                    score = 6,
                )

            input.resumeDeltaMs <= MAX_MATCH_WINDOW_MS ->
                RelatedAppEvidence(
                    behaviorSummary = "在异常前切到前台，且具备音频设置权限",
                    score = 4,
                )

            else -> null
        }
    }
}
