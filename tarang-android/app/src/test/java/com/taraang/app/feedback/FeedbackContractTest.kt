package com.taraang.app.feedback

import com.taraang.app.domain.model.DataConfidence
import com.taraang.app.domain.model.DriveEvent
import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.model.FeedbackMessage
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.FeedbackType
import com.taraang.app.domain.model.Skill
import com.taraang.app.domain.signal.Caution
import com.taraang.app.domain.signal.CautionType
import com.taraang.app.domain.signal.NaviSnapshot
import com.taraang.app.domain.signal.NextTurn
import com.taraang.app.domain.signal.SignalAvailability
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.domain.signal.TurnKind
import com.taraang.app.domain.signal.TurnSignalState
import com.taraang.app.domain.signal.VehicleSnapshot
import com.taraang.app.domain.signal.WarningType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ARCHITECTURE.md 4.3·5장 계약 명확화(어린이보호구역, 신호 미확인 회전, 붙은 회전, 권한 거부)를 고정한다. */
class FeedbackContractTest {

    private val all = SignalAvailability(SignalKey.entries.associateWith { SignalStatus.AVAILABLE })

    /** 북쪽으로 [kmh]로 [ms] 동안 달리며 틱마다 [navi]·[vehicle]을 정한다. */
    private fun drive(
        engine: FeedbackEngine,
        kmh: Double,
        ms: Long,
        availability: SignalAvailability = all,
        startMs: Long = 10_000,
        frame: (traveledM: Double) -> Pair<VehicleSnapshot, NaviSnapshot>,
    ): Pair<List<FeedbackMessage>, List<DriveEvent>> {
        val messages = mutableListOf<FeedbackMessage>()
        val events = mutableListOf<DriveEvent>()
        var t = startMs
        var traveled = 0.0
        repeat((ms / 250).toInt()) {
            t += 250
            traveled += kmh / 3.6 * 0.25
            val (v, n) = frame(traveled)
            val r = engine.tick(v, n.copy(timestampMs = t, position = START.north(traveled)), availability, t)
            r.message?.let(messages::add)
            events += r.events
        }
        return messages to events
    }

    private fun engine(course: com.taraang.app.domain.model.Course = testCourse()) = FeedbackEngine("s", course, FeedbackSettings())

    private val idle = VehicleSnapshot(turnSignal = TurnSignalState.OFF, brakePressed = false)

    @Test
    fun `school zone inside means remainM 0, approaching does not count`() {
        val zone = SchoolZone.evaluate(listOf(Caution(CautionType.SCHOOL_ZONE, 0)), FeedbackConfig())
        assertTrue(zone.inside)
        val near = SchoolZone.evaluate(listOf(Caution(CautionType.SCHOOL_ZONE, 5)), FeedbackConfig())
        assertFalse(near.inside)
        assertEquals(5, near.aheadM)
        assertFalse(SchoolZone.evaluate(emptyList(), FeedbackConfig()).inside)
    }

    @Test
    fun `leaving the school zone ends speeding judgment immediately`() {
        val e = engine()
        // 구역 안 2초 후 caution이 사라지면 그 뒤 과속은 보호구역 과속이 아니다.
        val (_, events) = drive(e, 45.0, 8_000) { m ->
            idle to NaviSnapshot(cautions = if (m < 25) listOf(Caution(CautionType.SCHOOL_ZONE, 0)) else emptyList())
        }
        assertTrue(events.none { it.type == DriveEventType.SCHOOL_ZONE_SPEEDING })
    }

    @Test
    fun `unknown signal confidence makes left and u-turns cautious but not right turns`() {
        assertTrue(FeedbackMessages.skillPreview(maneuver(0, Skill.U_TURN, 0.0, DataConfidence.UNKNOWN)).contains("신호가 확인되지 않은 유턴"))
        assertTrue(FeedbackMessages.skillPreview(maneuver(0, Skill.LEFT_TURN_SIGNALIZED, 0.0, DataConfidence.UNKNOWN)).contains("신호가 확인되지 않은 좌회전"))
        assertTrue(FeedbackMessages.skillPreview(maneuver(0, Skill.LEFT_TURN_UNKNOWN, 0.0, DataConfidence.ESTIMATED)).contains("신호가 확인되지 않은 좌회전"))
        assertEquals("곧 오늘 연습할 우회전 구간이에요.", FeedbackMessages.skillPreview(maneuver(0, Skill.RIGHT_TURN, 0.0, DataConfidence.UNKNOWN)))
    }

    @Test
    fun `turn less than 40m after the previous one is not judged`() {
        val tracker = TurnTracker()
        val judge = TurnSignalJudge()
        val results = mutableListOf<TurnSignalJudge.Judgment>()
        var t = 0L
        fun step(kind: TurnKind, d: Double, signal: TurnSignalState) {
            t += 250
            judge.judge(tracker.update(NextTurn(kind, d), START, t), signal)?.let(results::add)
        }
        listOf(80.0, 50.0, 29.0, 10.0, 2.0).forEach { step(TurnKind.RIGHT, it, TurnSignalState.RIGHT) }
        // 바로 다음 회전이 25m 뒤: 판정 거리(40m) 전에 신호를 넣을 수 없으니 기록하지 않는다.
        listOf(25.0, 15.0, 5.0).forEach { step(TurnKind.LEFT, it, TurnSignalState.RIGHT) }
        assertEquals(listOf(DriveEventType.TURN_SIGNAL_OK), results.map { it.type })
    }

    @Test
    fun `permission denied signal disables its rules`() {
        val denied = SignalAvailability(all.status + (SignalKey.TURN_SIGNAL to SignalStatus.PERMISSION_DENIED) + (SignalKey.WARNINGS to SignalStatus.PERMISSION_DENIED))
        val e = engine()
        val (messages, events) = drive(e, 30.0, 20_000, availability = denied) { m ->
            val v = idle.copy(activeWarnings = if (m > 50) setOf(WarningType.ENGINE) else emptySet())
            v to NaviSnapshot(nextTurn = NextTurn(TurnKind.RIGHT, (120 - m).coerceAtLeast(0.0)))
        }
        assertTrue(messages.none { it.type == FeedbackType.TURN_SIGNAL_MISSING || it.type == FeedbackType.NEW_WARNING })
        assertTrue(events.none { it.type == DriveEventType.TURN_SIGNAL_MISSED || it.type == DriveEventType.NEW_WARNING })
    }

    @Test
    fun `signal that comes back later re-baselines warnings instead of announcing old ones`() {
        val e = engine()
        val off = SignalAvailability(all.status + (SignalKey.WARNINGS to SignalStatus.ERROR))
        drive(e, 0.0, 1_000, availability = off) { idle.copy(activeWarnings = setOf(WarningType.LOW_FUEL)) to NaviSnapshot() }
        val (messages, _) = drive(e, 0.0, 1_000, startMs = 20_000) { idle.copy(activeWarnings = setOf(WarningType.LOW_FUEL)) to NaviSnapshot() }
        assertTrue(messages.isEmpty())
    }
}
