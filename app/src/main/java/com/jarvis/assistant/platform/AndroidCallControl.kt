package com.jarvis.assistant.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract.PhoneLookup
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.domain.CallControl
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Answers, declines and quiets the ringing call for "King is calling" announcements. */
@Singleton
class AndroidCallControl @Inject constructor(
    @ApplicationContext private val context: Context,
) : CallControl {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var mutedByUs = false

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Everything caller announcements need; call log access is optional (it reveals the number). */
    fun hasRequiredPermissions(): Boolean =
        granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.ANSWER_PHONE_CALLS)

    fun canSeeNumbers(): Boolean = granted(Manifest.permission.READ_CALL_LOG)

    @SuppressLint("MissingPermission") // checked via granted()
    override fun answer(): Boolean {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return try {
            @Suppress("DEPRECATION") // Still the only way for a non-dialer app to pick up.
            telecom.acceptRingingCall()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "acceptRingingCall refused", e)
            false
        }
    }

    @SuppressLint("MissingPermission") // checked via granted()
    override fun reject(): Boolean {
        if (!granted(Manifest.permission.ANSWER_PHONE_CALLS) || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            @Suppress("DEPRECATION") // Still the only way for a non-dialer app to decline.
            telecom.endCall()
        } catch (e: SecurityException) {
            Log.w(TAG, "endCall refused", e)
            false
        }
    }

    override fun silenceRinger() {
        // Leave phones the user already silenced alone, so restoring never turns their ringer on.
        if (mutedByUs || audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.isStreamMute(AudioManager.STREAM_RING)) return
        mutedByUs = runCatching { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0) }
            .onFailure { Log.w(TAG, "Could not mute the ringer", it) }
            .isSuccess
    }

    override fun restoreRinger() {
        if (!mutedByUs) return
        mutedByUs = false
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0) }
            .onFailure { Log.w(TAG, "Could not unmute the ringer", it) }
    }

    /** The contact name saved for [number], if any. */
    suspend fun contactNameFor(number: String?): String? = withContext(Dispatchers.IO) {
        if (number.isNullOrBlank() || !granted(Manifest.permission.READ_CONTACTS)) return@withContext null
        runCatching {
            context.contentResolver.query(
                Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                arrayOf(PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    private companion object {
        const val TAG = "CallControl"
    }
}
