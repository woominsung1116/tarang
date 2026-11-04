package com.taraang.app.data.course

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Geo
import com.taraang.app.core.LatLng
import com.taraang.app.core.runCatchingApp
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.DataConfidence
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.model.Skill
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** ARCHITECTURE.md 4.1 응답 DTO. 서버·캐시·번들 모두 같은 형식이다. */
@Serializable
internal data class CourseResponseDto(
    val schemaVersion: Int = 1,
    val origin: LatLonDto,
    val generatedAt: String? = null,
    val dataNotes: List<String> = emptyList(),
    val courses: List<CourseDto> = emptyList(),
)

@Serializable
internal data class LatLonDto(val lat: Double, val lon: Double)

@Serializable
internal data class CourseDto(
    val id: String,
    val level: Int,
    val title: String,
    val distanceM: Int,
    val path: List<List<Double>> = emptyList(),
    val waypoints: List<List<Double>> = emptyList(),
    val maneuvers: List<ManeuverDto> = emptyList(),
    val skillCounts: Map<String, Int> = emptyMap(),
    val warnings: List<String> = emptyList(),
)

@Serializable
internal data class ManeuverDto(
    val index: Int,
    val skill: String,
    val street: String = "",
    val lat: Double,
    val lon: Double,
    val distanceFromStartM: Double = 0.0,
    val confidence: String = "UNKNOWN",
)

/** `/v1/route-skills` 응답(4.2). */
@Serializable
internal data class RouteSkillsDto(
    val schemaVersion: Int = 1,
    val distanceM: Int,
    val skillCounts: Map<String, Int> = emptyMap(),
    val maneuvers: List<ManeuverDto> = emptyList(),
    val warnings: List<String> = emptyList(),
    val dataNotes: List<String> = emptyList(),
)

@Serializable
internal data class ErrorBodyDto(val error: ErrorDto)

@Serializable
internal data class ErrorDto(val code: String, val message: String = "")

/** [dropped]: 검증에 걸려 버린 코스 수(로그용). */
data class ParsedCourses(val origin: LatLng, val courses: List<Course>, val dataNotes: List<String>, val dropped: Int = 0)

/**
 * 코스 JSON → 도메인. 모르는 필드는 무시하고, 모르는 skill 값의 회전 지점은 버린다(서버가 먼저 확장돼도 앱이 죽지 않게).
 * 좌표가 [lat, lon] 두 개가 아닌 점도 버린다.
 *
 * 서버·캐시 응답은 신뢰하지 않는다(7장 6번). 출발지가 한국 밖이면 응답 전체를 오류로, 코스 하나가 아래 한도를 넘으면
 * 그 코스만 버린다: 경로 2~[MAX_PATH_POINTS]점, 제목 1~[MAX_TITLE]자, 거리 1~[MAX_DISTANCE_M]m, 모든 좌표 한국 범위,
 * 경유지 ≤[MAX_WAYPOINTS], 지점 ≤[MAX_MANEUVERS]개(번호 0~999 중복 없음, 진행 거리 0~코스 거리+[DISTANCE_SLACK_M]),
 * 경고 ≤[MAX_WARNINGS]×[MAX_TEXT]자. 데이터 안내문은 너무 긴 것을 빼고 앞 [MAX_WARNINGS]개만 쓴다.
 */
object CourseJson {
    internal val json = Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false }

    fun parse(text: String, source: CourseSource): AppResult<ParsedCourses> =
        runCatchingApp({ AppError.Server(200, "BAD_RESPONSE", "코스 JSON 해석 실패: ${it.javaClass.simpleName}") }) {
            val dto = json.decodeFromString(CourseResponseDto.serializer(), text)
            val origin = LatLng(dto.origin.lat, dto.origin.lon)
            require(isValidPoint(origin)) { "출발지가 한국 범위 밖" }
            val courses = dto.courses.mapNotNull { it.toDomain(origin, source) }
            val notes = dto.dataNotes.filter { it.length <= MAX_TEXT }.take(MAX_WARNINGS)
            ParsedCourses(origin, courses, notes, dropped = dto.courses.size - courses.size)
        }

    /**
     * 목표 경로 분해 응답 → 기술별 횟수. 코스와 같은 기준으로 검증하고(거리 1~[MAX_ROUTE_M]m, 지점 ≤[MAX_ROUTE_MANEUVERS]개·한국 범위·
     * 번호 중복 없음·진행 거리 범위, 경고 ≤[MAX_WARNINGS]×[MAX_TEXT]자), 하나라도 어기면 응답 전체를 오류로 본다(부분만 믿을 근거가 없다).
     * 모르는 기술 이름은 버린다.
     */
    fun parseRouteSkills(text: String): AppResult<Map<Skill, Int>> =
        runCatchingApp({ AppError.Server(200, "BAD_RESPONSE", "경로 분석 응답 해석 실패: ${it.javaClass.simpleName}") }) {
            val dto = json.decodeFromString(RouteSkillsDto.serializer(), text)
            require(dto.distanceM in 1..MAX_ROUTE_M) { "distanceM" }
            require(dto.maneuvers.size <= MAX_ROUTE_MANEUVERS) { "maneuvers" }
            require(dto.warnings.size <= MAX_WARNINGS && dto.warnings.all { it.length <= MAX_TEXT }) { "warnings" }
            require(dto.skillCounts.size <= Skill.entries.size * 2 && dto.skillCounts.values.all { it in 0..MAX_ROUTE_MANEUVERS }) { "skillCounts" }
            val known = dto.maneuvers.mapNotNull { it.toDomain() }
            require(known.all { isSaneManeuver(it, dto.distanceM) } && known.map { it.index }.toSet().size == known.size) { "maneuver" }
            dto.skillCounts.mapNotNull { (k, v) -> enumOrNull<Skill>(k)?.let { it to v } }.filter { it.second > 0 }.toMap()
        }

    internal fun parseError(text: String): ErrorDto? =
        runCatching { json.decodeFromString(ErrorBodyDto.serializer(), text).error }.getOrNull()

    /** 한도를 넘거나 값이 이상하면 null(이 코스만 버린다). */
    private fun CourseDto.toDomain(origin: LatLng, source: CourseSource): Course? {
        if (id.isBlank() || id.length > MAX_ID || level !in 1..4) return null
        if (title.isBlank() || title.length > MAX_TITLE || distanceM !in 1..MAX_DISTANCE_M) return null
        if (path.size > MAX_PATH_POINTS || waypoints.size > MAX_WAYPOINTS || maneuvers.size > MAX_MANEUVERS) return null
        if (warnings.size > MAX_WARNINGS || warnings.any { it.length > MAX_TEXT }) return null
        if (skillCounts.size > Skill.entries.size * 2 || skillCounts.values.any { it !in 0..MAX_MANEUVERS }) return null
        val points = path.mapNotNull { it.toLatLng() }
        val stops = waypoints.mapNotNull { it.toLatLng() }
        if (points.size < 2 || !points.all(::isValidPoint) || !stops.all(::isValidPoint)) return null
        val known = maneuvers.mapNotNull { it.toDomain() }
        if (!known.all { isSaneManeuver(it, distanceM) } || known.map { it.index }.toSet().size != known.size) return null
        return Course(
            id = id,
            level = level,
            title = title,
            distanceM = distanceM,
            origin = origin,
            path = points,
            waypoints = stops,
            maneuvers = known,
            skillCounts = skillCounts.mapNotNull { (k, v) -> enumOrNull<Skill>(k)?.let { it to v } }.toMap(),
            warnings = warnings,
            source = source,
        )
    }

    private fun isSaneManeuver(m: Maneuver, distanceM: Int): Boolean =
        m.index in 0..MAX_MANEUVER_INDEX && isValidPoint(m.position) && m.street.length <= MAX_STREET &&
            !m.distanceFromStartM.isNaN() && m.distanceFromStartM in 0.0..(distanceM + DISTANCE_SLACK_M)

    private fun isValidPoint(p: LatLng): Boolean = !p.lat.isNaN() && !p.lon.isNaN() && Geo.isInKorea(p)

    private fun ManeuverDto.toDomain(): Maneuver? {
        val skill = enumOrNull<Skill>(skill) ?: return null
        return Maneuver(
            index = index,
            skill = skill,
            street = street,
            position = LatLng(lat, lon),
            distanceFromStartM = distanceFromStartM,
            // 모르는 값은 어려운 쪽(UNKNOWN)으로 본다.
            confidence = enumOrNull<DataConfidence>(confidence) ?: DataConfidence.UNKNOWN,
        )
    }

    private fun List<Double>.toLatLng(): LatLng? = if (size == 2) LatLng(this[0], this[1]) else null

    private const val MAX_ID = 100
    private const val MAX_TITLE = 120
    private const val MAX_DISTANCE_M = 100_000
    private const val MAX_PATH_POINTS = 5_000
    /**
     * 서버는 2개를 보낸다(4.1, NaviHelper 3.0.5 WaypointIndex FIRST/SECOND). 이전 캐시·번들에는 3개가 있어 3개까지 받고,
     * 내비로 보낼 때 런처가 처음·마지막 2개만 쓴다. 3개 코스를 버리면 앱이 빈 화면이 된다.
     */
    private const val MAX_WAYPOINTS = 3
    private const val MAX_MANEUVERS = 200
    private const val MAX_MANEUVER_INDEX = 999
    private const val MAX_STREET = 100
    private const val MAX_WARNINGS = 10
    private const val MAX_TEXT = 200
    /** 목표 경로(출퇴근 등)는 연습 코스보다 길 수 있다. */
    private const val MAX_ROUTE_M = 200_000
    private const val MAX_ROUTE_MANEUVERS = 500
    /** 서버 거리 계산과 경로 길이의 반올림 차이 여유. */
    private const val DISTANCE_SLACK_M = 100.0

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
}
