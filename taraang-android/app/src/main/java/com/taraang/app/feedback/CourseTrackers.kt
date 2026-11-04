package com.taraang.app.feedback

import com.taraang.app.core.Geo
import com.taraang.app.core.LatLng
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.signal.Caution
import com.taraang.app.domain.signal.CautionType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** 코스 선(polyline)과 그 위 누적 거리. 수백 m~수 km 범위라 지점 근처 평면 근사로 충분하다. */
class CoursePath(private val points: List<LatLng>) {

    /** 선 위 투영 결과: 선까지 거리와 출발점부터 선을 따라 간 거리. */
    data class Projection(val offsetM: Double, val alongM: Double)

    private val cumulative = DoubleArray(points.size).also { acc ->
        for (i in 1 until points.size) acc[i] = acc[i - 1] + Geo.distanceM(points[i - 1], points[i])
    }

    val isUsable: Boolean get() = points.size >= 2
    val lengthM: Double get() = cumulative.lastOrNull() ?: 0.0

    /** 누적 거리가 [fromM]..[toM]과 겹치는 구간 중 가장 가까운 투영. 없으면 null. */
    fun project(p: LatLng, fromM: Double = 0.0, toM: Double = Double.MAX_VALUE): Projection? {
        var best: Projection? = null
        for (i in 0 until points.size - 1) {
            if (cumulative[i + 1] < fromM || cumulative[i] > toM) continue
            val proj = projectOnSegment(p, i)
            if (best == null || proj.offsetM < best.offsetM) best = proj
        }
        return best
    }

    /**
     * [fromM]부터 선을 따라가며 [maxOffsetM] 안에 처음 들어오는 투영. 순환 코스는 같은 곳을 다시 지나므로
     * "가장 가까운 곳"이 아니라 "앞쪽에서 가장 먼저 만나는 곳"을 고른다(끝으로 건너뛰지 않게).
     */
    fun firstWithin(p: LatLng, maxOffsetM: Double, fromM: Double = 0.0): Projection? {
        for (i in 0 until points.size - 1) {
            if (cumulative[i + 1] < fromM) continue
            val proj = projectOnSegment(p, i)
            if (proj.offsetM <= maxOffsetM && proj.alongM >= fromM) return proj
        }
        return null
    }

    private fun projectOnSegment(p: LatLng, i: Int): Projection {
        val a = points[i]
        val b = points[i + 1]
        val mPerDegLon = M_PER_DEG_LAT * cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * mPerDegLon
        val ay = (a.lat - p.lat) * M_PER_DEG_LAT
        val dx = (b.lon - a.lon) * mPerDegLon
        val dy = (b.lat - a.lat) * M_PER_DEG_LAT
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq == 0.0) 0.0 else (-(ax * dx + ay * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return Projection(offsetM = sqrt(cx * cx + cy * cy), alongM = cumulative[i] + t * (cumulative[i + 1] - cumulative[i]))
    }

    private companion object {
        const val M_PER_DEG_LAT = 111_320.0
    }
}

/**
 * 코스 위 진행 거리. 뒤로 가지 않는다(단조 증가).
 * 순환 코스는 출발점과 끝이 겹치고 같은 교차로를 두 번 지나기도 하므로, 직전 진행 거리 근처([BACK_M] 뒤 ~ [AHEAD_M] 앞)에서만
 * 가장 가까운 곳을 찾는다. 그 창에서 코스 선 근처를 못 찾으면(이탈 후 복귀 등) 앞쪽에서 처음 만나는 곳으로 잇는다.
 */
class PathProgress(private val path: CoursePath, private val config: FeedbackConfig = FeedbackConfig()) {

    var progressM: Double? = null
        private set

    fun update(position: LatLng?): Double? {
        if (position == null || !path.isUsable) return progressM
        val last = progressM ?: 0.0
        val near = path.project(position, last - BACK_M, last + AHEAD_M)?.takeIf { it.offsetM <= config.offCourseM }
            ?: path.firstWithin(position, config.offCourseM, fromM = last)
        if (near != null) progressM = maxOf(near.alongM, last)
        return progressM
    }

    private companion object {
        const val BACK_M = 50.0
        const val AHEAD_M = 300.0
    }
}

data class ManeuverProgress(val maneuver: Maneuver, val distanceM: Double)

/**
 * 코스 회전 지점을 순서대로 따라간다. 진행 거리([PathProgress])가 있으면 지점을 지났는지와 남은 거리를 코스 선을 따라 잰다.
 * 직선거리만 쓰면 순환 코스에서 같은 교차로를 먼저 한 번 지날 때 나중 지점을 "도착"으로 넘기거나,
 * 출발점 근처 지점이 먼저 가까워져 중간 지점을 건너뛴다(L2-s11에서 1→3으로 뛰던 문제).
 * 지점의 distanceFromStartM이 코스 선 위 그 위치와 맞지 않으면(값 누락으로 0 등) 그 지점만 직선거리로 따라간다.
 */
class ManeuverTracker(
    maneuvers: List<Maneuver>,
    private val config: FeedbackConfig = FeedbackConfig(),
    path: CoursePath? = null,
) {

    private val ordered = maneuvers.sortedBy { it.index }
    private val alongTrusted = BooleanArray(ordered.size) { i -> path != null && path.isUsable && isOnPathAt(path, ordered[i]) }
    private var cursor = 0

    /**
     * 지난 지점을 넘기고, 아직 안 지난 지점 중 [include]를 만족하는 첫 지점과 그까지 거리를 돌려준다.
     * [progressM]이 없으면(코스 선 없음·아직 코스 위에 못 올라옴) 직선거리와 도착 반경으로만 넘긴다.
     */
    fun update(position: LatLng?, progressM: Double? = null, include: (Maneuver) -> Boolean = { true }): ManeuverProgress? {
        if (position == null) return null
        while (cursor < ordered.size) {
            val m = ordered[cursor]
            val passed = if (progressM != null && alongTrusted[cursor]) {
                progressM > m.distanceFromStartM + config.maneuverReachedM
            } else {
                Geo.distanceM(position, m.position) <= config.maneuverReachedM
            }
            if (passed) cursor++ else break
        }
        var i = cursor
        while (i < ordered.size && !include(ordered[i])) i++
        val next = ordered.getOrNull(i) ?: return null
        val distanceM = if (progressM != null && alongTrusted[i]) {
            (next.distanceFromStartM - progressM).coerceAtLeast(0.0)
        } else {
            Geo.distanceM(position, next.position)
        }
        return ManeuverProgress(next, distanceM)
    }

    /** 지점 위치가 코스 선 위 distanceFromStartM 근처([FeedbackConfig.maneuverMatchM] 안)에 실제로 있는가. */
    private fun isOnPathAt(path: CoursePath, m: Maneuver): Boolean {
        val window = config.maneuverMatchM
        val proj = path.project(m.position, m.distanceFromStartM - window, m.distanceFromStartM + window) ?: return false
        return proj.offsetM <= config.maneuverReachedM && abs(proj.alongM - m.distanceFromStartM) <= window
    }

    /** 이벤트를 붙일 코스 지점: 현재 위치에서 가장 가까운 지점이 일정 거리 안일 때만. */
    fun nearestIndex(position: LatLng?): Int? {
        if (position == null) return null
        val nearest = ordered.minByOrNull { Geo.distanceM(position, it.position) } ?: return null
        return nearest.index.takeIf { Geo.distanceM(position, nearest.position) <= config.maneuverMatchM }
    }
}

data class SchoolZoneState(val aheadM: Int?, val inside: Boolean, val cautionPresent: Boolean)

/**
 * 어린이보호구역 판정(ARCHITECTURE.md 4.3): SCHOOL_ZONE caution의 remainM이 0(이하)이면 구역 안, 양수면 접근 중.
 * 실차 NaviHelper CautionPoint.remainDistance도 같은 규칙으로 읽는다. 상태가 없어 틱마다 다시 계산한다.
 */
object SchoolZone {
    fun evaluate(cautions: List<Caution>, config: FeedbackConfig): SchoolZoneState {
        var nearest = Int.MAX_VALUE
        for (c in cautions) if (c.type == CautionType.SCHOOL_ZONE && c.remainM < nearest) nearest = c.remainM
        if (nearest == Int.MAX_VALUE) return NONE
        return SchoolZoneState(
            aheadM = nearest.takeIf { it in 1..config.schoolZoneAheadM },
            inside = nearest <= 0,
            cautionPresent = true,
        )
    }

    private val NONE = SchoolZoneState(aheadM = null, inside = false, cautionPresent = false)
}

/**
 * 코스 선에서 일정 거리 이상 일정 시간 벗어나면 이탈로 본다. 이탈 중에는 회전 판정을 멈춘다(오탐 방지).
 */
class OffCourseDetector(private val path: CoursePath, private val config: FeedbackConfig = FeedbackConfig()) {

    private var farSince: Long? = null
    var offCourse: Boolean = false
        private set

    /** 이탈이 새로 확정된 순간에만 true. */
    fun update(position: LatLng?, nowMs: Long): Boolean {
        val offset = if (position != null && path.isUsable) path.project(position)?.offsetM else null
        if (offset == null || offset <= config.offCourseM) {
            farSince = null
            offCourse = false
            return false
        }
        val since = farSince ?: nowMs.also { farSince = it }
        if (!offCourse && nowMs - since >= config.offCourseSustainMs) {
            offCourse = true
            return true
        }
        return false
    }
}
