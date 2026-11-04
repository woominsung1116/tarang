package com.taraang.app.data

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.data.db.EventDao
import com.taraang.app.data.db.EventEntity
import com.taraang.app.data.db.FeelingEntity
import com.taraang.app.domain.model.SignalMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 사용자 요청 기록 삭제(L1): 한 트랜잭션, 주행 중이면 거절. */
class DriveRepositoryDeleteTest {

    private val db = FakeDriveDb()

    private fun seedFinished() {
        db.sessions += db.session("done", startedAtMs = 10, endedAtMs = 20)
        db.events += EventEntity(sessionId = "done", type = "HARD_BRAKE", timestampMs = 15, lat = 35.231, lon = 129.084, maneuverIndex = null, detail = "")
        db.feelings += FeelingEntity("done", 0, "OK")
    }

    @Test
    fun `deletes events, feelings and sessions in one transaction`() = runBlocking {
        seedFinished()
        val r = db.repo()
        db.txCount = 0
        assertEquals(AppResult.Ok(Unit), r.deleteAllHistory())
        assertTrue(db.sessions.isEmpty() && db.events.isEmpty() && db.feelings.isEmpty())
        assertEquals(1, db.txCount)
    }

    @Test
    fun `refuses while a drive started in this process is still running`() = runBlocking {
        seedFinished()
        val r = db.repo()
        db.now += 1_000
        r.startSession("L1-x", SignalMode.REPLAY)
        val result = r.deleteAllHistory()
        assertTrue((result as AppResult.Err).error is AppError.InvalidInput)
        assertEquals(2, db.sessions.size)
        assertEquals(1, db.events.size)
        assertEquals(1, db.feelings.size)
    }

    @Test
    fun `open session left by a previous process does not block deletion`() = runBlocking {
        seedFinished()
        db.sessions += db.session("crashed", startedAtMs = 30, endedAtMs = null)
        assertEquals(AppResult.Ok(Unit), db.repo().deleteAllHistory())
        assertTrue(db.sessions.isEmpty())
    }

    @Test
    fun `storage failure is an error, not an exception`() = runBlocking {
        val failing = object : EventDao by db.eventDao {
            override suspend fun deleteAll(): Int = error("disk")
        }
        assertTrue((db.repo(eventDao = failing).deleteAllHistory() as AppResult.Err).error is AppError.Storage)
    }
}
