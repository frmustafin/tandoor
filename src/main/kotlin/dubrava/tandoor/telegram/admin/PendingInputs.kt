package dubrava.tandoor.telegram.admin

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * "Send me the new name" state: which admin is expected to send what, for which product.
 * In memory on purpose: a restart simply forgets an unfinished edit, and a stale expectation
 * expires so a message sent ten minutes later is not misread as a product name.
 */
@Component
class PendingInputs(private val clock: Clock) {

    enum class Field { NEW_NAME, NAME, DESCRIPTION, PRICE, PHOTO, READY_AFTER_MINUTES, HOT_FOR_MINUTES }

    data class Pending(val productId: Long?, val field: Field, val since: Instant)

    private val byAdmin = ConcurrentHashMap<Long, Pending>()

    fun expect(adminId: Long, productId: Long?, field: Field) {
        byAdmin[adminId] = Pending(productId, field, clock.instant())
    }

    fun peek(adminId: Long): Pending? {
        val pending = byAdmin[adminId] ?: return null
        if (Duration.between(pending.since, clock.instant()) > TTL) {
            byAdmin.remove(adminId)
            return null
        }
        return pending
    }

    fun clear(adminId: Long) {
        byAdmin.remove(adminId)
    }

    companion object {
        val TTL: Duration = Duration.ofMinutes(10)
    }
}
