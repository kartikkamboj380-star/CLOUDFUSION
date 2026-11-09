package com.cloudfusion.app

import org.junit.Assert.*
import org.junit.Test

class DriveRulesTest {
    @Test fun allocationUsesKnownRealQuotaAndKeepsFilesWhole() {
        val a = QuotaSnapshot("a", 60, 100, false)
        val b = QuotaSnapshot("b", 10, 100, false)
        val missing = QuotaSnapshot("missing", null, null, false, false)
        assertEquals("b", chooseDestination(listOf(a, b, missing), 20)?.id)
        assertEquals("a", chooseDestination(listOf(a, b), 20, "a")?.id)
        assertNull(chooseDestination(listOf(a, b), 41, "a"))
        assertNull(chooseDestination(listOf(missing), 1))
        assertNull(chooseDestination(listOf(b), 0))
        assertNull(chooseDestination(listOf(b), MAX_TRANSFER_SIZE + 1))
    }

    @Test fun unlimitedAndUnknownQuotaAreDifferent() {
        val unlimited = QuotaSnapshot("unlimited", 10, null, true)
        assertEquals("unlimited", chooseDestination(listOf(unlimited), 500)?.id)
        assertEquals(AggregateQuota(10, null, null), aggregateQuota(listOf(unlimited)))
        assertEquals(AggregateQuota(null, null, null), aggregateQuota(listOf(unlimited, QuotaSnapshot("unknown", null, null, false))))
        assertEquals(AggregateQuota(null, null, null), aggregateQuota(emptyList()))
        assertEquals(AggregateQuota(30, 100, 70), aggregateQuota(listOf(QuotaSnapshot("a", 30, 100, false))))
    }

    @Test fun quotaMathNeverWraps() {
        val huge = listOf(QuotaSnapshot("a", Long.MAX_VALUE, Long.MAX_VALUE, false), QuotaSnapshot("b", 1, 10, false))
        assertNull(aggregateQuota(huge).used)
        assertNull(aggregateQuota(huge).capacity)
        assertNull(chooseDestination(listOf(huge[0]), 1))
        assertFalse(QuotaSnapshot("bad", -1, 100, false).canFit(1))
    }

    @Test fun queryEscapesSearchAndKeepsFilters() {
        val q = buildDriveQuery("O'Brien\\notes", "images")
        assertTrue(q.contains("name contains 'O\\'Brien\\\\notes'"))
        assertTrue(q.contains("mimeType contains 'image/'"))
        assertTrue(q.startsWith("trashed = false"))
        assertTrue(buildDriveQuery("", "folders").contains(FOLDER_MIME))
    }

    @Test fun resumeRangeUsesOnlyServerAcknowledgedBytes() {
        assertEquals(0, committedOffset(null, 100))
        assertEquals(1, committedOffset("bytes=0-0", 100))
        assertEquals(100, committedOffset("bytes=0-99", 100))
        assertThrows(IllegalStateException::class.java) { committedOffset("bytes=5-10", 100) }
        assertThrows(IllegalArgumentException::class.java) { committedOffset("bytes=0-100", 100) }
        assertThrows(IllegalStateException::class.java) { committedOffset("bytes=0-999999999999999999999", 100) }
    }

    @Test fun selectedAccountMustMatchDriveIdentity() {
        assertTrue(sameGoogleAccount("person@example.com", "PERSON@example.com"))
        assertFalse(sameGoogleAccount("person@example.com", "other@example.com"))
        assertFalse(sameGoogleAccount("person@example.com", null))
        assertTrue(isWorkspaceNative(FOLDER_MIME))
        assertFalse(isWorkspaceNative("application/pdf"))
    }
}
