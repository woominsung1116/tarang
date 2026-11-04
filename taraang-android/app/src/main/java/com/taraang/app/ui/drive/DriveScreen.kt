package com.taraang.app.ui.drive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Straight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taraang.app.R
import com.taraang.app.domain.signal.TurnKind
import com.taraang.app.domain.signal.TurnSignalState
import com.taraang.app.ui.Format
import com.taraang.app.ui.UiConfig
import com.taraang.app.ui.components.ConfirmDialog
import com.taraang.app.ui.components.EmptyState
import com.taraang.app.ui.components.PrimaryButton
import com.taraang.app.ui.containerViewModel
import com.taraang.app.ui.theme.Graphite
import com.taraang.app.ui.theme.Graphite2
import com.taraang.app.ui.theme.LightGray
import com.taraang.app.ui.theme.MutedIcon
import com.taraang.app.ui.theme.Orange
import com.taraang.app.ui.theme.OrangeText
import com.taraang.app.ui.theme.TaraangTheme
import com.taraang.app.ui.theme.WarningRed
import com.taraang.app.ui.theme.White

/** 한국어 문장을 글자 중간이 아니라 어절 단위로 줄바꿈한다(API 33+ 적용, 그 아래는 기본 동작). */
private val KoreanPhrase = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase)

@Composable
fun DriveDestination(onFinished: (sessionId: String?) -> Unit) {
    val vm = containerViewModel { c, _ -> DriveViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    val finished by rememberUpdatedState(onFinished)
    var confirmEnd by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is DriveEvent.Finished -> finished(event.sessionId)
            }
        }
    }
    KeepScreenOn()
    BackHandler { confirmEnd = true }

    if (!state.hasSession && !state.ending) {
        // 프로세스가 다시 시작돼 진행 중 주행이 없는 경우.
        Box(Modifier.fillMaxSize().background(White), contentAlignment = Alignment.Center) {
            EmptyState(
                title = stringResource(R.string.drive_no_session),
                action = { PrimaryButton(stringResource(R.string.action_back_home), { finished(null) }) },
            )
        }
        return
    }

    DriveScreen(state = state, onEnd = vm::endDrive)

    if (confirmEnd) {
        ConfirmDialog(
            title = stringResource(R.string.drive_end_confirm_title),
            body = stringResource(R.string.drive_end_confirm_body),
            confirmText = stringResource(R.string.drive_end),
            dismissText = stringResource(R.string.action_continue_drive),
            onConfirm = {
                confirmEnd = false
                vm.endDrive()
            },
            onDismiss = { confirmEnd = false },
        )
    }
}

/** 주행 화면에서만 화면 꺼짐을 막는다. 화면을 떠나면 해제된다. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * 주행 중 화면: 한눈에 보는 것만. 입력 없음, 목록 없음, 작은 글씨 없음(각주 한 줄 제외).
 * 말은 음성이 하고 화면은 같은 문장을 크게 한 줄로 보여준다.
 */
@Composable
fun DriveScreen(state: DriveUiState, onEnd: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(White)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        AnimatedVisibility(
            visible = state.warningText != null,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut(),
        ) {
            WarningBanner(state.warningText.orEmpty())
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 40.dp, vertical = 24.dp)) {
            if (maxWidth >= UiConfig.WideLayoutMinWidth) {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    MessageArea(state.message, Modifier.weight(1.6f).fillMaxHeight())
                    Spacer(Modifier.width(32.dp))
                    GuidancePanel(state, Modifier.weight(1f).height(360.dp))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    MessageArea(state.message, Modifier.weight(1f).fillMaxWidth())
                    Spacer(Modifier.height(24.dp))
                    GuidancePanel(state, Modifier.fillMaxWidth().height(300.dp))
                }
            }
        }
        BottomBar(state, onEnd)
    }
}

/** 빨강 + 경고 아이콘 + 문장. 색만으로 경고를 알리지 않는다. */
@Composable
private fun WarningBanner(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(WarningRed)
            .padding(horizontal = 40.dp, vertical = 20.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = stringResource(R.string.signal_warning), tint = White, modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(20.dp))
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall.copy(lineBreak = KoreanPhrase),
            color = White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MessageArea(message: String?, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        AnimatedContent(
            targetState = message,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "feedback",
        ) { text ->
            if (text != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Box(
                        Modifier
                            .width(10.dp)
                            .height(120.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Orange),
                    )
                    Spacer(Modifier.width(28.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.displayMedium.copy(lineBreak = KoreanPhrase),
                        color = Graphite,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    stringResource(R.string.drive_idle),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Graphite2,
                )
            }
        }
    }
}

@Composable
private fun GuidancePanel(state: DriveUiState, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(LightGray)
            .padding(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        NextTurnView(state.turnKind, state.turnDistanceM, Modifier.weight(1f))
        SpeedLimitSign(state.speedLimitKmh)
    }
}

@Composable
private fun NextTurnView(kind: TurnKind?, distanceM: Int?, modifier: Modifier) {
    val waiting = kind == null || distanceM == null
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val (icon, label) = turnVisual(if (waiting) null else kind)
        Icon(
            icon,
            contentDescription = label,
            tint = if (waiting) MutedIcon else OrangeText,
            modifier = Modifier.size(140.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (waiting) stringResource(R.string.drive_turn_waiting) else Format.distance(distanceM!!),
            style = if (waiting) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displaySmall,
            color = if (waiting) Graphite2 else Graphite,
        )
    }
}

@Composable
private fun turnVisual(kind: TurnKind?): Pair<ImageVector, String> = when (kind) {
    TurnKind.LEFT -> Icons.Filled.TurnLeft to stringResource(R.string.turn_left)
    TurnKind.RIGHT -> Icons.Filled.TurnRight to stringResource(R.string.turn_right)
    TurnKind.UTURN -> Icons.Filled.UTurnLeft to stringResource(R.string.turn_uturn)
    TurnKind.STRAIGHT -> Icons.Filled.Straight to stringResource(R.string.turn_straight)
    TurnKind.ROUNDABOUT -> Icons.Filled.RoundaboutRight to stringResource(R.string.turn_roundabout)
    TurnKind.ARRIVE -> Icons.Filled.Flag to stringResource(R.string.turn_arrive)
    TurnKind.OTHER, null -> Icons.Filled.Navigation to stringResource(R.string.drive_turn_waiting)
}

/** 제한속도 표지. 빨강은 차량 경고 전용이라 흑연 테두리로 그린다. */
@Composable
private fun SpeedLimitSign(limitKmh: Int?) {
    if (limitKmh == null) return
    val description = stringResource(R.string.drive_speed_limit_a11y, limitKmh)
    Box(
        Modifier
            .size(150.dp)
            .clip(CircleShape)
            .background(White)
            .border(14.dp, Graphite, CircleShape)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text("$limitKmh", style = MaterialTheme.typography.displayMedium, color = Graphite)
    }
}

@Composable
private fun BottomBar(state: DriveUiState, onEnd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 40.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                SignalIndicator(
                    icon = when (state.turnSignal) {
                        TurnSignalState.LEFT -> Icons.AutoMirrored.Filled.ArrowBack
                        TurnSignalState.RIGHT -> Icons.AutoMirrored.Filled.ArrowForward
                        else -> Icons.Filled.SwapHoriz
                    },
                    label = stringResource(R.string.signal_turn),
                    display = state.turnSignalDisplay,
                    activeColor = OrangeText,
                )
                SignalIndicator(
                    icon = if (state.brakeDisplay == SignalDisplay.ACTIVE) Icons.Filled.StopCircle else Icons.Outlined.StopCircle,
                    label = stringResource(R.string.signal_brake),
                    display = state.brakeDisplay,
                    activeColor = OrangeText,
                )
                SignalIndicator(
                    icon = if (state.warningDisplay == SignalDisplay.ACTIVE) Icons.Filled.Warning else Icons.Outlined.WarningAmber,
                    label = stringResource(R.string.signal_warning),
                    display = state.warningDisplay,
                    activeColor = WarningRed,
                )
            }
            when {
                state.errorMessage != null ->
                    Text(state.errorMessage, style = MaterialTheme.typography.bodySmall, color = Graphite2)
                state.anyUnavailable ->
                    Text(stringResource(R.string.signal_unsupported_footnote), style = MaterialTheme.typography.bodySmall, color = Graphite2)
            }
        }
        PrimaryButton(
            stringResource(R.string.drive_end),
            onEnd,
            loading = state.ending,
            containerColor = Graphite,
            modifier = Modifier.width(260.dp),
        )
    }
}

/**
 * 신호 하나. 상태를 색만이 아니라 모양으로도 구분한다:
 * 확인 불가는 금지 아이콘, 켜짐은 채운 아이콘, 꺼짐은 외곽선 아이콘.
 */
@Composable
private fun SignalIndicator(icon: ImageVector, label: String, display: SignalDisplay, activeColor: Color) {
    val unavailable = display == SignalDisplay.UNAVAILABLE
    val description = if (unavailable) stringResource(R.string.signal_unavailable_a11y, label) else label
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Icon(
            if (unavailable) Icons.Filled.Block else icon,
            contentDescription = null,
            tint = when (display) {
                SignalDisplay.UNAVAILABLE -> MutedIcon
                SignalDisplay.ACTIVE -> activeColor
                SignalDisplay.IDLE -> Graphite
            },
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = if (unavailable) Graphite2 else Graphite)
    }
}

private val previewState = DriveUiState(
    hasSession = true,
    message = "다음 교차로가 오늘 연습할 좌회전이에요.",
    turnKind = TurnKind.LEFT,
    turnDistanceM = 180,
    speedLimitKmh = 50,
    turnSignal = TurnSignalState.OFF,
    turnSignalDisplay = SignalDisplay.IDLE,
    brakeDisplay = SignalDisplay.UNAVAILABLE,
    warningDisplay = SignalDisplay.IDLE,
)

@Preview(widthDp = 1700, heightDp = 1160)
@Composable
private fun DrivePreview() {
    TaraangTheme { DriveScreen(previewState, onEnd = {}) }
}

@Preview(widthDp = 1408, heightDp = 792)
@Composable
private fun DriveWarningPreview() {
    TaraangTheme {
        DriveScreen(
            previewState.copy(
                message = null,
                warningText = "타이어 공기압 경고가 켜졌어요. 안전한 곳에 세운 뒤 확인하세요.",
                warningDisplay = SignalDisplay.ACTIVE,
            ),
            onEnd = {},
        )
    }
}
