package dubrava.tandoor.core

import dubrava.tandoor.core.catalogue.CatalogueTexts
import dubrava.tandoor.core.catalogue.Freshness
import dubrava.tandoor.core.product.Product
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CatalogueTextsTest {

    private val texts = CatalogueTexts(ZoneId.of("Europe/Moscow"))
    private val now = Instant.parse("2026-10-01T10:00:00Z") // 13:00 Moscow, 1 October
    private val readyToday = Instant.parse("2026-10-01T09:40:00Z")
    private val readyYesterday = Instant.parse("2026-09-30T15:05:00Z")
    private val readyEarlier = Instant.parse("2026-09-20T15:05:00Z")
    private val readyLastYear = Instant.parse("2025-12-31T21:30:00Z") // 00:30 on 1 Jan 2026 in Moscow

    private val samsa = Product(id = 2, name = "Самса из тандыра", price = BigDecimal("250.00"), description = "Говядина, лук, тесто на кефире", updatedAt = Instant.EPOCH)
    private val plain = Product(id = 5, name = "Обычная лепёшка", updatedAt = Instant.EPOCH)

    @Test
    fun `freshness is status and time, in the words of the batch messages`() {
        assertEquals("Готовим · к 12:55", texts.freshness(Freshness.Announced(Instant.parse("2026-10-01T09:55:00Z")), now))
        assertEquals("Готово · сегодня в 12:40", texts.freshness(Freshness.Hot(readyToday), now))
        assertEquals("Было готово · сегодня в 12:40", texts.freshness(Freshness.Last(readyToday), now))
        assertEquals("Было готово · вчера в 18:05", texts.freshness(Freshness.Last(readyYesterday), now))
        assertEquals("Было готово · 20 сентября в 18:05", texts.freshness(Freshness.Last(readyEarlier), now))
        assertEquals("Партий ещё не было", texts.freshness(Freshness.Never, now))
    }

    @Test
    fun `the year is shown only when it differs`() {
        assertEquals("1 января", texts.day(readyLastYear, now), "00:30 Moscow on 1 January 2026 is this year")
        assertEquals("1 января 2026", texts.day(readyLastYear, Instant.parse("2027-03-01T10:00:00Z")))
    }

    @Test
    fun `list line and card, with and without a price`() {
        assertEquals("Самса из тандыра · 250 ₽ · Готово · сегодня в 12:40", texts.listLine(samsa, Freshness.Hot(readyToday), now))
        assertEquals("Обычная лепёшка · Партий ещё не было", texts.listLine(plain, Freshness.Never, now))

        assertEquals(
            "Самса из тандыра · 250 ₽\n\nГовядина, лук, тесто на кефире\n\nГотово · сегодня в 12:40",
            texts.card(samsa, Freshness.Hot(readyToday), now),
        )
        assertEquals("Обычная лепёшка\n\nПартий ещё не было", texts.card(plain, Freshness.Never, now))
    }

    @Test
    fun `prices drop trailing zeros and use a comma`() {
        assertEquals("250 ₽", CatalogueTexts.price(BigDecimal("250.00")))
        assertEquals("250,5 ₽", CatalogueTexts.price(BigDecimal("250.50")))
        assertEquals("1200 ₽", CatalogueTexts.price(BigDecimal("1200")))
        assertNull(CatalogueTexts.price(null))
    }
}
