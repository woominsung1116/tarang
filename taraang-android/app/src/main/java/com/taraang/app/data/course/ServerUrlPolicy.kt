package com.taraang.app.data.course

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 코스 서버 주소 검증(ARCHITECTURE.md 7장 18번). 설정 저장과 요청 직전에 같은 규칙을 쓴다.
 * - http/https만. 평문 http는 debug 빌드([allowCleartext])에서 src/debug network_security_config와 같은 호스트(10.0.2.2, localhost)만.
 *   release는 평문을 전혀 허용하지 않는다(네트워크 설정도 평문 금지).
 * - 주소에 계정 정보(user:pass@)·쿼리·프래그먼트 금지, 길이 제한.
 * 통과하면 끝의 '/'를 뗀 정규화 주소, 아니면 null.
 */
object ServerUrlPolicy {
    private val CLEARTEXT_HOSTS = setOf("10.0.2.2", "localhost")
    private const val MAX_LENGTH = 200

    /** [allowCleartext]는 기본 false(평문 거부). 앱은 BuildConfig.DEBUG를 넘긴다. */
    fun validate(raw: String, allowCleartext: Boolean = false): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LENGTH) return null
        val url = trimmed.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.query != null || url.fragment != null) return null
        if (url.scheme == "http" && (!allowCleartext || url.host !in CLEARTEXT_HOSTS)) return null
        return url.toString().trimEnd('/')
    }
}
