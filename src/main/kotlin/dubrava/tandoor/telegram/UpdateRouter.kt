package dubrava.tandoor.telegram

import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.core.staff.RoleSource
import dubrava.tandoor.telegram.api.CallbackQuery
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.Update
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** One bot command such as `/start`. Implementations are discovered as Spring beans. */
interface CommandHandler {
    /** Command name without the slash, e.g. `start`. */
    val command: String

    /** Null means public. The router enforces it; handlers never check roles themselves. */
    val requiredRole: Role? get() = null

    /** [args] is whatever followed the command, trimmed; empty when there was nothing. */
    fun handle(message: Message, args: String)
}

/** A plain message in a private chat: a reply-keyboard tap, a typed answer, a photo. */
interface TextMessageHandler {
    val requiredRole: Role? get() = null

    /** Returns true when the message was consumed; the next handler is tried otherwise. */
    fun handle(message: Message): Boolean
}

/** An inline button tap. Callback data is `prefix:payload`; the handler owns the payload format. */
interface CallbackHandler {
    val prefix: String

    val requiredRole: Role? get() = null

    fun handle(query: CallbackQuery, payload: String)
}

/** How a refused command or button tap is answered; keeps the router free of Telegram calls. */
interface AccessDenied {
    fun command(message: Message)

    fun callback(query: CallbackQuery)
}

/**
 * Dispatches incoming updates to handlers and is the one place that checks roles: a handler
 * declares the role it needs and is simply never called without it. Only private chats are
 * served: the bot sits in the channel as an admin, and commands typed into groups are not
 * part of any scenario.
 */
@Component
class UpdateRouter(
    commands: List<CommandHandler>,
    private val texts: List<TextMessageHandler>,
    callbacks: List<CallbackHandler>,
    private val roles: RoleSource,
    private val denied: AccessDenied,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val byCommand = commands.associateBy { it.command }
    private val byPrefix = callbacks.associateBy { it.prefix }

    fun route(update: Update) {
        update.callbackQuery?.let { routeCallback(it); return }
        val message = update.message ?: return
        if (message.chat.type != "private") return
        // Photos matter too: the admin sends a product photo as a plain message.
        val text = message.text
        if (text == null && message.photo == null) return
        val userId = message.from?.id ?: message.chat.id

        val parsed = text?.let(::parseCommand)
        if (parsed != null) {
            val handler = byCommand[parsed.command]
            when {
                handler == null -> log.debug("no handler for /{}", parsed.command)
                !allowed(userId, handler.requiredRole) -> denied.command(message)
                else -> handler.handle(message, parsed.args)
            }
            return
        }
        texts.firstOrNull { allowed(userId, it.requiredRole) && it.handle(message) }
    }

    private fun routeCallback(query: CallbackQuery) {
        val data = query.data ?: return
        val handler = byPrefix[data.substringBefore(':')]
        if (handler == null) {
            log.warn("no handler for callback data {}", data)
            return
        }
        if (!allowed(query.from.id, handler.requiredRole)) {
            denied.callback(query)
            return
        }
        handler.handle(query, data.substringAfter(':', missingDelimiterValue = ""))
    }

    /** ADMIN covers everything a BAKER may do. */
    private fun allowed(userId: Long, required: Role?): Boolean {
        if (required == null) return true
        val role = roles.roleOf(userId) ?: return false
        return role == Role.ADMIN || role == required
    }

    data class ParsedCommand(val command: String, val args: String)

    companion object {
        /**
         * `/start qr` becomes (start, qr). The `@botname` suffix Telegram appends when a
         * command is picked from the menu is stripped, so `/start@some_bot` still routes to start.
         */
        fun parseCommand(text: String): ParsedCommand? {
            if (!text.startsWith("/")) return null
            val head = text.substringBefore(' ')
            val args = text.substringAfter(' ', missingDelimiterValue = "").trim()
            val command = head.drop(1).substringBefore('@').lowercase()
            return if (command.isEmpty()) null else ParsedCommand(command, args)
        }
    }
}
