package com.taraang.app.domain

import com.taraang.app.domain.model.DriveSession
import com.taraang.app.domain.model.DriveSummary
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.model.farFromStartM
import com.taraang.app.feedback.START
import com.taraang.app.feedback.north
import com.taraang.app.feedback.testCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRulesTest {

    @Test
    fun `levels up to the current one are unlocked, the next opens only when ready`() {
        val p = LevelProgress(2, 1, 3)
        assertTrue(p.isUnlocked(1))
        assertTrue(p.isUnlocked(2))
        assertFalse(p.isUnlocked(3))
        assertTrue(LevelProgress(2, 3, 3).isUnlocked(3))
        assertFalse(LevelProgress(2, 3, 3).isUnlocked(4))
    }

    @Test
    fun `a drive that stopped before most of the course is partial`() {
        val course = testCourse(lengthM = 1_000.0)
        fun summary(drivenM: Double?, interrupted: Boolean = false, ended: Long? = 10) = DriveSummary(
            DriveSession("s", course.id, SignalMode.LIVE, 0, ended, interrupted, drivenM), course, emptyList(), emptyList(), 0, 0, null,
        )
        assertTrue(summary(200.0).partial)
        assertTrue(summary(0.0).partial)
        assertFalse(summary(700.0).partial)
        assertFalse(summary(null).partial) // 진행 거리를 모르는 옛 기록
        assertFalse(summary(100.0, interrupted = true).partial) // 중단은 따로 표시한다
        assertFalse(summary(100.0, ended = null).partial)
    }

    @Test
    fun `far start is only reported beyond 1 km`() {
        val course = testCourse()
        assertNull(course.farFromStartM(null))
        assertNull(course.farFromStartM(START.north(900.0)))
        assertEquals(1_500.0, course.farFromStartM(START.north(1_500.0))!!, 5.0)
    }
}
