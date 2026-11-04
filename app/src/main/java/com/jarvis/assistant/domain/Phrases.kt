package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.messages.IncomingMessage
import com.jarvis.assistant.domain.reminders.CalendarEvent
import com.jarvis.assistant.domain.reminders.Reminder
import com.jarvis.assistant.domain.reminders.SpokenWhen
import java.time.LocalDate
import java.time.LocalDateTime

/** Short spoken confirmations. Hindi variants are used when the assistant language is Hindi. */
class Phrases(private val hindi: Boolean) {
    private fun pick(en: String, hi: String) = if (hindi) hi else en

    fun calling(name: String) = pick("Calling $name", "$name को कॉल कर रहा हूँ")
    fun whatsappSent(name: String) = pick("Message sent to $name on WhatsApp", "$name को व्हाट्सऐप पर मैसेज भेज दिया")
    fun whatsappOpened(name: String) =
        pick("I opened WhatsApp for $name. Please tap send.", "$name के लिए व्हाट्सऐप खोल दिया, भेजें दबाइए")
    fun smsSent(name: String) = pick("Text sent to $name", "$name को मैसेज भेज दिया")
    fun smsOpened(name: String) = pick("I opened your messages for $name. Please tap send.", "$name के लिए मैसेज खोल दिया")
    fun alarmSet(hour: Int, minute: Int) = pick("Alarm set for ${formatTime(hour, minute)}", "${formatTime(hour, minute)} का अलार्म लगा दिया")
    fun timerSet(seconds: Int) = pick("Timer set for ${formatDuration(seconds)}", "${formatDuration(seconds)} का टाइमर लगा दिया")
    fun opening(app: String) = pick("Opening $app", "$app खोल रहा हूँ")
    fun searching(query: String) = pick("Searching for $query", "$query खोज रहा हूँ")
    fun playing(query: String) = pick("Playing $query", "$query चला रहा हूँ")
    fun cancelled() = pick("Cancelled", "रद्द कर दिया")

    // Reminders
    fun reminderSet(text: String, at: LocalDateTime, now: LocalDateTime, exact: Boolean): String {
        val whenSpoken = SpokenWhen.of(at, now, hindi)
        val base = pick("Okay, I'll remind you $whenSpoken: $text.", "ठीक है, $whenSpoken याद दिलाऊँगा: $text।")
        return if (exact) base else base + pick(
            " It may be a few minutes late until you allow exact alarms in Jarvis settings.",
            " सटीक समय के लिए Jarvis सेटिंग्स में अलार्म की अनुमति दें।",
        )
    }
    fun reminderPast() = pick("That time has already passed. When should I remind you?", "वो समय निकल चुका है। कब याद दिलाऊँ?")
    fun reminderDue(text: String) = pick("Reminder: $text.", "याद दिलाना: $text।")
    fun noReminders() = pick("You have no upcoming reminders.", "कोई आने वाला रिमाइंडर नहीं है।")
    fun reminderList(reminders: List<Reminder>, now: LocalDateTime): String {
        val items = reminders.take(LIST_LIMIT).joinToString(" ") { "${it.text.trimEnd('.')}, ${SpokenWhen.of(it.at, now, hindi)}." }
        val more = (reminders.size - LIST_LIMIT).takeIf { it > 0 }?.let { pick(" And $it more.", " और $it।") }.orEmpty()
        return if (reminders.size == 1) {
            items + more
        } else {
            pick("You have ${reminders.size} reminders. ", "${reminders.size} रिमाइंडर हैं। ") + items + more
        }
    }
    fun reminderNotFound(query: String) = pick("I couldn't find a reminder about $query.", "$query का कोई रिमाइंडर नहीं मिला।")
    fun remindersCancelled(cancelled: List<Reminder>) = if (cancelled.size == 1) {
        pick("Cancelled the reminder: ${cancelled[0].text}.", "रिमाइंडर रद्द कर दिया: ${cancelled[0].text}।")
    } else {
        pick("Cancelled ${cancelled.size} reminders.", "${cancelled.size} रिमाइंडर रद्द कर दिए।")
    }

    // Calendar
    fun needCalendarPermission() = pick(
        "I need calendar access for that. Please allow it in Jarvis settings.",
        "इसके लिए कैलेंडर की अनुमति चाहिए। Jarvis सेटिंग्स में अनुमति दें।",
    )
    fun eventAdded(title: String, start: LocalDateTime, now: LocalDateTime) = pick(
        "Added $title ${SpokenWhen.of(start, now, hindi)} to your calendar.",
        "$title ${SpokenWhen.of(start, now, hindi)} कैलेंडर में जोड़ दिया।",
    )
    fun eventOpened() = pick("I opened your calendar with the event filled in. Please tap save.", "कैलेंडर में इवेंट भर दिया है, सेव दबाइए।")
    fun calendarMissing() = pick("I couldn't find a calendar on this phone.", "इस फ़ोन पर कोई कैलेंडर नहीं मिला।")
    fun agenda(events: List<CalendarEvent>, from: LocalDate, days: Int, today: LocalDate): String {
        val span = if (days == 1) SpokenWhen.day(from, today, hindi) else pick("in the next $days days", "अगले $days दिनों में")
        if (events.isEmpty()) return pick("Nothing on your calendar $span.", "$span कैलेंडर में कुछ नहीं है।")
        val items = events.take(LIST_LIMIT).joinToString(", ") { e ->
            val day = if (days == 1) "" else SpokenWhen.day(e.start.toLocalDate(), today, hindi) + " "
            val time = if (e.allDay) pick("all day", "पूरा दिन") else pick("at ", "") + formatTime(e.start.hour, e.start.minute)
            "${e.title} $day$time"
        }
        val more = (events.size - LIST_LIMIT).takeIf { it > 0 }?.let { pick(", and $it more", ", और $it") }.orEmpty()
        val count = if (events.size == 1) pick("one event", "एक इवेंट") else pick("${events.size} events", "${events.size} इवेंट")
        return pick("You have $count $span: ", "$span $count हैं: ") + items + more + "."
    }
    fun draftReadback(to: String?, message: String): String {
        // End the draft with exactly one sentence mark before the question.
        val text = message.trim().let { if (it.lastOrNull() in listOf('.', '!', '?', '।')) it else "$it." }
        return if (to == null) {
            pick("Here's your reply: $text Shall I send it?", "आपका जवाब: $text भेज दूँ?")
        } else {
            pick("Here's your message to $to: $text Shall I send it?", "$to के लिए मैसेज: $text भेज दूँ?")
        }
    }
    fun sayYesOrNo() = pick("Should I send it? Say yes or no.", "भेज दूँ? हाँ या ना बोलिए")
    fun draftNotSent() = pick("Okay, I won't send it.", "ठीक है, नहीं भेजा")
    fun notFound(name: String) = pick("I couldn't find $name in your contacts.", "$name आपके कॉन्टैक्ट्स में नहीं मिला")
    fun whichOne(options: List<String>) =
        pick("I found ${joinNames(options, "and")}. Which one?", "${joinNames(options, "और")} मिले। कौन सा?")
    fun didNotCatchChoice() = pick("Sorry, I didn't catch which one. Cancelled.", "समझ नहीं आया, रद्द कर दिया")
    fun didNotHear() = pick("I didn't hear anything.", "मुझे कुछ सुनाई नहीं दिया")
    fun appNotFound(app: String) = pick("I couldn't find an app called $app.", "$app नाम का ऐप नहीं मिला")
    fun noApp(what: String) = pick("There's no app on this phone that can $what.", "इस फ़ोन पर यह करने वाला ऐप नहीं है")
    fun whatsappMissing() = pick("WhatsApp isn't installed.", "व्हाट्सऐप इंस्टॉल नहीं है")
    fun needPermission(what: String) =
        pick("I need the $what permission for that. Please allow it in the Jarvis app.", "इसके लिए $what की अनुमति चाहिए")
    fun noPhoneNumber(name: String) = pick("$name doesn't have a phone number.", "$name का फ़ोन नंबर नहीं है")
    fun needNotificationAccess() =
        pick("I need notification access to read your messages. Please turn it on in Jarvis settings.", "मैसेज पढ़ने के लिए नोटिफिकेशन एक्सेस चाहिए")
    fun noMessages() = pick("You have no new messages.", "कोई नया मैसेज नहीं है")
    fun noMessagesFrom(who: String) = pick("No new messages from $who.", "$who का कोई नया मैसेज नहीं है")
    fun nothingToReply() = pick("I couldn't find a message to reply to.", "जवाब देने के लिए कोई मैसेज नहीं मिला")
    fun cantReply(app: String) = pick("$app doesn't let me reply from the notification.", "$app में यहाँ से जवाब नहीं दे सकता")
    fun replySent(name: String) = pick("Replied to $name", "$name को जवाब भेज दिया")
    fun unlockFirst() = pick("Please unlock your phone and try again.", "पहले फ़ोन अनलॉक कीजिए")

    fun screenOff() = pick("Screen awareness is turned off in Jarvis settings.", "स्क्रीन देखने की सुविधा सेटिंग्स में बंद है")
    fun screenUnavailable() = pick(
        "To see your screen I need the Jarvis accessibility service, on Android 11 or newer. Please turn it on in Jarvis settings.",
        "स्क्रीन देखने के लिए Jarvis की एक्सेसिबिलिटी सर्विस चालू कीजिए",
    )
    fun screenNeedsWakeWord() = pick(
        "Open the app you want me to look at, then say Jarvis and ask again.",
        "जिस ऐप को दिखाना है उसे खोलिए, फिर Jarvis बोलकर पूछिए",
    )
    fun screenSecure() = pick("That app blocks screenshots, so I can't see it.", "यह ऐप स्क्रीनशॉट की अनुमति नहीं देता")

    fun foundMe() = pick("I'm here!", "मैं यहाँ हूँ!")

    fun someone() = pick("Someone", "कोई")
    fun isCalling(who: String) =
        pick("$who is calling. Answer, reject, or tell me what to text them?", "$who का कॉल है। उठाऊँ, काटूँ, या कोई मैसेज भेजूँ?")
    fun callOptions() = pick("Sorry, should I answer, reject, or send a message?", "माफ़ कीजिए, उठाऊँ, काटूँ, या मैसेज भेजूँ?")
    fun callDeclined() = pick("Declined.", "कॉल काट दिया")
    fun letItRing() = pick("OK, I'll let it ring.", "ठीक है, बजने देता हूँ")
    fun couldNotAnswer() = pick("I couldn't answer the call. Please allow Jarvis to manage calls.", "कॉल नहीं उठा पाया")
    fun couldNotDecline() = pick("I couldn't decline the call. Please allow Jarvis to manage calls.", "कॉल नहीं काट पाया")
    fun cantTextUnknownCaller() =
        pick("I declined it, but I don't know their number, so I couldn't text them.", "कॉल काट दिया, पर नंबर नहीं पता इसलिए मैसेज नहीं भेज पाया")

    fun liveUnavailable() =
        pick("Live mode isn't available right now, so I'm using normal mode.", "लाइव मोड अभी उपलब्ध नहीं है, सामान्य मोड चल रहा है")

    fun backendNotSet() =
        pick("Please set the backend URL and shared secret in settings first.", "पहले सेटिंग्स में बैकएंड सेट कीजिए")

    fun readMessages(items: List<IncomingMessage>): String {
        val body = items.joinToString(" ") { m ->
            val text = m.text.take(MAX_READ_CHARS)
            if (m.sender.isBlank() || m.sender == m.appLabel) "${m.appLabel}: $text." else "${m.sender} on ${m.appLabel}: $text."
        }
        val intro = if (items.size == 1) "" else pick("${items.size} messages. ", "${items.size} मैसेज। ")
        return intro + body
    }

    companion object {
        private const val LIST_LIMIT = 6

        private const val MAX_READ_CHARS = 300
        fun formatTime(hour: Int, minute: Int): String {
            val h12 = if (hour % 12 == 0) 12 else hour % 12
            val suffix = if (hour < 12) "AM" else "PM"
            return if (minute == 0) "$h12 $suffix" else "$h12:${minute.toString().padStart(2, '0')} $suffix"
        }

        fun formatDuration(seconds: Int): String {
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            val s = seconds % 60
            return listOfNotNull(
                h.takeIf { it > 0 }?.let { plural(it, "hour") },
                m.takeIf { it > 0 }?.let { plural(it, "minute") },
                s.takeIf { it > 0 }?.let { plural(it, "second") },
            ).joinToString(" ")
        }

        private fun plural(n: Int, unit: String) = if (n == 1) "1 $unit" else "$n ${unit}s"

        private fun joinNames(names: List<String>, and: String) = when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " $and " + names.last()
        }
    }
}
