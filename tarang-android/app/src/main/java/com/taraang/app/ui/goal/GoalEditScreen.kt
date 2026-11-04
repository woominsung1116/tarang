package com.taraang.app.ui.goal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taraang.app.R
import com.taraang.app.domain.model.Skill
import com.taraang.app.ui.components.ConfirmDialog
import com.taraang.app.ui.components.LargeIconButton
import com.taraang.app.ui.components.NoteBox
import com.taraang.app.ui.components.PrimaryButton
import com.taraang.app.ui.components.QuietButton
import com.taraang.app.ui.components.ScreenScaffold
import com.taraang.app.ui.components.SecondaryButton
import com.taraang.app.ui.components.SectionHeader
import com.taraang.app.ui.components.SkeletonList
import com.taraang.app.ui.components.SkillChips
import com.taraang.app.ui.components.TouchTarget
import com.taraang.app.ui.Format
import com.taraang.app.ui.UiConfig
import com.taraang.app.ui.containerViewModel
import com.taraang.app.ui.theme.Graphite
import com.taraang.app.ui.theme.Graphite2
import com.taraang.app.ui.theme.LightGray
import com.taraang.app.ui.theme.Orange
import com.taraang.app.ui.theme.OrangeText
import com.taraang.app.ui.theme.TaraangTheme

@Composable
fun GoalEditDestination(onBack: () -> Unit, onSaved: () -> Unit) {
    val vm = containerViewModel { c, h -> GoalEditViewModel(c, h) }
    val state by vm.state.collectAsStateWithLifecycle()
    val done by rememberUpdatedState(onSaved)
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                GoalEditEvent.Done -> done()
            }
        }
    }
    GoalEditScreen(
        state = state,
        actions = GoalEditActions(
            onBack = onBack,
            onEdit = vm::edit,
            onSkillCount = vm::setSkillCount,
            onAddSpot = vm::addHardSpot,
            onRemoveSpot = vm::removeHardSpot,
            onEditSpot = vm::updateHardSpot,
            onFillDemo = vm::fillDemo,
            onAnalyze = vm::analyzeRoute,
            onSave = vm::save,
            onDelete = vm::delete,
            onDismissError = vm::dismissError,
        ),
    )
}

class GoalEditActions(
    val onBack: () -> Unit,
    val onEdit: ((GoalForm) -> GoalForm) -> Unit,
    val onSkillCount: (Skill, Int) -> Unit,
    val onAddSpot: () -> Unit,
    val onRemoveSpot: (String) -> Unit,
    val onEditSpot: (String, (HardSpotForm) -> HardSpotForm) -> Unit,
    val onFillDemo: () -> Unit,
    val onAnalyze: () -> Unit,
    val onSave: () -> Unit,
    val onDelete: () -> Unit,
    val onDismissError: () -> Unit,
)

@Composable
fun GoalEditScreen(state: GoalEditUiState, actions: GoalEditActions) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(
        title = stringResource(if (state.existingId == null) R.string.goal_title_new else R.string.goal_title_edit),
        onBack = actions.onBack,
        actions = {
            QuietButton(stringResource(R.string.goal_fill_demo), actions.onFillDemo, icon = Icons.Filled.AutoFixHigh)
        },
    ) {
        if (state.loading) {
            SkeletonList(count = 3, itemHeight = 120.dp, modifier = Modifier.padding(horizontal = 32.dp))
            return@ScreenScaffold
        }
        val f = state.form
        val errors = state.errors
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(stringResource(R.string.goal_intro), style = MaterialTheme.typography.bodyMedium, color = Graphite2)
                Field(
                    label = stringResource(R.string.goal_name),
                    value = f.name,
                    error = errors["name"],
                    onChange = { v -> actions.onEdit { it.copy(name = v) } },
                    numeric = false,
                )
                PointFields(
                    title = stringResource(R.string.goal_origin),
                    lat = f.originLat, lon = f.originLon,
                    latError = errors["originLat"], lonError = errors["originLon"],
                    onLat = { v -> actions.onEdit { it.copy(originLat = v) } },
                    onLon = { v -> actions.onEdit { it.copy(originLon = v) } },
                )
                PointFields(
                    title = stringResource(R.string.goal_destination),
                    lat = f.destLat, lon = f.destLon,
                    latError = errors["destLat"], lonError = errors["destLon"],
                    onLat = { v -> actions.onEdit { it.copy(destLat = v) } },
                    onLon = { v -> actions.onEdit { it.copy(destLon = v) } },
                )

                SectionHeader(stringResource(R.string.goal_skills)) {
                    SecondaryButton(
                        stringResource(if (state.analysis == RouteAnalysis.NotRun) R.string.goal_analyze else R.string.goal_analyze_again),
                        actions.onAnalyze,
                        icon = Icons.Filled.Route,
                        loading = state.analysis == RouteAnalysis.Running,
                    )
                }
                AnalysisResult(state, actions)

                SectionHeader(stringResource(R.string.goal_hard_spots)) {
                    SecondaryButton(
                        stringResource(R.string.goal_add_spot),
                        actions.onAddSpot,
                        icon = Icons.Filled.Add,
                        enabled = state.canAddSpot,
                    )
                }
                if (f.hardSpots.isEmpty()) {
                    Text(stringResource(R.string.goal_hard_spots_empty), style = MaterialTheme.typography.bodyMedium, color = Graphite2)
                }
                f.hardSpots.forEach { spot ->
                    HardSpotFields(spot, errors, actions)
                }

                if (state.errorMessage != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NoteBox(listOf(state.errorMessage), icon = Icons.Outlined.ErrorOutline, modifier = Modifier.weight(1f))
                        QuietButton(stringResource(R.string.action_ok), actions.onDismissError)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 16.dp)) {
                    PrimaryButton(
                        stringResource(R.string.action_save),
                        actions.onSave,
                        loading = state.saving,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.existingId != null) {
                        SecondaryButton(
                            stringResource(R.string.goal_delete),
                            { confirmDelete = true },
                            icon = Icons.Outlined.Delete,
                            enabled = !state.saving,
                        )
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.goal_delete_confirm_title),
            body = stringResource(R.string.goal_delete_confirm_body),
            confirmText = stringResource(R.string.goal_delete),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                confirmDelete = false
                actions.onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun AnalysisResult(state: GoalEditUiState, actions: GoalEditActions) {
    val skills = state.form.skills.filterValues { it > 0 }
    val analysis = state.analysis
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        when {
            analysis == RouteAnalysis.Running ->
                Text(stringResource(R.string.goal_analyzing), style = MaterialTheme.typography.bodyMedium, color = Graphite2)
            analysis is RouteAnalysis.Failed ->
                NoteBox(listOf(analysis.message, stringResource(R.string.goal_analyze_failed_manual)), icon = Icons.Outlined.ErrorOutline)
            analysis == RouteAnalysis.NotRun && skills.isEmpty() ->
                Text(stringResource(R.string.goal_skills_hint), style = MaterialTheme.typography.bodyMedium, color = Graphite2)
            skills.isEmpty() ->
                Text(stringResource(R.string.goal_analyze_none), style = MaterialTheme.typography.bodyMedium, color = Graphite)
            else -> {
                val parts = Format.sortedSkills(skills).map { (skill, n) -> stringResource(R.string.goal_skill_count, skill.label, n) }
                Text(
                    stringResource(R.string.goal_analyze_summary, parts.joinToString(", ")),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Graphite,
                )
                SkillChips(skills)
            }
        }
        if (state.analysisStale) {
            Text(stringResource(R.string.goal_analyze_stale), style = MaterialTheme.typography.bodyMedium, color = OrangeText)
        }
        if (state.manualSkillsEnabled) {
            Skill.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { skill ->
                        SkillStepper(skill, state.form.skills[skill] ?: 0, { actions.onSkillCount(skill, it) }, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PointFields(
    title: String,
    lat: String,
    lon: String,
    latError: GoalFieldError?,
    lonError: GoalFieldError?,
    onLat: (String) -> Unit,
    onLon: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Graphite)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field(stringResource(R.string.goal_lat), lat, latError, onLat, numeric = true, modifier = Modifier.weight(1f))
            Field(stringResource(R.string.goal_lon), lon, lonError, onLon, numeric = true, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun HardSpotFields(spot: HardSpotForm, errors: Map<String, GoalFieldError>, actions: GoalEditActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(LightGray)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Field(
                stringResource(R.string.goal_spot_label),
                spot.label,
                errors["spot:${spot.key}:label"],
                { v -> actions.onEditSpot(spot.key) { it.copy(label = v) } },
                numeric = false,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            LargeIconButton(Icons.Outlined.Delete, stringResource(R.string.goal_remove_spot), { actions.onRemoveSpot(spot.key) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field(
                stringResource(R.string.goal_lat), spot.lat, errors["spot:${spot.key}:lat"],
                { v -> actions.onEditSpot(spot.key) { it.copy(lat = v) } },
                numeric = true, modifier = Modifier.weight(1f),
            )
            Field(
                stringResource(R.string.goal_lon), spot.lon, errors["spot:${spot.key}:lon"],
                { v -> actions.onEditSpot(spot.key) { it.copy(lon = v) } },
                numeric = true, modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SkillStepper(skill: Skill, count: Int, onCount: (Int) -> Unit, modifier: Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(LightGray)
            .heightIn(min = TouchTarget)
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(skill.label, style = MaterialTheme.typography.bodyMedium, color = Graphite, modifier = Modifier.weight(1f))
        LargeIconButton(Icons.Filled.Remove, stringResource(R.string.goal_skill_less, skill.label), { onCount(count - 1) })
        Text(
            "$count",
            style = MaterialTheme.typography.titleLarge,
            color = if (count > 0) OrangeText else Graphite2,
            modifier = Modifier.widthIn(min = 32.dp),
        )
        LargeIconButton(Icons.Filled.Add, stringResource(R.string.goal_skill_more, skill.label), {
            if (count < UiConfig.SKILL_COUNT_MAX) onCount(count + 1)
        })
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    error: GoalFieldError?,
    onChange: (String) -> Unit,
    numeric: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.heightIn(min = TouchTarget),
        label = { Text(label, style = MaterialTheme.typography.bodySmall) },
        textStyle = MaterialTheme.typography.bodyLarge,
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(errorText(it), style = MaterialTheme.typography.bodySmall) } },
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text,
            imeAction = ImeAction.Next,
        ),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Orange, focusedLabelColor = OrangeText, cursorColor = Orange),
    )
}

@Composable
private fun errorText(error: GoalFieldError): String = when (error) {
    GoalFieldError.NAME_REQUIRED -> stringResource(R.string.goal_error_name)
    GoalFieldError.TOO_LONG -> stringResource(R.string.goal_error_too_long)
    GoalFieldError.NOT_A_NUMBER -> stringResource(R.string.goal_error_number)
    GoalFieldError.OUTSIDE_KOREA -> stringResource(R.string.goal_error_korea)
    GoalFieldError.LABEL_REQUIRED -> stringResource(R.string.goal_error_label)
}

@Preview(widthDp = 1408, heightDp = 792)
@Composable
private fun GoalEditPreview() {
    TaraangTheme {
        GoalEditScreen(
            state = GoalEditUiState(
                loading = false,
                existingId = "g",
                form = GoalForm(
                    name = "회사 지하주차장 가기",
                    originLat = "35.231", originLon = "129.084",
                    destLat = "35.2205", destLon = "200",
                    skills = mapOf(Skill.LEFT_TURN_SIGNALIZED to 2, Skill.MERGE to 1),
                    hardSpots = listOf(HardSpotForm(key = "a", label = "지하주차장 입구", lat = "35.2205", lon = "129.0868")),
                ),
                errors = mapOf("destLon" to GoalFieldError.OUTSIDE_KOREA, "destLat" to GoalFieldError.OUTSIDE_KOREA),
                analysis = RouteAnalysis.Done,
            ),
            actions = GoalEditActions({}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {}, {}, {}, {}, {}),
        )
    }
}
