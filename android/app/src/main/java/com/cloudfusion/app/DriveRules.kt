package com.cloudfusion.app

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
const val FOLDER_MIME = "application/vnd.google-apps.folder"
const val CHUNK_SIZE = 4 * 1024 * 1024 // A multiple of Drive's 256 KiB requirement.
const val MAX_TRANSFER_SIZE = 5L * 1024 * 1024 * 1024 * 1024

fun formatBytes(value: Long?): String {
    if (value == null || value < 0) return "Unavailable"
    if (value < 1024) return "$value B"
    val exponent = (ln(value.toDouble()) / ln(1024.0)).toInt().coerceAtMost(4)
    return String.format(Locale.US, "%.1f %s", value / 1024.0.pow(exponent.toDouble()), listOf("B", "KiB", "MiB", "GiB", "TiB")[exponent])
}

data class QuotaSnapshot(
    val id: String,
    val used: Long?,
    val limit: Long?,
    val unlimited: Boolean,
    val available: Boolean = true
) {
    fun canFit(bytes: Long): Boolean {
        if (!available || bytes <= 0 || used == null || used < 0) return false
        if (unlimited) return true
        val cap = limit ?: return false
        return cap >= 0 && used <= cap && bytes <= cap - used
    }
}

data class AggregateQuota(val used: Long?, val capacity: Long?, val remaining: Long?)

/** Never turn a missing quota into zero, or overflow a sum into a plausible value. */
fun aggregateQuota(quotas: List<QuotaSnapshot>): AggregateQuota {
    if (quotas.isEmpty() || quotas.any { !it.available || it.used == null || it.used < 0 }) {
        return AggregateQuota(null, null, null)
    }
    fun checkedSum(values: List<Long>): Long? = try {
        values.fold(0L) { acc, value -> Math.addExact(acc, value) }
    } catch (_: ArithmeticException) { null }
    val used = checkedSum(quotas.map { it.used!! })
    if (quotas.any { it.unlimited || it.limit == null || it.limit < 0 }) return AggregateQuota(used, null, null)
    val capacity = checkedSum(quotas.map { it.limit!! })
    // Quotas are independent: an overfull account must not consume another account's available space.
    val remaining = if (used != null && capacity != null) checkedSum(quotas.map { (it.limit!! - it.used!!).coerceAtLeast(0) }) else null
    return AggregateQuota(used, capacity, remaining)
}

/** Choose a whole-file destination, never assume a default Drive size. */
fun chooseDestination(quotas: List<QuotaSnapshot>, bytes: Long, selected: String? = null): QuotaSnapshot? {
    if (bytes <= 0 || bytes > MAX_TRANSFER_SIZE) return null
    val eligible = quotas.filter { it.canFit(bytes) && (selected == null || it.id.equals(selected, true)) }
    return eligible.minWithOrNull(compareBy<QuotaSnapshot>(
        { if (it.unlimited) 0.0 else (it.used!!.toDouble() + bytes.toDouble()) / it.limit!!.toDouble() },
        { it.id.lowercase(Locale.ROOT) }
    ))
}

fun escapeDriveLiteral(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

/** The predicates are sent to Drive, not interpolated as raw URL text. */
fun buildDriveQuery(search: String, type: String): String {
    val parts = mutableListOf("trashed = false")
    if (search.trim().isNotEmpty()) parts += "name contains '${escapeDriveLiteral(search.trim())}'"
    val predicate = when (type) {
        "images" -> "mimeType contains 'image/'"
        "videos" -> "mimeType contains 'video/'"
        "audio" -> "mimeType contains 'audio/'"
        "folders" -> "mimeType = '$FOLDER_MIME'"
        "documents" -> "(mimeType contains 'text/' or mimeType = 'application/pdf' or mimeType = 'application/vnd.google-apps.document' or mimeType = 'application/vnd.google-apps.spreadsheet' or mimeType = 'application/vnd.google-apps.presentation')"
        else -> null
    }
    if (predicate != null) parts += predicate
    return parts.joinToString(" and ")
}

/** Drive 308 Range describes committed bytes, not bytes the client attempted to send. */
fun committedOffset(range: String?, total: Long): Long {
    require(total > 0) { "Invalid transfer size." }
    if (range.isNullOrBlank()) return 0
    val match = Regex("bytes=0-([0-9]+)").matchEntire(range.trim())
        ?: throw IllegalStateException("Google returned an invalid upload range. Resume to check the session.")
    val end = match.groupValues[1].toLongOrNull()
        ?: throw IllegalStateException("Google returned an invalid upload range.")
    require(end < total) { "Google returned an upload offset outside the file." }
    return end + 1
}

fun sameGoogleAccount(selected: String, returned: String?): Boolean =
    !returned.isNullOrBlank() && selected.trim().equals(returned.trim(), ignoreCase = true)

fun isWorkspaceNative(mime: String): Boolean = mime.startsWith("application/vnd.google-apps.")

/** Completion without a checksum is not equivalent to verified completion. */
enum class CompletionIntegrity { VERIFIED, UNVERIFIED, MISMATCH }

fun completionIntegrity(expectedSize: Long, actualSize: Long?, expectedMd5: String?, actualMd5: String?): CompletionIntegrity {
    require(expectedSize >= 0)
    if (actualSize != null && actualSize != expectedSize) return CompletionIntegrity.MISMATCH
    if (expectedMd5.isNullOrBlank() || actualMd5.isNullOrBlank()) return CompletionIntegrity.UNVERIFIED
    return if (expectedMd5.equals(actualMd5, ignoreCase = true)) CompletionIntegrity.VERIFIED else CompletionIntegrity.MISMATCH
}
