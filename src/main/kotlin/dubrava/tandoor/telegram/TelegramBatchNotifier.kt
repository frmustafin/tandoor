package dubrava.tandoor.telegram

import dubrava.tandoor.config.TelegramProperties
import dubrava.tandoor.core.batch.Batch
import dubrava.tandoor.core.batch.BatchNotifier
import dubrava.tandoor.core.batch.BatchStatus
import dubrava.tandoor.core.batch.BatchTexts
import dubrava.tandoor.core.product.Product
import dubrava.tandoor.core.settings.SettingsService
import dubrava.tandoor.core.user.Source
import dubrava.tandoor.telegram.api.DeleteMessageRequest
import dubrava.tandoor.telegram.api.EditMessageTextRequest
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import dubrava.tandoor.telegram.api.TelegramApiException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Telegram side of the batch lifecycle: one post in the channel, one control card for the
 * baker, one personal message per subscriber, all edited in place as the batch changes.
 * Failures are logged, never propagated: a Telegram hiccup must not undo a batch.
 */
@Component
class TelegramBatchNotifier(
    private val api: TelegramApi,
    private val props: TelegramProperties,
    private val settings: SettingsService,
    private val texts: BatchTexts,
    private val control: ControlMessages,
    private val personal: PersonalMessages,
    private val identity: BotIdentity,
) : BatchNotifier {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun announced(batch: Batch, product: Product): BatchNotifier.MessageRefs {
        val channelMessageId = postToChannel(batch, product)
        // In a private chat the chat id is the user id, so the baker's id addresses their chat.
        val card = runCatching { control.send(batch.createdBy, batch, product) }
            .onFailure { log.error("control message for batch {} failed: {}", batch.id, it.message) }
            .getOrNull()
        // Subscribers last: the baker's card and the channel post must not wait for a broadcast.
        runCatching { personal.broadcast(batch, product) }
            .onFailure { log.error("broadcast for batch {} failed", batch.id, it) }
        return BatchNotifier.MessageRefs(
            channelMessageId = channelMessageId,
            controlChatId = card?.chat?.id,
            controlMessageId = card?.messageId,
        )
    }

    override fun changed(batch: Batch, product: Product) {
        updateChannel(batch, product)
        editControl(batch, product)
        runCatching { personal.edit(batch, product) }
            .onFailure { log.error("editing personal messages for batch {} failed", batch.id, it) }
    }

    override fun goingChanged(batch: Batch, product: Product) = editControl(batch, product)

    private fun editControl(batch: Batch, product: Product) {
        val chatId = batch.controlChatId ?: return
        val messageId = batch.controlMessageId ?: return
        runCatching { control.edit(chatId, messageId, batch, product) }
            .onFailure { log.error("control message edit for batch {} failed: {}", batch.id, it.message) }
    }

    private fun postToChannel(batch: Batch, product: Product): Long? {
        if (!props.hasChannel || !settings.channelAutoPosts()) return null
        return runCatching {
            api.sendMessage(
                SendMessageRequest(chatId = props.channelId, text = texts.public(batch, product), replyMarkup = channelKeyboard(batch))
            ).messageId
        }.onFailure { log.error("channel post for batch {} failed: {}", batch.id, it.message) }.getOrNull()
    }

    private fun updateChannel(batch: Batch, product: Product) {
        val messageId = batch.channelMessageId ?: return
        if (!props.hasChannel) return
        try {
            if (batch.status == BatchStatus.CANCELLED) {
                api.deleteMessage(DeleteMessageRequest(chatId = props.channelId, messageId = messageId))
            } else {
                api.editMessageText(
                    EditMessageTextRequest(
                        chatId = props.channelId, messageId = messageId,
                        text = texts.public(batch, product), replyMarkup = channelKeyboard(batch),
                    )
                )
            }
        } catch (e: TelegramApiException) {
            if (!e.isNotModified) log.error("channel post update for batch {} failed: {}", batch.id, e.message)
        } catch (e: Exception) {
            log.error("channel post update for batch {} failed: {}", batch.id, e.message)
        }
    }

    private fun channelKeyboard(batch: Batch) = GoingKeyboard.forChannel(batch, identity.deepLink(Source.CHANNEL))
}
