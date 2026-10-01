package dubrava.tandoor.core

import dubrava.tandoor.core.batch.Batch
import dubrava.tandoor.core.batch.BatchNotifier
import dubrava.tandoor.core.batch.BatchRepository
import dubrava.tandoor.core.batch.BatchService
import dubrava.tandoor.core.batch.BatchService.AnnounceResult
import dubrava.tandoor.core.batch.BatchStatus
import dubrava.tandoor.core.product.Product
import dubrava.tandoor.core.product.ProductRepository
import dubrava.tandoor.core.stats.ChannelMembers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The lifecycle against the real schema on H2, with time and the messenger replaced by fakes. */
@SpringBootTest
class BatchServiceTest {

    @TestConfiguration
    class Fakes {
        @Bean @Primary fun testClock(): MutableClock = MutableClock(START)
        @Bean @Primary fun testNotifier(): RecordingNotifier = RecordingNotifier()
        @Bean @Primary fun testChannelMembers(): FakeChannelMembers = FakeChannelMembers()
    }

    @Autowired lateinit var service: BatchService
    @Autowired lateinit var batches: BatchRepository
    @Autowired lateinit var products: ProductRepository
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var notifier: RecordingNotifier

    private val baker = 1001L
    private lateinit var samsa: Product

    @BeforeEach
    fun reset() {
        batches.deleteAll()
        clock.set(START)
        notifier.reset()
        samsa = products.findAllByIsActiveTrueOrderBySortOrder().first()
    }

    @Test
    fun `one tap creates an announced batch and keeps where the messages went`() {
        val result = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker))

        val batch = result.batch
        assertEquals(BatchStatus.ANNOUNCED, batch.status)
        assertEquals(START, batch.announcedAt)
        assertEquals(START + Duration.ofMinutes(15), batch.expectedReadyAt, "the default from settings")
        assertEquals(baker, batch.createdBy)
        assertEquals(500L, batch.channelMessageId)
        assertEquals(baker, batch.controlChatId)
        assertEquals(600L, batch.controlMessageId)
        assertEquals(listOf(batch.id), notifier.announced.map { it.id })
        assertEquals(batch, batches.findById(batch.id!!).orElseThrow())
    }

    @Test
    fun `a second tap shows the active batch instead of creating a duplicate`() {
        val first = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker)).batch

        val again = assertIs<AnnounceResult.AlreadyActive>(service.announce(samsa.id!!, baker))
        assertEquals(first.id, again.batch.id)
        assertEquals(1, batches.count())

        val forced = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker, force = true)).batch
        assertNotEquals(first.id, forced.id)
        assertEquals(2, batches.count())
    }

    @Test
    fun `unknown product is rejected`() {
        assertNull(service.announce(999_999, baker))
    }

    @Test
    fun `ready by tap, then sold out, each transition applied once`() {
        val id = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker)).batch.id!!
        clock.advance(Duration.ofMinutes(3))

        val ready = assertNotNull(service.markReady(id))
        assertEquals(BatchStatus.READY, ready.status)
        assertEquals(START + Duration.ofMinutes(3), ready.readyAt)
        assertTrue(ready.readyManually)
        assertNull(service.markReady(id), "READY cannot be applied twice")

        val sold = assertNotNull(service.markSoldOut(id))
        assertEquals(BatchStatus.SOLD_OUT, sold.status)
        assertTrue(sold.closedManually)
        assertNull(service.markSoldOut(id))

        assertEquals(listOf(BatchStatus.READY, BatchStatus.SOLD_OUT), notifier.changed.map { it.status })
    }

    @Test
    fun `timers move announced to ready and ready to expired`() {
        val id = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker)).batch.id!!

        clock.advance(Duration.ofMinutes(14))
        assertEquals(0, service.advanceTimers())
        assertEquals(BatchStatus.ANNOUNCED, batches.findById(id).orElseThrow().status)

        clock.advance(Duration.ofMinutes(1).plusSeconds(1))
        assertEquals(1, service.advanceTimers())
        val ready = batches.findById(id).orElseThrow()
        assertEquals(BatchStatus.READY, ready.status)
        assertFalse(ready.readyManually, "the timer, not the baker, made it ready")
        assertEquals(clock.instant(), ready.readyAt)

        clock.advance(Duration.ofMinutes(29))
        assertEquals(0, service.advanceTimers())

        clock.advance(Duration.ofMinutes(1).plusSeconds(1))
        assertEquals(1, service.advanceTimers())
        val expired = batches.findById(id).orElseThrow()
        assertEquals(BatchStatus.EXPIRED, expired.status)
        assertEquals(clock.instant(), expired.closedAt)
        assertFalse(expired.closedManually)

        assertEquals(0, service.advanceTimers(), "nothing left to move")
    }

    @Test
    fun `cancel is allowed only while announced`() {
        val id = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker)).batch.id!!
        assertNotNull(service.markReady(id))
        assertNull(service.cancel(id), "a batch people were told is ready cannot be cancelled")

        val fresh = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker, force = true)).batch.id!!
        val cancelled = assertNotNull(service.cancel(fresh))
        assertEquals(BatchStatus.CANCELLED, cancelled.status)
        assertNull(batches.findActiveByProduct(samsa.id!!)?.takeIf { it.id == fresh })
    }

    @Test
    fun `a failing notifier does not lose the batch`() {
        notifier.failNext = true

        val result = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker))

        assertEquals(BatchStatus.ANNOUNCED, result.batch.status)
        assertNull(result.batch.channelMessageId)
        assertNull(result.batch.controlMessageId)
    }

    @Test
    fun `the baker moves the ready time, the timer follows it`() {
        val id = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker)).batch.id!!

        assertEquals(START + Duration.ofMinutes(20), assertNotNull(service.setReadyIn(id, Duration.ofMinutes(20))).expectedReadyAt)
        assertEquals(START + Duration.ofMinutes(15), assertNotNull(service.shiftReadyTime(id, Duration.ofMinutes(-5))).expectedReadyAt)
        assertEquals(
            START + BatchService.MIN_LEAD,
            assertNotNull(service.shiftReadyTime(id, Duration.ofMinutes(-30))).expectedReadyAt,
            "a promise in the past is clamped to the minimum lead",
        )
        assertEquals(3, notifier.changed.size, "every move redraws the messages")

        assertEquals(0, service.advanceTimers(), "not yet")
        clock.advance(BatchService.MIN_LEAD)
        assertEquals(1, service.advanceTimers())
        assertEquals(BatchStatus.READY, batches.findById(id).orElseThrow().status)

        assertNull(service.setReadyIn(id, Duration.ofMinutes(10)), "a ready batch has no promise to move")
        assertNull(service.shiftReadyTime(id, Duration.ofMinutes(5)))
    }

    @Test
    fun `ready and sold out act on every tray of the product`() {
        val first = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker)).batch.id!!
        clock.advance(Duration.ofMinutes(5))
        val second = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker, force = true)).batch.id!!
        val other = products.findAllByIsActiveTrueOrderBySortOrder()[1]
        val otherProduct = assertIs<AnnounceResult.Created>(service.announce(other.id!!, baker)).batch.id!!

        assertEquals(listOf(first, second), service.markProductReady(samsa.id!!).map { it.id })
        assertEquals(BatchStatus.READY, batches.findById(second).orElseThrow().status)
        assertEquals(BatchStatus.ANNOUNCED, batches.findById(otherProduct).orElseThrow().status, "another product is untouched")
        assertEquals(emptyList(), service.markProductReady(samsa.id!!), "nothing left to make ready")

        assertEquals(listOf(first, second), service.markProductSoldOut(samsa.id!!).map { it.id })
        assertEquals(BatchStatus.SOLD_OUT, batches.findById(first).orElseThrow().status)
        assertEquals(emptyList(), batches.findAllActiveByProduct(samsa.id!!))
        assertEquals(4, notifier.changed.size, "two trays, two transitions each")
    }

    @Test
    fun `going taps count once per person and only while the batch is active`() {
        val id = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker)).batch.id!!

        assertEquals(BatchService.GoingResult.RECORDED, service.recordGoing(id, 42, "dm"))
        assertEquals(BatchService.GoingResult.ALREADY, service.recordGoing(id, 42, "channel"))
        assertEquals(BatchService.GoingResult.RECORDED, service.recordGoing(id, 43, "channel"))
        assertEquals(2, service.goingCount(id))
        assertEquals(2, notifier.going.size, "the counter is redrawn once per new tap")

        assertNotNull(service.markSoldOut(id))
        assertEquals(BatchService.GoingResult.CLOSED, service.recordGoing(id, 44, "dm"))
        assertEquals(BatchService.GoingResult.CLOSED, service.recordGoing(999_999, 44, "dm"))
    }

    companion object {
        val START: Instant = Instant.parse("2026-10-01T09:00:00Z")
    }
}

class MutableClock(private var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = now
    fun set(at: Instant) { now = at }
    fun advance(by: Duration) { now += by }
}

class RecordingNotifier : BatchNotifier {
    val announced = mutableListOf<Batch>()
    val changed = mutableListOf<Batch>()
    val going = mutableListOf<Batch>()
    var failNext = false

    override fun announced(batch: Batch, product: Product): BatchNotifier.MessageRefs {
        if (failNext) { failNext = false; error("telegram is down") }
        announced += batch
        return BatchNotifier.MessageRefs(channelMessageId = 500, controlChatId = batch.createdBy, controlMessageId = 600)
    }

    override fun changed(batch: Batch, product: Product) { changed += batch }

    override fun goingChanged(batch: Batch, product: Product) { going += batch }

    fun reset() { announced.clear(); changed.clear(); going.clear(); failNext = false }
}

class FakeChannelMembers : ChannelMembers {
    var members: Int? = 123

    override fun count(): Int? = members
}
