/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.transformMessages
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ProactiveMessageServiceTest {

    private fun text(message: UIMessage): String = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("") { it.text }

    private fun allText(messages: List<UIMessage>): String = messages.joinToString("\n") { text(it) }

    private fun constantEntry(
        content: String,
        position: InjectionPosition,
        injectDepth: Int = 4,
    ) = PromptInjection.RegexInjection(
        name = content,
        content = content,
        position = position,
        injectDepth = injectDepth,
        constantActive = true,
    )

    @Test
    fun `every injection position keeps the programmatic wakeup with no history`() {
        InjectionPosition.entries.forEach { position ->
            val lorebookId = Uuid.random()
            val content = "injection-$position"
            val result = transformMessages(
                messages = buildProactiveInputMessages(
                    systemPrompt = "system",
                    historyMessages = emptyList(),
                    wakeupInstruction = "wake instruction",
                ),
                assistant = Assistant(lorebookIds = setOf(lorebookId)),
                modeInjections = emptyList(),
                lorebooks = listOf(
                    Lorebook(
                        id = lorebookId,
                        entries = listOf(constantEntry(content, position)),
                    )
                ),
            )

            val resultText = allText(result)
            assertTrue("missing marker for $position", resultText.contains(PROGRAMMATIC_WAKEUP_MARKER))
            assertTrue("missing wakeup instruction for $position", resultText.contains("wake instruction"))
            assertTrue("missing injection for $position", resultText.contains(content))
        }
    }

    @Test
    fun `multiple lorebooks match the full proactive context`() {
        val historyLorebookId = Uuid.random()
        val wakeupLorebookId = Uuid.random()
        val historyEntry = PromptInjection.RegexInjection(
            name = "history entry",
            content = "history lorebook content",
            position = InjectionPosition.AFTER_SYSTEM_PROMPT,
            keywords = listOf("history-keyword"),
            scanDepth = 3,
        )
        val wakeupEntry = PromptInjection.RegexInjection(
            name = "wakeup entry",
            content = "wakeup lorebook content",
            position = InjectionPosition.BOTTOM_OF_CHAT,
            keywords = listOf("wakeup-keyword"),
            scanDepth = 1,
        )

        val result = transformMessages(
            messages = buildProactiveInputMessages(
                systemPrompt = "system",
                historyMessages = listOf(
                    UIMessage.user("history-keyword"),
                    UIMessage.assistant("ordinary reply"),
                ),
                wakeupInstruction = "wakeup-keyword",
            ),
            assistant = Assistant(lorebookIds = setOf(historyLorebookId, wakeupLorebookId)),
            modeInjections = emptyList(),
            lorebooks = listOf(
                Lorebook(id = historyLorebookId, entries = listOf(historyEntry)),
                Lorebook(id = wakeupLorebookId, entries = listOf(wakeupEntry)),
            ),
        )

        val resultText = allText(result)
        assertTrue(resultText.contains("history lorebook content"))
        assertTrue(resultText.contains("wakeup lorebook content"))
        assertTrue(resultText.contains(PROGRAMMATIC_WAKEUP_MARKER))
        assertTrue(resultText.contains("wakeup-keyword"))
        assertEquals(5, result.size)
    }

    @Test
    fun `ordinary chat history remains alongside the wakeup instruction`() {
        val history = listOf(
            UIMessage.user("normal chat question"),
            UIMessage.assistant("normal chat answer"),
        )

        val result = transformMessages(
            messages = buildProactiveInputMessages(
                systemPrompt = "system",
                historyMessages = history,
                wakeupInstruction = "decide whether to send",
            ),
            assistant = Assistant(),
            modeInjections = emptyList(),
            lorebooks = emptyList(),
        )

        assertEquals(4, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertEquals("normal chat question", text(result[1]))
        assertEquals("normal chat answer", text(result[2]))
        assertTrue(text(result[3]).contains(PROGRAMMATIC_WAKEUP_MARKER))
        assertTrue(text(result[3]).contains("decide whether to send"))
    }
}
