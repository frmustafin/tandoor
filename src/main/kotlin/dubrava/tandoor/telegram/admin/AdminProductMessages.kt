package dubrava.tandoor.telegram.admin

import dubrava.tandoor.core.catalogue.CatalogueTexts
import dubrava.tandoor.core.product.Product
import dubrava.tandoor.core.product.ProductService
import dubrava.tandoor.telegram.api.InlineKeyboardButton
import dubrava.tandoor.telegram.api.InlineKeyboardMarkup
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.SendPhotoRequest
import dubrava.tandoor.telegram.api.TelegramApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** The admin's view of products: the ordered list and an editing card. Callback data: `padmin:<action>[:<id>]`. */
@Component
class AdminProductMessages(private val api: TelegramApi, private val products: ProductService) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun sendList(chatId: Long): Message {
        val all = products.all()
        val rows = all.map { product ->
            val label = (if (product.isActive) "" else "🚫 ") + product.name
            listOf(InlineKeyboardButton(label, callbackData = "$OPEN:${product.id}"))
        } + listOf(listOf(InlineKeyboardButton("➕ Добавить позицию", callbackData = ADD)))
        val text = if (all.isEmpty()) "Позиций нет." else "Позиции в порядке кнопок пекаря. 🚫 — скрытые."
        return api.sendMessage(SendMessageRequest(chatId = chatId, text = text, replyMarkup = InlineKeyboardMarkup(rows)))
    }

    /** A photo message when there is a photo, so the admin sees what customers see. */
    fun sendCard(chatId: Long, product: Product, note: String? = null): Message {
        val all = products.all()
        val position = all.indexOfFirst { it.id == product.id } + 1
        val text = listOfNotNull(
            product.name,
            "Цена: ${CatalogueTexts.price(product.price) ?: "не указана"}",
            "Описание: ${product.description ?: "нет"}",
            "Фото: ${if (product.photoFileId != null) "есть" else "нет"}",
            "Статус: ${if (product.isActive) "показывается" else "скрыта"}",
            "Порядок: $position из ${all.size}",
            note,
        ).joinToString("\n")
        val keyboard = cardKeyboard(product)
        product.photoFileId?.let { fileId ->
            try {
                return api.sendPhoto(SendPhotoRequest(chatId = chatId, photo = fileId, caption = text, replyMarkup = keyboard))
            } catch (e: Exception) {
                log.warn("admin photo card for product {} failed, sending text: {}", product.id, e.message)
            }
        }
        return api.sendMessage(SendMessageRequest(chatId = chatId, text = text, replyMarkup = keyboard))
    }

    /** A separate question before anything is removed; one accidental tap must not be enough. */
    fun sendDeleteConfirmation(chatId: Long, product: Product): Message {
        val text = "Удалить «${product.name}»?\n" +
            "Если у позиции были партии, она исчезнет из списков, а история и статистика останутся."
        val keyboard = InlineKeyboardMarkup(
            listOf(listOf(button("Да, удалить", "$DELETE_YES:${product.id}"), button("Нет", "$DELETE_NO:${product.id}")))
        )
        return api.sendMessage(SendMessageRequest(chatId = chatId, text = text, replyMarkup = keyboard))
    }

    private fun cardKeyboard(product: Product): InlineKeyboardMarkup {
        val id = product.id!!
        return InlineKeyboardMarkup(
            listOf(
                listOf(button("✏️ Название", "$NAME:$id"), button("✏️ Описание", "$DESCRIPTION:$id")),
                listOf(button("💰 Цена", "$PRICE:$id"), button("🖼 Фото", "$PHOTO:$id")),
                listOf(button("⬆️ Выше", "$UP:$id"), button("⬇️ Ниже", "$DOWN:$id")),
                listOf(button(if (product.isActive) "🙈 Скрыть" else "👁 Показать", "$TOGGLE:$id"), button("🗑 Удалить", "$DELETE:$id")),
                listOf(button("← К списку", LIST)),
            )
        )
    }

    private fun button(text: String, data: String) = InlineKeyboardButton(text = text, callbackData = data)

    companion object {
        const val PREFIX = "padmin"
        const val LIST = "$PREFIX:list"
        const val ADD = "$PREFIX:add"
        const val OPEN = "$PREFIX:open"
        const val NAME = "$PREFIX:name"
        const val DESCRIPTION = "$PREFIX:desc"
        const val PRICE = "$PREFIX:price"
        const val PHOTO = "$PREFIX:photo"
        const val UP = "$PREFIX:up"
        const val DOWN = "$PREFIX:down"
        const val TOGGLE = "$PREFIX:toggle"
        const val DELETE = "$PREFIX:delete"
        const val DELETE_YES = "$PREFIX:delete-yes"
        const val DELETE_NO = "$PREFIX:delete-no"
        const val KEYBOARD_NOTE = "Клавиатура пекаря обновится после /start."
    }
}
