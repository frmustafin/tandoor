package dubrava.tandoor.telegram

import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.telegram.api.AnswerCallbackQueryRequest
import dubrava.tandoor.telegram.api.BotCommand
import dubrava.tandoor.telegram.api.CallbackQuery
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * The command menus. Customers see the public three everywhere; a staff member's own chat
 * gets a longer list when they `/start`. This is convenience, not protection: the router
 * refuses a command regardless of whether it was in the menu.
 */
object BotCommands {
    val CUSTOMER = listOf(
        BotCommand("start", "Начать"),
        BotCommand("catalog", "Продукция"),
        BotCommand("subscriptions", "Мои подписки"),
    )
    private val BAKER = CUSTOMER + BotCommand("id", "Мой Telegram ID")
    private val ADMIN = BAKER + listOf(
        BotCommand("products", "Позиции"),
        BotCommand("staff", "Сотрудники"),
        BotCommand("settings", "Настройки"),
        BotCommand("stats", "Статистика"),
    )

    fun forRole(role: Role): List<BotCommand> = when (role) {
        Role.BAKER -> BAKER
        Role.ADMIN -> ADMIN
    }
}

/** The polite refusal the router sends when a non-staff user reaches a staff entry point. */
@Component
class TelegramAccessDenied(private val api: TelegramApi) : AccessDenied {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun command(message: Message) {
        runCatching { api.sendMessage(SendMessageRequest(chatId = message.chat.id, text = STAFF_ONLY)) }
            .onFailure { log.warn("refusal failed: {}", it.message) }
    }

    override fun callback(query: CallbackQuery) {
        runCatching { api.answerCallbackQuery(AnswerCallbackQueryRequest(callbackQueryId = query.id, text = STAFF_ONLY)) }
            .onFailure { log.warn("refusal failed: {}", it.message) }
    }

    companion object {
        const val STAFF_ONLY = "Это доступно только сотрудникам пекарни."
    }
}
