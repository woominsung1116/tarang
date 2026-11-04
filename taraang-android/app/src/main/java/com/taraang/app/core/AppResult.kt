package com.taraang.app.core

import com.taraang.app.domain.signal.SignalKey
import com.taraang.app.domain.signal.SignalStatus
import kotlin.coroutines.cancellation.CancellationException

/** 레이어 경계에서 예외 대신 쓰는 결과 타입. ARCHITECTURE.md 3장. */
sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: AppError) : AppResult<Nothing>
}

sealed interface AppError {
    /** 개발자용 설명. 화면에 그대로 보여주지 않는다. */
    val debugMessage: String

    data class Network(override val debugMessage: String) : AppError
    data class Timeout(override val debugMessage: String) : AppError
    data class Server(val httpStatus: Int, val code: String, override val debugMessage: String) : AppError
    data class NoCourse(override val debugMessage: String) : AppError
    data class SignalUnavailable(val signal: SignalKey, val status: SignalStatus, override val debugMessage: String) : AppError
    data class Navigation(val code: String, override val debugMessage: String) : AppError
    data class Storage(override val debugMessage: String) : AppError
    data class InvalidInput(override val debugMessage: String) : AppError
    data class Unexpected(override val debugMessage: String) : AppError
}

/** 사용자에게 보여줄 문구는 여기서만 만든다. */
fun AppError.userMessage(): String = when (this) {
    is AppError.Network, is AppError.Timeout -> "네트워크에 연결할 수 없어요. 저장된 코스를 보여드릴게요."
    is AppError.Server -> if (code == "SERVER_DISABLED") {
        "이 버전은 코스 서버를 쓰지 않아 목표 경로를 분석할 수 없어요."
    } else {
        "코스 서버에 문제가 있어요. 잠시 후 다시 시도해 주세요."
    }
    is AppError.NoCourse -> "이 위치에서 조건에 맞는 연습 코스를 찾지 못했어요."
    is AppError.SignalUnavailable -> "이 차에서는 일부 차량 정보를 확인할 수 없어요."
    is AppError.Navigation -> "내비게이션에 코스를 보내지 못했어요."
    is AppError.Storage -> "기록을 저장하지 못했어요."
    is AppError.InvalidInput -> "입력값을 확인해 주세요."
    is AppError.Unexpected -> "문제가 생겼어요. 다시 시도해 주세요."
}

inline fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Ok -> AppResult.Ok(transform(value))
    is AppResult.Err -> this
}

inline fun <T> AppResult<T>.onErr(action: (AppError) -> Unit): AppResult<T> {
    if (this is AppResult.Err) action(error)
    return this
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Ok)?.value

/** 예외를 AppError로 바꾼다. 코루틴 취소는 다시 던진다. */
inline fun <T> runCatchingApp(toError: (Throwable) -> AppError = { AppError.Unexpected(it.toString()) }, block: () -> T): AppResult<T> =
    try {
        AppResult.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        AppResult.Err(toError(e))
    }
