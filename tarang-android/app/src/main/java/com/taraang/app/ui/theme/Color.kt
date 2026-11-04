package com.taraang.app.ui.theme

import androidx.compose.ui.graphics.Color

// ARCHITECTURE.md 8장 팔레트(공식 로고 실측). colors.xml의 taraang_logo_orange/taraang_logo_graphite와 같은 값.
// 대비(체크리스트 27): 주황 #F06519는 흰색과 3.19:1이라 면·선·큰 글자에만 쓴다.
// - 주황 면 위 글자: 흰색 굵게 24sp 이상만(큰 글자 3:1). 흑연 on 주황(3.72:1)은 쓰지 않는다.
// - 주황 글자: OrangeText(흰 배경 5.05:1, OrangeSoft 4.57:1, LightGray 4.55:1).
val Orange = Color(0xFFF06519)
val OrangeText = Color(0xFFBA4D0E)
val OrangeSoft = Color(0xFFFFF1E8)
val White = Color(0xFFFFFFFF)
val Graphite = Color(0xFF31383F)
val Graphite2 = Color(0xFF5B6067)
val LightGray = Color(0xFFF2F3F5)
val Divider = Color(0xFFE3E5E8)

/** 장식·비활성 전용(1.99:1). 의미 있는 아이콘에는 [MutedIcon]을 쓴다. */
val Muted = Color(0xFFB4B8BE)

/** "확인 불가" 같은 의미 있는 회색 아이콘. 흰 배경·연회색 카드 모두 3:1 이상. */
val MutedIcon = Color(0xFF898C90)

/** 차량 경고에만 쓴다. 흰 글자 대비 4.77:1. */
val WarningRed = Color(0xFFD93025)
val WarningRedSoft = Color(0xFFFCE8E6)
