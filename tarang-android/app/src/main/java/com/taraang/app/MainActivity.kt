package com.taraang.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.taraang.app.ui.navigation.TaraangNavHost
import com.taraang.app.ui.theme.TaraangTheme

/** 단일 Activity. 화면 켜짐 유지는 주행 화면(DriveScreen)만 켠다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // 라이트 전용 테마: 시스템 바 아이콘을 항상 어둡게.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            TaraangTheme {
                TaraangNavHost()
            }
        }
    }
}
