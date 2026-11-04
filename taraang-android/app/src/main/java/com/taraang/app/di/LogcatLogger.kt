package com.taraang.app.di

import android.util.Log
import com.taraang.app.core.Logger

/**
 * Logcat 구현. 호출부가 [com.taraang.app.core.LatLng.redact]를 쓰는 것이 원칙이지만,
 * 실수로 원본 좌표가 섞여도 남지 않도록 소수점 4자리 이상 숫자는 3자리로 잘라 한 번 더 막는다.
 * 예외 메시지·스택트레이스에도 좌표가 들어갈 수 있어, Throwable을 Log에 넘기지 않고 문자열로 펼쳐 같이 거른다.
 */
class LogcatLogger(private val prefix: String = "Taraang") : Logger {

    override fun d(tag: String, message: String) {
        Log.d("$prefix/$tag", scrub(message))
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        Log.w("$prefix/$tag", render(message, error?.let(Log::getStackTraceString).orEmpty()))
    }

    override fun e(tag: String, message: String, error: Throwable?) {
        Log.e("$prefix/$tag", render(message, error?.let(Log::getStackTraceString).orEmpty()))
    }

    internal companion object {
        private val PRECISE_NUMBER = Regex("""(-?\d{1,3}\.\d{3})\d+""")

        fun scrub(message: String): String = PRECISE_NUMBER.replace(message) { it.groupValues[1] }

        /** 메시지와 스택트레이스를 합쳐 한 번에 거른다. */
        fun render(message: String, stackTrace: String): String =
            scrub(if (stackTrace.isEmpty()) message else message + "\n" + stackTrace)
    }
}
