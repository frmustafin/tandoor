package dubrava.tandoor.core

import dubrava.tandoor.core.batch.BatchRepository
import dubrava.tandoor.core.batch.BatchService
import dubrava.tandoor.core.batch.BatchService.AnnounceResult
import dubrava.tandoor.core.product.ProductRepository
import dubrava.tandoor.core.stats.StatsService
import dubrava.tandoor.core.subscription.SubscriptionService
import dubrava.tandoor.core.user.Source
import dubrava.tandoor.core.user.UserService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The report is derived from real rows written through the real services, with a movable clock. */
@SpringBootTest
@Import(BatchServiceTest.Fakes::class)
class StatsServiceTest {

    @Autowired lateinit var stats: StatsService
    @Autowired lateinit var service: BatchService
    @Autowired lateinit var users: UserService
    @Autowired lateinit var subscriptions: SubscriptionService
    @Autowired lateinit var batches: BatchRepository
    @Autowired lateinit var products: ProductRepository
    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var channel: FakeChannelMembers
    @Autowired lateinit var jdbc: JdbcClient

    private val baker = 1001L
    private val today = LocalDate.of(2026, 10, 1) // START is 12:00 Moscow on this day

    @BeforeEach
    fun reset() {
        batches.deleteAll()
        jdbc.sql("DELETE FROM users").update()
        jdbc.sql("DELETE FROM daily_stats").update()
        clock.set(BatchServiceTest.START)
        channel.members = 123
    }

    @Test
    fun `a day of the pilot, summarised`() {
        val samsa = products.findAllByIsActiveTrueOrderBySortOrder()[0]
        val lepyoshka = products.findAllByIsActiveTrueOrderBySortOrder()[2]
        users.touch(11, Source.QR); users.touch(12, Source.QR); users.touch(13, Source.CHANNEL); users.touch(14, Source.DIRECT)
        users.markBlocked(14)

        val manual = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker)).batch.id!!
        assertNotNull(service.markReady(manual))
        service.recordGoing(manual, 11, "dm")
        service.recordGoing(manual, 12, "channel")
        service.recordGoing(manual, 13, "channel")
        assertNotNull(service.markSoldOut(manual))
        val byTimer = assertIs<AnnounceResult.Created>(service.announce(samsa.id!!, baker, force = true)).batch.id!!
        clock.advance(Duration.ofMinutes(16))
        assertEquals(1, service.advanceTimers())
        clock.advance(Duration.ofMinutes(31))
        assertEquals(1, service.advanceTimers(), "$byTimer expired")
        assertNotNull(service.cancel(assertIs<AnnounceResult.Created>(service.announce(lepyoshka.id!!, baker)).batch.id!!))

        val report = stats.report(3)

        assertEquals(3, report.days.size)
        val row = report.days.first()
        assertEquals(today, row.date)
        assertEquals(123, row.channelMembers, "today is live")
        assertEquals(3, row.botActiveUsers)
        assertEquals(4, row.newUsers)
        assertEquals(1, row.leftUsers)
        assertEquals(3, row.batches)
        assertEquals(2, row.goingChannel)
        assertEquals(1, row.goingPersonal)
        assertNull(report.days[1].channelMembers, "no snapshot for yesterday")
        assertEquals(0, report.days[1].batches)

        assertEquals(mapOf("qr" to 2, "channel" to 1, "direct" to 1), report.sources)
        assertEquals(listOf("Самса из печи" to 2, "Лепёшка с сыром" to 1), report.batchesByProduct)
        assertEquals(1, report.readyManually)
        assertEquals(1, report.readyByTimer)
        assertEquals(2, report.closedManually, "sold out and cancelled")
        assertEquals(1, report.closedByTimer)
        assertEquals(2, report.goingChannel)
        assertEquals(1, report.goingPersonal)
    }

    @Test
    fun `the snapshot keeps the end-of-day sizes for later reports`() {
        users.touch(11, Source.QR)
        val row = stats.snapshot()
        assertEquals(today, row.date)
        assertEquals(123, row.channelMembers)
        assertEquals(1, row.botActiveUsers)

        channel.members = 150
        users.touch(12, Source.QR)
        assertEquals(150, stats.snapshot().channelMembers, "the second run of the day overwrites")
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM daily_stats").query(Int::class.java).single())

        clock.advance(Duration.ofDays(1))
        channel.members = null
        val report = stats.report(2)
        assertEquals(150 to 2, report.days[1].channelMembers to report.days[1].botActiveUsers, "yesterday from the snapshot")
        assertNull(report.days[0].channelMembers, "today: Telegram did not answer")
    }
}
