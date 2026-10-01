package dubrava.tandoor.core

import dubrava.tandoor.core.batch.Batch
import dubrava.tandoor.core.batch.BatchStatus
import dubrava.tandoor.core.batch.BatchTexts
import dubrava.tandoor.core.product.Product
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals

class BatchTextsTest {

    private val texts = BatchTexts(ZoneId.of("Europe/Moscow"))
    private val samsa = Product(id = 2, name = "Самса из тандыра", updatedAt = Instant.EPOCH)
    private val announcedAt = Instant.parse("2026-10-01T09:25:00Z") // 12:25 Moscow
    private val expectedAt = Instant.parse("2026-10-01T09:40:00Z")  // 12:40 Moscow
    private val readyAt = Instant.parse("2026-10-01T09:38:00Z")     // 12:38 Moscow, tapped early
    private val closedAt = Instant.parse("2026-10-01T10:05:00Z")    // 13:05 Moscow
    private val thirty = Duration.ofMinutes(30)

    private fun batch(status: BatchStatus, ready: Instant? = null, closed: Instant? = null, channelMessageId: Long? = 55) =
        Batch(
            id = 7, productId = 2, status = status, announcedAt = announcedAt, expectedReadyAt = expectedAt,
            readyAt = ready, closedAt = closed, createdBy = 1, channelMessageId = channelMessageId,
        )

    @Test
    fun `public texts are status, product, time - in that order for every status`() {
        assertEquals("🔥 Готовим · Самса из тандыра · к 12:40", texts.public(batch(BatchStatus.ANNOUNCED), samsa))
        assertEquals("✅ Готово · Самса из тандыра · в 12:38", texts.public(batch(BatchStatus.READY, readyAt), samsa))
        assertEquals("🚫 Закончилось · Самса из тандыра · в 13:05", texts.public(batch(BatchStatus.SOLD_OUT, readyAt, closedAt), samsa))
        assertEquals("🕒 Было готово · Самса из тандыра · в 12:38", texts.public(batch(BatchStatus.EXPIRED, readyAt), samsa))
        assertEquals("❌ Отменено · Самса из тандыра · в 13:05", texts.public(batch(BatchStatus.CANCELLED, closed = closedAt), samsa))
    }

    @Test
    fun `the time is the moment of the status change, not the promise made at the announcement`() {
        // Promised for 12:40; the baker tapped "Готово" at 12:38 and "Закончилось" at 13:05.
        assertEquals("✅ Готово · Самса из тандыра · в 12:38", texts.public(batch(BatchStatus.READY, readyAt), samsa))
        assertEquals("🚫 Закончилось · Самса из тандыра · в 13:05", texts.public(batch(BatchStatus.SOLD_OUT, readyAt, closedAt), samsa))
        // Sold out before it was ever marked ready: still the moment of the tap.
        assertEquals("🚫 Закончилось · Самса из тандыра · в 13:05", texts.public(batch(BatchStatus.SOLD_OUT, closed = closedAt), samsa))
    }

    @Test
    fun `times are shown in the bakery zone`() {
        assertEquals("12:40", texts.time(expectedAt))
    }

    @Test
    fun `control text says the post is already in the channel and what the buttons do`() {
        assertEquals(
            "Самса из тандыра\n📣 В канале с 12:25 · будет готово к 12:40\nКнопки ниже правят этот пост.\nИдут: 0",
            texts.control(batch(BatchStatus.ANNOUNCED), samsa, 0, thirty),
        )
        assertEquals(
            "Самса из тандыра\n📣 В канале: готово в 12:38 · горячее до 13:08\nИдут: 3",
            texts.control(batch(BatchStatus.READY, readyAt), samsa, 3, thirty),
        )
        assertEquals(
            "Самса из тандыра\n📣 В канале: закончилось в 13:05\nИдут: 3",
            texts.control(batch(BatchStatus.SOLD_OUT, readyAt, closedAt), samsa, 3, thirty),
        )
        assertEquals(
            "Самса из тандыра\nОтменено, пост из канала удалён\nИдут: 0",
            texts.control(batch(BatchStatus.CANCELLED, closed = closedAt), samsa, 0, thirty),
        )
    }

    @Test
    fun `control text without a channel post does not pretend there is one`() {
        assertEquals(
            "Самса из тандыра\nБез поста в канале с 12:25 · будет готово к 12:40\nИдут: 0",
            texts.control(batch(BatchStatus.ANNOUNCED, channelMessageId = null), samsa, 0, thirty),
        )
        assertEquals(
            "Самса из тандыра\nОтменено\nИдут: 0",
            texts.control(batch(BatchStatus.CANCELLED, channelMessageId = null), samsa, 0, thirty),
        )
    }
}
