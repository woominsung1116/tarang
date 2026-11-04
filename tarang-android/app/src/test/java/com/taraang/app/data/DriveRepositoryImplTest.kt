package com.taraang.app.data

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.data.db.EventEntity
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.feedback.testCourse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRepositoryImplTest {

    private val db = FakeDriveDb()
    private val minutes = 60_000L

    @Test
    fun `finishSession is idempotent and keeps the first end time`() = runBlocking {
        val r = db.repo()
        val s = (r.startSession("L1-x", SignalMode.LIVE) as AppResult.Ok).value
        db.now += 5 * minutes
        val first = (r.finishSession(s.id) as AppResult.Ok).value
        db.now += 10 * minutes
        val second = (r.finishSession(s.id) as AppResult.Ok).value
        assertEquals(first.endedAtMs, second.endedAtMs)
        assertFalse(second.interrupted)
    }

    @Test
    fun `review shows the course as driven even if the same id later changes`() = runBlocking {
        val driven = testCourse().copy(id = "L1-same", title = "그때 코스", distanceM = 1200)
        db.currentCourse = driven
        val r = db.repo()
        val s = (r.startSession("L1-same", SignalMode.LIVE) as AppResult.Ok).value
        r.finishSession(s.id, drivenAlongCourseM = 900.0)
        assertEquals(1200, db.sessions.single().courseDistanceM)
        assertEquals(900.0, db.sessions.single().drivenM!!, 0.0)

        db.currentCourse = driven.copy(title = "바뀐 코스", path = driven.path.take(2))
        val summary = (r.summary(s.id) as AppResult.Ok).value
        assertEquals(driven, summary.course)
    }

    @Test
    fun `old rows without a snapshot fall back to looking the course up`() = runBlocking {
        val now = testCourse().copy(id = "L1-old")
        db.currentCourse = now
        db.sessions += db.session("v1-row", 0, 10, level = 1).copy(courseId = "L1-old")
        assertEquals(now, (db.repo().summary("v1-row") as AppResult.Ok).value.course)
        // 깨진 스냅샷도 같은 대체 경로.
        db.sessions += db.session("broken", 0, 10).copy(courseId = "L1-old", courseJson = "{nope")
        assertEquals(now, (db.repo().summary("broken") as AppResult.Ok).value.course)
    }

    @Test
    fun `nonsense driven distance is not stored`() = runBlocking {
        val r = db.repo()
        val a = (r.startSession("L1-x", SignalMode.LIVE) as AppResult.Ok).value
        r.finishSession(a.id, drivenAlongCourseM = Double.NaN)
        val b = (r.startSession("L1-x", SignalMode.LIVE) as AppResult.Ok).value
        r.finishSession(b.id, drivenAlongCourseM = -5.0)
        assertTrue(db.sessions.all { it.drivenM == null })
    }

    @Test
    fun `a failed drive is stored as interrupted and is never the previous drive or counted`() = runBlocking {
        val r = db.repo()
        val ok = (r.startSession("L1-x", SignalMode.LIVE) as AppResult.Ok).value
        db.now += 10 * minutes
        r.finishSession(ok.id)
        db.now += minutes
        val failed = (r.startSession("L1-x", SignalMode.LIVE) as AppResult.Ok).value
        db.now += 10 * minutes
        assertTrue((r.finishSession(failed.id, interrupted = true) as AppResult.Ok).value.interrupted)
        db.now += minutes
        val next = (r.startSession("L1-x", SignalMode.LIVE) as AppResult.Ok).value
        assertEquals(ok.id, (r.summary(next.id) as AppResult.Ok).value.previous?.session?.id)
        assertEquals(1, r.levelProgress().completedCoursesAtLevel)
    }

    @Test
    fun `a live drive is compared with the previous live drive, never with a replay demo`() = runBlocking {
        val r = db.repo()
        fun start(mode: SignalMode) = runBlocking { (r.startSession("L1-x", mode) as AppResult.Ok).value.also { db.now += minutes } }
        val live = start(SignalMode.LIVE).also { r.finishSession(it.id) }
        val replay = start(SignalMode.REPLAY).also { r.finishSession(it.id) }
        val liveNow = start(SignalMode.LIVE)
        val replayNow = start(SignalMode.REPLAY)
        assertEquals(live.id, (r.summary(liveNow.id) as AppResult.Ok).value.previous?.session?.id)
        assertEquals(replay.id, (r.summary(replayNow.id) as AppResult.Ok).value.previous?.session?.id)
        assertEquals(null, (r.summary(live.id) as AppResult.Ok).value.previous)
    }

    @Test
    fun `finishing an unknown session is a storage error`() = runBlocking {
        assertTrue((db.repo().finishSession("nope") as AppResult.Err).error is AppError.Storage)
    }

    @Test
    fun `interrupted sessions from an earlier process close at their last event, current ones stay open`() = runBlocking {
        db.sessions += db.session("old-with-events", startedAtMs = 100, endedAtMs = null)
        db.sessions += db.session("old-empty", startedAtMs = 200, endedAtMs = null)
        db.events += EventEntity(sessionId = "old-with-events", type = "HARD_BRAKE", timestampMs = 150, lat = null, lon = null, maneuverIndex = null, detail = "")
        val r = db.repo()
        db.now += minutes
        val current = (r.startSession("L1-x", SignalMode.LIVE) as AppResult.Ok).value

        assertEquals(2, r.closeInterruptedSessions())
        val byId = db.sessions.associateBy { it.id }
        assertEquals(150L, byId.getValue("old-with-events").endedAtMs)
        assertTrue(byId.getValue("old-with-events").interrupted)
        assertEquals(200L, byId.getValue("old-empty").endedAtMs)
        assertEquals(null, byId.getValue(current.id).endedAtMs)
        // 두 번째 호출은 할 일이 없다.
        assertEquals(0, r.closeInterruptedSessions())
    }

    @Test
    fun `level progress counts only finished live drives of at least 3 minutes`() = runBlocking {
        val min = DriveRepositoryImpl.MIN_COUNTED_DURATION_MS
        db.sessions += db.session("live-l2-ok", 0, min, level = 2)
        db.sessions += db.session("live-l2-ok2", 0, min + 1, level = 2)
        db.sessions += db.session("live-l3-short", 0, min - 1, level = 3)
        db.sessions += db.session("replay-l4", 0, 10 * min, mode = "REPLAY", level = 4)
        db.sessions += db.session("live-l4-interrupted", 0, 10 * min, level = 4, interrupted = true)
        db.sessions += db.session("live-l4-open", 0, null, level = 4)
        assertEquals(LevelProgress(2, 2, DriveRepositoryImpl.REQUIRED_TO_ADVANCE), db.repo().levelProgress())
    }

    @Test
    fun `a long parked live drive does not level up`() = runBlocking {
        val r = db.repo()
        db.currentCourse = testCourse().copy(id = "L3-parked", level = 3, distanceM = 1500)
        val s = (r.startSession("L3-parked", SignalMode.LIVE) as AppResult.Ok).value
        db.now += 10 * minutes
        r.finishSession(s.id, drivenAlongCourseM = 0.0)
        assertEquals(LevelProgress(1, 0, DriveRepositoryImpl.REQUIRED_TO_ADVANCE), r.levelProgress())
    }

    @Test
    fun `replay-only history stays at level 1`() = runBlocking {
        repeat(5) { db.sessions += db.session("r$it", 0, 10 * minutes, mode = "REPLAY", level = 2) }
        assertEquals(LevelProgress(1, 0, DriveRepositoryImpl.REQUIRED_TO_ADVANCE), db.repo().levelProgress())
    }
}
