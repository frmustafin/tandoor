package dubrava.tandoor.telegram.handler

import dubrava.tandoor.core.product.ProductRepository
import dubrava.tandoor.core.subscription.SubscriptionService
import dubrava.tandoor.core.user.Source
import dubrava.tandoor.core.user.UserService
import dubrava.tandoor.telegram.CallbackHandler
import dubrava.tandoor.telegram.SubscriptionKeyboard
import dubrava.tandoor.telegram.SubscriptionMessages
import dubrava.tandoor.telegram.api.AnswerCallbackQueryRequest
import dubrava.tandoor.telegram.api.CallbackQuery
import dubrava.tandoor.telegram.api.TelegramApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** Checkbox taps: `sub:toggle:<productId>` flips one product, `sub:all` subscribes to everything. */
@Component
class SubscriptionCallbackHandler(
    private val api: TelegramApi,
    private val users: UserService,
    private val subscriptions: SubscriptionService,
    private val products: ProductRepository,
    private val messages: SubscriptionMessages,
) : CallbackHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override val prefix = SubscriptionKeyboard.PREFIX

    override fun handle(query: CallbackQuery, payload: String) {
        val userId = query.from.id
        // A tap on an old message after a database reset must not fail on a missing user row.
        users.touch(userId, Source.DIRECT)

        val reply = when (payload.substringBefore(':')) {
            "toggle" -> payload.substringAfter(':', "").toLongOrNull()?.let { productId ->
                val product = products.findById(productId).orElse(null)
                if (product == null) null
                else if (subscriptions.toggle(userId, productId)) "Подписка включена: ${product.name}"
                else "Подписка выключена: ${product.name}"
            }
            "all" -> {
                subscriptions.subscribeAll(userId, messages.activeProductIds())
                "Вы подписаны на все позиции"
            }
            else -> null
        }

        query.message?.let { message ->
            runCatching { messages.redraw(message.chat.id, message.messageId, userId) }
                .onFailure { log.warn("redrawing subscriptions failed: {}", it.message) }
        }
        runCatching { api.answerCallbackQuery(AnswerCallbackQueryRequest(callbackQueryId = query.id, text = reply)) }
            .onFailure { log.warn("answerCallbackQuery failed: {}", it.message) }
    }
}
