package dubrava.tandoor.telegram

import dubrava.tandoor.core.batch.Batch
import dubrava.tandoor.core.batch.BatchRepository
import dubrava.tandoor.core.batch.BatchTexts
import dubrava.tandoor.core.product.Product
import dubrava.tandoor.core.settings.SettingsService
import dubrava.tandoor.telegram.api.EditMessageTextRequest
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import dubrava.tandoor.telegram.api.TelegramApiException
import org.springframework.stereotype.Component

/**
 * Sends and edits the baker's control message: status line from [BatchTexts], buttons from
 * [ControlKeyboard]. Reads the going-count straight from the repository to stay out of the
 * service's dependency graph (the service calls the notifier, which calls this).
 */
@Component
class ControlMessages(
    private val api: TelegramApi,
    private val texts: BatchTexts,
    private val settings: SettingsService,
    private val batches: BatchRepository,
) {

    fun send(chatId: Long, batch: Batch, product: Product, withNewBatch: Boolean = false): Message =
        api.sendMessage(
            SendMessageRequest(
                chatId = chatId,
                text = text(batch, product),
                replyMarkup = ControlKeyboard.build(batch, withNewBatch),
            )
        )

    /** Edits a specific control message; "not modified" is not an error. */
    fun edit(chatId: Long, messageId: Long, batch: Batch, product: Product, timePicker: Boolean = false) {
        try {
            api.editMessageText(
                EditMessageTextRequest(
                    chatId = chatId,
                    messageId = messageId,
                    text = text(batch, product),
                    replyMarkup = ControlKeyboard.build(batch, timePicker = timePicker),
                )
            )
        } catch (e: TelegramApiException) {
            if (!e.isNotModified) throw e
        }
    }

    fun text(batch: Batch, product: Product): String =
        texts.control(batch, product, batches.countGoing(batch.id!!), settings.hotFor())
}
