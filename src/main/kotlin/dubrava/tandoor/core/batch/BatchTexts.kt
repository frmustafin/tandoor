package dubrava.tandoor.core.batch

import dubrava.tandoor.core.product.Product
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Message texts from the plan, section 5. The public ones are not sentences, so no product
 * name needs agreement in gender or number. Times are shown in the bakery zone.
 * The announcement names the absolute time: the baker may move it, and an edited post keeps
 * its original timestamp, so "in 15 minutes" would go stale. Drafts until the baker approves them.
 */
class BatchTexts(private val zone: ZoneId) {

    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    fun time(at: Instant): String = clock.format(at.atZone(zone))

    /**
     * What the channel and subscribers see: always "status · product · time", in that order,
     * so the message is read at a glance and fits a push preview. The emoji is the status.
     * The time is the moment the status was set (the promised time while only announced);
     * "Было готово" keeps the ready time, since that is what tells how fresh the batch is.
     */
    fun public(batch: Batch, product: Product): String {
        val readyAt = batch.readyAt ?: batch.expectedReadyAt
        val (status, at) = when (batch.status) {
            BatchStatus.ANNOUNCED -> "🔥 Готовим" to "к ${time(batch.expectedReadyAt)}"
            BatchStatus.READY -> "✅ Готово" to "в ${time(readyAt)}"
            BatchStatus.SOLD_OUT -> "🚫 Закончилось" to "в ${time(batch.closedAt ?: readyAt)}"
            BatchStatus.EXPIRED -> "🕒 Было готово" to "в ${time(readyAt)}"
            BatchStatus.CANCELLED -> "❌ Отменено" to batch.closedAt?.let { "в ${time(it)}" }
        }
        return listOfNotNull(status, product.name, at).joinToString(" · ")
    }

    /**
     * What the baker sees above the control buttons. The tap already published the post, so
     * the text says so up front and makes clear that the buttons edit that post.
     */
    fun control(batch: Batch, product: Product, going: Int, hotFor: Duration): String {
        val posted = batch.channelMessageId != null
        val where = if (posted) "📣 В канале" else "Без поста в канале"
        val readyAt = batch.readyAt ?: batch.expectedReadyAt
        val line = when (batch.status) {
            BatchStatus.ANNOUNCED ->
                "$where с ${time(batch.announcedAt)} · будет готово к ${time(batch.expectedReadyAt)}"
            BatchStatus.READY -> "$where: готово в ${time(readyAt)} · горячее до ${time(readyAt + hotFor)}"
            BatchStatus.SOLD_OUT -> "$where: закончилось в ${time(batch.closedAt ?: batch.announcedAt)}"
            BatchStatus.EXPIRED -> "$where: было готово в ${time(readyAt)}, остыло"
            BatchStatus.CANCELLED -> if (posted) "Отменено, пост из канала удалён" else "Отменено"
        }
        val hint = if (batch.status == BatchStatus.ANNOUNCED && posted) "\nКнопки ниже правят этот пост." else ""
        return "${product.name}\n$line$hint\nИдут: $going"
    }
}
