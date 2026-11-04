package com.taraang.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 스키마 마이그레이션 검사(TaraangDatabase KDoc 규칙). 기기 없이 sqlite-jdbc로:
 * 동결된 1.json으로 만든 DB에 실제 행을 넣고 → 앱이 쓰는 [MIGRATION_1_2_SQL]을 돌린 뒤 →
 * (1) 행이 그대로 남는지 (2) 표 구조가 Room이 내보낸 2.json과 같은지 본다.
 */
class MigrationSqlTest {

    private val tables = listOf("sessions", "events", "feelings", "goals")

    @Test
    fun `v1 to v2 keeps every row and matches the exported v2 schema`() {
        SqliteTestDb.fromSchema(1).use { db ->
            db.exec("INSERT INTO sessions (id, courseId, courseLevel, mode, startedAtMs, endedAtMs, interrupted) VALUES ('s1', 'L2-x', 2, 'LIVE', 100, 400000, 0)")
            db.exec("INSERT INTO events (sessionId, type, timestampMs, lat, lon, maneuverIndex, detail) VALUES ('s1', 'HARD_BRAKE', 150, 35.231, 129.084, 3, 'd')")
            db.exec("INSERT INTO feelings (sessionId, maneuverIndex, feeling) VALUES ('s1', 3, 'OK')")
            db.exec(
                "INSERT INTO goals (id, name, originLat, originLon, destinationLat, destinationLon, skillsJson, hardSpotsJson, createdAtMs) " +
                    "VALUES ('g1', '출근', 35.2, 129.0, 35.3, 129.1, '{}', '[]', 1)",
            )

            MIGRATION_1_2_SQL.forEach(db::exec)

            assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM sessions WHERE id = 's1' AND courseLevel = 2 AND endedAtMs = 400000"))
            assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM sessions WHERE courseJson IS NULL AND courseDistanceM IS NULL AND drivenM IS NULL"))
            assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM events WHERE sessionId = 's1' AND maneuverIndex = 3"))
            assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM feelings"))
            assertEquals(1L, db.queryLong("SELECT COUNT(*) FROM goals"))
            // 외래키·CASCADE가 마이그레이션 뒤에도 산다.
            db.exec("DELETE FROM sessions WHERE id = 's1'")
            assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM events"))

            SqliteTestDb.fromSchema(2).use { fresh ->
                tables.forEach { t ->
                    assertEquals("$t columns", fresh.columns(t), db.columns(t))
                    assertEquals("$t indices", fresh.indices(t), db.indices(t))
                }
            }
        }
    }

    @Test
    fun `old v1 rows still count by duration after migration, new rows by distance too`() {
        SqliteTestDb.fromSchema(1).use { db ->
            val min = com.taraang.app.data.DriveRepositoryImpl.MIN_COUNTED_DURATION_MS
            db.exec("INSERT INTO sessions (id, courseId, courseLevel, mode, startedAtMs, endedAtMs, interrupted) VALUES ('old', 'L2-x', 2, 'LIVE', 0, $min, 0)")
            MIGRATION_1_2_SQL.forEach(db::exec)
            // 짧지만 코스를 70% 이상 돈 v2 행.
            db.exec(
                "INSERT INTO sessions (id, courseId, courseLevel, mode, startedAtMs, endedAtMs, interrupted, courseDistanceM, drivenM) " +
                    "VALUES ('new', 'L3-x', 3, 'LIVE', 0, 60000, 0, 1000, 700.0)",
            )
            db.exec(
                "INSERT INTO sessions (id, courseId, courseLevel, mode, startedAtMs, endedAtMs, interrupted, courseDistanceM, drivenM) " +
                    "VALUES ('short', 'L4-x', 4, 'LIVE', 0, 60000, 0, 1000, 699.0)",
            )
            assertEquals(3L, db.queryLong(SessionQueries.MAX_COUNTED_LEVEL, mapOf("minDurationMs" to min)))
            assertEquals(1L, db.queryLong(SessionQueries.COUNT_COUNTED_AT_LEVEL, mapOf("level" to 2, "minDurationMs" to min)))
            assertEquals(0L, db.queryLong(SessionQueries.COUNT_COUNTED_AT_LEVEL, mapOf("level" to 4, "minDurationMs" to min)))
        }
    }

    @Test
    fun `v1 schema file is frozen`() {
        // 1.json이 바뀌면 이미 설치된 v1 DB를 검증할 근거가 사라진다. 바꿔야 한다면 버전을 올린다.
        val text = SqliteTestDb.schemaFile(1).readText()
        assertTrue(text.contains("\"version\": 1"))
        assertTrue(text.contains("c4cd87cdd44adb1c00543ccbf15289ef"))
    }
}
