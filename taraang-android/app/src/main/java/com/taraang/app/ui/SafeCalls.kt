package com.taraang.app.ui

import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Logger
import com.taraang.app.core.runCatchingApp

/**
 * AppResult를 돌려주지 않는 계약 함수(get, levelProgress, settings.update 등)를 감싼다.
 * 예외는 AppError.Unexpected로 바꿔 한 번 기록하고, 코루틴 취소는 runCatchingApp이 다시 던진다.
 */
inline fun <T> Logger.guard(tag: String, what: String, block: () -> T): AppResult<T> {
    val result = runCatchingApp(block = block)
    if (result is AppResult.Err) w(tag, "$what failed: ${result.error.debugMessage}")
    return result
}

/** 읽기 실패용 문구. Storage는 "저장하지 못했어요"라 읽기 오류에 쓰지 않는다. */
val ReadFailure: AppError = AppError.Unexpected("read failed")
