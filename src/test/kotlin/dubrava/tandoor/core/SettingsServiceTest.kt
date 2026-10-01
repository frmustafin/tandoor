package dubrava.tandoor.core

import dubrava.tandoor.core.settings.SettingsService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
class SettingsServiceTest {

    @Autowired lateinit var settings: SettingsService
    @Autowired lateinit var jdbc: JdbcClient

    @AfterEach
    fun restoreDefaults() {
        settings.set(SettingsService.READY_AFTER_MINUTES, "15")
        settings.set(SettingsService.HOT_FOR_MINUTES, "30")
        settings.set(SettingsService.CHANNEL_AUTO_POSTS, "true")
        jdbc.sql("DELETE FROM settings WHERE setting_key = 'unknown'").update()
    }

    @Test
    fun `timers and the switch round-trip through the table`() {
        settings.set(SettingsService.READY_AFTER_MINUTES, "20")
        settings.set(SettingsService.HOT_FOR_MINUTES, "45")
        settings.set(SettingsService.CHANNEL_AUTO_POSTS, "false")

        assertEquals(Duration.ofMinutes(20), settings.readyAfter())
        assertEquals(Duration.ofMinutes(45), settings.hotFor())
        assertFalse(settings.channelAutoPosts())
    }

    @Test
    fun `a garbled value falls back to the configured default`() {
        settings.set(SettingsService.READY_AFTER_MINUTES, "soon")
        settings.set(SettingsService.CHANNEL_AUTO_POSTS, "maybe")

        assertEquals(Duration.ofMinutes(15), settings.readyAfter())
        assertTrue(settings.channelAutoPosts())
    }

    @Test
    fun `a new key is inserted, an existing one updated`() {
        settings.set("unknown", "1")
        settings.set("unknown", "2")
        assertEquals("2", settings.get("unknown"))
        assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM settings WHERE setting_key = 'unknown'").query(Int::class.java).single())
    }
}
