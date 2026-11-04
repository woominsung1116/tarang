package com.taraang.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taraang.app.ui.theme.Divider
import com.taraang.app.ui.theme.Graphite
import com.taraang.app.ui.theme.Graphite2
import com.taraang.app.ui.theme.Orange
import com.taraang.app.ui.theme.White

/** 차량 화면 최소 터치 높이. ARCHITECTURE.md 6장. */
val TouchTarget = 76.dp

private val ButtonPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    containerColor: Color = Orange,
    // 주황 위 흰 글자는 3.19:1이라 굵은 24sp(큰 글자 기준 3:1)로만 쓴다. ButtonContent가 굵게 그린다.
    contentColor: Color = White,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.defaultMinSize(minHeight = TouchTarget),
        shape = MaterialTheme.shapes.medium,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = Divider,
            disabledContentColor = Graphite2,
        ),
    ) {
        ButtonContent(text, icon, loading, contentColor)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.defaultMinSize(minHeight = TouchTarget),
        shape = MaterialTheme.shapes.medium,
        contentPadding = ButtonPadding,
        border = BorderStroke(2.dp, if (enabled) Graphite else Divider),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Graphite, disabledContentColor = Graphite2),
    ) {
        ButtonContent(text, icon, loading, Graphite)
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    TextButton(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = TouchTarget),
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.textButtonColors(contentColor = Graphite),
    ) {
        ButtonContent(text, icon, false, Graphite)
    }
}

@Composable
private fun ButtonContent(text: String, icon: ImageVector?, loading: Boolean, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = color, strokeWidth = 3.dp)
            Spacer(Modifier.width(14.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(14.dp))
        }
        // labelLarge는 24sp. 주황 버튼의 흰 글자가 큰 글자 대비 기준을 맞추도록 굵게 고정한다.
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

/** 76dp 원형 아이콘 버튼(뒤로가기 등). */
@Composable
fun LargeIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(TouchTarget)) {
        Icon(icon, contentDescription = contentDescription, tint = Graphite, modifier = Modifier.size(40.dp))
    }
}
