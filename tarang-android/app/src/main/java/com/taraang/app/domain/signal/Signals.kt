package com.taraang.app.domain.signal

import com.taraang.app.core.AppResult
import com.taraang.app.core.LatLng
import com.taraang.app.domain.model.SignalMode
import kotlinx.coroutines.flow.StateFlow

enum class TurnSignalState { OFF, LEFT, RIGHT }

/**
 * 차량 경고. 실차(LIVE)는 CAR_SAFETY 리스너가 주는 것(안전벨트·엔진 오일·브레이크액·엔진·워셔액)만 채운다
 * (vehicle/SafetyWarnings). 나머지는 리플레이 로그 호환용(이름으로 저장되므로 지우거나 바꾸지 않는다).
 */
enum class WarningType(val label: String) {
    TIRE_PRESSURE("타이어 공기압"),
    ENGINE("엔진"),
    ENGINE_OIL_PRESSURE("엔진 오일 압력"),
    ENGINE_TEMPERATURE("엔진 온도"),
    BRAKE_SYSTEM("브레이크 계통"),
    ABS("ABS"),
    ESC("차체 자세 제어"),
    AIRBAG("에어백"),
    LOW_VOLT_BATTERY("배터리 전압"),
    EV_SYSTEM("전기차 시스템"),
    LOW_FUEL("연료 부족"),
    LOW_EV_BATTERY("배터리 부족"),
    SEAT_BELT("안전벨트"),
    DOOR_OPEN("문 열림"),
    PARKING_BRAKE("주차 브레이크"),
    MASTER_WARNING("종합 경고"),
    ENGINE_OIL_LEVEL("엔진 오일 부족"),
    LOW_WASHER_FLUID("워셔액 부족"),
}

data class VehicleSnapshot(
    val timestampMs: Long = 0,
    val turnSignal: TurnSignalState? = null,
    val brakePressed: Boolean? = null,
    val activeWarnings: Set<WarningType> = emptySet(),
    val lowBeamOn: Boolean? = null,
)

enum class TurnKind { LEFT, RIGHT, UTURN, STRAIGHT, ROUNDABOUT, ARRIVE, OTHER }

data class NextTurn(val kind: TurnKind, val distanceM: Double)

enum class CautionType { SCHOOL_ZONE, ACCIDENT_PRONE_AREA, SPEED_BUMP, OTHER }

data class Caution(val type: CautionType, val remainM: Int)

/**
 * 내비 경로 상태. 앱이 붙기 전 일은 모르므로 UNKNOWN에서 시작한다. CANCELLED/ARRIVED는 주행 중 받은 콜백으로만 바뀐다
 * (NaviHelper onRouteCancelled / onDestinationArrived).
 */
enum class RouteStatus { UNKNOWN, ACTIVE, CANCELLED, ARRIVED }

data class NaviSnapshot(
    val timestampMs: Long = 0,
    val route: RouteStatus = RouteStatus.UNKNOWN,
    val position: LatLng? = null,
    val headingDeg: Double? = null,
    val speedLimitKmh: Int? = null,
    val nextTurn: NextTurn? = null,
    val cautions: List<Caution> = emptyList(),
)

enum class SignalKey { TURN_SIGNAL, BRAKE, WARNINGS, LOW_BEAM, POSITION, SPEED_LIMIT, NEXT_TURN, CAUTIONS }

enum class SignalStatus { UNKNOWN, AVAILABLE, UNSUPPORTED, PERMISSION_DENIED, ERROR }

data class SignalAvailability(val status: Map<SignalKey, SignalStatus> = emptyMap()) {
    fun isAvailable(key: SignalKey): Boolean = status[key] == SignalStatus.AVAILABLE
}

/**
 * 주행 중 신호의 단일 진입점. LIVE는 Pleos SDK, REPLAY는 assets/replay 로그를 쓴다.
 * 신호 하나가 실패해도 나머지는 계속 흘러야 한다.
 */
interface SignalSource {
    val mode: SignalMode
    val vehicle: StateFlow<VehicleSnapshot>
    val navi: StateFlow<NaviSnapshot>
    val availability: StateFlow<SignalAvailability>

    /** 리플레이가 끝나면 true. LIVE는 항상 false. */
    val finished: StateFlow<Boolean>

    suspend fun start(): AppResult<Unit>
    fun stop()
}

/** 경로 요청 결과. [currentPosition]: 요청 직전 내비가 준 현재 위치(못 받으면 null). 출발점과 너무 멀면 알린다. */
data class NaviStart(val currentPosition: LatLng?)

/** 코스를 차량 내비로 넘긴다(NaviHelper requestRouteWithWaypoints). */
interface NavigationLauncher {
    suspend fun startCourse(origin: LatLng, waypoints: List<LatLng>, title: String): AppResult<NaviStart>
}
