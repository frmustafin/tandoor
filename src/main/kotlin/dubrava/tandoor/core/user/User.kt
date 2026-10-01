package dubrava.tandoor.core.user

import org.springframework.data.annotation.Id
import org.springframework.data.jdbc.core.JdbcAggregateOperations
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/** A customer. Only the Telegram ID is stored, plus where they came from (QR, channel, direct). */
@Table("users")
data class User(
    @Id val telegramId: Long,
    val source: String = Source.DIRECT,
    val firstSeenAt: Instant,
    val isActive: Boolean = true,
    val blockedAt: Instant? = null,
)

/** Values of `users.source`, taken from the `/start` deep-link payload. */
object Source {
    const val DIRECT = "direct"
    const val QR = "qr"
    const val CHANNEL = "channel"

    fun fromStartPayload(payload: String): String = when (payload) {
        QR -> QR
        CHANNEL -> CHANNEL
        else -> DIRECT
    }
}

interface UserRepository : ListCrudRepository<User, Long>

@Service
class UserService(
    private val users: UserRepository,
    /** The id is assigned, not generated, so `save` would try an UPDATE; inserts go here. */
    private val aggregates: JdbcAggregateOperations,
    private val clock: Clock,
) {

    /**
     * First contact creates the row with its source; the source is never overwritten later,
     * so the metric "where did people come from" stays honest. A user who blocked the bot and
     * comes back is reactivated.
     */
    fun touch(telegramId: Long, source: String): User {
        val existing = users.findById(telegramId).orElse(null)
            ?: return aggregates.insert(User(telegramId = telegramId, source = source, firstSeenAt = clock.instant()))
        return if (existing.isActive) existing else users.save(existing.copy(isActive = true, blockedAt = null))
    }

    /** Telegram answered 403 to a message: the user blocked the bot and leaves every broadcast. */
    fun markBlocked(telegramId: Long) {
        users.findById(telegramId).ifPresent { users.save(it.copy(isActive = false, blockedAt = clock.instant())) }
    }

    fun find(telegramId: Long): User? = users.findById(telegramId).orElse(null)
}
