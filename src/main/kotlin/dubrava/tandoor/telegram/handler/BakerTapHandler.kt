package dubrava.tandoor.telegram.handler

import dubrava.tandoor.core.batch.BatchService
import dubrava.tandoor.core.batch.BatchService.AnnounceResult
import dubrava.tandoor.core.product.ProductRepository
import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.telegram.ControlMessages
import dubrava.tandoor.telegram.TextMessageHandler
import dubrava.tandoor.telegram.api.Message
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * A tap on a product button arrives as a plain text message equal to the product name.
 * One tap announces a batch; a tap while a batch is active re-shows its controls with a
 * "Новая партия" button instead of creating a duplicate. The router only lets staff in, so a
 * customer who types a product name never reaches this handler.
 */
@Component
@Order(1)
class BakerTapHandler(
    private val products: ProductRepository,
    private val batches: BatchService,
    private val control: ControlMessages,
) : TextMessageHandler {

    override val requiredRole = Role.BAKER

    override fun handle(message: Message): Boolean {
        val userId = message.from?.id ?: return false
        val text = message.text?.trim().orEmpty()
        if (text.isEmpty()) return false
        val product = products.findAllByNameAndIsActiveTrue(text).firstOrNull() ?: return false

        when (val result = batches.announce(product.id!!, userId)) {
            is AnnounceResult.Created -> {
                // The notifier normally sends the controls; if Telegram failed then, send them now.
                if (result.batch.controlMessageId == null) resend(message.chat.id, result, withNewBatch = false)
            }
            is AnnounceResult.AlreadyActive -> resend(message.chat.id, result, withNewBatch = true)
            null -> return false
        }
        return true
    }

    private fun resend(chatId: Long, result: AnnounceResult, withNewBatch: Boolean) {
        val sent = control.send(chatId, result.batch, result.product, withNewBatch)
        batches.attachControlMessage(result.batch.id!!, sent.chat.id, sent.messageId)
    }
}
