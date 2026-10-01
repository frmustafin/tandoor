package dubrava.tandoor.telegram.handler

import dubrava.tandoor.core.product.ProductService
import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.core.subscription.SubscriptionService
import dubrava.tandoor.core.user.Source
import dubrava.tandoor.core.user.UserService
import dubrava.tandoor.telegram.CallbackHandler
import dubrava.tandoor.telegram.CatalogueMessages
import dubrava.tandoor.telegram.CommandHandler
import dubrava.tandoor.telegram.CustomerMenu
import dubrava.tandoor.telegram.TextMessageHandler
import dubrava.tandoor.telegram.api.AnswerCallbackQueryRequest
import dubrava.tandoor.telegram.api.CallbackQuery
import dubrava.tandoor.telegram.api.DeleteMessageRequest
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.TelegramApi
import org.slf4j.LoggerFactory
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/** "Продукция" from the menu, `/catalog` for anyone (staff have no customer menu), and the card buttons. */
@Component
@Order(3)
class CatalogueHandler(
    private val api: TelegramApi,
    private val catalogue: CatalogueMessages,
    private val products: ProductService,
    private val users: UserService,
    private val subscriptions: SubscriptionService,
) : CommandHandler, TextMessageHandler, CallbackHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override val command = "catalog"

    override val prefix = CatalogueMessages.PREFIX

    /** Public on all three entry points; three interfaces each carry a default, so Kotlin wants it spelled out. */
    override val requiredRole: Role? = null

    override fun handle(message: Message, args: String) {
        catalogue.sendList(message.chat.id)
    }

    override fun handle(message: Message): Boolean {
        if (message.text?.trim() != CustomerMenu.CATALOGUE) return false
        catalogue.sendList(message.chat.id)
        return true
    }

    override fun handle(query: CallbackQuery, payload: String) {
        val userId = query.from.id
        val chatId = query.message?.chat?.id ?: userId
        val action = payload.substringBefore(':')
        val productId = payload.substringAfter(':', "").toLongOrNull()
        val reply: String? = when (action) {
            "open" -> productId?.let(products::find)?.takeIf { it.isActive }
                ?.let { catalogue.sendCard(chatId, userId, it); null }
                ?: "Позиция больше не продаётся"
            "list" -> {
                // The card is a dead end once the list is back; drop it to keep the chat tidy.
                query.message?.let { card ->
                    runCatching { api.deleteMessage(DeleteMessageRequest(chatId = card.chat.id, messageId = card.messageId)) }
                }
                catalogue.sendList(chatId)
                null
            }
            "sub" -> productId?.let(products::find)?.let { product ->
                users.touch(userId, Source.DIRECT)
                val subscribed = subscriptions.toggle(userId, product.id!!)
                query.message?.let { card ->
                    runCatching { catalogue.redrawCardButtons(card.chat.id, card.messageId, product, subscribed) }
                        .onFailure { log.warn("redrawing card buttons failed: {}", it.message) }
                }
                if (subscribed) "Подписка включена: ${product.name}" else "Подписка выключена: ${product.name}"
            }
            else -> null
        }
        runCatching { api.answerCallbackQuery(AnswerCallbackQueryRequest(callbackQueryId = query.id, text = reply)) }
            .onFailure { log.warn("answerCallbackQuery failed: {}", it.message) }
    }
}
