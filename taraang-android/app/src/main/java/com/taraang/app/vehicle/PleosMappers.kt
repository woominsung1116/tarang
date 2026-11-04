package com.taraang.app.vehicle

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import com.taraang.app.domain.signal.Caution
import com.taraang.app.domain.signal.CautionType
import com.taraang.app.domain.signal.NextTurn
import com.taraang.app.domain.signal.TurnKind
import com.taraang.app.domain.signal.TurnSignalState
import com.taraang.app.domain.signal.WarningType

/** Pleos SDK 타입 → 도메인. SDK 타입은 이 패키지 밖으로 나가지 않는다. */
internal object PleosMappers {

    fun turnSignal(value: TurnSignalValue?): TurnSignalState? = when (value) {
        TurnSignalValue.OFF -> TurnSignalState.OFF
        TurnSignalValue.LEFT -> TurnSignalState.LEFT
        TurnSignalValue.RIGHT -> TurnSignalState.RIGHT
        null -> null
    }

    /** 켜짐·깜빡임을 활성으로 본다. 초보 피드백과 무관한 표시등(주행 모드 등)은 버린다. */
    fun warnings(telltales: Telltales?): Set<WarningType> =
        telltales?.telltales.orEmpty()
            .filter { it.state == TelltaleState.ON || it.state == TelltaleState.BLINK }
            .mapNotNull { warning(it.type) }
            .toSet()

    private fun warning(type: TelltaleType): WarningType? = when (type) {
        // 바퀴별 공기압 값은 PRIVATE 권한이라 TPMS 경고등으로 판단한다.
        TelltaleType.TPMS -> WarningType.TIRE_PRESSURE
        TelltaleType.ENGINE -> WarningType.ENGINE
        TelltaleType.ENGINE_OIL_PRESSURE -> WarningType.ENGINE_OIL_PRESSURE
        TelltaleType.ENGINE_TEMPERATURE -> WarningType.ENGINE_TEMPERATURE
        TelltaleType.ABS -> WarningType.ABS
        TelltaleType.ESC -> WarningType.ESC
        TelltaleType.AIRBAG -> WarningType.AIRBAG
        TelltaleType.LOW_VOLT_BATTERY -> WarningType.LOW_VOLT_BATTERY
        TelltaleType.EV_SYSTEM -> WarningType.EV_SYSTEM
        TelltaleType.LOW_FUEL, TelltaleType.LOW_FUEL_FCEV -> WarningType.LOW_FUEL
        TelltaleType.LOW_EV_BATTERY -> WarningType.LOW_EV_BATTERY
        TelltaleType.SEAT_BELT -> WarningType.SEAT_BELT
        TelltaleType.DOOR_OPEN -> WarningType.DOOR_OPEN
        TelltaleType.PARKING_BRAKE, TelltaleType.ELECTRONIC_PARKING_BRAKE -> WarningType.PARKING_BRAKE
        TelltaleType.MASTER_WARNING -> WarningType.MASTER_WARNING
        else -> null
    }

    /**
     * 다음 회전 = TBT 목록에서 실제 조작(좌·우·유턴·회전교차로)이나 도착인 첫 항목. 직진·경로 안내(TURN_WAY 등)는 건너뛴다.
     * 앞에 직진 안내가 끼면 다음 회전 판정·깜빡이 안내를 놓치기 때문이다.
     */
    fun nextTurn(list: List<TBTInfo>): NextTurn? = list.firstNotNullOfOrNull { info ->
        val kind = turnKind(info.type)
        NextTurn(kind, info.distance).takeIf { kind in GUIDED_KINDS }
    }

    private val GUIDED_KINDS = setOf(TurnKind.LEFT, TurnKind.RIGHT, TurnKind.UTURN, TurnKind.ROUNDABOUT, TurnKind.ARRIVE)

    fun turnKind(type: TurnType): TurnKind = when (type) {
        TurnType.TURN_LEFT, TurnType.TURN_SHARP_L -> TurnKind.LEFT
        TurnType.TURN_RIGHT, TurnType.TURN_SHARP_R -> TurnKind.RIGHT
        TurnType.TURN_UTURN -> TurnKind.UTURN
        TurnType.TURN_STRAIGHT -> TurnKind.STRAIGHT
        TurnType.TURN_ENTER_ROUNDABOUT_02, TurnType.TURN_ENTER_ROUNDABOUT_03, TurnType.TURN_ENTER_ROUNDABOUT_04,
        TurnType.TURN_ENTER_ROUNDABOUT_06, TurnType.TURN_ENTER_ROUNDABOUT_08, TurnType.TURN_ENTER_ROUNDABOUT_09,
        TurnType.TURN_ENTER_ROUNDABOUT_10, TurnType.TURN_ENTER_ROUNDABOUT_12, TurnType.TURN_EXIT_ROUNDABOUT,
        -> TurnKind.ROUNDABOUT
        TurnType.TURN_ARRIVE -> TurnKind.ARRIVE
        else -> TurnKind.OTHER
    }

    fun cautions(list: List<CautionPoint>): List<Caution> = list.map {
        val type = when (it.type) {
            CautionPointType.SCHOOL_ZONE -> CautionType.SCHOOL_ZONE
            CautionPointType.ACCIDENT_PRONE_AREA -> CautionType.ACCIDENT_PRONE_AREA
            CautionPointType.SPEED_BUMP -> CautionType.SPEED_BUMP
            else -> CautionType.OTHER
        }
        Caution(type, it.remainDistance)
    }
}
