package dubrava.tandoor.telegram

import dubrava.tandoor.core.product.ProductRepository
import dubrava.tandoor.core.subscription.SubscriptionService
import dubrava.tandoor.telegram.api.EditMessageTextRequest
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import dubrava.tandoor.telegram.api.TelegramApiException
import org.springframework.stereotype.Component

/** The "which products to hear about" message, sent fresh or redrawn in place after a tap. */
@Component
class SubscriptionMessages(
    private val api: TelegramApi,
    private val products: ProductRepository,
    private val subscriptions: SubscriptionService,
) {

    fun send(chatId: Long, userId: Long, text: String = PROMPT) {
        api.sendMessage(SendMessageRequest(chatId = chatId, text = text, replyMarkup = keyboard(userId)))
    }

    fun redraw(chatId: Long, messageId: Long, userId: Long) {
        try {
            api.editMessageText(
                EditMessageTextRequest(chatId = chatId, messageId = messageId, text = PROMPT, replyMarkup = keyboard(userId))
            )
        } catch (e: TelegramApiException) {
            if (!e.isNotModified) throw e
        }
    }

    fun activeProductIds(): List<Long> = products.findAllByIsActiveTrueOrderBySortOrder().map { it.id!! }

    private fun keyboard(userId: Long) =
        SubscriptionKeyboard.build(products.findAllByIsActiveTrueOrderBySortOrder(), subscriptions.productIdsOf(userId))

    companion object {
        const val PROMPT = "О каких позициях сообщать? Нажмите, чтобы включить или выключить:"
        const val NOTHING_LEFT = "Вы отписаны от всех позиций. Снова подписаться можно здесь:"
    }
}
