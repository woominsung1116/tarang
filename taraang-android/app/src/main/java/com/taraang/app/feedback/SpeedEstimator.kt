package com.taraang.app.feedback

import com.taraang.app.core.Geo
import com.taraang.app.core.LatLng

/**
 * 위치 변화로 차속을 추정한다. Pleos SDK에 차속 API가 없어서다.
 *
 * - 창(window) 양 끝 두 점의 직선거리/시간으로 계산해 정차 중 위치 떨림이 누적되지 않게 한다.
 * - 물리적으로 불가능한 순간 이동은 GPS 튐으로 버리되, 연속되면 실제 이동으로 보고 다시 시작한다.
 * - 저속은 0으로 내리고 지수 평활로 흔들림을 줄인다.
 */
class SpeedEstimator(private val config: FeedbackConfig = FeedbackConfig()) {

    private data class Sample(val position: LatLng, val timestampMs: Long)

    private val samples = ArrayDeque<Sample>()
    private var smoothedKmh: Double? = null
    private var shortKmh: Double? = null
    private var consecutiveJumps = 0

    /** 새 위치를 넣고 현재 추정값을 돌려준다. 같은 시각의 중복 입력은 무시한다. */
    fun update(position: LatLng?, timestampMs: Long): Double? {
        if (position == null) return current(timestampMs)
        val last = samples.lastOrNull()
        if (last != null && timestampMs <= last.timestampMs) return current(timestampMs)

        if (last != null && isJump(last, position, timestampMs)) {
            consecutiveJumps++
            if (consecutiveJumps < config.jumpResetCount) return current(timestampMs)
            reset()
        }
        consecutiveJumps = 0
        samples.addLast(Sample(position, timestampMs))
        val cutoff = timestampMs - config.speedWindowMs
        while (samples.size > 2 && samples[1].timestampMs <= cutoff) samples.removeFirst()

        rawKmh()?.let { raw ->
            val prev = smoothedKmh
            val next = if (prev == null) raw else config.speedSmoothingAlpha * raw + (1 - config.speedSmoothingAlpha) * prev
            smoothedKmh = if (next < config.stationaryBelowKmh) 0.0 else next
        }
        shortKmh = rawKmh(config.hardBrakeSpeedWindowMs) ?: shortKmh
        return current(timestampMs)
    }

    /** 마지막 위치가 오래됐으면 모른다(null). 모르는 값으로 판정하지 않는다. */
    fun current(nowMs: Long): Double? {
        val last = samples.lastOrNull() ?: return null
        if (nowMs - last.timestampMs > config.speedStaleMs) return null
        return smoothedKmh
    }

    /**
     * 평활하지 않은 짧은 창([FeedbackConfig.hardBrakeSpeedWindowMs]) 속도. 1초 감속 판정용이다.
     * 평활한 [current]는 1.5초 평균과 지수 평활 때문에 1초 감속이 30~40% 깎여 급제동 기준에 닿지 않는다.
     */
    fun currentShort(nowMs: Long): Double? {
        val last = samples.lastOrNull() ?: return null
        if (nowMs - last.timestampMs > config.speedStaleMs) return null
        return shortKmh
    }

    fun reset() {
        samples.clear()
        smoothedKmh = null
        shortKmh = null
        consecutiveJumps = 0
    }

    private fun isJump(last: Sample, position: LatLng, timestampMs: Long): Boolean {
        val dtS = (timestampMs - last.timestampMs) / 1000.0
        return Geo.distanceM(last.position, position) / dtS * MS_TO_KMH > config.maxPlausibleKmh
    }

    private fun rawKmh(): Double? {
        if (samples.size < 2) return null
        val dtMs = samples.last().timestampMs - samples.first().timestampMs
        // 창의 절반도 안 쌓였으면 한 구간 오차가 너무 크다.
        if (dtMs < config.speedWindowMs / 2) return null
        return kmhBetween(samples.first(), samples.last())
    }

    /** 마지막 점과, 그보다 [minSpanMs] 이상 앞선 점 중 가장 최근 점 사이 속도. */
    private fun rawKmh(minSpanMs: Long): Double? {
        val last = samples.lastOrNull() ?: return null
        for (i in samples.size - 2 downTo 0) {
            if (last.timestampMs - samples[i].timestampMs >= minSpanMs) return kmhBetween(samples[i], last)
        }
        return null
    }

    private fun kmhBetween(first: Sample, last: Sample): Double {
        val dtMs = last.timestampMs - first.timestampMs
        val raw = Geo.distanceM(first.position, last.position) / (dtMs / 1000.0) * MS_TO_KMH
        return if (raw < config.stationaryBelowKmh) 0.0 else raw
    }

    private companion object {
        const val MS_TO_KMH = 3.6
    }
}
