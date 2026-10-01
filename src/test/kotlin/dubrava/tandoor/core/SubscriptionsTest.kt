package dubrava.tandoor.core

import dubrava.tandoor.core.product.Product
import dubrava.tandoor.core.product.ProductRepository
import dubrava.tandoor.core.subscription.SubscriptionService
import dubrava.tandoor.core.user.Source
import dubrava.tandoor.core.user.UserService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
class SubscriptionsTest {

    @Autowired lateinit var users: UserService
    @Autowired lateinit var subscriptions: SubscriptionService
    @Autowired lateinit var products: ProductRepository
    @Autowired lateinit var jdbc: JdbcClient

    private lateinit var samsa: Product
    private lateinit var lepyoshka: Product

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM users").update() // cascades to subscriptions
        val all = products.findAllByIsActiveTrueOrderBySortOrder()
        samsa = all[0]
        lepyoshka = all[2]
    }

    @Test
    fun `first contact keeps its source, later contacts do not overwrite it`() {
        val created = users.touch(7, Source.QR)
        assertEquals(Source.QR, created.source)
        assertTrue(created.isActive)

        assertEquals(Source.QR, users.touch(7, Source.CHANNEL).source)
    }

    @Test
    fun `a blocked user is reactivated by coming back`() {
        users.touch(7, Source.DIRECT)
        users.markBlocked(7)
        assertFalse(users.find(7)!!.isActive)
        assertNotNull(users.find(7)!!.blockedAt)

        val back = users.touch(7, Source.DIRECT)
        assertTrue(back.isActive)
        assertNull(back.blockedAt)
    }

    @Test
    fun `toggle flips one product`() {
        users.touch(7, Source.DIRECT)

        assertTrue(subscriptions.toggle(7, samsa.id!!))
        assertEquals(setOf(samsa.id), subscriptions.productIdsOf(7))

        assertFalse(subscriptions.toggle(7, samsa.id!!))
        assertEquals(emptySet(), subscriptions.productIdsOf(7))
    }

    @Test
    fun `subscribe to all is idempotent and unsubscribe clears everything`() {
        users.touch(7, Source.DIRECT)
        subscriptions.toggle(7, samsa.id!!)
        val ids = products.findAllByIsActiveTrueOrderBySortOrder().map { it.id!! }

        subscriptions.subscribeAll(7, ids)
        subscriptions.subscribeAll(7, ids)
        assertEquals(ids.toSet(), subscriptions.productIdsOf(7))

        assertEquals(ids.size, subscriptions.unsubscribeAll(7))
        assertEquals(emptySet(), subscriptions.productIdsOf(7))
    }

    @Test
    fun `subscribers of a product exclude those who blocked the bot`() {
        for (id in listOf(1L, 2L, 3L)) {
            users.touch(id, Source.DIRECT)
            subscriptions.toggle(id, samsa.id!!)
        }
        subscriptions.toggle(3, lepyoshka.id!!)
        users.markBlocked(2)

        assertEquals(listOf(1L, 3L), subscriptions.subscriberIds(samsa.id!!))
        assertEquals(listOf(3L), subscriptions.subscriberIds(lepyoshka.id!!))
    }
}
