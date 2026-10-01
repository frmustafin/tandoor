package dubrava.tandoor.core.stats

import java.time.LocalDate

/** How many people follow the channel; the only number that lives outside the database. */
fun interface ChannelMembers {
    /** Null when the channel is not configured or Telegram did not answer. */
    fun count(): Int?
}

/** One line of the by-day table (plan, section 6). Null counts mean "no snapshot for that day". */
data class DailyRow(
    val date: LocalDate,
    val channelMembers: Int?,
    val botActiveUsers: Int?,
    val newUsers: Int,
    val leftUsers: Int,
    val batches: Int,
    val goingChannel: Int,
    val goingPersonal: Int,
)

data class Report(
    val periodDays: Int,
    /** Newest first. */
    val days: List<DailyRow>,
    /** All-time: where bot users came from, by `users.source`. */
    val sources: Map<String, Int>,
    /** Batches in the period by product name, most first. */
    val batchesByProduct: List<Pair<String, Int>>,
    val readyManually: Int,
    val readyByTimer: Int,
    val closedManually: Int,
    val closedByTimer: Int,
    val goingChannel: Int,
    val goingPersonal: Int,
)
