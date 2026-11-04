package com.taraang.app.feedback

import com.taraang.app.core.AppResult
import com.taraang.app.data.course.CourseJson
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.FeedbackType
import com.taraang.app.domain.signal.SignalAvailability
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.vehicle.ReplayLogParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 번들 시연 로그(assets/replay/X.json)를 번들 코스와 함께 엔진에 돌려, 같은 이름의 X.expected.json(서버 담당이 시뮬레이터로
 * 만든 기대 결과)과 정확히 같은지 본다. 시연 자산은 제출물의 일부라 없거나 짝이 안 맞으면 건너뛰지 않고 실패한다.
 */
class DemoReplayAssetsTest {

    private val assets = File("src/main/assets")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Expected(val replay: String, val courseId: String, val spoken: List<String>, val events: Map<String, Int>)

    private data class Outcome(val spoken: List<FeedbackType>, val events: List<DriveEventType>)

    private fun replays(): List<File> =
        assets.resolve("replay").listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".expected.json") }
            .orEmpty().sortedBy { it.name }

    private fun bundledCourses(): List<Course> {
        val courseFile = File(assets, "courses/demo_pnu.json")
        assertTrue("번들 코스 없음: $courseFile", courseFile.exists())
        return (CourseJson.parse(courseFile.readText(), CourseSource.BUNDLED) as AppResult.Ok).value.courses
    }

    /**
     * [courseId]가 null이면 로그의 courseId 코스로 돌린다. [settings]는 기본 설정(기대 결과 파일의 기준).
     */
    private fun play(replayFile: File, courseId: String? = null, settings: FeedbackSettings = FeedbackSettings()): Pair<String, Outcome> {
        val log = (ReplayLogParser.parse(replayFile.readText()) as AppResult.Ok).value
        val wanted = courseId ?: log.courseId
        // 다른 코스로 대신 돌리면 경로·지점이 안 맞아도 신호 기반 단언은 통과해 버린다. 없으면 실패시킨다.
        val course = bundledCourses().firstOrNull { it.id == wanted }
            ?: throw AssertionError("${replayFile.name}: courseId ${wanted}가 courses/demo_pnu.json에 없음")
        val engine = FeedbackEngine("demo", course, settings)
        val availability = SignalAvailability(SignalKey.entries.associateWith { SignalStatus.AVAILABLE })

        val spoken = mutableListOf<FeedbackType>()
        val events = mutableListOf<DriveEventType>()
        val tickMs = 250L
        var frameIdx = 0
        var now = 0L
        val end = log.frames.last().offsetMs + tickMs
        while (now <= end) {
            while (frameIdx + 1 < log.frames.size && log.frames[frameIdx + 1].offsetMs <= now) frameIdx++
            val f = log.frames[frameIdx]
            val r = engine.tick(f.vehicle.copy(timestampMs = f.offsetMs), f.navi.copy(timestampMs = f.offsetMs), availability, now)
            r.message?.let { spoken += it.type }
            events += r.events.map { it.type }
            now += tickMs
        }
        println("${replayFile.name} → spoken=$spoken events=$events")
        return log.courseId to Outcome(spoken, events)
    }

    @Test
    fun `every demo replay matches its expected outcome`() {
        val files = replays()
        assertTrue("assets/replay에 시연 로그가 없음", files.isNotEmpty())
        for (replay in files) {
            val expectedFile = File(replay.parentFile, replay.name.removeSuffix(".json") + ".expected.json")
            assertTrue("기대 결과 없음: ${expectedFile.name}", expectedFile.exists())
            val expected = json.decodeFromString(Expected.serializer(), expectedFile.readText())
            assertEquals("${expectedFile.name}의 replay", replay.name, expected.replay)

            val (courseId, out) = play(replay)
            assertEquals("${replay.name} courseId", expected.courseId, courseId)
            assertEquals("${replay.name} 발화", expected.spoken, out.spoken.map { it.name })
            expected.events.forEach { (type, count) ->
                val known = DriveEventType.entries.firstOrNull { it.name == type } ?: throw AssertionError("${expectedFile.name}: 모르는 이벤트 $type")
                assertEquals("${replay.name} $type 횟수", count, out.events.count { it == known })
            }
        }
    }

    /** 기대 결과는 기본 설정 기준이다. 보호구역 진입 예고를 켜면 그것이 먼저 나가고 기록은 같다(course-engine test_replays와 같은 약속). */
    @Test
    fun `level 1 demo speaks the school zone preview only when the user turns it on`() {
        val file = File(assets, "replay/demo_l1_signal_miss.json")
        val on = FeedbackSettings().let { it.copy(enabled = it.enabled + (FeedbackType.SCHOOL_ZONE_AHEAD to true)) }
        val (_, withPreview) = play(file, settings = on)
        val (_, byDefault) = play(file)
        assertEquals(
            listOf(FeedbackType.SCHOOL_ZONE_AHEAD, FeedbackType.SCHOOL_ZONE_SPEED, FeedbackType.TURN_SIGNAL_MISSING, FeedbackType.SPEED_LIMIT_SUSTAINED),
            withPreview.spoken,
        )
        assertTrue(FeedbackType.SCHOOL_ZONE_AHEAD !in byDefault.spoken)
        assertEquals(byDefault.events.groupingBy { it }.eachCount(), withPreview.events.groupingBy { it }.eachCount())
    }

    @Test
    fun `level 1 demo misses a turn signal`() {
        val (_, out) = play(File(assets, "replay/demo_l1_signal_miss.json"))
        assertTrue(out.toString(), DriveEventType.TURN_SIGNAL_MISSED in out.events)
        assertTrue(out.toString(), FeedbackType.TURN_SIGNAL_MISSING in out.spoken)
    }

    @Test
    fun `level 2 demo raises a new warning and hard brakes`() {
        val (_, out) = play(File(assets, "replay/demo_l2_warning_brake.json"))
        assertTrue(out.toString(), FeedbackType.NEW_WARNING in out.spoken)
        assertTrue(out.toString(), DriveEventType.HARD_BRAKE in out.events)
    }

    /**
     * 시연 로그는 자기 courseId 코스의 지점·경로로 만들어졌다. 같은 출발지의 다른 코스로 재생하면 신호 기반 발화(깜빡이·과속)는
     * 그대로 나오지만 기록(회전 판정·코스 이탈)은 엉뚱해진다. 그래서 앱은 코스에 맞는 로그만 고르게 해야 한다.
     */
    @Test
    fun `level 1 demo on another bundled course records wrong turn and off course results`() {
        val replay = File(assets, "replay/demo_l1_signal_miss.json")
        val other = bundledCourses().first { it.level == 1 && it.id != ReplayLogParser.parse(replay.readText()).let { r -> (r as AppResult.Ok).value.courseId } }
        val (_, out) = play(replay, other.id)
        assertTrue(out.toString(), FeedbackType.TURN_SIGNAL_MISSING in out.spoken)
        assertTrue(out.toString(), DriveEventType.OFF_COURSE in out.events)
    }
}
