package com.taraang.app.ui.preview

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.LatLng
import com.taraang.app.core.Logger
import com.taraang.app.di.AppContainer
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.DataConfidence
import com.taraang.app.domain.model.DriveEvent
import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.model.DriveSession
import com.taraang.app.domain.model.DriveSummary
import com.taraang.app.domain.model.FeedbackMessage
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.FeedbackType
import com.taraang.app.domain.model.Feeling
import com.taraang.app.domain.model.GoalRoute
import com.taraang.app.domain.model.HardSpot
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.model.SegmentFeeling
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.model.Skill
import com.taraang.app.domain.repository.CourseRepository
import com.taraang.app.domain.repository.DriveController
import com.taraang.app.domain.repository.DriveRepository
import com.taraang.app.domain.repository.GoalRepository
import com.taraang.app.domain.repository.LiveDriveState
import com.taraang.app.domain.repository.SettingsRepository
import com.taraang.app.domain.signal.NavigationLauncher
import com.taraang.app.domain.signal.NaviSnapshot
import com.taraang.app.domain.signal.NextTurn
import com.taraang.app.domain.signal.SignalAvailability
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.domain.signal.TurnKind
import com.taraang.app.domain.signal.TurnSignalState
import com.taraang.app.domain.signal.VehicleSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** @Preview 전용 고정 데이터. 부산대 앞 순환 코스 모양. */
object PreviewData {
    private const val NOW = 1_761_120_000_000L
    val origin = LatLng(35.2310, 129.0840)

    private val path = listOf(
        LatLng(35.2310, 129.0840), LatLng(35.2318, 129.0846), LatLng(35.2329, 129.0851),
        LatLng(35.2336, 129.0862), LatLng(35.2331, 129.0877), LatLng(35.2320, 129.0881),
        LatLng(35.2309, 129.0874), LatLng(35.2302, 129.0861), LatLng(35.2304, 129.0848),
        LatLng(35.2310, 129.0840),
    )

    val course = Course(
        id = "L2-35.2310-129.0840-s1",
        level = 2,
        title = "부산대 앞 신호 좌회전 연습",
        distanceM = 2810,
        origin = origin,
        path = path,
        waypoints = listOf(path[3], path[6]),
        maneuvers = listOf(
            Maneuver(0, Skill.RIGHT_TURN, "금정로", path[2], 320.0, DataConfidence.VERIFIED),
            Maneuver(1, Skill.LEFT_TURN_SIGNALIZED, "부산대학로", path[4], 910.0, DataConfidence.VERIFIED),
            Maneuver(2, Skill.SCHOOL_ZONE, "장전로", path[6], 1500.0, DataConfidence.ESTIMATED),
            Maneuver(3, Skill.LEFT_TURN_SIGNALIZED, "중앙대로", path[8], 2300.0, DataConfidence.VERIFIED),
        ),
        skillCounts = mapOf(Skill.LEFT_TURN_SIGNALIZED to 2, Skill.RIGHT_TURN to 1, Skill.SCHOOL_ZONE to 1),
        warnings = listOf("신호 정보 없는 좌회전 1곳 — 출발 전 확인"),
        source = CourseSource.SERVER,
    )

    val progress = LevelProgress(currentLevel = 2, completedCoursesAtLevel = 1, requiredToAdvance = 3)

    val goal = GoalRoute(
        id = "goal-1",
        name = "회사 지하주차장 가기",
        origin = origin,
        destination = LatLng(35.2205, 129.0868),
        skills = mapOf(Skill.LEFT_TURN_SIGNALIZED to 2, Skill.MERGE to 1, Skill.UNDERGROUND_PARKING to 1),
        hardSpots = listOf(HardSpot("h1", LatLng(35.2206, 129.0866), "지하주차장 입구")),
        createdAtMs = NOW,
    )

    val session = DriveSession("s-2", course.id, SignalMode.REPLAY, NOW, NOW + 14 * 60_000, interrupted = false)
    private val previousSession = session.copy(id = "s-1", startedAtMs = NOW - 7 * 86_400_000L, endedAtMs = NOW - 7 * 86_400_000L + 15 * 60_000)

    val events = listOf(
        DriveEvent(session.id, DriveEventType.TURN_SIGNAL_OK, NOW + 120_000, path[2], 0, ""),
        DriveEvent(session.id, DriveEventType.TURN_SIGNAL_MISSED, NOW + 300_000, path[4], 1, ""),
        DriveEvent(session.id, DriveEventType.TURN_SIGNAL_OK, NOW + 600_000, path[8], 3, ""),
        DriveEvent(session.id, DriveEventType.NEW_WARNING, NOW + 700_000, path[8], null, "타이어 공기압"),
    )

    val summary = DriveSummary(
        session = session,
        course = course,
        events = events,
        feelings = listOf(SegmentFeeling(session.id, 1, Feeling.TENSE)),
        turnSignalOk = 2,
        turnSignalTotal = 3,
        previous = DriveSummary(
            session = previousSession,
            course = course,
            events = listOf(DriveEvent(previousSession.id, DriveEventType.SPEEDING, NOW, path[5], null, "")),
            feelings = emptyList(),
            turnSignalOk = 1,
            turnSignalTotal = 3,
            previous = null,
        ),
    )

    val liveState = LiveDriveState(
        session = session,
        vehicle = VehicleSnapshot(NOW, turnSignal = TurnSignalState.OFF, brakePressed = false),
        navi = NaviSnapshot(NOW, position = path[3], speedLimitKmh = 50, nextTurn = NextTurn(TurnKind.LEFT, 180.0)),
        availability = SignalAvailability(
            mapOf(
                SignalKey.TURN_SIGNAL to SignalStatus.AVAILABLE,
                SignalKey.BRAKE to SignalStatus.UNSUPPORTED,
                SignalKey.WARNINGS to SignalStatus.AVAILABLE,
            ),
        ),
        lastFeedback = FeedbackMessage(FeedbackType.SKILL_PREVIEW, "다음 교차로가 오늘 연습할 좌회전이에요.", NOW),
    )
}

class FakeCourseRepository(private val result: AppResult<List<Course>> = AppResult.Ok(listOf(PreviewData.course))) :
    CourseRepository {
    override suspend fun recommend(origin: LatLng, level: Int, focus: Set<Skill>): AppResult<List<Course>> = result
    override suspend fun analyzeRoute(origin: LatLng, destination: LatLng): AppResult<Map<Skill, Int>> =
        AppResult.Ok(PreviewData.goal.skills)
    override suspend fun get(courseId: String): Course? = (result as? AppResult.Ok)?.value?.firstOrNull { it.id == courseId }
    override fun demoOrigin(): LatLng = PreviewData.origin
}

class FakeGoalRepository : GoalRepository {
    private val state = MutableStateFlow(listOf(PreviewData.goal))
    override val goals: Flow<List<GoalRoute>> = state
    override suspend fun save(goal: GoalRoute): AppResult<Unit> {
        state.value = listOf(goal)
        return AppResult.Ok(Unit)
    }
    override suspend fun delete(goalId: String): AppResult<Unit> {
        state.update { list -> list.filterNot { it.id == goalId } }
        return AppResult.Ok(Unit)
    }
}

class FakeDriveRepository : DriveRepository {
    override val sessions: Flow<List<DriveSession>> = MutableStateFlow(listOf(PreviewData.session))
    override suspend fun startSession(courseId: String, mode: SignalMode) = AppResult.Ok(PreviewData.session)
    override suspend fun appendEvent(event: DriveEvent): AppResult<Unit> = AppResult.Ok(Unit)
    override suspend fun finishSession(sessionId: String, drivenAlongCourseM: Double?) = AppResult.Ok(PreviewData.session)
    override suspend fun saveFeelings(feelings: List<SegmentFeeling>): AppResult<Unit> = AppResult.Ok(Unit)
    override suspend fun summary(sessionId: String): AppResult<DriveSummary> = AppResult.Ok(PreviewData.summary)
    override suspend fun levelProgress(): LevelProgress = PreviewData.progress
    override suspend fun closeInterruptedSessions(): Int = 0
    override suspend fun deleteAllHistory(): AppResult<Unit> = AppResult.Ok(Unit)
}

class FakeSettingsRepository : SettingsRepository {
    private val state = MutableStateFlow(FeedbackSettings())
    override val settings: Flow<FeedbackSettings> = state
    override suspend fun update(transform: (FeedbackSettings) -> FeedbackSettings) = state.update(transform)
}

class FakeDriveController : DriveController {
    private val live = MutableStateFlow(PreviewData.liveState)
    override val state: StateFlow<LiveDriveState> = live
    override suspend fun start(course: Course, mode: SignalMode, replayAsset: String?) = AppResult.Ok(PreviewData.session)
    override suspend fun stop(): AppResult<DriveSession?> = AppResult.Ok(PreviewData.session)
    override fun replayAssets(): List<String> = listOf("replay/demo_l2.json")
}

class FakeNavigationLauncher : NavigationLauncher {
    override suspend fun startCourse(origin: LatLng, waypoints: List<LatLng>, title: String): AppResult<Unit> =
        AppResult.Err(AppError.Navigation("PREVIEW", "no navi in preview"))
}

class FakeAppContainer : AppContainer {
    override val logger: Logger = Logger.Noop
    override val courseRepository: CourseRepository = FakeCourseRepository()
    override val goalRepository: GoalRepository = FakeGoalRepository()
    override val driveRepository: DriveRepository = FakeDriveRepository()
    override val settingsRepository: SettingsRepository = FakeSettingsRepository()
    override val driveController: DriveController = FakeDriveController()
    override val navigationLauncher: NavigationLauncher = FakeNavigationLauncher()
}
