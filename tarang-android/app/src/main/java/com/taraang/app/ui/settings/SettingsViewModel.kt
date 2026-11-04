package com.taraang.app.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taraang.app.BuildConfig
import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.userMessage
import com.taraang.app.di.AppContainer
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.FeedbackType
import com.taraang.app.ui.ReadFailure
import com.taraang.app.ui.UiConfig
import com.taraang.app.ui.guard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URISyntaxException

enum class UrlStatus { IDLE, INVALID, SAVED }

/** 릴리스 빌드에서 주소 대신 보여주는 서버 상태. 주소 자체는 화면에 내보이지 않는다. */
enum class ServerLabel { DEFAULT, CUSTOM, OFFLINE }

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: FeedbackSettings = FeedbackSettings(),
    /** 입력 중인 서버 주소. 저장 버튼을 눌러야 반영한다. */
    val urlDraft: String = "",
    val urlStatus: UrlStatus = UrlStatus.IDLE,
    val savingUrl: Boolean = false,
    /** 서버 주소를 고칠 수 있는지. 디버그 빌드만 true(보안 검토 M3). */
    val urlEditable: Boolean = false,
    /** 이 빌드의 기본 주소(BuildConfig.COURSE_SERVER_URL). release는 빈 값 = 서버 안 씀. */
    val defaultServerUrl: String = FeedbackSettings.DEFAULT_SERVER_URL,
    val deletingHistory: Boolean = false,
    val historyDeleted: Boolean = false,
    /** 진행 중인 주행이 있다(DriveController.state). 기록 지우기를 막는다. */
    val driving: Boolean = false,
    /** 화면이 주행 시작을 알기 전에 눌러 저장소가 주행 중이라 거절한 경우. */
    val deleteRefusedWhileDriving: Boolean = false,
    val errorMessage: String? = null,
) {
    /** "주행 중에는 지울 수 없어요"를 보이고 버튼을 끈다. */
    val historyLocked: Boolean get() = driving || deleteRefusedWhileDriving

    val serverLabel: ServerLabel
        get() = when (settings.courseServerUrl.trim()) {
            "" -> ServerLabel.OFFLINE
            defaultServerUrl.trim() -> ServerLabel.DEFAULT
            else -> ServerLabel.CUSTOM
        }
}

/**
 * 피드백 켜기·끄기와 코스 서버 주소. 서버 주소의 최종 판정은 저장소가 한다(통과 못 하면 이전 값 유지).
 * 화면은 같은 규칙으로 먼저 걸러 바로 안내하고, 저장 뒤 저장소가 실제로 남긴 값을 읽어 결과를 보여준다.
 * 입력 중인 주소는 [SavedStateHandle]에 둬 프로세스가 죽었다 살아나도 이어서 쓴다(체크리스트 11).
 * 릴리스 빌드는 주소를 바꿀 수 없다([urlEditable] = false). 화면을 숨기는 것과 별개로 저장 경로도 막는다.
 */
class SettingsViewModel(
    private val container: AppContainer,
    private val handle: SavedStateHandle,
    urlEditable: Boolean = BuildConfig.DEBUG,
    defaultServerUrl: String = BuildConfig.COURSE_SERVER_URL,
) : ViewModel() {
    private val _state = MutableStateFlow(
        SettingsUiState(urlDraft = handle[URL_DRAFT_KEY] ?: "", urlEditable = urlEditable, defaultServerUrl = defaultServerUrl),
    )
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.driveController.state
                .map { it.session != null && it.session.endedAtMs == null && !it.finished }
                .distinctUntilChanged()
                .collect { driving ->
                    _state.update { it.copy(driving = driving, deleteRefusedWhileDriving = it.deleteRefusedWhileDriving && driving) }
                }
        }
        viewModelScope.launch {
            container.settingsRepository.settings
                .catch { e ->
                    container.logger.w(TAG, "settings flow failed", e)
                    _state.update { it.copy(errorMessage = ReadFailure.userMessage()) }
                    emit(FeedbackSettings())
                }
                .collect { settings ->
                    _state.update { s ->
                        s.copy(
                            loading = false,
                            settings = settings,
                            // 첫 값만 입력칸에 넣는다. 복원한 초안이 있으면 그것을 이어 쓴다.
                            urlDraft = if (s.loading && handle.get<String>(URL_DRAFT_KEY) == null) settings.courseServerUrl else s.urlDraft,
                        )
                    }
                }
        }
    }

    fun setEnabled(type: FeedbackType, enabled: Boolean) {
        if (!type.userToggleable) return
        persist { it.copy(enabled = it.enabled + (type to enabled)) }
    }

    fun setVoice(enabled: Boolean) = persist { it.copy(voiceEnabled = enabled) }

    fun editUrl(value: String) {
        if (!_state.value.urlEditable) return
        // 아주 긴 붙여넣기는 한도+1자에서 자른다. 저장은 어차피 거부되고, 상태 저장 크기를 묶어 둔다.
        val draft = value.take(UiConfig.SERVER_URL_MAX + 1)
        handle[URL_DRAFT_KEY] = draft
        _state.update { it.copy(urlDraft = draft, urlStatus = UrlStatus.IDLE) }
    }

    fun resetUrl() = editUrl(_state.value.defaultServerUrl)

    fun saveUrl() {
        val s = _state.value
        if (!s.urlEditable || s.savingUrl || s.loading) return
        val url = normalizeUrl(s.urlDraft)
        if (url == null) {
            _state.update { it.copy(urlStatus = UrlStatus.INVALID) }
            return
        }
        val previous = s.settings.courseServerUrl
        _state.update { it.copy(savingUrl = true, urlStatus = UrlStatus.IDLE) }
        viewModelScope.launch {
            val stored = container.logger.guard(TAG, "server url save") {
                container.settingsRepository.update { it.copy(courseServerUrl = url) }
                container.settingsRepository.settings.first().courseServerUrl
            }
            _state.update { it.copy(savingUrl = false) }
            when {
                stored is AppResult.Err -> _state.update { it.copy(errorMessage = SaveFailureMessage) }
                // 저장소가 표기를 조금 바꿔 저장할 수 있다(예: 기본 포트 생략). 바뀌었으면 저장된 것이다.
                stored is AppResult.Ok && (stored.value == url || stored.value != previous) -> {
                    handle.remove<String>(URL_DRAFT_KEY)
                    _state.update { it.copy(urlDraft = stored.value, urlStatus = UrlStatus.SAVED) }
                }
                else -> {
                    container.logger.w(TAG, "server url not kept by repository")
                    _state.update { it.copy(urlStatus = UrlStatus.INVALID) }
                }
            }
        }
    }

    private fun persist(transform: (FeedbackSettings) -> FeedbackSettings) {
        viewModelScope.launch {
            val result = container.logger.guard(TAG, "settings update") { container.settingsRepository.update(transform) }
            if (result is AppResult.Err) _state.update { it.copy(errorMessage = SaveFailureMessage) }
        }
    }

    /** 주행 기록(세션·이벤트·느낌)만 지운다. 목표 경로와 설정은 남는다. 확인 대화상자를 거친 뒤 부른다. */
    fun deleteAllHistory() {
        if (_state.value.deletingHistory || _state.value.historyLocked) return
        _state.update { it.copy(deletingHistory = true, historyDeleted = false, errorMessage = null) }
        viewModelScope.launch {
            val result = container.logger.guard(TAG, "deleteAllHistory") { container.driveRepository.deleteAllHistory() }
            // guard는 예외만 Err로 바꾼다. 저장소가 돌려준 Err(예: 주행 중)는 안쪽 값에 있다.
            val error = when (result) {
                is AppResult.Err -> result.error
                is AppResult.Ok -> (result.value as? AppResult.Err)?.error
            }
            if (error != null) container.logger.w(TAG, "deleteAllHistory failed: ${error.debugMessage}")
            // 저장소 계약: 진행 중인 주행이 있으면 InvalidInput. 공용 문구("입력값을 확인해 주세요") 대신 주행 중 안내를 쓴다.
            val refusedWhileDriving = error is AppError.InvalidInput
            _state.update {
                it.copy(
                    deletingHistory = false,
                    historyDeleted = error == null,
                    deleteRefusedWhileDriving = refusedWhileDriving,
                    errorMessage = if (refusedWhileDriving) null else error?.userMessage(),
                )
            }
        }
    }

    fun dismissHistoryDeleted() = _state.update { it.copy(historyDeleted = false) }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    companion object {
        private const val TAG = "SettingsVM"
        private const val URL_DRAFT_KEY = "serverUrlDraft"
        private val SaveFailureMessage = AppError.Storage("settings write").userMessage()

        /**
         * 저장소(ServerUrlPolicy)와 같은 규칙으로 먼저 거른다: http/https만, 평문 http는 [UiConfig.PLAINTEXT_HOSTS]만,
         * 계정 정보·쿼리·프래그먼트 금지, [UiConfig.SERVER_URL_MAX]자 이하. 통과하면 scheme·host 소문자,
         * 기본 포트와 끝의 '/'를 뗀 주소, 아니면 null.
         */
        fun normalizeUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > UiConfig.SERVER_URL_MAX || trimmed.any { it.isWhitespace() }) return null
            val uri = try {
                URI(trimmed)
            } catch (e: URISyntaxException) {
                return null
            }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http" && scheme != "https") return null
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            if (scheme == "http" && host !in UiConfig.PLAINTEXT_HOSTS) return null
            val port = when (uri.port) {
                -1, defaultPort(scheme) -> ""
                in 1..65_535 -> ":${uri.port}"
                else -> return null
            }
            return "$scheme://$host$port${uri.rawPath.orEmpty()}".trimEnd('/')
        }

        private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80
    }
}
