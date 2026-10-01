package dubrava.tandoor.telegram

import dubrava.tandoor.config.TelegramProperties
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import dubrava.tandoor.telegram.api.TelegramApiException
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withException
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import java.net.SocketTimeoutException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the real mapper and converters against a mock Bot API endpoint. */
class TelegramApiTest {

    private val props = TelegramProperties(token = "123:ABC", apiBaseUrl = "https://bot.invalid")
    private val builder = TelegramClientSupport.restClientBuilder(props)
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val api = TelegramApi(builder.build())

    @Test
    fun `requests go out as snake_case json without null fields and the result is unwrapped`() {
        server.expect(requestTo("https://bot.invalid/bot123:ABC/sendMessage"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            // strict mode: an extra "parse_mode": null would fail this
            .andExpect(content().json("""{"chat_id": 42, "text": "Ждём!"}""", JsonCompareMode.STRICT))
            .andRespond(
                withSuccess(
                    """{"ok": true, "result": {"message_id": 7, "date": 1, "chat": {"id": 42, "type": "private"}, "text": "Ждём!"}}""",
                    MediaType.APPLICATION_JSON,
                )
            )

        val sent = api.sendMessage(SendMessageRequest(chatId = 42, text = "Ждём!"))

        assertEquals(7, sent.messageId)
        assertEquals(42, sent.chat.id)
        server.verify()
    }

    @Test
    fun `an error body becomes a typed exception carrying retry_after`() {
        server.expect(requestTo("https://bot.invalid/bot123:ABC/getMe"))
            .andRespond(
                withStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("""{"ok": false, "error_code": 429, "description": "Too Many Requests: retry after 7", "parameters": {"retry_after": 7}}""")
            )

        val error = assertFailsWith<TelegramApiException> { api.getMe() }

        assertTrue(error.isRateLimited)
        assertEquals(Duration.ofSeconds(7), error.retryAfter)
        assertEquals("getMe", error.method)
        assertFalse(error.message.orEmpty().contains("123:ABC"), "the token must never leak into messages")
    }

    @Test
    fun `a transport failure is reported without the request URL, so the token stays out of the logs`() {
        server.expect(requestTo("https://bot.invalid/bot123:ABC/getUpdates"))
            .andRespond(withException(SocketTimeoutException("Connect timed out")))

        val error = assertFailsWith<TelegramApiException> {
            api.getUpdates(offset = null, timeout = Duration.ofSeconds(30), allowedUpdates = LongPollingWorker.ALLOWED_UPDATES)
        }

        assertEquals("Telegram getUpdates failed: no code SocketTimeoutException: Connect timed out", error.message)
        assertNull(error.cause, "the cause's message quotes the URL and would be printed with a stack trace")
        assertFalse(error.stackTraceToString().contains("123:ABC"))
    }

    @Test
    fun `getUpdates sends offset and timeout, parses updates and ignores unknown fields`() {
        server.expect(requestTo("https://bot.invalid/bot123:ABC/getUpdates"))
            .andExpect(content().json("""{"offset": 100, "timeout": 30, "allowed_updates": ["message", "callback_query"]}""", JsonCompareMode.STRICT))
            .andRespond(
                withSuccess(
                    """
                    {"ok": true, "result": [
                      {"update_id": 100, "message": {"message_id": 1, "date": 1, "chat": {"id": 5, "type": "private"},
                                                     "from": {"id": 5, "is_bot": false, "first_name": "A", "language_code": "ru"},
                                                     "text": "/start qr", "entities": [{"type": "bot_command", "offset": 0, "length": 6}]}},
                      {"update_id": 101, "my_chat_member": {"whatever": true}}
                    ]}
                    """.trimIndent(),
                    MediaType.APPLICATION_JSON,
                )
            )

        val updates = api.getUpdates(offset = 100, timeout = Duration.ofSeconds(30), allowedUpdates = LongPollingWorker.ALLOWED_UPDATES)

        assertEquals(listOf(100L, 101L), updates.map { it.updateId })
        assertEquals("/start qr", updates[0].message?.text)
        assertEquals(5, updates[0].message?.from?.id)
        assertNull(updates[1].message)
    }
}
