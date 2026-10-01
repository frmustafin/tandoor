package dubrava.tandoor.telegram.admin

import dubrava.tandoor.core.product.ProductService
import dubrava.tandoor.core.product.ProductService.Deleted
import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.telegram.CallbackHandler
import dubrava.tandoor.telegram.CommandHandler
import dubrava.tandoor.telegram.admin.PendingInputs.Field
import dubrava.tandoor.telegram.api.AnswerCallbackQueryRequest
import dubrava.tandoor.telegram.api.CallbackQuery
import dubrava.tandoor.telegram.api.EditMessageTextRequest
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** `/products`: the list, the card, and the buttons that start an edit (the reply is handled by [AdminInputHandler]). Admins only, by the router. */
@Component
class AdminProductsHandler(
    private val api: TelegramApi,
    private val products: ProductService,
    private val messages: AdminProductMessages,
    private val inputs: PendingInputs,
) : CommandHandler, CallbackHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override val command = "products"

    override val prefix = AdminProductMessages.PREFIX

    override val requiredRole = Role.ADMIN

    override fun handle(message: Message, args: String) {
        messages.sendList(message.chat.id)
    }

    override fun handle(query: CallbackQuery, payload: String) {
        val adminId = query.from.id
        val chatId = query.message?.chat?.id ?: adminId
        val action = payload.substringBefore(':')
        val id = payload.substringAfter(':', "").toLongOrNull()
        val product = id?.let(products::find)

        val reply: String? = when {
            action == "list" -> { messages.sendList(chatId); null }
            action == "add" -> ask(adminId, chatId, null, Field.NEW_NAME, "Отправьте название новой позиции.")
            product == null -> "Позиция не найдена"
            action == "open" -> { messages.sendCard(chatId, product); null }
            action == "name" -> ask(adminId, chatId, product.id, Field.NAME, "Отправьте новое название для «${product.name}».")
            action == "desc" -> ask(adminId, chatId, product.id, Field.DESCRIPTION, "Отправьте описание для «${product.name}». «-» — убрать описание.")
            action == "price" -> ask(adminId, chatId, product.id, Field.PRICE, "Отправьте цену для «${product.name}»: например 250 или 250,50. «-» — без цены.")
            action == "photo" -> ask(adminId, chatId, product.id, Field.PHOTO, "Пришлите фото для «${product.name}» одним сообщением (как фото, не как файл).")
            action == "up" || action == "down" -> {
                products.move(product.id!!, if (action == "up") -1 else 1)
                messages.sendList(chatId)
                AdminProductMessages.KEYBOARD_NOTE
            }
            action == "toggle" -> {
                val updated = products.setActive(product.id!!, !product.isActive)!!
                messages.sendCard(chatId, updated, note = AdminProductMessages.KEYBOARD_NOTE)
                if (updated.isActive) "Позиция показывается" else "Позиция скрыта"
            }
            action == "delete" -> { messages.sendDeleteConfirmation(chatId, product); null }
            action == "delete-no" -> { replaceQuestion(query, "Оставляю «${product.name}»."); null }
            action == "delete-yes" -> when (val deleted = products.delete(product.id!!)) {
                is Deleted.Hard -> finishDelete(query, chatId, "Удалено: «${deleted.product.name}».")
                is Deleted.Soft -> finishDelete(query, chatId, "Удалено: «${deleted.product.name}». Партии и статистика сохранены.")
                null -> "Позиция уже удалена"
            }
            else -> { log.warn("unknown admin action {}", action); null }
        }
        answer(query, reply)
    }

    private fun ask(adminId: Long, chatId: Long, productId: Long?, field: Field, prompt: String): String? {
        inputs.expect(adminId, productId, field)
        api.sendMessage(SendMessageRequest(chatId = chatId, text = "$prompt\nИли напишите «отмена»."))
        return null
    }

    /** Replaces the question with the outcome, then shows the list without the product. */
    private fun finishDelete(query: CallbackQuery, chatId: Long, outcome: String): String {
        replaceQuestion(query, outcome)
        messages.sendList(chatId)
        return AdminProductMessages.KEYBOARD_NOTE
    }

    private fun replaceQuestion(query: CallbackQuery, text: String) {
        val message = query.message ?: return
        runCatching { api.editMessageText(EditMessageTextRequest(chatId = message.chat.id, messageId = message.messageId, text = text)) }
            .onFailure { log.warn("replacing the confirmation failed: {}", it.message) }
    }

    private fun answer(query: CallbackQuery, text: String?) {
        runCatching { api.answerCallbackQuery(AnswerCallbackQueryRequest(callbackQueryId = query.id, text = text)) }
            .onFailure { log.warn("answerCallbackQuery failed: {}", it.message) }
    }
}
