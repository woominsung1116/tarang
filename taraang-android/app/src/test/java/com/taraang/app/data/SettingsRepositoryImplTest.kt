package com.taraang.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.taraang.app.core.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
