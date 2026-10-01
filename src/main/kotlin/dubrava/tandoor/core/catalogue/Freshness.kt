package dubrava.tandoor.core.catalogue

import dubrava.tandoor.core.batch.BatchRepository
import dubrava.tandoor.core.batch.BatchStatus
import dubrava.tandoor.core.product.Product
import org.springframework.stereotype.Service
import java.time.Instant

/** What the catalogue says about a product, derived from its batches (plan, section 4.4). */
sealed interface Freshness {
    data class Announced(val expectedReadyAt: Instant) : Freshness
    data class Hot(val readyAt: Instant) : Freshness
    data class Last(val readyAt: Instant) : Freshness
    data object Never : Freshness

    /** Hot first, then announced, then the rest: the order of the catalogue list. */
    val rank: Int
        get() = when (this) {
            is Hot -> 0
            is Announced -> 1
            is Last, Never -> 2
        }
}

/**
 * The moment of a fresh batch is its transition to READY; cancelled batches and batches that
 * never got there do not count. No table of its own: everything comes from `batches`.
 */
@Service
class FreshnessService(private val batches: BatchRepository) {

    fun of(productId: Long): Freshness {
        val active = batches.findAllActiveByProduct(productId)
        active.lastOrNull { it.status == BatchStatus.READY }?.let { return Freshness.Hot(it.readyAt ?: it.expectedReadyAt) }
        active.lastOrNull()?.let { return Freshness.Announced(it.expectedReadyAt) }
        return batches.findLastReadyAt(productId)?.let { Freshness.Last(it.toInstant()) } ?: Freshness.Never
    }

    /** Products with their freshness, hot ones first, then the admin's button order. */
    fun catalogue(products: List<Product>): List<Pair<Product, Freshness>> =
        products.map { it to of(it.id!!) }.sortedWith(compareBy({ it.second.rank }, { it.first.sortOrder }))
}
