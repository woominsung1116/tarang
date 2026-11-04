package com.taraang.app.ui.course

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taraang.app.R
import com.taraang.app.domain.model.Course
import com.taraang.app.domain.model.CourseSource
import com.taraang.app.domain.model.DataConfidence
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.ui.Format
import com.taraang.app.ui.components.Badge
import com.taraang.app.ui.components.CourseMap
import com.taraang.app.ui.components.EmptyState
import com.taraang.app.ui.components.NoteBox
import com.taraang.app.ui.components.PrimaryButton
import com.taraang.app.ui.components.QuietButton
import com.taraang.app.ui.components.RoutePin
import com.taraang.app.ui.components.ScreenScaffold
import com.taraang.app.ui.components.SecondaryButton
import com.taraang.app.ui.components.SectionHeader
import com.taraang.app.ui.components.SkeletonBlock
import com.taraang.app.ui.components.SkillChips
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.toRoute
import com.taraang.app.ui.UiConfig
import com.taraang.app.ui.containerViewModel
import com.taraang.app.ui.navigation.CourseDetailRoute
import com.taraang.app.ui.preview.PreviewData
import com.taraang.app.ui.theme.Graphite
import com.taraang.app.ui.theme.Graphite2
import com.taraang.app.ui.theme.LightGray
import com.taraang.app.ui.theme.TaraangTheme
import com.taraang.app.ui.theme.White

@Composable
fun CourseDetailDestination(onBack: () -> Unit, onDriveStarted: () -> Unit) {
    val vm = containerViewModel { c, h -> CourseDetailViewModel(c, h.toRoute<CourseDetailRoute>().courseId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val started by rememberUpdatedState(onDriveStarted)
    val context = LocalContext.current
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                CourseDetailEvent.DriveStarted -> started()
                // 바로 주행 화면으로 넘어가므로 화면 상태 대신 토스트(화면 이동 뒤에도 남는다).
                is CourseDetailEvent.Notice -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                is CourseDetailEvent.FarFromStart -> Toast.makeText(
                    context,
                    context.getString(R.string.course_far_from_start, Format.distance(event.distanceM)),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    CourseDetailScreen(
        state = state,
        onBack = onBack,
        onStartNavi = vm::startWithNavigation,
        onStartReplay = vm::startReplay,
        onFallbackReplay = vm::startFallbackReplay,
        onChooseReplay = vm::chooseReplay,
        onDismissReplay = vm::dismissReplayChoices,
        onDismissError = vm::dismissError,
    )
}

@Composable
fun CourseDetailScreen(
    state: CourseDetailUiState,
    onBack: () -> Unit,
    onStartNavi: () -> Unit,
    onStartReplay: () -> Unit,
    onFallbackReplay: () -> Unit,
    onChooseReplay: (String) -> Unit,
    onDismissReplay: () -> Unit,
    onDismissError: () -> Unit,
) {
    val course = state.course
    ScreenScaffold(title = course?.title ?: stringResource(R.string.course_title), onBack = onBack) {
        when {
            state.loading -> Column(Modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SkeletonBlock(320.dp)
                SkeletonBlock(120.dp)
            }
            course == null -> EmptyState(
                title = stringResource(R.string.course_not_found),
                body = stringResource(R.string.course_not_found_body),
                action = { SecondaryButton(stringResource(R.string.action_back_home), onBack) },
            )
            else -> CourseBody(course, state, onStartNavi, onStartReplay, onFallbackReplay, onDismissError)
        }
    }
    if (state.replayChoices.isNotEmpty()) {
        ReplayPicker(state.replayChoices, onChooseReplay, onDismissReplay)
    }
}

@Composable
private fun CourseBody(
    course: Course,
    state: CourseDetailUiState,
    onStartNavi: () -> Unit,
    onStartReplay: () -> Unit,
    onFallbackReplay: () -> Unit,
    onDismissError: () -> Unit,
) {
    val pins = remember(course) { course.maneuvers.map { RoutePin(it.position, Graphite, "${it.index + 1}") } }
    val mapDescription = stringResource(R.string.course_map_a11y, course.title, course.maneuvers.size)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= UiConfig.WideLayoutMinWidth) {
            Row(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = 24.dp)) {
                Column(Modifier.weight(0.55f).fillMaxHeight()) {
                    CourseMap(course.path, pins, mapDescription, Modifier.weight(1f).fillMaxWidth())
                    DataCredit()
                }
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(0.45f).fillMaxHeight()) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        CourseInfo(course)
                    }
                    StartPanel(state, onStartNavi, onStartReplay, onFallbackReplay, onDismissError)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    CourseMap(course.path, pins, mapDescription, Modifier.fillMaxWidth().height(360.dp))
                    DataCredit()
                    Spacer(Modifier.height(24.dp))
                    CourseInfo(course)
                }
                StartPanel(state, onStartNavi, onStartReplay, onFallbackReplay, onDismissError)
            }
        }
    }
}

/** 지도 아래 데이터 출처 한 줄. 전체 목록은 설정 → 데이터 출처. */
@Composable
private fun DataCredit() {
    Text(
        stringResource(R.string.course_data_credit),
        style = MaterialTheme.typography.labelSmall,
        color = Graphite2,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ColumnScope.CourseInfo(course: Course) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.course_meta, Format.distance(course.distanceM), course.maneuvers.size),
            style = MaterialTheme.typography.titleMedium,
            color = Graphite2,
        )
        Spacer(Modifier.width(12.dp))
        Badge(stringResource(R.string.course_level_badge, course.level, Format.levelName(course.level)))
        if (course.source != CourseSource.SERVER) {
            Spacer(Modifier.width(8.dp))
            Badge(stringResource(R.string.course_source_saved))
        }
    }
    if (course.skillCounts.values.any { it > 0 }) {
        Spacer(Modifier.height(16.dp))
        SkillChips(course.skillCounts)
    }
    if (course.warnings.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        NoteBox(course.warnings, title = stringResource(R.string.course_warnings_title))
    }
    Spacer(Modifier.height(24.dp))
    SectionHeader(stringResource(R.string.course_maneuvers_title))
    if (course.maneuvers.isEmpty()) {
        Text(stringResource(R.string.course_maneuvers_empty), style = MaterialTheme.typography.bodyMedium, color = Graphite2)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        course.maneuvers.sortedBy { it.index }.forEach { ManeuverRow(it) }
    }
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.course_disclaimer), style = MaterialTheme.typography.bodySmall, color = Graphite2)
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ManeuverRow(m: Maneuver) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LightGray)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(Graphite),
            contentAlignment = Alignment.Center,
        ) {
            Text("${m.index + 1}", style = MaterialTheme.typography.labelLarge, color = White)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${m.street.ifBlank { stringResource(R.string.course_unnamed_street) }} ${m.skill.label}",
                style = MaterialTheme.typography.titleSmall,
                color = Graphite,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.course_maneuver_at, Format.distance(m.distanceFromStartM)),
                style = MaterialTheme.typography.bodySmall,
                color = Graphite2,
            )
        }
        when (m.confidence) {
            DataConfidence.VERIFIED -> Unit
            DataConfidence.ESTIMATED -> Badge(stringResource(R.string.confidence_estimated))
            DataConfidence.UNKNOWN -> Badge(stringResource(R.string.confidence_unknown))
        }
    }
}

@Composable
private fun StartPanel(
    state: CourseDetailUiState,
    onStartNavi: () -> Unit,
    onStartReplay: () -> Unit,
    onFallbackReplay: () -> Unit,
    onDismissError: () -> Unit,
) {
    Column(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val notice = when {
            state.errorMessage != null -> state.errorMessage
            state.replayMissing -> stringResource(R.string.replay_missing)
            else -> null
        }
        if (notice != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NoteBox(listOf(notice), icon = Icons.Outlined.ErrorOutline, modifier = Modifier.weight(1f))
                QuietButton(stringResource(R.string.action_ok), onDismissError)
            }
        }
        if (state.offerReplay) {
            // 실차 신호·내비가 연결되지 않은 경우: 시연 로그로 같은 코스를 보여준다.
            PrimaryButton(
                stringResource(R.string.course_fallback_replay),
                onFallbackReplay,
                icon = Icons.Filled.PlayArrow,
                loading = state.starting == StartAction.REPLAY,
                enabled = state.starting == null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.locked) {
            NoteBox(listOf(stringResource(R.string.course_locked_notice)), icon = Icons.Outlined.Lock)
        } else {
            Text(stringResource(R.string.course_parked_hint), style = MaterialTheme.typography.bodySmall, color = Graphite2)
            PrimaryButton(
                stringResource(R.string.course_start_navi),
                onStartNavi,
                icon = Icons.Filled.Navigation,
                loading = state.starting == StartAction.NAVI,
                enabled = state.starting == null,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SecondaryButton(
            stringResource(R.string.course_start_replay),
            onStartReplay,
            icon = Icons.Filled.PlayArrow,
            loading = state.starting == StartAction.REPLAY,
            enabled = state.starting == null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ReplayPicker(choices: List<String>, onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = White,
        shape = RoundedCornerShape(28.dp),
        title = { Text(stringResource(R.string.replay_pick_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                choices.forEach { asset ->
                    SecondaryButton(Format.assetName(asset), { onChoose(asset) }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = { QuietButton(stringResource(R.string.action_cancel), onDismiss) },
    )
}

@Preview(widthDp = 1408, heightDp = 792)
@Composable
private fun CourseDetailPreview() {
    TaraangTheme {
        CourseDetailScreen(
            state = CourseDetailUiState(
                loading = false,
                course = PreviewData.course,
                errorMessage = "내비게이션에 코스를 보내지 못했어요.",
                offerReplay = true,
            ),
            onBack = {}, onStartNavi = {}, onStartReplay = {}, onFallbackReplay = {}, onChooseReplay = {},
            onDismissReplay = {}, onDismissError = {},
        )
    }
}

@Preview(widthDp = 720, heightDp = 1200)
@Composable
private fun CourseDetailNotFoundPreview() {
    TaraangTheme {
        CourseDetailScreen(
            state = CourseDetailUiState(loading = false, course = null),
            onBack = {}, onStartNavi = {}, onStartReplay = {}, onFallbackReplay = {}, onChooseReplay = {},
            onDismissReplay = {}, onDismissError = {},
        )
    }
}
