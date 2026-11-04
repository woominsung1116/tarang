package com.taraang.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taraang.app.R
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.ui.Format
import com.taraang.app.ui.theme.Divider
import com.taraang.app.ui.theme.Graphite
import com.taraang.app.ui.theme.Graphite2
import com.taraang.app.ui.theme.Orange
import com.taraang.app.ui.theme.OrangeSoft

/**
 * 4단계 계단. 지난 단계는 주황, 현재 단계는 완료 비율만큼 채운다.
 * 점수가 아니라 "어디까지 왔는지"만 보여준다.
 */
@Composable
fun LevelStepper(progress: LevelProgress, modifier: Modifier = Modifier) {
    val current = progress.currentLevel.coerceIn(1, LevelProgress.MAX_LEVEL)
    val ratio = if (progress.requiredToAdvance <= 0) 1f
    else (progress.completedCoursesAtLevel.toFloat() / progress.requiredToAdvance).coerceIn(0f, 1f)
    val description = stringResource(
        R.string.level_progress_a11y,
        current,
        Format.levelName(current),
        progress.completedCoursesAtLevel,
        progress.requiredToAdvance,
    )
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = description }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (level in 1..LevelProgress.MAX_LEVEL) {
                Column(Modifier.weight(1f)) {
                    val fill = when {
                        level < current -> 1f
                        level == current -> ratio
                        else -> 0f
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (level <= current) OrangeSoft else Divider),
                    ) {
                        if (fill > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fill)
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Orange),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$level ${Format.levelName(level)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (level == current) FontWeight.Bold else FontWeight.Medium,
                        color = if (level == current) Graphite else Graphite2,
                    )
                }
            }
        }
    }
}
