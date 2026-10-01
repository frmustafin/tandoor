package dubrava.tandoor.core.batch

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** "Иду!" taps: one per person and batch, with where the tap came from (personal chat or channel). */
@Component
class GoingClicks(private val jdbc: JdbcClient, private val clock: Clock) {

    /** Returns false when this person already tapped for this batch. */
    fun record(batchId: Long, telegramId: Long, source: String): Boolean {
        return try {
            jdbc.sql(
                """
                INSERT INTO going_clicks (batch_id, telegram_id, source, clicked_at)
                VALUES (:batchId, :telegramId, :source, :now)
                """
            )
                .param("batchId", batchId).param("telegramId", telegramId).param("source", source)
                .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .update() == 1
        } catch (_: DuplicateKeyException) {
            false
        }
    }

    object Source {
        const val PERSONAL = "dm"
        const val CHANNEL = "channel"
    }
}

/** Personal messages sent about a batch, kept so status changes can edit them in place. */
@Component
class BatchMessages(private val jdbc: JdbcClient) {

    data class Ref(val chatId: Long, val messageId: Long)

    fun save(batchId: Long, chatId: Long, messageId: Long) {
        jdbc.sql("INSERT INTO batch_messages (batch_id, chat_id, message_id) VALUES (:batchId, :chatId, :messageId)")
            .param("batchId", batchId).param("chatId", chatId).param("messageId", messageId)
            .update()
    }

    fun of(batchId: Long): List<Ref> =
        jdbc.sql("SELECT chat_id, message_id FROM batch_messages WHERE batch_id = :batchId")
            .param("batchId", batchId)
            .query { rs, _ -> Ref(rs.getLong("chat_id"), rs.getLong("message_id")) }
            .list()
}
