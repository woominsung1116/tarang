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
import com.taraang.app.domain.signal.SignalAvailability
import com.taraang.app.domain.signal.SignalSource
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.domain.signal.VehicleSnapshot
import com.taraang.app.feedback.testCourse
import com.taraang.app.vehicle.MemoryReplayAssets
import com.taraang.app.vehicle.ReplaySignalSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeDriveRepository : DriveRepository {
    val started = mutableListOf<DriveSession>()
    val events = mutableListOf<DriveEvent>()
    val finished = mutableListOf<String>()
    var failStart = false
    var failAppend = false

    override val sessions: Flow<List<DriveSession>> = flowOf(emptyList())
    override suspend fun startSession(courseId: String, mode: SignalMode): AppResult<DriveSession> {
        if (failStart) return AppResult.Err(AppError.Storage("full"))
        return AppResult.Ok(DriveSession("sess-${started.size}", courseId, mode, 0, null, false).also { started += it })
    }

    override suspend fun appendEvent(event: DriveEvent): AppResult<Unit> {
        if (failAppend) return AppResult.Err(AppError.Storage("io"))
        events += event
        return AppResult.Ok(Unit)
    }

    override suspend fun finishSession(sessionId: String, drivenAlongCourseM: Double?): AppResult<DriveSession> {
        finished += sessionId
        return AppResult.Ok(started.first { it.id == sessionId }.copy(endedAtMs = 1))
    }

    override suspend fun saveFeelings(feelings: List<SegmentFeeling>) = AppResult.Ok(Unit)
    override suspend fun summary(sessionId: String): AppResult<DriveSummary> = AppResult.Err(AppError.Storage("n/a"))
    override suspend fun levelProgress() = LevelProgress(1, 0, 3)
    override suspend fun closeInterruptedSessions() = 0
    override suspend fun deleteAllHistory(): AppResult<Unit> = AppResult.Ok(Unit)
}

private class FakeSettings(settings: FeedbackSettings = FeedbackSettings()) : SettingsRepository {
    override val settings = MutableStateFlow(settings)
    override suspend fun update(transform: (FeedbackSettings) -> FeedbackSettings) {
        settings.value = transform(settings.value)
    }
}

private class RecordingSpeaker : FeedbackSpeaker {
    val spoken = mutableListOf<Pair<FeedbackMessage, Boolean>>()
    override fun speak(message: FeedbackMessage, voiceEnabled: Boolean) {
        spoken += message to voiceEnabled
    }

    override fun release() = Unit
}

/** start가 실패하는 LIVE 소스 대역. stop 호출 여부를 본다. */
private class FailingSource : SignalSource {
    var stopped = false
    override val mode = SignalMode.LIVE
    override val vehicle: StateFlow<VehicleSnapshot> = MutableStateFlow(VehicleSnapshot())
    override val navi: StateFlow<NaviSnapshot> = MutableStateFlow(NaviSnapshot())
    override val availability: StateFlow<SignalAvailability> = MutableStateFlow(SignalAvailability())
    override val finished: StateFlow<Boolean> = MutableStateFlow(false)
    override suspend fun start(): AppResult<Unit> =
        AppResult.Err(AppError.SignalUnavailable(SignalKey.POSITION, SignalStatus.PERMISSION_DENIED, "denied"))

    override fun stop() {
        stopped = true
    }
}

/** 0.5초마다 북쪽으로 약 5.6m(40km/h), 오른쪽 회전을 방향지시등 없이 지나고 새 경고가 켜지는 로그. */
private fun replayLog(): String {
    val frames = (0..60).joinToString(",") { i ->
        val lat = 35.2300 + i * 5.6 / 111_320.0
        val remain = 200.0 - i * 5.6
        val turn = if (remain > 0) """{"type":"RIGHT","distanceM":$remain}""" else """{"type":"STRAIGHT","distanceM":900}"""
        val warnings = if (i >= 50) """["ENGINE"]""" else "[]"
        """{"t":${i * 500},"lat":$lat,"lon":129.08,"speedLimitKmh":50,"turnSignal":"OFF","brake":false,"warnings":$warnings,"nextTurn":$turn,"caution":[]}"""
    }
    return """{"schemaVersion":1,"courseId":"L1-test","title":"t","tickMs":500,"frames":[$frames]}"""
}

@OptIn(ExperimentalCoroutinesApi::class)
class DriveControllerImplTest {

    private val repo = FakeDriveRepository()
    private val speaker = RecordingSpeaker()

    private fun TestScope.controller(
        settings: FeedbackSettings = FeedbackSettings(),
        factory: ((SignalMode, String?) -> SignalSource)? = null,
        scope: CoroutineScope = backgroundScope,
        settingsRepository: SettingsRepository = FakeSettings(settings),
        keepAlive: DriveKeepAlive = DriveKeepAlive.None,
    ): DriveControllerImpl {
        val clock = Clock { testScheduler.currentTime }
        val assets = MemoryReplayAssets(mapOf("demo.json" to replayLog()))
        return DriveControllerImpl(
            driveRepository = repo,
            settingsRepository = settingsRepository,
            signalSourceFactory = factory ?: { _, name -> ReplaySignalSource(assets, requireNotNull(name), clock, scope, Logger.Noop) },
            replayAssetNames = assets::list,
            speaker = speaker,
            clock = clock,
            logger = Logger.Noop,
            scope = scope,
            keepAlive = keepAlive,
        )
    }

    private class CountingKeepAlive : DriveKeepAlive {
        val calls = mutableListOf<String>()
        override fun start() { calls += "start" }
        override fun stop() { calls += "stop" }
    }

    @Test
    fun `foreground keep-alive runs exactly while the drive runs`() = runTest {
        val keep = CountingKeepAlive()
        val c = controller(keepAlive = keep)
        c.start(testCourse(), SignalMode.REPLAY, "demo.json")
        assertEquals(listOf("start"), keep.calls)
        advanceTimeBy(1_000)
        c.stop()
        assertEquals(listOf("start", "stop"), keep.calls)
        // 리플레이가 스스로 끝나도 끈다.
        c.start(testCourse(), SignalMode.REPLAY, "demo.json")
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(listOf("start", "stop", "start", "stop"), keep.calls)
    }

    @Test
    fun `failed start never starts the keep-alive`() = runTest {
        val keep = CountingKeepAlive()
        val c = controller(factory = { _, _ -> FailingSource() }, keepAlive = keep)
        assertTrue(c.start(testCourse(), SignalMode.LIVE, null) is AppResult.Err)
        assertTrue("start" !in keep.calls)
    }

    @Test
    fun `replay drive runs to the end, persists events, speaks and closes the session`() = runTest {
        val c = controller()
        val session = (c.start(testCourse(), SignalMode.REPLAY, null) as AppResult.Ok).value
        assertEquals(session, c.state.value.session)
        advanceTimeBy(40_000)
        runCurrent()

        assertTrue(c.state.value.finished)
        assertEquals(listOf(session.id), repo.finished)
        assertEquals(1, repo.events.count { it.type == DriveEventType.TURN_SIGNAL_MISSED })
        assertEquals(1, repo.events.count { it.type == DriveEventType.NEW_WARNING })
        assertTrue(repo.events.all { it.sessionId == session.id })
        val texts = speaker.spoken.map { it.first.text }
        assertTrue(texts.toString(), texts.any { it.contains("깜빡이") })
        assertTrue(texts.toString(), texts.any { it.contains("엔진 경고") })
        assertEquals(repo.events.size, c.state.value.eventCount)
        assertNotNull(c.state.value.estimatedSpeedKmh)
    }

    @Test
    fun `voice setting is passed to the speaker`() = runTest {
        val c = controller(settings = FeedbackSettings(voiceEnabled = false))
        c.start(testCourse(), SignalMode.REPLAY, "demo.json")
        advanceTimeBy(40_000)
        runCurrent()
        assertTrue(speaker.spoken.isNotEmpty())
        assertTrue(speaker.spoken.none { it.second })
    }

    @Test
    fun `turning voice off during a drive applies to the next message`() = runTest {
        val settings = FakeSettings(FeedbackSettings(voiceEnabled = true))
        val c = controller(settingsRepository = settings)
        c.start(testCourse(), SignalMode.REPLAY, "demo.json")
        while (speaker.spoken.isEmpty()) advanceTimeBy(250)
        assertTrue(speaker.spoken.single().second)
        settings.update { it.copy(voiceEnabled = false) }
        advanceTimeBy(40_000)
        runCurrent()
        assertTrue(speaker.spoken.size > 1)
        assertTrue(speaker.spoken.drop(1).none { it.second })
    }

    @Test
    fun `only one drive at a time and stop ends it`() = runTest {
        val c = controller()
        assertTrue(c.start(testCourse(), SignalMode.REPLAY, "demo.json") is AppResult.Ok)
        assertTrue(c.start(testCourse(), SignalMode.REPLAY, "demo.json") is AppResult.Err)
        advanceTimeBy(2_000)
        val stopped = c.stop()
        assertEquals(repo.started.single().id, (stopped as AppResult.Ok).value?.id)
        assertTrue(c.state.value.finished)
        assertEquals(AppResult.Ok(null), c.stop())
        // 종료 뒤에는 새 주행을 시작할 수 있다.
        assertTrue(c.start(testCourse(), SignalMode.REPLAY, "demo.json") is AppResult.Ok)
        c.stop()
    }

    @Test
    fun `signal source failure is returned and the source is stopped`() = runTest {
        val failing = FailingSource()
        val c = controller(factory = { _, _ -> failing })
        val result = c.start(testCourse(), SignalMode.LIVE, null)
        assertTrue((result as AppResult.Err).error is AppError.SignalUnavailable)
        assertTrue(failing.stopped)
        assertTrue(repo.started.isEmpty())
    }

    @Test
    fun `session creation failure stops the source`() = runTest {
        repo.failStart = true
        val c = controller()
        assertTrue((c.start(testCourse(), SignalMode.REPLAY, "demo.json") as AppResult.Err).error is AppError.Storage)
        assertFalse(c.state.value.finished)
        // 소스가 멈췄으니 새 시도가 가능하다.
        repo.failStart = false
        assertTrue(c.start(testCourse(), SignalMode.REPLAY, "demo.json") is AppResult.Ok)
        c.stop()
    }

    @Test
    fun `event write failures do not stop the drive`() = runTest {
        repo.failAppend = true
        val c = controller()
        c.start(testCourse(), SignalMode.REPLAY, "demo.json")
        advanceTimeBy(40_000)
        runCurrent()
        assertTrue(c.state.value.finished)
        assertEquals(1, repo.finished.size)
    }

    @Test
    fun `replay without any asset is rejected`() = runTest {
        val c = DriveControllerImpl(
            repo, FakeSettings(), { _, _ -> FailingSource() }, { emptyList() }, speaker,
            Clock { testScheduler.currentTime }, Logger.Noop, backgroundScope,
        )
        assertTrue((c.start(testCourse(), SignalMode.REPLAY, null) as AppResult.Err).error is AppError.InvalidInput)
        assertEquals(emptyList<String>(), c.replayAssets())
    }
}
