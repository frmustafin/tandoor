package dubrava.tandoor.telegram.api

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import java.time.Duration

/**
 * Thin, synchronous wrapper over the Bot API methods this bot needs. Every method is a
 * POST with a JSON body, which is the form the Bot API accepts for all of them.
 */
class TelegramApi(private val rest: RestClient) {

    fun getMe(): TgUser = call("getMe", emptyMap<String, Any>(), typeOf())

    /** getUpdates answers 409 while a webhook is registered, so one is dropped at startup. */
    fun deleteWebhook(): Boolean = call("deleteWebhook", emptyMap<String, Any>(), typeOf())

    fun getUpdates(offset: Long?, timeout: Duration, allowedUpdates: List<String>): List<Update> =
        call(
            "getUpdates",
            GetUpdatesRequest(offset = offset, timeout = timeout.seconds.toInt(), allowedUpdates = allowedUpdates),
            typeOf(),
        )

    fun sendMessage(request: SendMessageRequest): Message = call("sendMessage", request, typeOf())

    fun editMessageText(request: EditMessageTextRequest): Message = call("editMessageText", request, typeOf())

    fun sendPhoto(request: SendPhotoRequest): Message = call("sendPhoto", request, typeOf())

    fun editMessageReplyMarkup(request: EditMessageReplyMarkupRequest): Message =
        call("editMessageReplyMarkup", request, typeOf())

    fun deleteMessage(request: DeleteMessageRequest): Boolean = call("deleteMessage", request, typeOf())

    /** Every callback query must be answered, or the client keeps showing a spinner on the button. */
    fun answerCallbackQuery(request: AnswerCallbackQueryRequest): Boolean = call("answerCallbackQuery", request, typeOf())

    /** Without a scope the list applies everywhere; with `BotCommandScope.chat` only to that chat. */
    fun setMyCommands(commands: List<BotCommand>, scope: BotCommandScope? = null): Boolean =
        call("setMyCommands", SetMyCommandsRequest(commands, scope), typeOf())

    fun getChatMemberCount(request: GetChatMemberCountRequest): Int = call("getChatMemberCount", request, typeOf())

    /** Drops the per-chat list set with [setMyCommands]; the chat falls back to the default menu. */
    fun deleteMyCommands(scope: BotCommandScope? = null): Boolean =
        call("deleteMyCommands", DeleteMyCommandsRequest(scope), typeOf())

    private fun <T : Any> call(
        method: String,
        body: Any,
        type: ParameterizedTypeReference<TelegramResponse<T>>,
    ): T {
        val response = try {
            rest.post()
                .uri("/{method}", method)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                // Telegram reports failures as 4xx with a JSON body; read it instead of throwing.
                .onStatus({ true }, RestClient.ResponseSpec.ErrorHandler { _, _ -> })
                .body(type)
        } catch (e: RestClientException) {
            // Spring puts the request URL, and with it the bot token, into the message of a
            // transport failure. Rethrow without the URL and without the cause, so neither
            // `e.message` nor a logged stack trace can carry the token.
            throw TelegramApiException(method, null, transportFailure(e))
        } ?: throw TelegramApiException(method, null, "empty response")

        if (!response.ok) {
            throw TelegramApiException(
                method = method,
                errorCode = response.errorCode,
                description = response.description,
                retryAfter = response.parameters?.retryAfter?.let { Duration.ofSeconds(it.toLong()) },
            )
        }
        return response.result ?: throw TelegramApiException(method, null, "ok without result")
    }

    /** The root cause ("SocketTimeoutException: Connect timed out") names the problem and never the URL. */
    private fun transportFailure(e: RestClientException): String {
        val root = generateSequence<Throwable>(e) { it.cause }.last()
        val text = if (root === e) e.javaClass.simpleName else "${root.javaClass.simpleName}: ${root.message.orEmpty()}"
        return text.replace(TOKEN_IN_URL, "/bot<redacted>").take(200)
    }

    private inline fun <reified T : Any> typeOf() = object : ParameterizedTypeReference<TelegramResponse<T>>() {}

    private companion object {
        /** Belt and braces for a cause that quotes the URL after all. */
        val TOKEN_IN_URL = Regex("""/bot\d+:[\w-]+""")
    }
}
