package com.taraang.app.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import com.taraang.app.ui.preview.PreviewData
import com.taraang.app.domain.repository.LiveDriveState
import com.taraang.app.ui.ScriptedDriveController
import androidx.lifecycle.SavedStateHandle
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.FeedbackType
import com.taraang.app.ui.MainDispatcherRule
import com.taraang.app.core.AppError
import com.taraang.app.core.AppResult
import com.taraang.app.core.userMessage
import com.taraang.app.ui.PolicySettingsRepository
import com.taraang.app.ui.ScriptedDriveRepository
import com.taraang.app.ui.TestContainer
import com.taraang.app.ui.UiConfig
import com.taraang.app.ui.settings.SettingsViewModel.Companion.normalizeUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test
    fun `normalizeUrl accepts https anywhere and plain http only for emulator host and localhost`() {
        assertEquals("http://10.0.2.2:8000", normalizeUrl(" http://10.0.2.2:8000/ "))
        assertEquals("http://localhost:8000", normalizeUrl("HTTP://LOCALHOST:8000"))
        assertEquals("https://api.taraang.kr/v1", normalizeUrl("https://api.taraang.kr/v1/"))
        assertNull(normalizeUrl("http://api.taraang.kr"))
        assertNull(normalizeUrl("http://127.0.0.1:8000"))
        assertNull(normalizeUrl("http://192.168.0.10:8000"))
    }

    @Test
    fun `normalizeUrl drops default ports like the repository does`() {
        assertEquals("https://api.taraang.kr", normalizeUrl("https://api.taraang.kr:443"))
        assertEquals("http://localhost", normalizeUrl("http://localhost:80/"))
        assertEquals("https://api.taraang.kr:8443", normalizeUrl("https://api.taraang.kr:8443"))
    }

    @Test
    fun `normalizeUrl rejects other schemes, credentials, query, fragment, spaces and bad ports`() {
        listOf(
            "", "   ", "ftp://10.0.2.2", "javascript:alert(1)", "10.0.2.2:8000", "https://",
            "https://user:pw@api.taraang.kr", "https://api.taraang.kr?x=1", "https://api.taraang.kr#a",
            "https://api taraang.kr", "https://api.taraang.kr:0", "https://api.taraang.kr:70000",
        ).forEach { assertNull(it, normalizeUrl(it)) }
    }

    @Test
    fun `normalizeUrl enforces the length limit`() {
        val base = "https://a.kr/"
        val ok = base + "p".repeat(UiConfig.SERVER_URL_MAX - base.length)
        assertNotNull(normalizeUrl(ok))
        assertNull(normalizeUrl(ok + "p"))
    }

    @Test
    fun `draft starts from the stored url`() {
        val vm = SettingsViewModel(TestContainer(settingsRepository = PolicySettingsRepository()), SavedStateHandle(), urlEditable = true)
        assertFalse(vm.state.value.loading)
        assertEquals(FeedbackSettings.DEFAULT_SERVER_URL, vm.state.value.urlDraft)
    }

    @Test
    fun `draft survives process death through SavedStateHandle`() {
        val repo = PolicySettingsRepository()
        val handle = SavedStateHandle()
        SettingsViewModel(TestContainer(settingsRepository = repo), handle, urlEditable = true).editUrl("https://typing.kr")
        val restored = SettingsViewModel(TestContainer(settingsRepository = repo), handle, urlEditable = true)
        assertEquals("https://typing.kr", restored.state.value.urlDraft)
    }

    @Test
    fun `saving a valid url stores the normalized value and clears the draft`() {
        val repo = PolicySettingsRepository()
        val handle = SavedStateHandle()
        val vm = SettingsViewModel(TestContainer(settingsRepository = repo), handle, urlEditable = true)
        vm.editUrl("https://API.taraang.kr/")
        vm.saveUrl()
        assertEquals("https://api.taraang.kr", repo.state.value.courseServerUrl)
        assertEquals(UrlStatus.SAVED, vm.state.value.urlStatus)
        assertEquals("https://api.taraang.kr", vm.state.value.urlDraft)
        assertFalse(vm.state.value.savingUrl)
        assertTrue("serverUrlDraft" !in handle.keys())
    }

    @Test
    fun `invalid url is not sent to the repository`() {
        val repo = PolicySettingsRepository()
        val vm = SettingsViewModel(TestContainer(settingsRepository = repo), SavedStateHandle(), urlEditable = true)
        vm.editUrl("http://evil.example.com")
        vm.saveUrl()
        assertEquals(UrlStatus.INVALID, vm.state.value.urlStatus)
        assertEquals(FeedbackSettings.DEFAULT_SERVER_URL, repo.state.value.courseServerUrl)
    }

    @Test
    fun `repository rejection is shown as invalid`() {
        val repo = PolicySettingsRepository(accept = { it == FeedbackSettings.DEFAULT_SERVER_URL })
        val vm = SettingsViewModel(TestContainer(settingsRepository = repo), SavedStateHandle(), urlEditable = true)
        vm.editUrl("https://api.taraang.kr")
        vm.saveUrl()
        assertEquals(UrlStatus.INVALID, vm.state.value.urlStatus)
        assertEquals(FeedbackSettings.DEFAULT_SERVER_URL, repo.state.value.courseServerUrl)
    }

    @Test
    fun `storage failure shows a message instead of crashing`() {
        val repo = PolicySettingsRepository().apply { failWith = IOException("disk") }
        val vm = SettingsViewModel(TestContainer(settingsRepository = repo), SavedStateHandle(), urlEditable = true)
        vm.setVoice(false)
        assertNotNull(vm.state.value.errorMessage)
        vm.dismissError()
        vm.editUrl("https://api.taraang.kr")
        vm.saveUrl()
        assertNotNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.savingUrl)
        assertEquals(UrlStatus.IDLE, vm.state.value.urlStatus)
    }

    @Test
    fun `very long paste is capped one past the limit so it stays invalid`() {
        val vm = SettingsViewModel(TestContainer(settingsRepository = PolicySettingsRepository()), SavedStateHandle(), urlEditable = true)
        vm.editUrl("https://a.kr/" + "p".repeat(10_000))
        assertEquals(UiConfig.SERVER_URL_MAX + 1, vm.state.value.urlDraft.length)
        vm.saveUrl()
        assertEquals(UrlStatus.INVALID, vm.state.value.urlStatus)
    }

    @Test
    fun `locked feedback type cannot be turned off`() {
        val repo = PolicySettingsRepository()
        val vm = SettingsViewModel(TestContainer(settingsRepository = repo), SavedStateHandle(), urlEditable = true)
        val locked = FeedbackType.entries.first { !it.userToggleable }
        vm.setEnabled(locked, false)
        assertTrue(repo.state.value.isEnabled(locked))
        val toggleable = FeedbackType.entries.first { it.userToggleable }
        vm.setEnabled(toggleable, false)
        assertFalse(repo.state.value.isEnabled(toggleable))
    }

    @Test
    fun `release build cannot change the server url even if the screen calls it`() {
        val repo = PolicySettingsRepository()
        val vm = SettingsViewModel(TestContainer(settingsRepository = repo), SavedStateHandle(), urlEditable = false)
        vm.editUrl("https://api.taraang.kr")
        vm.saveUrl()
        assertEquals(FeedbackSettings.DEFAULT_SERVER_URL, repo.state.value.courseServerUrl)
        assertEquals(UrlStatus.IDLE, vm.state.value.urlStatus)
        assertEquals(ServerLabel.DEFAULT, vm.state.value.serverLabel)
    }

    @Test
    fun `server label distinguishes default, custom and offline`() {
        val base = SettingsUiState()
        assertEquals(ServerLabel.DEFAULT, base.serverLabel)
        // release: 기본 주소가 빈 값이면 서버 없이 번들 코스.
        val release = SettingsUiState(defaultServerUrl = "", settings = FeedbackSettings(courseServerUrl = ""))
        assertEquals(ServerLabel.OFFLINE, release.serverLabel)
        assertEquals(ServerLabel.CUSTOM, base.copy(settings = FeedbackSettings(courseServerUrl = "https://a.kr")).serverLabel)
        assertEquals(ServerLabel.OFFLINE, base.copy(settings = FeedbackSettings(courseServerUrl = " ")).serverLabel)
    }

    @Test
    fun `deleting history reports success`() {
        val drive = ScriptedDriveRepository()
        val vm = SettingsViewModel(TestContainer(driveRepository = drive, settingsRepository = PolicySettingsRepository()), SavedStateHandle())
        vm.deleteAllHistory()
        assertEquals(1, drive.deleteCalls)
        assertTrue(vm.state.value.historyDeleted)
        assertFalse(vm.state.value.deletingHistory)
        assertNull(vm.state.value.errorMessage)
        vm.dismissHistoryDeleted()
        assertFalse(vm.state.value.historyDeleted)
    }

    @Test
    fun `refusal because a drive is running shows the driving notice, not the generic input message`() {
        val drive = ScriptedDriveRepository(deleteHistory = { AppResult.Err(AppError.InvalidInput("drive in progress")) })
        val vm = SettingsViewModel(TestContainer(driveRepository = drive, settingsRepository = PolicySettingsRepository()), SavedStateHandle())
        vm.deleteAllHistory()
        assertNull(vm.state.value.errorMessage)
        assertTrue(vm.state.value.deleteRefusedWhileDriving)
        assertTrue(vm.state.value.historyLocked)
        assertFalse(vm.state.value.historyDeleted)
        assertFalse(vm.state.value.deletingHistory)
    }

    @Test
    fun `other delete errors show their user message`() {
        val error = AppError.Storage("db")
        val drive = ScriptedDriveRepository(deleteHistory = { AppResult.Err(error) })
        val vm = SettingsViewModel(TestContainer(driveRepository = drive, settingsRepository = PolicySettingsRepository()), SavedStateHandle())
        vm.deleteAllHistory()
        assertEquals(error.userMessage(), vm.state.value.errorMessage)
        assertFalse(vm.state.value.historyLocked)
    }

    @Test
    fun `delete is locked while a drive is running and unlocks when it ends`() {
        val running = PreviewData.session.copy(endedAtMs = null)
        val controller = ScriptedDriveController(live = MutableStateFlow(LiveDriveState(session = running)))
        val drive = ScriptedDriveRepository()
        val vm = SettingsViewModel(
            TestContainer(driveRepository = drive, driveController = controller, settingsRepository = PolicySettingsRepository()),
            SavedStateHandle(),
        )
        assertTrue(vm.state.value.driving)
        assertTrue(vm.state.value.historyLocked)
        vm.deleteAllHistory()
        assertEquals(0, drive.deleteCalls)

        controller.live.value = LiveDriveState(session = PreviewData.session, finished = true)
        assertFalse(vm.state.value.historyLocked)
        vm.deleteAllHistory()
        assertEquals(1, drive.deleteCalls)
    }

    @Test
    fun `driving refusal clears once the drive ends`() {
        val running = PreviewData.session.copy(endedAtMs = null)
        val controller = ScriptedDriveController(live = MutableStateFlow(LiveDriveState(session = running)))
        val drive = ScriptedDriveRepository(deleteHistory = { AppResult.Err(AppError.InvalidInput("drive in progress")) })
        val vm = SettingsViewModel(
            TestContainer(driveRepository = drive, driveController = controller, settingsRepository = PolicySettingsRepository()),
            SavedStateHandle(),
        )
        controller.live.value = LiveDriveState()
        assertFalse(vm.state.value.historyLocked)
        vm.deleteAllHistory()
        assertTrue(vm.state.value.deleteRefusedWhileDriving)
        controller.live.value = LiveDriveState(session = running)
        controller.live.value = LiveDriveState()
        assertFalse(vm.state.value.historyLocked)
    }

    @Test
    fun `deleting history that throws shows a message instead of crashing`() {
        val drive = ScriptedDriveRepository(deleteHistory = { throw IllegalStateException("db") })
        val vm = SettingsViewModel(TestContainer(driveRepository = drive, settingsRepository = PolicySettingsRepository()), SavedStateHandle())
        vm.deleteAllHistory()
        assertNotNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.historyDeleted)
        assertFalse(vm.state.value.deletingHistory)
    }
}
