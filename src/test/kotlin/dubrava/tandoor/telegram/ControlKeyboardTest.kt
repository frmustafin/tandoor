package dubrava.tandoor.telegram

import dubrava.tandoor.core.batch.Batch
import dubrava.tandoor.core.batch.BatchStatus
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ControlKeyboardTest {

    private fun batch(status: BatchStatus) =
        Batch(id = 7, productId = 2, status = status, announcedAt = Instant.EPOCH, expectedReadyAt = Instant.EPOCH, createdBy = 1)

    /** Rows as "label→data" so a layout change is visible at a glance. */
    private fun rows(batch: Batch, withNewBatch: Boolean = false, timePicker: Boolean = false) =
        ControlKeyboard.build(batch, withNewBatch, timePicker)?.inlineKeyboard?.map { row -> row.map { "${it.text}→${it.callbackData}" } }

    @Test
    fun `announced batch shows two small rows by default`() {
        assertEquals(
            listOf(
                listOf("✅ Готово→batch:ready:7", "Закончилось→batch:soldout:7"),
                listOf("⏱ Изменить время→batch:time:7", "Отмена→batch:cancel:7"),
            ),
            rows(batch(BatchStatus.ANNOUNCED)),
        )
    }

    @Test
    fun `the time picker replaces the default rows and offers a way back`() {
        assertEquals(
            listOf(
                listOf("10 мин→batch:in:7:10", "15 мин→batch:in:7:15", "20 мин→batch:in:7:20", "30 мин→batch:in:7:30"),
                listOf("+5 мин→batch:shift:7:5", "−5 мин→batch:shift:7:-5"),
                listOf("← Назад→batch:back:7"),
            ),
            rows(batch(BatchStatus.ANNOUNCED), timePicker = true),
        )
    }

    @Test
    fun `a ready batch can only be sold out`() {
        assertEquals(listOf(listOf("Закончилось→batch:soldout:7")), rows(batch(BatchStatus.READY)))
        assertEquals(listOf(listOf("Закончилось→batch:soldout:7")), rows(batch(BatchStatus.READY), timePicker = true), "no picker once ready")
    }

    @Test
    fun `closed batches have no buttons unless a new batch is offered`() {
        for (status in listOf(BatchStatus.SOLD_OUT, BatchStatus.EXPIRED, BatchStatus.CANCELLED)) {
            assertNull(ControlKeyboard.build(batch(status)), "$status")
            assertEquals(listOf(listOf("🔥 Новая партия→batch:new:2")), rows(batch(status), withNewBatch = true), "$status")
        }
    }
}
