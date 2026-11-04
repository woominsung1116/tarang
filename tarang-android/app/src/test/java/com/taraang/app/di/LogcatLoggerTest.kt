package com.taraang.app.di

import com.taraang.app.data.DriveRepositoryImpl
import org.junit.Assert.assertEquals
import org.junit.Test

class LogcatLoggerTest {

    @Test
    fun `precise coordinates are cut to 3 decimals`() {
        assertEquals("위치 35.231,129.084 도착", LogcatLogger.scrub("위치 35.231049,129.084372 도착"))
        assertEquals("음수 -12.345", LogcatLogger.scrub("음수 -12.34567"))
    }

    @Test
    fun `ordinary numbers are untouched`() {
        assertEquals("재시도 1/2, 500ms, 36.0km/h", LogcatLogger.scrub("재시도 1/2, 500ms, 36.0km/h"))
    }

    @Test
    fun `stack trace is scrubbed together with the message`() {
        val stack = "java.lang.IllegalStateException: 위치 35.231049,129.084372\n\tat Foo.bar(Foo.kt:12)"
        assertEquals(
            "저장 실패\njava.lang.IllegalStateException: 위치 35.231,129.084\n\tat Foo.bar(Foo.kt:12)",
            LogcatLogger.render("저장 실패", stack),
        )
        assertEquals("메시지만 1.234", LogcatLogger.render("메시지만 1.23456", ""))
    }

    @Test
    fun `course level is read from id when course is unknown`() {
        assertEquals(2, DriveRepositoryImpl.levelFromId("L2-35.2310-129.0840-s7"))
        assertEquals(4, DriveRepositoryImpl.levelFromId("L9-x"))
        assertEquals(1, DriveRepositoryImpl.levelFromId("custom"))
    }
}
