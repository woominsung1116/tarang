package com.taraang.app.vehicle

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.OilLevelState
import com.taraang.app.domain.signal.CautionType
import com.taraang.app.domain.signal.SignalStatus
import com.taraang.app.domain.signal.TurnKind
import com.taraang.app.domain.signal.TurnSignalState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SDK 콜백 감싸기와 SDK→도메인 변환. 실기기 없이 확인할 수 있는 부분만. */
class SdkAdaptersTest {

    @Test
    fun `callback success, failure and timeout`() = runTest {
        assertEquals(SdkOutcome.Ok(1), awaitSdk<Int>(1_000) { ok, _ -> ok(1) })
        val boom = IllegalStateException("x")
        assertEquals(SdkOutcome.Failed(boom), awaitSdk<Int>(1_000) { _, fail -> fail(boom) })
        assertEquals(SdkOutcome.TimedOut, awaitSdk<Int>(1_000) { _, _ -> })
    }

    @Test
    fun `synchronous exception and double callbacks are handled once`() = runTest {
        val denied = SecurityException("Permission required")
        assertEquals(SdkOutcome.Failed(denied), awaitSdk<Int>(1_000) { _, _ -> throw denied })
        assertEquals(SdkOutcome.Ok(1), awaitSdk<Int>(1_000) { ok, fail -> ok(1); ok(2); fail(Exception()) })
    }

    @Test
    fun `callbacks racing on two threads resume exactly once`() = kotlinx.coroutines.runBlocking {
        // isActive 확인 뒤 resume은 두 스레드가 함께 통과하면 IllegalStateException(Already resumed)이 난다(코드 리뷰 L1).
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(2_000) {
            val gate = java.util.concurrent.CyclicBarrier(2)
            val threads = mutableListOf<Thread>()
            val outcome = awaitSdk<Int>(5_000) { ok, fail ->
                listOf<() -> Unit>({ ok(1) }, { fail(IllegalStateException("late")) }).forEach { callback ->
                    threads += Thread {
                        try {
                            gate.await()
                            callback()
                        } catch (e: Throwable) {
                            errors += e
                        }
                    }.also(Thread::start)
                }
            }
            threads.forEach(Thread::join)
            assertTrue("$outcome", outcome is SdkOutcome.Ok || outcome is SdkOutcome.Failed)
        }
        assertTrue("callback errors: ${errors.take(3)}", errors.isEmpty())
    }

    @Test
    fun `errors map to signal status`() {
        assertEquals(SignalStatus.PERMISSION_DENIED, SecurityException().toSignalStatus())
        assertEquals(SignalStatus.PERMISSION_DENIED, Exception("ERROR_SECURITY_PERMISSION").toSignalStatus())
        assertEquals(SignalStatus.UNSUPPORTED, Exception("ERROR_NOT_SUPPORTED_PROPERTY").toSignalStatus())
        assertEquals(SignalStatus.UNSUPPORTED, Exception("ERROR_NOT_SUPPORTED_AREA").toSignalStatus())
        assertEquals(SignalStatus.ERROR, Exception("ERROR_SERVICE_CONNECTION_FAILURE").toSignalStatus())
        assertEquals(SignalStatus.ERROR, Exception().toSignalStatus())
        assertEquals(SignalStatus.ERROR, SdkOutcome.TimedOut.toStatus<Int> { SignalStatus.AVAILABLE })
    }

    @Test
    fun `capability access maps to availability`() {
        assertEquals(SignalStatus.AVAILABLE, CapabilityConfig(AccessType.READ).toStatus())
        assertEquals(SignalStatus.AVAILABLE, CapabilityConfig(AccessType.READ_WRITE).toStatus())
        assertEquals(SignalStatus.UNSUPPORTED, CapabilityConfig(AccessType.NONE).toStatus())
        assertEquals(SignalStatus.UNSUPPORTED, CapabilityConfig(AccessType.WRITE).toStatus())
    }

    @Test
    fun `fluid levels map to a warning, unknown keeps the previous value`() {
        assertEquals(true, PleosMappers.fluidLow(OilLevelState.LOW))
        assertEquals(true, PleosMappers.fluidLow(OilLevelState.CRITICALLY_LOW))
        assertEquals(false, PleosMappers.fluidLow(OilLevelState.NORMAL))
        assertEquals(false, PleosMappers.fluidLow(OilLevelState.HIGH))
        assertNull(PleosMappers.fluidLow(OilLevelState.ERROR))
        assertNull(PleosMappers.fluidLow(null))
    }

    @Test
    fun `grouped signal is available if any input is, otherwise the clearest cause`() {
        assertEquals(SignalStatus.AVAILABLE, combinedStatus(listOf(SignalStatus.UNSUPPORTED, SignalStatus.AVAILABLE, SignalStatus.PERMISSION_DENIED)))
        assertEquals(SignalStatus.PERMISSION_DENIED, combinedStatus(listOf(SignalStatus.UNSUPPORTED, SignalStatus.ERROR, SignalStatus.PERMISSION_DENIED)))
        assertEquals(SignalStatus.ERROR, combinedStatus(listOf(SignalStatus.UNSUPPORTED, SignalStatus.ERROR)))
        assertEquals(SignalStatus.UNSUPPORTED, combinedStatus(listOf(SignalStatus.UNSUPPORTED, SignalStatus.UNSUPPORTED)))
        assertEquals(SignalStatus.UNSUPPORTED, combinedStatus(emptyList()))
    }

    @Test
    fun `turn signal, tbt and caution mapping`() {
        assertEquals(TurnSignalState.LEFT, PleosMappers.turnSignal(TurnSignalValue.LEFT))
        assertNull(PleosMappers.turnSignal(null))
        assertEquals(TurnKind.LEFT, PleosMappers.turnKind(TurnType.TURN_SHARP_L))
        assertEquals(TurnKind.UTURN, PleosMappers.turnKind(TurnType.TURN_UTURN))
        assertEquals(TurnKind.ROUNDABOUT, PleosMappers.turnKind(TurnType.TURN_ENTER_ROUNDABOUT_03))
        assertEquals(TurnKind.OTHER, PleosMappers.turnKind(TurnType.TURN_KEEP_L))
        val next = PleosMappers.nextTurn(listOf(TBTInfo(TurnType.TURN_RIGHT, 42.0, ""), TBTInfo(TurnType.TURN_LEFT, 300.0, "")))
        assertEquals(TurnKind.RIGHT, next?.kind)
        assertEquals(42.0, next!!.distanceM, 0.0)
        assertNull(PleosMappers.nextTurn(emptyList()))
        // 직진·경로 안내가 앞에 있어도 다음 실제 회전을 고른다(코드 리뷰 L2).
        val skipped = PleosMappers.nextTurn(
            listOf(TBTInfo(TurnType.TURN_WAY, 30.0, ""), TBTInfo(TurnType.TURN_STRAIGHT, 80.0, ""), TBTInfo(TurnType.TURN_LEFT, 150.0, "")),
        )
        assertEquals(TurnKind.LEFT, skipped?.kind)
        assertEquals(150.0, skipped!!.distanceM, 0.0)
        assertEquals(TurnKind.ARRIVE, PleosMappers.nextTurn(listOf(TBTInfo(TurnType.TURN_STRAIGHT, 10.0, ""), TBTInfo(TurnType.TURN_ARRIVE, 90.0, "")))?.kind)
        assertNull(PleosMappers.nextTurn(listOf(TBTInfo(TurnType.TURN_STRAIGHT, 10.0, ""))))
        val cautions = PleosMappers.cautions(listOf(CautionPoint("1", CautionPointType.SCHOOL_ZONE, 0), CautionPoint("2", CautionPointType.FOG_AREA, 10)))
        assertEquals(listOf(CautionType.SCHOOL_ZONE, CautionType.OTHER), cautions.map { it.type })
    }
}
