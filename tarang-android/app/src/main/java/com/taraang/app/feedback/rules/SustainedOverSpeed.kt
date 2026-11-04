package com.taraang.app.feedback.rules

/**
 * "임계값 초과가 T 동안 지속" 판정. 경계에서 흔들릴 때 같은 초과를 여러 번 세지 않도록
 * 임계값보다 [hysteresis]만큼 내려가야 다음 초과로 본다.
 */
internal class SustainedOverSpeed(private val sustainMs: Long, private val hysteresis: Double) {

    private var overSinceMs: Long? = null

    /** 지금 초과 상태가 확정된 구간 안에 있는가. */
    var inEpisode: Boolean = false
        private set

    /** 이번 초과 구간이 막 확정되면 true(한 구간에 한 번). */
    fun update(speedKmh: Double?, thresholdKmh: Double?, nowMs: Long): Boolean {
        if (speedKmh == null || thresholdKmh == null) {
            reset()
            return false
        }
        if (speedKmh <= thresholdKmh) {
            overSinceMs = null
            if (speedKmh <= thresholdKmh - hysteresis) inEpisode = false
            return false
        }
        val since = overSinceMs ?: nowMs.also { overSinceMs = it }
        if (!inEpisode && nowMs - since >= sustainMs) {
            inEpisode = true
            return true
        }
        return false
    }

    fun reset() {
        overSinceMs = null
        inEpisode = false
    }
}
