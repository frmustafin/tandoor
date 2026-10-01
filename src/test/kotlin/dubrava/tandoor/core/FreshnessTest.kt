package dubrava.tandoor.core

import dubrava.tandoor.core.batch.BatchRepository
import dubrava.tandoor.core.batch.BatchService
import dubrava.tandoor.core.batch.BatchService.AnnounceResult
import dubrava.tandoor.core.catalogue.Freshness
import dubrava.tandoor.core.catalogue.FreshnessService
import dubrava.tandoor.core.product.Product
import dubrava.tandoor.core.product.ProductRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Freshness is derived from batches, so it is driven through the real service with a movable clock. */
@SpringBootTest
@Import(BatchServiceTest.Fakes::class)
class FreshnessTest {

    @Autowired lateinit var service: BatchService
    @Autowired lateinit var freshness: FreshnessService
    @Autowired lateinit var batches: BatchRepository
    @Autowired lateinit var products: ProductRepository
    @Autowired lateinit var clock: MutableClock

    private val baker = 1001L
    private lateinit var samsa: Product
    private lateinit var lepyoshka: Product

    @BeforeEach
    fun reset() {
        batches.deleteAll()
        clock.set(BatchServiceTest.START)
        val all = products.findAllByIsActiveTrueOrderBySortOrder()
        samsa = all[0]
        lepyoshka = all[2]
    }

    @Test
    fun `follows the latest batch through its life`() {
        assertEquals(Freshness.Never, freshness.of(samsa.id!!))

        val id = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker)).batch.id!!
        assertEquals(Freshness.Announced(BatchServiceTest.START + Duration.ofMinutes(15)), freshness.of(samsa.id!!))

        clock.advance(Duration.ofMinutes(12))
        assertNotNull(service.markReady(id))
        assertEquals(Freshness.Hot(clock.instant()), freshness.of(samsa.id!!))

        val readyAt = clock.instant()
        clock.advance(Duration.ofMinutes(20))
        assertNotNull(service.markSoldOut(id))
        assertEquals(Freshness.Last(readyAt), freshness.of(samsa.id!!), "the moment it became ready, not when it sold out")
    }

    @Test
    fun `a cancelled batch leaves no trace`() {
        val id = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker)).batch.id!!
        assertNotNull(service.cancel(id))
        assertEquals(Freshness.Never, freshness.of(samsa.id!!))
    }

    @Test
    fun `a hot tray wins over a newer announced one`() {
        val first = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker)).batch.id!!
        assertNotNull(service.markReady(first))
        val readyAt = clock.instant()
        clock.advance(Duration.ofMinutes(5))
        service.announce(samsa.id!!, baker, force = true)

        assertEquals(Freshness.Hot(readyAt), freshness.of(samsa.id!!))
    }

    @Test
    fun `catalogue puts hot products first, then announced, then the button order`() {
        service.announce(lepyoshka.id!!, baker)
        val hot = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker)).batch.id!!
        service.markReady(hot)
        val obychnaya = products.findAllByIsActiveTrueOrderBySortOrder()[4]
        service.announce(obychnaya.id!!, baker)

        val names = freshness.catalogue(products.findAllByIsActiveTrueOrderBySortOrder()).map { it.first.name }
        assertEquals(listOf("Самса из печи", "Лепёшка с сыром", "Обычная лепёшка", "Самса из тандыра", "Лепёшка с мясом"), names)
    }
}
