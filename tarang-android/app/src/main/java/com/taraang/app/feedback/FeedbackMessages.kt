package com.taraang.app.feedback

import com.taraang.app.domain.model.DataConfidence
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.model.Skill
import com.taraang.app.domain.signal.WarningType

/**
 * 주행 중 음성 문구. 짧고 차분하게, 한 문장 위주로 쓴다(IDEA 3.4).
 * 문구는 여기서만 만든다.
 */
object FeedbackMessages {

    fun newWarning(warning: WarningType): String = newWarnings(listOf(warning))

    /** 한 번에 켜진 경고는 한 문장으로 묶는다(따로 말하면 두 번째가 첫 번째를 끊는다). */
    fun newWarnings(warnings: List<WarningType>): String =
        "${warnings.joinToString("·") { it.label }} 경고가 켜졌어요. 급하게 조작하지 말고 안전한 곳에 세운 뒤 확인하세요."

    const val SCHOOL_ZONE_AHEAD = "곧 어린이보호구역이에요. 시속 30 이하로 천천히요."
    const val SCHOOL_ZONE_SPEED = "어린이보호구역이에요. 시속 30 이하로 천천히요."
    const val SPEED_LIMIT = "제한속도보다 빨라요. 조금만 속도를 줄여 주세요."
    const val TURN_SIGNAL = "깜빡이를 켜 주세요."
    const val HARD_BRAKE_REPEAT = "브레이크를 조금 일찍, 부드럽게 밟아 보세요."

    /**
     * confidence는 신호등 데이터 근거다. UNKNOWN을 어려운 쪽으로 보는 것은 좌회전·유턴뿐이다(ARCHITECTURE.md 4.1).
     */
    fun skillPreview(maneuver: Maneuver): String {
        val unknownSignal = maneuver.skill == Skill.LEFT_TURN_UNKNOWN ||
            (maneuver.confidence == DataConfidence.UNKNOWN && (maneuver.skill == Skill.LEFT_TURN_SIGNALIZED || maneuver.skill == Skill.U_TURN))
        return if (unknownSignal) {
            val what = if (maneuver.skill == Skill.U_TURN) "유턴" else "좌회전"
            "곧 신호가 확인되지 않은 ${what}이에요. 천천히 살피고 도세요."
        } else {
            "곧 오늘 연습할 ${spoken(maneuver.skill)} 구간이에요."
        }
    }

    /** 화면 라벨과 달리 귀로 듣기 좋은 말. */
    private fun spoken(skill: Skill): String = when (skill) {
        Skill.RIGHT_TURN -> "우회전"
        Skill.LEFT_TURN_SIGNALIZED -> "신호 좌회전"
        Skill.LEFT_TURN_UNKNOWN -> "좌회전"
        Skill.U_TURN -> "유턴"
        Skill.ROUNDABOUT -> "회전교차로"
        Skill.MERGE -> "합류"
        Skill.SCHOOL_ZONE -> "어린이보호구역"
        Skill.UNDERGROUND_PARKING -> "지하주차장 진입"
    }
}
