package dubrava.tandoor.core.product

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.ListCrudRepository
import java.math.BigDecimal
import java.time.Instant

/**
 * A product card. Buttons on the baker keyboard follow [sortOrder]; a hidden product keeps
 * its batches and statistics, it only disappears from keyboards and the catalogue.
 */
@Table("products")
data class Product(
    @Id val id: Long? = null,
    val name: String,
    val description: String? = null,
    val price: BigDecimal? = null,
    val photoFileId: String? = null,
    val sortOrder: Int = 0,
    val isActive: Boolean = true,
    /** Set by a soft delete; such a product is also inactive, so the keyboard queries need no change. */
    val deletedAt: Instant? = null,
    val updatedAt: Instant,
)

interface ProductRepository : ListCrudRepository<Product, Long> {

    fun findAllByIsActiveTrueOrderBySortOrder(): List<Product>

    /** Hidden products included, deleted ones not: the admin list. */
    fun findAllByDeletedAtIsNullOrderBySortOrder(): List<Product>

    /** Names are not unique by constraint; callers take the first active match. */
    fun findAllByNameAndIsActiveTrue(name: String): List<Product>
}
