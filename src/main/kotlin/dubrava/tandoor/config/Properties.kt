package dubrava.tandoor.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneId

/**
 * Bakery-wide defaults. The admin can override the timers and the auto-post switch at
 * runtime through the `settings` table, so these values are only the fallback.
 */
@ConfigurationProperties("bakery")
data class BakeryProperties(
    val timezone: ZoneId = ZoneId.of("Europe/Moscow"),
    val readyAfter: Duration = Duration.ofMinutes(15),
    val hotFor: Duration = Duration.ofMinutes(30),
    val channelAutoPosts: Boolean = true,
    /** Telegram IDs that are admins from day one; later roles are managed in the bot. */
    val bootstrapAdmins: List<Long> = emptyList(),
    /** Off in tests, where a scheduled tick would race the assertions. */
    val timersEnabled: Boolean = true,
)

/**
 * Telegram transport settings. The token never belongs in a committed file: it is read from
 * the BOT_TOKEN environment variable.
 */
@ConfigurationProperties("telegram")
data class TelegramProperties(
    val token: String,
    val channelId: String = "",
    val apiBaseUrl: String = "https://api.telegram.org",
    val pollTimeout: Duration = Duration.ofSeconds(30),
    val sendRatePerSecond: Int = 25,
    /** Off in tests and in any run that must not touch api.telegram.org. */
    val pollingEnabled: Boolean = true,
) {
    val hasChannel: Boolean get() = channelId.isNotBlank()
}
