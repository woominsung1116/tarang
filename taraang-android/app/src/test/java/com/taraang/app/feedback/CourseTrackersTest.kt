package com.taraang.app.feedback

import com.taraang.app.core.LatLng
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.DataConfidence
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.FeedbackType
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.model.Skill
import com.taraang.app.domain.signal.NaviSnapshot
import com.taraang.app.domain.signal.SignalAvailability
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.domain.signal.TurnSignalState
import com.taraang.app.domain.signal.VehicleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * 순환 코스 회귀 테스트. 막대사탕 모양: 출발점 S에서 북쪽 교차로 J를 직진으로 지나 사각형을 돌고,
 * J로 돌아와 좌회전해 S 옆(맞은편 차로, 동쪽 8m)으로 끝난다. 같은 교차로를 두 번 지나고 끝이 출발점과 겹친다.
 */
class CourseTrackersTest {

    private fun at(northM: Double, eastM: Double): LatLng = START.north(northM).east(eastM)

    /** (북, 동) m. 돌아오는 길은 맞은편 차로라 동쪽 8m. */
    private val loopM = listOf(
        0.0 to 0.0, 200.0 to 0.0, 400.0 to 0.0, 400.0 to 200.0, 200.0 to 200.0, 200.0 to 8.0, 0.0 to 8.0,
    )
    private val loopAlongM = loopM.zipWithNext { a, b -> hypot(b.first - a.first, b.second - a.second) }.runningFold(0.0, Double::plus)

    /** 두 번째로 J를 지날 때의 좌회전. 진행 거리 992m. */
    private val leftAtJ = Maneuver(0, Skill.LEFT_TURN_SIGNALIZED, "테스트로", at(200.0, 8.0), loopAlongM[5], DataConfidence.VERIFIED)

    private fun loopCourse(maneuvers: List<Maneuver>) = Course(
        id = "L2-loop",
        level = 2,
        title = "순환 테스트",
        distanceM = loopAlongM.last().toInt(),
        origin = START,
        path = loopM.map { (n, e) -> at(n, e) },
        waypoints = emptyList(),
        maneuvers = maneuvers,
        skillCounts = maneuvers.groupingBy { it.skill }.eachCount(),
        warnings = emptyList(),
        source = CourseSource.BUNDLED,
    )

    /** 코스 선을 따라 [alongM] 지점의 (북, 동) m. */
    private fun pointAt(alongM: Double): Pair<Double, Double> {
        val i = loopAlongM.indexOfLast { it <= alongM }.coerceAtMost(loopM.size - 2)
        val t = ((alongM - loopAlongM[i]) / (loopAlongM[i + 1] - loopAlongM[i])).coerceIn(0.0, 1.0)
        val (n0, e0) = loopM[i]
        val (n1, e1) = loopM[i + 1]
        return (n0 + (n1 - n0) * t) to (e0 + (e1 - e0) * t)
    }

    @Test
    fun `loop course previews the left turn on the second pass, not when going straight through first`() {
        val engine = FeedbackEngine("loop", loopCourse(listOf(leftAtJ)), FeedbackSettings())
        val all = SignalAvailability(SignalKey.entries.associateWith { SignalStatus.AVAILABLE })
        val vehicle = VehicleSnapshot(turnSignal = TurnSignalState.OFF, brakePressed = false)
        val previewsAt = mutableListOf<Double>()
        var now = 10_000L
        var along = 0.0
        while (along < loopAlongM.last()) {
            now += 250
            along += 2.5 // 36km/h
            val (n, e) = pointAt(along)
            // 처음 직진 구간에서는 차가 동쪽으로 5m 치우쳐 맞은편(돌아오는) 차로가 더 가깝다.
            val drift = if (along < 200) 5.0 else 0.0
            val r = engine.tick(vehicle, NaviSnapshot(timestampMs = now, position = at(n, e + drift)), all, now)
            if (r.message?.type == FeedbackType.SKILL_PREVIEW) previewsAt += along
        }
        assertEquals("previews at $previewsAt", 1, previewsAt.size)
        val before = leftAtJ.distanceFromStartM - previewsAt.single()
        assertTrue("preview ${before}m before the turn", before in 50.0..150.0)
    }

    @Test
    fun `progress stays on the outbound leg when the return lane of the loop is closer`() {
        val progress = PathProgress(CoursePath(loopCourse(emptyList()).path))
        assertEquals(0.0, progress.update(at(0.0, 5.0))!!, 1.0)
        assertEquals(100.0, progress.update(at(100.0, 5.0))!!, 1.0)
        assertEquals(200.0, progress.update(at(200.0, 5.0))!!, 1.0)
    }

    @Test
    fun `progress never goes backwards`() {
        val progress = PathProgress(CoursePath(loopCourse(emptyList()).path))
        progress.update(at(150.0, 0.0))
        assertEquals(150.0, progress.update(at(120.0, 0.0))!!, 1.0)
    }

    @Test
    fun `progress rejoins at the first matching place ahead, not the nearest one further on`() {
        // 북쪽 1000m 갔다가 동쪽 30m 차로로 돌아오는 코스. 위치를 한참 놓친 뒤 500m 지점(돌아오는 차로에 더 가까움)에서 다시 잡힘.
        val out = (0..1000 step 100).map { at(it.toDouble(), 0.0) }
        val back = (1000 downTo 0 step 100).map { at(it.toDouble(), 30.0) }
        val path = CoursePath(out + back)
        val progress = PathProgress(path)
        progress.update(at(0.0, 0.0))
        assertEquals(500.0, progress.update(at(500.0, 25.0))!!, 1.0)
    }

    @Test
    fun `position farther than the off-course distance does not move progress`() {
        val progress = PathProgress(CoursePath(loopCourse(emptyList()).path))
        assertNull(progress.update(at(100.0, 100.0)))
        assertEquals(10.0, progress.update(at(10.0, 0.0))!!, 1.0)
    }

    @Test
    fun `tracker passes maneuvers by path progress and reports distance along the path`() {
        val path = CoursePath(loopCourse(emptyList()).path)
        val tracker = ManeuverTracker(listOf(leftAtJ), FeedbackConfig(), path)
        // 처음 J를 직진으로 지날 때(진행 200m)는 지점이 J라도 아직 792m 남았다.
        val first = tracker.update(at(200.0, 0.0), 200.0)!!
        assertEquals(0, first.maneuver.index)
        assertEquals(leftAtJ.distanceFromStartM - 200.0, first.distanceM, 1.0)
        assertEquals(100.0, tracker.update(at(200.0, 108.0), leftAtJ.distanceFromStartM - 100.0)!!.distanceM, 1.0)
        assertNull(tracker.update(at(170.0, 8.0), leftAtJ.distanceFromStartM + 30.0))
    }

    @Test
    fun `maneuver whose distance from start does not match the path falls back to straight-line reach`() {
        // distanceFromStartM 누락(0)인 옛 응답: 진행 거리로 넘기면 출발하자마자 지난 것으로 본다.
        val missing = leftAtJ.copy(distanceFromStartM = 0.0)
        val tracker = ManeuverTracker(listOf(missing), FeedbackConfig(), CoursePath(loopCourse(emptyList()).path))
        val far = tracker.update(at(100.0, 0.0), 100.0)!!
        assertEquals(0, far.maneuver.index)
        assertEquals(100.0, far.distanceM, 1.0)
        assertNull(tracker.update(at(190.0, 8.0), 990.0))
    }

    @Test
    fun `tracker can look past maneuvers that are not wanted`() {
        val right = maneuver(0, Skill.RIGHT_TURN, 270.0)
        val left = maneuver(1, Skill.LEFT_TURN_UNKNOWN, 285.0)
        val tracker = ManeuverTracker(listOf(right, left), FeedbackConfig(), CoursePath(testCourse().path))
        val next = tracker.update(START.north(150.0), 150.0) { it.skill != Skill.RIGHT_TURN }!!
        assertEquals(1, next.maneuver.index)
        assertEquals(135.0, next.distanceM, 1.0)
        assertNull(tracker.update(START.north(320.0), 320.0) { it.skill == Skill.U_TURN })
    }

    @Test
    fun `tracker without path progress uses straight-line reach`() {
        val tracker = ManeuverTracker(listOf(maneuver(0, Skill.RIGHT_TURN, 100.0), maneuver(1, Skill.RIGHT_TURN, 300.0)))
        assertEquals(0, tracker.update(START.north(50.0))!!.maneuver.index)
        assertEquals(1, tracker.update(START.north(80.0))!!.maneuver.index)
    }
}
