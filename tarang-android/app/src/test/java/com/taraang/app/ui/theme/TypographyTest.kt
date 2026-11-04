package com.taraang.app.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/** ARCHITECTURE.md 8장: 차량 화면 글자는 24sp 이상. 테마의 모든 글자 스타일이 기준을 지키는지 본다. */
class TypographyTest {
    @Test
    fun `every text style is at least 24sp`() {
        val t = TaraangTypography
        val styles = mapOf(
            "displayLarge" to t.displayLarge, "displayMedium" to t.displayMedium, "displaySmall" to t.displaySmall,
            "headlineLarge" to t.headlineLarge, "headlineMedium" to t.headlineMedium, "headlineSmall" to t.headlineSmall,
            "titleLarge" to t.titleLarge, "titleMedium" to t.titleMedium, "titleSmall" to t.titleSmall,
            "bodyLarge" to t.bodyLarge, "bodyMedium" to t.bodyMedium, "bodySmall" to t.bodySmall,
            "labelLarge" to t.labelLarge, "labelMedium" to t.labelMedium, "labelSmall" to t.labelSmall,
        )
        val small = styles.filter { (_, s) -> s.fontSize.value < 24f }.mapValues { it.value.fontSize }
        assertTrue("24sp 미만: $small", small.isEmpty())
        val tight = styles.filter { (_, s) -> s.lineHeight.value < s.fontSize.value }.keys
        assertTrue("줄 높이가 글자보다 작음: $tight", tight.isEmpty())
    }
}
