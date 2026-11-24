package com.example.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToLong

object RelativeTimeFormatter {

    const val ACTIVE_WINDOW_MS: Long = 24L * 60L * 60L * 1000L // 24 hours
    const val RETENTION_WINDOW_MS: Long = 48L * 60L * 60L * 1000L // 48 hours
    const val DAY_WINDOW_MS: Long = 24L * 60L * 60L * 1000L // 24 hours

    fun isLineActive(lastAtEpochMs: Long?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lastAtEpochMs == null || lastAtEpochMs <= 0L) return false
        return (nowMs - lastAtEpochMs) < ACTIVE_WINDOW_MS
    }

    fun formatRelative(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val delta = (nowMs - epochMs).coerceAtLeast(0L)
        val seconds = delta / 1000L
        if (seconds < 45L) return "Just now"
        val mins = (delta / 60_000.0).roundToLong()
        if (mins < 60L) return "${mins}m ago"
        val hours = (mins / 60.0).roundToLong()
        if (hours < 24L) return "${hours}h ago"
        val days = (hours / 24.0).roundToLong()
        return "${days}d ago"
    }

    fun formatAbsoluteUtc(epochMs: Long): String {
        val formatter = SimpleDateFormat("MMM d, HH:mm 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return formatter.format(Date(epochMs))
    }

    fun formatShortClock(epochMs: Long): String {
        val formatter = SimpleDateFormat("HH:mm:ss", Locale.US)
        return formatter.format(Date(epochMs))
    }
}
