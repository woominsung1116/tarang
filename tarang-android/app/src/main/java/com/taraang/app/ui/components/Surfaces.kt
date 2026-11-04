package com.taraang.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taraang.app.R
import com.taraang.app.ui.theme.Divider
import com.taraang.app.ui.theme.Graphite
import com.taraang.app.ui.theme.Graphite2
import com.taraang.app.ui.theme.LightGray
import com.taraang.app.ui.theme.Orange
import com.taraang.app.ui.theme.OrangeSoft
import com.taraang.app.ui.theme.OrangeText
import com.taraang.app.ui.theme.White

/** 주차 중 화면의 공통 틀: 흰 배경, 큰 뒤로가기, 제목. */
@Composable
fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(White)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 24.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                LargeIconButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), onBack)
                Spacer(Modifier.width(8.dp))
            } else {
                Spacer(Modifier.width(20.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                color = Graphite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
        content()
    }
}

/** 연한 회색 카드. 그림자 없이 면으로만 구분한다. */
@Composable
fun TaraangCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: Int = 24,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .clip(shape)
            .background(LightGray)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding.dp),
        content = content,
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Graphite)
        trailing?.invoke()
    }
}

/**
 * 코스 경고 등 "출발 전 확인할 것" 메모. 빨강이 아닌 주황 톤으로, 겁주지 않는 문장으로 쓴다.
 * 빨강은 차량 경고 전용이다.
 */
@Composable
fun NoteBox(
    lines: List<String>,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector = Icons.Filled.Info,
) {
    if (lines.isEmpty()) return
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OrangeSoft)
            .padding(20.dp),
    ) {
        Icon(icon, contentDescription = null, tint = OrangeText, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = Graphite)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Graphite) }
        }
    }
}

/** 카드 안의 작은 라벨(예: "저장된 코스"). */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    // 테두리를 둬서 흰 배경·회색 카드 어디서든 라벨로 읽히게 한다.
    // 22sp 라벨은 주황 면 위에 둘 수 없다(흰 글자 3.19:1). 강조는 연주황 면 + OrangeText(4.57:1).
    // 대비: Graphite2/흰 6.34:1.
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .clip(shape)
            .background(if (emphasized) OrangeSoft else White)
            .border(1.dp, if (emphasized) Orange else Divider, shape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasized) OrangeText else Graphite2,
        )
    }
}

/** 큰 터치 영역의 목록 행. 설정·진입 메뉴에 쓴다. */
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TouchTarget)
            .clip(RoundedCornerShape(20.dp))
            .background(LightGray)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Graphite, modifier = Modifier.size(36.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Graphite)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Graphite2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Graphite2, modifier = Modifier.size(36.dp))
    }
}
