package com.taraang.app.feedback

import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.signal.NextTurn
import com.taraang.app.domain.signal.TurnKind
import com.taraang.app.domain.signal.TurnSignalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSignalJudgeTest {

    private val tracker = TurnTracker()
    private val judge = TurnSignalJudge()

    /** 다음 회전까지 거리를 [distances] 순으로 줄이며 판정 결과를 모은다. */
    private fun approach(kind: TurnKind, signal: TurnSignalState, distances: List<Double>): List<TurnSignalJudge.Judgment> =
        distances.mapIndexedNotNull { i, d ->
            judge.judge(tracker.update(NextTurn(kind, d), START.north(i * 5.0), i * 500L), signal)
        }

    @Test
    fun `right signal before right turn is OK once`() {
        val results = approach(TurnKind.RIGHT, TurnSignalState.RIGHT, listOf(80.0, 60.0, 40.0, 28.0, 20.0, 10.0))
        assertEquals(1, results.size)
        assertEquals(DriveEventType.TURN_SIGNAL_OK, results.single().type)
    }

    @Test
    fun `signal off at 40m is MISSED`() {
        val results = approach(TurnKind.LEFT, TurnSignalState.OFF, listOf(80.0, 40.0, 29.0, 15.0))
        assertEquals(DriveEventType.TURN_SIGNAL_MISSED, results.single().type)
    }

    @Test
    fun `judged at 40m from the turn node, not at 41m`() {
        val turn = { d: Double, i: Int -> tracker.update(NextTurn(TurnKind.RIGHT, d), START.north(i * 5.0), i * 500L) }
        assertNull(judge.judge(turn(80.0, 0), TurnSignalState.OFF))
        assertNull(judge.judge(turn(41.0, 1), TurnSignalState.OFF))
        // 41m에서 꺼져 있어도 40m에서 켜져 있으면 법 기준(교차로 가장자리 30m 전) 안에 넣은 것이다.
        assertEquals(DriveEventType.TURN_SIGNAL_OK, judge.judge(turn(40.0, 2), TurnSignalState.RIGHT)!!.type)
    }

    @Test
    fun `signal switched on only after 40m is MISSED`() {
        val turn = { d: Double, i: Int -> tracker.update(NextTurn(TurnKind.LEFT, d), START.north(i * 5.0), i * 500L) }
        judge.judge(turn(80.0, 0), TurnSignalState.OFF)
        assertEquals(DriveEventType.TURN_SIGNAL_MISSED, judge.judge(turn(39.0, 1), TurnSignalState.OFF)!!.type)
        assertNull(judge.judge(turn(30.0, 2), TurnSignalState.LEFT))
    }

    @Test
    fun `opposite signal is MISSED`() {
        val results = approach(TurnKind.RIGHT, TurnSignalState.LEFT, listOf(80.0, 29.0))
        assertEquals(DriveEventType.TURN_SIGNAL_MISSED, results.single().type)
    }

    @Test
    fun `u-turn expects left signal`() {
        val results = approach(TurnKind.UTURN, TurnSignalState.LEFT, listOf(80.0, 29.0))
        assertEquals(DriveEventType.TURN_SIGNAL_OK, results.single().type)
    }

    @Test
    fun `turn first seen inside 40m is not judged`() {
        assertTrue(approach(TurnKind.RIGHT, TurnSignalState.OFF, listOf(25.0, 15.0, 5.0)).isEmpty())
    }

    @Test
    fun `turn 39m after the previous one is skipped, 40m is judged`() {
        approach(TurnKind.RIGHT, TurnSignalState.RIGHT, listOf(80.0, 40.0, 10.0, 2.0))
        // TBT가 다음 회전으로 넘어갈 때 처음 보이는 거리가 앞 회전과의 간격이다.
        assertTrue(approach(TurnKind.LEFT, TurnSignalState.OFF, listOf(39.0, 20.0, 5.0)).isEmpty())
        val judged = approach(TurnKind.RIGHT, TurnSignalState.OFF, listOf(40.0, 20.0, 5.0))
        assertEquals(DriveEventType.TURN_SIGNAL_MISSED, judged.single().type)
    }

    @Test
    fun `roundabout and straight are not judged`() {
        assertTrue(approach(TurnKind.ROUNDABOUT, TurnSignalState.OFF, listOf(80.0, 20.0)).isEmpty())
        assertTrue(approach(TurnKind.STRAIGHT, TurnSignalState.OFF, listOf(80.0, 20.0)).isEmpty())
    }

    @Test
    fun `roundabout exit is not judged with any signal`() {
        // 진입 안내 뒤 진출 안내(둘 다 ROUNDABOUT, 진출은 TURN_EXIT_ROUNDABOUT 매핑)가 이어진다.
        approach(TurnKind.ROUNDABOUT, TurnSignalState.OFF, listOf(80.0, 40.0, 5.0))
        assertTrue(approach(TurnKind.ROUNDABOUT, TurnSignalState.OFF, listOf(120.0, 60.0, 30.0, 5.0)).isEmpty())
        assertTrue(approach(TurnKind.ROUNDABOUT, TurnSignalState.RIGHT, listOf(120.0, 60.0, 30.0, 5.0)).isEmpty())
    }

    @Test
    fun `unknown signal is not judged`() {
        val turn = tracker.update(NextTurn(TurnKind.RIGHT, 80.0), START, 0)
        assertNull(judge.judge(turn, null))
    }

    @Test
    fun `consecutive turns get separate judgments`() {
        val first = approach(TurnKind.RIGHT, TurnSignalState.RIGHT, listOf(80.0, 25.0, 5.0))
        // TBT가 다음 회전으로 넘어가며 거리가 다시 커진다.
        val second = approach(TurnKind.RIGHT, TurnSignalState.OFF, listOf(200.0, 100.0, 25.0))
        assertEquals(DriveEventType.TURN_SIGNAL_OK, first.single().type)
        assertEquals(DriveEventType.TURN_SIGNAL_MISSED, second.single().type)
    }

    @Test
    fun `tracker assigns new id when distance jumps or kind changes`() {
        val a = tracker.update(NextTurn(TurnKind.RIGHT, 100.0), START, 0)!!
        val b = tracker.update(NextTurn(TurnKind.RIGHT, 90.0), START, 500)!!
        val c = tracker.update(NextTurn(TurnKind.RIGHT, 300.0), START, 1_000)!!
        val d = tracker.update(NextTurn(TurnKind.LEFT, 290.0), START, 1_500)!!
        assertEquals(a.id, b.id)
        assertNotEquals(b.id, c.id)
        assertNotEquals(c.id, d.id)
        assertEquals(100.0, b.maxSeenM, 0.0)
    }

    @Test
    fun `near turn is true within 20m before and after the turn`() {
        tracker.update(NextTurn(TurnKind.RIGHT, 50.0), START, 0)
        assertFalse(tracker.nearTurn)
        tracker.update(NextTurn(TurnKind.RIGHT, 15.0), START.north(35.0), 500)
        assertTrue(tracker.nearTurn)
        // 회전을 지나 다음 안내로 넘어감: 지난 지점에서 20m 안이면 계속 침묵
        tracker.update(NextTurn(TurnKind.LEFT, 400.0), START.north(50.0), 1_000)
        assertTrue(tracker.nearTurn)
        tracker.update(NextTurn(TurnKind.LEFT, 370.0), START.north(80.0), 1_500)
        assertFalse(tracker.nearTurn)
    }
}
