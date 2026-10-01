package dubrava.tandoor.telegram

import dubrava.tandoor.config.TelegramProperties
import dubrava.tandoor.core.batch.Batch
import dubrava.tandoor.core.batch.BatchMessages
import dubrava.tandoor.core.batch.BatchTexts
import dubrava.tandoor.core.product.Product
import dubrava.tandoor.core.subscription.SubscriptionService
import dubrava.tandoor.core.user.UserService
import dubrava.tandoor.telegram.api.EditMessageTextRequest
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import dubrava.tandoor.telegram.api.TelegramApiException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Personal messages about a batch: one per subscriber, edited in place on every change.
 * Sends are paced to the configured rate; a 403 marks the user as having blocked the bot.
 * Runs on the caller's thread: at pilot scale (about a hundred subscribers) that is seconds.
 */
@Component
class PersonalMessages(
    private val api: TelegramApi,
    private val props: TelegramProperties,
    private val texts: BatchTexts,
    private val subscriptions: SubscriptionService,
    private val users: UserService,
    private val messages: BatchMessages,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun broadcast(batch: Batch, product: Product) {
        for (userId in subscriptions.subscriberIds(product.id!!)) {
            send(userId, batch, product)
            pace()
        }
    }

    fun edit(batch: Batch, product: Product) {
        for (ref in messages.of(batch.id!!)) {
            attempt(ref.chatId) {
                api.editMessageText(
                    EditMessageTextRequest(
                        chatId = ref.chatId, messageId = ref.messageId,
                        text = texts.public(batch, product), replyMarkup = GoingKeyboard.forPersonal(batch),
                    )
                )
            }
            pace()
        }
    }

    private fun send(userId: Long, batch: Batch, product: Product) {
        attempt(userId) {
            val sent = api.sendMessage(
                SendMessageRequest(chatId = userId, text = texts.public(batch, product), replyMarkup = GoingKeyboard.forPersonal(batch))
            )
            messages.save(batch.id!!, sent.chat.id, sent.messageId)
        }
    }

    /** One retry after a 429; a 403 is final for that user; anything else is logged and skipped. */
    private fun attempt(userId: Long, call: () -> Unit) {
        repeat(2) { tryNumber ->
            try {
                call()
                return
            } catch (e: TelegramApiException) {
                when {
                    e.isNotModified -> return
                    e.isBotBlockedByUser -> { users.markBlocked(userId); return }
                    e.isRateLimited && tryNumber == 0 -> Thread.sleep((e.retryAfter ?: Duration.ofSeconds(1)).toMillis())
                    else -> { log.warn("message to {} failed: {}", userId, e.message); return }
                }
            } catch (e: Exception) {
                log.warn("message to {} failed: {}", userId, e.message)
                return
            }
        }
    }

    private fun pace() = Thread.sleep(1000L / props.sendRatePerSecond)
}
