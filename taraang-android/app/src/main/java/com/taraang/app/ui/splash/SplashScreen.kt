package com.taraang.app.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.taraang.app.R
import com.taraang.app.ui.UiConfig
import com.taraang.app.ui.theme.TaraangTheme
import com.taraang.app.ui.theme.White
import kotlinx.coroutines.delay

/** 흰 배경 + 로고·워드마크 조합. 시스템 스플래시(아이콘만) 뒤에 이어서 보인다. */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val alpha = remember { Animatable(0f) }
    val finish = rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(UiConfig.SPLASH_FADE_MS))
        delay(UiConfig.SPLASH_TOTAL_MS - UiConfig.SPLASH_FADE_MS)
        finish.value()
    }
    SplashContent(Modifier.alpha(alpha.value))
}

@Composable
private fun SplashContent(logoModifier: Modifier = Modifier) {
    Box(
        Modifier
            .fillMaxSize()
            .background(White),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.taraang_splash_lockup),
            contentDescription = stringResource(R.string.app_name),
            // 가로로 긴 조합(약 3.4:1)이라 너비로 크기를 정하고 높이는 비율을 따른다. 좁은 창에서는 화면 폭의 60%까지.
            modifier = logoModifier
                .fillMaxWidth(0.6f)
                .widthIn(max = UiConfig.SplashLockupWidth),
            contentScale = ContentScale.Fit,
        )
    }
}

@Preview(widthDp = 1700, heightDp = 1160)
@Composable
private fun SplashPreview() {
    TaraangTheme { SplashContent() }
}
