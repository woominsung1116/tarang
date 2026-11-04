package com.taraang.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.taraang.app.R
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.taraang.app.core.LatLng
import com.taraang.app.ui.Format
import com.taraang.app.ui.UiConfig
import com.taraang.app.ui.theme.Graphite2
import com.taraang.app.ui.theme.Orange
import com.taraang.app.ui.theme.White
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * 코스 지도. OSM 타일 위에 주황 경로와 번호 표시를 그린다.
 * 오프라인이거나 타일을 못 받으면 [RouteCanvas]로 대신 그린다(시연장 네트워크가 없어도 화면이 비지 않게).
 */
@Composable
fun CourseMap(path: List<LatLng>, pins: List<RoutePin>, contentDescription: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    var useFallback by remember { mutableStateOf(preview || !isOnline(context)) }
    val path = remember(path) { path.filter(Format::isDrawable) }
    val pins = remember(pins) { pins.filter { Format.isDrawable(it.position) } }

    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .semantics { this.contentDescription = contentDescription },
    ) {
        if (useFallback || path.size < 2) {
            RouteCanvas(path, Modifier.fillMaxSize(), pins = pins)
        } else {
            OsmRouteMap(path, pins, Modifier.fillMaxSize(), onTilesUnavailable = { useFallback = true })
        }
        Text(
            stringResource(R.string.osm_attribution),
            style = MaterialTheme.typography.labelSmall,
            color = Graphite2,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(White.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun OsmRouteMap(
    path: List<LatLng>,
    pins: List<RoutePin>,
    modifier: Modifier,
    onTilesUnavailable: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tilesLoaded by remember { mutableIntStateOf(0) }

    val mapView = remember {
        configureOsmdroid(context)
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            setMinZoomLevel(5.0)
            tileProvider.tileRequestCompleteHandlers.add(
                Handler(Looper.getMainLooper()) { msg ->
                    if (msg.what == MapTileProviderBase.MAPTILE_SUCCESS_ID) tilesLoaded++
                    false
                },
            )
        }
    }

    val tilesUnavailable by rememberUpdatedState(onTilesUnavailable)
    LaunchedEffect(mapView) {
        delay(UiConfig.TILE_GRACE_MS)
        if (tilesLoaded == 0) tilesUnavailable()
    }

    // 경로가 바뀔 때만 다시 그린다. 화면 재구성마다 그리면 사용자가 맞춘 확대가 풀린다.
    LaunchedEffect(mapView, path, pins) { bindRoute(mapView, path, pins) }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

private fun bindRoute(view: MapView, path: List<LatLng>, pins: List<RoutePin>) {
    val points = path.map { GeoPoint(it.lat, it.lon) }
    val density = view.context.resources.displayMetrics.density
    view.overlays.clear()
    view.overlays.add(
        Polyline(view).apply {
            setPoints(points)
            outlinePaint.color = Orange.toArgb()
            outlinePaint.strokeWidth = 10f * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            outlinePaint.isAntiAlias = true
        },
    )
    pins.forEach { pin ->
        view.overlays.add(
            Marker(view).apply {
                position = GeoPoint(pin.position.lat, pin.position.lon)
                icon = pinDrawable(view.context, pin, density)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = pin.label
                setOnMarkerClickListener { _, _ -> true }
            },
        )
    }
    val fit = {
        if (points.size >= 2) {
            view.zoomToBoundingBox(BoundingBox.fromGeoPoints(points).increaseByScale(1.3f), false, (32 * density).toInt())
        }
        view.invalidate()
    }
    if (view.width > 0 && view.height > 0) fit() else view.addOnFirstLayoutListener { _, _, _, _, _ -> fit() }
}

/** 번호 원(흰 테두리) 비트맵. */
private fun pinDrawable(context: Context, pin: RoutePin, density: Float): BitmapDrawable {
    val radius = (if (pin.label != null) 20 else 10) * density
    val border = 3 * density
    val sizePx = ((radius + border) * 2).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = sizePx / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(c, c, radius + border, paint)
    paint.color = pin.color.toArgb()
    canvas.drawCircle(c, c, radius, paint)
    if (pin.label != null) {
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 18 * density
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        val y = c - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(pin.label, c, y, paint)
    }
    return BitmapDrawable(context.resources, bitmap)
}

private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    config.userAgentValue = context.packageName
    // 외부 저장소 권한 없이 앱 캐시에 타일을 둔다.
    val base = File(context.cacheDir, "osmdroid")
    config.osmdroidBasePath = base
    config.osmdroidTileCache = File(base, "tiles")
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
