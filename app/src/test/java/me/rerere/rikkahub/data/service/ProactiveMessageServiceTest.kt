/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.service

import android.content.ContextWrapper
import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import kotlinx.coroutines.runBlocking
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.PromptInjectionTransformer
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.ai.transformers.TimeReminderTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Reader
import java.io.StringReader
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

    private val templateTransformer: TemplateTransformer by lazy {
        val templateText = "[{{ role }}] {{ message }}"
        val loader = object : Loader<String> {
            override fun getReader(cacheKey: String?): Reader = StringReader(templateText)

            override fun setCharset(charset: String?) = Unit

            override fun setPrefix(prefix: String?) = Unit

            override fun setSuffix(suffix: String?) = Unit

            override fun resolveRelativePath(relativePath: String?, anchorPath: String?): String? = relativePath

            override fun createCacheKey(templateName: String?): String? = templateName

            override fun resourceExists(templateName: String?): Boolean = true
        }
        TemplateTransformer(
            PebbleEngine.Builder()
                .loader(loader)
                .autoEscaping(false)
                .build()
        )
    }

    private fun runProactivePipeline(
        assistant: Assistant,
        historyMessages: List<UIMessage> = emptyList(),
        lorebooks: List<Lorebook> = emptyList(),
        wakeupInstruction: String = "wake instruction",
        extraTransformers: List<InputMessageTransformer> = emptyList(),
    ): List<UIMessage> = runBlocking {
        val settings = Settings(
            assistants = listOf(assistant),
            lorebooks = lorebooks,
        )
        buildProactiveModelMessages(
            messages = buildProactiveInputMessages(
                systemPrompt = "system prompt",
                historyMessages = historyMessages,
                wakeupInstruction = wakeupInstruction,
            ),
            transformers = listOf(
                TimeReminderTransformer,
                PromptInjectionTransformer,
                *extraTransformers.toTypedArray(),
                templateTransformer,
            ),
            context = ContextWrapper(null),
            model = Model(modelId = "test-model", displayName = "Test model"),
            assistant = assistant,
            settings = settings,
        )
    }

    @Test
    fun `complete pipeline keeps the wakeup and every injection position`() {
        InjectionPosition.entries.forEach { position ->
            val lorebookId = Uuid.random()
            val content = "injection-$position"
            val result = runProactivePipeline(
                assistant = Assistant(
                    enableTimeReminder = true,
                    lorebookIds = setOf(lorebookId),
                ),
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
            assertTrue("time reminder was not sent through the pipeline", resultText.contains("<time_reminder>"))
            assertTrue("template was not applied", resultText.contains("[user]"))
            assertTrue(
                "final model input contains adjacent roles for $position",
                result.zipWithNext().none { (left, right) -> left.role == right.role },
            )
        }
    }

    @Test
    fun `multiple lorebooks match history and wakeup in the final model input`() {
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

        val result = runProactivePipeline(
            assistant = Assistant(lorebookIds = setOf(historyLorebookId, wakeupLorebookId)),
            historyMessages = listOf(
                UIMessage.user("history-keyword"),
                UIMessage.assistant("ordinary reply"),
            ),
            lorebooks = listOf(
                Lorebook(id = historyLorebookId, entries = listOf(historyEntry)),
                Lorebook(id = wakeupLorebookId, entries = listOf(wakeupEntry)),
            ),
            wakeupInstruction = "wakeup-keyword",
        )

        val resultText = allText(result)
        assertTrue(resultText.contains("history lorebook content"))
        assertTrue(resultText.contains("wakeup lorebook content"))
        assertTrue(resultText.contains(PROGRAMMATIC_WAKEUP_MARKER))
        assertTrue(resultText.contains("wakeup-keyword"))
        assertTrue(result.all { message -> message.parts.filterIsInstance<UIMessagePart.Text>().isNotEmpty() })
        assertTrue(result.zipWithNext().none { (left, right) -> left.role == right.role })
    }

    @Test
    fun `ordinary history remains in the final input beside the synthetic wakeup`() {
        val result = runProactivePipeline(
            assistant = Assistant(),
            historyMessages = listOf(
                UIMessage.user("normal chat question"),
                UIMessage.assistant("normal chat answer"),
            ),
            wakeupInstruction = "decide whether to send",
        )

        assertEquals(4, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertTrue(text(result[0]).contains("[system]"))
        assertTrue(text(result[1]).contains("normal chat question"))
        assertTrue(text(result[2]).contains("normal chat answer"))
        assertTrue(text(result[3]).contains(PROGRAMMATIC_WAKEUP_MARKER))
        assertTrue(text(result[3]).contains("decide whether to send"))
    }

    @Test
    fun `adjacent user history and wakeup are merged without losing either`() {
        val result = runProactivePipeline(
            assistant = Assistant(),
            historyMessages = listOf(UIMessage.user("last ordinary user message")),
            wakeupInstruction = "complete wakeup command",
        )

        assertEquals(2, result.size)
        assertEquals(MessageRole.SYSTEM, result[0].role)
        assertEquals(MessageRole.USER, result[1].role)
        assertTrue(text(result[1]).contains("last ordinary user message"))
        assertTrue(text(result[1]).contains(PROGRAMMATIC_WAKEUP_MARKER))
        assertTrue(text(result[1]).contains("complete wakeup command"))
    }
}
