package dubrava.tandoor.telegram

import dubrava.tandoor.config.TelegramProperties
import dubrava.tandoor.telegram.api.EditMessageTextRequest
import dubrava.tandoor.telegram.api.InlineKeyboardButton
import dubrava.tandoor.telegram.api.InlineKeyboardMarkup
import dubrava.tandoor.telegram.api.KeyboardButton
import dubrava.tandoor.telegram.api.ReplyKeyboardMarkup
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess

/** Keyboards are nested objects with `is_`-prefixed fields: the two places naming can go wrong. */
class KeyboardSerializationTest {

    private val props = TelegramProperties(token = "123:ABC", apiBaseUrl = "https://bot.invalid")
    private val builder = TelegramClientSupport.restClientBuilder(props)
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val api = TelegramApi(builder.build())

    private val sentMessage = """{"ok": true, "result": {"message_id": 1, "date": 1, "chat": {"id": 1, "type": "private"}}}"""

    @Test
    fun `reply keyboard keeps the is_persistent name and skips null placeholder`() {
        server.expect(requestTo("https://bot.invalid/bot123:ABC/sendMessage"))
            .andExpect(
                content().json(
                    """
                    {"chat_id": 1, "text": "t", "reply_markup": {
                        "keyboard": [[{"text": "Самса"}, {"text": "Лепёшка"}], [{"text": "Чай"}]],
                        "resize_keyboard": true, "is_persistent": true}}
                    """,
                    JsonCompareMode.STRICT,
                )
            )
            .andRespond(withSuccess(sentMessage, MediaType.APPLICATION_JSON))

        api.sendMessage(
            SendMessageRequest(
                chatId = 1,
                text = "t",
                replyMarkup = ReplyKeyboardMarkup(
                    keyboard = listOf(KeyboardButton("Самса"), KeyboardButton("Лепёшка"), KeyboardButton("Чай")).chunked(2),
                ),
            )
        )
        server.verify()
    }

    @Test
    fun `inline keyboard under an edited channel post addressed by username`() {
        server.expect(requestTo("https://bot.invalid/bot123:ABC/editMessageText"))
            .andExpect(
                content().json(
                    """
                    {"chat_id": "@bakery", "message_id": 55, "text": "x",
                     "reply_markup": {"inline_keyboard": [[{"text": "Готово", "callback_data": "batch:ready:7"}]]}}
                    """,
                    JsonCompareMode.STRICT,
                )
            )
            .andRespond(withSuccess(sentMessage, MediaType.APPLICATION_JSON))

        api.editMessageText(
            EditMessageTextRequest(
                chatId = "@bakery",
                messageId = 55,
                text = "x",
                replyMarkup = InlineKeyboardMarkup(listOf(listOf(InlineKeyboardButton("Готово", callbackData = "batch:ready:7")))),
            )
        )
        server.verify()
    }
}
