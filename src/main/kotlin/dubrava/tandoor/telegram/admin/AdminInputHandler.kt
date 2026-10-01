package dubrava.tandoor.telegram.admin

import dubrava.tandoor.core.product.ProductService
import dubrava.tandoor.core.product.ProductService.PriceInput
import dubrava.tandoor.core.settings.SettingsService
import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.telegram.TextMessageHandler
import dubrava.tandoor.telegram.admin.PendingInputs.Field
import dubrava.tandoor.telegram.admin.PendingInputs.Pending
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * The message an admin sends after tapping "Название", "Цена", "Фото", a timer… Runs before
 * every other plain-message handler, so a product named like a menu button is still just a
 * name. Admins only, by the router; a pending edit of someone whose role was revoked is simply
 * never consumed and expires.
 */
@Component
@Order(0)
class AdminInputHandler(
    private val api: TelegramApi,
    private val products: ProductService,
    private val productMessages: AdminProductMessages,
    private val settings: SettingsService,
    private val settingsMessages: AdminSettingsHandler,
    private val inputs: PendingInputs,
) : TextMessageHandler {

    override val requiredRole = Role.ADMIN

    override fun handle(message: Message): Boolean {
        val adminId = message.from?.id ?: return false
        val pending = inputs.peek(adminId) ?: return false
        val chatId = message.chat.id
        val text = message.text?.trim()
        if (text != null && text.equals("отмена", ignoreCase = true)) {
            inputs.clear(adminId)
            say(chatId, "Хорошо, ничего не меняю.")
            return true
        }
        return when (pending.field) {
            Field.READY_AFTER_MINUTES -> minutes(adminId, chatId, text, SettingsService.READY_AFTER_MINUTES)
            Field.HOT_FOR_MINUTES -> minutes(adminId, chatId, text, SettingsService.HOT_FOR_MINUTES)
            else -> product(adminId, chatId, pending, text, message.largestPhotoFileId)
        }
    }

    private fun minutes(adminId: Long, chatId: Long, text: String?, key: String): Boolean {
        val value = text?.toLongOrNull()
        if (value == null || value !in AdminSettingsHandler.MIN_MINUTES..AdminSettingsHandler.MAX_MINUTES) {
            return retry(chatId, "Нужно число минут от ${AdminSettingsHandler.MIN_MINUTES} до ${AdminSettingsHandler.MAX_MINUTES}.")
        }
        settings.set(key, value.toString())
        inputs.clear(adminId)
        settingsMessages.send(chatId)
        return true
    }

    private fun product(adminId: Long, chatId: Long, pending: Pending, text: String?, photoFileId: String?): Boolean {
        val updated = when (pending.field) {
            Field.NEW_NAME -> text?.takeIf { it.isNotBlank() }?.let { products.create(it) }
                ?: return retry(chatId, "Нужно название текстом.")
            Field.NAME -> text?.takeIf { it.isNotBlank() }?.let { products.rename(pending.productId!!, it) }
                ?: return retry(chatId, "Нужно название текстом.")
            Field.DESCRIPTION -> text?.let { products.describe(pending.productId!!, it.takeIf { it != "-" }) ?: return gone(adminId, chatId) }
                ?: return retry(chatId, "Нужно описание текстом, или «-», чтобы убрать его.")
            Field.PRICE -> when (val input = ProductService.parsePrice(text ?: "")) {
                is PriceInput.Value -> products.price(pending.productId!!, input.price) ?: return gone(adminId, chatId)
                PriceInput.Invalid -> return retry(chatId, "Не понял цену. Пример: 250 или 250,50. «-» — без цены.")
            }
            Field.PHOTO -> photoFileId?.let { products.photo(pending.productId!!, it) }
                ?: return retry(chatId, "Пришлите фото одним сообщением (как фото, не как файл).")
            Field.READY_AFTER_MINUTES, Field.HOT_FOR_MINUTES -> error("settings fields are handled above")
        }
        inputs.clear(adminId)
        val touchesKeyboard = pending.field == Field.NAME || pending.field == Field.NEW_NAME
        productMessages.sendCard(chatId, updated, note = if (touchesKeyboard) AdminProductMessages.KEYBOARD_NOTE else null)
        return true
    }

    /** Keeps the expectation and asks again. */
    private fun retry(chatId: Long, text: String): Boolean {
        say(chatId, "$text\nИли напишите «отмена».")
        return true
    }

    private fun gone(adminId: Long, chatId: Long): Boolean {
        inputs.clear(adminId)
        say(chatId, "Позиция не найдена.")
        return true
    }

    private fun say(chatId: Long, text: String) {
        api.sendMessage(SendMessageRequest(chatId = chatId, text = text))
    }
}
