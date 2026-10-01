package dubrava.tandoor.telegram

import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.telegram.api.BotCommandScope
import dubrava.tandoor.telegram.api.ReplyKeyboardRemove
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import dubrava.tandoor.telegram.api.TgUser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** What a staff member's chat looks like: the baker keyboard and the staff command menu, given and taken away. */
@Component
class StaffOnboarding(private val api: TelegramApi, private val keyboard: BakerKeyboard) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun welcome(chatId: Long, role: Role) {
        val markup = keyboard.build()
        val text = if (markup == null) NO_PRODUCTS else BAKER_GREETING
        api.sendMessage(SendMessageRequest(chatId = chatId, text = text, replyMarkup = markup))
        runCatching { api.setMyCommands(BotCommands.forRole(role), BotCommandScope.chat(chatId)) }
            .onFailure { log.warn("staff command menu for chat {} failed: {}", chatId, it.message) }
    }

    /** Best effort: the person may have blocked the bot; the role is gone either way. */
    fun farewell(chatId: Long) {
        runCatching { api.sendMessage(SendMessageRequest(chatId = chatId, text = REVOKED, replyMarkup = ReplyKeyboardRemove())) }
            .onFailure { log.warn("farewell to chat {} failed: {}", chatId, it.message) }
        runCatching { api.deleteMyCommands(BotCommandScope.chat(chatId)) }
            .onFailure { log.warn("resetting the command menu for chat {} failed: {}", chatId, it.message) }
    }

    companion object {
        // Placeholder wording; the final texts are agreed with the baker (plan, section 5).
        const val BAKER_GREETING = "Клавиатура пекаря включена.\n\n" +
            "Поставили партию в печь — нажмите её кнопку: пост в канал уходит сразу, " +
            "с готовностью через 15 минут. В ответ бот пришлёт карточку партии: " +
            "её кнопки правят этот пост — время, «Готово», «Закончилось», «Отмена».\n\n" +
            "Личные уведомления о партиях, как у покупателей: /subscriptions"

        const val NO_PRODUCTS = "Активных позиций нет — добавьте их в настройках позиций."
        const val REVOKED = "Ваша роль в боте пекарни отозвана. Как покупатель вы по-прежнему можете пользоваться ботом: /start"

        fun roleName(role: Role) = when (role) {
            Role.BAKER -> "пекарь"
            Role.ADMIN -> "админ"
        }

        /** First and last name from Telegram, else the username, else the id: enough to tell people apart. */
        fun displayName(user: TgUser?): String {
            if (user == null) return "сотрудник"
            val name = listOfNotNull(user.firstName, user.lastName).joinToString(" ").trim()
            return name.ifEmpty { user.username?.let { "@$it" } ?: "сотрудник ${user.id}" }
        }
    }
}
