package com.plwd.audiochannelguard

internal data class ModeResidueInput(
    val mode: Int,
    val modeRequestedByEnhanced: Boolean,
    val alreadyReported: Boolean,
)

internal object ModeResidueResolver {
    // Mirrors AudioManager.MODE_IN_COMMUNICATION without depending on the Android SDK in unit tests.
    const val MODE_IN_COMMUNICATION = 3

    fun shouldReport(input: ModeResidueInput): Boolean {
        if (input.mode != MODE_IN_COMMUNICATION) return false
        if (input.modeRequestedByEnhanced) return false
        if (input.alreadyReported) return false
        return true
    }

    fun shouldClearReportedFlag(mode: Int): Boolean {
        return mode != MODE_IN_COMMUNICATION
    }
}
