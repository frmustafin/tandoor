package dubrava.tandoor.core.stats

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Hourly at :58, so the 23:58 run in the bakery zone is the day's figure whatever zone the
 * server runs in; earlier runs just make a restart or a late outage lose less.
 */
@Component
@ConditionalOnProperty("bakery.timers-enabled", havingValue = "true", matchIfMissing = true)
class DailySnapshot(private val stats: StatsService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 58 * * * *")
    fun take() {
        try {
            val row = stats.snapshot()
            log.info("daily stats {}: channel {}, bot {}", row.date, row.channelMembers, row.botActiveUsers)
        } catch (e: Exception) {
            log.error("daily snapshot failed", e)
        }
    }
}
