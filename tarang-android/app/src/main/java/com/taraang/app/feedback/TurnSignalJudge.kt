package com.taraang.app.feedback

import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.signal.TurnKind
import com.taraang.app.domain.signal.TurnSignalState

/**
 * 기록용 판정: 회전 지점 [FeedbackConfig.turnSignalJudgeDistanceM](40m) 앞에서 방향지시등이 맞는 쪽이면 OK, 아니면 MISSED.
 * 회전 하나에 한 번만 판정한다. TBT는 앞 회전을 지나면 바로 다음 회전을 보여 주므로, 처음 보인 거리가
 * [FeedbackConfig.turnSignalMinSpacingM]보다 짧으면 앞 회전과 붙은 회전(또는 출발 직후)이라 판정하지 않는다.
 * 회전교차로 진출은 법상 "그 행위를 하려는 때" 신호하므로 거리 기준을 쓰지 않는다([expectedSignal]이 null).
 */
class TurnSignalJudge(private val config: FeedbackConfig = FeedbackConfig()) {

    data class Judgment(val type: DriveEventType, val turnKind: TurnKind, val signal: TurnSignalState)

    private var lastJudgedId = -1

    fun judge(turn: TrackedTurn?, signal: TurnSignalState?): Judgment? {
        if (turn == null || signal == null || turn.id == lastJudgedId) return null
        val expected = expectedSignal(turn.kind) ?: return null
        if (turn.distanceM > config.turnSignalJudgeDistanceM || turn.maxSeenM < config.turnSignalMinSpacingM) return null
        lastJudgedId = turn.id
        val type = if (signal == expected) DriveEventType.TURN_SIGNAL_OK else DriveEventType.TURN_SIGNAL_MISSED
        return Judgment(type, turn.kind, signal)
    }

    /** 이 회전은 판정하지 않고 넘긴다(코스 이탈 중 등). */
    fun skip(turn: TrackedTurn?) {
        if (turn != null) lastJudgedId = turn.id
    }

    companion object {
        /** 유턴은 좌측 방향지시등. 회전교차로(진입·진출)는 30m 규칙 대상이 아니라 판정하지 않는다. */
        fun expectedSignal(kind: TurnKind): TurnSignalState? = when (kind) {
            TurnKind.LEFT, TurnKind.UTURN -> TurnSignalState.LEFT
            TurnKind.RIGHT -> TurnSignalState.RIGHT
            else -> null
        }
    }
}
