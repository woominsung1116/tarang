package com.taraang.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.taraang.app.core.Logger
import com.taraang.app.data.course.ServerUrlPolicy
import com.taraang.app.domain.model.FeedbackSettings
import com.taraang.app.domain.model.FeedbackType
import com.taraang.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 피드백 설정(DataStore). 끈 항목만 저장해 새 피드백 타입이 생기면 기본으로 켜진다.
 * 읽기 실패(파일 손상 등)는 기본값으로 대신한다.
 * 서버 주소는 [ServerUrlPolicy]를 통과한 값만 저장한다. 통과하지 못하면 이전 값을 유지한다.
 * 기본 주소는 빌드별 [defaultServerUrl](BuildConfig.COURSE_SERVER_URL)이다. release는 빈 값이라 서버를 쓰지 않는다.
 * 저장된 주소도 읽을 때 다시 검사한다(debug에서 저장한 평문 주소가 release에서 쓰이지 않게).
 */
class SettingsRepositoryImpl(
    private val store: DataStore<Preferences>,
    private val logger: Logger,
    private val defaultServerUrl: String = FeedbackSettings.DEFAULT_SERVER_URL,
    private val allowCleartext: Boolean = false,
) : SettingsRepository {

    override val settings: Flow<FeedbackSettings> = store.data
        .catch { e ->
            if (e is IOException) {
                logger.e(TAG, "설정 읽기 실패, 기본값 사용", e)
                emit(emptyPreferences())
            } else {
                throw e
            }
        }
        .map { it.toSettings() }

    override suspend fun update(transform: (FeedbackSettings) -> FeedbackSettings) {
        try {
            store.edit { prefs ->
                val current = prefs.toSettings()
                val next = transform(current)
                val toggleable = FeedbackType.entries.filter { it.userToggleable }
                prefs[KEY_DISABLED] = toggleable.filter { !next.isEnabled(it) }.map { it.name }.toSet()
                // 기본으로 꺼진 타입을 사용자가 켠 것만 따로 남긴다(끈 목록만으로는 "켬"을 표현할 수 없다).
                prefs[KEY_ENABLED] = toggleable.filter { !it.defaultEnabled && next.isEnabled(it) }.map { it.name }.toSet()
                prefs[KEY_VOICE] = next.voiceEnabled
                val url = ServerUrlPolicy.validate(next.courseServerUrl, allowCleartext)
                if (url == null) logger.w(TAG, "허용되지 않는 서버 주소, 이전 값 유지")
                prefs[KEY_SERVER] = url ?: current.courseServerUrl
            }
        } catch (e: IOException) {
            logger.e(TAG, "설정 저장 실패", e)
        }
    }

    /**
     * 사용자가 고른 값이 있으면 그 값, 없으면 [FeedbackType.defaultEnabled]. 옛 버전은 끈 목록([KEY_DISABLED])만 저장했으므로
     * 그 선택은 그대로 지키고, 기본값이 바뀐 타입은 켠 기록([KEY_ENABLED])이 없으면 새 기본값을 따른다.
     */
    private fun Preferences.toSettings(): FeedbackSettings {
        val disabled = this[KEY_DISABLED].orEmpty()
        val explicitlyEnabled = this[KEY_ENABLED].orEmpty()
        return FeedbackSettings(
            enabled = FeedbackType.entries.associateWith {
                when {
                    !it.userToggleable -> true
                    it.name in disabled -> false
                    it.name in explicitlyEnabled -> true
                    else -> it.defaultEnabled
                }
            },
            voiceEnabled = this[KEY_VOICE] ?: true,
            courseServerUrl = this[KEY_SERVER]?.let { ServerUrlPolicy.validate(it, allowCleartext) } ?: defaultServerUrl,
        )
    }

    private companion object {
        const val TAG = "Settings"
        val KEY_DISABLED = stringSetPreferencesKey("disabled_feedback")
        val KEY_ENABLED = stringSetPreferencesKey("enabled_feedback")
        val KEY_VOICE = booleanPreferencesKey("voice_enabled")
        val KEY_SERVER = stringPreferencesKey("course_server_url")
    }
}
