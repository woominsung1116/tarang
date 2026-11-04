package com.taraang.app.vehicle

import ai.pleos.playground.vehicle.api.Safety
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.listener.LowWasherFluidWaringListener
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.PowerDownWarningIndicatorListener
import ai.pleos.playground.vehicle.listener.SeatBeltNotBuckledWarningListener
import com.taraang.app.core.Logger
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.domain.signal.WarningType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * NEW_WARNING 입력(ARCHITECTURE.md 5장): Safety의 CAR_SAFETY(normal 권한) 경고 리스너 묶음.
 * 통합 경고등(telltales)과 바퀴별 공기압은 PRIVATE_CAR_SAFETY(signature|privileged)라 일반 앱은 쓸 수 없다.
 *
 * - 입력마다 capability → 현재 값 → 구독 순서로 붙이고, 하나가 실패해도 나머지는 붙인다.
 * - 묶음 상태는 붙은 입력이 하나라도 있으면 AVAILABLE([combinedStatus]).
 * - 구독 중 실패한 입력은 그 경고만 끈다. 붙은 입력이 모두 실패하면 [onStatus]로 묶음 상태를 바꾼다.
 * - null 값은 "값 없음"이지 "경고 꺼짐"이 아니다. 이전 값을 유지한다.
 */
internal class SafetyWarnings(
    private val api: Safety,
    private val probeTimeoutMs: Long,
    private val logger: Logger,
    /** 경고 하나가 켜지거나 꺼졌다. SDK 스레드에서 온다. */
    private val onWarning: (WarningType, Boolean) -> Unit,
    /** 구독 중 실패로 묶음 상태가 바뀌었다. */
    private val onStatus: (SignalStatus) -> Unit,
) {

    private class Input(
        val type: WarningType,
        /** capability 확인. AVAILABLE이 아니면 이 입력은 쓰지 않는다. */
        val check: suspend () -> SignalStatus,
        val read: (onValue: (Boolean?) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
        /** 리스너를 등록하고 해제 함수를 돌려준다. */
        val register: (onValue: (Boolean?) -> Unit, onFailed: (Exception) -> Unit) -> (() -> Unit),
    )

    private val unregisters = CopyOnWriteArrayList<() -> Unit>()
    private val subscribed = AtomicInteger(0)

    /** 모든 입력을 붙여 보고 묶음 상태를 돌려준다. */
    suspend fun start(): SignalStatus {
        val statuses = coroutineScope { inputs().map { async { setUp(it) } }.awaitAll() }
        logger.d(TAG, "경고 입력 상태: $statuses")
        val status = combinedStatus(statuses)
        // 붙자마자 모두 실패했으면(시작 중 실패 콜백) AVAILABLE로 덮지 않는다.
        return if (status == SignalStatus.AVAILABLE && subscribed.get() == 0) SignalStatus.ERROR else status
    }

    fun stop() {
        unregisters.forEach { runCatching(it).onFailure { e -> logger.w(TAG, "경고 구독 해제 실패", e) } }
        unregisters.clear()
        subscribed.set(0)
    }

    private suspend fun setUp(input: Input): SignalStatus {
        val status = input.check()
        if (status != SignalStatus.AVAILABLE) return status

        awaitSdk<Boolean?>(probeTimeoutMs) { ok, fail -> input.read(ok, fail) }
            .let { if (it is SdkOutcome.Ok) it.value?.let { on -> onWarning(input.type, on) } }

        val failed = AtomicBoolean(false)
        val onValue: (Boolean?) -> Unit = { on -> if (on != null && !failed.get()) onWarning(input.type, on) }
        val onFailed: (Exception) -> Unit = { e ->
            // 같은 입력의 실패가 여러 번 와도 한 번만 센다.
            if (failed.compareAndSet(false, true)) {
                logger.w(TAG, "경고 입력 실패: ${input.type}", e)
                onWarning(input.type, false)
                if (subscribed.decrementAndGet() == 0) onStatus(e.toSignalStatus())
            }
        }
        // 등록 중에 실패 콜백이 바로 올 수 있어 먼저 센다.
        subscribed.incrementAndGet()
        return try {
            unregisters += input.register(onValue, onFailed)
            SignalStatus.AVAILABLE
        } catch (e: Exception) {
            if (failed.compareAndSet(false, true)) subscribed.decrementAndGet()
            logger.w(TAG, "경고 구독 실패: ${input.type}", e)
            e.toSignalStatus()
        }
    }

    private fun inputs(): List<Input> = listOf(seatBelt(), engineOil(), brakeFluid(), powerDown(), washerFluid())

    private fun seatBelt(): Input {
        var driverSeat: SeatArea? = null
        return Input(
            WarningType.SEAT_BELT,
            check = {
                awaitSdk<Pair<CapabilityConfig, List<SeatArea>>>(probeTimeoutMs) { ok, fail ->
                    api.checkSeatNotBuckledWarningCapabilities({ config, areas -> ok(config to areas) }, fail)
                }.toStatus { (config, areas) ->
                    driverSeat = PleosMappers.driverSeat(areas)
                    if (driverSeat == null) SignalStatus.UNSUPPORTED else config.toStatus()
                }
            },
            read = { ok, fail -> api.hasSeatBeltNotBuckledWarning(requireNotNull(driverSeat), ok, fail) },
            register = { onValue, onFailed ->
                val area = requireNotNull(driverSeat)
                val listener = object : SeatBeltNotBuckledWarningListener {
                    override fun onSeatBeltNotBuckledWarningUpdated(state: Boolean?) = onValue(state)
                    override fun onFailed(e: Exception) = onFailed(e)
                }
                api.registerSeatBeltNotBuckledWarning(area, listener)
                ({ api.unregisterSeatBeltNotBuckledWarning(area, listener) })
            },
        )
    }

    private fun engineOil() = Input(
        WarningType.ENGINE_OIL_LEVEL,
        check = { checkLevel { ok, fail -> api.checkEngineOilLevelStateCapabilities({ config, _ -> ok(config) }, fail) } },
        read = { ok, fail -> api.getEngineOilLevelState({ ok(PleosMappers.fluidLow(it)) }, fail) },
        register = { onValue, onFailed ->
            val listener = levelListener(onValue, onFailed)
            api.registerEngineOilLevelState(listener)
            ({ api.unregisterEngineOilLevelState(listener) })
        },
    )

    private fun brakeFluid() = Input(
        WarningType.BRAKE_SYSTEM,
        check = { checkLevel { ok, fail -> api.checkBrakeFluidLevelStateCapabilities({ config, _ -> ok(config) }, fail) } },
        read = { ok, fail -> api.getBrakeFluidLevelState({ ok(PleosMappers.fluidLow(it)) }, fail) },
        register = { onValue, onFailed ->
            val listener = levelListener(onValue, onFailed)
            api.registerBrakeFluidLevelState(listener)
            ({ api.unregisterBrakeFluidLevelState(listener) })
        },
    )

    /** SDK: true면 엔진(동력계)에 문제가 있다. */
    private fun powerDown() = Input(
        WarningType.ENGINE,
        check = { checkLevel { ok, fail -> api.checkPowerDownWarningIndicatorCapabilities(ok, fail) } },
        read = { ok, fail -> api.hasPowerDownWarningIndicator(ok, fail) },
        register = { onValue, onFailed ->
            val listener = object : PowerDownWarningIndicatorListener {
                override fun onPowerDownWarningIndicatorUpdated(state: Boolean?) = onValue(state)
                override fun onFailed(e: Exception) = onFailed(e)
            }
            api.registerPowerDownWarningIndicator(listener)
            ({ api.unregisterPowerDownWarningIndicator(listener) })
        },
    )

    private fun washerFluid() = Input(
        WarningType.LOW_WASHER_FLUID,
        check = { checkLevel { ok, fail -> api.checkLowWasherFluidWarningStateCapabilities(ok, fail) } },
        read = { ok, fail -> api.hasLowWasherFluidWarning(ok, fail) },
        register = { onValue, onFailed ->
            val listener = object : LowWasherFluidWaringListener {
                override fun onLowWasherFluidWaringUpdated(state: Boolean?) = onValue(state)
                override fun onFailed(e: Exception) = onFailed(e)
            }
            api.registerLowWasherFluidWaringState(listener)
            ({ api.unregisterLowWasherFluidWaringState(listener) })
        },
    )

    private suspend fun checkLevel(call: (onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit) -> Unit): SignalStatus =
        awaitSdk(probeTimeoutMs, call).toStatus { it.toStatus() }

    private fun levelListener(onValue: (Boolean?) -> Unit, onFailed: (Exception) -> Unit) = object : OilLevelStateListener {
        override fun onOilLevelUpdated(level: OilLevelState?) = onValue(PleosMappers.fluidLow(level))
        override fun onFailed(e: Exception) = onFailed(e)
    }

    private companion object {
        const val TAG = "SafetyWarnings"
    }
}
