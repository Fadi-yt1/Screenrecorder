package com.qorvexa.recorder.core.storage

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object FileNaming {
    const val ROOT_DIR = "ScreenRecorder"
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** Relative folder under Movies/ (or Pictures/), organised by year and month, e.g. "ScreenRecorder/2026/November". */
    fun folderFor(time: LocalDateTime): String {
        val month = time.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "$ROOT_DIR/${time.year}/$month"
    }

    /**
     * Expands a user pattern. Tokens: {n} counter (3 digits), {date} yyyy-MM-dd,
     * {time} HH-mm-ss, {res} e.g. 1080p, {fps}. Unsafe characters are removed.
     */
    fun expand(pattern: String, counter: Int, time: LocalDateTime, resLabel: String, fps: Int): String {
        val base = pattern.ifBlank { "QORVEXA_Recording_{n}" }
            .replace("{n}", counter.toString().padStart(3, '0'))
            .replace("{date}", time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
            .replace("{time}", time.format(DateTimeFormatter.ofPattern("HH-mm-ss")))
            .replace("{res}", resLabel)
            .replace("{fps}", fps.toString())
        return sanitize(base)
    }

    fun sanitize(name: String): String {
        val cleaned = name.replace(ILLEGAL, "_").trim().trim('.')
        return cleaned.take(120).ifBlank { "QORVEXA_Recording" }
    }

    /** Ensures "name.ext" is unique against [existing] (case-insensitive) by appending (2), (3)… */
    fun unique(baseName: String, ext: String, existing: Set<String>): String {
        val lower = existing.map { it.lowercase() }.toSet()
        var candidate = "$baseName.$ext"
        var i = 2
        while (candidate.lowercase() in lower) {
            candidate = "$baseName ($i).$ext"
            i++
        }
        return candidate
    }

    /** Next counter value given existing names produced by the default pattern. */
    fun nextCounter(existing: Collection<String>): Int {
        val re = Regex("_(\\d{3,})(?: \\(\\d+\\))?\\.(mp4|png)$", RegexOption.IGNORE_CASE)
        val max = existing.mapNotNull { re.find(it)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
        return max + 1
    }
}
