package com.taraang.app.feedback.rules

import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.signal.NaviSnapshot
import com.taraang.app.domain.signal.VehicleSnapshot
import com.taraang.app.feedback.FeedbackConfig
import com.taraang.app.feedback.SchoolZoneState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 급제동 경계값(eTAS 택시 급감속 준용): 시작 속도 30km/h 이하 14, 초과 15km/h/s, 감속 후 6km/h 이상.
 * 속도 추정을 거치지 않도록 규칙에 속도를 직접 넣는다.
 */
class HardBrakeRuleTest {

    private val noZone = SchoolZoneState(aheadM = null, inside = false, cautionPresent = false)

    /** 브레이크를 밟은 채 [speeds]를 [stepMs] 간격으로 넣고 기록된 급제동 수를 센다. */
    private fun hardBrakes(speeds: List<Double>, stepMs: Long = 1_000, smoothed: List<Double?> = speeds): Int {
        val rule = HardBrakeRule(FeedbackConfig())
        val events = mutableListOf<RuleEvent>()
        speeds.forEachIndexed { i, kmh ->
            val ctx = TickContext(
                nowMs = i * stepMs,
                vehicle = VehicleSnapshot(brakePressed = true),
                navi = NaviSnapshot(),
                speedKmh = smoothed[i],
                responsiveSpeedKmh = kmh,
                turn = null,
                schoolZone = noZone,
                nextPracticeManeuver = null,
                offCourse = false,
            )
            rule.evaluate(ctx, events)
        }
        return events.count { it.type == DriveEventType.HARD_BRAKE }
    }

    @Test
    fun `at or below 30 kmh a 14 kmh drop in one second counts, 13 does not`() {
        assertEquals(1, hardBrakes(listOf(30.0, 16.0)))
        assertEquals(0, hardBrakes(listOf(30.0, 17.0)))
    }

    @Test
    fun `above 30 kmh a 15 kmh drop counts, 14 does not`() {
        assertEquals(1, hardBrakes(listOf(40.0, 25.0)))
        assertEquals(0, hardBrakes(listOf(40.0, 26.0)))
        assertEquals(0, hardBrakes(listOf(31.0, 17.0)))
    }

    @Test
    fun `ending at 6 kmh counts, ending at 5 kmh is a normal stop`() {
        assertEquals(1, hardBrakes(listOf(20.0, 6.0)))
        assertEquals(0, hardBrakes(listOf(20.0, 5.0)))
        assertEquals(0, hardBrakes(listOf(25.0, 0.0)))
    }

    @Test
    fun `position jitter while standing still is not a hard brake`() {
        // 신호 대기: 짧은 창 속도는 튀지만 평활 속도는 거의 0.
        assertEquals(0, hardBrakes(listOf(22.0, 7.0), smoothed = listOf(3.0, 3.0)))
        assertEquals(0, hardBrakes(listOf(22.0, 7.0), smoothed = listOf(null, null)))
        assertEquals(0, hardBrakes(listOf(22.0, 7.0), smoothed = listOf(9.9, 9.0)))
        assertEquals(1, hardBrakes(listOf(22.0, 7.0), smoothed = listOf(10.0, 9.0)))
    }

    @Test
    fun `drop spread over more than one second is not a hard brake`() {
        // 30→10을 2초에 걸쳐(초당 10km/h): 1초 창 안에서는 14에 못 미친다.
        assertEquals(0, hardBrakes(listOf(30.0, 25.0, 20.0, 15.0, 10.0), stepMs = 500))
    }

    @Test
    fun `normal stop at about 8 kmh per second is not counted`() {
        // 예전 기준(1초 8km/h)에서 정상 정지가 급제동으로 세지던 문제.
        assertEquals(0, hardBrakes(listOf(32.0, 30.0, 26.0, 22.0, 18.0, 14.0, 10.0, 6.0, 2.0, 0.0), stepMs = 500))
    }
}
