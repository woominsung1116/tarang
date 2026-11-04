package com.taraang.app.vehicle

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Clock
import com.taraang.app.core.Logger
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.signal.NaviSnapshot
import com.taraang.app.domain.signal.SignalAvailability
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.SignalSource
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.domain.signal.VehicleSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** assets/replay 로그 읽기. 테스트에서는 메모리 구현을 쓴다. */
interface ReplayAssets {
    fun list(): List<String>
    suspend fun read(name: String): String?
}

/**
 * 에뮬레이터 시연용 신호 소스. 로그 프레임을 [Clock] 기준 시각에 맞춰 내보내고, 끝나면 [finished]를 올린다.
 * 리플레이에 없는 신호(전조등)는 UNSUPPORTED로 둔다.
 */
class ReplaySignalSource(
    private val assets: ReplayAssets,
    private val assetName: String,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val logger: Logger,
) : SignalSource {

    override val mode = SignalMode.REPLAY

    private val _vehicle = MutableStateFlow(VehicleSnapshot())
    private val _navi = MutableStateFlow(NaviSnapshot())
    private val _availability = MutableStateFlow(SignalAvailability())
    private val _finished = MutableStateFlow(false)

    override val vehicle: StateFlow<VehicleSnapshot> = _vehicle.asStateFlow()
    override val navi: StateFlow<NaviSnapshot> = _navi.asStateFlow()
    override val availability: StateFlow<SignalAvailability> = _availability.asStateFlow()
    override val finished: StateFlow<Boolean> = _finished.asStateFlow()

    private var job: Job? = null

    override suspend fun start(): AppResult<Unit> {
        if (job != null) return AppResult.Err(AppError.InvalidInput("이미 재생 중"))
        val text = try {
            assets.read(assetName)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "리플레이 파일 읽기 실패", e)
            null
        } ?: return AppResult.Err(AppError.InvalidInput("리플레이 파일 없음: $assetName"))

        val log = when (val parsed = ReplayLogParser.parse(text)) {
            is AppResult.Ok -> parsed.value
            is AppResult.Err -> return parsed
        }
        _availability.value = SignalAvailability(
            SignalKey.entries.associateWith { if (it == SignalKey.LOW_BEAM) SignalStatus.UNSUPPORTED else SignalStatus.AVAILABLE },
        )
        _finished.value = false
        val startMs = clock.nowMs()
        job = scope.launch {
            for (frame in log.frames) {
                val at = startMs + frame.offsetMs
                val wait = at - clock.nowMs()
                if (wait > 0) delay(wait)
                _vehicle.value = frame.vehicle.copy(timestampMs = at)
                _navi.value = frame.navi.copy(timestampMs = at)
            }
            _finished.value = true
        }
        logger.d(TAG, "리플레이 시작: $assetName, 프레임 ${log.frames.size}개")
        return AppResult.Ok(Unit)
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val TAG = "Replay"
    }
}
