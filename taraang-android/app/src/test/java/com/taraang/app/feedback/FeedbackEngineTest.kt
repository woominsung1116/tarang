package com.taraang.app.feedback

import com.taraang.app.core.LatLng
import com.taraang.app.domain.model.Course
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
import org.junit.Assert.assertTrue
import org.junit.Test

/** 위치를 실제로 움직여 속도 추정까지 포함한 엔진 전체를 돌린다. */
private class Harness(
    course: Course = testCourse(),
    settings: FeedbackSettings = FeedbackSettings(),
    var availability: SignalAvailability = allAvailable(),
) {
    val engine = FeedbackEngine("s1", course, settings)
    var now = 10_000L
    var position: LatLng = START
    var heading: Pair<Double, Double> = 1.0 to 0.0 // 북, 동 비율
    var kmh = 0.0
    var vehicle = VehicleSnapshot(turnSignal = TurnSignalState.OFF, brakePressed = false)
    var navi = NaviSnapshot()
    var traveledM = 0.0
    val messages = mutableListOf<FeedbackMessage>()
    val events = mutableListOf<DriveEvent>()
    var lastSpeed: Double? = null

    fun run(ms: Long, tickMs: Long = 250, each: Harness.() -> Unit = {}) {
        repeat((ms / tickMs).toInt()) {
            now += tickMs
            val step = kmh / 3.6 * tickMs / 1000.0
            traveledM += step
            position = position.north(step * heading.first).east(step * heading.second)
            each()
            val r = engine.tick(vehicle, navi.copy(timestampMs = now, position = position), availability, now)
            r.message?.let(messages::add)
            events += r.events
            lastSpeed = r.speedKmh
        }
    }

    fun spoken(type: FeedbackType) = messages.filter { it.type == type }
    fun recorded(type: DriveEventType) = events.filter { it.type == type }

    companion object {
        fun allAvailable() = SignalAvailability(SignalKey.entries.associateWith { SignalStatus.AVAILABLE })
    }
}

private fun SignalAvailability.without(vararg keys: SignalKey) =
    SignalAvailability(status + keys.associateWith { SignalStatus.UNSUPPORTED })

class FeedbackEngineTest {

    // --- 새 경고 ---

    @Test
    fun `warning present at start is baseline, new one is announced`() {
        val h = Harness()
        h.vehicle = h.vehicle.copy(activeWarnings = setOf(WarningType.TIRE_PRESSURE))
        h.run(2_000)
        assertTrue(h.messages.isEmpty())
        h.vehicle = h.vehicle.copy(activeWarnings = setOf(WarningType.TIRE_PRESSURE, WarningType.ENGINE))
        h.run(500)
        val msg = h.spoken(FeedbackType.NEW_WARNING).single()
        assertTrue(msg.text.startsWith("엔진 경고가 켜졌어요"))
        assertEquals("ENGINE", h.recorded(DriveEventType.NEW_WARNING).single().detail)
    }

    @Test
    fun `warning that turns off and on again is announced again`() {
        val h = Harness()
        h.run(500)
        h.vehicle = h.vehicle.copy(activeWarnings = setOf(WarningType.LOW_FUEL))
        h.run(500)
        h.vehicle = h.vehicle.copy(activeWarnings = emptySet())
        h.run(500)
        h.vehicle = h.vehicle.copy(activeWarnings = setOf(WarningType.LOW_FUEL))
        h.run(500)
        assertEquals(2, h.spoken(FeedbackType.NEW_WARNING).size)
    }

    @Test
    fun `warnings that turn on together are spoken as one message`() {
        // 따로 말하면 250ms 뒤 두 번째가 첫 번째를 끊는다(코드 리뷰 M3).
        val h = Harness()
        h.run(500)
        h.vehicle = h.vehicle.copy(activeWarnings = setOf(WarningType.DOOR_OPEN, WarningType.SEAT_BELT))
        h.run(5_000)
        val msg = h.spoken(FeedbackType.NEW_WARNING).single()
        assertTrue(msg.text, msg.text.startsWith("${WarningType.SEAT_BELT.label}·${WarningType.DOOR_OPEN.label} 경고가 켜졌어요"))
        assertEquals(2, h.recorded(DriveEventType.NEW_WARNING).size)
    }

    @Test
    fun `warnings rule is off when signal unavailable`() {
        val h = Harness(availability = Harness.allAvailable().without(SignalKey.WARNINGS))
        h.run(500)
        h.vehicle = h.vehicle.copy(activeWarnings = setOf(WarningType.ENGINE))
        h.run(1_000)
        assertTrue(h.messages.isEmpty())
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun `new warning cannot be switched off in settings`() {
        val settings = FeedbackSettings(enabled = FeedbackType.entries.associateWith { false })
        val h = Harness(settings = settings)
        h.run(500)
        h.vehicle = h.vehicle.copy(activeWarnings = setOf(WarningType.ENGINE))
        h.run(500)
        assertEquals(1, h.spoken(FeedbackType.NEW_WARNING).size)
    }

    // --- 어린이보호구역 ---

    @Test
    fun `school zone ahead is announced once within 300m`() {
        val h = Harness()
        h.kmh = 30.0
        var remain = 450.0
        h.run(20_000) {
            remain -= kmh / 3.6 * 0.25
            navi = navi.copy(cautions = listOf(Caution(CautionType.SCHOOL_ZONE, remain.toInt())))
        }
        val ahead = h.spoken(FeedbackType.SCHOOL_ZONE_AHEAD)
        assertEquals(1, ahead.size)
    }

    @Test
    fun `school zone ahead not announced beyond 300m`() {
        val h = Harness()
        h.navi = h.navi.copy(cautions = listOf(Caution(CautionType.SCHOOL_ZONE, 450)))
        h.run(3_000)
        assertTrue(h.messages.isEmpty())
    }

    @Test
    fun `speeding inside school zone for 3s is recorded and spoken`() {
        val h = Harness()
        h.navi = h.navi.copy(cautions = listOf(Caution(CautionType.SCHOOL_ZONE, 0)))
        h.kmh = 40.0
        h.run(6_000)
        assertEquals(1, h.spoken(FeedbackType.SCHOOL_ZONE_SPEED).size)
        assertEquals(1, h.recorded(DriveEventType.SCHOOL_ZONE_SPEEDING).size)
    }

    @Test
    fun `slow driving or short burst inside school zone is fine`() {
        val slow = Harness()
        slow.navi = slow.navi.copy(cautions = listOf(Caution(CautionType.SCHOOL_ZONE, 0)))
        slow.kmh = 25.0
        slow.run(8_000)
        assertTrue(slow.events.isEmpty())

        val burst = Harness()
        burst.navi = burst.navi.copy(cautions = listOf(Caution(CautionType.SCHOOL_ZONE, 0)))
        burst.kmh = 25.0
        burst.run(3_000)
        burst.kmh = 40.0
        burst.run(2_000)
        burst.kmh = 25.0
        burst.run(3_000)
        assertTrue(burst.recorded(DriveEventType.SCHOOL_ZONE_SPEEDING).isEmpty())
    }

    @Test
    fun `posted 20 kmh school zone uses the posted limit`() {
        val h = Harness()
        h.navi = h.navi.copy(cautions = listOf(Caution(CautionType.SCHOOL_ZONE, 0)), speedLimitKmh = 20)
        h.kmh = 26.0
        h.run(6_000)
        assertEquals(1, h.recorded(DriveEventType.SCHOOL_ZONE_SPEEDING).size)
        assertEquals(1, h.spoken(FeedbackType.SCHOOL_ZONE_SPEED).size)

        val under = Harness()
        under.navi = under.navi.copy(cautions = listOf(Caution(CautionType.SCHOOL_ZONE, 0)), speedLimitKmh = 20)
        under.kmh = 18.0
        under.run(6_000)
        assertTrue(under.recorded(DriveEventType.SCHOOL_ZONE_SPEEDING).isEmpty())
    }

    @Test
    fun `night-time 50 kmh school zone is not speeding at 40`() {
        val h = Harness()
        h.navi = h.navi.copy(cautions = listOf(Caution(CautionType.SCHOOL_ZONE, 0)), speedLimitKmh = 50)
        h.kmh = 40.0
        h.run(6_000)
        assertTrue(h.recorded(DriveEventType.SCHOOL_ZONE_SPEEDING).isEmpty())
    }

    @Test
    fun `school zone without a posted limit falls back to 30`() {
        listOf<Int?>(null, 0).forEach { posted ->
            val h = Harness()
            h.navi = h.navi.copy(cautions = listOf(Caution(CautionType.SCHOOL_ZONE, 0)), speedLimitKmh = posted)
            h.kmh = 36.0
            h.run(6_000)
            assertEquals("posted=$posted", 1, h.recorded(DriveEventType.SCHOOL_ZONE_SPEEDING).size)
        }
        // 제한속도 신호를 못 받으면 넘어온 값은 믿지 않는다.
        val unavailable = Harness(availability = Harness.allAvailable().without(SignalKey.SPEED_LIMIT))
        unavailable.navi = unavailable.navi.copy(cautions = listOf(Caution(CautionType.SCHOOL_ZONE, 0)), speedLimitKmh = 50)
        unavailable.kmh = 40.0
        unavailable.run(6_000)
        assertEquals(1, unavailable.recorded(DriveEventType.SCHOOL_ZONE_SPEEDING).size)
    }

    @Test
    fun `school zone rules are off without cautions signal`() {
        val h = Harness(availability = Harness.allAvailable().without(SignalKey.CAUTIONS))
        h.navi = h.navi.copy(cautions = listOf(Caution(CautionType.SCHOOL_ZONE, 0)))
        h.kmh = 50.0
        h.run(6_000)
        assertTrue(h.spoken(FeedbackType.SCHOOL_ZONE_SPEED).isEmpty())
        assertTrue(h.recorded(DriveEventType.SCHOOL_ZONE_SPEEDING).isEmpty())
    }

    // --- 제한속도 ---

    @Test
    fun `sustained speeding over limit+10 for 5s is recorded and spoken once`() {
        val h = Harness()
        h.navi = h.navi.copy(speedLimitKmh = 50)
        h.kmh = 70.0
        h.run(15_000)
        assertEquals(1, h.spoken(FeedbackType.SPEED_LIMIT_SUSTAINED).size)
        assertEquals(1, h.recorded(DriveEventType.SPEEDING).size)
    }

    @Test
    fun `speed within limit+10 or brief overshoot is ignored`() {
        val within = Harness()
        within.navi = within.navi.copy(speedLimitKmh = 50)
        within.kmh = 58.0
        within.run(10_000)
        assertTrue(within.events.isEmpty())

        val brief = Harness()
        brief.navi = brief.navi.copy(speedLimitKmh = 50)
        brief.kmh = 50.0
        brief.run(3_000)
        brief.kmh = 70.0
        brief.run(3_000)
        brief.kmh = 45.0
        brief.run(4_000)
        assertTrue(brief.recorded(DriveEventType.SPEEDING).isEmpty())
    }

    @Test
    fun `speed limit rule is off without speed limit or position`() {
        listOf(SignalKey.SPEED_LIMIT, SignalKey.POSITION).forEach { key ->
            val h = Harness(availability = Harness.allAvailable().without(key))
            h.navi = h.navi.copy(speedLimitKmh = 50)
            h.kmh = 80.0
            h.run(10_000)
            assertTrue("$key", h.recorded(DriveEventType.SPEEDING).isEmpty())
        }
    }

    @Test
    fun `disabled in settings still records but stays silent`() {
        val settings = FeedbackSettings(enabled = FeedbackType.entries.associateWith { it != FeedbackType.SPEED_LIMIT_SUSTAINED })
        val h = Harness(settings = settings)
        h.navi = h.navi.copy(speedLimitKmh = 50)
        h.kmh = 75.0
        h.run(10_000)
        assertTrue(h.spoken(FeedbackType.SPEED_LIMIT_SUSTAINED).isEmpty())
        assertEquals(1, h.recorded(DriveEventType.SPEEDING).size)
    }

    // --- 방향지시등 ---

    private fun approachTurn(kind: TurnKind, signal: TurnSignalState, startM: Double = 120.0): Harness {
        val h = Harness()
        h.kmh = 30.0
        h.vehicle = h.vehicle.copy(turnSignal = signal)
        h.run(((startM + 40) / (30 / 3.6) * 1000).toLong()) {
            val remain = startM - traveledM
            navi = navi.copy(nextTurn = if (remain > -5) NextTurn(kind, remain.coerceAtLeast(0.0)) else NextTurn(TurnKind.STRAIGHT, 500.0))
        }
        return h
    }

    @Test
    fun `signal off before turn gets one nag and MISSED record`() {
        val h = approachTurn(TurnKind.RIGHT, TurnSignalState.OFF)
        val nag = h.spoken(FeedbackType.TURN_SIGNAL_MISSING)
        assertEquals(1, nag.size)
        assertEquals(FeedbackMessages.TURN_SIGNAL, nag.single().text)
        assertEquals(1, h.recorded(DriveEventType.TURN_SIGNAL_MISSED).size)
        assertTrue(h.recorded(DriveEventType.TURN_SIGNAL_OK).isEmpty())
    }

    @Test
    fun `correct signal is silent and recorded OK`() {
        val h = approachTurn(TurnKind.LEFT, TurnSignalState.LEFT)
        assertTrue(h.messages.isEmpty())
        assertEquals(1, h.recorded(DriveEventType.TURN_SIGNAL_OK).size)
    }

    @Test
    fun `wrong side signal is nagged`() {
        val h = approachTurn(TurnKind.RIGHT, TurnSignalState.LEFT)
        assertEquals(1, h.spoken(FeedbackType.TURN_SIGNAL_MISSING).size)
        assertEquals(1, h.recorded(DriveEventType.TURN_SIGNAL_MISSED).size)
    }

    @Test
    fun `roundabout is neither nagged nor recorded`() {
        val h = approachTurn(TurnKind.ROUNDABOUT, TurnSignalState.OFF)
        assertTrue(h.spoken(FeedbackType.TURN_SIGNAL_MISSING).isEmpty())
        assertTrue(h.recorded(DriveEventType.TURN_SIGNAL_MISSED).isEmpty())
    }

    @Test
    fun `nag still comes at 50m, before the 40m record`() {
        val h = approachTurn(TurnKind.RIGHT, TurnSignalState.OFF)
        val nagAtM = (h.spoken(FeedbackType.TURN_SIGNAL_MISSING).single().timestampMs - 10_000) / 1000.0 * (30 / 3.6)
        assertTrue("nag at ${120 - nagAtM}m before", 120 - nagAtM in 40.0..50.0)
    }

    @Test
    fun `turn signal rule is off without turn signal input`() {
        val h = Harness(availability = Harness.allAvailable().without(SignalKey.TURN_SIGNAL))
        h.kmh = 30.0
        h.run(20_000) { navi = navi.copy(nextTurn = NextTurn(TurnKind.RIGHT, (120 - traveledM).coerceAtLeast(0.0))) }
        assertTrue(h.messages.isEmpty())
        assertTrue(h.events.isEmpty())
    }

    @Test
    fun `nothing is spoken within 20m of a turn`() {
        val h = Harness()
        h.kmh = 20.0
        h.vehicle = h.vehicle.copy(turnSignal = TurnSignalState.RIGHT)
        h.navi = h.navi.copy(nextTurn = NextTurn(TurnKind.RIGHT, 15.0))
        h.run(1_000)
        // 회전 직전에 새 경고가 켜져도 기다렸다가 말한다.
        h.vehicle = h.vehicle.copy(activeWarnings = setOf(WarningType.ENGINE))
        h.run(1_000)
        assertTrue(h.messages.isEmpty())
        h.navi = h.navi.copy(nextTurn = null)
        h.run(6_000)
        assertEquals(1, h.spoken(FeedbackType.NEW_WARNING).size)
    }

    // --- 연습 기술 예고 ---

    @Test
    fun `practice skill is previewed once around 150m before`() {
        val course = testCourse(listOf(maneuver(0, Skill.RIGHT_TURN, 200.0), maneuver(1, Skill.LEFT_TURN_SIGNALIZED, 600.0)))
        val h = Harness(course = course)
        h.kmh = 36.0
        h.run(70_000)
        val previews = h.spoken(FeedbackType.SKILL_PREVIEW)
        assertEquals(1, previews.size)
        assertTrue(previews.single().text.contains("신호 좌회전"))
        // 예고 시점 위치가 지점 150m~50m 앞
        val at = (previews.single().timestampMs - 10_000) / 1000.0 * 10.0
        assertTrue("at=$at", at in 450.0..550.0)
    }

    @Test
    fun `practice turn right behind a non-practice turn is still previewed`() {
        // 번들 L1-s3: 270m 우회전 바로 뒤 285m 신호 미확인 좌회전. 다음 지점(우회전)만 보면 좌회전은 50m 안에서야 다음이 된다.
        val course = testCourse(listOf(maneuver(0, Skill.RIGHT_TURN, 270.0), maneuver(1, Skill.LEFT_TURN_UNKNOWN, 285.0, DataConfidence.UNKNOWN)))
        val h = Harness(course = course)
        h.kmh = 36.0
        h.run(40_000)
        val preview = h.spoken(FeedbackType.SKILL_PREVIEW).single()
        assertTrue(preview.text.contains("신호가 확인되지 않은 좌회전"))
        val at = (preview.timestampMs - 10_000) / 1000.0 * 10.0
        assertTrue("at=$at", 285 - at in 50.0..150.0)
    }

    @Test
    fun `right turns are previewed when they are the only practice skill`() {
        val course = testCourse(listOf(maneuver(0, Skill.RIGHT_TURN, 300.0)))
        val h = Harness(course = course)
        h.kmh = 36.0
        h.run(30_000)
        assertEquals(1, h.spoken(FeedbackType.SKILL_PREVIEW).size)
    }

    @Test
    fun `unknown left turn gets cautious preview`() {
        val course = testCourse(listOf(maneuver(0, Skill.LEFT_TURN_UNKNOWN, 300.0, DataConfidence.UNKNOWN)))
        val h = Harness(course = course)
        h.kmh = 36.0
        h.run(30_000)
        assertTrue(h.spoken(FeedbackType.SKILL_PREVIEW).single().text.contains("신호가 확인되지 않은 좌회전"))
    }

    // --- 급제동 ---

    /** 50km/h로 달리다 [decelKmhPerS]로 20km/h까지 줄이고 다시 가속한다. */
    private fun Harness.brakeOnce(decelKmhPerS: Double) {
        kmh = 50.0
        run(4_000)
        vehicle = vehicle.copy(brakePressed = true)
        run(((50.0 - 20.0) / decelKmhPerS * 1000).toLong()) { kmh = (kmh - decelKmhPerS * 0.25).coerceAtLeast(20.0) }
        vehicle = vehicle.copy(brakePressed = false)
        kmh = 40.0
        run(3_000)
    }

    @Test
    fun `third hard brake is spoken once after a calm period`() {
        val h = Harness()
        h.brakeOnce(20.0)
        h.brakeOnce(20.0)
        assertTrue(h.spoken(FeedbackType.HARD_BRAKE_REPEAT).isEmpty())
        h.brakeOnce(20.0)
        h.run(6_000)
        assertEquals(3, h.recorded(DriveEventType.HARD_BRAKE).size)
        assertEquals(1, h.spoken(FeedbackType.HARD_BRAKE_REPEAT).size)
        h.brakeOnce(20.0)
        h.run(6_000)
        assertEquals(1, h.spoken(FeedbackType.HARD_BRAKE_REPEAT).size)
    }

    @Test
    fun `end to end, above 30 kmh 14 kmh per second is not a hard brake but 16 is`() {
        val soft = Harness()
        soft.brakeOnce(14.0)
        assertTrue(soft.recorded(DriveEventType.HARD_BRAKE).isEmpty())
        val hard = Harness()
        hard.brakeOnce(16.0)
        assertEquals(1, hard.recorded(DriveEventType.HARD_BRAKE).size)
    }

    @Test
    fun `gentle braking is not a hard brake`() {
        val h = Harness()
        repeat(4) { h.brakeOnce(4.0) }
        assertTrue(h.recorded(DriveEventType.HARD_BRAKE).isEmpty())
    }

    @Test
    fun `stopping to a standstill is not a hard brake`() {
        val h = Harness()
        repeat(3) {
            h.kmh = 40.0
            h.run(4_000)
            h.vehicle = h.vehicle.copy(brakePressed = true)
            // 초당 10km/h로 정지: 예전 8km/h 기준에서는 급제동으로 세던 정상 정지.
            h.run(5_000) { kmh = (kmh - 2.5).coerceAtLeast(0.0) }
            h.run(2_000)
            h.vehicle = h.vehicle.copy(brakePressed = false)
        }
        assertTrue(h.recorded(DriveEventType.HARD_BRAKE).isEmpty())
    }

    @Test
    fun `fast slowdown without brake pedal is not a hard brake`() {
        val h = Harness()
        h.kmh = 50.0
        h.run(4_000)
        h.run(1_500) { kmh = (kmh - 5.0).coerceAtLeast(20.0) }
        assertTrue(h.recorded(DriveEventType.HARD_BRAKE).isEmpty())
    }

    @Test
    fun `hard brake rule is off without brake signal`() {
        val h = Harness(availability = Harness.allAvailable().without(SignalKey.BRAKE))
        repeat(3) { h.brakeOnce(20.0) }
        assertTrue(h.recorded(DriveEventType.HARD_BRAKE).isEmpty())
    }

    // --- 코스 이탈 ---

    @Test
    fun `leaving the course is recorded once and pauses turn judgment`() {
        val h = Harness()
        h.kmh = 36.0
        h.heading = 0.0 to 1.0
        h.vehicle = h.vehicle.copy(turnSignal = TurnSignalState.OFF)
        h.run(15_000)
        assertEquals(1, h.recorded(DriveEventType.OFF_COURSE).size)
        h.run(8_000) { navi = navi.copy(nextTurn = NextTurn(TurnKind.RIGHT, (80 - (traveledM - 150)).coerceAtLeast(0.0))) }
        assertTrue(h.recorded(DriveEventType.TURN_SIGNAL_MISSED).isEmpty())
    }

    // --- 이벤트 메타데이터 ---

    @Test
    fun `events carry session, position and nearby maneuver index`() {
        val course = testCourse(listOf(maneuver(7, Skill.RIGHT_TURN, 100.0)))
        val h = Harness(course = course)
        h.kmh = 30.0
        h.run(((100 + 40) / (30 / 3.6) * 1000).toLong()) {
            val remain = 120 - traveledM
            navi = navi.copy(nextTurn = if (remain > 0) NextTurn(TurnKind.RIGHT, remain) else null)
        }
        val missed = h.recorded(DriveEventType.TURN_SIGNAL_MISSED).single()
        assertEquals("s1", missed.sessionId)
        assertEquals(7, missed.maneuverIndex)
        assertTrue(missed.position != null)
    }

    @Test
    fun `speed is reported once enough positions arrive`() {
        val h = Harness()
        h.kmh = 36.0
        h.run(3_000)
        assertEquals(36.0, h.lastSpeed!!, 1.5)
    }
}
