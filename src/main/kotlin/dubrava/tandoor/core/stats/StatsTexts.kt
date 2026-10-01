package dubrava.tandoor.core.stats

import java.time.format.DateTimeFormatter

/**
 * Renders a [Report] as Telegram HTML. The table is kept to 32 ASCII-separated columns so it
 * survives a phone screen inside `<pre>`, and the message is assembled line by line: a raw
 * string with trimIndent would keep its indentation once the unindented table is pasted in.
 */
object StatsTexts {

    private val day = DateTimeFormatter.ofPattern("dd.MM")

    fun render(report: Report): String {
        val table = buildString {
            append(line("дата", "канал", "бот", "+/-", "парт", "иду"))
            for (row in report.days) {
                append(
                    line(
                        day.format(row.date),
                        num(row.channelMembers),
                        num(row.botActiveUsers),
                        "${row.newUsers}/${row.leftUsers}",
                        row.batches.toString(),
                        "${row.goingChannel}/${row.goingPersonal}",
                    )
                )
            }
        }.trimEnd()

        val sources = listOf("qr" to "QR", "channel" to "канал", "direct" to "прочее")
            .joinToString(" · ") { (key, label) -> "$label ${report.sources[key] ?: 0}" }
        val byProduct = report.batchesByProduct.joinToString(", ") { (name, count) -> "${escape(name)} $count" }
            .ifEmpty { "—" }

        return listOf(
            "<b>Статистика за ${report.periodDays} дн.</b>",
            "",
            "<pre>$table</pre>",
            legend,
            "",
            "<b>Источники прихода в бота:</b> $sources",
            "<b>Партии по позициям:</b> $byProduct",
            "<b>Дисциплина пекаря:</b> «Готово» вручную ${report.readyManually}, по таймеру ${report.readyByTimer} · закрыто вручную ${report.closedManually}, остыло по таймеру ${report.closedByTimer}",
            "<b>«Иду!»:</b> из канала ${report.goingChannel}, из лички ${report.goingPersonal}",
        ).joinToString("\n")
    }

    /** One table line; the header goes through the same widths, so it cannot drift from the rows. */
    private fun line(date: String, channel: String, bot: String, delta: String, batches: String, going: String) =
        "$date ${channel.padStart(5)} ${bot.padStart(4)} ${delta.padStart(4)} ${batches.padStart(4)} ${going.padStart(5)}\n"

    /** One column per line: run together in a sentence, the descriptions were unreadable on a phone. */
    private val legend = listOf(
        "<b>канал</b> — подписчики канала на конец дня (сегодня — сейчас)",
        "<b>бот</b> — активные подписчики бота на конец дня (сегодня — сейчас)",
        "<b>+/-</b> — пришли в бота / заблокировали его за день",
        "<b>парт</b> — партий за день",
        "<b>иду</b> — «Иду!» из канала / из лички",
    ).joinToString("\n")

    private fun num(value: Int?) = value?.toString() ?: "-"

    /** Product names are typed by the admin; they must not break the HTML. */
    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
