package com.taraang.app.ui

import com.taraang.app.core.LatLng
import com.taraang.app.domain.model.DataConfidence
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.model.Skill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test
    fun `distance rounds to 1m below 100m and 10m above`() {
        assertEquals("0m", Format.distance(0.0))
        assertEquals("50m", Format.distance(50.4))
        assertEquals("100m", Format.distance(99.6))
        assertEquals("160m", Format.distance(155.0))
        assertEquals("990m", Format.distance(994.0))
    }

    @Test
    fun `distance switches to km after rounding so 995m is not shown as 1000m`() {
        assertEquals("1.0km", Format.distance(995.0))
        assertEquals("1.0km", Format.distance(1000.0))
        assertEquals("1.4km", Format.distance(1356))
    }

    @Test
    fun `distance hides invalid values`() {
        assertEquals("–", Format.distance(-1.0))
        assertEquals("–", Format.distance(Double.NaN))
        assertEquals("–", Format.distance(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `duration needs an end after the start and shows at least one minute`() {
        assertNull(Format.durationMin(1_000, null))
        assertNull(Format.durationMin(1_000, 999))
        assertEquals("1분", Format.durationMin(0, 10_000))
        assertEquals("14분", Format.durationMin(0, 14 * 60_000L))
    }

    @Test
    fun `isDrawable rejects NaN and out of range coordinates`() {
        assertTrue(Format.isDrawable(LatLng(35.23, 129.08)))
        assertFalse(Format.isDrawable(LatLng(Double.NaN, 129.0)))
        assertFalse(Format.isDrawable(LatLng(91.0, 129.0)))
        assertFalse(Format.isDrawable(LatLng(35.0, -181.0)))
    }

    @Test
    fun `maneuver numbers from 1 and names blank streets`() {
        val m = Maneuver(0, Skill.RIGHT_TURN, " ", LatLng(35.0, 129.0), 0.0, DataConfidence.VERIFIED)
        assertEquals("1. 이름 없는 길 우회전", Format.maneuver(m))
        assertEquals("3. 금정로 우회전", Format.maneuver(m.copy(index = 2, street = "금정로")))
    }

    @Test
    fun `sortedSkills keeps enum order and drops zero counts`() {
        val sorted = Format.sortedSkills(mapOf(Skill.MERGE to 1, Skill.RIGHT_TURN to 2, Skill.U_TURN to 0))
        assertEquals(listOf(Skill.RIGHT_TURN to 2, Skill.MERGE to 1), sorted)
    }

    @Test
    fun `levelName is empty outside 1 to 4 and assetName strips folder and extension`() {
        assertEquals("출발·정지", Format.levelName(1))
        assertEquals("", Format.levelName(0))
        assertEquals("", Format.levelName(5))
        assertEquals("demo_l1", Format.assetName("replay/demo_l1.json"))
        assertEquals("demo", Format.assetName("demo"))
    }

    @Test
    fun `objectParticle follows the final consonant of the last Hangul syllable`() {
        assertEquals("을", Format.objectParticle("신호 좌회전"))
        assertEquals("를", Format.objectParticle("합류"))
        assertEquals("을", Format.objectParticle("좌회전(신호 미확인)"))
        assertEquals("를", Format.objectParticle("유턴·어린이보호구역 진입·회전교차로"))
        assertEquals("을", Format.objectParticle("abc"))
    }
}
