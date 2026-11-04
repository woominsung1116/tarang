package com.taraang.app.vehicle

import ai.pleos.playground.navi.helper.NaviHelper
import android.content.Context
import kotlinx.coroutines.CancellationException

/**
 * NaviHelper를 붙인다. SDK는 연결을 최대 30회 재시도하므로 [timeoutMs] 안에 붙지 않으면 포기하고 해제한다.
 * 성공하면 붙은 인스턴스를, 실패하면 원인을 돌려준다.
 */
internal suspend fun connectNaviHelper(context: Context, timeoutMs: Long): SdkOutcome<NaviHelper> {
    val helper = try {
        NaviHelper(context.applicationContext)
    } catch (e: Exception) {
        return SdkOutcome.Failed(e)
    }
    val outcome = try {
        awaitSdk<Unit>(timeoutMs) { ok, fail ->
            helper.initialize(onConnected = { ok(Unit) }, onFailed = { fail(it as? Exception ?: RuntimeException(it)) })
        }
    } catch (e: CancellationException) {
        releaseQuietly(helper)
        throw e
    }
    return when (outcome) {
        is SdkOutcome.Ok -> SdkOutcome.Ok(helper)
        is SdkOutcome.Failed -> {
            releaseQuietly(helper)
            outcome
        }
        SdkOutcome.TimedOut -> {
            releaseQuietly(helper)
            SdkOutcome.TimedOut
        }
    }
}

internal fun releaseQuietly(helper: NaviHelper) {
    runCatching { helper.release() }
}
