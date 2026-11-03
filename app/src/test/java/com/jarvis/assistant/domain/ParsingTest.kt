package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.apps.AppMatcher
import com.jarvis.assistant.domain.apps.InstalledApp
import com.jarvis.assistant.domain.model.AssistantCommand
import com.jarvis.assistant.domain.model.CommandParser
import com.jarvis.assistant.domain.wakeword.WakeWordDetector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    private fun parse(tool: String, args: String) = CommandParser.parse(tool, Json.parseToJsonElement(args).jsonObject)

    @Test fun parsesEveryTool() {
        assertEquals(AssistantCommand.CallContact("King"), parse("call_contact", """{"name":"King"}"""))
        assertEquals(
            AssistantCommand.SendWhatsApp("Junior", "hi"),
            parse("send_whatsapp", """{"contact_name":"Junior","message":"hi"}"""),
        )
        assertEquals(
            AssistantCommand.SendSms("Mom", "on my way"),
            parse("send_sms", """{"contact_name":"Mom","message":"on my way"}"""),
        )
        assertEquals(AssistantCommand.SetAlarm(6, 30, "gym"), parse("set_alarm", """{"hour":6,"minute":30,"label":"gym"}"""))
        assertEquals(AssistantCommand.SetTimer(300, ""), parse("set_timer", """{"seconds":300}"""))
        assertEquals(AssistantCommand.OpenApp("YouTube"), parse("open_app", """{"app_name":"YouTube"}"""))
        assertEquals(AssistantCommand.WebSearch("weather"), parse("web_search", """{"query":"weather"}"""))
        assertEquals(AssistantCommand.PlayMusic("lofi"), parse("play_music", """{"query":"lofi"}"""))
        assertEquals(AssistantCommand.ScreenQuestion("is this a scam?"), parse("screen_question", """{"question":"is this a scam?"}"""))
        assertEquals(
            AssistantCommand.SummarizeMessages("", "Family", "summarise my family group"),
            parse("summarize_messages", """{"app_name":"","contact_name":"Family","question":"summarise my family group"}"""),
        )
        assertEquals(AssistantCommand.SummarizeMessages("", "", ""), parse("summarize_messages", "{}"))
        assertEquals(AssistantCommand.FindPhone, parse("find_phone", "{}"))
        assertEquals(AssistantCommand.Answer("Hello"), parse("answer", """{"text":"Hello"}"""))
    }

    @Test fun screenQuestionNeedsAQuestion() {
        assertEquals(AssistantCommand.Answer(CommandParser.FALLBACK), parse("screen_question", """{"question":"  "}"""))
    }

    @Test fun acceptsNumericStrings() {
        assertEquals(AssistantCommand.SetAlarm(7, 0, ""), parse("set_alarm", """{"hour":"7"}"""))
    }

    @Test fun invalidInputFallsBackToAnswer() {
        val fallback = AssistantCommand.Answer(CommandParser.FALLBACK)
        assertEquals(fallback, parse("call_contact", """{"name":"  "}"""))
        assertEquals(fallback, parse("set_alarm", """{"hour":25,"minute":0}"""))
        assertEquals(fallback, parse("set_timer", """{"seconds":0}"""))
        assertEquals(fallback, parse("send_whatsapp", """{"contact_name":"Junior"}"""))
        assertEquals(fallback, parse("format_phone", """{}"""))
        assertEquals(fallback, parse("call_contact", """{"name":42}"""))
    }
}

class WakeWordDetectorTest {
    private var now = 0L
    private fun detector(sensitivity: Float) = WakeWordDetector(sensitivity = sensitivity, debounceMs = 2_000, clock = { now })
    private fun result(conf: Double) = """{"result":[{"conf":$conf,"end":1.2,"start":0.8,"word":"jarvis"}],"text":"jarvis"}"""

    @Test fun confidenceThresholdFollowsSensitivity() {
        assertTrue(detector(0.5f).onResult(result(0.8)))
        assertFalse(detector(0.0f).onResult(result(0.8)))
        assertTrue(detector(1.0f).onResult(result(0.5)))
    }

    @Test fun unknownWordsDoNotTrigger() {
        val d = detector(1f)
        assertFalse(d.onResult("""{"result":[{"conf":1.0,"word":"[unk]"}],"text":"[unk]"}"""))
        assertFalse(d.onResult("""{"text":""}"""))
        assertFalse(d.onResult("not json"))
    }

    @Test fun partialsOnlyTriggerAtHighSensitivity() {
        assertFalse(detector(0.5f).onPartial("""{"partial":"jarvis"}"""))
        assertTrue(detector(0.9f).onPartial("""{"partial":"[unk] jarvis"}"""))
    }

    @Test fun debounceSuppressesRepeats() {
        val d = detector(0.5f)
        assertTrue(d.onResult(result(0.9)))
        now = 1_000
        assertFalse(d.onResult(result(0.9)))
        now = 2_500
        assertTrue(d.onResult(result(0.9)))
    }

    @Test fun textFallbackWithoutWordList() {
        assertTrue(detector(0.5f).onResult("""{"text":"jarvis"}"""))
    }
}

class AppMatcherTest {
    private val apps = listOf(
        InstalledApp("YouTube", "com.google.android.youtube"),
        InstalledApp("YouTube Music", "com.google.android.apps.youtube.music"),
        InstalledApp("Instagram", "com.instagram.android"),
        InstalledApp("Camera", "com.android.camera"),
        InstalledApp("Chrome", "com.android.chrome"),
        InstalledApp("Settings", "com.android.settings"),
    )

    @Test fun exactAndCaseInsensitive() {
        assertEquals("com.google.android.youtube", AppMatcher.bestMatch("youtube", apps)?.packageName)
        assertEquals("com.google.android.apps.youtube.music", AppMatcher.bestMatch("YouTube music", apps)?.packageName)
    }

    @Test fun aliasesAndSuffix() {
        assertEquals("com.instagram.android", AppMatcher.bestMatch("insta", apps)?.packageName)
        assertEquals("com.android.chrome", AppMatcher.bestMatch("browser", apps)?.packageName)
        assertEquals("com.android.camera", AppMatcher.bestMatch("camera app", apps)?.packageName)
    }

    @Test fun unknownAppIsNull() {
        assertNull(AppMatcher.bestMatch("spotify", apps))
    }
}

class PhrasesTest {
    @Test fun formatsTimeAndDuration() {
        assertEquals("6:30 PM", Phrases.formatTime(18, 30))
        assertEquals("12 AM", Phrases.formatTime(0, 0))
        assertEquals("12 PM", Phrases.formatTime(12, 0))
        assertEquals("5 minutes", Phrases.formatDuration(300))
        assertEquals("1 hour 1 minute 5 seconds", Phrases.formatDuration(3665))
    }

    @Test fun whichOneListsNames() {
        assertEquals("I found A, B and C. Which one?", Phrases(hindi = false).whichOne(listOf("A", "B", "C")))
        assertEquals("Calling King", Phrases(hindi = false).calling("King"))
    }
}
