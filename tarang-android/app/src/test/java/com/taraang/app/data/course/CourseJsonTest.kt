package com.taraang.app.data.course

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.LatLng
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.DataConfidence
import com.taraang.app.domain.model.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

fun fixture(name: String): String =
    requireNotNull(CourseJsonTest::class.java.classLoader?.getResource(name)) { "fixture 없음: $name" }.readText()

class CourseJsonTest {

    private fun parseSample() = (CourseJson.parse(fixture("courses/sample.json"), CourseSource.SERVER) as AppResult.Ok).value

    @Test
    fun `parses origin, notes and courses with source`() {
        val parsed = parseSample()
        assertEquals(LatLng(35.2310, 129.0840), parsed.origin)
        assertEquals(1, parsed.dataNotes.size)
        assertEquals(listOf("L1-35.2310-129.0840-s3", "L2-35.2310-129.0840-s7"), parsed.courses.map { it.id })
        assertTrue(parsed.courses.all { it.source == CourseSource.SERVER && it.origin == parsed.origin })
    }

    @Test
    fun `malformed points and unknown skills are dropped, unknown confidence is UNKNOWN`() {
        val course = parseSample().courses.first()
        assertEquals(3, course.path.size)
        assertEquals(2, course.waypoints.size)
        assertEquals(listOf(0, 2), course.maneuvers.map { it.index })
        val left = course.maneuvers.last()
        assertEquals(Skill.LEFT_TURN_UNKNOWN, left.skill)
        assertEquals(DataConfidence.UNKNOWN, left.confidence)
        assertEquals(mapOf(Skill.RIGHT_TURN to 1, Skill.LEFT_TURN_UNKNOWN to 1), course.skillCounts)
        assertEquals("금정로", course.maneuvers.first().street)
        assertEquals(120.5, course.maneuvers.first().distanceFromStartM, 0.0)
    }

    @Test
    fun `invalid json is an error, not an exception`() {
        val result = CourseJson.parse("{not json", CourseSource.CACHE)
        assertTrue((result as AppResult.Err).error is AppError.Server)
    }

    @Test
    fun `missing required field is an error`() {
        val result = CourseJson.parse("""{"courses": []}""", CourseSource.CACHE)
        assertTrue(result is AppResult.Err)
    }

    // --- 신뢰하지 않는 응답 검증(M4) ---

    private val okCourse = """{"id":"L1-a","level":1,"title":"코스","distanceM":1000,
        "path":[[35.231,129.084],[35.232,129.085]],"waypoints":[[35.232,129.085]],
        "maneuvers":[{"index":0,"skill":"RIGHT_TURN","lat":35.2315,"lon":129.0845,"distanceFromStartM":100.0}],
        "skillCounts":{"RIGHT_TURN":1},"warnings":["주의"]}"""

    private fun parseOne(course: String, origin: String = """{"lat":35.231,"lon":129.084}""", notes: String = "[]") =
        CourseJson.parse("""{"origin":$origin,"dataNotes":$notes,"courses":[$course]}""", CourseSource.SERVER)

    private fun keeps(course: String) = (parseOne(course) as AppResult.Ok).value.courses.size == 1

    @Test
    fun `valid course passes`() {
        val parsed = (parseOne(okCourse) as AppResult.Ok).value
        assertEquals(1, parsed.courses.size)
        assertEquals(0, parsed.dropped)
    }

    @Test
    fun `courses breaking a limit are dropped one by one`() {
        val far = "[33.0,140.0]"
        val bad = mapOf(
            "path 1 point" to okCourse.replace("[[35.231,129.084],[35.232,129.085]]", "[[35.231,129.084]]"),
            "path over 5000" to okCourse.replace("[[35.231,129.084],[35.232,129.085]]", (1..5001).joinToString(",", "[", "]") { "[35.231,129.084]" }),
            "path outside Korea" to okCourse.replace("[35.232,129.085]],\"waypoints\"", "$far],\"waypoints\""),
            "blank title" to okCourse.replace("\"코스\"", "\"  \""),
            "title over 120" to okCourse.replace("\"코스\"", "\"" + "가".repeat(121) + "\""),
            "distance 0" to okCourse.replace("1000", "0"),
            "distance over 100km" to okCourse.replace("1000", "100001"),
            "level 5" to okCourse.replace("\"level\":1", "\"level\":5"),
            "4 waypoints" to okCourse.replace("[[35.232,129.085]]", "[[35.232,129.085],[35.232,129.085],[35.232,129.085],[35.232,129.085]]"),
            "maneuver outside Korea" to okCourse.replace("\"lat\":35.2315,\"lon\":129.0845", "\"lat\":10.0,\"lon\":10.0"),
            "maneuver negative index" to okCourse.replace("\"index\":0", "\"index\":-1"),
            "maneuver beyond course end" to okCourse.replace("100.0", "1200.0"),
            "maneuver negative distance" to okCourse.replace("100.0", "-1.0"),
            "duplicate maneuver index" to okCourse.replace("\"distanceFromStartM\":100.0}]", "\"distanceFromStartM\":100.0},{\"index\":0,\"skill\":\"RIGHT_TURN\",\"lat\":35.2315,\"lon\":129.0845}]"),
            "11 warnings" to okCourse.replace("[\"주의\"]", (1..11).joinToString(",", "[", "]") { "\"w\"" }),
            "warning over 200" to okCourse.replace("[\"주의\"]", "[\"" + "w".repeat(201) + "\"]"),
        )
        bad.forEach { (name, json) -> assertTrue(name, !keeps(json)) }
        // 경계값은 통과
        assertTrue(keeps(okCourse.replace("\"코스\"", "\"" + "가".repeat(120) + "\"")))
        assertTrue(keeps(okCourse.replace("[\"주의\"]", (1..10).joinToString(",", "[", "]") { "\"" + "w".repeat(200) + "\"" })))
        assertTrue(keeps(okCourse.replace("100.0", "1100.0")))
        assertTrue(keeps(okCourse.replace("[[35.232,129.085]]", "[[35.232,129.085],[35.232,129.085]]")))
        // 옛 캐시·번들의 3개 경유지도 받는다(런처가 2개로 줄인다).
        assertTrue(keeps(okCourse.replace("[[35.232,129.085]]", "[[35.232,129.085],[35.232,129.085],[35.232,129.085]]")))
    }

    @Test
    fun `dropped count is reported and good courses survive`() {
        val bad = okCourse.replace("1000", "0").replace("L1-a", "L1-bad")
        val parsed = (CourseJson.parse("""{"origin":{"lat":35.231,"lon":129.084},"courses":[$okCourse,$bad]}""", CourseSource.SERVER) as AppResult.Ok).value
        assertEquals(listOf("L1-a"), parsed.courses.map { it.id })
        assertEquals(1, parsed.dropped)
    }

    @Test
    fun `origin outside Korea rejects the whole response`() {
        assertTrue(parseOne(okCourse, origin = """{"lat":0.0,"lon":0.0}""") is AppResult.Err)
    }

    @Test
    fun `long or excess data notes are trimmed, not fatal`() {
        val notes = (listOf("가".repeat(201)) + (1..12).map { "n$it" }).joinToString(",", "[", "]") { "\"$it\"" }
        val parsed = (parseOne(okCourse, notes = notes) as AppResult.Ok).value
        assertEquals((1..10).map { "n$it" }, parsed.dataNotes)
    }

    // --- /v1/route-skills ---

    private val okRoute = """{"schemaVersion":1,"distanceM":8200,"skillCounts":{"LEFT_TURN_SIGNALIZED":2,"U_TURN":1,"RIGHT_TURN":0,"FLYING":3},
        "maneuvers":[{"index":0,"skill":"LEFT_TURN_SIGNALIZED","lat":35.24,"lon":129.09,"distanceFromStartM":500.0}],
        "warnings":["w"],"dataNotes":["n"]}"""

    @Test
    fun `route skills keep known positive counts`() {
        val counts = (CourseJson.parseRouteSkills(okRoute) as AppResult.Ok).value
        assertEquals(mapOf(Skill.LEFT_TURN_SIGNALIZED to 2, Skill.U_TURN to 1), counts)
    }

    @Test
    fun `route skills response breaking a limit is rejected as a whole`() {
        listOf(
            okRoute.replace("8200", "0"),
            okRoute.replace("8200", "200001"),
            okRoute.replace("\"lat\":35.24", "\"lat\":10.0"),
            okRoute.replace("500.0", "9000.0"),
            okRoute.replace("\"U_TURN\":1", "\"U_TURN\":-1"),
            okRoute.replace("[\"w\"]", "[\"" + "w".repeat(201) + "\"]"),
            """{"skillCounts":{}}""",
            "{broken",
        ).forEach { bad ->
            val err = (CourseJson.parseRouteSkills(bad) as AppResult.Err).error
            assertEquals(bad, "BAD_RESPONSE", (err as AppError.Server).code)
        }
    }

    @Test
    fun `bundled demo courses all pass validation`() {
        val text = java.io.File("src/main/assets/courses/demo_pnu.json").readText()
        val parsed = (CourseJson.parse(text, CourseSource.BUNDLED) as AppResult.Ok).value
        assertEquals(0, parsed.dropped)
        assertTrue(parsed.courses.isNotEmpty())
    }

    @Test
    fun `error body is parsed`() {
        val err = CourseJson.parseError("""{"error":{"code":"NO_COURSE","message":"없음"}}""")
        assertEquals("NO_COURSE", err?.code)
        assertEquals(null, CourseJson.parseError("<html>"))
    }
}
