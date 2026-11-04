package com.taraang.app.ui.drive

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taraang.app.core.AppResult
import com.taraang.app.core.userMessage
import com.taraang.app.di.AppContainer
import com.taraang.app.domain.model.FeedbackType
import com.taraang.app.domain.repository.LiveDriveState
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.domain.signal.TurnKind
import com.taraang.app.domain.signal.TurnSignalState
import com.taraang.app.ui.UiConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 신호 하나의 화면 표시. AVAILABLE이 아니면(UNKNOWN 포함) 모두 UNAVAILABLE이다. */
enum class SignalDisplay { ACTIVE, IDLE, UNAVAILABLE }

/**
 * 주행 화면이 그리는 전부. 틱마다 바뀌는 원시 값(위치, 정밀 거리)은 담지 않아서
 * 보이는 값이 같으면 재구성이 일어나지 않는다(체크리스트 21).
 */
@Immutable
data class DriveUiState(
    val hasSession: Boolean = false,
    val ending: Boolean = false,
    val errorMessage: String? = null,
    /** 지금 크게 보여줄 문장. 새 경고가 켜져 있으면 경고 문장, 아니면 최근 피드백. null이면 기본 문장. */
    val message: String? = null,
    /** [message]가 차량 경고 문장인가(빨강 + 경고 아이콘으로 그린다). */
    val messageIsWarning: Boolean = false,
    val turnKind: TurnKind? = null,
    /** [UiConfig.TURN_DISTANCE_STEP_M] 단위로 반올림한 거리. 잘못된 값이면 null. */
    val turnDistanceM: Int? = null,
    val speedLimitKmh: Int? = null,
    val turnSignal: TurnSignalState? = null,
    val turnSignalDisplay: SignalDisplay = SignalDisplay.UNAVAILABLE,
    val brakeDisplay: SignalDisplay = SignalDisplay.UNAVAILABLE,
    val warningDisplay: SignalDisplay = SignalDisplay.UNAVAILABLE,
) {
    val anyUnavailable: Boolean
        get() = listOf(turnSignalDisplay, brakeDisplay, warningDisplay).any { it == SignalDisplay.UNAVAILABLE }
}

sealed interface DriveEvent {
    /** sessionId가 null이면 기록할 세션이 없었다는 뜻 → 홈으로. */
    data class Finished(val sessionId: String?) : DriveEvent
}

class DriveViewModel(private val container: AppContainer) : ViewModel() {
    private val controller = container.driveController

    /** 화면에서만 생기는 상태: 종료 중, 종료 오류, 피드백 표시 시간, 경고 띠. */
    internal data class Local(
        val ending: Boolean = false,
        val errorMessage: String? = null,
        val message: String? = null,
        val warningText: String? = null,
    )

    private val local = MutableStateFlow(Local())
    private var feedbackJob: Job? = null

    val state: StateFlow<DriveUiState> = combine(controller.state, local, ::toUiState)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, toUiState(controller.state.value, local.value))

    private val _events = Channel<DriveEvent>(Channel.BUFFERED)
    val events: Flow<DriveEvent> = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            controller.state.map { it.lastFeedback }.distinctUntilChanged().collect { feedback ->
                if (feedback == null) return@collect
                if (feedback.type == FeedbackType.NEW_WARNING) {
                    // 말하기 전에 이미 꺼졌으면 띄우지 않는다.
                    if (controller.state.value.newWarnings.isNotEmpty()) local.update { it.copy(warningText = feedback.text) }
                } else {
                    local.update { it.copy(message = feedback.text) }
                    feedbackJob?.cancel()
                    feedbackJob = launch {
                        delay(UiConfig.FEEDBACK_VISIBLE_MS)
                        local.update { it.copy(message = null) }
                    }
                }
            }
        }
        // 경고 표시는 새로 켜진 경고가 모두 꺼지면 내린다(ARCHITECTURE.md 5장).
        viewModelScope.launch {
            controller.state.map { it.newWarnings.isEmpty() }.distinctUntilChanged().filter { it }.collect {
                local.update { it.copy(warningText = null) }
            }
        }
        // 리플레이가 끝나면 사용자가 누르지 않아도 복기로 넘어간다.
        viewModelScope.launch {
            controller.state.map { it.finished }.distinctUntilChanged().filter { it }.first()
            endDrive()
        }
    }

    /** 여러 번 눌러도 stop()은 한 번만 부른다. */
    fun endDrive() {
        if (local.value.ending) return
        local.update { it.copy(ending = true, errorMessage = null) }
        viewModelScope.launch {
            val knownId = controller.state.value.session?.id
            val sessionId = when (val result = controller.stop()) {
                is AppResult.Ok -> result.value?.id ?: knownId
                is AppResult.Err -> {
                    container.logger.w(TAG, "stop failed: ${result.error.debugMessage}")
                    // 이벤트는 발생 즉시 저장되므로 세션 id가 있으면 복기는 그대로 연다.
                    if (knownId == null) {
                        local.update { it.copy(ending = false, errorMessage = result.error.userMessage()) }
                        return@launch
                    }
                    knownId
                }
            }
            _events.send(DriveEvent.Finished(sessionId))
        }
    }

    companion object {
        private const val TAG = "DriveVM"

        /** 화면 상태는 한 가지만: 새 경고가 켜져 있는 동안 메시지 자리는 경고가 차지한다(코칭은 음성으로만). */
        internal fun toUiState(live: LiveDriveState, local: Local): DriveUiState {
            val status = live.availability.status
            fun available(key: SignalKey) = status[key] == SignalStatus.AVAILABLE
            val turn = live.navi.nextTurn
            return DriveUiState(
                hasSession = live.session != null || live.finished,
                ending = local.ending,
                errorMessage = local.errorMessage,
                message = local.warningText ?: local.message,
                messageIsWarning = local.warningText != null,
                turnKind = turn?.kind,
                turnDistanceM = turn?.distanceM?.let(::roundDistance),
                speedLimitKmh = live.navi.speedLimitKmh?.takeIf { it in 1..200 },
                turnSignal = live.vehicle.turnSignal.takeIf { available(SignalKey.TURN_SIGNAL) },
                turnSignalDisplay = when {
                    !available(SignalKey.TURN_SIGNAL) -> SignalDisplay.UNAVAILABLE
                    live.vehicle.turnSignal == TurnSignalState.LEFT || live.vehicle.turnSignal == TurnSignalState.RIGHT -> SignalDisplay.ACTIVE
                    else -> SignalDisplay.IDLE
                },
                brakeDisplay = when {
                    !available(SignalKey.BRAKE) -> SignalDisplay.UNAVAILABLE
                    live.vehicle.brakePressed == true -> SignalDisplay.ACTIVE
                    else -> SignalDisplay.IDLE
                },
                warningDisplay = when {
                    !available(SignalKey.WARNINGS) -> SignalDisplay.UNAVAILABLE
                    live.vehicle.activeWarnings.isNotEmpty() -> SignalDisplay.ACTIVE
                    else -> SignalDisplay.IDLE
                },
            )
        }

        /** NaN·음수는 null. 그 밖은 10m 단위. */
        internal fun roundDistance(meters: Double): Int? {
            if (!meters.isFinite() || meters < 0) return null
            val step = UiConfig.TURN_DISTANCE_STEP_M
            return (meters / step).roundToInt() * step
        }
    }
}
