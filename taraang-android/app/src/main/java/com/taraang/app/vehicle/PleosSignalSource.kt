package com.taraang.app.vehicle

import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Clock
import com.taraang.app.core.LatLng
import com.taraang.app.core.Logger
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.domain.signal.NaviSnapshot
import com.taraang.app.domain.signal.SignalAvailability
import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.SignalSource
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.domain.signal.VehicleSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 실차(LIVE) 신호 소스: Pleos Vehicle + NaviHelper.
 *
 * - 신호별로 capability를 확인해 [SignalStatus]를 정하고, AVAILABLE인 것만 구독한다.
 * - 신호 하나가 실패(권한·미지원·시간 초과·구독 중 오류)해도 그 신호만 상태를 바꾸고 나머지는 계속 흐른다.
 * - 모든 신호가 막혔을 때만 [start]가 Err를 돌려준다.
 * - SDK 콜백은 바인더/SDK 스레드에서 오므로 StateFlow.update로만 쓴다.
 * - 차속 API가 없어서 위치를 받은 시각만 [NaviSnapshot.timestampMs]에 쓴다(엔진이 속도를 추정할 때 기준).
 */
class PleosSignalSource(
    context: Context,
    private val clock: Clock,
    private val logger: Logger,
    private val probeTimeoutMs: Long = PROBE_TIMEOUT_MS,
    private val naviConnectTimeoutMs: Long = NAVI_CONNECT_TIMEOUT_MS,
) : SignalSource {

    private val appContext = context.applicationContext

    override val mode = SignalMode.LIVE

    private val _vehicle = MutableStateFlow(VehicleSnapshot())
    private val _navi = MutableStateFlow(NaviSnapshot())
    private val _availability = MutableStateFlow(SignalAvailability())

    override val vehicle: StateFlow<VehicleSnapshot> = _vehicle.asStateFlow()
    override val navi: StateFlow<NaviSnapshot> = _navi.asStateFlow()
    override val availability: StateFlow<SignalAvailability> = _availability.asStateFlow()
    override val finished: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    private var vehicleSdk: Vehicle? = null
    private var naviHelper: NaviHelper? = null
    private var naviListener: NaviHelperEventListener? = null
    private val unregisters = CopyOnWriteArrayList<() -> Unit>()

    override suspend fun start(): AppResult<Unit> {
        _availability.value = SignalAvailability(SignalKey.entries.associateWith { SignalStatus.UNKNOWN })
        // SDK 내부가 Handler/바인딩을 쓰므로 생성과 등록은 메인 스레드에서 한다.
        withContext(Dispatchers.Main.immediate) {
            val vehicle = try {
                Vehicle(appContext).also { it.initialize() }
            } catch (e: Exception) {
                logger.e(TAG, "Vehicle SDK 초기화 실패", e)
                setStatus(VEHICLE_KEYS, e.toSignalStatus())
                null
            }
            vehicleSdk = vehicle
            coroutineScope {
                if (vehicle != null) {
                    launch { setUpTurnSignal(vehicle) }
                    launch { setUpBrake(vehicle) }
                    launch { setUpWarnings(vehicle) }
                    launch { setUpLowBeam(vehicle) }
                }
                launch { setUpNavi() }
            }
        }
        val statuses = _availability.value.status
        logger.d(TAG, "신호 상태: $statuses")
        if (statuses.values.none { it == SignalStatus.AVAILABLE }) {
            stop()
            val position = statuses[SignalKey.POSITION] ?: SignalStatus.ERROR
            return AppResult.Err(AppError.SignalUnavailable(SignalKey.POSITION, position, "모든 차량·내비 신호 사용 불가"))
        }
        return AppResult.Ok(Unit)
    }

    /** SDK 해제(리스너 해제·서비스 unbind)는 등록과 같은 메인 스레드에서 한다. 다른 스레드에서 부르면 메인으로 넘긴다. */
    override fun stop() {
        if (Looper.myLooper() == Looper.getMainLooper()) releaseAll() else Handler(Looper.getMainLooper()).post { releaseAll() }
    }

    private fun releaseAll() {
        unregisters.forEach { runCatching(it).onFailure { e -> logger.w(TAG, "구독 해제 실패", e) } }
        unregisters.clear()
        naviHelper?.let { helper ->
            naviListener?.let { runCatching { helper.removeListener(it) } }
            releaseQuietly(helper)
        }
        naviHelper = null
        naviListener = null
        vehicleSdk?.let { runCatching { it.release() } }
        vehicleSdk = null
    }

    private suspend fun setUpTurnSignal(vehicle: Vehicle) {
        val api = vehicle.getTurnSignal()
        val status = awaitSdk<CapabilityConfig>(probeTimeoutMs) { ok, fail -> api.checkTurnSignalStateCapabilities(ok, fail) }
            .toStatus { it.toStatus() }
        setStatus(SignalKey.TURN_SIGNAL, status)
        if (status != SignalStatus.AVAILABLE) return

        awaitSdk<TurnSignalValue?>(probeTimeoutMs) { ok, fail -> api.getCurrentTurnSignalState(ok, fail) }
            .let { if (it is SdkOutcome.Ok) updateVehicle { v -> v.copy(turnSignal = PleosMappers.turnSignal(it.value)) } }

        val listener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) =
                updateVehicle { it.copy(turnSignal = PleosMappers.turnSignal(state)) }

            override fun onFailed(e: Exception) = onSignalFailed(SignalKey.TURN_SIGNAL, e)
        }
        subscribe(SignalKey.TURN_SIGNAL, { api.registerTurnSignalState(listener) }, { api.unregisterTurnSignalState(listener) })
    }

    private suspend fun setUpBrake(vehicle: Vehicle) {
        val api = vehicle.getBrake()
        val status = awaitSdk<CapabilityConfig>(probeTimeoutMs) { ok, fail -> api.checkBrakePedalStatusCapabilities(ok, fail) }
            .toStatus { it.toStatus() }
        setStatus(SignalKey.BRAKE, status)
        if (status != SignalStatus.AVAILABLE) return

        awaitSdk<Boolean?>(probeTimeoutMs) { ok, fail -> api.getCurrentBrakePedalStatus(ok, fail) }
            .let { if (it is SdkOutcome.Ok) updateVehicle { v -> v.copy(brakePressed = it.value) } }

        val listener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) = updateVehicle { it.copy(brakePressed = status) }
            override fun onFailed(e: Exception) = onSignalFailed(SignalKey.BRAKE, e)
        }
        subscribe(SignalKey.BRAKE, { api.registerBrakePedalStatus(listener) }, { api.unregisterBrakePedalStatus(listener) })
    }

    /** checkTelltalesCapabilities는 PRIVATE 권한이라 쓸 수 없다. 현재 값 조회 성공 여부로 판단한다. */
    private suspend fun setUpWarnings(vehicle: Vehicle) {
        val api = vehicle.getSafety()
        val initial = awaitSdk<Telltales>(probeTimeoutMs) { ok, fail -> api.getTelltalesInfo(ok, fail) }
        val status = initial.toStatus { SignalStatus.AVAILABLE }
        setStatus(SignalKey.WARNINGS, status)
        if (initial !is SdkOutcome.Ok) return
        updateVehicle { it.copy(activeWarnings = PleosMappers.warnings(initial.value)) }

        val listener = object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(telltales: Telltales?) {
                // null은 "값 없음"이지 "경고 전부 꺼짐"이 아니다. 이전 값을 유지한다.
                if (telltales != null) updateVehicle { it.copy(activeWarnings = PleosMappers.warnings(telltales)) }
            }

            override fun onFailed(e: Exception) = onSignalFailed(SignalKey.WARNINGS, e)
        }
        subscribe(SignalKey.WARNINGS, { api.registerTelltales(listener) }, { api.unregisterTelltales(listener) })
    }

    private suspend fun setUpLowBeam(vehicle: Vehicle) {
        val api = vehicle.getLight()
        val status = awaitSdk<CapabilityConfig>(probeTimeoutMs) { ok, fail ->
            api.checkLowBeamLightStateCapabilities({ config, _ -> ok(config) }, fail)
        }.toStatus { it.toStatus() }
        setStatus(SignalKey.LOW_BEAM, status)
        if (status != SignalStatus.AVAILABLE) return

        awaitSdk<LightState?>(probeTimeoutMs) { ok, fail -> api.getCurrentLowBeamLightState(ok, fail) }
            .let { if (it is SdkOutcome.Ok) updateVehicle { v -> v.copy(lowBeamOn = it.value?.let { s -> s == LightState.ON }) } }

        val listener = object : LightStateListener {
            override fun onLightStateUpdated(state: LightState?) = updateVehicle { it.copy(lowBeamOn = state?.let { s -> s == LightState.ON }) }
            override fun onFailed(e: Exception) = onSignalFailed(SignalKey.LOW_BEAM, e)
        }
        subscribe(SignalKey.LOW_BEAM, { api.registerLowBeamLightState(listener) }, { api.unregisterLowBeamLightState(listener) })
    }

    private suspend fun setUpNavi() {
        val helper = when (val connected = connectNaviHelper(appContext, naviConnectTimeoutMs)) {
            is SdkOutcome.Ok -> connected.value
            is SdkOutcome.Failed -> {
                logger.w(TAG, "NaviHelper 연결 실패", connected.error)
                setStatus(NAVI_KEYS, connected.error.toSignalStatus())
                return
            }
            SdkOutcome.TimedOut -> {
                logger.w(TAG, "NaviHelper 연결 시간 초과")
                setStatus(NAVI_KEYS, SignalStatus.ERROR)
                return
            }
        }
        val listener = object : NaviHelperEventListener {
            override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
                val p = LatLng(currentLocationInfo.latitude, currentLocationInfo.longitude)
                // (0,0) 등 의미 없는 좌표는 위치 없음으로 본다.
                if (p.lat == 0.0 && p.lon == 0.0) return
                _navi.update { it.copy(timestampMs = clock.nowMs(), position = p, headingDeg = currentLocationInfo.heading) }
            }

            override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
                _navi.update { it.copy(speedLimitKmh = speedLimit.takeIf { v -> v > 0 }) }
            }

            override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
                _navi.update { it.copy(nextTurn = PleosMappers.nextTurn(tbtInfoList)) }
            }

            override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
                _navi.update { it.copy(cautions = PleosMappers.cautions(cautionPointList)) }
            }

            override fun onRouteEnded() {
                _navi.update { it.copy(nextTurn = null, cautions = emptyList()) }
            }

            override fun onError(errorCode: NaviErrorCode) {
                logger.w(TAG, "내비 오류: ${errorCode.name}")
            }
        }
        try {
            helper.addListener(listener)
            // 첫 값을 바로 받기 위한 요청. 이후 값은 리스너로 온다.
            helper.getCurrentLocationInfo()
            helper.getTBTInfo()
        } catch (e: Exception) {
            logger.w(TAG, "내비 구독 실패", e)
            runCatching { helper.removeListener(listener) }
            releaseQuietly(helper)
            setStatus(NAVI_KEYS, e.toSignalStatus())
            return
        }
        naviHelper = helper
        naviListener = listener
        setStatus(NAVI_KEYS, SignalStatus.AVAILABLE)
    }

    private fun subscribe(key: SignalKey, register: () -> Unit, unregister: () -> Unit) {
        try {
            register()
            unregisters += unregister
        } catch (e: Exception) {
            onSignalFailed(key, e)
        }
    }

    private fun onSignalFailed(key: SignalKey, e: Exception) {
        val status = e.toSignalStatus()
        logger.w(TAG, "신호 실패: $key → $status", e)
        setStatus(key, status)
    }

    private fun updateVehicle(transform: (VehicleSnapshot) -> VehicleSnapshot) {
        _vehicle.update { transform(it).copy(timestampMs = clock.nowMs()) }
    }

    private fun setStatus(key: SignalKey, status: SignalStatus) = setStatus(listOf(key), status)

    private fun setStatus(keys: List<SignalKey>, status: SignalStatus) {
        _availability.update { current -> SignalAvailability(current.status + keys.associateWith { status }) }
    }

    private companion object {
        const val TAG = "PleosSignals"
        const val PROBE_TIMEOUT_MS = 3_000L
        const val NAVI_CONNECT_TIMEOUT_MS = 10_000L
        val VEHICLE_KEYS = listOf(SignalKey.TURN_SIGNAL, SignalKey.BRAKE, SignalKey.WARNINGS, SignalKey.LOW_BEAM)
        val NAVI_KEYS = listOf(SignalKey.POSITION, SignalKey.SPEED_LIMIT, SignalKey.NEXT_TURN, SignalKey.CAUTIONS)
    }
}
