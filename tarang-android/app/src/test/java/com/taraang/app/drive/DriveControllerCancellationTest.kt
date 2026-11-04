package com.taraang.app.drive

import com.taraang.app.audio.FeedbackSpeaker
import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Clock
import com.taraang.app.core.Logger
import com.taraang.app.domain.model.DriveEvent
import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.model.DriveSession
import com.taraang.app.domain.model.DriveSummary
import com.taraang.app.domain.model.FeedbackMessage
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.SegmentFeeling
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.repository.DriveRepository
import com.taraang.app.domain.repository.SettingsRepository
import com.taraang.app.domain.signal.NaviSnapshot
import com.taraang.app.domain.signal.RouteStatus
import com.taraang.app.domain.signal.SignalAvailability
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.SignalSource
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.domain.signal.TurnSignalState
import com.taraang.app.domain.signal.VehicleSnapshot
import com.taraang.app.domain.signal.WarningType
import com.taraang.app.feedback.START
import com.taraang.app.feedback.east
import com.taraang.app.feedback.testCourse
import com.taraang.app.vehicle.MemoryReplayAssets
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 코드 리뷰 M1·M2(취소 중 자원 누수)와 M4(시계 분리) 회귀 테스트. */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveControllerCancellationTest {

    private class Repo(val startDelayMs: Long = 0) : DriveRepository {
        val started = mutableListOf<DriveSession>()
        val finished = mutableListOf<String>()
        val interrupted = mutableListOf<Boolean>()
        val events = mutableListOf<DriveEvent>()
        override val sessions: Flow<List<DriveSession>> = flowOf(emptyList())
        override suspend fun startSession(courseId: String, mode: SignalMode): AppResult<DriveSession> {
            val s = DriveSession("sess-${started.size}", courseId, mode, 0, null, false).also { started += it }
            delay(startDelayMs) // Room insert가 커밋된 뒤 재개 전
            return AppResult.Ok(s)
        }
        override suspend fun appendEvent(event: DriveEvent): AppResult<Unit> { events += event; return AppResult.Ok(Unit) }
        override suspend fun finishSession(sessionId: String, drivenAlongCourseM: Double?, interrupted: Boolean): AppResult<DriveSession> {
            finished += sessionId
            this.interrupted += interrupted
            return AppResult.Ok(started.first { it.id == sessionId }.copy(endedAtMs = 1))
        }
        override suspend fun saveFeelings(feelings: List<SegmentFeeling>) = AppResult.Ok(Unit)
        override suspend fun summary(sessionId: String): AppResult<DriveSummary> = AppResult.Err(AppError.Storage("n/a"))
        override suspend fun levelProgress() = LevelProgress(1, 0, 3)
        override suspend fun closeInterruptedSessions() = 0
        override suspend fun deleteAllHistory(): AppResult<Unit> = AppResult.Ok(Unit)
    }

    private class Source : SignalSource {
        var stopped = 0
        override val mode = SignalMode.LIVE
        override val vehicle = MutableStateFlow(VehicleSnapshot(turnSignal = TurnSignalState.OFF, brakePressed = false))
        override val navi = MutableStateFlow(NaviSnapshot())
        override val availability: StateFlow<SignalAvailability> =
            MutableStateFlow(SignalAvailability(SignalKey.entries.associateWith { SignalStatus.AVAILABLE }))
        override val finished: StateFlow<Boolean> = MutableStateFlow(false)
        override suspend fun start(): AppResult<Unit> = AppResult.Ok(Unit)
        override fun stop() { stopped++ }
    }

    private val spoken = mutableListOf<FeedbackMessage>()

    private fun TestScope.controller(repo: DriveRepository, src: SignalSource, wall: Clock = Clock { testScheduler.currentTime }) = DriveControllerImpl(
        driveRepository = repo,
        settingsRepository = object : SettingsRepository {
            override val settings = MutableStateFlow(FeedbackSettings())
            override suspend fun update(transform: (FeedbackSettings) -> FeedbackSettings) {}
        },
        signalSourceFactory = { _, _ -> src },
        replayLogs = MemoryReplayAssets(emptyMap()),
        speaker = object : FeedbackSpeaker {
            override fun speak(message: FeedbackMessage, voiceEnabled: Boolean) { spoken += message }
            override fun release() {}
        },
        clock = wall,
        logger = Logger.Noop,
        scope = backgroundScope,
        tickClock = Clock { testScheduler.currentTime },
    )

    @Test
    fun `stop cancelled by its caller still stops the source and finishes the session`() = runTest {
        val repo = Repo()
        val src = Source()
        val c = controller(repo, src)
        c.start(testCourse(), SignalMode.LIVE, null)
        advanceTimeBy(1_000)
        val stopping = launch(start = CoroutineStart.UNDISPATCHED) { c.stop() }
        stopping.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(DrivePhase.FINISHED, c.phase.value)
        assertEquals(1, src.stopped)
        assertEquals(listOf("sess-0"), repo.finished)
        assertEquals(listOf(false), repo.interrupted)
        assertTrue(c.start(testCourse(), SignalMode.LIVE, null) is AppResult.Ok)
    }

    @Test
    fun `start cancelled during session creation releases the source and closes the session`() = runTest {
        val repo = Repo(startDelayMs = 50)
        val src = Source()
        val c = controller(repo, src)
        val starting = launch { c.start(testCourse(), SignalMode.LIVE, null) }
        advanceTimeBy(10)
        runCurrent()
        starting.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(DrivePhase.FAILED, c.phase.value)
        assertEquals(1, src.stopped)
        assertEquals(1, repo.started.size)
        assertEquals(listOf("sess-0"), repo.finished)
        // 실패로 닫은 주행은 중단으로 저장한다(단계 진행·"지난 주행" 비교에서 빠진다).
        assertEquals(listOf(true), repo.interrupted)
        // 다시 시작할 수 있다.
        assertTrue(c.start(testCourse(), SignalMode.LIVE, null) is AppResult.Ok)
    }

    @Test
    fun `closing the route in the navi ends the drive cleanly without recording anything after it`() = runTest {
        val repo = Repo()
        val src = Source()
        val c = controller(repo, src)
        val course = testCourse()
        c.start(course, SignalMode.LIVE, null)
        src.navi.value = NaviSnapshot(timestampMs = 1, position = START, route = RouteStatus.ACTIVE)
        advanceTimeBy(1_000)
        // 경로를 닫은 뒤 위치는 코스에서 한참 벗어나도 이탈로 기록하지 않는다.
        src.navi.value = NaviSnapshot(timestampMs = 2, position = START.east(500.0), route = RouteStatus.CANCELLED)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(DrivePhase.FINISHED, c.phase.value)
        assertTrue(c.state.value.finished)
        assertEquals(listOf("sess-0"), repo.finished)
        assertEquals(listOf(false), repo.interrupted)
        assertTrue(repo.events.none { it.type == DriveEventType.OFF_COURSE })
        assertEquals(1, src.stopped)
    }

    @Test
    fun `stored event and message times come from the wall clock, not the tick clock`() = runTest {
        val repo = Repo()
        val src = Source()
        val wallBase = 1_700_000_000_000L
        val c = controller(repo, src, wall = Clock { wallBase + testScheduler.currentTime })
        c.start(testCourse(), SignalMode.LIVE, null)
        src.navi.value = NaviSnapshot(timestampMs = testScheduler.currentTime, position = START)
        advanceTimeBy(1_000)
        src.vehicle.value = src.vehicle.value.copy(activeWarnings = setOf(WarningType.ENGINE))
        advanceTimeBy(1_000)
        val event = repo.events.single()
        assertTrue("event at ${event.timestampMs}", event.timestampMs >= wallBase)
        assertTrue(spoken.single().timestampMs >= wallBase)
        c.stop()
    }
}
