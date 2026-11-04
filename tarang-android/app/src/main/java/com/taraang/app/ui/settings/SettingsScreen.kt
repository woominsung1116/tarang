package com.taraang.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taraang.app.BuildConfig
import com.taraang.app.R
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.FeedbackType
import com.taraang.app.ui.components.ConfirmDialog
import com.taraang.app.ui.components.Hairline
import com.taraang.app.ui.components.NoteBox
import com.taraang.app.ui.components.PrimaryButton
import com.taraang.app.ui.components.QuietButton
import com.taraang.app.ui.components.ScreenScaffold
import com.taraang.app.ui.components.SecondaryButton
import com.taraang.app.ui.components.SectionHeader
import com.taraang.app.ui.components.SkeletonList
import com.taraang.app.ui.components.TaraangCard
import com.taraang.app.ui.components.TouchTarget
import com.taraang.app.ui.containerViewModel
import com.taraang.app.ui.theme.Divider
import com.taraang.app.ui.theme.Graphite
import com.taraang.app.ui.theme.Graphite2
import com.taraang.app.ui.theme.Orange
import com.taraang.app.ui.theme.TaraangTheme
import com.taraang.app.ui.theme.White

@Composable
fun SettingsDestination(onBack: () -> Unit) {
    val vm = containerViewModel { c, h -> SettingsViewModel(c, h) }
    val state by vm.state.collectAsStateWithLifecycle()
    SettingsScreen(
        state = state,
        onBack = onBack,
        onToggle = vm::setEnabled,
        onVoice = vm::setVoice,
        onUrl = vm::editUrl,
        onSaveUrl = vm::saveUrl,
        onResetUrl = vm::resetUrl,
        onDeleteHistory = vm::deleteAllHistory,
        onDismissError = vm::dismissError,
    )
}

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onToggle: (FeedbackType, Boolean) -> Unit,
    onVoice: (Boolean) -> Unit,
    onUrl: (String) -> Unit,
    onSaveUrl: () -> Unit,
    onResetUrl: () -> Unit,
    onDeleteHistory: () -> Unit,
    onDismissError: () -> Unit,
) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    ScreenScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        if (state.loading) {
            SkeletonList(count = 3, itemHeight = 140.dp, modifier = Modifier.padding(horizontal = 32.dp))
            return@ScreenScaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 1100.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
                if (state.errorMessage != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        NoteBox(listOf(state.errorMessage), icon = Icons.Outlined.ErrorOutline, modifier = Modifier.weight(1f))
                        QuietButton(stringResource(R.string.action_ok), onDismissError)
                    }
                }

                Column {
                    SectionHeader(stringResource(R.string.settings_feedback_title))
                    Text(stringResource(R.string.settings_feedback_hint), style = MaterialTheme.typography.bodySmall, color = Graphite2)
                    Spacer(Modifier.height(12.dp))
                    TaraangCard(Modifier.fillMaxWidth(), contentPadding = 8) {
                        ToggleRow(
                            title = stringResource(R.string.settings_voice),
                            subtitle = stringResource(R.string.settings_voice_hint),
                            checked = state.settings.voiceEnabled,
                            onChange = onVoice,
                        )
                        FeedbackType.entries.sortedBy { it.priority }.forEach { type ->
                            Hairline(Modifier.padding(horizontal = 16.dp))
                            ToggleRow(
                                title = type.label,
                                subtitle = if (type.userToggleable) null else stringResource(R.string.settings_locked_hint),
                                checked = state.settings.isEnabled(type),
                                locked = !type.userToggleable,
                                onChange = { onToggle(type, it) },
                            )
                        }
                    }
                }

                Column {
                    SectionHeader(stringResource(R.string.settings_server_title))
                    if (state.urlEditable) {
                        ServerUrlEditor(state, onUrl, onSaveUrl, onResetUrl)
                    } else {
                        // 릴리스 빌드: 주소를 보여주지도 고치지도 않는다(보안 검토 M3).
                        Text(
                            stringResource(
                                when (state.serverLabel) {
                                    ServerLabel.DEFAULT -> R.string.settings_server_default
                                    ServerLabel.CUSTOM -> R.string.settings_server_custom
                                    ServerLabel.OFFLINE -> R.string.settings_server_offline
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Graphite,
                        )
                    }
                }

                Column {
                    SectionHeader(stringResource(R.string.settings_history_title))
                    Text(stringResource(R.string.settings_history_hint), style = MaterialTheme.typography.bodySmall, color = Graphite2)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SecondaryButton(
                            stringResource(R.string.settings_history_delete),
                            { confirmDelete = true },
                            icon = Icons.Outlined.DeleteForever,
                            enabled = !state.historyLocked,
                            loading = state.deletingHistory,
                        )
                        if (state.historyLocked) {
                            Spacer(Modifier.width(16.dp))
                            Text(
                                stringResource(R.string.settings_history_locked_driving),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Graphite,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                        if (state.historyDeleted) {
                            Spacer(Modifier.width(16.dp))
                            Text(
                                stringResource(R.string.settings_history_deleted),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Graphite,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }

                Column {
                    SectionHeader(stringResource(R.string.settings_data_sources_title))
                    TaraangCard(Modifier.fillMaxWidth()) {
                        listOf(
                            R.string.data_source_osm,
                            R.string.data_source_signals,
                            R.string.data_source_road_links,
                            R.string.data_source_zones,
                        ).forEach { id ->
                            Text(stringResource(id), style = MaterialTheme.typography.bodyMedium, color = Graphite)
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }

                Column {
                    SectionHeader(stringResource(R.string.settings_about_title))
                    TaraangCard(Modifier.fillMaxWidth()) {
                        Text(
                            stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Graphite,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.osm_attribution_full), style = MaterialTheme.typography.bodyMedium, color = Graphite)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.settings_about_body), style = MaterialTheme.typography.bodySmall, color = Graphite2)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.settings_history_confirm_title),
            body = stringResource(R.string.settings_history_confirm_body),
            confirmText = stringResource(R.string.settings_history_delete),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                confirmDelete = false
                onDeleteHistory()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ServerUrlEditor(state: SettingsUiState, onUrl: (String) -> Unit, onSaveUrl: () -> Unit, onResetUrl: () -> Unit) {
    Column {
        Text(stringResource(R.string.settings_server_hint), style = MaterialTheme.typography.bodySmall, color = Graphite2)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.urlDraft,
            onValueChange = onUrl,
            textStyle = MaterialTheme.typography.bodyLarge,
            singleLine = true,
            isError = state.urlStatus == UrlStatus.INVALID,
            supportingText = when (state.urlStatus) {
                UrlStatus.INVALID -> { { Text(stringResource(R.string.settings_server_invalid), style = MaterialTheme.typography.bodySmall) } }
                UrlStatus.SAVED -> { { Text(stringResource(R.string.settings_server_saved), style = MaterialTheme.typography.bodySmall) } }
                UrlStatus.IDLE -> null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSaveUrl() }),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Orange, cursorColor = Orange),
            modifier = Modifier.fillMaxWidth().heightIn(min = TouchTarget),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PrimaryButton(stringResource(R.string.action_save), onSaveUrl, loading = state.savingUrl)
            QuietButton(stringResource(R.string.settings_server_reset), onResetUrl)
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    locked: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = TouchTarget)
            // toggleable: 접근성 서비스가 켜짐/꺼짐 상태를 읽는다.
            .toggleable(value = checked, enabled = !locked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Graphite)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Graphite2)
        }
        Spacer(Modifier.width(16.dp))
        if (locked) {
            Icon(Icons.Outlined.Lock, contentDescription = null, tint = Graphite2, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = !locked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = White,
                checkedTrackColor = Orange,
                uncheckedThumbColor = White,
                uncheckedTrackColor = Divider,
                uncheckedBorderColor = Divider,
                disabledCheckedTrackColor = Orange.copy(alpha = 0.45f),
                disabledCheckedThumbColor = White,
            ),
        )
    }
}

@Preview(widthDp = 1408, heightDp = 792)
@Composable
private fun SettingsPreview() {
    TaraangTheme {
        SettingsScreen(
            state = SettingsUiState(
                loading = false,
                settings = FeedbackSettings(enabled = FeedbackType.entries.associateWith { it != FeedbackType.SKILL_PREVIEW }),
                urlDraft = "http://10.0.2.2:8000",
                urlStatus = UrlStatus.SAVED,
            ),
            onBack = {}, onToggle = { _, _ -> }, onVoice = {}, onUrl = {}, onSaveUrl = {}, onResetUrl = {}, onDeleteHistory = {}, onDismissError = {},
        )
    }
}
