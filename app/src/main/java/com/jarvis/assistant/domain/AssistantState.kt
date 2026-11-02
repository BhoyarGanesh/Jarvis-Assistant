package com.jarvis.assistant.domain

/** Phases the orb animates between. */
enum class AssistantPhase { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

enum class ActionKind { CALL, WHATSAPP, SMS, ALARM, TIMER, APP, SEARCH, MUSIC, ANSWER, INFO }

data class ActionCard(
    val id: Long,
    val kind: ActionKind,
    val title: String,
    val subtitle: String? = null,
    val status: ActionStatus = ActionStatus.SUCCESS,
)

data class PendingConfirmation(
    val title: String,
    val subtitle: String?,
    val kind: ActionKind,
    val totalSeconds: Int,
    val secondsLeft: Int,
)

data class Disambiguation(val prompt: String, val options: List<String>)

data class AssistantUiState(
    val phase: AssistantPhase = AssistantPhase.IDLE,
    val transcript: String = "",
    val reply: String = "",
    val cards: List<ActionCard> = emptyList(),
    val confirmation: PendingConfirmation? = null,
    val disambiguation: Disambiguation? = null,
    val errorMessage: String? = null,
) {
    val busy: Boolean get() = phase != AssistantPhase.IDLE && phase != AssistantPhase.ERROR
}
