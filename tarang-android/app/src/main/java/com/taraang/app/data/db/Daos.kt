package com.taraang.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.taraang.app.domain.model.LevelProgress
import kotlinx.coroutines.flow.Flow

/**
 * 단계 진행에 세는 주행: 실차(LIVE)로 정상 종료했고, 코스를 [MIN_DRIVEN_RATIO] 이상 따라간 것.
 * 리플레이·중단(실패 포함)·잠깐 켜 본 주행은 세지 않는다.
 * 시간 기준([minDurationMs] 이상)은 코스 거리 열이 없던 v1 행(courseDistanceM IS NULL)에만 쓴다.
 * v2 행에 시간을 쓰면 주차한 채 켜 둔 주행도 단계를 올린다.
 * JVM 테스트(SessionQueriesTest)가 같은 문자열을 SQLite에 돌려 본다.
 */
object SessionQueries {
    const val MIN_DRIVEN_RATIO = LevelProgress.MIN_DRIVEN_RATIO
    private const val COUNTED = "endedAtMs IS NOT NULL AND interrupted = 0 AND mode = 'LIVE' AND " +
        "((drivenM IS NOT NULL AND courseDistanceM > 0 AND drivenM >= $MIN_DRIVEN_RATIO * courseDistanceM) " +
        "OR (courseDistanceM IS NULL AND endedAtMs - startedAtMs >= :minDurationMs))"
    const val MAX_COUNTED_LEVEL = "SELECT MAX(courseLevel) FROM sessions WHERE $COUNTED"
    const val COUNT_COUNTED_AT_LEVEL = "SELECT COUNT(*) FROM sessions WHERE courseLevel = :level AND $COUNTED"
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAtMs DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Query("UPDATE sessions SET endedAtMs = :endedAtMs, interrupted = :interrupted, drivenM = :drivenM WHERE id = :id AND endedAtMs IS NULL")
    suspend fun finish(id: String, endedAtMs: Long, interrupted: Boolean, drivenM: Double?): Int

    /**
     * 같은 코스·같은 방식([mode], LIVE/REPLAY)에서 이 세션 직전에 정상 종료된 주행.
     * 실차 주행을 시연 리플레이와 비교하면(그 반대도) 운전 실력 변화가 아니라 녹화 내용 차이를 보여 준다.
     */
    @Query(
        "SELECT * FROM sessions WHERE courseId = :courseId AND mode = :mode AND startedAtMs < :before AND endedAtMs IS NOT NULL " +
            "AND interrupted = 0 ORDER BY startedAtMs DESC LIMIT 1",
    )
    suspend fun previousCompleted(courseId: String, mode: String, before: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE endedAtMs IS NULL AND startedAtMs < :before")
    suspend fun openStartedBefore(before: Long): List<SessionEntity>

    @Query(SessionQueries.MAX_COUNTED_LEVEL)
    suspend fun maxCountedLevel(minDurationMs: Long): Int?

    @Query(SessionQueries.COUNT_COUNTED_AT_LEVEL)
    suspend fun countCountedAtLevel(level: Int, minDurationMs: Long): Int

    /** 이 시각 이후 시작해 아직 안 끝난 세션 수(이번 프로세스에서 진행 중인 주행). */
    @Query("SELECT COUNT(*) FROM sessions WHERE endedAtMs IS NULL AND startedAtMs >= :since")
    suspend fun countOpenStartedSince(since: Long): Int

    @Query("DELETE FROM sessions")
    suspend fun deleteAll(): Int
}

@Dao
interface EventDao {
    /** 중복이면 무시한다. 반환값 -1은 이미 있던 이벤트. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE sessionId = :sessionId ORDER BY timestampMs, rowId")
    suspend fun forSession(sessionId: String): List<EventEntity>

    @Query("SELECT MAX(timestampMs) FROM events WHERE sessionId = :sessionId")
    suspend fun lastTimestamp(sessionId: String): Long?

    @Query("DELETE FROM events")
    suspend fun deleteAll(): Int
}

@Dao
interface FeelingDao {
    @Upsert
    suspend fun upsert(feelings: List<FeelingEntity>)

    @Query("SELECT * FROM feelings WHERE sessionId = :sessionId ORDER BY maneuverIndex")
    suspend fun forSession(sessionId: String): List<FeelingEntity>

    @Query("DELETE FROM feelings")
    suspend fun deleteAll(): Int
}

@Dao
interface GoalDao {
    @Query("SELECT * FROM goals ORDER BY createdAtMs DESC")
    fun observeAll(): Flow<List<GoalEntity>>

    @Upsert
    suspend fun upsert(goal: GoalEntity)

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun delete(id: String): Int
}
