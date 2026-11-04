package com.taraang.app.ui.review

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taraang.app.R
import com.taraang.app.domain.model.DriveEvent
import com.taraang.app.domain.model.DriveEventType
import com.taraang.app.domain.model.DriveSummary
import com.taraang.app.domain.model.Feeling
import com.taraang.app.domain.model.LevelProgress
import com.taraang.app.domain.model.Maneuver
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.ui.Format
import com.taraang.app.ui.components.Badge
import com.taraang.app.ui.components.ChoiceChip
import com.taraang.app.ui.components.ErrorState
import com.taraang.app.ui.components.Hairline
import com.taraang.app.ui.components.NoteBox
import com.taraang.app.ui.components.PrimaryButton
import com.taraang.app.ui.components.RouteCanvas
import com.taraang.app.ui.components.RoutePin
import com.taraang.app.ui.components.ScreenScaffold
import com.taraang.app.ui.components.SecondaryButton
import com.taraang.app.ui.components.SectionHeader
import com.taraang.app.ui.components.SkeletonBlock
import com.taraang.app.ui.components.TaraangCard
import androidx.navigation.toRoute
import com.taraang.app.ui.UiConfig
import com.taraang.app.ui.containerViewModel
import com.taraang.app.ui.navigation.ReviewRoute
import com.taraang.app.ui.preview.PreviewData
import com.taraang.app.ui.theme.Graphite
import com.taraang.app.ui.theme.Graphite2
import com.taraang.app.ui.theme.Orange
import com.taraang.app.ui.theme.TaraangTheme
import com.taraang.app.ui.theme.WarningRed
import kotlinx.coroutines.launch

/** 비교 블록에 0회여도 항상 보여주는 항목. 0회도 의미 있는 기록이다. */
private val ALWAYS_COMPARED = listOf(DriveEventType.SPEEDING, DriveEventType.SCHOOL_ZONE_SPEEDING, DriveEventType.HARD_BRAKE)

@Composable
fun ReviewDestination(onBack: () -> Unit, onPracticeLevel: (Int) -> Unit) {
    val vm = containerViewModel { c, h -> ReviewViewModel(c, h.toRoute<ReviewRoute>().sessionId) }
    val state by vm.state.collectAsStateWithLifecycle()
    ReviewScreen(state, onBack, onRetry = vm::load, onFeeling = vm::setFeeling, onPracticeLevel = onPracticeLevel)
}

@Composable
fun ReviewScreen(
    state: ReviewUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onFeeling: (Int, Feeling) -> Unit,
    onPracticeLevel: (Int) -> Unit,
) {
    ScreenScaffold(title = stringResource(R.string.review_title), onBack = onBack) {
        val summary = state.summary
        when {
            state.loading -> Column(Modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SkeletonBlock(120.dp)
                SkeletonBlock(320.dp)
            }
            summary == null -> ErrorState(state.loadError ?: stringResource(R.string.review_load_failed), onRetry)
            else -> ReviewBody(summary, state, onFeeling, onPracticeLevel)
        }
    }
}

@Composable
private fun ReviewBody(
    summary: DriveSummary,
    state: ReviewUiState,
    onFeeling: (Int, Feeling) -> Unit,
    onPracticeLevel: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= UiConfig.WideLayoutMinWidth) {
            Row(Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, bottom = 24.dp)) {
                Column(
                    Modifier.weight(0.45f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    SessionHeader(summary)
                    ReplayMap(summary, Modifier.fillMaxWidth().height(380.dp))
                    CompareBlock(summary)
                }
                Spacer(Modifier.width(32.dp))
                Column(
                    Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Segments(summary, state, onFeeling)
                    EventGroups(summary.events)
                    NextCourse(summary, state.progress, onPracticeLevel)
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SessionHeader(summary)
                ReplayMap(summary, Modifier.fillMaxWidth().height(320.dp))
                CompareBlock(summary)
                Segments(summary, state, onFeeling)
                EventGroups(summary.events)
                NextCourse(summary, state.progress, onPracticeLevel)
            }
        }
    }
}

@Composable
private fun SessionHeader(summary: DriveSummary) {
    Column {
        Text(
            summary.course?.title ?: stringResource(R.string.review_unknown_course),
            style = MaterialTheme.typography.headlineMedium,
            color = Graphite,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val duration = Format.durationMin(summary.session.startedAtMs, summary.session.endedAtMs)
            Text(
                listOfNotNull(Format.dateTime(summary.session.startedAtMs), duration).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = Graphite2,
            )
            if (summary.session.mode == SignalMode.REPLAY) Badge(stringResource(R.string.session_mode_replay))
            if (summary.session.interrupted) Badge(stringResource(R.string.session_interrupted))
        }
    }
}

/** 지도 위 궤적 재생: 경로를 따라 점이 움직이고 지나간 자리의 기록 핀이 나타난다. */
@Composable
private fun ReplayMap(summary: DriveSummary, modifier: Modifier) {
    val path = summary.course?.path.orEmpty()
    if (path.size < 2) return
    // 차량 경고 핀은 빨강에 "!"를 붙여 색만으로 구분하지 않는다.
    val pins = remember(summary) {
        summary.events.mapNotNull { e ->
            e.position?.let { RoutePin(it, eventColor(e.type), if (e.type == DriveEventType.NEW_WARNING) "!" else null) }
        }
    }
    val progress = remember { Animatable(0f) }
    var playing by remember { mutableStateOf(false) }
    var played by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val description = stringResource(R.string.review_map_a11y, summary.events.size)
    Column(modifier = Modifier.fillMaxWidth()) {
        RouteCanvas(
            path,
            modifier,
            pins = pins,
            progress = if (played || playing) progress.value else null,
            contentDescription = description,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(
                stringResource(if (played) R.string.review_replay_again else R.string.review_replay),
                onClick = {
                    scope.launch {
                        playing = true
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(UiConfig.REPLAY_MS, easing = LinearEasing))
                        playing = false
                        played = true
                    }
                },
                icon = if (played) Icons.Filled.Replay else Icons.Filled.PlayArrow,
                enabled = !playing,
            )
            Spacer(Modifier.width(20.dp))
            Legend(Orange, stringResource(R.string.review_legend_event))
            Spacer(Modifier.width(16.dp))
            Legend(WarningRed, stringResource(R.string.review_legend_warning))
        }
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = Graphite2)
    }
}

private fun eventColor(type: DriveEventType): Color = when (type) {
    DriveEventType.NEW_WARNING -> WarningRed
    DriveEventType.TURN_SIGNAL_OK -> Graphite
    else -> Orange
}

/** "지난번과 비교": 단일 점수 없이 항목별 변화만. */
@Composable
private fun CompareBlock(summary: DriveSummary) {
    TaraangCard(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.review_compare_title), style = MaterialTheme.typography.titleLarge, color = Graphite)
        Spacer(Modifier.height(16.dp))
        val previous = summary.previous
        if (summary.turnSignalTotal > 0 || (previous?.turnSignalTotal ?: 0) > 0) {
            CompareRow(
                label = stringResource(R.string.review_turn_signal),
                now = "${summary.turnSignalOk}/${summary.turnSignalTotal}",
                before = previous?.let { "${it.turnSignalOk}/${it.turnSignalTotal}" },
            )
        }
        val shown = ALWAYS_COMPARED + DriveEventType.entries.filter { type ->
            type !in ALWAYS_COMPARED && type != DriveEventType.TURN_SIGNAL_OK && type != DriveEventType.TURN_SIGNAL_MISSED &&
                (summary.events.any { it.type == type } || previous?.events?.any { it.type == type } == true)
        }
        shown.forEach { type ->
            CompareRow(
                label = type.label,
                now = stringResource(R.string.count_times, summary.events.count { it.type == type }),
                before = previous?.let { p -> stringResource(R.string.count_times, p.events.count { it.type == type }) },
            )
        }
        if (previous == null) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.review_compare_first), style = MaterialTheme.typography.bodySmall, color = Graphite2)
        }
    }
}

@Composable
private fun CompareRow(label: String, now: String, before: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Graphite, modifier = Modifier.weight(1f))
        Text(now, style = MaterialTheme.typography.titleMedium, color = Graphite)
        if (before != null) {
            Spacer(Modifier.width(16.dp))
            Text(
                stringResource(R.string.review_compare_before, before),
                style = MaterialTheme.typography.bodyMedium,
                color = Graphite2,
            )
        }
    }
}

@Composable
private fun Segments(summary: DriveSummary, state: ReviewUiState, onFeeling: (Int, Feeling) -> Unit) {
    val maneuvers = summary.course?.maneuvers.orEmpty().sortedBy { it.index }
    if (maneuvers.isEmpty()) return
    Column {
        SectionHeader(stringResource(R.string.review_segments_title))
        Text(stringResource(R.string.review_segments_hint), style = MaterialTheme.typography.bodySmall, color = Graphite2)
        if (state.saveError != null) {
            Spacer(Modifier.height(12.dp))
            NoteBox(listOf(state.saveError))
        }
        Spacer(Modifier.height(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            maneuvers.forEach { m ->
                SegmentRow(
                    maneuver = m,
                    events = summary.events.filter { it.maneuverIndex == m.index },
                    selected = state.feelings[m.index],
                    onFeeling = { onFeeling(m.index, it) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SegmentRow(maneuver: Maneuver, events: List<DriveEvent>, selected: Feeling?, onFeeling: (Feeling) -> Unit) {
    TaraangCard(Modifier.fillMaxWidth(), contentPadding = 20) {
        Text(Format.maneuver(maneuver), style = MaterialTheme.typography.titleMedium, color = Graphite)
        // 주관(느낌)과 객관(기록)을 섞지 않는다: 기록은 따로 한 줄로.
        val recorded = events.groupingBy { it.type }.eachCount()
        Spacer(Modifier.height(6.dp))
        Text(
            if (recorded.isEmpty()) stringResource(R.string.review_segment_no_record)
            else recorded.entries.joinToString(" · ") { (type, n) -> if (n > 1) "${type.label} ${n}회" else type.label },
            style = MaterialTheme.typography.bodySmall,
            color = Graphite2,
        )
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Feeling.entries.forEach { feeling ->
                ChoiceChip(feeling.label, selected == feeling, onClick = { onFeeling(feeling) })
            }
        }
    }
}

@Composable
private fun EventGroups(events: List<DriveEvent>) {
    Column {
        SectionHeader(stringResource(R.string.review_events_title))
        val groups = events.groupingBy { it.type }.eachCount()
        if (groups.isEmpty()) {
            Text(stringResource(R.string.review_events_empty), style = MaterialTheme.typography.bodyMedium, color = Graphite2)
            return@Column
        }
        TaraangCard(Modifier.fillMaxWidth(), contentPadding = 20) {
            DriveEventType.entries.filter { it in groups }.forEachIndexed { i, type ->
                if (i > 0) Hairline()
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(
                        Modifier
                            .size(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(eventColor(type)),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(type.label, style = MaterialTheme.typography.bodyMedium, color = Graphite, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.count_times, groups.getValue(type)), style = MaterialTheme.typography.titleSmall, color = Graphite)
                }
            }
        }
    }
}

@Composable
private fun NextCourse(summary: DriveSummary, progress: LevelProgress?, onPracticeLevel: (Int) -> Unit) {
    val current = progress?.currentLevel ?: summary.course?.level ?: 1
    val sameLevel = summary.course?.level ?: current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(stringResource(R.string.review_next_title))
        if (progress?.canAdvance == true) {
            Text(stringResource(R.string.review_next_can_advance), style = MaterialTheme.typography.bodyMedium, color = Graphite2)
            PrimaryButton(
                stringResource(R.string.review_next_level, current + 1, Format.levelName(current + 1)),
                { onPracticeLevel(current + 1) },
                modifier = Modifier.fillMaxWidth(),
            )
            SecondaryButton(stringResource(R.string.review_same_level), { onPracticeLevel(sameLevel) }, modifier = Modifier.fillMaxWidth())
        } else {
            PrimaryButton(stringResource(R.string.review_same_level), { onPracticeLevel(sameLevel) }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Preview(widthDp = 1408, heightDp = 792)
@Composable
private fun ReviewPreview() {
    TaraangTheme {
        ReviewScreen(
            state = ReviewUiState(
                loading = false,
                summary = PreviewData.summary,
                feelings = mapOf(1 to Feeling.TENSE),
                progress = PreviewData.progress.copy(completedCoursesAtLevel = 3),
            ),
            onBack = {}, onRetry = {}, onFeeling = { _, _ -> }, onPracticeLevel = {},
        )
    }
}
