package dubrava.tandoor.core.catalogue

import dubrava.tandoor.core.product.Product
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Catalogue wording from the plan, sections 4.4 and 5. Times and days are in the bakery zone. */
class CatalogueTexts(private val zone: ZoneId) {

    private val clock = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * "status · time", with the status words of the batch messages ([dubrava.tandoor.core.batch.BatchTexts]).
     * "Готово" is kept for a batch that is still hot; afterwards it is "Было готово", so a
     * morning batch does not read as ready in the evening.
     */
    fun freshness(freshness: Freshness, now: Instant): String = when (freshness) {
        is Freshness.Announced -> "Готовим · к ${time(freshness.expectedReadyAt)}"
        is Freshness.Hot -> "Готово · ${day(freshness.readyAt, now)} в ${time(freshness.readyAt)}"
        is Freshness.Last -> "Было готово · ${day(freshness.readyAt, now)} в ${time(freshness.readyAt)}"
        Freshness.Never -> "Партий ещё не было"
    }

    /** "{название} · {цена} ₽ · {статус свежести}"; the price part is dropped when there is none. */
    fun listLine(product: Product, freshness: Freshness, now: Instant): String =
        listOfNotNull(product.name, price(product.price), freshness(freshness, now)).joinToString(" · ")

    /** "{название} · {цена} ₽", then the description, then the freshness status. */
    fun card(product: Product, freshness: Freshness, now: Instant): String =
        listOfNotNull(
            listOfNotNull(product.name, price(product.price)).joinToString(" · "),
            product.description,
            freshness(freshness, now),
        ).joinToString("\n\n")

    fun time(at: Instant): String = clock.format(at.atZone(zone))

    /** "сегодня", "вчера", "30 сентября", or "30 сентября 2025" once the year differs. */
    fun day(at: Instant, now: Instant): String {
        val date = at.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (date) {
            today -> "сегодня"
            today.minusDays(1) -> "вчера"
            else -> "${date.dayOfMonth} ${MONTHS[date.monthValue - 1]}" + if (date.year != today.year) " ${date.year}" else ""
        }
    }

    companion object {
        private val MONTHS = listOf(
            "января", "февраля", "марта", "апреля", "мая", "июня",
            "июля", "августа", "сентября", "октября", "ноября", "декабря",
        )

        /** 250.00 → "250 ₽", 250.50 → "250,50 ₽"; null → null. */
        fun price(price: BigDecimal?): String? = price?.let {
            val plain = it.stripTrailingZeros().toPlainString().replace('.', ',')
            "$plain ₽"
        }
    }
}
