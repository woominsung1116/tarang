package com.taraang.app.feedback

import com.taraang.app.domain.model.FeedbackType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeedbackArbiterTest {

    private val config = FeedbackConfig()
    private val arbiter = FeedbackArbiter(config)

    private fun c(type: FeedbackType) = FeedbackCandidate(type, type.name)

    @Test
    fun `highest priority wins when several fire together`() {
        val chosen = arbiter.select(listOf(c(FeedbackType.SKILL_PREVIEW), c(FeedbackType.SPEED_LIMIT_SUSTAINED)), 0, nearTurn = false)
        assertEquals(FeedbackType.SPEED_LIMIT_SUSTAINED, chosen?.type)
    }

    @Test
    fun `min gap blocks non urgent messages`() {
        arbiter.select(listOf(c(FeedbackType.SKILL_PREVIEW)), 0, false)
        assertNull(arbiter.select(listOf(c(FeedbackType.SPEED_LIMIT_SUSTAINED)), config.minGapMs - 1, false))
        assertEquals(FeedbackType.SPEED_LIMIT_SUSTAINED, arbiter.select(listOf(c(FeedbackType.SPEED_LIMIT_SUSTAINED)), config.minGapMs, false)?.type)
    }

    @Test
    fun `new warning ignores gap, cooldown and cap`() {
        arbiter.select(listOf(c(FeedbackType.SKILL_PREVIEW)), 0, false)
        assertEquals(FeedbackType.NEW_WARNING, arbiter.select(listOf(c(FeedbackType.NEW_WARNING)), 1_000, false)?.type)
        assertEquals(FeedbackType.NEW_WARNING, arbiter.select(listOf(c(FeedbackType.NEW_WARNING)), 2_000, false)?.type)
    }

    @Test
    fun `same type cooldown lets a different type through`() {
        arbiter.select(listOf(c(FeedbackType.SPEED_LIMIT_SUSTAINED)), 0, false)
        val t = config.minGapMs + 1
        val chosen = arbiter.select(listOf(c(FeedbackType.SPEED_LIMIT_SUSTAINED), c(FeedbackType.SKILL_PREVIEW)), t, false)
        assertEquals(FeedbackType.SKILL_PREVIEW, chosen?.type)
        assertNull(arbiter.select(listOf(c(FeedbackType.SPEED_LIMIT_SUSTAINED)), config.sameTypeCooldownMs - 1, false))
        assertEquals(
            FeedbackType.SPEED_LIMIT_SUSTAINED,
            arbiter.select(listOf(c(FeedbackType.SPEED_LIMIT_SUSTAINED)), config.sameTypeCooldownMs + config.minGapMs, false)?.type,
        )
    }

    @Test
    fun `silent near a turn even for warnings`() {
        assertNull(arbiter.select(listOf(c(FeedbackType.NEW_WARNING)), 0, nearTurn = true))
    }

    @Test
    fun `cap per drive stops non urgent messages`() {
        val types = FeedbackType.entries.filter { it.priority > 1 }
        var t = 0L
        repeat(config.maxMessagesPerDrive) { i ->
            assertEquals(types[i % types.size], arbiter.select(listOf(c(types[i % types.size])), t, false)?.type)
            t += config.sameTypeCooldownMs
        }
        assertNull(arbiter.select(listOf(c(FeedbackType.SKILL_PREVIEW)), t, false))
        assertEquals(FeedbackType.NEW_WARNING, arbiter.select(listOf(c(FeedbackType.NEW_WARNING)), t, false)?.type)
    }
}
