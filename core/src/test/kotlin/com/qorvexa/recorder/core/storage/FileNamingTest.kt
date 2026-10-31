package com.qorvexa.recorder.core.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class FileNamingTest {
    private val t = LocalDateTime.of(2026, 11, 5, 14, 3, 9)

    @Test fun folderByYearAndMonth() = assertEquals("ScreenRecorder/2026/November", FileNaming.folderFor(t))

    @Test fun defaultPattern() = assertEquals("QORVEXA_Recording_001", FileNaming.expand("QORVEXA_Recording_{n}", 1, t, "1080p", 60))

    @Test fun tokens() = assertEquals("Game_2026-11-05_14-03-09_1080p_60", FileNaming.expand("Game_{date}_{time}_{res}_{fps}", 7, t, "1080p", 60))

    @Test fun sanitizesIllegalCharacters() = assertEquals("a_b_c", FileNaming.sanitize("a/b:c"))

    @Test fun uniqueAppendsSuffix() =
        assertEquals("x (3).mp4", FileNaming.unique("x", "mp4", setOf("X.mp4", "x (2).mp4")))

    @Test fun nextCounter() =
        assertEquals(13, FileNaming.nextCounter(listOf("QORVEXA_Recording_012.mp4", "QORVEXA_Recording_003.mp4", "other.mp4")))
}
