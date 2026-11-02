package com.jarvis.assistant.platform

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.jarvis.assistant.service.WhatsAppAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-off between the action executor (which opens the wa.me deep link) and the accessibility
 * service (which taps Send). The service only acts while a request is armed, so it never touches
 * WhatsApp on its own.
 */
@Singleton
class WhatsAppSendCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    class Request(val expectedText: String, val deadline: Long) {
        val result = CompletableDeferred<Boolean>()
    }

    private val pending = AtomicReference<Request?>(null)

    fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, WhatsAppAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** Arms a send request; returns a suspend function that waits for the tap or times out. */
    fun arm(message: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): suspend () -> Boolean {
        val request = Request(message, System.currentTimeMillis() + timeoutMs)
        pending.getAndSet(request)?.result?.complete(false)
        return {
            val sent = withTimeoutOrNull(timeoutMs) { request.result.await() } ?: false
            pending.compareAndSet(request, null)
            sent
        }
    }

    /** Called by the accessibility service; null when nothing is armed or the request expired. */
    fun activeRequest(): Request? {
        val request = pending.get() ?: return null
        if (System.currentTimeMillis() > request.deadline) {
            pending.compareAndSet(request, null)
            request.result.complete(false)
            return null
        }
        return request
    }

    fun reportSent(request: Request) {
        if (pending.compareAndSet(request, null)) request.result.complete(true)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}
