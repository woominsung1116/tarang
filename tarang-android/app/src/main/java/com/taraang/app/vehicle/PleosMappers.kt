package com.taraang.app.vehicle

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import com.taraang.app.domain.signal.Caution
import com.taraang.app.domain.signal.CautionType
import com.taraang.app.domain.signal.NextTurn
import com.taraang.app.domain.signal.TurnKind
import com.taraang.app.domain.signal.TurnSignalState

/** Pleos SDK 타입 → 도메인. SDK 타입은 이 패키지 밖으로 나가지 않는다. */
internal object PleosMappers {

    fun turnSignal(value: TurnSignalValue?): TurnSignalState? = when (value) {
        TurnSignalValue.OFF -> TurnSignalState.OFF
        TurnSignalValue.LEFT -> TurnSignalState.LEFT
        TurnSignalValue.RIGHT -> TurnSignalState.RIGHT
        null -> null
    }

    /**
     * 오일·브레이크액 수위 → 경고 여부. LOW·CRITICALLY_LOW만 경고. ERROR(센서 오류)와 null은 값 없음(null)이라
     * 호출자가 이전 값을 유지한다.
     */
    fun fluidLow(level: OilLevelState?): Boolean? = when (level) {
        OilLevelState.LOW, OilLevelState.CRITICALLY_LOW -> true
        OilLevelState.NORMAL, OilLevelState.HIGH -> false
        OilLevelState.ERROR, null -> null
    }

    /** 안전벨트 경고를 볼 좌석: 운전석(국내 좌핸들, 1열 왼쪽)을 포함한 지원 영역. 없으면 null. */
    fun driverSeat(areas: List<SeatArea>): SeatArea? = areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values }

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
