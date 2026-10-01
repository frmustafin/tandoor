package dubrava.tandoor.core.batch

import dubrava.tandoor.core.product.Product
import dubrava.tandoor.core.product.ProductRepository
import dubrava.tandoor.core.settings.SettingsService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The batch lifecycle. Everything here is messenger-independent: who gets told, and how,
 * is the [BatchNotifier]'s job.
 */
@Service
class BatchService(
    private val batches: BatchRepository,
    private val products: ProductRepository,
    private val settings: SettingsService,
    private val notifier: BatchNotifier,
    private val going: GoingClicks,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    sealed interface AnnounceResult {
        val batch: Batch
        val product: Product

        data class Created(override val batch: Batch, override val product: Product) : AnnounceResult

        /** A second tap while a batch is active: no duplicate, the caller shows the existing one. */
        data class AlreadyActive(override val batch: Batch, override val product: Product) : AnnounceResult
    }

    /**
     * One tap of the baker. Returns null for an unknown or hidden product. With [force] a new
     * batch is created even while another is active ("Новая партия"); the older one keeps
     * running down its own timers.
     */
    fun announce(productId: Long, bakerId: Long, force: Boolean = false): AnnounceResult? {
        val product = products.findById(productId).orElse(null)?.takeIf { it.isActive } ?: return null
        if (!force) {
            batches.findActiveByProduct(productId)?.let { return AnnounceResult.AlreadyActive(it, product) }
        }
        val now = clock.instant()
        val created = batches.save(
            Batch(
                productId = productId,
                status = BatchStatus.ANNOUNCED,
                announcedAt = now,
                expectedReadyAt = now + settings.readyAfter(),
                createdBy = bakerId,
            )
        )
        val refs = runCatching { notifier.announced(created, product) }
            .onFailure { log.error("announcing batch {} failed", created.id, it) }
            .getOrDefault(BatchNotifier.MessageRefs())
        val stored = batches.save(
            created.copy(
                channelMessageId = refs.channelMessageId,
                controlChatId = refs.controlChatId,
                controlMessageId = refs.controlMessageId,
            )
        )
        return AnnounceResult.Created(stored, product)
    }

    /** The baker got a fresh control message (after a duplicate tap); edits go there from now on. */
    fun attachControlMessage(batchId: Long, chatId: Long, messageId: Long) {
        batches.attachControlMessage(batchId, chatId, messageId)
    }

    /** "Ready in N minutes" counted from now; the baker's own estimate replaces the default. */
    fun setReadyIn(batchId: Long, duration: Duration): Batch? = reschedule(batchId, clock.instant() + duration)

    /** "+5" / "−5": moves the current promise, whatever it was. */
    fun shiftReadyTime(batchId: Long, by: Duration): Batch? {
        val (batch, _) = find(batchId) ?: return null
        return reschedule(batchId, batch.expectedReadyAt + by)
    }

    fun markReady(batchId: Long): Batch? = transition(batchId) { batches.markReady(it, clock.instant(), manual = true) }

    fun markSoldOut(batchId: Long): Batch? = transition(batchId) { batches.markSoldOut(it, clock.instant()) }

    /**
     * "Готово" on any card of the product: every tray still announced is ready now. The baker
     * thinks in products, not trays, so one tap closes the question for all of them.
     */
    fun markProductReady(productId: Long): List<Batch> =
        batches.findAllActiveByProduct(productId)
            .filter { it.status == BatchStatus.ANNOUNCED }
            .mapNotNull { markReady(it.id!!) }

    /** "Закончилось" on any card of the product: every active tray is sold out. */
    fun markProductSoldOut(productId: Long): List<Batch> =
        batches.findAllActiveByProduct(productId).mapNotNull { markSoldOut(it.id!!) }

    /** Allowed only while ANNOUNCED: once people were told it is ready, cancelling makes no sense. */
    fun cancel(batchId: Long): Batch? = transition(batchId) { batches.markCancelled(it, clock.instant()) }

    /**
     * The automatic transitions that make one tap enough: ANNOUNCED becomes READY at its
     * expected time, READY becomes EXPIRED when the hot-for time has passed.
     * Returns how many batches moved.
     */
    fun advanceTimers(): Int {
        val now = clock.instant()
        var moved = 0
        for (batch in batches.findAllByStatusAndExpectedReadyAtLessThanEqual(BatchStatus.ANNOUNCED, now)) {
            if (transition(batch.id!!) { batches.markReady(it, now, manual = false) } != null) moved++
        }
        for (batch in batches.findAllByStatusAndReadyAtBefore(BatchStatus.READY, now - settings.hotFor())) {
            if (transition(batch.id!!) { batches.markExpired(it, now) } != null) moved++
        }
        return moved
    }

    fun find(batchId: Long): Pair<Batch, Product>? {
        val batch = batches.findById(batchId).orElse(null) ?: return null
        val product = products.findById(batch.productId).orElse(null) ?: return null
        return batch to product
    }

    fun goingCount(batchId: Long): Int = batches.countGoing(batchId)

    enum class GoingResult { RECORDED, ALREADY, CLOSED }

    /** "Иду!" from a personal message or the channel post; the baker's counter follows. */
    fun recordGoing(batchId: Long, telegramId: Long, source: String): GoingResult {
        val (batch, product) = find(batchId) ?: return GoingResult.CLOSED
        if (!batch.status.isActive) return GoingResult.CLOSED
        if (!going.record(batchId, telegramId, source)) return GoingResult.ALREADY
        runCatching { notifier.goingChanged(batch, product) }
            .onFailure { log.error("redrawing the going counter for batch {} failed", batchId, it) }
        return GoingResult.RECORDED
    }

    /** A promise in the past makes no sense; the floor leaves the timer one tick to fire. */
    private fun reschedule(batchId: Long, at: Instant): Batch? {
        val floor = clock.instant() + MIN_LEAD
        val target = if (at < floor) floor else at
        return transition(batchId) { batches.reschedule(it, target) }
    }

    /** Applies a guarded SQL transition; null means the batch was not in the expected status. */
    private fun transition(batchId: Long, update: (Long) -> Int): Batch? {
        if (update(batchId) != 1) return null
        val (batch, product) = find(batchId) ?: return null
        runCatching { notifier.changed(batch, product) }
            .onFailure { log.error("notifying about batch {} failed", batchId, it) }
        return batch
    }

    companion object {
        val MIN_LEAD: Duration = Duration.ofMinutes(1)
    }
}
