package com.jarvis.assistant.platform

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import androidx.core.graphics.scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.max

/**
 * Grabs the screen through the accessibility service the moment the wake word fires, before the
 * Jarvis UI covers it, so "Jarvis, what's on my screen?" can be answered.
 *
 * The screenshot stays in memory only, is dropped after [TTL_MS] or as soon as it is used, and is
 * sent to the backend only when the user's request turns out to be about the screen.
 */
@Singleton
class ScreenCapture @Inject constructor() {

    sealed interface Shot {
        data class Image(val jpegBase64: String) : Shot
        /** The app on screen sets FLAG_SECURE (banking, passwords...). */
        data object Secure : Shot
    }

    private class Held(val shot: Shot, val takenAt: Long)

    @Volatile private var service: AccessibilityService? = null
    @Volatile private var held: Held? = null

    /** Set while a Jarvis activity is visible; its own UI is never worth capturing. */
    @Volatile var jarvisInForeground = false

    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable { held = null }

    val isAvailable: Boolean get() = service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun attach(service: AccessibilityService) {
        this.service = service
    }

    fun detach(service: AccessibilityService) {
        if (this.service === service) this.service = null
    }

    /** Captures the current screen into memory. Best effort: does nothing if unavailable. */
    suspend fun capture() {
        discard()
        val svc = service ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || jarvisInForeground) return
        val shot = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { screenshot(svc) } ?: return
        held = Held(shot, System.currentTimeMillis())
        handler.postDelayed(expire, TTL_MS)
    }

    /** Returns the screenshot from this interaction and forgets it; null if none is fresh. */
    fun take(): Shot? {
        val h = held
        discard()
        return h?.takeIf { System.currentTimeMillis() - it.takenAt <= TTL_MS }?.shot
    }

    fun discard() {
        handler.removeCallbacks(expire)
        held = null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun screenshot(svc: AccessibilityService): Shot? {
        val result = suspendCancellableCoroutine { cont ->
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                svc.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        if (cont.isActive) cont.resume(Result.success(screenshot)) else screenshot.hardwareBuffer.close()
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(Result.failure(ScreenshotFailed(errorCode)))
                    }
                },
            )
        }
        val screenshot = result.getOrElse { e ->
            val code = (e as ScreenshotFailed).code
            Log.w(TAG, "takeScreenshot failed: $code")
            return if (code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) Shot.Secure else null
        }
        return withContext(Dispatchers.Default) {
            screenshot.hardwareBuffer.use { buffer ->
                val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return@withContext null
                val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                hardware.recycle()
                Shot.Image(encode(bitmap))
            }
        }
    }

    private fun encode(bitmap: Bitmap): String {
        val longest = max(bitmap.width, bitmap.height)
        val scaled = if (longest > MAX_EDGE_PX) {
            val factor = MAX_EDGE_PX.toFloat() / longest
            bitmap.scale((bitmap.width * factor).toInt(), (bitmap.height * factor).toInt()).also { bitmap.recycle() }
        } else bitmap
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private class ScreenshotFailed(val code: Int) : Exception()

    private companion object {
        const val TAG = "ScreenCapture"
        const val TTL_MS = 60_000L
        const val CAPTURE_TIMEOUT_MS = 1_500L
        /** Enough for text to stay legible to the model while keeping uploads around 200 KB. */
        const val MAX_EDGE_PX = 1600
        const val JPEG_QUALITY = 75
    }
}
