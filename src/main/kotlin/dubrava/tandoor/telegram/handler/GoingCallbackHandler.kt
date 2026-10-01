package dubrava.tandoor.telegram.handler

import dubrava.tandoor.core.batch.BatchService
import dubrava.tandoor.core.batch.BatchService.GoingResult
import dubrava.tandoor.core.batch.GoingClicks
import dubrava.tandoor.telegram.CallbackHandler
import dubrava.tandoor.telegram.GoingKeyboard
import dubrava.tandoor.telegram.api.AnswerCallbackQueryRequest
import dubrava.tandoor.telegram.api.CallbackQuery
import dubrava.tandoor.telegram.api.TelegramApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * "Иду!" from a personal message or from under the channel post: `going:<batchId>:<dm|channel>`.
 * Channel readers need not have started the bot; only their Telegram ID is recorded.
 */
@Component
class GoingCallbackHandler(
    private val api: TelegramApi,
    private val batches: BatchService,
) : CallbackHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override val prefix = GoingKeyboard.PREFIX

    override fun handle(query: CallbackQuery, payload: String) {
        val batchId = payload.substringBefore(':').toLongOrNull()
        val source = if (payload.substringAfter(':', "") == GoingClicks.Source.CHANNEL) GoingClicks.Source.CHANNEL else GoingClicks.Source.PERSONAL
        val reply = when (batchId?.let { batches.recordGoing(it, query.from.id, source) }) {
            GoingResult.RECORDED -> "Ждём!"
            GoingResult.ALREADY -> "Уже отметили вас, ждём!"
            GoingResult.CLOSED, null -> "Эта партия уже закрыта"
        }
        runCatching { api.answerCallbackQuery(AnswerCallbackQueryRequest(callbackQueryId = query.id, text = reply)) }
            .onFailure { log.warn("answerCallbackQuery failed: {}", it.message) }
    }
}
