package com.plwd.audiochannelguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModeResidueResolverTest {

    @Test
    fun `reports residual communication mode when not requested by guard`() {
        val shouldReport = ModeResidueResolver.shouldReport(
            ModeResidueInput(
                mode = ModeResidueResolver.MODE_IN_COMMUNICATION,
                modeRequestedByEnhanced = false,
                alreadyReported = false,
            )
        )

        assertTrue(shouldReport)
    }

    @Test
    fun `does not report when mode was requested by enhanced guard`() {
        val shouldReport = ModeResidueResolver.shouldReport(
            ModeResidueInput(
                mode = ModeResidueResolver.MODE_IN_COMMUNICATION,
                modeRequestedByEnhanced = true,
                alreadyReported = false,
            )
        )

        assertFalse(shouldReport)
    }

    @Test
    fun `does not report the same residue twice in a row`() {
        val shouldReport = ModeResidueResolver.shouldReport(
            ModeResidueInput(
                mode = ModeResidueResolver.MODE_IN_COMMUNICATION,
                modeRequestedByEnhanced = false,
                alreadyReported = true,
            )
        )

        assertFalse(shouldReport)
    }

    @Test
    fun `does not report for normal mode`() {
        val shouldReport = ModeResidueResolver.shouldReport(
            ModeResidueInput(
                mode = 0,
                modeRequestedByEnhanced = false,
                alreadyReported = false,
            )
        )

        assertFalse(shouldReport)
    }

    @Test
    fun `clears reported flag when mode leaves communication`() {
        assertTrue(ModeResidueResolver.shouldClearReportedFlag(mode = 0))
        assertFalse(
            ModeResidueResolver.shouldClearReportedFlag(
                mode = ModeResidueResolver.MODE_IN_COMMUNICATION
            )
        )
    }

    @Test
    fun `mode constant matches platform value`() {
        assertEquals(3, ModeResidueResolver.MODE_IN_COMMUNICATION)
    }
}
