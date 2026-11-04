package com.taraang.app.data.db

import com.taraang.app.data.DriveRepositoryImpl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Daos.kt의 단계 진행 SQL([SessionQueries])을 실제 SQLite에 돌려 본다(FakeDriveDb의 조건과 같은지). */
class SessionQueriesTest {

    private val db = SqliteTestDb.fromSchema(2)
    private val min = DriveRepositoryImpl.MIN_COUNTED_DURATION_MS

    @After
    fun tearDown() = db.close()

    private fun insert(
        id: String, level: Int, mode: String, startedAtMs: Long, endedAtMs: Long?, interrupted: Boolean = false,
        courseDistanceM: Int? = null, drivenM: Double? = null,
    ) = db.exec(
        "INSERT INTO sessions (id, courseId, courseLevel, mode, startedAtMs, endedAtMs, interrupted, courseDistanceM, drivenM) " +
            "VALUES ('$id', 'L$level-x', $level, '$mode', $startedAtMs, ${endedAtMs ?: "NULL"}, ${if (interrupted) 1 else 0}, " +
            "${courseDistanceM ?: "NULL"}, ${drivenM ?: "NULL"})",
    )

    @Test
    fun `short drive counts when it covered 70 percent of the course`() {
        insert("short-far", 3, "LIVE", 0, 60_000, courseDistanceM = 2000, drivenM = 1400.0)
        insert("short-near", 4, "LIVE", 0, 60_000, courseDistanceM = 2000, drivenM = 1399.0)
        insert("replay-far", 4, "REPLAY", 0, 60_000, courseDistanceM = 2000, drivenM = 2000.0)
        insert("no-distance", 4, "LIVE", 0, 60_000, courseDistanceM = 0, drivenM = 10.0)
        assertEquals(3L, db.queryLong(SessionQueries.MAX_COUNTED_LEVEL, mapOf("minDurationMs" to min)))
    }

    @Test
    fun `only finished live drives of the minimum duration are counted`() {
        insert("live-l2", 2, "LIVE", 0, min)
        insert("live-l2b", 2, "LIVE", 1_000, 1_000 + min + 1)
        insert("live-l3-short", 3, "LIVE", 0, min - 1)
        insert("replay-l4", 4, "REPLAY", 0, 10 * min)
        insert("live-l4-interrupted", 4, "LIVE", 0, 10 * min, interrupted = true)
        insert("live-l4-open", 4, "LIVE", 0, null)

        assertEquals(2L, db.queryLong(SessionQueries.MAX_COUNTED_LEVEL, mapOf("minDurationMs" to min)))
        assertEquals(2L, db.queryLong(SessionQueries.COUNT_COUNTED_AT_LEVEL, mapOf("level" to 2, "minDurationMs" to min)))
        assertEquals(0L, db.queryLong(SessionQueries.COUNT_COUNTED_AT_LEVEL, mapOf("level" to 4, "minDurationMs" to min)))
    }

    @Test
    fun `a long parked live drive does not count, only legacy rows without course distance count by time`() {
        insert("parked", 4, "LIVE", 0, 10 * min, courseDistanceM = 2000, drivenM = 0.0)
        insert("parked-no-position", 4, "LIVE", 0, 10 * min, courseDistanceM = 2000, drivenM = null)
        insert("v1-legacy", 2, "LIVE", 0, min)
        assertEquals(2L, db.queryLong(SessionQueries.MAX_COUNTED_LEVEL, mapOf("minDurationMs" to min)))
        assertEquals(0L, db.queryLong(SessionQueries.COUNT_COUNTED_AT_LEVEL, mapOf("level" to 4, "minDurationMs" to min)))
    }

    @Test
    fun `no counted drives gives null max level`() {
        insert("replay", 2, "REPLAY", 0, 10 * min)
        assertEquals(null, db.queryLong(SessionQueries.MAX_COUNTED_LEVEL, mapOf("minDurationMs" to min)))
    }
}
