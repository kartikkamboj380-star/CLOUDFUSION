package com.cloudfusion.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {
    @Test fun formatsByteCountsWithoutAssumingGoogleQuota() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("1.0 GiB", formatBytes(1024L * 1024 * 1024))
        assertEquals("Unavailable", formatBytes(null))
    }
}
