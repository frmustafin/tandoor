package dubrava.tandoor.telegram.handler

import dubrava.tandoor.core.subscription.SubscriptionService
import dubrava.tandoor.core.user.Source
import dubrava.tandoor.core.user.UserService
import dubrava.tandoor.telegram.CustomerMenu
import dubrava.tandoor.telegram.SubscriptionMessages
import dubrava.tandoor.telegram.TextMessageHandler
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** Taps on the customer menu buttons, which arrive as plain text equal to the label. */
@Component
@Order(2)
class CustomerMenuHandler(
    private val users: UserService,
    private val subscriptions: SubscriptionService,
    private val messages: SubscriptionMessages,
) : TextMessageHandler {

    override fun handle(message: dubrava.tandoor.telegram.api.Message): Boolean {
        val userId = message.from?.id ?: return false
        when (message.text?.trim()) {
            CustomerMenu.MY_SUBSCRIPTIONS -> {
                users.touch(userId, Source.DIRECT)
                messages.send(message.chat.id, userId)
            }
            CustomerMenu.UNSUBSCRIBE_ALL -> {
                users.touch(userId, Source.DIRECT)
                subscriptions.unsubscribeAll(userId)
                messages.send(message.chat.id, userId, text = SubscriptionMessages.NOTHING_LEFT)
            }
            else -> return false
        }
        return true
    }
}
