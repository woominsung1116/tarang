package com.taraang.app.vehicle

import com.taraang.app.core.AppResult
import com.taraang.app.core.Clock
import com.taraang.app.core.LatLng
import com.taraang.app.core.Logger
import com.taraang.app.domain.signal.CautionType
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.domain.signal.TurnKind
import com.taraang.app.domain.signal.TurnSignalState
import com.taraang.app.domain.signal.WarningType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryReplayAssets(private val files: Map<String, String>) : ReplayAssets {
    override fun list() = files.keys.sorted()
    override suspend fun read(name: String) = files[name]
}

const val SAMPLE_REPLAY = """
{
  "schemaVersion": 1,
  "courseId": "L1-test",
  "title": "테스트",
  "tickMs": 500,
  "unknownTopLevel": 1,
  "frames": [
    {"t": 500, "lat": 35.2301, "lon": 129.08, "turnSignal": "RIGHT", "brake": true,
     "warnings": ["TIRE_PRESSURE", "NOT_A_WARNING"], "nextTurn": {"type": "UTURN", "distanceM": 40.0},
     "caution": [{"type": "SCHOOL_ZONE", "remainM": 120}, {"type": "FOG", "remainM": 9}]},
    {"t": 0, "lat": 35.2300, "lon": 129.08, "heading": 0.0, "speedLimitKmh": 50, "turnSignal": "OFF", "brake": false,
     "warnings": [], "nextTurn": {"type": "RIGHT", "distanceM": 120.0}, "caution": []},
    {"t": 1000, "lat": 35.2302, "lon": 129.08, "turnSignal": "WOBBLE", "nextTurn": {"type": "SPIRAL", "distanceM": 10.0}}
  ]
}
"""

@OptIn(ExperimentalCoroutinesApi::class)
class ReplaySignalSourceTest {

    private fun TestScope.source(files: Map<String, String> = mapOf("a.json" to SAMPLE_REPLAY), name: String = "a.json") =
        ReplaySignalSource(MemoryReplayAssets(files), name, Clock { testScheduler.currentTime }, backgroundScope, Logger.Noop)

    @Test
    fun `parser sorts frames and tolerates unknown values`() {
        val log = (ReplayLogParser.parse(SAMPLE_REPLAY) as AppResult.Ok).value
        assertEquals(listOf(0L, 500L, 1000L), log.frames.map { it.offsetMs })
        val second = log.frames[1]
        assertEquals(setOf(WarningType.TIRE_PRESSURE), second.vehicle.activeWarnings)
        assertEquals(TurnKind.UTURN, second.navi.nextTurn?.kind)
        assertEquals(listOf(CautionType.SCHOOL_ZONE, CautionType.OTHER), second.navi.cautions.map { it.type })
        assertEquals(null, log.frames[2].vehicle.turnSignal)
        assertEquals(TurnKind.OTHER, log.frames[2].navi.nextTurn?.kind)
    }

    @Test
    fun `empty or broken logs are errors`() {
        assertTrue(ReplayLogParser.parse("""{"frames": []}""") is AppResult.Err)
        assertTrue(ReplayLogParser.parse("nope") is AppResult.Err)
    }

    @Test
    fun `frames are emitted on schedule and finished is raised at the end`() = runTest {
        val src = source()
        assertTrue(src.start() is AppResult.Ok)
        runCurrent()
        assertEquals(LatLng(35.2300, 129.08), src.navi.value.position)
        assertEquals(TurnSignalState.OFF, src.vehicle.value.turnSignal)
        assertEquals(SignalStatus.AVAILABLE, src.availability.value.status[SignalKey.TURN_SIGNAL])
        assertEquals(SignalStatus.UNSUPPORTED, src.availability.value.status[SignalKey.LOW_BEAM])

        advanceTimeBy(499)
        runCurrent()
        assertEquals(TurnSignalState.OFF, src.vehicle.value.turnSignal)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(TurnSignalState.RIGHT, src.vehicle.value.turnSignal)
        assertEquals(500L, src.navi.value.timestampMs)
        assertFalse(src.finished.value)

        advanceTimeBy(500)
        runCurrent()
        assertTrue(src.finished.value)
    }

    @Test
    fun `stop halts playback`() = runTest {
        val src = source()
        src.start()
        runCurrent()
        src.stop()
        advanceTimeBy(5_000)
        runCurrent()
        assertFalse(src.finished.value)
    }

    @Test
    fun `missing asset is an error`() = runTest {
        assertTrue(source(name = "missing.json").start() is AppResult.Err)
    }
}
