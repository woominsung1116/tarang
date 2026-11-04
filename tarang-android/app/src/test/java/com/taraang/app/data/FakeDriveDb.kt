package com.taraang.app.data

import com.taraang.app.core.AppResult
import com.taraang.app.core.Clock
import com.taraang.app.core.LatLng
import com.taraang.app.core.Logger
import com.taraang.app.data.db.EventDao
import com.taraang.app.data.db.EventEntity
import com.taraang.app.data.db.FeelingDao
import com.taraang.app.data.db.FeelingEntity
import com.taraang.app.data.db.SessionDao
import com.taraang.app.data.db.SessionEntity
import com.taraang.app.data.db.TransactionRunner
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.Skill
import com.taraang.app.domain.repository.CourseRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * DriveRepositoryImpl 테스트용 메모리 DB. DAO 의미(조건)는 Daos.kt의 SQL을 그대로 옮겼고,
 * SQL 자체는 SessionQueriesTest가 실제 SQLite로 확인한다.
 */
class FakeDriveDb {
    val sessions = mutableListOf<SessionEntity>()
    val events = mutableListOf<EventEntity>()
    val feelings = mutableListOf<FeelingEntity>()
    var txCount = 0
    var now = 1_000_000L

    private fun SessionEntity.counted(minDurationMs: Long): Boolean {
        if (endedAtMs == null || interrupted || mode != "LIVE") return false
        val drivenEnough = drivenM != null && courseDistanceM != null && courseDistanceM > 0 &&
            drivenM >= com.taraang.app.data.db.SessionQueries.MIN_DRIVEN_RATIO * courseDistanceM
        return drivenEnough || (courseDistanceM == null && endedAtMs - startedAtMs >= minDurationMs)
    }

    val sessionDao = object : SessionDao {
        override fun observeAll(): Flow<List<SessionEntity>> = flowOf(sessions.toList())
        override suspend fun insert(session: SessionEntity) {
            require(sessions.none { it.id == session.id }) { "중복 id" }
            sessions += session
        }
        override suspend fun get(id: String) = sessions.firstOrNull { it.id == id }
        override suspend fun finish(id: String, endedAtMs: Long, interrupted: Boolean, drivenM: Double?): Int {
            val i = sessions.indexOfFirst { it.id == id && it.endedAtMs == null }
            if (i < 0) return 0
            sessions[i] = sessions[i].copy(endedAtMs = endedAtMs, interrupted = interrupted, drivenM = drivenM)
            return 1
        }
        override suspend fun previousCompleted(courseId: String, mode: String, before: Long): SessionEntity? =
            sessions.filter { it.courseId == courseId && it.mode == mode && it.startedAtMs < before && it.endedAtMs != null && !it.interrupted }
                .maxByOrNull { it.startedAtMs }
        override suspend fun openStartedBefore(before: Long) = sessions.filter { it.endedAtMs == null && it.startedAtMs < before }
        override suspend fun maxCountedLevel(minDurationMs: Long): Int? =
            sessions.filter { it.counted(minDurationMs) }.maxOfOrNull { it.courseLevel }
        override suspend fun countCountedAtLevel(level: Int, minDurationMs: Long) =
            sessions.count { it.courseLevel == level && it.counted(minDurationMs) }
        override suspend fun countOpenStartedSince(since: Long) = sessions.count { it.endedAtMs == null && it.startedAtMs >= since }
        override suspend fun deleteAll() = sessions.size.also { sessions.clear() }
    }

    val eventDao = object : EventDao {
        override suspend fun insert(event: EventEntity): Long {
            if (events.any { it.sessionId == event.sessionId && it.type == event.type && it.timestampMs == event.timestampMs && it.detail == event.detail }) return -1
            events += event.copy(rowId = events.size + 1L)
            return events.size.toLong()
        }
        override suspend fun forSession(sessionId: String) = events.filter { it.sessionId == sessionId }.sortedBy { it.timestampMs }
        override suspend fun lastTimestamp(sessionId: String): Long? = events.filter { it.sessionId == sessionId }.maxOfOrNull { it.timestampMs }
        override suspend fun deleteAll() = events.size.also { events.clear() }
    }

    val feelingDao = object : FeelingDao {
        override suspend fun upsert(feelings: List<FeelingEntity>) {
            feelings.forEach { f -> this@FakeDriveDb.feelings.removeAll { it.sessionId == f.sessionId && it.maneuverIndex == f.maneuverIndex } }
            this@FakeDriveDb.feelings += feelings
        }
        override suspend fun forSession(sessionId: String) = feelings.filter { it.sessionId == sessionId }
        override suspend fun deleteAll() = feelings.size.also { feelings.clear() }
    }

    /** courseRepository.get이 돌려줄 코스(지금 시점의 코스). */
    var currentCourse: Course? = null

    val courses = object : CourseRepository {
        override suspend fun recommend(origin: LatLng, level: Int, focus: Set<Skill>): AppResult<List<Course>> = AppResult.Ok(emptyList())
        override suspend fun analyzeRoute(origin: LatLng, destination: LatLng): AppResult<Map<Skill, Int>> = AppResult.Ok(emptyMap())
        override suspend fun get(courseId: String): Course? = currentCourse?.takeIf { it.id == courseId }
        override fun demoOrigin() = LatLng(35.231, 129.084)
    }

    val tx = object : TransactionRunner {
        override suspend fun <T> run(block: suspend () -> T): T {
            txCount++
            return block()
        }
    }

    private var ids = 0

    fun repo(eventDao: EventDao = this.eventDao) =
        DriveRepositoryImpl(sessionDao, eventDao, feelingDao, courses, Clock { now }, Logger.Noop, tx, newId = { "s${ids++}" })

    fun session(
        id: String, startedAtMs: Long, endedAtMs: Long?, mode: String = "LIVE", level: Int = 1, interrupted: Boolean = false,
        courseDistanceM: Int? = null, drivenM: Double? = null,
    ) = SessionEntity(id, "L$level-x", level, mode, startedAtMs, endedAtMs, interrupted, courseDistanceM = courseDistanceM, drivenM = drivenM)
}
