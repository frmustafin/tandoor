package dubrava.tandoor.core.settings

import dubrava.tandoor.config.BakeryProperties
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Admin-editable settings from the `settings` table, falling back to the configured defaults.
 * Read on every call: the table has three rows and the bot handles a few events a minute.
 */
@Service
class SettingsService(
    private val jdbc: JdbcClient,
    private val defaults: BakeryProperties,
    private val clock: Clock,
) {

    fun readyAfter(): Duration = minutes(READY_AFTER_MINUTES) ?: defaults.readyAfter

    fun hotFor(): Duration = minutes(HOT_FOR_MINUTES) ?: defaults.hotFor

    fun channelAutoPosts(): Boolean = get(CHANNEL_AUTO_POSTS)?.toBooleanStrictOrNull() ?: defaults.channelAutoPosts

    fun get(key: String): String? = jdbc.sql("SELECT setting_value FROM settings WHERE setting_key = :key")
        .param("key", key)
        .query(String::class.java)
        .optional()
        .orElse(null)

    fun set(key: String, value: String) {
        // OffsetDateTime rather than Instant: both H2 and the PostgreSQL driver bind it directly.
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
        val updated = jdbc.sql("UPDATE settings SET setting_value = :value, updated_at = :now WHERE setting_key = :key")
            .param("key", key).param("value", value).param("now", now)
            .update()
        if (updated == 0) {
            jdbc.sql("INSERT INTO settings (setting_key, setting_value, updated_at) VALUES (:key, :value, :now)")
                .param("key", key).param("value", value).param("now", now)
                .update()
        }
    }

    private fun minutes(key: String): Duration? = get(key)?.toLongOrNull()?.let(Duration::ofMinutes)

    companion object {
        const val READY_AFTER_MINUTES = "ready_after_minutes"
        const val HOT_FOR_MINUTES = "hot_for_minutes"
        const val CHANNEL_AUTO_POSTS = "channel_auto_posts"
    }
}
