package com.taraang.app.data.db

import com.taraang.app.core.LatLng
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.DataConfidence
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.model.Skill
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 세션에 저장하는 코스 스냅샷(sessions.courseJson). 도메인 모델은 직렬화 주석을 달지 않으므로 여기서 옮긴다.
 * 스냅샷 형식이 바뀌어도 옛 행을 읽을 수 있게 모르는 필드는 무시하고, 깨진 값이면 null(코스 다시 찾기)로 둔다.
 */
internal object CourseSnapshot {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Point(val lat: Double, val lon: Double)

    @Serializable
    private data class ManeuverJson(
        val index: Int,
        val skill: String,
        val street: String,
        val at: Point,
        val distanceFromStartM: Double,
        val confidence: String,
    )

    @Serializable
    private data class CourseJson(
        val v: Int = 1,
        val id: String,
        val level: Int,
        val title: String,
        val distanceM: Int,
        val origin: Point,
        val path: List<Point>,
        val waypoints: List<Point>,
        val maneuvers: List<ManeuverJson>,
        val skillCounts: Map<String, Int>,
        val warnings: List<String>,
        val source: String,
    )

    fun encode(course: Course): String = json.encodeToString(
        CourseJson.serializer(),
        CourseJson(
            id = course.id,
            level = course.level,
            title = course.title,
            distanceM = course.distanceM,
            origin = course.origin.toPoint(),
            path = course.path.map { it.toPoint() },
            waypoints = course.waypoints.map { it.toPoint() },
            maneuvers = course.maneuvers.map {
                ManeuverJson(it.index, it.skill.name, it.street, it.position.toPoint(), it.distanceFromStartM, it.confidence.name)
            },
            skillCounts = course.skillCounts.mapKeys { it.key.name },
            warnings = course.warnings,
            source = course.source.name,
        ),
    )

    fun decode(text: String): Course? = runCatching {
        val c = json.decodeFromString(CourseJson.serializer(), text)
        Course(
            id = c.id,
            level = c.level,
            title = c.title,
            distanceM = c.distanceM,
            origin = c.origin.toLatLng(),
            path = c.path.map { it.toLatLng() },
            waypoints = c.waypoints.map { it.toLatLng() },
            maneuvers = c.maneuvers.mapNotNull { m ->
                val skill = enumOrNull<Skill>(m.skill) ?: return@mapNotNull null
                Maneuver(m.index, skill, m.street, m.at.toLatLng(), m.distanceFromStartM, enumOrNull<DataConfidence>(m.confidence) ?: DataConfidence.UNKNOWN)
            },
            skillCounts = c.skillCounts.mapNotNull { (k, v) -> enumOrNull<Skill>(k)?.let { it to v } }.toMap(),
            warnings = c.warnings,
            source = enumOrNull<CourseSource>(c.source) ?: CourseSource.CACHE,
        )
    }.getOrNull()

    private fun LatLng.toPoint() = Point(lat, lon)
    private fun Point.toLatLng() = LatLng(lat, lon)
    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
}
