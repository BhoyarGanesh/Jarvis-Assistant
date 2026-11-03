package com.jarvis.assistant.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.data.history.ActionLogEntity
import com.jarvis.assistant.data.history.HistoryRepository
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.domain.AppSettings
import com.jarvis.assistant.domain.AssistantOrchestrator
import com.jarvis.assistant.domain.AssistantUiState
import com.jarvis.assistant.domain.wakeword.WakeWordEngine
import com.jarvis.assistant.domain.wakeword.WakeWordEngineState
import com.jarvis.assistant.voice.Mouth
import com.jarvis.assistant.voice.MouthLevel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val orchestrator: AssistantOrchestrator,
    private val historyRepository: HistoryRepository,
    settingsRepository: SettingsRepository,
    wakeWordEngine: WakeWordEngine,
    mouthLevel: MouthLevel,
) : ViewModel() {

    val uiState: StateFlow<AssistantUiState> = orchestrator.state
    val level: StateFlow<Float> = orchestrator.level
    val mouth: StateFlow<Mouth> = mouthLevel.mouth
    val engineState: StateFlow<WakeWordEngineState> = wakeWordEngine.state

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val history: StateFlow<List<ActionLogEntity>> = historyRepository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The big button: start listening, or stop whatever is happening. */
    fun onTalkPressed() {
        if (orchestrator.isBusy) orchestrator.cancel() else orchestrator.trigger()
    }

    fun cancel() = orchestrator.cancel()
    fun confirmNow() = orchestrator.confirmNow()
    fun choose(index: Int) = orchestrator.chooseOption(index)
    fun dismissError() = orchestrator.dismissError()

    fun clearHistory() {
        viewModelScope.launch { historyRepository.clear() }
    }
}
