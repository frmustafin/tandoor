package dubrava.tandoor.telegram.admin

import dubrava.tandoor.core.settings.SettingsService
import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.telegram.CallbackHandler
import dubrava.tandoor.telegram.CommandHandler
import dubrava.tandoor.telegram.admin.PendingInputs.Field
import dubrava.tandoor.telegram.api.AnswerCallbackQueryRequest
import dubrava.tandoor.telegram.api.CallbackQuery
import dubrava.tandoor.telegram.api.EditMessageTextRequest
import dubrava.tandoor.telegram.api.InlineKeyboardButton
import dubrava.tandoor.telegram.api.InlineKeyboardMarkup
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import dubrava.tandoor.telegram.api.TelegramApiException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * `/settings`: the two timers and the channel auto-post switch (plan 4.6). Timers are typed
 * as minutes after a tap (handled by [AdminInputHandler]); the switch flips in place.
 * Admins only, by the router.
 */
@Component
class AdminSettingsHandler(
    private val api: TelegramApi,
    private val settings: SettingsService,
    private val inputs: PendingInputs,
) : CommandHandler, CallbackHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override val command = "settings"

    override val prefix = PREFIX

    override val requiredRole = Role.ADMIN

    override fun handle(message: Message, args: String) {
        send(message.chat.id)
    }

    override fun handle(query: CallbackQuery, payload: String) {
        val adminId = query.from.id
        val chatId = query.message?.chat?.id ?: adminId
        val reply: String? = when (payload) {
            "ready" -> ask(adminId, chatId, Field.READY_AFTER_MINUTES, "Через сколько минут после нажатия партия считается готовой? Сейчас: ${settings.readyAfter().toMinutes()}.")
            "hot" -> ask(adminId, chatId, Field.HOT_FOR_MINUTES, "Сколько минут партия считается горячей после готовности? Сейчас: ${settings.hotFor().toMinutes()}.")
            "autoposts" -> {
                val on = !settings.channelAutoPosts()
                settings.set(SettingsService.CHANNEL_AUTO_POSTS, on.toString())
                query.message?.let { redraw(it.chat.id, it.messageId) }
                if (on) "Автопосты в канал включены" else "Автопосты в канал выключены"
            }
            else -> { log.warn("unknown settings action {}", payload); null }
        }
        runCatching { api.answerCallbackQuery(AnswerCallbackQueryRequest(callbackQueryId = query.id, text = reply)) }
            .onFailure { log.warn("answerCallbackQuery failed: {}", it.message) }
    }

    fun send(chatId: Long): Message = api.sendMessage(SendMessageRequest(chatId = chatId, text = text(), replyMarkup = keyboard()))

    private fun redraw(chatId: Long, messageId: Long) {
        try {
            api.editMessageText(EditMessageTextRequest(chatId = chatId, messageId = messageId, text = text(), replyMarkup = keyboard()))
        } catch (e: TelegramApiException) {
            if (!e.isNotModified) log.warn("redrawing settings failed: {}", e.message)
        }
    }

    private fun text(): String {
        val autoposts = if (settings.channelAutoPosts()) "включены" else "выключены"
        return "Настройки\n\n" +
            "⏱ Время до готовности: ${settings.readyAfter().toMinutes()} мин (по умолчанию для новой партии)\n" +
            "🔥 Время жизни «горячего»: ${settings.hotFor().toMinutes()} мин\n" +
            "📣 Автопосты в канал: $autoposts"
    }

    private fun keyboard() = InlineKeyboardMarkup(
        listOf(
            listOf(InlineKeyboardButton("⏱ Время до готовности", callbackData = "$PREFIX:ready")),
            listOf(InlineKeyboardButton("🔥 Время «горячего»", callbackData = "$PREFIX:hot")),
            listOf(InlineKeyboardButton(if (settings.channelAutoPosts()) "📣 Выключить автопосты" else "📣 Включить автопосты", callbackData = "$PREFIX:autoposts")),
        )
    )

    private fun ask(adminId: Long, chatId: Long, field: Field, prompt: String): String? {
        inputs.expect(adminId, null, field)
        api.sendMessage(SendMessageRequest(chatId = chatId, text = "$prompt\nОтправьте число от $MIN_MINUTES до $MAX_MINUTES. Или напишите «отмена»."))
        return null
    }

    companion object {
        const val PREFIX = "settings"
        const val MIN_MINUTES = 1L
        const val MAX_MINUTES = 180L
    }
}
