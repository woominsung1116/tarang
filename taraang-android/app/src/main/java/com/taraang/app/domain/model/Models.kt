package com.taraang.app.domain.model

import com.taraang.app.core.LatLng

/** 코스와 목표 경로를 구성하는 운전 기술. 코스 JSON의 skill 값과 이름이 같다. */
enum class Skill(val label: String) {
    RIGHT_TURN("우회전"),
    LEFT_TURN_SIGNALIZED("신호 좌회전"),
    LEFT_TURN_UNKNOWN("좌회전(신호 미확인)"),
    U_TURN("유턴"),
    ROUNDABOUT("회전교차로"),
    MERGE("합류"),
    SCHOOL_ZONE("어린이보호구역"),
    UNDERGROUND_PARKING("지하주차장 진입"),
}

/** 데이터 근거 수준. UNKNOWN은 어려운 쪽으로 간주한다. */
enum class DataConfidence { VERIFIED, ESTIMATED, UNKNOWN }

enum class CourseSource { SERVER, CACHE, BUNDLED }

data class Maneuver(
    val index: Int,
    val skill: Skill,
    val street: String,
    val position: LatLng,
    val distanceFromStartM: Double,
    val confidence: DataConfidence,
)

data class Course(
    val id: String,
    val level: Int,
    val title: String,
    val distanceM: Int,
    val origin: LatLng,
    val path: List<LatLng>,
    val waypoints: List<LatLng>,
    val maneuvers: List<Maneuver>,
    val skillCounts: Map<Skill, Int>,
    val warnings: List<String>,
    val source: CourseSource,
)

data class HardSpot(val id: String, val position: LatLng, val label: String)

/** 사용자가 결국 혼자 다니고 싶은 생활 경로. */
data class GoalRoute(
    val id: String,
    val name: String,
    val origin: LatLng,
    val destination: LatLng,
    val skills: Map<Skill, Int>,
    val hardSpots: List<HardSpot>,
    val createdAtMs: Long,
)

/** 단계 1..4. IDEA 3.2. */
data class LevelProgress(val currentLevel: Int, val completedCoursesAtLevel: Int, val requiredToAdvance: Int) {
    val canAdvance: Boolean get() = currentLevel < MAX_LEVEL && completedCoursesAtLevel >= requiredToAdvance

    companion object {
        const val MAX_LEVEL = 4
    }
}

enum class SignalMode { LIVE, REPLAY }

data class DriveSession(
    val id: String,
    val courseId: String,
    val mode: SignalMode,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val interrupted: Boolean,
)

enum class DriveEventType(val label: String) {
    TURN_SIGNAL_OK("회전 전 방향지시등"),
    TURN_SIGNAL_MISSED("방향지시등 미사용"),
    SPEEDING("제한속도 초과"),
    SCHOOL_ZONE_SPEEDING("어린이보호구역 속도 초과"),
    HARD_BRAKE("급제동"),
    NEW_WARNING("새 차량 경고"),
    OFF_COURSE("코스 이탈"),
}

data class DriveEvent(
    val sessionId: String,
    val type: DriveEventType,
    val timestampMs: Long,
    val position: LatLng?,
    val maneuverIndex: Int?,
    val detail: String,
)

enum class Feeling(val label: String) { OK("괜찮았음"), TENSE("긴장됨"), MISTAKE("실수함") }

data class SegmentFeeling(val sessionId: String, val maneuverIndex: Int, val feeling: Feeling)

/** 주행 1회 요약. 같은 코스의 이전 주행과 비교한다(IDEA 3.5). */
data class DriveSummary(
    val session: DriveSession,
    val course: Course?,
    val events: List<DriveEvent>,
    val feelings: List<SegmentFeeling>,
    val turnSignalOk: Int,
    val turnSignalTotal: Int,
    val previous: DriveSummary?,
)

data class FeedbackSettings(
    val enabled: Map<FeedbackType, Boolean> = FeedbackType.entries.associateWith { true },
    val voiceEnabled: Boolean = true,
    val courseServerUrl: String = DEFAULT_SERVER_URL,
) {
    fun isEnabled(type: FeedbackType): Boolean = type == FeedbackType.NEW_WARNING || enabled[type] != false

    companion object {
        /** 에뮬레이터에서 호스트 PC의 코스 서버. */
        const val DEFAULT_SERVER_URL = "http://10.0.2.2:8000"
    }
}

/** 주행 중 피드백 종류. priority가 낮을수록 우선. ARCHITECTURE.md 5장. */
enum class FeedbackType(val priority: Int, val label: String, val userToggleable: Boolean) {
    NEW_WARNING(1, "새 차량 경고", false),
    SCHOOL_ZONE_AHEAD(2, "어린이보호구역 예고", true),
    SCHOOL_ZONE_SPEED(2, "어린이보호구역 속도", true),
    SPEED_LIMIT_SUSTAINED(3, "제한속도 초과", true),
    TURN_SIGNAL_MISSING(4, "회전 전 방향지시등", true),
    SKILL_PREVIEW(5, "연습 기술 예고", true),
    HARD_BRAKE_REPEAT(6, "급제동 반복", true),
}

data class FeedbackMessage(val type: FeedbackType, val text: String, val timestampMs: Long)
