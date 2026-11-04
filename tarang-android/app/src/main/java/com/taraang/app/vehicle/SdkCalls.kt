package com.taraang.app.vehicle

import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import com.taraang.app.domain.signal.SignalStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** SDK 콜백 호출 결과. 서비스가 안 붙으면 콜백이 영영 안 올 수 있어 시간 초과를 따로 둔다. */
internal sealed interface SdkOutcome<out T> {
    data class Ok<T>(val value: T) : SdkOutcome<T>
    data class Failed(val error: Throwable) : SdkOutcome<Nothing>
    data object TimedOut : SdkOutcome<Nothing>
}

/**
 * onComplete/onFailed 콜백 API를 suspend로 감싼다. 동기 예외(SecurityException 등)도 Failed로 바꾸고,
 * 콜백이 두 번 와도(서로 다른 스레드에서 동시에 와도) 한 번만 반영한다. isActive 확인 후 resume은
 * 두 스레드가 같이 통과할 수 있어 원자 플래그로 막는다.
 */
internal suspend fun <T> awaitSdk(
    timeoutMs: Long,
    call: (onComplete: (T) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
): SdkOutcome<T> = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine<SdkOutcome<T>> { cont ->
        val resumed = AtomicBoolean(false)
        fun finish(outcome: SdkOutcome<T>) {
            if (resumed.compareAndSet(false, true) && cont.isActive) cont.resume(outcome)
        }
        try {
            call({ value -> finish(SdkOutcome.Ok(value)) }, { error -> finish(SdkOutcome.Failed(error)) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finish(SdkOutcome.Failed(e))
        }
    }
} ?: SdkOutcome.TimedOut

internal fun CapabilityConfig.toStatus(): SignalStatus = when (accessType) {
    AccessType.READ, AccessType.READ_WRITE -> SignalStatus.AVAILABLE
    AccessType.NONE, AccessType.WRITE -> SignalStatus.UNSUPPORTED
}

/**
 * 배포된 Vehicle AAR은 VehicleException 클래스 이름이 난독화돼 있고 ErrorCode가 든 vehicle-data는 runtime 의존성이라
 * 타입으로 구분할 수 없다. VehicleException.message가 ErrorCode 이름(ERROR_...)인 점을 이용한다.
 */
internal fun Throwable.toSignalStatus(): SignalStatus {
    if (this is SecurityException) return SignalStatus.PERMISSION_DENIED
    val code = message.orEmpty()
    return when {
        code.contains("ERROR_SECURITY_PERMISSION") -> SignalStatus.PERMISSION_DENIED
        code.contains("ERROR_NOT_SUPPORTED") -> SignalStatus.UNSUPPORTED
        else -> SignalStatus.ERROR
    }
}

internal fun <T> SdkOutcome<T>.toStatus(onOk: (T) -> SignalStatus): SignalStatus = when (this) {
    is SdkOutcome.Ok -> onOk(value)
    is SdkOutcome.Failed -> error.toSignalStatus()
    SdkOutcome.TimedOut -> SignalStatus.ERROR
}

/**
 * 여러 입력을 묶은 신호(경고 등)의 상태: 하나라도 AVAILABLE이면 AVAILABLE. 아니면 원인이 분명한 순서
 * (권한 없음 → 오류 → 미지원)로 고른다. 입력이 없으면 미지원.
 */
internal fun combinedStatus(statuses: Collection<SignalStatus>): SignalStatus = when {
    SignalStatus.AVAILABLE in statuses -> SignalStatus.AVAILABLE
    SignalStatus.PERMISSION_DENIED in statuses -> SignalStatus.PERMISSION_DENIED
    SignalStatus.ERROR in statuses || SignalStatus.UNKNOWN in statuses -> SignalStatus.ERROR
    else -> SignalStatus.UNSUPPORTED
}
