package com.jarvis.assistant.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.jarvis.assistant.domain.AppSettings
import com.jarvis.assistant.domain.SettingsSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : SettingsSource {
    private val store = context.dataStore

    private object Keys {
        val wakeWord = booleanPreferencesKey("wake_word_enabled")
        val sensitivity = floatPreferencesKey("wake_word_sensitivity")
        val debounce = longPreferencesKey("wake_word_debounce_ms")
        val backendUrl = stringPreferencesKey("backend_url")
        val secret = stringPreferencesKey("shared_secret")
        val voiceReply = booleanPreferencesKey("voice_reply")
        val countdown = booleanPreferencesKey("confirmation_countdown")
        val countdownSeconds = intPreferencesKey("confirmation_seconds")
        val language = stringPreferencesKey("language")
        val boot = booleanPreferencesKey("start_on_boot")
        val shareContacts = booleanPreferencesKey("share_contact_names")
        val onboarding = booleanPreferencesKey("onboarding_complete")
    }

    override val settings: Flow<AppSettings> = store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            wakeWordEnabled = p[Keys.wakeWord] ?: d.wakeWordEnabled,
            wakeWordSensitivity = p[Keys.sensitivity] ?: d.wakeWordSensitivity,
            wakeWordDebounceMs = p[Keys.debounce] ?: d.wakeWordDebounceMs,
            backendUrl = p[Keys.backendUrl] ?: d.backendUrl,
            sharedSecret = p[Keys.secret] ?: d.sharedSecret,
            voiceReplyEnabled = p[Keys.voiceReply] ?: d.voiceReplyEnabled,
            confirmationCountdownEnabled = p[Keys.countdown] ?: d.confirmationCountdownEnabled,
            confirmationSeconds = p[Keys.countdownSeconds] ?: d.confirmationSeconds,
            languageTag = p[Keys.language] ?: d.languageTag,
            startOnBoot = p[Keys.boot] ?: d.startOnBoot,
            shareContactNames = p[Keys.shareContacts] ?: d.shareContactNames,
            onboardingComplete = p[Keys.onboarding] ?: d.onboardingComplete,
        )
    }

    override suspend fun current(): AppSettings = settings.first()

    suspend fun setWakeWordEnabled(value: Boolean) = store.edit { it[Keys.wakeWord] = value }
    suspend fun setSensitivity(value: Float) = store.edit { it[Keys.sensitivity] = value.coerceIn(0f, 1f) }
    suspend fun setDebounceMs(value: Long) = store.edit { it[Keys.debounce] = value.coerceIn(500, 10_000) }
    suspend fun setBackendUrl(value: String) = store.edit { it[Keys.backendUrl] = value.trim().trimEnd('/') }
    suspend fun setSharedSecret(value: String) = store.edit { it[Keys.secret] = value.trim() }
    suspend fun setVoiceReply(value: Boolean) = store.edit { it[Keys.voiceReply] = value }
    suspend fun setCountdownEnabled(value: Boolean) = store.edit { it[Keys.countdown] = value }
    suspend fun setCountdownSeconds(value: Int) = store.edit { it[Keys.countdownSeconds] = value.coerceIn(1, 10) }
    suspend fun setLanguage(tag: String) = store.edit { it[Keys.language] = tag }
    suspend fun setStartOnBoot(value: Boolean) = store.edit { it[Keys.boot] = value }
    suspend fun setShareContactNames(value: Boolean) = store.edit { it[Keys.shareContacts] = value }
    suspend fun setOnboardingComplete(value: Boolean) = store.edit { it[Keys.onboarding] = value }
}
