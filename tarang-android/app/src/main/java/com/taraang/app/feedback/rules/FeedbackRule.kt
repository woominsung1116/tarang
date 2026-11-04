package com.taraang.app.feedback.rules

import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.model.FeedbackType
import com.taraang.app.domain.signal.NaviSnapshot
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.VehicleSnapshot
import com.taraang.app.feedback.FeedbackCandidate
import com.taraang.app.feedback.ManeuverProgress
import com.taraang.app.feedback.SchoolZoneState
import com.taraang.app.feedback.TrackedTurn

/** 한 틱에 규칙이 보는 값. 엔진이 추적기들로 미리 계산해 넘긴다. */
data class TickContext(
    val nowMs: Long,
    val vehicle: VehicleSnapshot,
    val navi: NaviSnapshot,
    val speedKmh: Double?,
    /** 평활하지 않은 짧은 창 속도. 급제동처럼 1초 감속을 재는 규칙만 쓴다. */
    val responsiveSpeedKmh: Double?,
    val turn: TrackedTurn?,
    val schoolZone: SchoolZoneState,
    /** 아직 안 지난 첫 "연습 기술" 지점([SkillPreviewRule.practiceSkills]). 연습 대상이 아닌 지점은 건너뛴다. */
    val nextPracticeManeuver: ManeuverProgress?,
    val offCourse: Boolean,
)

/** 기록할 주행 이벤트. 세션·위치·코스 지점은 엔진이 채운다. */
data class RuleEvent(val type: DriveEventType, val detail: String)

/**
 * 피드백 규칙 하나. 입력 신호가 하나라도 AVAILABLE이 아니면 엔진이 [evaluate]를 부르지 않고 [reset]한다.
 * 기록([events])은 사용자가 음성을 꺼도 남기고, 후보는 설정이 켜져 있을 때만 쓴다.
 */
interface FeedbackRule {
    val type: FeedbackType
    val requires: Set<SignalKey>

    fun evaluate(ctx: TickContext, events: MutableList<RuleEvent>): FeedbackCandidate?

    /** 이 규칙의 후보가 실제로 발화됐다. */
    fun onSpoken(ctx: TickContext)

    /** 입력 신호가 끊겼다. 지속 시간 같은 상태를 버린다. */
    fun reset()
}
