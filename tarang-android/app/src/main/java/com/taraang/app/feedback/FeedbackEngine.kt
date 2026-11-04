package com.taraang.app.feedback

import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.FAR_FROM_START_M
import com.taraang.app.domain.model.farFromStartM
import com.taraang.app.domain.model.DriveEvent
import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.model.FeedbackMessage
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.signal.NaviSnapshot
import com.taraang.app.domain.signal.SignalAvailability
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.VehicleSnapshot
import com.taraang.app.domain.signal.WarningType
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
    /** 주행 중 새로 켜졌고 아직 켜져 있는 경고. 화면 경고 표시의 단일 출처(비면 경고 표시를 내린다). */
    val newWarnings: Set<WarningType> = emptySet(),
    /** 코스 선에 한 번이라도 올라왔는가. 그 전에는 코스 이탈을 세지 않는다. */
    val joinedCourse: Boolean = false,
    /** 아직 코스에 오르지 않았고 출발점에서 [FAR_FROM_START_M]보다 멀면 출발점까지 직선거리. 아니면 null. */
    val farFromStartM: Double? = null,
)

/**
 * 주행 1회의 피드백 판정. 신호 스냅샷을 틱마다 받아 말할 문장(최대 1개)과 기록할 이벤트를 돌려준다.
 * Android 의존성이 없고 시간은 인자로만 받으므로 리플레이·단위 테스트에서 그대로 돈다.
 * 스레드 안전하지 않다. 한 코루틴에서만 부른다.
 */
class FeedbackEngine(
    private val sessionId: String,
    private val course: Course,
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
    private var previousWarnings: Set<WarningType>? = null
    private var newWarnings: Set<WarningType> = emptySet()
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
        trackNewWarnings(vehicle, availability)
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
            newWarnings = newWarnings,
            joinedCourse = offCourseDetector.joined,
            farFromStartM = if (offCourseDetector.joined) null else course.farFromStartM(position),
        )
    }

    /**
     * NewWarningRule과 같은 기준: 직전 틱 대비 새로 켜진 경고를 더하고, 꺼진 경고는 뺀다. 첫 값은 기준선이다.
     * 경고 신호가 끊기면 모르는 상태라 비우고, 다시 붙으면 그때 값을 새 기준선으로 삼는다.
     */
    private fun trackNewWarnings(vehicle: VehicleSnapshot, availability: SignalAvailability) {
        if (!availability.isAvailable(SignalKey.WARNINGS)) {
            previousWarnings = null
            newWarnings = emptySet()
            return
        }
        val active = vehicle.activeWarnings
        val previous = previousWarnings
        previousWarnings = active
        if (previous == null) return
        val next = (newWarnings + (active - previous)).intersect(active)
        if (next != newWarnings) newWarnings = next
    }

    /**
     * 코스 경로를 따라 진행한 거리(단계 진행·"일부만 주행" 판정용). 코스 선이 있는데 한 번도 못 올라왔으면 0
     * (출발점에서 먼 곳에서 켠 주행), 코스 선이 없으면 null(판단 불가).
     */
    val drivenAlongCourseM: Double? get() = pathProgress.progressM ?: 0.0.takeIf { coursePath.isUsable }

    /** 이번 주행에서 말한 횟수. */
    val spokenCount: Int get() = arbiter.spokenCount
}
