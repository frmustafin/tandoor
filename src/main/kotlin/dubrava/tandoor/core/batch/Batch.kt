package dubrava.tandoor.core.batch

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.time.Instant

/** Lifecycle from the plan, section 4.2. Only ANNOUNCED and READY count as active. */
enum class BatchStatus {
    ANNOUNCED, READY, SOLD_OUT, EXPIRED, CANCELLED;

    val isActive: Boolean
        get() = this == ANNOUNCED || this == READY
}

/**
 * One tap of the baker. Message ids are kept so the channel post and the baker's control
 * message can be edited in place when the status changes: one post per batch, no new messages.
 */
@Table("batches")
data class Batch(
    @Id val id: Long? = null,
    val productId: Long,
    val status: BatchStatus,
    val announcedAt: Instant,
    /** When the batch is promised to be ready; the baker moves it, the timer fires on it. */
    val expectedReadyAt: Instant,
    val readyAt: Instant? = null,
    val closedAt: Instant? = null,
    val createdBy: Long,
    val channelMessageId: Long? = null,
    val controlChatId: Long? = null,
    val controlMessageId: Long? = null,
    /** False when the timer, not the baker, moved the batch to READY (discipline metric). */
    val readyManually: Boolean = false,
    val closedManually: Boolean = false,
)

/**
 * Status transitions are guarded in SQL (`WHERE status = ...`) so that a baker tap and the
 * timer thread cannot both apply the same transition; the caller checks the row count.
 */
interface BatchRepository : ListCrudRepository<Batch, Long> {

    @Query(
        """
        SELECT * FROM batches
        WHERE product_id = :productId AND status IN ('ANNOUNCED', 'READY')
        ORDER BY announced_at DESC
        LIMIT 1
        """
    )
    fun findActiveByProduct(productId: Long): Batch?

    /** Every tray of the product still on the go, oldest first: "Готово"/"Закончилось" act on all of them. */
    @Query(
        """
        SELECT * FROM batches
        WHERE product_id = :productId AND status IN ('ANNOUNCED', 'READY')
        ORDER BY announced_at
        """
    )
    fun findAllActiveByProduct(productId: Long): List<Batch>

    /** OffsetDateTime rather than Instant: both the H2 and the PostgreSQL driver read it directly. */
    @Query("SELECT MAX(ready_at) FROM batches WHERE product_id = :productId AND ready_at IS NOT NULL")
    fun findLastReadyAt(productId: Long): java.time.OffsetDateTime?

    fun findAllByStatusAndExpectedReadyAtLessThanEqual(status: BatchStatus, at: Instant): List<Batch>

    /** Whether a product has any history; decides between a hard and a soft delete. */
    fun countByProductId(productId: Long): Long

    fun findAllByStatusAndReadyAtBefore(status: BatchStatus, before: Instant): List<Batch>

    @Modifying
    @Query(
        """
        UPDATE batches SET status = 'READY', ready_at = :at, ready_manually = :manual
        WHERE id = :id AND status = 'ANNOUNCED'
        """
    )
    fun markReady(id: Long, at: Instant, manual: Boolean): Int

    @Modifying
    @Query(
        """
        UPDATE batches SET status = 'SOLD_OUT', closed_at = :at, closed_manually = TRUE
        WHERE id = :id AND status IN ('ANNOUNCED', 'READY')
        """
    )
    fun markSoldOut(id: Long, at: Instant): Int

    @Modifying
    @Query("UPDATE batches SET status = 'EXPIRED', closed_at = :at WHERE id = :id AND status = 'READY'")
    fun markExpired(id: Long, at: Instant): Int

    @Modifying
    @Query(
        """
        UPDATE batches SET status = 'CANCELLED', closed_at = :at, closed_manually = TRUE
        WHERE id = :id AND status = 'ANNOUNCED'
        """
    )
    fun markCancelled(id: Long, at: Instant): Int

    /** Only an announced batch has a ready time to move. */
    @Modifying
    @Query("UPDATE batches SET expected_ready_at = :at WHERE id = :id AND status = 'ANNOUNCED'")
    fun reschedule(id: Long, at: Instant): Int

    @Modifying
    @Query("UPDATE batches SET control_chat_id = :chatId, control_message_id = :messageId WHERE id = :id")
    fun attachControlMessage(id: Long, chatId: Long, messageId: Long): Int

    @Query("SELECT COUNT(*) FROM going_clicks WHERE batch_id = :batchId")
    fun countGoing(batchId: Long): Int
}
