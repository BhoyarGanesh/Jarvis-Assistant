package com.jarvis.assistant.platform

import android.app.KeyguardManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Jarvis can run over the lock screen, but opening another app (WhatsApp, YouTube...) needs the
 * phone unlocked. Actions call [ensureUnlocked]; the visible [com.jarvis.assistant.MainActivity]
 * shows the system unlock prompt and reports back.
 */
@Singleton
class UnlockCoordinator @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val _requests = MutableSharedFlow<CompletableDeferred<Boolean>>(extraBufferCapacity = 1)
    val requests: SharedFlow<CompletableDeferred<Boolean>> = _requests.asSharedFlow()

    val isLocked: Boolean get() = keyguard.isKeyguardLocked

    /** True when the phone is (or becomes) unlocked; false if the user cancels or no screen can ask. */
    suspend fun ensureUnlocked(): Boolean {
        if (!keyguard.isKeyguardLocked) return true
        if (_requests.subscriptionCount.value == 0) return false
        val answer = CompletableDeferred<Boolean>()
        _requests.emit(answer)
        return withTimeoutOrNull(UNLOCK_TIMEOUT_MS) { answer.await() } ?: false
    }

    private companion object {
        const val UNLOCK_TIMEOUT_MS = 30_000L
    }
}
