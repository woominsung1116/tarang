package com.taraang.app.vehicle

import ai.pleos.playground.vehicle.api.Safety
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import ai.pleos.playground.vehicle.listener.OilLevelStateListener
import ai.pleos.playground.vehicle.listener.VehicleListener
import com.taraang.app.core.Logger
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.domain.signal.WarningType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * LIVE 경고 입력. Safety는 메서드가 많은 인터페이스라 이름으로 응답하는 동적 프록시로 흉내 낸다.
 * PRIVATE_CAR_SAFETY API(telltales·타이어)를 부르면 바로 실패한다.
 */
class SafetyWarningsTest {

    private val driver = SeatArea(SeatValue.ROW_1_LEFT)

    private class FakeSafety(
        /** capability 메서드 이름 → 응답(AccessType 또는 던질 예외). 없으면 READ. */
        val capability: Map<String, Any> = emptyMap(),
        /** 현재 값 메서드 이름 → 값. */
        val current: Map<String, Any?> = emptyMap(),
        val seatAreas: List<SeatArea> = listOf(SeatArea(SeatValue.ROW_1_LEFT), SeatArea(SeatValue.ROW_1_RIGHT)),
    ) {
        val calls = mutableListOf<String>()
        val listeners = mutableMapOf<String, VehicleListener>()
        val unregistered = mutableListOf<String>()

        @Suppress("UNCHECKED_CAST")
        val api: Safety = Proxy.newProxyInstance(Safety::class.java.classLoader, arrayOf(Safety::class.java)) { _, method, args ->
            val name = method.name
            calls += name
            require(!name.contains("Telltale") && !name.contains("Tire")) { "PRIVATE API 호출: $name" }
            val a = args.orEmpty()
            when {
                name.startsWith("check") -> {
                    val onFailed = a[1] as (Exception) -> Unit
                    when (val c = capability[name] ?: AccessType.READ) {
                        is Exception -> onFailed(c)
                        else -> {
                            val config = CapabilityConfig(c as AccessType)
                            when (name) {
                                "checkSeatNotBuckledWarningCapabilities" -> (a[0] as (CapabilityConfig, List<SeatArea>) -> Unit)(config, seatAreas)
                                "checkEngineOilLevelStateCapabilities", "checkBrakeFluidLevelStateCapabilities" ->
                                    (a[0] as (CapabilityConfig, List<OilLevelState>) -> Unit)(config, OilLevelState.entries)
                                else -> (a[0] as (CapabilityConfig) -> Unit)(config)
                            }
                        }
                    }
                }
                name.startsWith("has") || name.startsWith("get") -> {
                    val onComplete = a[a.size - 2] as (Any?) -> Unit
                    onComplete(current[name])
                }
                name.startsWith("register") -> listeners[name.removePrefix("register")] = a.last() as VehicleListener
                name.startsWith("unregister") -> unregistered += name.removePrefix("unregister")
            }
            null
        } as Safety
    }

    private class Recorder {
        val warnings = mutableSetOf<WarningType>()
        val statuses = mutableListOf<SignalStatus>()
        fun warnings(s: FakeSafety) = SafetyWarnings(
            s.api, probeTimeoutMs = 1_000, logger = Logger.Noop,
            onWarning = { type, on -> if (on) warnings += type else warnings -= type },
            onStatus = { statuses += it },
        )
    }

    @Test
    fun `one failing input does not block the others, and stop unregisters what was registered`() = runTest {
        val safety = FakeSafety(
            capability = mapOf(
                "checkEngineOilLevelStateCapabilities" to SecurityException("no"),
                "checkLowWasherFluidWarningStateCapabilities" to AccessType.NONE,
            ),
            current = mapOf("hasSeatBeltNotBuckledWarning" to true, "getBrakeFluidLevelState" to OilLevelState.NORMAL),
        )
        val r = Recorder()
        val warnings = r.warnings(safety)

        assertEquals(SignalStatus.AVAILABLE, warnings.start())
        assertEquals(setOf(WarningType.SEAT_BELT), r.warnings)
        assertEquals(setOf("SeatBeltNotBuckledWarning", "BrakeFluidLevelState", "PowerDownWarningIndicator"), safety.listeners.keys)

        // 값 없음(null·ERROR)은 이전 값을 유지하고, LOW는 켜고, NORMAL은 끈다.
        val brake = safety.listeners.getValue("BrakeFluidLevelState") as OilLevelStateListener
        brake.onOilLevelUpdated(OilLevelState.LOW)
        assertEquals(setOf(WarningType.SEAT_BELT, WarningType.BRAKE_SYSTEM), r.warnings)
        brake.onOilLevelUpdated(null)
        brake.onOilLevelUpdated(OilLevelState.ERROR)
        assertTrue(WarningType.BRAKE_SYSTEM in r.warnings)
        brake.onOilLevelUpdated(OilLevelState.NORMAL)
        assertEquals(setOf(WarningType.SEAT_BELT), r.warnings)

        warnings.stop()
        assertEquals(safety.listeners.keys, safety.unregistered.toSet())
    }

    @Test
    fun `seat belt uses the driver seat area the car reports`() = runTest {
        val safety = FakeSafety(current = mapOf("hasSeatBeltNotBuckledWarning" to false))
        val r = Recorder()
        r.warnings(safety).start()
        assertTrue("SeatBeltNotBuckledWarning" in safety.listeners)

        val noDriver = FakeSafety(seatAreas = listOf(SeatArea(SeatValue.ROW_2_LEFT)))
        Recorder().warnings(noDriver).start()
        assertTrue("SeatBeltNotBuckledWarning" !in noDriver.listeners)
        assertEquals(driver, PleosMappers.driverSeat(listOf(SeatArea(SeatValue.ROW_1_RIGHT), driver)))
    }

    @Test
    fun `all inputs blocked reports the clearest cause`() = runTest {
        val denied = SecurityException("no")
        val safety = FakeSafety(
            capability = mapOf(
                "checkSeatNotBuckledWarningCapabilities" to denied,
                "checkEngineOilLevelStateCapabilities" to denied,
                "checkBrakeFluidLevelStateCapabilities" to AccessType.NONE,
                "checkPowerDownWarningIndicatorCapabilities" to Exception("ERROR_NOT_SUPPORTED_PROPERTY"),
                "checkLowWasherFluidWarningStateCapabilities" to Exception("ERROR_SERVICE_CONNECTION_FAILURE"),
            ),
        )
        assertEquals(SignalStatus.PERMISSION_DENIED, Recorder().warnings(safety).start())
        assertTrue(safety.listeners.isEmpty())
    }

    @Test
    fun `when every subscribed input fails the group status changes, a single failure only drops that warning`() = runTest {
        val safety = FakeSafety(
            capability = mapOf(
                "checkSeatNotBuckledWarningCapabilities" to AccessType.NONE,
                "checkEngineOilLevelStateCapabilities" to AccessType.NONE,
                "checkLowWasherFluidWarningStateCapabilities" to AccessType.NONE,
            ),
            current = mapOf("getBrakeFluidLevelState" to OilLevelState.LOW, "hasPowerDownWarningIndicator" to true),
        )
        val r = Recorder()
        assertEquals(SignalStatus.AVAILABLE, r.warnings(safety).start())
        assertEquals(setOf(WarningType.BRAKE_SYSTEM, WarningType.ENGINE), r.warnings)

        safety.listeners.getValue("BrakeFluidLevelState").onFailed(Exception("ERROR_SERVICE_CONNECTION_FAILURE"))
        safety.listeners.getValue("BrakeFluidLevelState").onFailed(Exception("again"))
        assertEquals(setOf(WarningType.ENGINE), r.warnings)
        assertTrue(r.statuses.isEmpty())

        safety.listeners.getValue("PowerDownWarningIndicator").onFailed(SecurityException("revoked"))
        assertEquals(emptySet<WarningType>(), r.warnings)
        assertEquals(listOf(SignalStatus.PERMISSION_DENIED), r.statuses)
    }
}
