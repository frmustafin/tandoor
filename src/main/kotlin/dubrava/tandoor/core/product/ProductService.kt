package dubrava.tandoor.core.product

import dubrava.tandoor.core.batch.BatchRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock

/**
 * Product cards as the admin edits them in the bot. Hiding keeps a product in the admin list
 * for later; deleting removes it from every list, and from the database too when nothing
 * refers to it yet.
 */
@Service
class ProductService(
    private val products: ProductRepository,
    private val batches: BatchRepository,
    private val clock: Clock,
) {

    fun active(): List<Product> = products.findAllByIsActiveTrueOrderBySortOrder()

    fun all(): List<Product> = products.findAllByDeletedAtIsNullOrderBySortOrder()

    fun find(id: Long): Product? = products.findById(id).orElse(null)

    fun create(name: String): Product {
        val last = all().maxOfOrNull { it.sortOrder } ?: 0
        return products.save(Product(name = name.trim(), sortOrder = last + 1, updatedAt = clock.instant()))
    }

    fun rename(id: Long, name: String): Product? = update(id) { it.copy(name = name.trim()) }

    /** Blank clears the description. */
    fun describe(id: Long, description: String?): Product? =
        update(id) { it.copy(description = description?.trim()?.takeIf(String::isNotEmpty)) }

    /** Null clears the price: the plan allows a card without one. */
    fun price(id: Long, price: BigDecimal?): Product? = update(id) { it.copy(price = price) }

    fun photo(id: Long, fileId: String?): Product? = update(id) { it.copy(photoFileId = fileId) }

    fun setActive(id: Long, active: Boolean): Product? = update(id) { it.copy(isActive = active) }

    sealed interface Deleted {
        val product: Product

        /** No batches ever: the row is gone. */
        data class Hard(override val product: Product) : Deleted

        /** Has history: hidden from every list, batches and statistics intact. */
        data class Soft(override val product: Product) : Deleted
    }

    fun delete(id: Long): Deleted? {
        val product = find(id)?.takeIf { it.deletedAt == null } ?: return null
        if (batches.countByProductId(id) == 0L) {
            products.deleteById(id) // subscriptions cascade
            return Deleted.Hard(product)
        }
        val now = clock.instant()
        return Deleted.Soft(products.save(product.copy(isActive = false, deletedAt = now, updatedAt = now)))
    }

    /**
     * Moves a product one step up (-1) or down (+1) in the button order. Orders are renumbered
     * 1..n on the way, so duplicates or gaps from earlier edits never make a swap ambiguous.
     */
    fun move(id: Long, delta: Int): Product? {
        val ordered = all().toMutableList()
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return null
        val target = index + delta
        if (target in ordered.indices) ordered[index] = ordered[target].also { ordered[target] = ordered[index] }
        val now = clock.instant()
        ordered.forEachIndexed { position, product ->
            if (product.sortOrder != position + 1) products.save(product.copy(sortOrder = position + 1, updatedAt = now))
        }
        return find(id)
    }

    private fun update(id: Long, change: (Product) -> Product): Product? =
        find(id)?.let { products.save(change(it).copy(updatedAt = clock.instant())) }

    sealed interface PriceInput {
        data class Value(val price: BigDecimal?) : PriceInput
        data object Invalid : PriceInput
    }

    companion object {
        /** "250", "250,50", "250.5" are prices; "", "-" and "нет" clear it; anything else is invalid. */
        fun parsePrice(text: String): PriceInput {
            val raw = text.trim()
            if (raw.isEmpty() || raw == "-" || raw.equals("нет", ignoreCase = true)) return PriceInput.Value(null)
            val value = raw.replace(',', '.').replace(" ", "").toBigDecimalOrNull() ?: return PriceInput.Invalid
            if (value < BigDecimal.ZERO || value.scale() > 2) return PriceInput.Invalid
            return PriceInput.Value(value.setScale(2, RoundingMode.HALF_UP))
        }
    }
}
