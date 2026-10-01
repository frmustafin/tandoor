package dubrava.tandoor.telegram

import dubrava.tandoor.core.batch.Batch
import dubrava.tandoor.core.batch.BatchStatus
import dubrava.tandoor.telegram.api.InlineKeyboardButton
import dubrava.tandoor.telegram.api.InlineKeyboardMarkup

/**
 * Buttons under the baker's control message and their callback data (`batch:<action>:<id>[:<arg>]`).
 * Pure, so the layout is testable without Telegram.
 *
 * The default view stays small: one tap is the whole job most of the time. Changing the ready
 * time is behind "⏱ Изменить время", which swaps the same message's keyboard for the picker.
 */
object ControlKeyboard {

    const val PREFIX = "batch"
    const val READY = "$PREFIX:ready"
    const val SOLD_OUT = "$PREFIX:soldout"
    const val CANCEL = "$PREFIX:cancel"
    const val NEW = "$PREFIX:new"
    const val READY_IN = "$PREFIX:in"
    const val SHIFT = "$PREFIX:shift"
    const val OPEN_TIME = "$PREFIX:time"
    const val CLOSE_TIME = "$PREFIX:back"

    /** "Ready in N minutes from now." 15 is the usual default, kept so one tap restores it. */
    val READY_IN_PRESETS = listOf(10, 15, 20, 30)

    fun build(batch: Batch, withNewBatch: Boolean = false, timePicker: Boolean = false): InlineKeyboardMarkup? {
        val id = batch.id!!
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        when (batch.status) {
            BatchStatus.ANNOUNCED -> if (timePicker) {
                rows += READY_IN_PRESETS.map { button("$it мин", "$READY_IN:$id:$it") }
                rows += listOf(button("+5 мин", "$SHIFT:$id:5"), button("−5 мин", "$SHIFT:$id:-5"))
                rows += listOf(button("← Назад", "$CLOSE_TIME:$id"))
            } else {
                rows += listOf(button("✅ Готово", "$READY:$id"), button("Закончилось", "$SOLD_OUT:$id"))
                rows += listOf(button("⏱ Изменить время", "$OPEN_TIME:$id"), button("Отмена", "$CANCEL:$id"))
            }
            BatchStatus.READY -> rows += listOf(button("Закончилось", "$SOLD_OUT:$id"))
            BatchStatus.SOLD_OUT, BatchStatus.EXPIRED, BatchStatus.CANCELLED -> Unit
        }
        if (withNewBatch) rows += listOf(button("🔥 Новая партия", "$NEW:${batch.productId}"))
        return rows.takeIf { it.isNotEmpty() }?.let(::InlineKeyboardMarkup)
    }

    private fun button(text: String, data: String) = InlineKeyboardButton(text = text, callbackData = data)
}
