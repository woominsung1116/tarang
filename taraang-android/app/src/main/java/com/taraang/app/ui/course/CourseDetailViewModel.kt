package com.taraang.app.ui.course

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.getOrNull
import com.taraang.app.core.userMessage
import com.taraang.app.di.AppContainer
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.ui.guard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class StartAction { NAVI, REPLAY }

data class CourseDetailUiState(
    val loading: Boolean = true,
    val course: Course? = null,
    /** 진행 중인 시작 동작. 버튼 로딩 표시와 중복 탭 방지에 쓴다. */
    val starting: StartAction? = null,
    val errorMessage: String? = null,
    /** 실차 연결(내비·차량 신호)이 안 됐을 때 "시연 모드로 보기"를 함께 보여준다(체크리스트 22). */
    val offerReplay: Boolean = false,
    /** 시연 로그가 하나도 없을 때. */
    val replayMissing: Boolean = false,
    /** 시연 로그가 여러 개면 고르게 한다. */
    val replayChoices: List<String> = emptyList(),
)

sealed interface CourseDetailEvent {
    data object DriveStarted : CourseDetailEvent
}

class CourseDetailViewModel(
    private val container: AppContainer,
    private val courseId: String,
    /** replayAssets()는 assets 목록을 읽으므로 메인 밖에서 부른다. 테스트에서 교체한다. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(CourseDetailUiState())
    val state: StateFlow<CourseDetailUiState> = _state.asStateFlow()

    private val _events = Channel<CourseDetailEvent>(Channel.BUFFERED)
    val events: Flow<CourseDetailEvent> = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            // courseId에는 좌표가 들어 있어 로그에 남기지 않는다(체크리스트 19).
            val course = container.logger.guard(TAG, "course get") { container.courseRepository.get(courseId) }.getOrNull()
            if (course == null) container.logger.w(TAG, "course not found")
            _state.update { it.copy(loading = false, course = course) }
        }
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null, offerReplay = false, replayMissing = false) }

    /** 내비에 코스를 넘긴 뒤 실제 차량 신호로 주행을 시작한다. 내비 실패 시 주행을 시작하지 않는다. */
    fun startWithNavigation() {
        val course = claimStart(StartAction.NAVI) ?: return
        viewModelScope.launch {
            val navi = container.navigationLauncher.startCourse(course.origin, course.waypoints, course.title)
            if (navi is AppResult.Err) {
                fail(navi.error)
                return@launch
            }
            startDrive(course, SignalMode.LIVE, null)
        }
    }

    /** 로그가 하나면 바로, 여러 개면 고르게 한다. */
    fun startReplay() {
        val course = claimStart(StartAction.REPLAY) ?: return
        viewModelScope.launch {
            val assets = replayAssets()
            when {
                assets.isEmpty() -> {
                    container.logger.w(TAG, "no replay assets")
                    _state.update { it.copy(starting = null, replayMissing = true) }
                }
                assets.size == 1 -> startDrive(course, SignalMode.REPLAY, assets.first())
                else -> _state.update { it.copy(starting = null, replayChoices = assets) }
            }
        }
    }

    /** 실차 시작 실패 뒤 "시연 모드로 보기": 첫 시연 로그로 바로 시작한다. */
    fun startFallbackReplay() {
        val course = claimStart(StartAction.REPLAY) ?: return
        viewModelScope.launch {
            val asset = replayAssets().firstOrNull()
            if (asset == null) {
                _state.update { it.copy(starting = null, replayMissing = true, offerReplay = false) }
            } else {
                startDrive(course, SignalMode.REPLAY, asset)
            }
        }
    }

    fun dismissReplayChoices() = _state.update { it.copy(replayChoices = emptyList()) }

    fun chooseReplay(asset: String) {
        val course = claimStart(StartAction.REPLAY) ?: return
        _state.update { it.copy(replayChoices = emptyList()) }
        viewModelScope.launch { startDrive(course, SignalMode.REPLAY, asset) }
    }

    /** 시작 중이 아니면 즉시 시작 상태로 바꾸고 코스를 돌려준다. 빠른 두 번 탭의 두 번째는 null. */
    private fun claimStart(action: StartAction): Course? {
        val current = _state.value
        val course = current.course ?: return null
        if (current.starting != null) return null
        _state.update {
            it.copy(starting = action, errorMessage = null, offerReplay = false, replayMissing = false)
        }
        return course
    }

    private suspend fun replayAssets(): List<String> =
        container.logger.guard(TAG, "replayAssets") {
            withContext(io) { container.driveController.replayAssets() }
        }.getOrNull().orEmpty()

    private suspend fun startDrive(course: Course, mode: SignalMode, asset: String?) {
        when (val result = container.driveController.start(course, mode, asset)) {
            is AppResult.Ok -> {
                _state.update { it.copy(starting = null) }
                _events.send(CourseDetailEvent.DriveStarted)
            }
            is AppResult.Err -> fail(result.error)
        }
    }

    private fun fail(error: AppError) {
        container.logger.w(TAG, "start failed: ${error.debugMessage}")
        val carNotReady = error is AppError.Navigation || error is AppError.SignalUnavailable
        _state.update { it.copy(starting = null, errorMessage = error.userMessage(), offerReplay = carNotReady) }
    }

    private companion object {
        const val TAG = "CourseDetailVM"
    }
}
