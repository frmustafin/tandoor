package dubrava.tandoor.telegram.handler

import dubrava.tandoor.telegram.CommandHandler
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import org.springframework.stereotype.Component

/** `/id` tells a person their own Telegram ID, which is what BOOTSTRAP_ADMINS needs. */
@Component
class IdHandler(private val api: TelegramApi) : CommandHandler {

    override val command = "id"

    override fun handle(message: Message, args: String) {
        val id = message.from?.id ?: message.chat.id
        api.sendMessage(SendMessageRequest(chatId = message.chat.id, text = "Ваш Telegram ID: $id"))
    }
}
