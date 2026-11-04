package com.taraang.app.feedback

import com.taraang.app.domain.model.FeedbackType

/** 규칙이 "지금 말할 만하다"고 낸 후보. 말하지 못하면 다음 틱에 조건이 남아 있을 때 다시 나온다. */
data class FeedbackCandidate(val type: FeedbackType, val text: String)

/**
 * 발화 제한(ARCHITECTURE.md 5장): 한 번에 하나, 최소 간격, 주행당 상한, 같은 타입 쿨다운, 회전 중 침묵.
 * 우선순위 1(새 경고)은 간격·상한·쿨다운을 건너뛰지만 회전 중 침묵은 지킨다(몇 초 늦어도 조작 중 말하지 않는다).
 */
class FeedbackArbiter(private val config: FeedbackConfig = FeedbackConfig()) {

    private var lastSpokenAtMs: Long? = null
    private val lastSpokenByType = mutableMapOf<FeedbackType, Long>()

    var spokenCount: Int = 0
        private set

    /** 말할 후보 하나를 고르고 발화 기록을 남긴다. 고를 게 없으면 null. */
    fun select(candidates: List<FeedbackCandidate>, nowMs: Long, nearTurn: Boolean): FeedbackCandidate? {
        if (nearTurn) return null
        var chosen: FeedbackCandidate? = null
        for (c in candidates) {
            if ((chosen == null || c.type.priority < chosen.type.priority) && isAllowed(c.type, nowMs)) chosen = c
        }
        if (chosen == null) return null
        lastSpokenAtMs = nowMs
        lastSpokenByType[chosen.type] = nowMs
        spokenCount++
        return chosen
    }

    private fun isAllowed(type: FeedbackType, nowMs: Long): Boolean {
        if (type.priority == URGENT_PRIORITY) return true
        if (spokenCount >= config.maxMessagesPerDrive) return false
        val last = lastSpokenAtMs
        if (last != null && nowMs - last < config.minGapMs) return false
        val lastSame = lastSpokenByType[type]
        return lastSame == null || nowMs - lastSame >= config.sameTypeCooldownMs
    }

    private companion object {
        const val URGENT_PRIORITY = 1
    }
}
