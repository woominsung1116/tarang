package com.taraang.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.taraang.app.domain.model.Skill
import com.taraang.app.ui.Format
import com.taraang.app.ui.theme.Divider
import com.taraang.app.ui.theme.Graphite
import com.taraang.app.ui.theme.Orange
import com.taraang.app.ui.theme.OrangeSoft
import com.taraang.app.ui.theme.OrangeText
import com.taraang.app.ui.theme.White

/** 운전 기술 칩. 표시 전용. "우회전 ×3" */
@Composable
fun SkillChip(skill: Skill, count: Int?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(White)
            .border(BorderStroke(1.dp, Divider), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            if (count != null && count > 1) "${skill.label} ×$count" else skill.label,
            style = MaterialTheme.typography.labelMedium,
            color = Graphite,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillChips(skills: Map<Skill, Int>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Format.sortedSkills(skills).forEach { (skill, count) -> SkillChip(skill, count) }
    }
}

/** 한 번 탭 선택 칩(느낌 기록 등). 76dp 터치 높이. */
@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(38.dp)
    Row(
        modifier
            .defaultMinSize(minHeight = TouchTarget)
            .clip(shape)
            .background(if (selected) OrangeSoft else White)
            .border(BorderStroke(2.dp, if (selected) Orange else Divider), shape)
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
            }
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 색만으로 선택을 알리지 않는다: 선택되면 체크 아이콘이 붙는다.
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = OrangeText, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) OrangeText else Graphite)
    }
}
