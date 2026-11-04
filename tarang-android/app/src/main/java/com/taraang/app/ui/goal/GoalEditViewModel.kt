package com.taraang.app.ui.goal

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taraang.app.core.AppResult
import com.taraang.app.core.Clock
import com.taraang.app.core.Geo
import com.taraang.app.core.LatLng
import com.taraang.app.core.getOrNull
import com.taraang.app.core.userMessage
import com.taraang.app.di.AppContainer
import com.taraang.app.domain.model.GoalRoute
import com.taraang.app.domain.model.HardSpot
import com.taraang.app.domain.model.Skill
import com.taraang.app.ui.ReadFailure
import com.taraang.app.ui.UiConfig
import com.taraang.app.ui.guard
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class HardSpotForm(val key: String = UUID.randomUUID().toString(), val label: String = "", val lat: String = "", val lon: String = "")

@Serializable
data class GoalForm(
    val name: String = "",
    val originLat: String = "",
    val originLon: String = "",
    val destLat: String = "",
    val destLon: String = "",
    val skills: Map<Skill, Int> = emptyMap(),
    val hardSpots: List<HardSpotForm> = emptyList(),
)

/** 입력 칸별 오류 키. 화면이 문구로 바꾼다. */
enum class GoalFieldError { NAME_REQUIRED, TOO_LONG, NOT_A_NUMBER, OUTSIDE_KOREA, LABEL_REQUIRED }

/** 목표 경로 기술 분석(IDEA 3.1). 실패하면 그때만 손으로 고치게 한다. */
sealed interface RouteAnalysis {
    data object NotRun : RouteAnalysis
    data object Running : RouteAnalysis
    data object Done : RouteAnalysis
    data class Failed(val message: String) : RouteAnalysis
}

data class GoalEditUiState(
    val loading: Boolean = true,
    val existingId: String? = null,
    val createdAtMs: Long? = null,
    val form: GoalForm = GoalForm(),
    /** 필드 이름(예: "originLat", "spot:<key>:lat") → 오류. 저장을 누른 뒤에만 채운다. */
    val errors: Map<String, GoalFieldError> = emptyMap(),
    val saving: Boolean = false,
    val errorMessage: String? = null,
    val analysis: RouteAnalysis = RouteAnalysis.NotRun,
    /** 분석 뒤 출발·도착을 고쳤으면 true. 기술 목록은 남기되 다시 분석하라고 안내한다. */
    val analysisStale: Boolean = false,
) {
    val canAddSpot: Boolean get() = form.hardSpots.size < UiConfig.HARD_SPOTS_MAX

    /** 손으로 기술 수를 고치는 칸. 분석이 실패했을 때만 보여준다. */
    val manualSkillsEnabled: Boolean get() = analysis is RouteAnalysis.Failed
}

sealed interface GoalEditEvent {
    data object Done : GoalEditEvent
}

/**
 * 목표 경로 하나를 만들고 고친다. 입력 중인 값은 [SavedStateHandle]에 저장해
 * 프로세스가 죽었다 살아나도 이어서 쓸 수 있다(체크리스트 11).
 */
class GoalEditViewModel(
    private val container: AppContainer,
    private val handle: SavedStateHandle,
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val _state = MutableStateFlow(GoalEditUiState())
    val state: StateFlow<GoalEditUiState> = _state.asStateFlow()

    private val _events = Channel<GoalEditEvent>(Channel.BUFFERED)
    val events: Flow<GoalEditEvent> = _events.receiveAsFlow()

    private var analysisJob: Job? = null

    init {
        viewModelScope.launch {
            val read = container.logger.guard(TAG, "goals read") { container.goalRepository.goals.first().firstOrNull() }
            val goal = read.getOrNull()
            val draft = restoreDraft()
            val form = draft ?: goal?.toForm() ?: GoalForm()
            _state.update {
                it.copy(
                    loading = false,
                    existingId = goal?.id,
                    createdAtMs = goal?.createdAtMs,
                    form = form,
                    // 저장된 기술 목록은 예전 분석 결과다.
                    analysis = if (form.skills.isEmpty()) RouteAnalysis.NotRun else RouteAnalysis.Done,
                    // 읽기에 실패해도 새로 입력은 할 수 있다. 안내만 띄운다.
                    errorMessage = if (read is AppResult.Err) ReadFailure.userMessage() else null,
                )
            }
        }
    }

    fun edit(transform: (GoalForm) -> GoalForm) {
        _state.update { s ->
            val form = transform(s.form)
            val routeChanged = form.routeKey() != s.form.routeKey()
            if (routeChanged) analysisJob?.cancel()
            s.copy(
                form = form,
                // 이미 오류를 보여주는 중이면 고칠 때마다 다시 검사한다.
                errors = if (s.errors.isEmpty()) s.errors else validate(form),
                analysis = if (routeChanged && s.analysis == RouteAnalysis.Running) RouteAnalysis.NotRun else s.analysis,
                analysisStale = s.analysisStale || (routeChanged && s.form.skills.isNotEmpty()),
            )
        }
        saveDraft(_state.value.form)
    }

    fun setSkillCount(skill: Skill, count: Int) = edit { f ->
        f.copy(skills = if (count <= 0) f.skills - skill else f.skills + (skill to count.coerceAtMost(UiConfig.SKILL_COUNT_MAX)))
    }

    fun addHardSpot() = edit {
        if (it.hardSpots.size >= UiConfig.HARD_SPOTS_MAX) it else it.copy(hardSpots = it.hardSpots + HardSpotForm())
    }

    fun removeHardSpot(key: String) = edit { f -> f.copy(hardSpots = f.hardSpots.filterNot { it.key == key }) }

    fun updateHardSpot(key: String, transform: (HardSpotForm) -> HardSpotForm) = edit { f ->
        f.copy(hardSpots = f.hardSpots.map { if (it.key == key) transform(it) else it })
    }

    /** PT 시연 시나리오(IDEA 8장): 부산대 앞 출발 → 회사 지하주차장. 좌표를 채우고 바로 분석한다. */
    fun fillDemo() {
        val origin = container.courseRepository.demoOrigin()
        edit {
            GoalForm(
                name = "회사 지하주차장 가기",
                originLat = origin.lat.toString(),
                originLon = origin.lon.toString(),
                destLat = DEMO_DEST.lat.toString(),
                destLon = DEMO_DEST.lon.toString(),
                hardSpots = listOf(HardSpotForm(label = "지하주차장 입구", lat = DEMO_DEST.lat.toString(), lon = DEMO_DEST.lon.toString())),
            )
        }
        _state.update { it.copy(analysis = RouteAnalysis.NotRun, analysisStale = false) }
        analyzeRoute()
    }

    /**
     * 출발→도착 경로를 기술 단위로 나눠 form.skills를 채운다. 좌표가 잘못됐으면 그 칸 오류만 보여주고 부르지 않는다.
     * 실패하면 이전 기술 목록은 그대로 두고 손으로 고칠 수 있게 연다.
     */
    fun analyzeRoute() {
        val s = _state.value
        if (s.loading || s.analysis == RouteAnalysis.Running) return
        val pointErrors = mutableMapOf<String, GoalFieldError>()
        checkPoint(s.form.originLat, s.form.originLon, "originLat", "originLon", pointErrors)
        checkPoint(s.form.destLat, s.form.destLon, "destLat", "destLon", pointErrors)
        if (pointErrors.isNotEmpty()) {
            _state.update { it.copy(errors = it.errors + pointErrors) }
            return
        }
        val origin = LatLng(parseCoordinate(s.form.originLat)!!, parseCoordinate(s.form.originLon)!!)
        val destination = LatLng(parseCoordinate(s.form.destLat)!!, parseCoordinate(s.form.destLon)!!)
        _state.update { it.copy(analysis = RouteAnalysis.Running) }
        analysisJob = viewModelScope.launch {
            val result = container.logger.guard(TAG, "analyzeRoute") { container.courseRepository.analyzeRoute(origin, destination) }
            val outcome = when (result) {
                is AppResult.Err -> result
                is AppResult.Ok -> result.value
            }
            when (outcome) {
                is AppResult.Ok -> {
                    val skills = outcome.value.filterValues { it > 0 }
                    _state.update { it.copy(analysis = RouteAnalysis.Done, analysisStale = false) }
                    edit { it.copy(skills = skills) }
                }
                is AppResult.Err -> {
                    container.logger.w(TAG, "analyzeRoute failed: ${outcome.error.debugMessage}")
                    _state.update { it.copy(analysis = RouteAnalysis.Failed(outcome.error.userMessage())) }
                }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        val errors = validate(s.form)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(errors = errors) }
            return
        }
        val goal = toGoal(s.form, s.existingId ?: UUID.randomUUID().toString(), s.createdAtMs ?: clock.nowMs())
        _state.update { it.copy(saving = true, errors = emptyMap(), errorMessage = null) }
        viewModelScope.launch { finish(container.goalRepository.save(goal), "save") }
    }

    fun delete() {
        val id = _state.value.existingId ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch { finish(container.goalRepository.delete(id), "delete") }
    }

    private suspend fun finish(result: AppResult<Unit>, what: String) {
        when (result) {
            is AppResult.Ok -> {
                handle.remove<String>(DRAFT_KEY)
                _state.update { it.copy(saving = false) }
                _events.send(GoalEditEvent.Done)
            }
            is AppResult.Err -> {
                container.logger.w(TAG, "$what failed: ${result.error.debugMessage}")
                _state.update { it.copy(saving = false, errorMessage = result.error.userMessage()) }
            }
        }
    }

    private fun saveDraft(form: GoalForm) {
        handle[DRAFT_KEY] = json.encodeToString(form)
    }

    /** 저장해 둔 초안이 깨졌으면 버리고 저장소 값을 쓴다. */
    private fun restoreDraft(): GoalForm? {
        val raw = handle.get<String>(DRAFT_KEY) ?: return null
        return try {
            json.decodeFromString<GoalForm>(raw)
        } catch (e: IllegalArgumentException) {
            container.logger.w(TAG, "draft discarded", e)
            null
        }
    }

    companion object {
        private const val TAG = "GoalEditVM"
        private const val DRAFT_KEY = "goalDraft"
        private val DEMO_DEST = LatLng(35.2205, 129.0868)
        private val json = Json { ignoreUnknownKeys = true }

        fun validate(f: GoalForm): Map<String, GoalFieldError> {
            val errors = mutableMapOf<String, GoalFieldError>()
            when {
                f.name.isBlank() -> errors["name"] = GoalFieldError.NAME_REQUIRED
                f.name.trim().length > UiConfig.GOAL_NAME_MAX -> errors["name"] = GoalFieldError.TOO_LONG
            }
            checkPoint(f.originLat, f.originLon, "originLat", "originLon", errors)
            checkPoint(f.destLat, f.destLon, "destLat", "destLon", errors)
            f.hardSpots.forEach { spot ->
                val labelKey = "spot:${spot.key}:label"
                when {
                    spot.label.isBlank() -> errors[labelKey] = GoalFieldError.LABEL_REQUIRED
                    spot.label.trim().length > UiConfig.SPOT_LABEL_MAX -> errors[labelKey] = GoalFieldError.TOO_LONG
                }
                checkPoint(spot.lat, spot.lon, "spot:${spot.key}:lat", "spot:${spot.key}:lon", errors)
            }
            return errors
        }

        /** "NaN", "Infinity"도 toDouble은 받아주므로 유한한 수만 숫자로 친다. */
        fun parseCoordinate(raw: String): Double? = raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }

        internal fun checkPoint(lat: String, lon: String, latKey: String, lonKey: String, errors: MutableMap<String, GoalFieldError>) {
            val la = parseCoordinate(lat)
            val lo = parseCoordinate(lon)
            // 칸마다 따로 본다: 잘못 넣은 칸만 표시한다.
            when {
                la == null -> errors[latKey] = GoalFieldError.NOT_A_NUMBER
                la !in Geo.KOREA_LAT -> errors[latKey] = GoalFieldError.OUTSIDE_KOREA
            }
            when {
                lo == null -> errors[lonKey] = GoalFieldError.NOT_A_NUMBER
                lo !in Geo.KOREA_LON -> errors[lonKey] = GoalFieldError.OUTSIDE_KOREA
            }
        }

        /** [validate]를 통과한 폼만 넘긴다. */
        internal fun toGoal(f: GoalForm, id: String, createdAtMs: Long) = GoalRoute(
            id = id,
            name = f.name.trim(),
            origin = LatLng(parseCoordinate(f.originLat)!!, parseCoordinate(f.originLon)!!),
            destination = LatLng(parseCoordinate(f.destLat)!!, parseCoordinate(f.destLon)!!),
            skills = f.skills.filterValues { it > 0 },
            hardSpots = f.hardSpots.map {
                HardSpot(it.key, LatLng(parseCoordinate(it.lat)!!, parseCoordinate(it.lon)!!), it.label.trim())
            },
            createdAtMs = createdAtMs,
        )
    }
}

/** 분석 결과가 어느 경로의 것인지 가르는 값. 이름·지점 수정은 분석을 무효로 만들지 않는다. */
private fun GoalForm.routeKey() = listOf(originLat.trim(), originLon.trim(), destLat.trim(), destLon.trim())

private fun GoalRoute.toForm() = GoalForm(
    name = name,
    originLat = origin.lat.toString(),
    originLon = origin.lon.toString(),
    destLat = destination.lat.toString(),
    destLon = destination.lon.toString(),
    skills = skills,
    hardSpots = hardSpots.map { HardSpotForm(it.id, it.label, it.position.lat.toString(), it.position.lon.toString()) },
)
