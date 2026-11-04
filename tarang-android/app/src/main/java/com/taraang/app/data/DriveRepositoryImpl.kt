package com.taraang.app.data

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Clock
import com.taraang.app.core.Logger
import com.taraang.app.core.runCatchingApp
import com.taraang.app.data.db.CourseSnapshot
import com.taraang.app.data.db.EventDao
import com.taraang.app.data.db.FeelingDao
import com.taraang.app.data.db.SessionDao
import com.taraang.app.data.db.SessionEntity
import com.taraang.app.data.db.TransactionRunner
import com.taraang.app.data.db.toDomain
import com.taraang.app.data.db.toDomainOrNull
import com.taraang.app.data.db.toEntity
import com.taraang.app.domain.model.DriveEvent
import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.model.DriveSession
import com.taraang.app.domain.model.DriveSummary
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.SegmentFeeling
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.repository.CourseRepository
import com.taraang.app.domain.repository.DriveRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 주행 기록 저장소. 이벤트는 받는 즉시 한 줄씩 쓴다(프로세스가 죽어도 남게).
 * 단계 진행: 세는 주행([com.taraang.app.data.db.SessionQueries]: 실차 LIVE, 정상 종료, 코스 70% 이상 진행)의 최고 단계가 현재 단계이고,
 * 그 단계에서 [REQUIRED_TO_ADVANCE]번 끝내면 올릴 수 있다. 리플레이 시연은 단계를 올리지 않는다.
 */
class DriveRepositoryImpl(
    private val sessionDao: SessionDao,
    private val eventDao: EventDao,
    private val feelingDao: FeelingDao,
    private val courseRepository: CourseRepository,
    private val clock: Clock,
    private val logger: Logger,
    private val tx: TransactionRunner = TransactionRunner.Direct,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : DriveRepository {

    /** 이 시각 이후 시작한 세션은 이번 프로세스 것이라 "중단됨"으로 닫지 않는다. */
    private val processStartMs = clock.nowMs()

    override val sessions: Flow<List<DriveSession>> = sessionDao.observeAll()
        .map { list -> list.map { it.toDomain() } }
        .catch { e ->
            logger.e(TAG, "세션 목록 읽기 실패", e)
            emit(emptyList())
        }

    /** 시작 시점 코스를 통째로 저장한다(4.1: 같은 id가 나중에 다른 경로가 돼도 기록은 원래 코스를 보여 준다). */
    override suspend fun startSession(courseId: String, mode: SignalMode): AppResult<DriveSession> = storage {
        val course = courseRepository.get(courseId)
        val entity = SessionEntity(
            newId(), courseId, course?.level ?: levelFromId(courseId), mode.name, clock.nowMs(), endedAtMs = null, interrupted = false,
            courseJson = course?.let(CourseSnapshot::encode),
            courseDistanceM = course?.distanceM,
        )
        sessionDao.insert(entity)
        entity.toDomain()
    }

    /** 같은 이벤트가 다시 와도 한 줄만 남는다(유니크 인덱스 + IGNORE). */
    override suspend fun appendEvent(event: DriveEvent): AppResult<Unit> = storage {
        eventDao.insert(event.copy(detail = event.detail.take(MAX_DETAIL_LENGTH)).toEntity())
        Unit
    }

    override suspend fun finishSession(sessionId: String, drivenAlongCourseM: Double?, interrupted: Boolean): AppResult<DriveSession> {
        // 이미 끝난 세션이면 종료 시각을 바꾸지 않고 그대로 돌려준다(멱등).
        val result = storage {
            tx.run {
                sessionDao.finish(sessionId, clock.nowMs(), interrupted = interrupted, drivenM = drivenAlongCourseM?.takeIf { it.isFinite() && it >= 0 })
                sessionDao.get(sessionId)?.toDomain()
            }
        }
        return when (result) {
            is AppResult.Ok -> result.value?.let { AppResult.Ok(it) } ?: AppResult.Err(AppError.Storage("세션 없음: $sessionId"))
            is AppResult.Err -> result
        }
    }

    override suspend fun saveFeelings(feelings: List<SegmentFeeling>): AppResult<Unit> =
        storage { feelingDao.upsert(feelings.map { it.toEntity() }) }

    override suspend fun summary(sessionId: String): AppResult<DriveSummary> {
        val result = storage {
            tx.run {
                sessionDao.get(sessionId)?.let { session ->
                    val previous = sessionDao.previousCompleted(session.courseId, session.mode, session.startedAtMs)?.let { buildSummary(it, null) }
                    buildSummary(session, previous)
                }
            }
        }
        return when (result) {
            is AppResult.Ok -> result.value?.let { AppResult.Ok(it) } ?: AppResult.Err(AppError.Storage("세션 없음: $sessionId"))
            is AppResult.Err -> result
        }
    }

    override suspend fun levelProgress(): LevelProgress {
        val result = storage {
            val level = (sessionDao.maxCountedLevel(MIN_COUNTED_DURATION_MS) ?: 1).coerceIn(1, LevelProgress.MAX_LEVEL)
            LevelProgress(level, sessionDao.countCountedAtLevel(level, MIN_COUNTED_DURATION_MS), REQUIRED_TO_ADVANCE)
        }
        return (result as? AppResult.Ok)?.value ?: LevelProgress(1, 0, REQUIRED_TO_ADVANCE)
    }

    override suspend fun closeInterruptedSessions(): Int {
        val result = storage {
            tx.run {
                sessionDao.openStartedBefore(processStartMs).count { s ->
                    // 마지막 이벤트 시각을 종료 시각으로 쓴다. 이벤트가 없으면 시작 시각.
                    val endedAt = eventDao.lastTimestamp(s.id) ?: s.startedAtMs
                    sessionDao.finish(s.id, endedAt, interrupted = true, drivenM = null) > 0
                }
            }
        }
        return when (result) {
            is AppResult.Ok -> result.value
            is AppResult.Err -> {
                logger.e(TAG, "중단 세션 정리 실패: ${result.error.debugMessage}")
                0
            }
        }
    }

    /**
     * 위치가 든 주행 기록을 사용자 요청으로 모두 지운다. 이벤트·느낌·세션을 한 트랜잭션으로(외래키 CASCADE에 기대지 않고 명시적으로).
     * 이번 프로세스에서 시작해 아직 안 끝난 세션이 있으면 주행 중이라 거절한다(이벤트가 계속 써지므로).
     */
    override suspend fun deleteAllHistory(): AppResult<Unit> {
        val result = storage {
            tx.run {
                if (sessionDao.countOpenStartedSince(processStartMs) > 0) {
                    false
                } else {
                    eventDao.deleteAll()
                    feelingDao.deleteAll()
                    sessionDao.deleteAll()
                    true
                }
            }
        }
        return when (result) {
            is AppResult.Ok -> if (result.value) AppResult.Ok(Unit) else AppResult.Err(AppError.InvalidInput("주행 중에는 기록을 지울 수 없음"))
            is AppResult.Err -> result
        }
    }

    private suspend fun buildSummary(session: SessionEntity, previous: DriveSummary?): DriveSummary {
        val events = eventDao.forSession(session.id).mapNotNull { it.toDomainOrNull() }
        val ok = events.count { it.type == DriveEventType.TURN_SIGNAL_OK }
        val missed = events.count { it.type == DriveEventType.TURN_SIGNAL_MISSED }
        return DriveSummary(
            session = session.toDomain(),
            // 스냅샷이 없는 v1 행·깨진 스냅샷만 지금 코스를 다시 찾는다.
            course = session.courseJson?.let(CourseSnapshot::decode) ?: courseRepository.get(session.courseId),
            events = events,
            feelings = feelingDao.forSession(session.id).mapNotNull { it.toDomainOrNull() },
            turnSignalOk = ok,
            turnSignalTotal = ok + missed,
            previous = previous,
        )
    }

    private inline fun <T> storage(block: () -> T): AppResult<T> =
        runCatchingApp({ e ->
            logger.e(TAG, "저장소 오류", e)
            AppError.Storage(e.javaClass.simpleName)
        }, block)

    companion object {
        const val REQUIRED_TO_ADVANCE = 3
        /** 진행 거리가 없는 v1 행만 이 시간 이상이면 센다(v2 행은 진행 거리로 본다). */
        const val MIN_COUNTED_DURATION_MS = 3 * 60 * 1000L
        private const val MAX_DETAIL_LENGTH = 200
        private const val TAG = "DriveRepo"

        /** 코스를 못 찾으면 id 규칙("L1-...")에서 단계를 읽는다. 그것도 안 되면 1. */
        internal fun levelFromId(courseId: String): Int =
            courseId.removePrefix("L").takeWhile { it.isDigit() }.toIntOrNull()?.coerceIn(1, LevelProgress.MAX_LEVEL) ?: 1
    }
}
