package com.taraang.app.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taraang.app.R
import com.taraang.app.core.AppError
import com.taraang.app.core.userMessage
import com.taraang.app.di.AppContainer
import com.taraang.app.domain.model.DriveSession
import com.taraang.app.domain.model.SignalMode
import com.taraang.app.ui.Format
import com.taraang.app.ui.components.Badge
import com.taraang.app.ui.components.EmptyState
import com.taraang.app.ui.components.ErrorState
import com.taraang.app.ui.components.ScreenScaffold
import com.taraang.app.ui.components.SkeletonList
import com.taraang.app.ui.components.TaraangCard
import com.taraang.app.ui.containerViewModel
import com.taraang.app.ui.preview.PreviewData
import com.taraang.app.ui.theme.Graphite
import com.taraang.app.ui.theme.Graphite2
import com.taraang.app.ui.theme.TaraangTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import com.taraang.app.core.getOrNull
import com.taraang.app.ui.ReadFailure
import com.taraang.app.ui.guard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class HistoryItem(val session: DriveSession, val courseTitle: String?)

sealed interface HistoryUiState {
    data object Loading : HistoryUiState
    data class Loaded(val items: List<HistoryItem>) : HistoryUiState
    data class Error(val message: String) : HistoryUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    /** courseId → 제목. 코스를 못 찾으면 null을 기억해 다시 묻지 않는다. */
    private val titles = mutableMapOf<String, String?>()

    /** 값이 바뀔 때마다 sessions를 다시 구독한다(오류 후 재시도). */
    private val attempt = MutableStateFlow(0)

    val state: StateFlow<HistoryUiState> = attempt
        .flatMapLatest {
            container.driveRepository.sessions
                .map { sessions -> HistoryUiState.Loaded(toItems(sessions)) as HistoryUiState }
                .onStart { emit(HistoryUiState.Loading) }
                .catch { e ->
                    container.logger.w(TAG, "sessions flow failed", e)
                    emit(HistoryUiState.Error(ReadFailure.userMessage()))
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState.Loading)

    fun retry() = attempt.update { it + 1 }

    // 같은 id가 두 번 오면 목록 key가 충돌하므로 첫 번째만 남긴다.
    private suspend fun toItems(sessions: List<DriveSession>): List<HistoryItem> =
        sessions.distinctBy { it.id }.sortedByDescending { it.startedAtMs }.map { s ->
            val title = titles.getOrPut(s.courseId) {
                container.logger.guard(TAG, "course get") { container.courseRepository.get(s.courseId) }.getOrNull()?.title
            }
            HistoryItem(s, title)
        }

    private companion object {
        const val TAG = "HistoryVM"
    }
}

@Composable
fun HistoryDestination(onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val vm = containerViewModel { c, _ -> HistoryViewModel(c) }
    val state by vm.state.collectAsStateWithLifecycle()
    HistoryScreen(state, onBack, onOpenSession, onRetry = vm::retry)
}

@Composable
fun HistoryScreen(state: HistoryUiState, onBack: () -> Unit, onOpenSession: (String) -> Unit, onRetry: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.history_title), onBack = onBack) {
        when (state) {
            HistoryUiState.Loading -> SkeletonList(count = 4, itemHeight = 110.dp, modifier = Modifier.padding(horizontal = 32.dp))
            is HistoryUiState.Error -> ErrorState(state.message, onRetry = onRetry)
            is HistoryUiState.Loaded -> if (state.items.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.history_empty_title),
                    body = stringResource(R.string.history_empty_body),
                    icon = Icons.Outlined.History,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 32.dp, end = 32.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    items(state.items, key = { it.session.id }) { item ->
                        HistoryRow(item, Modifier.widthIn(max = 1100.dp)) { onOpenSession(item.session.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, modifier: Modifier, onClick: () -> Unit) {
    TaraangCard(modifier.fillMaxWidth(), onClick = onClick, contentPadding = 22) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.courseTitle ?: stringResource(R.string.review_unknown_course),
                style = MaterialTheme.typography.titleMedium,
                color = Graphite,
                modifier = Modifier.weight(1f),
            )
            if (item.session.mode == SignalMode.REPLAY) Badge(stringResource(R.string.session_mode_replay))
            if (item.session.interrupted) {
                Spacer(Modifier.width(8.dp))
                Badge(stringResource(R.string.session_interrupted))
            }
        }
        Spacer(Modifier.height(4.dp))
        val duration = Format.durationMin(item.session.startedAtMs, item.session.endedAtMs)
        Text(
            listOfNotNull(Format.dateTime(item.session.startedAtMs), duration).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = Graphite2,
        )
    }
}

@Preview(widthDp = 1408, heightDp = 792)
@Composable
private fun HistoryPreview() {
    TaraangTheme {
        HistoryScreen(
            HistoryUiState.Loaded(
                listOf(
                    HistoryItem(PreviewData.session, PreviewData.course.title),
                    HistoryItem(PreviewData.session.copy(id = "s-0", mode = SignalMode.LIVE, interrupted = true, endedAtMs = null), null),
                ),
            ),
            onBack = {}, onOpenSession = {}, onRetry = {},
        )
    }
}
