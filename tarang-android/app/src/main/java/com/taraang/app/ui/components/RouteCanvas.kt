package com.taraang.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taraang.app.core.Geo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.taraang.app.core.LatLng
import com.taraang.app.ui.Format
import com.taraang.app.ui.theme.Divider
import com.taraang.app.ui.theme.Graphite
import com.taraang.app.ui.theme.LightGray
import com.taraang.app.ui.theme.Muted
import com.taraang.app.ui.theme.Orange
import com.taraang.app.ui.theme.White
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** 지도 위 표시 지점. label이 있으면 번호 원, 없으면 작은 핀. */
data class RoutePin(val position: LatLng, val color: Color, val label: String? = null)

/**
 * 타일 없이 그리는 코스 그림. 오프라인·타일 실패 시 지도 대신, 복기 화면의 궤적 재생에 쓴다.
 * [progress]가 null이면 전체 경로를 주황으로, 값이 있으면 지나온 구간만 주황으로 그리고 현재 위치 점을 찍는다.
 */
@Composable
fun RouteCanvas(
    path: List<LatLng>,
    modifier: Modifier = Modifier,
    pins: List<RoutePin> = emptyList(),
    progress: Float? = null,
    contentDescription: String? = null,
) {
    val measurer = rememberTextMeasurer()
    // API·리플레이에서 온 NaN·범위 밖 좌표는 그리지 않는다.
    val path = remember(path) { path.filter(Format::isDrawable) }
    val pins = remember(pins) { pins.filter { Format.isDrawable(it.position) } }
    val track = remember(path) { RouteTrack(path) }
    // 핀이 경로의 어디쯤인지는 프레임마다가 아니라 한 번만 계산한다.
    val pinFractions = remember(track, pins) { pins.map { track.fractionOf(it.position) } }
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(LightGray)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawGrid()
            if (path.size < 2) return@Canvas
            val projector = Projector(path + pins.map { it.position }, size, padding = 48.dp.toPx())
            val routeWidth = 10.dp.toPx()
            val full = projector.path(path)
            drawPath(full, White, style = Stroke(routeWidth + 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(
                full,
                if (progress == null) Orange else Muted,
                style = Stroke(routeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            if (progress != null) {
                val travelled = track.prefix(progress)
                if (travelled.size >= 2) {
                    drawPath(
                        projector.path(travelled),
                        Orange,
                        style = Stroke(routeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
            // 출발점
            val start = projector.toOffset(path.first())
            drawCircle(Graphite, radius = 14.dp.toPx(), center = start)
            drawCircle(White, radius = 6.dp.toPx(), center = start)

            pins.forEachIndexed { i, pin ->
                if (progress != null && pinFractions[i] > progress) return@forEachIndexed
                val c = projector.toOffset(pin.position)
                if (pin.label != null) {
                    val r = 24.dp.toPx()
                    drawCircle(White, radius = r + 3.dp.toPx(), center = c)
                    drawCircle(pin.color, radius = r, center = c)
                    val layout = measurer.measure(
                        pin.label,
                        TextStyle(color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold),
                    )
                    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
                } else {
                    drawCircle(White, radius = 13.dp.toPx(), center = c)
                    drawCircle(pin.color, radius = 10.dp.toPx(), center = c)
                }
            }

            if (progress != null) {
                val here = projector.toOffset(track.pointAt(progress))
                drawCircle(Graphite.copy(alpha = 0.18f), radius = 26.dp.toPx(), center = here)
                drawCircle(Graphite, radius = 14.dp.toPx(), center = here)
                drawCircle(White, radius = 5.dp.toPx(), center = here)
            }
        }
    }
}

private fun DrawScope.drawGrid() {
    val step = 64.dp.toPx()
    var x = step
    while (x < size.width) {
        drawLine(Divider, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        x += step
    }
    var y = step
    while (y < size.height) {
        drawLine(Divider, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        y += step
    }
}

/** 위경도 → 화면 좌표. 좁은 지역이므로 위도 보정한 등장방형 투영으로 충분하다. */
private class Projector(points: List<LatLng>, private val size: Size, padding: Float) {
    private val minLat = points.minOf { it.lat }
    private val maxLat = points.maxOf { it.lat }
    private val minLon = points.minOf { it.lon }
    private val maxLon = points.maxOf { it.lon }
    private val lonScale = cos(Math.toRadians((minLat + maxLat) / 2))
    private val spanX = max((maxLon - minLon) * lonScale, 1e-6)
    private val spanY = max(maxLat - minLat, 1e-6)
    private val scale = min((size.width - 2 * padding) / spanX, (size.height - 2 * padding) / spanY).coerceAtLeast(1.0)
    private val offsetX = (size.width - spanX * scale) / 2
    private val offsetY = (size.height - spanY * scale) / 2

    fun toOffset(p: LatLng): Offset = Offset(
        (offsetX + (p.lon - minLon) * lonScale * scale).toFloat(),
        (offsetY + (maxLat - p.lat) * scale).toFloat(),
    )

    fun path(points: List<LatLng>): Path = Path().apply {
        points.forEachIndexed { i, p ->
            val o = toOffset(p)
            if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
        }
    }
}

/** 경로를 거리 기준 0..1로 다룬다(재생 진행, 핀이 경로의 어디쯤인지). */
private class RouteTrack(private val path: List<LatLng>) {
    private val cumulative: DoubleArray = DoubleArray(path.size).also { acc ->
        for (i in 1 until path.size) acc[i] = acc[i - 1] + Geo.distanceM(path[i - 1], path[i])
    }
    private val total = cumulative.lastOrNull()?.takeIf { it > 0 } ?: 1.0

    fun pointAt(fraction: Float): LatLng {
        if (path.isEmpty()) return LatLng(0.0, 0.0)
        val target = fraction.coerceIn(0f, 1f) * total
        val i = cumulative.indexOfFirst { it >= target }.let { if (it <= 0) return path.first() else it }
        val segment = cumulative[i] - cumulative[i - 1]
        val t = if (segment <= 0) 0.0 else (target - cumulative[i - 1]) / segment
        val a = path[i - 1]
        val b = path[i]
        return LatLng(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    fun prefix(fraction: Float): List<LatLng> {
        if (path.isEmpty()) return emptyList()
        val target = fraction.coerceIn(0f, 1f) * total
        val kept = path.indices.filter { cumulative[it] <= target }.map { path[it] }
        return kept + pointAt(fraction)
    }

    /** 가장 가까운 경로 꼭짓점의 진행 비율. */
    fun fractionOf(p: LatLng): Float {
        if (path.isEmpty()) return 0f
        val nearest = path.indices.minBy { Geo.distanceM(path[it], p) }
        return (cumulative[nearest] / total).toFloat()
    }
}
