package com.taraang.app.feedback.rules

import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.model.FeedbackType
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.model.Skill
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.WarningType
import com.taraang.app.feedback.FeedbackCandidate
import com.taraang.app.feedback.FeedbackConfig
import com.taraang.app.feedback.FeedbackMessages
import com.taraang.app.feedback.TurnSignalJudge

/** 우선순위 1. 출발 시점 경고는 기준선이고, 그 뒤 새로 켜진 경고만 알린다. 아직 말하지 않은 경고는 한 문장으로 묶는다. */
class NewWarningRule : FeedbackRule {
    override val type = FeedbackType.NEW_WARNING
    override val requires = setOf(SignalKey.WARNINGS)

    private var previous: Set<WarningType>? = null
    private val pending = LinkedHashSet<WarningType>()

    override fun evaluate(ctx: TickContext, events: MutableList<RuleEvent>): FeedbackCandidate? {
        val active = ctx.vehicle.activeWarnings
        val prev = previous
        previous = active
        if (prev != null) {
            (active - prev).sortedBy { it.ordinal }.forEach { warning ->
                events += RuleEvent(DriveEventType.NEW_WARNING, warning.name)
                pending += warning
            }
        }
        // 말하기 전에 꺼진 경고는 알리지 않는다.
        pending.retainAll(active)
        if (pending.isEmpty()) return null
        return FeedbackCandidate(type, FeedbackMessages.newWarnings(pending.toList()))
    }

    // evaluate와 같은 틱에 불리므로 pending은 방금 말한 목록 그대로다.
    override fun onSpoken(ctx: TickContext) {
        pending.clear()
    }

    override fun reset() {
        previous = null
        pending.clear()
    }
}

/** 우선순위 2. 보호구역 [FeedbackConfig.schoolZoneAheadM] 앞에서 한 번 예고한다. */
class SchoolZoneAheadRule : FeedbackRule {
    override val type = FeedbackType.SCHOOL_ZONE_AHEAD
    override val requires = setOf(SignalKey.CAUTIONS)

    private var announced = false

    override fun evaluate(ctx: TickContext, events: MutableList<RuleEvent>): FeedbackCandidate? {
        val zone = ctx.schoolZone
        if (!zone.cautionPresent && !zone.inside) announced = false
        if (announced || zone.aheadM == null) return null
        return FeedbackCandidate(type, FeedbackMessages.SCHOOL_ZONE_AHEAD)
    }

    override fun onSpoken(ctx: TickContext) {
        announced = true
    }

    override fun reset() {
        announced = false
    }
}

/**
 * 우선순위 2. 보호구역 안에서 그 지점 제한속도 초과가 3초 이어지면 기록하고 구역당 한 번 말한다.
 * 보호구역 제한속도는 "30km/h 이내"로 구간·시간대마다 다르므로(20, 야간 50 등) 내비가 주는 지점 제한속도를 먼저 쓰고,
 * 없을 때만 [FeedbackConfig.schoolZoneFallbackLimitKmh]를 쓴다.
 */
class SchoolZoneSpeedRule(private val config: FeedbackConfig) : FeedbackRule {
    override val type = FeedbackType.SCHOOL_ZONE_SPEED
    override val requires = setOf(SignalKey.CAUTIONS, SignalKey.POSITION)

    private val over = SustainedOverSpeed(config.schoolZoneSustainMs, config.speedHysteresisKmh)
    private var spokenInZone = false

    override fun evaluate(ctx: TickContext, events: MutableList<RuleEvent>): FeedbackCandidate? {
        if (!ctx.schoolZone.inside) {
            over.reset()
            spokenInZone = false
            return null
        }
        val limit = ctx.navi.speedLimitKmh?.takeIf { it > 0 }?.toDouble() ?: config.schoolZoneFallbackLimitKmh
        if (over.update(ctx.speedKmh, limit, ctx.nowMs)) {
            events += RuleEvent(DriveEventType.SCHOOL_ZONE_SPEEDING, "제한 ${limit.toInt()}km/h, 추정 ${ctx.speedKmh?.toInt()}km/h")
        }
        if (!over.inEpisode || spokenInZone) return null
        return FeedbackCandidate(type, FeedbackMessages.SCHOOL_ZONE_SPEED)
    }

    override fun onSpoken(ctx: TickContext) {
        spokenInZone = true
    }

    override fun reset() {
        over.reset()
        spokenInZone = false
    }
}

/**
 * 우선순위 3. 제한속도+10 초과가 5초 이어지면 기록하고 그 구간에 한 번 말한다. 순간 초과는 무시한다.
 * +10/5초는 코칭 가설값이다(공단 eTAS 과속은 +20). [FeedbackConfig.speedOverLimitKmh] 참고.
 */
class SpeedLimitRule(private val config: FeedbackConfig) : FeedbackRule {
    override val type = FeedbackType.SPEED_LIMIT_SUSTAINED
    override val requires = setOf(SignalKey.SPEED_LIMIT, SignalKey.POSITION)

    private val over = SustainedOverSpeed(config.speedSustainMs, config.speedHysteresisKmh)
    private var spokenInEpisode = false

    override fun evaluate(ctx: TickContext, events: MutableList<RuleEvent>): FeedbackCandidate? {
        val limit = ctx.navi.speedLimitKmh?.takeIf { it > 0 }
        val threshold = limit?.let { it + config.speedOverLimitKmh }
        if (over.update(ctx.speedKmh, threshold, ctx.nowMs)) {
            spokenInEpisode = false
            events += RuleEvent(DriveEventType.SPEEDING, "제한 ${limit}km/h, 추정 ${ctx.speedKmh?.toInt()}km/h")
        }
        if (!over.inEpisode || spokenInEpisode) return null
        return FeedbackCandidate(type, FeedbackMessages.SPEED_LIMIT)
    }

    override fun onSpoken(ctx: TickContext) {
        spokenInEpisode = true
    }

    override fun reset() {
        over.reset()
        spokenInEpisode = false
    }
}

/**
 * 우선순위 4. 다음 회전 50m 안인데 방향지시등이 꺼져 있거나 반대면 "깜빡이"를 한 번 말한다.
 * 회전 40m 앞 기록 판정([TurnSignalJudge])도 여기서 한다. 코스 이탈 중에는 판정하지 않는다.
 */
class TurnSignalRule(private val config: FeedbackConfig) : FeedbackRule {
    override val type = FeedbackType.TURN_SIGNAL_MISSING
    override val requires = setOf(SignalKey.NEXT_TURN, SignalKey.TURN_SIGNAL)

    private val judge = TurnSignalJudge(config)
    private var naggedTurnId = -1

    override fun evaluate(ctx: TickContext, events: MutableList<RuleEvent>): FeedbackCandidate? {
        val turn = ctx.turn ?: return null
        val signal = ctx.vehicle.turnSignal ?: return null
        if (ctx.offCourse) {
            judge.skip(turn)
        } else {
            judge.judge(turn, signal)?.let { events += RuleEvent(it.type, "${it.turnKind.name} 회전, 방향지시등 ${it.signal.name}") }
        }
        val expected = TurnSignalJudge.expectedSignal(turn.kind) ?: return null
        if (turn.id == naggedTurnId || signal == expected || turn.distanceM > config.turnSignalNagDistanceM) return null
        return FeedbackCandidate(type, FeedbackMessages.TURN_SIGNAL)
    }

    override fun onSpoken(ctx: TickContext) {
        ctx.turn?.let { naggedTurnId = it.id }
    }

    override fun reset() {
        naggedTurnId = -1
    }
}

/**
 * 우선순위 5. 다음 "연습 기술" 지점 150m 전에 예고한다. 그 앞에 연습 대상이 아닌 회전이 있어도 건너뛰고 본다
 * (L1-s3: 270m 우회전 바로 뒤 285m 좌회전). 연습 기술은 [practiceSkills].
 */
class SkillPreviewRule(private val config: FeedbackConfig) : FeedbackRule {
    override val type = FeedbackType.SKILL_PREVIEW
    override val requires = setOf(SignalKey.POSITION)

    private val previewed = mutableSetOf<Int>()

    override fun evaluate(ctx: TickContext, events: MutableList<RuleEvent>): FeedbackCandidate? {
        val next = ctx.nextPracticeManeuver ?: return null
        val m = next.maneuver
        if (m.index in previewed) return null
        if (next.distanceM !in config.skillPreviewMinM..config.skillPreviewMaxM) return null
        return FeedbackCandidate(type, FeedbackMessages.skillPreview(m))
    }

    override fun onSpoken(ctx: TickContext) {
        ctx.nextPracticeManeuver?.let { previewed += it.maneuver.index }
    }

    // 위치가 잠시 끊겨도 이미 예고한 지점은 다시 말하지 않는다.
    override fun reset() = Unit

    companion object {
        /**
         * 코스의 연습 기술. 우회전은 그 코스에 우회전밖에 없을 때만(우회전이 목표인 경우) 넣는다. 흔해서 말이 너무 많아진다.
         * 어린이보호구역은 차량 내비가 진입을 안내하고 전용 규칙(SCHOOL_ZONE_AHEAD, 기본 꺼짐)도 있어 겹쳐 말하지 않게 뺀다.
         */
        fun practiceSkills(maneuvers: List<Maneuver>): Set<Skill> {
            val skills = maneuvers.map { it.skill }.toSet() - Skill.SCHOOL_ZONE
            return if (skills.any { it != Skill.RIGHT_TURN }) skills - Skill.RIGHT_TURN else skills
        }
    }
}

/**
 * 우선순위 6. 브레이크를 밟은 채 1초 안에 감속을 시작한 속도가 30km/h 이하면 14km/h, 초과면 15km/h 이상 줄고
 * 줄어든 속도가 6km/h 이상이면 급제동 1회(eTAS 택시 급감속 준용). 5km/h 이하로 서는 것은 정상 정지라 세지 않는다.
 * 감속 시작 시점의 평활 속도가 10km/h 미만이면(정차 중 위치 튐) 세지 않는다.
 * 한 주행 3회째에, 급제동 직후가 아니라 브레이크를 뗀 뒤 안정 구간에서 한 번 말한다.
 */
class HardBrakeRule(private val config: FeedbackConfig) : FeedbackRule {
    override val type = FeedbackType.HARD_BRAKE_REPEAT
    override val requires = setOf(SignalKey.BRAKE, SignalKey.POSITION)

    private class Sample(val atMs: Long, val kmh: Double, val smoothedKmh: Double?)

    private val history = ArrayDeque<Sample>()
    private var count = 0
    private var lastHardBrakeMs: Long? = null
    private var inHardBrake = false
    private var spoken = false

    override fun evaluate(ctx: TickContext, events: MutableList<RuleEvent>): FeedbackCandidate? {
        val speed = ctx.responsiveSpeedKmh
        val braking = ctx.vehicle.brakePressed == true
        if (!braking) inHardBrake = false
        if (speed == null) {
            history.clear()
            return null
        }
        history.addLast(Sample(ctx.nowMs, speed, ctx.speedKmh))
        while (history.isNotEmpty() && ctx.nowMs - history.first().atMs > config.hardBrakeWindowMs) history.removeFirst()
        // 창 안 최고 속도를 감속 시작 속도로 본다.
        val start = history.maxBy { it.kmh }
        val startSpeed = start.kmh
        val drop = startSpeed - speed
        val minDrop = if (startSpeed <= config.hardBrakeSplitKmh) config.hardBrakeDropLowKmh else config.hardBrakeDropHighKmh
        val wasMoving = (start.smoothedKmh ?: 0.0) >= config.hardBrakeMinStartSmoothedKmh
        if (braking && !inHardBrake && wasMoving && drop >= minDrop && speed >= config.hardBrakeMinEndKmh) {
            inHardBrake = true
            count++
            lastHardBrakeMs = ctx.nowMs
            events += RuleEvent(DriveEventType.HARD_BRAKE, "1초 감속 ${drop.toInt()}km/h")
        }
        val last = lastHardBrakeMs ?: return null
        if (spoken || braking || count < config.hardBrakeRepeatCount || ctx.nowMs - last < config.hardBrakeQuietAfterMs) return null
        return FeedbackCandidate(type, FeedbackMessages.HARD_BRAKE_REPEAT)
    }

    override fun onSpoken(ctx: TickContext) {
        spoken = true
    }

    // 횟수는 주행 전체 기준이라 유지한다. 속도 이력만 버린다.
    override fun reset() {
        history.clear()
        inHardBrake = false
    }
}
