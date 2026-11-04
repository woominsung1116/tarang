package com.taraang.app.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEstimatorTest {

    private val estimator = SpeedEstimator()

    /** [kmh]로 [ms] 동안 북쪽으로 달리며 [tickMs]마다 위치를 넣는다. */
    private fun drive(fromMs: Long, start: com.taraang.app.core.LatLng, kmh: Double, ms: Long, tickMs: Long = 500): Pair<Long, com.taraang.app.core.LatLng> {
        var t = fromMs
        var p = start
        while (t < fromMs + ms) {
            t += tickMs
            p = p.north(kmh / 3.6 * tickMs / 1000.0)
            estimator.update(p, t)
        }
        return t to p
    }

    @Test
    fun `constant speed is estimated within 1 kmh`() {
        estimator.update(START, 0)
        val (t, _) = drive(0, START, 36.0, 5_000)
        assertEquals(36.0, estimator.current(t)!!, 1.0)
    }

    @Test
    fun `short-window speed follows a hard brake that the smoothed speed lags`() {
        estimator.update(START, 0)
        var (t, p) = drive(0, START, 50.0, 4_000)
        // 1초에 50→35(초당 15km/h) 감속
        val before = estimator.currentShort(t)!!
        listOf(46.25, 42.5, 38.75, 35.0).forEach { kmh -> t += 250; p = p.north(kmh / 3.6 * 0.25); estimator.update(p, t) }
        assertEquals(50.0, before, 0.5)
        // 마지막 500ms 평균(38.75→35 구간)
        assertEquals(36.9, estimator.currentShort(t)!!, 1.0)
        assertTrue("smoothed ${estimator.current(t)}", estimator.current(t)!! > 40.0)
    }

    @Test
    fun `no estimate until window half filled`() {
        estimator.update(START, 0)
        estimator.update(START.north(5.0), 500)
        assertNull(estimator.current(500))
    }

    @Test
    fun `stationary jitter reads as zero`() {
        var t = 0L
        repeat(20) { i ->
            t += 500
            val jitter = if (i % 2 == 0) 1.0 else -1.0
            estimator.update(START.north(jitter), t)
        }
        assertEquals(0.0, estimator.current(t)!!, 0.0)
    }

    @Test
    fun `single gps jump is ignored`() {
        estimator.update(START, 0)
        val (t, p) = drive(0, START, 36.0, 4_000)
        estimator.update(p.north(1_000.0), t + 500)
        val (t2, _) = drive(t + 500, p.north(5.0), 36.0, 1_000)
        assertEquals(36.0, estimator.current(t2)!!, 3.0)
    }

    @Test
    fun `repeated jumps are accepted as real relocation`() {
        estimator.update(START, 0)
        val (t, _) = drive(0, START, 36.0, 3_000)
        val far = START.north(10_000.0)
        estimator.update(far, t + 500)
        estimator.update(far, t + 1_000)
        estimator.update(far, t + 1_500)
        // 리셋 후 새 위치에서 정지 상태로 다시 쌓인다.
        estimator.update(far, t + 2_000)
        estimator.update(far, t + 2_500)
        assertEquals(0.0, estimator.current(t + 2_500)!!, 0.0)
    }

    @Test
    fun `stale position gives no estimate`() {
        estimator.update(START, 0)
        val (t, _) = drive(0, START, 36.0, 3_000)
        assertNull(estimator.current(t + 6_000))
    }

    @Test
    fun `duplicate or older timestamps are ignored`() {
        estimator.update(START, 0)
        val (t, p) = drive(0, START, 36.0, 3_000)
        estimator.update(p.north(500.0), t)
        estimator.update(p.north(500.0), t - 100)
        assertEquals(36.0, estimator.current(t)!!, 1.0)
    }

    @Test
    fun `slowing down is tracked`() {
        estimator.update(START, 0)
        val (t, p) = drive(0, START, 50.0, 4_000)
        val (t2, _) = drive(t, p, 20.0, 4_000)
        assertEquals(20.0, estimator.current(t2)!!, 2.0)
    }
}
