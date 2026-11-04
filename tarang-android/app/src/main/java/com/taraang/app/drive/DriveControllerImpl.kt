package com.taraang.app.drive

import com.taraang.app.audio.FeedbackSpeaker
import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Clock
import com.taraang.app.core.Logger
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.DriveSession
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.repository.DriveController
import com.taraang.app.domain.repository.DriveRepository
import com.taraang.app.domain.repository.LiveDriveState
import com.taraang.app.domain.repository.SettingsRepository
import com.taraang.app.domain.signal.RouteStatus
import com.taraang.app.domain.signal.SignalSource
import com.taraang.app.feedback.FeedbackConfig
import com.taraang.app.feedback.FeedbackEngine
import com.taraang.app.vehicle.ReplayAssets
import com.taraang.app.vehicle.ReplayLogParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 주행 1회 오케스트레이션: 신호 소스 시작 → 세션 생성 → [tickMs]마다 엔진 → 이벤트 즉시 저장 → 음성 출력.
 *
 * - 루프는 앱 수명 스코프([scope])에서 돈다. 화면 회전·화면 이동과 무관하게 주행이 이어진다.
 * - 동시에 하나의 주행만. start/stop은 [mutex]로 직렬화한다.
 * - 이벤트 저장 실패는 기록하고 주행은 계속한다(주행 중 사용자에게 오류를 들이밀지 않는다).
 * - 리플레이가 끝나면 스스로 세션을 닫는다.
 * - 시작·종료의 자원 정리(신호 소스 해제, 세션 닫기)는 호출자가 취소돼도 끝까지 한다(NonCancellable).
 * - 시계 둘: [tickClock](단조, 엔진 틱·신호 시각)과 [clock](벽시계, 저장되는 이벤트·문구 시각).
 *   벽시계가 뒤로 바뀌어도 속도 추정·발화 간격이 멈추지 않게 한다.
 */
class DriveControllerImpl(
    private val driveRepository: DriveRepository,
    private val settingsRepository: SettingsRepository,
    private val signalSourceFactory: (SignalMode, String?) -> SignalSource,
    private val replayLogs: ReplayAssets,
    private val speaker: FeedbackSpeaker,
    private val clock: Clock,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val tickClock: Clock = clock,
    /** 주행 동안 프로세스를 붙잡는 장치. [prepareLiveStart] 또는 RUNNING에서 켜고 FINISHED/FAILED에서 끈다. */
    private val keepAlive: DriveKeepAlive = DriveKeepAlive.None,
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val config: FeedbackConfig = FeedbackConfig(),
    /** 리플레이 로그 목록·파싱(assets I/O)을 돌릴 곳. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DriveController {

    private class ActiveDrive(val session: DriveSession, val source: SignalSource, val engine: FeedbackEngine, val job: Job)

    private val mutex = Mutex()
    private var active: ActiveDrive? = null
    private val _state = MutableStateFlow(LiveDriveState())
    override val state: StateFlow<LiveDriveState> = _state.asStateFlow()

    private val _phase = MutableStateFlow(DrivePhase.IDLE)

    /** 주행 상태 기계. [LiveDriveState.finished]는 FINISHED/FAILED에서만 true다. */
    val phase: StateFlow<DrivePhase> = _phase.asStateFlow()

    /** 유지 장치를 켰는지. 내비 전에 미리 켠 것([prepareLiveStart])과 RUNNING에서 켠 것을 한 번으로 친다. */
    private val keepAliveHeld = AtomicBoolean(false)

    override fun prepareLiveStart() = holdKeepAlive()

    override fun cancelPreparedStart() {
        if (_phase.value !in IN_DRIVE) releaseKeepAlive()
    }

    private fun holdKeepAlive() {
        if (keepAliveHeld.compareAndSet(false, true)) keepAlive.start()
    }

    private fun releaseKeepAlive() {
        if (keepAliveHeld.compareAndSet(true, false)) keepAlive.stop()
    }

    override suspend fun replayAssets(courseId: String): List<String> = replayIndex()[courseId].orEmpty()

    override suspend fun demoCourseIds(): Set<String> = replayIndex().keys

    private val replayIndexMutex = Mutex()

    @Volatile
    private var replayIndex: Map<String, List<String>>? = null

    /**
     * 녹화 코스 id → 로그 이름. 로그를 통째로 파싱해야 courseId를 알 수 있어, 앱 수명 동안 한 번만 만든다
     * (번들 assets는 실행 중에 바뀌지 않는다). 목록 읽기가 실패하면 캐시하지 않고 빈 결과를 준다(다음 호출에 다시 시도).
     */
    private suspend fun replayIndex(): Map<String, List<String>> {
        replayIndex?.let { return it }
        return try {
            replayIndexMutex.withLock { replayIndex ?: withContext(io) { buildReplayIndex() }.also { replayIndex = it } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "리플레이 목록 읽기 실패", e)
            emptyMap()
        }
    }

    private suspend fun buildReplayIndex(): Map<String, List<String>> =
        replayLogs.list().mapNotNull { name -> recordedCourseId(name)?.let { it to name } }.groupBy({ it.first }, { it.second })

    /** 읽을 수 없거나 해석이 안 되는 로그는 null(목록에서 빠진다). */
    private suspend fun recordedCourseId(name: String): String? {
        val text = replayLogs.read(name) ?: return null
        return (ReplayLogParser.parse(text) as? AppResult.Ok)?.value?.courseId
    }

    override suspend fun start(course: Course, mode: SignalMode, replayAsset: String?): AppResult<DriveSession> = mutex.withLock {
        if (!moveTo(DrivePhase.STARTING)) return AppResult.Err(AppError.InvalidInput("이미 주행 중(${_phase.value})"))
        // 신호 소스·세션은 중간에 끊기면 새어 나간다. 시작은 끝까지 하고, 그 사이 호출자가 취소됐으면 되돌린다.
        val result = withContext(NonCancellable) { launchDrive(course, mode, replayAsset) }
        if (!currentCoroutineContext().isActive) {
            withContext(NonCancellable) {
                if (result is AppResult.Ok) finishActive(failed = true) else moveTo(DrivePhase.FAILED)
            }
            throw CancellationException("주행 시작 중 호출자 취소")
        }
        if (result is AppResult.Err) {
            logger.w(TAG, "주행 시작 실패: ${result.error.debugMessage}")
            moveTo(DrivePhase.FAILED)
        }
        result
    }

    override suspend fun stop(): AppResult<DriveSession?> = mutex.withLock { finishActive(failed = false) }

    /** STARTING 상태에서만 부른다. 실패하면 만든 자원을 모두 정리하고 Err를 돌려준다. */
    private suspend fun launchDrive(course: Course, mode: SignalMode, replayAsset: String?): AppResult<DriveSession> {
        val asset = if (mode == SignalMode.REPLAY) {
            // 다른 코스에서 녹화한 로그는 회전 판정·코스 이탈 기록을 틀리게 만든다. 이 코스 로그만 받는다.
            val matching = replayAssets(course.id)
            (replayAsset ?: matching.firstOrNull())?.takeIf { it in matching }
                ?: return AppResult.Err(AppError.InvalidInput("이 코스의 리플레이 로그 없음"))
        } else {
            null
        }
        val settings = try {
            settingsRepository.settings.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "설정 읽기 실패, 기본값으로 주행", e)
            FeedbackSettings()
        }

        val source = try {
            signalSourceFactory(mode, asset)
        } catch (e: Exception) {
            logger.e(TAG, "신호 소스 생성 실패", e)
            return AppResult.Err(AppError.Unexpected(e.javaClass.simpleName))
        }
        val started = try {
            source.start()
        } catch (e: CancellationException) {
            source.stop()
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.Unexpected(e.javaClass.simpleName))
        }
        if (started is AppResult.Err) {
            source.stop()
            return started
        }

        val session = when (val created = driveRepository.startSession(course.id, mode)) {
            is AppResult.Ok -> created.value
            is AppResult.Err -> {
                source.stop()
                return created
            }
        }
        val engine = FeedbackEngine(session.id, course, settings, config)
        _state.value = LiveDriveState(session = session, availability = source.availability.value)
        val job = scope.launch { runLoop(session, source, engine, settings) }
        active = ActiveDrive(session, source, engine, job)
        moveTo(DrivePhase.RUNNING)
        logger.d(TAG, "주행 시작: mode=$mode course=${course.id}")
        return AppResult.Ok(session)
    }

    /** 호출자가 취소돼도 STOPPING에 멈추지 않게 정리 전체를 NonCancellable로 한다. */
    private suspend fun finishActive(failed: Boolean): AppResult<DriveSession?> = withContext(NonCancellable) {
        val drive = active ?: return@withContext AppResult.Ok(null)
        moveTo(DrivePhase.STOPPING)
        active = null
        // 루프 코루틴 안에서 부르면 join이 끝나지 않는다. 루프는 끝날 때 별도 코루틴으로 이 함수를 부른다.
        drive.job.cancelAndJoin()
        drive.source.stop()
        // 루프가 끝난(join) 뒤라 엔진을 읽어도 안전하다.
        // 실패로 끝난 주행은 중단으로 저장한다(단계 진행·"지난 주행" 비교에서 빠진다).
        val finished = driveRepository.finishSession(drive.session.id, drive.engine.drivenAlongCourseM, interrupted = failed)
        _state.update { it.copy(finished = true, session = (finished as? AppResult.Ok)?.value ?: it.session) }
        moveTo(if (failed || finished is AppResult.Err) DrivePhase.FAILED else DrivePhase.FINISHED)
        when (finished) {
            is AppResult.Ok -> AppResult.Ok(finished.value)
            is AppResult.Err -> finished
        }
    }

    private suspend fun runLoop(session: DriveSession, source: SignalSource, engine: FeedbackEngine, settings: FeedbackSettings) {
        var failed = false
        var writeFailures = 0
        // 음성 켜기/끄기는 주행 중에도 바로 반영한다(설정 화면에서 바꾼 즉시). 읽기 실패는 마지막 값을 유지한다.
        val voiceEnabled = AtomicBoolean(settings.voiceEnabled)
        val settingsWatcher = CoroutineScope(currentCoroutineContext()).launch {
            settingsRepository.settings
                .catch { logger.w(TAG, "주행 중 설정 읽기 실패, 이전 값 유지", it) }
                .collect { voiceEnabled.set(it.voiceEnabled) }
        }
        try {
            while (currentCoroutineContext().isActive) {
                val replayDone = source.finished.value
                val vehicle = source.vehicle.value
                val navi = source.navi.value
                // 내비에서 경로를 닫았거나 도착했으면 이번 주행은 끝이다. 그 뒤 위치로 코스 이탈 등을 기록하지 않게 틱 전에 멈춘다.
                if (navi.route == RouteStatus.CANCELLED || navi.route == RouteStatus.ARRIVED) {
                    logger.d(TAG, "내비 경로 종료(${navi.route}), 주행 종료")
                    break
                }
                val availability = source.availability.value
                val result = engine.tick(vehicle, navi, availability, tickClock.nowMs())
                // 엔진 시각은 단조 시계라 저장·표시용으로는 벽시계로 바꾼다.
                val wallNow = clock.nowMs()
                val message = result.message?.copy(timestampMs = wallNow)
                for (event in result.events) {
                    val saved = driveRepository.appendEvent(event.copy(timestampMs = wallNow))
                    // 저장소가 계속 실패해도 로그는 첫 번째만 남긴다.
                    if (saved is AppResult.Err && writeFailures++ == 0) logger.e(TAG, "이벤트 저장 실패: ${saved.error.debugMessage}")
                }
                message?.let { speaker.speak(it, voiceEnabled.get()) }
                _state.update {
                    it.copy(
                        vehicle = vehicle,
                        navi = navi,
                        availability = availability,
                        estimatedSpeedKmh = result.speedKmh,
                        lastFeedback = message ?: it.lastFeedback,
                        eventCount = it.eventCount + result.events.size,
                        newWarnings = result.newWarnings,
                    )
                }
                if (replayDone) break
                delay(tickMs)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 판정 버그로 주행 기록 전체를 잃지 않게 세션은 닫고 FAILED로 끝낸다.
            logger.e(TAG, "주행 루프 오류, 주행 종료", e)
            failed = true
        } finally {
            settingsWatcher.cancel()
            if (writeFailures > 1) logger.e(TAG, "이벤트 저장 실패 총 ${writeFailures}건")
        }
        logger.d(TAG, "주행 루프 종료: ${session.id.take(8)}")
        scope.launch { mutex.withLock { if (active?.session?.id == session.id) finishActive(failed) } }
    }

    private fun moveTo(next: DrivePhase): Boolean {
        val current = _phase.value
        if (!current.canMoveTo(next)) {
            if (next != DrivePhase.STARTING) logger.w(TAG, "잘못된 상태 전이 무시: $current → $next")
            return false
        }
        _phase.value = next
        when (next) {
            DrivePhase.RUNNING -> holdKeepAlive()
            DrivePhase.FINISHED, DrivePhase.FAILED -> releaseKeepAlive()
            else -> Unit
        }
        return true
    }

    companion object {
        const val DEFAULT_TICK_MS = 250L
        private val IN_DRIVE = setOf(DrivePhase.STARTING, DrivePhase.RUNNING, DrivePhase.STOPPING)
        private const val TAG = "DriveController"
    }
}
