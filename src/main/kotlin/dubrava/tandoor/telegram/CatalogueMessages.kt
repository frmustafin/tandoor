package dubrava.tandoor.telegram

import dubrava.tandoor.core.catalogue.CatalogueTexts
import dubrava.tandoor.core.catalogue.FreshnessService
import dubrava.tandoor.core.product.Product
import dubrava.tandoor.core.product.ProductService
import dubrava.tandoor.core.subscription.SubscriptionService
import dubrava.tandoor.telegram.api.EditMessageReplyMarkupRequest
import dubrava.tandoor.telegram.api.InlineKeyboardButton
import dubrava.tandoor.telegram.api.InlineKeyboardMarkup
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.SendPhotoRequest
import dubrava.tandoor.telegram.api.TelegramApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * The customer-facing catalogue: a list with freshness, and a card per product. A card is a
 * photo message when the product has a photo, so it cannot be edited into the text list;
 * navigation therefore sends fresh messages. Callback data: `product:open:<id>`,
 * `product:sub:<id>`, `product:list`.
 */
@Component
class CatalogueMessages(
    private val api: TelegramApi,
    private val products: ProductService,
    private val freshness: FreshnessService,
    private val texts: CatalogueTexts,
    private val subscriptions: SubscriptionService,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun sendList(chatId: Long): Message {
        val items = freshness.catalogue(products.active())
        if (items.isEmpty()) return api.sendMessage(SendMessageRequest(chatId = chatId, text = EMPTY))
        val now = clock.instant()
        val text = "Продукция:\n\n" + items.joinToString("\n") { (product, state) -> "• " + texts.listLine(product, state, now) }
        val keyboard = InlineKeyboardMarkup(
            items.map { (product, _) -> listOf(InlineKeyboardButton(product.name, callbackData = "$OPEN:${product.id}")) }
        )
        return api.sendMessage(SendMessageRequest(chatId = chatId, text = text, replyMarkup = keyboard))
    }

    /** Falls back to a text card if the stored photo cannot be sent (file ids die with a bot token). */
    fun sendCard(chatId: Long, userId: Long, product: Product): Message {
        val text = texts.card(product, freshness.of(product.id!!), clock.instant())
        val keyboard = cardKeyboard(product, product.id in subscriptions.productIdsOf(userId))
        product.photoFileId?.let { fileId ->
            try {
                return api.sendPhoto(SendPhotoRequest(chatId = chatId, photo = fileId, caption = text, replyMarkup = keyboard))
            } catch (e: Exception) {
                log.warn("photo card for product {} failed, sending text: {}", product.id, e.message)
            }
        }
        return api.sendMessage(SendMessageRequest(chatId = chatId, text = text, replyMarkup = keyboard))
    }

    fun redrawCardButtons(chatId: Long, messageId: Long, product: Product, subscribed: Boolean) {
        api.editMessageReplyMarkup(
            EditMessageReplyMarkupRequest(chatId = chatId, messageId = messageId, replyMarkup = cardKeyboard(product, subscribed))
        )
    }

    fun cardKeyboard(product: Product, subscribed: Boolean) = InlineKeyboardMarkup(
        listOf(
            listOf(InlineKeyboardButton(if (subscribed) "🔕 Отписаться" else "🔔 Подписаться", callbackData = "$SUB:${product.id}")),
            listOf(InlineKeyboardButton("← К списку", callbackData = LIST)),
        )
    )

    companion object {
        const val PREFIX = "product"
        const val OPEN = "$PREFIX:open"
        const val SUB = "$PREFIX:sub"
        const val LIST = "$PREFIX:list"
        const val EMPTY = "Позиций пока нет."
    }
}
