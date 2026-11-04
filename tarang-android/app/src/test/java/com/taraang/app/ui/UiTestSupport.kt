package com.taraang.app.ui

import com.taraang.app.core.AppResult
import com.taraang.app.core.LatLng
import com.taraang.app.core.Logger
import com.taraang.app.di.AppContainer
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.DriveSession
import com.taraang.app.domain.model.DriveSummary
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.GoalRoute
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.SegmentFeeling
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.model.Skill
import com.taraang.app.domain.repository.CourseRepository
import com.taraang.app.domain.repository.DriveController
import com.taraang.app.domain.repository.DriveRepository
import com.taraang.app.domain.repository.GoalRepository
import com.taraang.app.domain.repository.LiveDriveState
import com.taraang.app.domain.repository.LiveStart
import com.taraang.app.domain.repository.SettingsRepository
import com.taraang.app.domain.signal.NaviStart
import com.taraang.app.domain.signal.NavigationLauncher
import com.taraang.app.ui.preview.FakeCourseRepository
import com.taraang.app.ui.preview.FakeDriveController
import com.taraang.app.ui.preview.FakeDriveRepository
import com.taraang.app.ui.preview.FakeGoalRepository
import com.taraang.app.ui.preview.FakeNavigationLauncher
import com.taraang.app.ui.preview.FakeSettingsRepository
import com.taraang.app.ui.preview.PreviewData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/** viewModelScope(Dispatchers.Main)를 테스트 디스패처로 바꾼다. Unconfined라 launch가 바로 실행된다. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(val dispatcher: TestDispatcher = UnconfinedTestDispatcher()) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

/** ui/preview/Fakes.kt의 고정 가짜를 기본으로 쓰고, 테스트마다 필요한 것만 바꾼다. */
class TestContainer(
    override val courseRepository: CourseRepository = FakeCourseRepository(),
    override val goalRepository: GoalRepository = FakeGoalRepository(),
    override val driveRepository: DriveRepository = FakeDriveRepository(),
    override val settingsRepository: SettingsRepository = FakeSettingsRepository(),
    override val driveController: DriveController = FakeDriveController(),
    override val navigationLauncher: NavigationLauncher = FakeNavigationLauncher(),
) : AppContainer {
    override val logger: Logger = Logger.Noop
}

/** NavigationLauncher는 fun interface가 아니라(계약) 람다로 만드는 도우미를 둔다. */
fun navi(block: suspend (LatLng, List<LatLng>, String) -> AppResult<NaviStart>) = object : NavigationLauncher {
    override suspend fun startCourse(origin: LatLng, waypoints: List<LatLng>, title: String) = block(origin, waypoints, title)
}

/** 내비가 경로를 받았고 현재 위치는 [here](모르면 null). */
fun naviOk(here: LatLng? = null): AppResult<NaviStart> = AppResult.Ok(NaviStart(here))

class RecordingCourseRepository(
    var result: AppResult<List<Course>> = AppResult.Ok(listOf(PreviewData.course)),
    var analysis: suspend () -> AppResult<Map<Skill, Int>> = { AppResult.Ok(PreviewData.goal.skills) },
) : CourseRepository by FakeCourseRepository() {
    val calls = mutableListOf<Pair<LatLng, Int>>()
    val focuses = mutableListOf<Set<Skill>>()
    val analyzed = mutableListOf<Pair<LatLng, LatLng>>()
    override suspend fun recommend(origin: LatLng, level: Int, focus: Set<Skill>): AppResult<List<Course>> {
        calls += origin to level
        focuses += focus
        return result
    }
    override suspend fun analyzeRoute(origin: LatLng, destination: LatLng): AppResult<Map<Skill, Int>> {
        analyzed += origin to destination
        return analysis()
    }
}

class ScriptedDriveRepository(
    var summaryResult: AppResult<DriveSummary> = AppResult.Ok(PreviewData.summary),
    var progress: () -> LevelProgress = { PreviewData.progress },
    var saveResult: AppResult<Unit> = AppResult.Ok(Unit),
    var deleteHistory: () -> AppResult<Unit> = { AppResult.Ok(Unit) },
) : DriveRepository by FakeDriveRepository() {
    val saved = mutableListOf<List<SegmentFeeling>>()
    var deleteCalls = 0
    override suspend fun deleteAllHistory(): AppResult<Unit> {
        deleteCalls++
        return deleteHistory()
    }
    override suspend fun summary(sessionId: String) = summaryResult
    override suspend fun levelProgress(): LevelProgress = progress()
    override suspend fun saveFeelings(feelings: List<SegmentFeeling>): AppResult<Unit> {
        saved += feelings
        return saveResult
    }
}

class ScriptedDriveController(
    var assets: () -> List<String> = { listOf("replay/demo_l2.json") },
    var demoIds: () -> Set<String> = { emptySet() },
    var startResult: AppResult<DriveSession> = AppResult.Ok(PreviewData.session),
    /** 기본은 주행 없음. 주행 중을 흉내 내려면 session(endedAtMs = null)을 넣는다. */
    val live: MutableStateFlow<LiveDriveState> = MutableStateFlow(LiveDriveState()),
) : DriveController by FakeDriveController() {
    override val state: StateFlow<LiveDriveState> = live
    val starts = mutableListOf<Triple<String, SignalMode, String?>>()
    val liveStarts = mutableListOf<LiveStart>()
    val replayQueries = mutableListOf<String>()
    override suspend fun replayAssets(courseId: String): List<String> {
        replayQueries += courseId
        return assets()
    }
    override suspend fun demoCourseIds(): Set<String> = demoIds()

    /** prepareLiveStart / cancelPreparedStart 호출 순서. */
    val keepAliveCalls = mutableListOf<String>()
    override fun prepareLiveStart() { keepAliveCalls += "prepare" }
    override fun cancelPreparedStart() { keepAliveCalls += "cancel" }
    override suspend fun start(course: Course, mode: SignalMode, replayAsset: String?, liveStart: LiveStart): AppResult<DriveSession> {
        liveStarts += liveStart
        starts += Triple(course.id, mode, replayAsset)
        return startResult
    }
}

class EmptyGoalRepository : GoalRepository {
    val state = MutableStateFlow<List<GoalRoute>>(emptyList())
    override val goals: Flow<List<GoalRoute>> = state
    override suspend fun save(goal: GoalRoute): AppResult<Unit> {
        state.value = listOf(goal)
        return AppResult.Ok(Unit)
    }
    override suspend fun delete(goalId: String): AppResult<Unit> = AppResult.Ok(Unit)
}

/** 실제 저장소처럼 허용되지 않는 주소는 버리고 이전 값을 유지하는 설정 저장소. */
class PolicySettingsRepository(private val accept: (String) -> Boolean = { true }) : SettingsRepository {
    val state = MutableStateFlow(FeedbackSettings())
    var failWith: Exception? = null
    override val settings: Flow<FeedbackSettings> = state
    override suspend fun update(transform: (FeedbackSettings) -> FeedbackSettings) {
        failWith?.let { throw it }
        val next = transform(state.value)
        state.value = if (accept(next.courseServerUrl)) next else next.copy(courseServerUrl = state.value.courseServerUrl)
    }
}
