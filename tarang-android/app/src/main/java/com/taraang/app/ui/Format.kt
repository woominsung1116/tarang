package com.taraang.app.ui

import com.taraang.app.core.LatLng
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.model.Skill
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

object Format {
    private val dateTime = DateTimeFormatter.ofPattern("M월 d일 (E) a h:mm", Locale.KOREAN)

    /** 음수·NaN·무한대는 "–"로 보여준다(잘못된 신호 값으로 화면이 죽지 않게). */
    fun distance(meters: Double): String {
        if (!meters.isFinite() || meters < 0) return "–"
        // 10m 단위로 먼저 반올림해 995m가 "1000m"로 보이지 않게 km 경계를 반올림 뒤에 판단한다.
        val rounded = if (meters >= 100) (meters / 10).roundToInt() * 10 else meters.roundToInt()
        return if (rounded >= 1000) "%.1fkm".format(Locale.US, meters / 1000) else "${rounded}m"
    }

    fun distance(meters: Int): String = distance(meters.toDouble())

    fun dateTime(epochMs: Long): String =
        dateTime.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    /** 좌표가 실제로 그릴 수 있는 값인지. API·리플레이에서 온 NaN·범위 밖 값을 걸러낸다. */
    fun isDrawable(p: LatLng): Boolean =
        p.lat.isFinite() && p.lon.isFinite() && p.lat in -90.0..90.0 && p.lon in -180.0..180.0

    fun durationMin(startMs: Long, endMs: Long?): String? {
        if (endMs == null || endMs < startMs) return null
        val min = ((endMs - startMs) / 60_000.0).roundToInt().coerceAtLeast(1)
        return "${min}분"
    }

    /** "1. 금정로 우회전" */
    fun maneuver(m: Maneuver): String {
        val street = m.street.ifBlank { "이름 없는 길" }
        return "${m.index + 1}. $street ${m.skill.label}"
    }

    fun levelName(level: Int): String = when (level) {
        1 -> "출발·정지"
        2 -> "기술 하나"
        3 -> "기술 조합"
        4 -> "목표 경로"
        else -> ""
    }

    /** 위험·난도 순서와 무관하게 화면에서 항상 같은 순서로 보여준다. */
    fun sortedSkills(skills: Map<Skill, Int>): List<Pair<Skill, Int>> =
        Skill.entries.mapNotNull { s -> skills[s]?.takeIf { it > 0 }?.let { s to it } }

    /** 목적격 조사 "을/를". 마지막 한글 글자의 받침으로 고른다("좌회전(신호 미확인)" → "인" → 을). 한글이 없으면 "을". */
    fun objectParticle(word: String): String {
        val last = word.lastOrNull { it in '가'..'힣' } ?: return "을"
        return if ((last - '가') % 28 == 0) "를" else "을"
    }

    /** 리플레이 에셋 경로를 사람이 읽을 이름으로. "replay/demo_l1.json" → "demo_l1" */
    fun assetName(asset: String): String = asset.substringAfterLast('/').substringBeforeLast('.')
}
