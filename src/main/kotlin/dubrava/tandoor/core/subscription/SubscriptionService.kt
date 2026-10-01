package dubrava.tandoor.core.subscription

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Which products a user wants to hear about. The table has a composite key, which Spring Data
 * JDBC does not model, so this is plain SQL.
 */
@Service
class SubscriptionService(private val jdbc: JdbcClient, private val clock: Clock) {

    fun productIdsOf(userId: Long): Set<Long> =
        jdbc.sql("SELECT product_id FROM subscriptions WHERE user_id = :userId")
            .param("userId", userId)
            .query { rs, _ -> rs.getLong(1) }
            .set()

    /** Flips one subscription; returns the new state. */
    fun toggle(userId: Long, productId: Long): Boolean {
        val removed = jdbc.sql("DELETE FROM subscriptions WHERE user_id = :userId AND product_id = :productId")
            .param("userId", userId).param("productId", productId)
            .update()
        if (removed > 0) return false
        subscribe(userId, productId)
        return true
    }

    fun subscribeAll(userId: Long, productIds: Collection<Long>) {
        val current = productIdsOf(userId)
        productIds.filter { it !in current }.forEach { subscribe(userId, it) }
    }

    fun unsubscribeAll(userId: Long): Int =
        jdbc.sql("DELETE FROM subscriptions WHERE user_id = :userId").param("userId", userId).update()

    /** Who gets a personal message about a batch of the product: subscribers who have not blocked the bot. */
    fun subscriberIds(productId: Long): List<Long> =
        jdbc.sql(
            """
            SELECT s.user_id FROM subscriptions s
            JOIN users u ON u.telegram_id = s.user_id
            WHERE s.product_id = :productId AND u.is_active = TRUE
            ORDER BY s.created_at
            """
        )
            .param("productId", productId)
            .query { rs, _ -> rs.getLong(1) }
            .list()

    private fun subscribe(userId: Long, productId: Long) {
        jdbc.sql("INSERT INTO subscriptions (user_id, product_id, created_at) VALUES (:userId, :productId, :now)")
            .param("userId", userId).param("productId", productId)
            .param("now", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .update()
    }
}
