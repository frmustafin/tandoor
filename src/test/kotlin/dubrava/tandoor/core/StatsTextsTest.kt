package dubrava.tandoor.core

import dubrava.tandoor.core.stats.DailyRow
import dubrava.tandoor.core.stats.Report
import dubrava.tandoor.core.stats.StatsTexts
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StatsTextsTest {

    private val report = Report(
        periodDays = 2,
        days = listOf(
            DailyRow(LocalDate.of(2026, 10, 1), channelMembers = 123, botActiveUsers = 45, newUsers = 3, leftUsers = 0, batches = 7, goingChannel = 2, goingPersonal = 5),
            DailyRow(LocalDate.of(2026, 9, 30), channelMembers = null, botActiveUsers = null, newUsers = 0, leftUsers = 1, batches = 0, goingChannel = 0, goingPersonal = 0),
        ),
        sources = mapOf("qr" to 10, "channel" to 5),
        batchesByProduct = listOf("Самса <острая> & Co" to 4, "Лепёшка" to 3),
        readyManually = 5, readyByTimer = 2, closedManually = 4, closedByTimer = 3,
        goingChannel = 2, goingPersonal = 5,
    )

    private val text = StatsTexts.render(report)

    @Test
    fun `table rows line up and missing snapshots show a dash`() {
        assertTrue(text.contains("<pre>дата канал  бот  +/- парт   иду\n"), text)
        assertTrue(text.contains("01.10   123   45  3/0    7   2/5\n"), text)
        assertTrue(text.contains("30.09     -    -  0/1    0   0/0</pre>"), text)
    }

    @Test
    fun `no stray indentation and the table fits a phone`() {
        assertTrue(text.lines().none { it.startsWith(" ") }, text)
        val rows = text.lines().filter { it.firstOrNull()?.isDigit() == true }
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.removeSuffix("</pre>").length == 32 }, rows.toString())
    }

    @Test
    fun `every column is explained on its own line`() {
        val legend = text.substringAfter("</pre>\n").substringBefore("\n\n").lines()
        assertEquals(listOf("<b>канал</b>", "<b>бот</b>", "<b>+/-</b>", "<b>парт</b>", "<b>иду</b>"), legend.map { it.substringBefore(" — ") })
    }

    @Test
    fun `summaries, with product names escaped for HTML`() {
        assertTrue(text.contains("<b>Источники прихода в бота:</b> QR 10 · канал 5 · прочее 0"), text)
        assertTrue(text.contains("Самса &lt;острая&gt; &amp; Co 4, Лепёшка 3"), text)
        assertTrue(text.contains("«Готово» вручную 5, по таймеру 2 · закрыто вручную 4, остыло по таймеру 3"), text)
        assertTrue(text.contains("<b>«Иду!»:</b> из канала 2, из лички 5"), text)
    }

    @Test
    fun `escape`() {
        assertEquals("a &amp;&lt;b&gt;", StatsTexts.escape("a &<b>"))
    }
}
