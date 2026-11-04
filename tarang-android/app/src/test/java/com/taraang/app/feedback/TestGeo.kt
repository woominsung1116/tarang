package com.taraang.app.feedback

import com.taraang.app.core.LatLng
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.DataConfidence
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.model.Skill
import kotlin.math.cos

/** 테스트용: 부산대 앞에서 북쪽으로 곧게 뻗은 코스. */
val START = LatLng(35.2300, 129.0800)

private const val M_PER_DEG_LAT = 111_320.0

fun LatLng.north(meters: Double) = LatLng(lat + meters / M_PER_DEG_LAT, lon)

fun LatLng.east(meters: Double) = LatLng(lat, lon + meters / (M_PER_DEG_LAT * cos(Math.toRadians(lat))))

fun maneuver(index: Int, skill: Skill, metersNorth: Double, confidence: DataConfidence = DataConfidence.VERIFIED) =
    Maneuver(index, skill, "테스트로", START.north(metersNorth), metersNorth, confidence)

fun testCourse(maneuvers: List<Maneuver> = emptyList(), lengthM: Double = 5_000.0) = Course(
    id = "L1-test",
    level = 1,
    title = "테스트 코스",
    distanceM = lengthM.toInt(),
    origin = START,
    path = (0..lengthM.toInt() step 100).map { START.north(it.toDouble()) },
    waypoints = emptyList(),
    maneuvers = maneuvers,
    skillCounts = maneuvers.groupingBy { it.skill }.eachCount(),
    warnings = emptyList(),
    source = CourseSource.BUNDLED,
)
