package dubrava.tandoor.telegram.api

import java.time.Duration

/**
 * A Bot API call that came back with ok=false, or did not come back at all (then
 * [errorCode] is null). The bot token sits in the request URL, so nothing here ever
 * carries the URL: only the method name reaches the logs.
 */
class TelegramApiException(
    val method: String,
    val errorCode: Int?,
    val description: String?,
    val retryAfter: Duration? = null,
) : RuntimeException("Telegram $method failed: ${errorCode ?: "no code"} ${description ?: ""}".trim()) {

    /** Editing a message with identical text and buttons; harmless, callers ignore it. */
    val isNotModified: Boolean
        get() = errorCode == 400 && description.orEmpty().contains("message is not modified")

    /** 403 means the user blocked the bot; such a user is marked inactive, not retried. */
    val isBotBlockedByUser: Boolean
        get() = errorCode == 403

    /** 429: Telegram asks us to back off for [retryAfter]. */
    val isRateLimited: Boolean
        get() = errorCode == 429

    /**
     * 409 means another getUpdates consumer or a webhook is attached to the same token —
     * two bot instances running at once, which silently splits updates between them.
     */
    val isConflict: Boolean
        get() = errorCode == 409
}
