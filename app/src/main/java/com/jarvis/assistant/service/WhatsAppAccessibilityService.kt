package com.jarvis.assistant.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.platform.WhatsAppSendCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Taps WhatsApp's Send button right after Jarvis opened a chat with a prefilled message.
 * It does nothing unless [WhatsAppSendCoordinator] has an armed, unexpired request.
 *
 * Lookup order: known view ids -> content description "Send" (several languages) -> any clickable
 * ImageButton at the bottom of the conversation whose description contains "send".
 */
@AndroidEntryPoint
class WhatsAppAccessibilityService : AccessibilityService() {

    @Inject lateinit var coordinator: WhatsAppSendCoordinator

    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { tryClickSend() }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in PACKAGES) return
        if (coordinator.activeRequest() == null) return
        tryClickSend()
    }

    private fun tryClickSend() {
        val request = coordinator.activeRequest() ?: return
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg !in PACKAGES) {
            scheduleRetry()
            return
        }
        val button = findSendButton(root, pkg!!)
        if (button != null && click(button)) {
            coordinator.reportSent(request)
            handler.removeCallbacks(retry)
        } else {
            // The chat may still be loading; content-changed events usually arrive, but poll as a fallback.
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, RETRY_MS)
    }

    private fun findSendButton(root: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        for (id in SEND_IDS) {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
                .firstOrNull { it.isVisibleToUser && it.isEnabled }
                ?.let { return it }
        }
        for (label in SEND_LABELS) {
            root.findAccessibilityNodeInfosByText(label)
                .firstOrNull { it.contentDescription?.toString()?.equals(label, ignoreCase = true) == true && it.isVisibleToUser }
                ?.let { return it }
        }
        return findByDescription(root, depth = 0)
    }

    private fun findByDescription(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_DEPTH) return null
        val desc = node.contentDescription?.toString()?.lowercase()
        if (desc != null && "send" in desc && node.isClickable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findByDescription(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = current.parent
        }
        return false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The system rebinds this service after Jarvis is killed, so use it to resume listening.
        JarvisListeningService.ensureRunning(this)
    }

    override fun onInterrupt() {
        handler.removeCallbacks(retry)
    }

    override fun onDestroy() {
        handler.removeCallbacks(retry)
        super.onDestroy()
    }

    private companion object {
        const val RETRY_MS = 400L
        const val MAX_DEPTH = 40
        val PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        val SEND_IDS = listOf("send", "send_btn", "conversation_send_button")
        val SEND_LABELS = listOf("Send", "भेजें", "Enviar", "Envoyer", "Senden")
    }
}
