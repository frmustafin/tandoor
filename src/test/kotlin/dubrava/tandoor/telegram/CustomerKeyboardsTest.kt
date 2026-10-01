package dubrava.tandoor.telegram

import dubrava.tandoor.core.batch.Batch
import dubrava.tandoor.core.batch.BatchStatus
import dubrava.tandoor.core.product.Product
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomerKeyboardsTest {

    private val samsa = Product(id = 1, name = "Самса из печи", updatedAt = Instant.EPOCH)
    private val lepyoshka = Product(id = 2, name = "Лепёшка", updatedAt = Instant.EPOCH)
    private val active = Batch(id = 7, productId = 1, status = BatchStatus.READY, announcedAt = Instant.EPOCH, expectedReadyAt = Instant.EPOCH, createdBy = 1)
    private val closed = active.copy(status = BatchStatus.SOLD_OUT)

    private fun rows(subscribed: Set<Long>) =
        SubscriptionKeyboard.build(listOf(samsa, lepyoshka), subscribed).inlineKeyboard.map { row -> row.map { "${it.text}→${it.callbackData}" } }

    @Test
    fun `checked and unchecked lines, then subscribe to all`() {
        assertEquals(
            listOf(listOf("✅ Самса из печи→sub:toggle:1"), listOf("⬜ Лепёшка→sub:toggle:2"), listOf("Подписаться на всё→sub:all")),
            rows(setOf(1)),
        )
    }

    @Test
    fun `subscribe to all disappears once everything is chosen`() {
        assertEquals(listOf(listOf("✅ Самса из печи→sub:toggle:1"), listOf("✅ Лепёшка→sub:toggle:2")), rows(setOf(1, 2)))
    }

    @Test
    fun `going button only while the batch is active`() {
        assertEquals("Иду!→going:7:dm", GoingKeyboard.forPersonal(active)!!.inlineKeyboard.single().single().let { "${it.text}→${it.callbackData}" })
        assertNull(GoingKeyboard.forPersonal(closed))
    }

    @Test
    fun `channel post invites into the bot and drops going when closed`() {
        val link = "https://t.me/some_bot?start=channel"
        val open = GoingKeyboard.forChannel(active, link)!!.inlineKeyboard.single()
        assertEquals(listOf("Иду!" to "going:7:channel", "Уведомлять лично" to null), open.map { it.text to it.callbackData })
        assertEquals(link, open[1].url)

        val done = GoingKeyboard.forChannel(closed, link)!!.inlineKeyboard.single()
        assertEquals(listOf("Уведомлять лично"), done.map { it.text })

        assertNull(GoingKeyboard.forChannel(closed, subscribeLink = null))
        assertEquals(listOf("Иду!"), GoingKeyboard.forChannel(active, subscribeLink = null)!!.inlineKeyboard.single().map { it.text })
    }
}
