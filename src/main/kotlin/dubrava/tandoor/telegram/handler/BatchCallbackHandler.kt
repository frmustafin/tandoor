package dubrava.tandoor.telegram.handler

import dubrava.tandoor.core.batch.Batch
import dubrava.tandoor.core.batch.BatchService
import dubrava.tandoor.core.batch.BatchService.AnnounceResult
import dubrava.tandoor.core.batch.BatchTexts
import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.telegram.CallbackHandler
import dubrava.tandoor.telegram.ControlKeyboard
import dubrava.tandoor.telegram.ControlMessages
import dubrava.tandoor.telegram.api.AnswerCallbackQueryRequest
import dubrava.tandoor.telegram.api.CallbackQuery
import dubrava.tandoor.telegram.api.TelegramApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Buttons under the control message. Payload is `<action>:<id>[:<arg>]`:
 * `ready:<batch>`, `soldout:<batch>`, `cancel:<batch>`, `new:<product>`,
 * `time:<batch>` / `back:<batch>` (open / close the time picker on the tapped message),
 * `in:<batch>:<minutes>` (ready in N minutes from now), `shift:<batch>:<±minutes>`.
 * The service guards every transition, so a stale button (timer got there first, or a
 * second tap) is answered and the message redrawn, not applied. Staff only, by the router.
 */
@Component
class BatchCallbackHandler(
    private val api: TelegramApi,
    private val batches: BatchService,
    private val control: ControlMessages,
    private val texts: BatchTexts,
) : CallbackHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override val prefix = ControlKeyboard.PREFIX

    override val requiredRole = Role.BAKER

    override fun handle(query: CallbackQuery, payload: String) {
        val userId = query.from.id
        val parts = payload.split(':')
        val action = parts[0]
        val id = parts.getOrNull(1)?.toLongOrNull()
        val arg = parts.getOrNull(2)?.toLongOrNull()
        if (id == null) {
            answer(query, null)
            return
        }

        val reply = when (action) {
            "ready" -> forProduct(id) { batches.markProductReady(it) }?.let { posts(it, "готово, горячее") }
                ?: stale(query, id, "Партий в анонсе уже нет")
            "soldout" -> forProduct(id) { batches.markProductSoldOut(it) }?.let { posts(it, "закончилось") }
                ?: stale(query, id, "Активных партий уже нет")
            "cancel" -> batches.cancel(id)?.let { "Партия отменена, пост из канала удалён" }
                ?: stale(query, id, "Отменить можно только до готовности")
            // The picker lives on the tapped message; a time change redraws it closed via the notifier.
            "time" -> redraw(query, id, timePicker = true)
            "back" -> redraw(query, id, timePicker = false)
            "in" -> arg?.let { minutes -> batches.setReadyIn(id, Duration.ofMinutes(minutes)) }
                ?.let { "Пост в канале обновлён: будет готово к ${texts.time(it.expectedReadyAt)}" }
                ?: stale(query, id, "Время можно менять только до готовности")
            "shift" -> arg?.let { minutes -> batches.shiftReadyTime(id, Duration.ofMinutes(minutes)) }
                ?.let { "Пост в канале обновлён: будет готово к ${texts.time(it.expectedReadyAt)}" }
                ?: stale(query, id, "Время можно менять только до готовности")
            "new" -> when (val result = batches.announce(id, userId, force = true)) {
                is AnnounceResult.Created -> {
                    if (result.batch.controlMessageId == null) {
                        val sent = control.send(query.from.id, result.batch, result.product)
                        batches.attachControlMessage(result.batch.id!!, sent.chat.id, sent.messageId)
                    }
                    "Новая партия объявлена"
                }
                is AnnounceResult.AlreadyActive, null -> "Позиция не найдена"
            }
            else -> {
                log.warn("unknown batch action {}", action)
                null
            }
        }
        answer(query, reply)
    }

    /** The card belongs to one tray; "Готово"/"Закончилось" act on every tray of its product. */
    private fun forProduct(batchId: Long, action: (productId: Long) -> List<Batch>): Int? {
        val (batch, _) = batches.find(batchId) ?: return null
        return action(batch.productId).size.takeIf { it > 0 }
    }

    private fun posts(count: Int, what: String) =
        if (count == 1) "Пост в канале обновлён: $what" else "Обновлено постов в канале: $count — $what"

    /** The tapped message is out of date: redraw it with the real status so the baker sees why. */
    private fun stale(query: CallbackQuery, batchId: Long, reply: String): String {
        redraw(query, batchId, timePicker = false)
        return reply
    }

    /** Redraws the tapped message from the current batch state; returns null (no toast). */
    private fun redraw(query: CallbackQuery, batchId: Long, timePicker: Boolean): String? {
        val message = query.message ?: return null
        val (batch, product) = batches.find(batchId) ?: return null
        runCatching { control.edit(message.chat.id, message.messageId, batch, product, timePicker) }
            .onFailure { log.warn("redrawing control message failed: {}", it.message) }
        return null
    }

    private fun answer(query: CallbackQuery, text: String?) {
        runCatching { api.answerCallbackQuery(AnswerCallbackQueryRequest(callbackQueryId = query.id, text = text)) }
            .onFailure { log.warn("answerCallbackQuery failed: {}", it.message) }
    }
}
