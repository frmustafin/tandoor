package dubrava.tandoor

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Boots the context so Flyway runs the real migrations against H2 in PostgreSQL mode.
 * This is what proves the schema SQL stays portable between H2 and PostgreSQL.
 */
@SpringBootTest
class MigrationTest {

    @Autowired
    lateinit var jdbc: JdbcClient

    @Test
    fun `every table from the data model exists`() {
        val expected = listOf(
            "users", "staff", "invites", "products", "subscriptions",
            "batches", "batch_messages", "going_clicks", "daily_stats", "settings",
        )
        val actual = jdbc.sql(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"
        ).query(String::class.java).list()

        val missing = expected - actual.toSet()
        assertTrue(missing.isEmpty(), "missing tables: $missing (found: $actual)")
    }

    @Test
    fun `starting assortment is seeded in button order`() {
        val names = jdbc.sql("SELECT name FROM products WHERE is_active ORDER BY sort_order")
            .query(String::class.java)
            .list()

        assertEquals(
            listOf(
                "Самса из печи", "Самса из тандыра",
                "Лепёшка с сыром", "Лепёшка с мясом", "Обычная лепёшка",
            ),
            names,
        )
    }

    @Test
    fun `admin editable settings have defaults`() {
        val settings = jdbc.sql("SELECT setting_key, setting_value FROM settings")
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }
            .list()
            .toMap()

        assertEquals("15", settings["ready_after_minutes"])
        assertEquals("30", settings["hot_for_minutes"])
        assertEquals("true", settings["channel_auto_posts"])
    }

    @Test
    fun `batch status check constraint rejects an unknown status`() {
        val insert = { status: String ->
            jdbc.sql(
                """
                INSERT INTO batches (product_id, status, announced_at, expected_ready_at, created_by)
                VALUES (1, :status, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
                """
            ).param("status", status).update()
        }

        assertEquals(1, insert("ANNOUNCED"), "a valid status must be accepted")

        val error = assertFailsWith<DataIntegrityViolationException> { insert("NONSENSE") }
        assertTrue(
            error.message.orEmpty().contains("batches_status_check", ignoreCase = true),
            "expected the status check constraint to fail, got: ${error.message}",
        )
    }
}
