package com.taraang.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.LatLng
import com.taraang.app.core.getOrNull
import com.taraang.app.core.userMessage
import com.taraang.app.di.AppContainer
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.GoalRoute
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.Skill
import com.taraang.app.ui.guard
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface CoursesState {
    data object Loading : CoursesState
    data class Loaded(val courses: List<Course>) : CoursesState
    data class Empty(val message: String?) : CoursesState
    data class Error(val message: String) : CoursesState
}

data class HomeUiState(
    val progress: LevelProgress? = null,
    /** progress를 한 번이라도 읽어 봤는지. false면 단계 카드를 자리표시로 그린다. */
    val progressLoaded: Boolean = false,
    /** 코스를 고르는 단계. 복기에서 "다음 단계"로 왔으면 progress.currentLevel보다 클 수 있다. */
    val level: Int = 1,
    val goal: GoalRoute? = null,
    val courses: CoursesState = CoursesState.Loading,
) {
    /** 서버 대신 캐시·번들 코스를 보여주는 중이면 안내 한 줄을 띄운다(체크리스트 22). */
    val showingSavedCourses: Boolean
        get() = (courses as? CoursesState.Loaded)?.courses?.any { it.source != CourseSource.SERVER } == true

    /** 목표 경로에 있는 기술. 코스 추천의 focus로 넘긴다. */
    val focus: Set<Skill> get() = goal.focusSkills()

    /** 이 코스가 연습시키는 목표 경로 기술(화면 순서). 없으면 빈 목록. */
    fun goalSkillsIn(course: Course): List<Skill> {
        val f = focus
        return Skill.entries.filter { it in f && (course.skillCounts[it] ?: 0) > 0 }
    }
}

private fun GoalRoute?.focusSkills(): Set<Skill> = this?.skills?.filterValues { it > 0 }?.keys.orEmpty()

/** @param requestedLevel 1..4면 그 단계 코스를, 그 밖(0 등)이면 현재 단계 코스를 보여준다. */
class HomeViewModel(
    private val container: AppContainer,
    requestedLevel: Int,
) : ViewModel() {
    private val levelOverride = requestedLevel.takeIf { it in 1..LevelProgress.MAX_LEVEL }

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var goalLoaded = false

    init {
        viewModelScope.launch {
            container.goalRepository.goals
                .map { it.firstOrNull() }
                .distinctUntilChanged()
                .catch { e ->
                    container.logger.w(TAG, "goals flow failed", e)
                    emit(null)
                }
                .collect { goal ->
                    val previous = _state.value.goal
                    // 출발지나 연습할 기술이 바뀌었을 때만 다시 추천받는다(이름만 고친 경우는 그대로).
                    val inputsChanged = !goalLoaded || goal?.origin != previous?.origin || goal.focusSkills() != previous.focusSkills()
                    goalLoaded = true
                    _state.update { it.copy(goal = goal) }
                    if (inputsChanged) loadCourses()
                }
        }
    }

    /** 화면에 다시 들어올 때(복기 후 등) 단계 진행만 새로 읽는다. */
    fun refreshProgress() {
        if (!goalLoaded) return
        viewModelScope.launch { readProgress() }
    }

    fun retry() = loadCourses()

    private fun loadCourses() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(courses = CoursesState.Loading) }
            val progress = readProgress()
            val level = (levelOverride ?: progress?.currentLevel ?: 1).coerceIn(1, LevelProgress.MAX_LEVEL)
            _state.update { it.copy(level = level) }
            val origin: LatLng = _state.value.goal?.origin ?: container.courseRepository.demoOrigin()
            val result = container.courseRepository.recommend(origin, level, _state.value.focus)
            _state.update { it.copy(courses = toCoursesState(result)) }
        }
    }

    private fun toCoursesState(result: AppResult<List<Course>>): CoursesState = when (result) {
        // 같은 id가 두 번 오면 목록 key가 충돌하므로 첫 번째만 남긴다.
        is AppResult.Ok -> result.value.distinctBy { it.id }.let {
            if (it.isEmpty()) CoursesState.Empty(null) else CoursesState.Loaded(it)
        }
        is AppResult.Err -> {
            container.logger.w(TAG, "recommend failed: ${result.error.debugMessage}")
            if (result.error is AppError.NoCourse) CoursesState.Empty(result.error.userMessage())
            else CoursesState.Error(result.error.userMessage())
        }
    }

    /** 실패하면 이전 값을 유지한다. 처음부터 실패면 null(단계 카드는 숨기고 1단계 코스를 보여준다). */
    private suspend fun readProgress(): LevelProgress? {
        val progress = container.logger.guard(TAG, "levelProgress") { container.driveRepository.levelProgress() }.getOrNull()
            ?: _state.value.progress
        _state.update { it.copy(progress = progress, progressLoaded = true) }
        return progress
    }

    private companion object {
        const val TAG = "HomeVM"
    }
}
