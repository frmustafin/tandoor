package dubrava.tandoor.telegram.handler

import dubrava.tandoor.core.user.Source
import dubrava.tandoor.core.user.UserService
import dubrava.tandoor.telegram.CommandHandler
import dubrava.tandoor.telegram.SubscriptionMessages
import dubrava.tandoor.telegram.api.Message
import org.springframework.stereotype.Component

/** `/subscriptions`: the product checkboxes for anyone, staff included, and for customers who lost the menu. */
@Component
class SubscriptionsCommandHandler(
    private val users: UserService,
    private val messages: SubscriptionMessages,
) : CommandHandler {

    override val command = "subscriptions"

    override fun handle(message: Message, args: String) {
        val userId = message.from?.id ?: message.chat.id
        users.touch(userId, Source.DIRECT)
        messages.send(message.chat.id, userId)
    }
}
