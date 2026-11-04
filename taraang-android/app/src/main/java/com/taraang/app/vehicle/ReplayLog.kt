package com.taraang.app.vehicle

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.LatLng
import com.taraang.app.core.runCatchingApp
import com.taraang.app.domain.signal.Caution
import com.taraang.app.domain.signal.CautionType
import com.taraang.app.domain.signal.NaviSnapshot
import com.taraang.app.domain.signal.NextTurn
import com.taraang.app.domain.signal.TurnKind
import com.taraang.app.domain.signal.TurnSignalState
import com.taraang.app.domain.signal.VehicleSnapshot
import com.taraang.app.domain.signal.WarningType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** ARCHITECTURE.md 4.3 리플레이 로그 DTO. */
@Serializable
internal data class ReplayLogDto(
    val schemaVersion: Int = 1,
    val courseId: String = "",
    val title: String = "",
    val tickMs: Long = 500,
    val frames: List<ReplayFrameDto> = emptyList(),
)

@Serializable
internal data class ReplayFrameDto(
    val t: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val heading: Double? = null,
    val speedLimitKmh: Int? = null,
    val turnSignal: String? = null,
    val brake: Boolean? = null,
    val warnings: List<String> = emptyList(),
    val nextTurn: NextTurnDto? = null,
    val caution: List<CautionDto> = emptyList(),
)

@Serializable
internal data class NextTurnDto(val type: String, val distanceM: Double)

@Serializable
internal data class CautionDto(val type: String, val remainM: Int)

/** 해석된 프레임. [offsetMs]는 재생 시작 기준. 스냅샷 timestampMs는 재생 시 채운다. */
data class ReplayFrame(val offsetMs: Long, val vehicle: VehicleSnapshot, val navi: NaviSnapshot)

data class ReplayLog(val courseId: String, val title: String, val frames: List<ReplayFrame>)

object ReplayLogParser {
    private val json = Json { ignoreUnknownKeys = true }

    /** 프레임은 t 순으로 정렬하고, 음수 t나 좌표가 반쪽인 값은 버린다. 모르는 enum 값은 무시한다. */
    fun parse(text: String): AppResult<ReplayLog> =
        runCatchingApp({ AppError.InvalidInput("리플레이 로그 해석 실패: ${it.javaClass.simpleName}") }) {
            val dto = json.decodeFromString(ReplayLogDto.serializer(), text)
            val frames = dto.frames.filter { it.t >= 0 }.sortedBy { it.t }.map { it.toFrame() }
            require(frames.isNotEmpty()) { "프레임 없음" }
            ReplayLog(dto.courseId, dto.title, frames)
        }

    private fun ReplayFrameDto.toFrame(): ReplayFrame {
        val position = if (lat != null && lon != null) LatLng(lat, lon) else null
        return ReplayFrame(
            offsetMs = t,
            vehicle = VehicleSnapshot(
                turnSignal = turnSignal?.let { enumOrNull<TurnSignalState>(it) },
                brakePressed = brake,
                activeWarnings = warnings.mapNotNull { enumOrNull<WarningType>(it) }.toSet(),
            ),
            navi = NaviSnapshot(
                position = position,
                headingDeg = heading,
                speedLimitKmh = speedLimitKmh?.takeIf { it > 0 },
                nextTurn = nextTurn?.let { NextTurn(enumOrNull<TurnKind>(it.type) ?: TurnKind.OTHER, it.distanceM) },
                cautions = caution.map { Caution(enumOrNull<CautionType>(it.type) ?: CautionType.OTHER, it.remainM) },
            ),
        )
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }
}
