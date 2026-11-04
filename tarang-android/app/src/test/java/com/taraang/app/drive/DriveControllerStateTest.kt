package com.taraang.app.drive

import com.taraang.app.audio.FeedbackSpeaker
import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Clock
import com.taraang.app.core.Logger
import com.taraang.app.domain.model.DriveEvent
import com.taraang.app.domain.model.DriveSession
import com.taraang.app.domain.model.DriveSummary
import com.taraang.app.domain.model.FeedbackMessage
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.SegmentFeeling
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.repository.DriveRepository
import com.taraang.app.domain.repository.SettingsRepository
import com.taraang.app.feedback.testCourse
import com.taraang.app.vehicle.MemoryReplayAssets
import com.taraang.app.vehicle.ReplaySignalSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 두 번째 주행이 이전 주행의 finished/이벤트 수/마지막 피드백을 물려받지 않는지, 상태 기계 전이가 맞는지 본다. */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveControllerStateTest {

    private class Repo : DriveRepository {
        val started = mutableListOf<DriveSession>()
        override val sessions = flowOf(emptyList<DriveSession>())
        override suspend fun startSession(courseId: String, mode: SignalMode): AppResult<DriveSession> =
            AppResult.Ok(DriveSession("s${started.size}", courseId, mode, 0, null, false).also { started += it })
        override suspend fun appendEvent(event: DriveEvent) = AppResult.Ok(Unit)
        override suspend fun finishSession(sessionId: String, drivenAlongCourseM: Double?, interrupted: Boolean): AppResult<DriveSession> =
            AppResult.Ok(started.first { it.id == sessionId }.copy(endedAtMs = 1))
        override suspend fun saveFeelings(feelings: List<SegmentFeeling>) = AppResult.Ok(Unit)
        override suspend fun summary(sessionId: String): AppResult<DriveSummary> = AppResult.Err(AppError.Storage("n/a"))
        override suspend fun levelProgress() = LevelProgress(1, 0, 3)
        override suspend fun closeInterruptedSessions() = 0
        override suspend fun deleteAllHistory(): AppResult<Unit> = AppResult.Ok(Unit)
    }

    private class Settings : SettingsRepository {
        override val settings = MutableStateFlow(FeedbackSettings())
        override suspend fun update(transform: (FeedbackSettings) -> FeedbackSettings) = Unit
    }

    private object Silent : FeedbackSpeaker {
        override fun speak(message: FeedbackMessage, voiceEnabled: Boolean) = Unit
        override fun release() = Unit
    }

    /** 0.5초 간격 40km/h, 깜빡이 없이 우회전을 지나는 짧은 로그(이벤트·피드백이 반드시 생긴다). */
    private fun log(): String {
        val frames = (0..40).joinToString(",") { i ->
            val lat = 35.2300 + i * 5.6 / 111_320.0
            val remain = 120.0 - i * 5.6
            val turn = if (remain > 0) """{"type":"RIGHT","distanceM":$remain}""" else """{"type":"STRAIGHT","distanceM":900}"""
            """{"t":${i * 500},"lat":$lat,"lon":129.08,"turnSignal":"OFF","brake":false,"warnings":[],"nextTurn":$turn,"caution":[]}"""
        }
        return """{"courseId":"L1-test","frames":[$frames]}"""
    }

    private fun TestScope.controller(repo: Repo): DriveControllerImpl {
        val clock = Clock { testScheduler.currentTime }
        val assets = MemoryReplayAssets(mapOf("demo.json" to log()))
        return DriveControllerImpl(
            repo, Settings(),
            { _, name -> ReplaySignalSource(assets, requireNotNull(name), clock, backgroundScope, Logger.Noop) },
            assets, Silent, clock, Logger.Noop, backgroundScope,
        )
    }

    @Test
    fun `second drive starts with a clean state and the new session`() = runTest {
        val repo = Repo()
        val c = controller(repo)
        assertEquals(DrivePhase.IDLE, c.phase.value)

        c.start(testCourse(), SignalMode.REPLAY, "demo.json")
        assertEquals(DrivePhase.RUNNING, c.phase.value)
        advanceTimeBy(30_000)
        runCurrent()
        val first = c.state.value
        assertTrue(first.finished)
        assertTrue(first.eventCount > 0)
        assertTrue(first.lastFeedback != null)
        assertEquals(DrivePhase.FINISHED, c.phase.value)

        val second = (c.start(testCourse(), SignalMode.REPLAY, "demo.json") as AppResult.Ok).value
        // start가 Ok를 돌려준 시점에 이미 새 상태여야 한다(화면이 이전 finished=true를 보지 않게).
        val s = c.state.value
        assertEquals(second, s.session)
        assertFalse(s.finished)
        assertEquals(0, s.eventCount)
        assertNull(s.lastFeedback)
        assertEquals(DrivePhase.RUNNING, c.phase.value)
        c.stop()
        assertEquals(DrivePhase.FINISHED, c.phase.value)
    }

    @Test
    fun `duplicate start while running is refused and failed start can be retried`() = runTest {
        val c = controller(Repo())
        assertTrue(c.start(testCourse(), SignalMode.REPLAY, "demo.json") is AppResult.Ok)
        assertTrue(c.start(testCourse(), SignalMode.REPLAY, "demo.json") is AppResult.Err)
        assertEquals(DrivePhase.RUNNING, c.phase.value)
        c.stop()

        assertTrue(c.start(testCourse(), SignalMode.REPLAY, "missing.json") is AppResult.Err)
        assertEquals(DrivePhase.FAILED, c.phase.value)
        assertTrue(c.start(testCourse(), SignalMode.REPLAY, "demo.json") is AppResult.Ok)
        c.stop()
    }

    @Test
    fun `phase transitions follow the documented machine`() {
        assertTrue(DrivePhase.IDLE.canMoveTo(DrivePhase.STARTING))
        assertFalse(DrivePhase.RUNNING.canMoveTo(DrivePhase.STARTING))
        assertFalse(DrivePhase.STOPPING.canMoveTo(DrivePhase.STARTING))
        assertTrue(DrivePhase.STARTING.canMoveTo(DrivePhase.FAILED))
        assertTrue(DrivePhase.FINISHED.canMoveTo(DrivePhase.STARTING))
        assertFalse(DrivePhase.IDLE.canMoveTo(DrivePhase.RUNNING))
    }
}
