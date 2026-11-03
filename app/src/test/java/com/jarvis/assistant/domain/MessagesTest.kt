package com.jarvis.assistant.domain

import com.jarvis.assistant.domain.messages.IncomingMessage
import com.jarvis.assistant.domain.messages.MessageSelector
import com.jarvis.assistant.domain.model.AssistantCommand
import com.jarvis.assistant.domain.model.CommandParser
import com.jarvis.assistant.domain.model.needsConfirmation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesTest {
    private fun msg(
        key: String,
        sender: String,
        app: String = "WhatsApp",
        pkg: String = "com.whatsapp",
        at: Long,
        canReply: Boolean = true,
        conversation: Boolean = true,
    ) = IncomingMessage(key, pkg, app, sender, "hello from $sender", at, canReply, conversation)

    private val king = msg("k", "King", at = 300)
    private val mom = msg("m", "Mom", app = "Messages", pkg = "com.google.android.apps.messaging", at = 200)
    private val promo = msg("p", "Big Sale", app = "Shop", pkg = "com.shop", at = 400, canReply = false, conversation = false)
    private val group = msg("g", "Ravi in Family", at = 100)
    private val all = listOf(king, mom, promo, group)

    @Test fun conversationsComeFirstNewestFirst() {
        assertEquals(listOf(king, mom, group, promo), MessageSelector.select(all, "", ""))
    }

    @Test fun filtersByAppAndSender() {
        assertEquals(listOf(king, group), MessageSelector.select(all, "whatsapp", ""))
        assertEquals(listOf(mom), MessageSelector.select(all, "sms", ""))
        assertEquals(listOf(king), MessageSelector.select(all, "", "king"))
        assertEquals(listOf(group), MessageSelector.select(all, "", "ravi"))
        assertTrue(MessageSelector.select(all, "", "zebra").isEmpty())
    }

    @Test fun filtersByGroupName() {
        val family = group.copy(key = "f", conversation = "Family", transcript = listOf("Ravi: dinner at 8", "Mom: bring milk"))
        assertEquals(listOf(family), MessageSelector.select(listOf(king, mom, family), "", "family"))
    }

    @Test fun limitsCount() {
        assertEquals(2, MessageSelector.select(all, "", "", limit = 2).size)
    }

    @Test fun replyTargetPrefersNamedThenLastReadThenNewest() {
        assertEquals(mom, MessageSelector.replyTarget(all, "mom", lastRead = king))
        assertEquals(group, MessageSelector.replyTarget(all, "", lastRead = group))
        assertEquals(king, MessageSelector.replyTarget(all, "", lastRead = null))
        assertNull(MessageSelector.replyTarget(all, "big sale", lastRead = null))
    }

    @Test fun readMessagesPhrase() {
        val p = Phrases(hindi = false)
        assertEquals("King on WhatsApp: hello from King.", p.readMessages(listOf(king)))
        assertEquals(
            "2 messages. King on WhatsApp: hello from King. Mom on Messages: hello from Mom.",
            p.readMessages(listOf(king, mom)),
        )
    }

    @Test fun parsesMessageTools() {
        fun parse(tool: String, args: String) = CommandParser.parse(tool, Json.parseToJsonElement(args).jsonObject)
        assertEquals(
            AssistantCommand.ReadNotifications("WhatsApp", ""),
            parse("read_notifications", """{"app_name":"WhatsApp","contact_name":""}"""),
        )
        assertEquals(AssistantCommand.ReadNotifications("", ""), parse("read_notifications", "{}"))
        assertEquals(
            AssistantCommand.ReplyMessage("", "on my way"),
            parse("reply_message", """{"contact_name":"","message":"on my way"}"""),
        )
        assertEquals(AssistantCommand.Answer(CommandParser.FALLBACK), parse("reply_message", """{"message":" "}"""))
    }

    @Test fun replyNeedsConfirmationButReadingDoesNot() {
        assertTrue(AssistantCommand.ReplyMessage("", "hi").needsConfirmation)
        assertTrue(!AssistantCommand.ReadNotifications("", "").needsConfirmation)
    }
}

class SmartComposeTest {
    private fun parse(tool: String, args: String) = CommandParser.parse(tool, Json.parseToJsonElement(args).jsonObject)

    @Test fun composedFlagIsParsed() {
        assertEquals(
            AssistantCommand.SendWhatsApp("Mom", "मैं देर से आऊँगा", composed = true),
            parse("send_whatsapp", """{"contact_name":"Mom","message":"मैं देर से आऊँगा","composed":true}"""),
        )
        assertEquals(AssistantCommand.SendSms("Mom", "hi"), parse("send_sms", """{"contact_name":"Mom","message":"hi"}"""))
        assertEquals(
            AssistantCommand.ReplyMessage("", "ok", composed = true),
            parse("reply_message", """{"contact_name":"","message":"ok","composed":"true"}"""),
        )
    }

    @Test fun yesNo() {
        listOf("yes", "Yeah send it", "ok", "go ahead", "haan bhejo", "हाँ", "Jarvis, send").forEach {
            assertEquals(it, YesNo.YES, YesNoParser.parse(it))
        }
        listOf("no", "don't send it", "cancel", "nahi", "wait", "नहीं").forEach {
            assertEquals(it, YesNo.NO, YesNoParser.parse(it))
        }
        val p = Phrases(hindi = false)
        assertEquals("Here's your reply: Are you free? Shall I send it?", p.draftReadback(null, "Are you free?"))
        assertEquals("Here's your message to Mom: Coming home. Shall I send it?", p.draftReadback("Mom", "Coming home"))
        listOf("", "hmm", "what").forEach { assertEquals(it, YesNo.UNCLEAR, YesNoParser.parse(it)) }
    }
}
