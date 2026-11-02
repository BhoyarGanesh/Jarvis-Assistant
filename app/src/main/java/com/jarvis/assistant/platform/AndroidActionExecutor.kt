package com.jarvis.assistant.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.MediaStore
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.jarvis.assistant.domain.ActionExecutor
import com.jarvis.assistant.domain.ActionOutcome
import com.jarvis.assistant.domain.ActionStatus
import com.jarvis.assistant.domain.Phrases
import com.jarvis.assistant.domain.apps.AppMatcher
import com.jarvis.assistant.domain.apps.InstalledApp
import com.jarvis.assistant.domain.contacts.Contact
import com.jarvis.assistant.domain.model.AssistantCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidActionExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val whatsApp: WhatsAppSendCoordinator,
) : ActionExecutor {

    override suspend fun execute(command: AssistantCommand, contact: Contact?, phrases: Phrases): ActionOutcome =
        when (command) {
            is AssistantCommand.CallContact -> call(requireNotNull(contact), phrases)
            is AssistantCommand.SendWhatsApp -> sendWhatsApp(requireNotNull(contact), command.message, phrases)
            is AssistantCommand.SendSms -> sendSms(requireNotNull(contact), command.message, phrases)
            is AssistantCommand.SetAlarm -> launch(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, command.hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, command.minute)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .apply { if (command.label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, command.label) },
                success = phrases.alarmSet(command.hour, command.minute),
                missing = phrases.noApp("set alarms"),
            )
            is AssistantCommand.SetTimer -> launch(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, command.seconds)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .apply { if (command.label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, command.label) },
                success = phrases.timerSet(command.seconds),
                missing = phrases.noApp("set timers"),
            )
            is AssistantCommand.OpenApp -> openApp(command.appName, phrases)
            is AssistantCommand.WebSearch -> webSearch(command.query, phrases)
            is AssistantCommand.PlayMusic -> playMusic(command.query, phrases)
            is AssistantCommand.Answer -> ActionOutcome(ActionStatus.SUCCESS, command.text)
        }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked via granted()
    private fun call(contact: Contact, phrases: Phrases): ActionOutcome {
        if (!granted(Manifest.permission.CALL_PHONE)) {
            return ActionOutcome(ActionStatus.FAILED, phrases.needPermission("phone"), "CALL_PHONE not granted")
        }
        val uri = Uri.fromParts("tel", contact.phoneNumber, null)
        return try {
            // placeCall goes through the system dialer, so it also works while Jarvis is in the background.
            context.getSystemService(TelecomManager::class.java).placeCall(uri, Bundle())
            ActionOutcome(ActionStatus.SUCCESS, phrases.calling(contact.displayName))
        } catch (_: SecurityException) {
            launch(Intent(Intent.ACTION_CALL, uri), phrases.calling(contact.displayName), phrases.noApp("make calls"))
        }
    }

    private suspend fun sendWhatsApp(contact: Contact, message: String, phrases: Phrases): ActionOutcome {
        val pkg = WHATSAPP_PACKAGES.firstOrNull(::isInstalled)
            ?: return ActionOutcome(ActionStatus.FAILED, phrases.whatsappMissing())
        val digits = toInternationalDigits(contact.phoneNumber)
        val intent = Intent(Intent.ACTION_VIEW, WhatsAppLink.build(digits, message).toUri()).setPackage(pkg)

        val autoSend = whatsApp.isServiceEnabled()
        val awaitSent = if (autoSend) whatsApp.arm(message) else null
        val opened = startActivity(intent)
        if (!opened) {
            return ActionOutcome(ActionStatus.FAILED, phrases.whatsappMissing(), "wa.me intent failed")
        }
        if (awaitSent == null) {
            return ActionOutcome(ActionStatus.NEEDS_USER, phrases.whatsappOpened(contact.displayName), "Accessibility service disabled")
        }
        return if (awaitSent()) {
            ActionOutcome(ActionStatus.SUCCESS, phrases.whatsappSent(contact.displayName))
        } else {
            ActionOutcome(ActionStatus.NEEDS_USER, phrases.whatsappOpened(contact.displayName), "Send button not found in time")
        }
    }

    private suspend fun sendSms(contact: Contact, message: String, phrases: Phrases): ActionOutcome {
        if (granted(Manifest.permission.SEND_SMS)) {
            val sent = withContext(Dispatchers.IO) {
                runCatching {
                    val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        context.getSystemService(SmsManager::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        SmsManager.getDefault()
                    }
                    val parts = sms.divideMessage(message)
                    sms.sendMultipartTextMessage(contact.phoneNumber, null, parts, null, null)
                }.isSuccess
            }
            if (sent) return ActionOutcome(ActionStatus.SUCCESS, phrases.smsSent(contact.displayName))
        }
        // No permission (or the send failed): fall back to the SMS app with the text prefilled.
        val intent = Intent(Intent.ACTION_SENDTO, "smsto:${Uri.encode(contact.phoneNumber)}".toUri())
            .putExtra("sms_body", message)
        return if (startActivity(intent)) {
            ActionOutcome(ActionStatus.NEEDS_USER, phrases.smsOpened(contact.displayName), "Opened SMS composer")
        } else {
            ActionOutcome(ActionStatus.FAILED, phrases.noApp("send texts"))
        }
    }

    private fun openApp(name: String, phrases: Phrases): ActionOutcome {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
        val app = AppMatcher.bestMatch(name, apps)
            ?: return ActionOutcome(ActionStatus.FAILED, phrases.appNotFound(name))
        val intent = pm.getLaunchIntentForPackage(app.packageName)
            ?: return ActionOutcome(ActionStatus.FAILED, phrases.appNotFound(name))
        return if (startActivity(intent)) ActionOutcome(ActionStatus.SUCCESS, phrases.opening(app.label))
        else ActionOutcome(ActionStatus.FAILED, phrases.appNotFound(name))
    }

    private fun webSearch(query: String, phrases: Phrases): ActionOutcome {
        val search = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)
        val browser = Intent(Intent.ACTION_VIEW, "https://www.google.com/search?q=${Uri.encode(query)}".toUri())
        return if (startActivity(search) || startActivity(browser)) {
            ActionOutcome(ActionStatus.SUCCESS, phrases.searching(query))
        } else {
            ActionOutcome(ActionStatus.FAILED, phrases.noApp("search the web"))
        }
    }

    private fun playMusic(query: String, phrases: Phrases): ActionOutcome {
        val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            .putExtra(SearchManager.QUERY, query)
        val web = Intent(Intent.ACTION_VIEW, "https://music.youtube.com/search?q=${Uri.encode(query)}".toUri())
        return if (startActivity(play) || startActivity(web)) {
            ActionOutcome(ActionStatus.SUCCESS, phrases.playing(query))
        } else {
            ActionOutcome(ActionStatus.FAILED, phrases.noApp("play music"))
        }
    }

    private fun launch(intent: Intent, success: String, missing: String): ActionOutcome =
        if (startActivity(intent)) ActionOutcome(ActionStatus.SUCCESS, success)
        else ActionOutcome(ActionStatus.FAILED, missing)

    private fun startActivity(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun toInternationalDigits(number: String): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val iso = listOfNotNull(tm?.simCountryIso, tm?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { it.isNotBlank() }?.uppercase(Locale.ROOT) ?: "US"
        val e164 = PhoneNumberUtils.formatNumberToE164(number, iso)
        return WhatsAppLink.digits(e164 ?: number)
    }

    companion object {
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
    }
}
