package com.taraang.app.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** 테스트에서 시간을 바꿀 수 있게 하는 시계. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}

/** 앱 전체 로깅 진입점. 위치·개인정보는 [redact]로 줄여서 남긴다. */
interface Logger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String, error: Throwable? = null)
    fun e(tag: String, message: String, error: Throwable? = null)

    companion object {
        /** JVM 테스트용. Android에서는 di 레이어가 Logcat 구현을 넣는다. */
        val Noop = object : Logger {
            override fun d(tag: String, message: String) = Unit
            override fun w(tag: String, message: String, error: Throwable?) = Unit
            override fun e(tag: String, message: String, error: Throwable?) = Unit
        }
    }
}

data class LatLng(val lat: Double, val lon: Double) {
    /** 로그용: 소수점 3자리(약 100m). */
    fun redact(): String = "${round(lat * 1000) / 1000},${round(lon * 1000) / 1000}"
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(a: LatLng, b: LatLng): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** 한국 범위(서버 입력 검증과 같은 값, ARCHITECTURE.md 4.2). */
    val KOREA_LAT = 33.0..39.0
    val KOREA_LON = 124.0..132.0

    fun isInKorea(p: LatLng): Boolean = p.lat in KOREA_LAT && p.lon in KOREA_LON
}
