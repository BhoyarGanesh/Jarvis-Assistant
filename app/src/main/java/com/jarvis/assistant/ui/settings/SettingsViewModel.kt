package com.jarvis.assistant.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.data.remote.BackendClient
import com.jarvis.assistant.data.remote.HealthResult
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.domain.AppSettings
import com.jarvis.assistant.domain.wakeword.WakeWordEngine
import com.jarvis.assistant.domain.wakeword.WakeWordEngineState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConnectionTest {
    data object Idle : ConnectionTest
    data object Running : ConnectionTest
    data class Done(val ok: Boolean, val message: String) : ConnectionTest
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val backend: BackendClient,
    wakeWordEngine: WakeWordEngine,
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val engineState: StateFlow<WakeWordEngineState> = wakeWordEngine.state

    private val _connection = MutableStateFlow<ConnectionTest>(ConnectionTest.Idle)
    val connection: StateFlow<ConnectionTest> = _connection.asStateFlow()

    private fun save(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repo.block() }
    }

    fun setWakeWord(v: Boolean) = save { setWakeWordEnabled(v) }
    fun setSensitivity(v: Float) = save { setSensitivity(v) }
    fun setDebounce(v: Long) = save { setDebounceMs(v) }
    fun setBackendUrl(v: String) {
        _connection.value = ConnectionTest.Idle
        save { setBackendUrl(v) }
    }
    fun setSecret(v: String) = save { setSharedSecret(v) }
    fun setVoiceReply(v: Boolean) = save { setVoiceReply(v) }
    fun setCountdown(v: Boolean) = save { setCountdownEnabled(v) }
    fun setCountdownSeconds(v: Int) = save { setCountdownSeconds(v) }
    fun setLanguage(tag: String) = save { setLanguage(tag) }
    fun setStartOnBoot(v: Boolean) = save { setStartOnBoot(v) }
    fun setShareContacts(v: Boolean) = save { setShareContactNames(v) }
    fun completeOnboarding() = save { setOnboardingComplete(true) }

    fun testConnection(url: String) {
        _connection.value = ConnectionTest.Running
        viewModelScope.launch {
            _connection.value = when (val r = backend.health(url)) {
                is HealthResult.Ok -> ConnectionTest.Done(
                    ok = r.configured,
                    message = if (r.configured) "Connected • ${r.provider} (${r.model})"
                    else "Reached the backend, but its ${r.provider} API key isn't set in backend/.env",
                )
                is HealthResult.Failed -> ConnectionTest.Done(false, "Can't reach backend: ${r.message}")
            }
        }
    }
}
