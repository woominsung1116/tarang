package com.taraang.app.vehicle

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.Geo
import com.taraang.app.core.LatLng
import com.taraang.app.core.Logger
import com.taraang.app.domain.signal.NavigationLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 코스를 차량 내비로 넘긴다(requestRouteWithWaypoints). 출발지로 돌아오는 순환 코스라 목적지는 origin이다.
 *
 * - NaviHelper SDK 3.0.5의 [WaypointIndex]는 FIRST/SECOND뿐이라 경유지는 최대 2개다. 더 많으면 처음과 마지막을 쓴다.
 * - 요청은 응답 없는 IPC다. onRoutingCompleted/onRouteStarted면 성공, onError면 실패로 본다.
 *   [resultTimeoutMs] 안에 둘 다 없으면 요청은 전달된 것으로 보고 성공 처리한다(내비 화면에서 사용자가 확인 가능).
 */
class PleosNavigationLauncher(
    context: Context,
    private val logger: Logger,
    private val connectTimeoutMs: Long = 10_000,
    private val resultTimeoutMs: Long = 15_000,
) : NavigationLauncher {

    private val appContext = context.applicationContext

    override suspend fun startCourse(origin: LatLng, waypoints: List<LatLng>, title: String): AppResult<Unit> {
        if (!Geo.isInKorea(origin) || waypoints.any { !Geo.isInKorea(it) }) {
            return AppResult.Err(AppError.InvalidInput("경로 좌표가 한국 범위 밖"))
        }
        return withContext(Dispatchers.Main.immediate) { send(origin, pickWaypoints(waypoints), title) }
    }

    private suspend fun send(origin: LatLng, waypoints: List<LatLng>, title: String): AppResult<Unit> {
        val helper = when (val connected = connectNaviHelper(appContext, connectTimeoutMs)) {
            is SdkOutcome.Ok -> connected.value
            is SdkOutcome.Failed -> return AppResult.Err(AppError.Navigation("CONNECT_FAILED", connected.error.javaClass.simpleName))
            SdkOutcome.TimedOut -> return AppResult.Err(AppError.Navigation("CONNECT_TIMEOUT", "NaviHelper ${connectTimeoutMs}ms 안에 연결 안 됨"))
        }
        val result = CompletableDeferred<AppResult<Unit>>()
        val listener = object : NaviHelperEventListener {
            override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
                result.complete(AppResult.Ok(Unit))
            }

            override fun onRoutingCompleted() {
                result.complete(AppResult.Ok(Unit))
            }

            override fun onError(errorCode: NaviErrorCode) {
                result.complete(AppResult.Err(AppError.Navigation(errorCode.name, "경로 요청 실패")))
            }
        }
        return try {
            helper.addListener(listener)
            helper.requestRouteWithWaypoints(buildRequest(origin, waypoints, title))
            withTimeoutOrNull(resultTimeoutMs) { result.await() } ?: run {
                logger.w(TAG, "경로 요청 응답 없음, 전달된 것으로 처리")
                AppResult.Ok(Unit)
            }
        } catch (e: SecurityException) {
            AppResult.Err(AppError.Navigation("PERMISSION_DENIED", e.javaClass.simpleName))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppResult.Err(AppError.Navigation("REQUEST_FAILED", e.javaClass.simpleName))
        } finally {
            runCatching { helper.removeListener(listener) }
            releaseQuietly(helper)
        }
    }

    private companion object {
        const val TAG = "NaviLauncher"
        const val MAX_WAYPOINTS = 2

        fun pickWaypoints(all: List<LatLng>): List<LatLng> =
            if (all.size <= MAX_WAYPOINTS) all else listOf(all.first(), all.last())

        /** poiId·poiSubId·address는 필수 필드라 앱 고유의 합성 값을 넣는다. */
        fun buildRequest(origin: LatLng, waypoints: List<LatLng>, title: String) = RouteWithWaypointsRequest(
            destination = RouteInfo(
                latitude = origin.lat,
                longitude = origin.lon,
                poiName = title.take(40),
                poiId = "taraang-dest",
                address = "",
                poiSubId = "0",
            ),
            waypoints = waypoints.mapIndexed { i, p ->
                RequestWaypointInfo(
                    latitude = p.lat,
                    longitude = p.lon,
                    poiName = "연습 경유지 ${i + 1}",
                    waypointIndex = if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                    poiId = "taraang-wp-${i + 1}",
                    address = "",
                    poiSubId = "0",
                )
            },
        )
    }
}
