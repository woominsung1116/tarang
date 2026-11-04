package com.taraang.app.feedback

import com.taraang.app.core.Geo
import com.taraang.app.core.LatLng
import com.taraang.app.domain.signal.NextTurn
import com.taraang.app.domain.signal.TurnKind

/** TBT의 "다음 회전"을 회전 하나하나로 구분한 것. [id]가 같으면 같은 회전이다. */
data class TrackedTurn(val id: Int, val kind: TurnKind, val distanceM: Double, val maxSeenM: Double)

val TurnKind.isManeuver: Boolean
    get() = this == TurnKind.LEFT || this == TurnKind.RIGHT || this == TurnKind.UTURN || this == TurnKind.ROUNDABOUT

/**
 * TBT는 "다음 회전까지 거리"만 준다. 거리가 크게 늘거나 종류가 바뀌면 앞 회전을 지난 것으로 본다.
 * 지난 지점을 기억해 회전 직후(±[FeedbackConfig.silentNearTurnM])에도 말하지 않게 한다.
 */
class TurnTracker(private val config: FeedbackConfig = FeedbackConfig()) {

    private var current: TrackedTurn? = null
    private var nextId = 0
    private var passedAt: LatLng? = null
    private var passedAtMs: Long? = null

    /** 회전 지점 앞뒤 [FeedbackConfig.silentNearTurnM] 안이면 true. */
    var nearTurn: Boolean = false
        private set

    fun update(nextTurn: NextTurn?, position: LatLng?, nowMs: Long): TrackedTurn? {
        val prev = current
        val next = when {
            nextTurn == null -> null
            prev == null || prev.kind != nextTurn.kind || nextTurn.distanceM > prev.distanceM + config.newTurnDistanceJumpM ->
                TrackedTurn(nextId++, nextTurn.kind, nextTurn.distanceM, nextTurn.distanceM)
            else -> prev.copy(distanceM = nextTurn.distanceM, maxSeenM = maxOf(prev.maxSeenM, nextTurn.distanceM))
        }
        // 가까이 있던 회전이 사라지거나 바뀌었으면 방금 그 회전을 지난 것이다.
        if (prev != null && prev.kind.isManeuver && prev.id != next?.id && prev.distanceM <= config.turnSignalJudgeDistanceM) {
            passedAt = position
            passedAtMs = nowMs
        }
        current = next
        nearTurn = isApproaching(next) || isJustPassed(position, nowMs)
        return next
    }

    fun reset() {
        current = null
        passedAt = null
        passedAtMs = null
        nearTurn = false
    }

    private fun isApproaching(turn: TrackedTurn?): Boolean =
        turn != null && turn.kind.isManeuver && turn.distanceM <= config.silentNearTurnM

    private fun isJustPassed(position: LatLng?, nowMs: Long): Boolean {
        val at = passedAt
        if (at != null && position != null) return Geo.distanceM(at, position) <= config.silentNearTurnM
        // 위치를 모르면 시간으로 대신한다(시속 25km로 약 20m).
        val atMs = passedAtMs ?: return false
        return nowMs - atMs <= PASSED_FALLBACK_MS
    }

    private companion object {
        const val PASSED_FALLBACK_MS = 3_000L
    }
}
