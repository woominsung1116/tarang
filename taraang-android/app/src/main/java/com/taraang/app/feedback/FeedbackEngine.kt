package com.taraang.app.feedback

import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.DriveEvent
import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.model.FeedbackMessage
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.signal.NaviSnapshot
import com.taraang.app.domain.signal.SignalAvailability
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.VehicleSnapshot
import com.taraang.app.feedback.rules.FeedbackRule
import com.taraang.app.feedback.rules.HardBrakeRule
import com.taraang.app.feedback.rules.NewWarningRule
import com.taraang.app.feedback.rules.RuleEvent
import com.taraang.app.feedback.rules.SchoolZoneAheadRule
import com.taraang.app.feedback.rules.SchoolZoneSpeedRule
import com.taraang.app.feedback.rules.SkillPreviewRule
import com.taraang.app.feedback.rules.SpeedLimitRule
import com.taraang.app.feedback.rules.TickContext
import com.taraang.app.feedback.rules.TurnSignalRule

data class TickResult(
    val message: FeedbackMessage?,
    val events: List<DriveEvent>,
    val speedKmh: Double?,
)

/**
 * 주행 1회의 피드백 판정. 신호 스냅샷을 틱마다 받아 말할 문장(최대 1개)과 기록할 이벤트를 돌려준다.
 * Android 의존성이 없고 시간은 인자로만 받으므로 리플레이·단위 테스트에서 그대로 돈다.
 * 스레드 안전하지 않다. 한 코루틴에서만 부른다.
 */
class FeedbackEngine(
    private val sessionId: String,
    course: Course,
    private val settings: FeedbackSettings,
    private val config: FeedbackConfig = FeedbackConfig(),
) {
    private val speedEstimator = SpeedEstimator(config)
    private val turnTracker = TurnTracker(config)
    private val coursePath = CoursePath(course.path)
    private val pathProgress = PathProgress(coursePath, config)
    private val maneuverTracker = ManeuverTracker(course.maneuvers, config, coursePath)
    private val practiceSkills = SkillPreviewRule.practiceSkills(course.maneuvers)
    private val isPractice: (Maneuver) -> Boolean = { it.skill in practiceSkills }
    private val offCourseDetector = OffCourseDetector(coursePath, config)
    private val arbiter = FeedbackArbiter(config)

    private val rules: List<FeedbackRule> = listOf(
        NewWarningRule(),
        SchoolZoneAheadRule(),
        SchoolZoneSpeedRule(config),
        SpeedLimitRule(config),
        TurnSignalRule(config),
        SkillPreviewRule(config),
        HardBrakeRule(config),
    )
    private val activeRules = mutableSetOf<FeedbackRule>()
    private val eventBuffer = ArrayList<RuleEvent>()
    private val candidates = ArrayList<FeedbackCandidate>(rules.size)
    private val candidateRules = ArrayList<FeedbackRule>(rules.size)

    fun tick(vehicle: VehicleSnapshot, navi: NaviSnapshot, availability: SignalAvailability, nowMs: Long): TickResult {
        val position = navi.position.takeIf { availability.isAvailable(SignalKey.POSITION) }
        val hasFix = position != null && navi.timestampMs > 0
        if (hasFix) speedEstimator.update(position, navi.timestampMs)
        val speed = if (hasFix) speedEstimator.current(nowMs) else null
        val responsiveSpeed = if (hasFix) speedEstimator.currentShort(nowMs) else null
        val nextTurn = navi.nextTurn.takeIf { availability.isAvailable(SignalKey.NEXT_TURN) }
        val turn = turnTracker.update(nextTurn, position, nowMs)
        val cautions = if (availability.isAvailable(SignalKey.CAUTIONS)) navi.cautions else emptyList()

        // 틱은 초당 4번 돈다. 버퍼를 재사용해 할당을 줄인다.
        val events = eventBuffer.apply { clear() }
        if (offCourseDetector.update(position, nowMs)) events += RuleEvent(DriveEventType.OFF_COURSE, "코스에서 벗어남")

        val ctx = TickContext(
            nowMs = nowMs,
            vehicle = vehicle,
            // 보호구역 과속은 제한속도를 선택적으로 쓴다. 신호가 없으면 값을 믿지 않고 기본값으로 가게 지운다.
            navi = if (availability.isAvailable(SignalKey.SPEED_LIMIT)) navi else navi.copy(speedLimitKmh = null),
            speedKmh = speed,
            responsiveSpeedKmh = responsiveSpeed,
            turn = turn,
            schoolZone = SchoolZone.evaluate(cautions, config),
            nextPracticeManeuver = maneuverTracker.update(position, pathProgress.update(position), isPractice),
            offCourse = offCourseDetector.offCourse,
        )

        candidates.clear()
        candidateRules.clear()
        for (rule in rules) {
            if (!rule.requires.all(availability::isAvailable)) {
                if (activeRules.remove(rule)) rule.reset()
                continue
            }
            activeRules += rule
            val candidate = rule.evaluate(ctx, events) ?: continue
            if (!settings.isEnabled(rule.type)) continue
            candidates += candidate
            candidateRules += rule
        }
        val chosen = arbiter.select(candidates, nowMs, turnTracker.nearTurn)
        if (chosen != null) candidateRules[candidates.indexOf(chosen)].onSpoken(ctx)

        val drafted = if (events.isEmpty()) {
            emptyList()
        } else {
            val maneuverIndex = maneuverTracker.nearestIndex(position)
            events.map { DriveEvent(sessionId, it.type, nowMs, position, maneuverIndex, it.detail) }
        }
        return TickResult(
            message = chosen?.let { FeedbackMessage(it.type, it.text, nowMs) },
            events = drafted,
            speedKmh = speed,
        )
    }

    /** 코스 경로를 따라 진행한 거리(단계 진행 판정용). 코스 선 위에 한 번도 못 올라왔으면 null. */
    val drivenAlongCourseM: Double? get() = pathProgress.progressM

    /** 이번 주행에서 말한 횟수. */
    val spokenCount: Int get() = arbiter.spokenCount
}
