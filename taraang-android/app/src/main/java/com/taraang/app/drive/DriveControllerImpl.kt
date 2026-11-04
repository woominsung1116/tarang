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
import com.taraang.app.domain.signal.SignalSource
import com.taraang.app.feedback.FeedbackConfig
import com.taraang.app.feedback.FeedbackEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    private val replayAssetNames: () -> List<String>,
    private val speaker: FeedbackSpeaker,
    private val clock: Clock,
    private val logger: Logger,
    private val scope: CoroutineScope,
    private val tickClock: Clock = clock,
    /** 주행 동안 프로세스를 붙잡는 장치. RUNNING에서 켜고 FINISHED/FAILED에서 끈다. */
    private val keepAlive: DriveKeepAlive = DriveKeepAlive.None,
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val config: FeedbackConfig = FeedbackConfig(),
) : DriveController {

    private class ActiveDrive(val session: DriveSession, val source: SignalSource, val engine: FeedbackEngine, val job: Job)

    private val mutex = Mutex()
    private var active: ActiveDrive? = null
    private val _state = MutableStateFlow(LiveDriveState())
    override val state: StateFlow<LiveDriveState> = _state.asStateFlow()

    private val _phase = MutableStateFlow(DrivePhase.IDLE)

    /** 주행 상태 기계. [LiveDriveState.finished]는 FINISHED/FAILED에서만 true다. */
    val phase: StateFlow<DrivePhase> = _phase.asStateFlow()

    override fun replayAssets(): List<String> = runCatching(replayAssetNames).getOrElse {
        logger.w(TAG, "리플레이 목록 읽기 실패", it)
        emptyList()
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
            replayAsset ?: replayAssets().firstOrNull() ?: return AppResult.Err(AppError.InvalidInput("리플레이 로그 없음"))
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
        val finished = driveRepository.finishSession(drive.session.id, drive.engine.drivenAlongCourseM)
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
            DrivePhase.RUNNING -> keepAlive.start()
            DrivePhase.FINISHED, DrivePhase.FAILED -> keepAlive.stop()
            else -> Unit
        }
        return true
    }

    companion object {
        const val DEFAULT_TICK_MS = 250L
        private const val TAG = "DriveController"
    }
}
