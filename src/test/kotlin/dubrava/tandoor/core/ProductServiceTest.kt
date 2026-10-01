package dubrava.tandoor.core

import dubrava.tandoor.core.batch.Batch
import dubrava.tandoor.core.batch.BatchRepository
import dubrava.tandoor.core.batch.BatchStatus
import dubrava.tandoor.core.product.ProductService
import dubrava.tandoor.core.product.ProductService.Deleted
import dubrava.tandoor.core.product.ProductService.PriceInput
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
class ProductServiceTest {

    @Autowired lateinit var products: ProductService
    @Autowired lateinit var batches: BatchRepository
    @Autowired lateinit var jdbc: JdbcClient

    private val seed = listOf("Самса из печи", "Самса из тандыра", "Лепёшка с сыром", "Лепёшка с мясом", "Обычная лепёшка")

    /** Other test classes rely on the seeded five, so every change here is rolled back by hand. */
    @AfterEach
    fun restoreSeed() {
        jdbc.sql("DELETE FROM batches WHERE created_by = 777").update()
        jdbc.sql("DELETE FROM products WHERE id > 5").update()
        seed.forEachIndexed { index, name ->
            jdbc.sql(
                """
                UPDATE products SET name = :name, sort_order = :order, is_active = TRUE, deleted_at = NULL,
                    price = NULL, description = NULL, photo_file_id = NULL
                WHERE id = :id
                """
            ).param("name", name).param("order", index + 1).param("id", index + 1L).update()
        }
    }

    @Test
    fun `a new product goes to the end of the keyboard`() {
        val created = products.create("  Чай  ")
        assertEquals("Чай", created.name)
        assertEquals(6, created.sortOrder)
        assertEquals(seed + "Чай", products.active().map { it.name })
    }

    @Test
    fun `card fields are edited one at a time`() {
        val id = products.active().first().id!!
        assertEquals("Самса", products.rename(id, "Самса")!!.name)
        assertEquals("Из печи", products.describe(id, " Из печи ")!!.description)
        assertNull(products.describe(id, "  ")!!.description, "blank clears the description")
        assertEquals(BigDecimal("250.00"), products.price(id, BigDecimal("250.00"))!!.price)
        assertEquals("photo-1", products.photo(id, "photo-1")!!.photoFileId)
        assertNull(products.rename(999_999, "x"))
    }

    @Test
    fun `hiding removes a product from the keyboard but keeps it in the admin list`() {
        val id = products.active()[1].id!!
        assertNotNull(products.setActive(id, false))
        assertEquals(seed - "Самса из тандыра", products.active().map { it.name })
        assertEquals(seed, products.all().map { it.name })
    }

    @Test
    fun `moving swaps neighbours and renumbers, edges stay put`() {
        val second = products.all()[1].id!!
        products.move(second, -1)
        assertEquals(listOf("Самса из тандыра", "Самса из печи", "Лепёшка с сыром", "Лепёшка с мясом", "Обычная лепёшка"), products.all().map { it.name })
        assertEquals(listOf(1, 2, 3, 4, 5), products.all().map { it.sortOrder })

        products.move(second, -1)
        assertEquals("Самса из тандыра", products.all().first().name, "already first")

        val last = products.all().last().id!!
        products.move(last, 1)
        assertEquals("Обычная лепёшка", products.all().last().name, "already last")
    }

    @Test
    fun `a product nobody baked yet is deleted outright`() {
        val id = products.create("Ошибка").id!!

        assertIs<Deleted.Hard>(products.delete(id))
        assertNull(products.find(id))
        assertEquals(seed, products.all().map { it.name })
        assertNull(products.delete(id), "already gone")
    }

    @Test
    fun `a product with history only leaves the lists`() {
        val id = products.active()[2].id!!
        val now = Instant.parse("2026-10-01T09:00:00Z")
        batches.save(Batch(productId = id, status = BatchStatus.EXPIRED, announcedAt = now, expectedReadyAt = now, readyAt = now, closedAt = now, createdBy = 777))

        val deleted = assertIs<Deleted.Soft>(products.delete(id))

        assertNotNull(deleted.product.deletedAt)
        assertFalse(deleted.product.isActive)
        assertEquals(seed - "Лепёшка с сыром", products.all().map { it.name }, "gone from the admin list")
        assertEquals(seed - "Лепёшка с сыром", products.active().map { it.name }, "gone from the keyboard")
        assertNotNull(products.find(id), "still resolvable for the batches that refer to it")
        assertEquals(1, batches.countByProductId(id))
        assertNull(products.delete(id), "deleting twice changes nothing")
    }

    @Test
    fun `price input`() {
        assertEquals(PriceInput.Value(BigDecimal("250.00")), ProductService.parsePrice("250"))
        assertEquals(PriceInput.Value(BigDecimal("250.50")), ProductService.parsePrice(" 250,5 "))
        assertEquals(PriceInput.Value(BigDecimal("1200.00")), ProductService.parsePrice("1 200"))
        assertEquals(PriceInput.Value(null), ProductService.parsePrice(""))
        assertEquals(PriceInput.Value(null), ProductService.parsePrice("-"))
        assertEquals(PriceInput.Value(null), ProductService.parsePrice("нет"))
        assertEquals(PriceInput.Invalid, ProductService.parsePrice("дорого"))
        assertEquals(PriceInput.Invalid, ProductService.parsePrice("-5"))
        assertEquals(PriceInput.Invalid, ProductService.parsePrice("1.005"))
    }
}
