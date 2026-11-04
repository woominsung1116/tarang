package com.taraang.app.ui.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taraang.app.core.AppResult
import com.taraang.app.core.getOrNull
import com.taraang.app.core.userMessage
import com.taraang.app.di.AppContainer
import com.taraang.app.domain.model.DriveSummary
import com.taraang.app.domain.model.Feeling
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.SegmentFeeling
import com.taraang.app.ui.guard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUiState(
    val loading: Boolean = true,
    val summary: DriveSummary? = null,
    val loadError: String? = null,
    /** maneuverIndex → 느낌. 탭 즉시 반영하고 저장한다. */
    val feelings: Map<Int, Feeling> = emptyMap(),
    val saveError: String? = null,
    /** 읽기 실패면 null. 다음 코스 제안은 코스 단계로 대신한다. */
    val progress: LevelProgress? = null,
)

class ReviewViewModel(
    private val container: AppContainer,
    private val sessionId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            when (val result = container.driveRepository.summary(sessionId)) {
                is AppResult.Ok -> _state.update {
                    it.copy(
                        loading = false,
                        summary = result.value,
                        feelings = result.value.feelings.associate { f -> f.maneuverIndex to f.feeling },
                    )
                }
                is AppResult.Err -> {
                    container.logger.w(TAG, "summary failed: ${result.error.debugMessage}")
                    _state.update { it.copy(loading = false, loadError = result.error.userMessage()) }
                }
            }
            val progress = container.logger.guard(TAG, "levelProgress") { container.driveRepository.levelProgress() }.getOrNull()
            _state.update { it.copy(progress = progress) }
        }
    }

    /** 구간마다 하나만 고른다. 저장은 전체 목록으로 덮어쓴다(선택 해제는 두지 않는다). */
    fun setFeeling(maneuverIndex: Int, feeling: Feeling) {
        val before = _state.value.feelings
        if (before[maneuverIndex] == feeling) return
        val after = before + (maneuverIndex to feeling)
        _state.update { it.copy(feelings = after, saveError = null) }
        viewModelScope.launch {
            val list = after.map { (index, f) -> SegmentFeeling(sessionId, index, f) }
            val result = container.driveRepository.saveFeelings(list)
            if (result is AppResult.Err) {
                container.logger.w(TAG, "saveFeelings failed: ${result.error.debugMessage}")
                _state.update { it.copy(saveError = result.error.userMessage()) }
            }
        }
    }

    private companion object {
        const val TAG = "ReviewVM"
    }
}
