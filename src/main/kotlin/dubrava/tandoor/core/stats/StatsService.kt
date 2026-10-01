package dubrava.tandoor.core.stats

import dubrava.tandoor.core.batch.BatchStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The numbers from the plan, section 6. Rows are pulled for the period and grouped by bakery
 * day in Kotlin: at pilot scale that is a few hundred rows, and it keeps the SQL identical
 * on H2 and PostgreSQL. Only the channel size and the day's active-user count are snapshotted,
 * because they cannot be reconstructed later; everything else is derived from the tables.
 */
@Service
class StatsService(
    private val jdbc: JdbcClient,
    private val zone: ZoneId,
    private val channel: ChannelMembers,
    private val clock: Clock,
) {

    /** Stores today's channel and bot sizes; called hourly, so the last write of the day is the day's value. */
    fun snapshot(): DailyRow {
        val today = LocalDate.ofInstant(clock.instant(), zone)
        val users = users()
        val row = DailyRow(
            date = today,
            channelMembers = channel.count(),
            botActiveUsers = users.count { it.active },
            newUsers = users.count { it.firstSeen.toLocalDate() == today },
            leftUsers = users.count { it.blockedAt?.toLocalDate() == today },
            batches = 0, goingChannel = 0, goingPersonal = 0,
        )
        val updated = jdbc.sql(
            """
            UPDATE daily_stats SET channel_members = :channel, bot_active_users = :bot, new_users = :new, left_users = :left
            WHERE stat_date = :date
            """
        ).withRow(row).update()
        if (updated == 0) {
            jdbc.sql(
                """
                INSERT INTO daily_stats (stat_date, channel_members, bot_active_users, new_users, left_users)
                VALUES (:date, :channel, :bot, :new, :left)
                """
            ).withRow(row).update()
        }
        return row
    }

    fun report(periodDays: Int): Report {
        val today = LocalDate.ofInstant(clock.instant(), zone)
        val firstDay = today.minusDays(periodDays - 1L)
        val from = OffsetDateTime.ofInstant(firstDay.atStartOfDay(zone).toInstant(), ZoneOffset.UTC)

        val users = users()
        val batches = jdbc.sql(
            """
            SELECT b.announced_at, b.status, b.ready_at, b.ready_manually, p.name
            FROM batches b JOIN products p ON p.id = b.product_id
            WHERE b.announced_at >= :from
            """
        ).param("from", from).query { rs, _ ->
            BatchRow(
                day = rs.getObject("announced_at", OffsetDateTime::class.java).toInstant().toLocalDate(),
                status = BatchStatus.valueOf(rs.getString("status")),
                reachedReady = rs.getObject("ready_at") != null,
                readyManually = rs.getBoolean("ready_manually"),
                product = rs.getString("name"),
            )
        }.list()
        val going = jdbc.sql("SELECT clicked_at, source FROM going_clicks WHERE clicked_at >= :from")
            .param("from", from)
            .query { rs, _ -> rs.getObject("clicked_at", OffsetDateTime::class.java).toInstant().toLocalDate() to rs.getString("source") }
            .list()
        val snapshots = jdbc.sql("SELECT stat_date, channel_members, bot_active_users FROM daily_stats WHERE stat_date >= :from")
            .param("from", firstDay)
            .query { rs, _ ->
                rs.getObject("stat_date", LocalDate::class.java) to
                    (rs.getObject("channel_members") as Int? to rs.getObject("bot_active_users") as Int?)
            }
            .list().toMap()

        val days = (0 until periodDays).map { today.minusDays(it.toLong()) }.map { day ->
            // Today is live; past days come from the snapshot taken at the end of that day.
            val (channelMembers, botActive) =
                if (day == today) channel.count() to users.count { it.active } else snapshots[day] ?: (null to null)
            DailyRow(
                date = day,
                channelMembers = channelMembers,
                botActiveUsers = botActive,
                newUsers = users.count { it.firstSeen.toLocalDate() == day },
                leftUsers = users.count { it.blockedAt?.toLocalDate() == day },
                batches = batches.count { it.day == day },
                goingChannel = going.count { it.first == day && it.second == "channel" },
                goingPersonal = going.count { it.first == day && it.second != "channel" },
            )
        }
        return Report(
            periodDays = periodDays,
            days = days,
            sources = users.groupingBy { it.source }.eachCount(),
            batchesByProduct = batches.groupingBy { it.product }.eachCount().toList().sortedByDescending { it.second },
            readyManually = batches.count { it.reachedReady && it.readyManually },
            readyByTimer = batches.count { it.reachedReady && !it.readyManually },
            closedManually = batches.count { it.status == BatchStatus.SOLD_OUT || it.status == BatchStatus.CANCELLED },
            closedByTimer = batches.count { it.status == BatchStatus.EXPIRED },
            goingChannel = going.count { it.second == "channel" },
            goingPersonal = going.count { it.second != "channel" },
        )
    }

    private data class UserRow(val source: String, val firstSeen: Instant, val active: Boolean, val blockedAt: Instant?)

    private data class BatchRow(val day: LocalDate, val status: BatchStatus, val reachedReady: Boolean, val readyManually: Boolean, val product: String)

    private fun users(): List<UserRow> =
        jdbc.sql("SELECT source, first_seen_at, is_active, blocked_at FROM users").query { rs, _ ->
            UserRow(
                source = rs.getString("source"),
                firstSeen = rs.getObject("first_seen_at", OffsetDateTime::class.java).toInstant(),
                active = rs.getBoolean("is_active"),
                blockedAt = rs.getObject("blocked_at", OffsetDateTime::class.java)?.toInstant(),
            )
        }.list()

    private fun Instant.toLocalDate(): LocalDate = LocalDate.ofInstant(this, zone)

    /** Not `params`: the spec has a member of that name that binds bean properties instead. */
    private fun JdbcClient.StatementSpec.withRow(row: DailyRow) = this
        .param("date", row.date).param("channel", row.channelMembers).param("bot", row.botActiveUsers)
        .param("new", row.newUsers).param("left", row.leftUsers)
}
