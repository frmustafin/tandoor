package dubrava.tandoor.telegram.api

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Only the slice of the Bot API payload this bot actually reads is modelled here.
 * Unknown fields are ignored, so Telegram can add fields without breaking us.
 * Wire names are snake_case; the mapper applies the naming strategy. Kotlin `is`-properties
 * are named explicitly because bean introspection would otherwise drop the prefix.
 */

data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val errorCode: Int? = null,
    val description: String? = null,
    val parameters: ResponseParameters? = null,
)

data class ResponseParameters(
    val retryAfter: Int? = null,
    val migrateToChatId: Long? = null,
)

data class Update(
    val updateId: Long,
    val message: Message? = null,
    val callbackQuery: CallbackQuery? = null,
)

data class Message(
    val messageId: Long,
    val from: TgUser? = null,
    val chat: Chat,
    val text: String? = null,
    /** Sizes of one photo, smallest first; the last entry is the full-size file id. */
    val photo: List<PhotoSize>? = null,
) {
    val largestPhotoFileId: String? get() = photo?.lastOrNull()?.fileId
}

data class PhotoSize(
    val fileId: String,
    val width: Int = 0,
    val height: Int = 0,
)

data class Chat(
    val id: Long,
    val type: String,
)

data class TgUser(
    val id: Long,
    @JsonProperty("is_bot") val isBot: Boolean = false,
    val firstName: String? = null,
    val lastName: String? = null,
    val username: String? = null,
)

data class CallbackQuery(
    val id: String,
    val from: TgUser,
    val message: Message? = null,
    val data: String? = null,
)

data class BotCommand(
    val command: String,
    val description: String,
)

/** The two keyboard kinds Telegram accepts in `reply_markup`. */
sealed interface ReplyMarkup

/** The persistent keyboard under the input field: the baker's product buttons. */
data class ReplyKeyboardMarkup(
    val keyboard: List<List<KeyboardButton>>,
    val resizeKeyboard: Boolean = true,
    @JsonProperty("is_persistent") val isPersistent: Boolean = true,
    val inputFieldPlaceholder: String? = null,
) : ReplyMarkup

data class KeyboardButton(val text: String)

/** Takes the persistent keyboard away, e.g. when a role is revoked. */
data class ReplyKeyboardRemove(
    val removeKeyboard: Boolean = true,
) : ReplyMarkup

/** Buttons attached to one message: batch controls, "Иду!". */
data class InlineKeyboardMarkup(
    val inlineKeyboard: List<List<InlineKeyboardButton>>,
) : ReplyMarkup

data class InlineKeyboardButton(
    val text: String,
    val callbackData: String? = null,
    val url: String? = null,
)

data class SendMessageRequest(
    /** A numeric chat id, or `@username` for a public channel. */
    val chatId: Any,
    val text: String,
    val parseMode: String? = null,
    val disableNotification: Boolean? = null,
    val replyMarkup: ReplyMarkup? = null,
)

/** Omitting `replyMarkup` removes the inline keyboard from the message, which is what closing a batch wants. */
data class EditMessageTextRequest(
    val chatId: Any,
    val messageId: Long,
    val text: String,
    val parseMode: String? = null,
    val replyMarkup: InlineKeyboardMarkup? = null,
)

data class DeleteMessageRequest(
    val chatId: Any,
    val messageId: Long,
)

data class AnswerCallbackQueryRequest(
    val callbackQueryId: String,
    val text: String? = null,
    val showAlert: Boolean? = null,
)

data class GetUpdatesRequest(
    val offset: Long? = null,
    val timeout: Int? = null,
    val allowedUpdates: List<String>? = null,
)

/** `type` is `default`, `all_private_chats`, `chat` (with `chat_id`)… Null scope means the default. */
data class BotCommandScope(
    val type: String,
    val chatId: Long? = null,
) {
    companion object {
        fun chat(chatId: Long) = BotCommandScope(type = "chat", chatId = chatId)
    }
}

data class SetMyCommandsRequest(
    val commands: List<BotCommand>,
    val scope: BotCommandScope? = null,
)

/** `photo` is a Telegram file id the bot has seen before (the admin sent the photo to the bot). */
data class SendPhotoRequest(
    val chatId: Any,
    val photo: String,
    val caption: String? = null,
    val replyMarkup: ReplyMarkup? = null,
)

/** Works for photo and text messages alike, which is why the catalogue card uses it. */
data class EditMessageReplyMarkupRequest(
    val chatId: Any,
    val messageId: Long,
    val replyMarkup: InlineKeyboardMarkup? = null,
)

data class DeleteMyCommandsRequest(
    val scope: BotCommandScope? = null,
)

data class GetChatMemberCountRequest(
    val chatId: Any,
)
