/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.ai.transformers

import io.pebbletemplates.pebble.PebbleEngine
import io.pebbletemplates.pebble.loader.Loader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.utils.toLocalDate
import me.rerere.rikkahub.utils.toLocalTime
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.time.Instant

class TemplateTransformer(
    private val engine: PebbleEngine,
) : InputMessageTransformer {
    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        val template = engine.getTemplate(ctx.assistant.id.toString())
        return transformMessageTemplates(messages) { message, text ->
            val result = StringWriter()
            template.evaluate(
                result, mapOf(
                    "message" to text,
                    "role" to message.role.name.lowercase(),
                    "time" to Instant.now().toLocalTime(),
                    "date" to Instant.now().toLocalDate(),
                )
            )
            result.toString()
        }
    }
}

internal fun transformMessageTemplates(
    messages: List<UIMessage>,
    renderText: (message: UIMessage, text: String) -> String,
): List<UIMessage> = messages.map { message ->
    message.copy(
        parts = message.parts.map { part ->
            when (part) {
                is UIMessagePart.Text -> part.copy(text = renderText(message, part.text))
                else -> part
            }
        }
    )
}

class AssistantTemplateLoader(private val settingsStore: SettingsStore) : Loader<String> {
    override fun getReader(cacheKey: String?): Reader? {
        val content = settingsStore.settingsFlow.value.assistants
            .find { it.id.toString() == cacheKey }?.messageTemplate
            ?: return null
        return StringReader(content)
    }

    override fun setCharset(charset: String?) {}

    override fun setPrefix(prefix: String?) {}

    override fun setSuffix(suffix: String?) {}

    override fun resolveRelativePath(
        relativePath: String?,
        anchorPath: String?
    ): String? {
        return relativePath
    }

    override fun createCacheKey(templateName: String?): String? {
        return templateName
    }

    override fun resourceExists(templateName: String?): Boolean {
        return settingsStore.settingsFlow.value.assistants.any { it.id.toString() == templateName }
    }
}
