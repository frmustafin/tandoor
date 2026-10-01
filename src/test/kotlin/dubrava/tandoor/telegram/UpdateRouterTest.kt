package dubrava.tandoor.telegram

import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.core.staff.RoleSource
import dubrava.tandoor.telegram.UpdateRouter.Companion.parseCommand
import dubrava.tandoor.telegram.api.CallbackQuery
import dubrava.tandoor.telegram.api.Chat
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.TgUser
import dubrava.tandoor.telegram.api.Update
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateRouterTest {

    @Test
    fun `plain command`() =
        assertEquals(UpdateRouter.ParsedCommand("start", ""), parseCommand("/start"))

    @Test
    fun `command with a deep-link payload`() =
        assertEquals(UpdateRouter.ParsedCommand("start", "qr"), parseCommand("/start qr"))

    @Test
    fun `bot name suffix from the command menu is stripped`() =
        assertEquals(UpdateRouter.ParsedCommand("start", "channel"), parseCommand("/start@tandoor_on_dubravnaya_bot  channel "))

    @Test
    fun `case is normalised`() =
        assertEquals("stats", parseCommand("/STATS")?.command)

    @Test
    fun `ordinary text is not a command`() {
        assertNull(parseCommand("иду!"))
        assertNull(parseCommand("/"))
    }
}

/** Dispatch and, above all, the role gate: a handler that needs a role is never called without it. */
class UpdateRouterDispatchTest {

    private val calls = mutableListOf<String>()

    private val customer = 5L
    private val baker = 6L
    private val admin = 7L
    private val roles = RoleSource { id -> mapOf(baker to Role.BAKER, admin to Role.ADMIN)[id] }
    private val denied = object : AccessDenied {
        override fun command(message: Message) { calls += "denied-command" }
        override fun callback(query: CallbackQuery) { calls += "denied-callback" }
    }

    private val start = command("start", null)
    private val products = command("products", Role.ADMIN)
    private val tap = object : TextMessageHandler {
        override val requiredRole = Role.BAKER
        override fun handle(message: Message): Boolean { calls += "tap(${message.text})"; return message.text == "Самса" }
    }
    private val fallback = object : TextMessageHandler {
        override fun handle(message: Message): Boolean { calls += "fallback"; return true }
    }
    private val batch = object : CallbackHandler {
        override val prefix = "batch"
        override val requiredRole = Role.BAKER
        override fun handle(query: CallbackQuery, payload: String) { calls += "batch($payload)" }
    }
    private val going = object : CallbackHandler {
        override val prefix = "going"
        override fun handle(query: CallbackQuery, payload: String) { calls += "going($payload)" }
    }
    private val router = UpdateRouter(listOf(start, products), listOf(tap, fallback), listOf(batch, going), roles, denied)

    private fun command(name: String, role: Role?) = object : CommandHandler {
        override val command = name
        override val requiredRole = role
        override fun handle(message: Message, args: String) { calls += "$name($args)" }
    }

    private fun message(text: String, from: Long = customer, chatType: String = "private") =
        Update(updateId = 1, message = Message(messageId = 1, from = TgUser(id = from), chat = Chat(id = from, type = chatType), text = text))

    private fun callback(data: String, from: Long) =
        Update(updateId = 2, callbackQuery = CallbackQuery(id = "q", from = TgUser(id = from), data = data))

    @Test
    fun `public command from anyone`() {
        router.route(message("/start qr"))
        assertEquals(listOf("start(qr)"), calls)
    }

    @Test
    fun `admin command is refused to a customer and to a baker, served to an admin`() {
        router.route(message("/products", from = customer))
        router.route(message("/products", from = baker))
        router.route(message("/products", from = admin))
        assertEquals(listOf("denied-command", "denied-command", "products()"), calls)
    }

    @Test
    fun `a customer typing a product name skips the baker handler, an admin reaches it`() {
        router.route(message("Самса", from = customer))
        assertEquals(listOf("fallback"), calls, "the baker handler was not even asked")

        calls.clear()
        router.route(message("Самса", from = admin))
        assertEquals(listOf("tap(Самса)"), calls, "ADMIN covers BAKER")
    }

    @Test
    fun `staff buttons are refused to a customer, public buttons are not`() {
        router.route(callback("batch:ready:7", from = customer))
        router.route(callback("going:7:dm", from = customer))
        router.route(callback("batch:ready:7", from = baker))
        assertEquals(listOf("denied-callback", "going(7:dm)", "batch(ready:7)"), calls)
    }

    @Test
    fun `group chats are ignored`() {
        router.route(message("/start", chatType = "supergroup"))
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `callback data is split on the first colon`() {
        router.route(callback("batch:ready:7", from = baker))
        assertEquals(listOf("batch(ready:7)"), calls)
    }
}
