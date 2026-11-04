package com.taraang.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.taraang.app.core.Logger
import com.taraang.app.domain.model.FeedbackType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 서버 주소 기본값·평문 정책(M2): release는 빈 기본값, 평문 주소를 저장도 사용도 하지 않는다. */
class SettingsRepositoryImplTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val store by lazy { PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("settings.preferences_pb").also { it.delete() } } }

    @After
    fun tearDown() = scope.cancel()

    private fun release() = SettingsRepositoryImpl(store, Logger.Noop, defaultServerUrl = "", allowCleartext = false)
    private fun debug() = SettingsRepositoryImpl(store, Logger.Noop, defaultServerUrl = "http://10.0.2.2:8000", allowCleartext = true)

    @Test
    fun `school zone preview is off by default and the user's choice either way is kept`() = runBlocking {
        val repo = release()
        val fresh = repo.settings.first()
        assertFalse(fresh.isEnabled(FeedbackType.SCHOOL_ZONE_AHEAD))
        assertTrue(fresh.isEnabled(FeedbackType.SCHOOL_ZONE_SPEED))
        repo.update { it.copy(enabled = it.enabled + (FeedbackType.SCHOOL_ZONE_AHEAD to true)) }
        assertTrue(repo.settings.first().isEnabled(FeedbackType.SCHOOL_ZONE_AHEAD))
        // 다른 설정을 바꿔도 켠 값이 남는다.
        repo.update { it.copy(voiceEnabled = false) }
        assertTrue(repo.settings.first().isEnabled(FeedbackType.SCHOOL_ZONE_AHEAD))
        repo.update { it.copy(enabled = it.enabled + (FeedbackType.SCHOOL_ZONE_AHEAD to false)) }
        assertFalse(repo.settings.first().isEnabled(FeedbackType.SCHOOL_ZONE_AHEAD))
    }

    @Test
    fun `settings stored by an older version keep their disabled choices and get the new default`() = runBlocking {
        // 옛 버전은 끈 타입 목록만 저장했다. 보호구역 예고를 켠 선택은 저장할 방법이 없었으므로 새 기본값(꺼짐)을 받는다.
        store.edit { it[stringSetPreferencesKey("disabled_feedback")] = setOf("SKILL_PREVIEW") }
        val s = release().settings.first()
        assertFalse(s.isEnabled(FeedbackType.SKILL_PREVIEW))
        assertFalse(s.isEnabled(FeedbackType.SCHOOL_ZONE_AHEAD))
        assertTrue(s.isEnabled(FeedbackType.TURN_SIGNAL_MISSING))
    }

    @Test
    fun `release default is empty, meaning no server`() = runBlocking {
        assertEquals("", release().settings.first().courseServerUrl)
    }

    @Test
    fun `debug default is the emulator dev server`() = runBlocking {
        assertEquals("http://10.0.2.2:8000", debug().settings.first().courseServerUrl)
    }

    @Test
    fun `cleartext url saved by a debug build is ignored by release`() = runBlocking {
        store.edit { it[stringPreferencesKey("course_server_url")] = "http://10.0.2.2:8000" }
        assertEquals("", release().settings.first().courseServerUrl)
        assertEquals("http://10.0.2.2:8000", debug().settings.first().courseServerUrl)
    }

    @Test
    fun `release refuses to save a cleartext url but saves https`() = runBlocking {
        val repo = release()
        repo.update { it.copy(courseServerUrl = "http://localhost:8000") }
        assertEquals("", repo.settings.first().courseServerUrl)
        repo.update { it.copy(courseServerUrl = "https://courses.example.kr/") }
        assertEquals("https://courses.example.kr", repo.settings.first().courseServerUrl)
    }
}
