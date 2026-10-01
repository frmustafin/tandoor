package dubrava.tandoor.telegram

import dubrava.tandoor.core.batch.Batch
import dubrava.tandoor.core.batch.BatchMessages
import dubrava.tandoor.core.batch.BatchRepository
import dubrava.tandoor.core.batch.BatchStatus
import dubrava.tandoor.core.product.Product
import dubrava.tandoor.core.product.ProductRepository
import dubrava.tandoor.core.subscription.SubscriptionService
import dubrava.tandoor.core.user.Source
import dubrava.tandoor.core.user.UserService
import dubrava.tandoor.telegram.api.Chat
import dubrava.tandoor.telegram.api.EditMessageTextRequest
import dubrava.tandoor.telegram.api.InlineKeyboardMarkup
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import dubrava.tandoor.telegram.api.TelegramApiException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The whole fan-out against the real database, with the Bot API replaced by a mock. */
@SpringBootTest
class TelegramBatchNotifierTest {

    @MockitoBean lateinit var api: TelegramApi
    @Autowired lateinit var notifier: TelegramBatchNotifier
    @Autowired lateinit var identity: BotIdentity
    @Autowired lateinit var users: UserService
    @Autowired lateinit var subscriptions: SubscriptionService
    @Autowired lateinit var products: ProductRepository
    @Autowired lateinit var batches: BatchRepository
    @Autowired lateinit var messages: BatchMessages
    @Autowired lateinit var jdbc: JdbcClient

    private val baker = 1001L
    private lateinit var samsa: Product
    private lateinit var batch: Batch

    @BeforeEach
    fun reset() {
        jdbc.sql("DELETE FROM batches").update()
        jdbc.sql("DELETE FROM users").update()
        identity.username = "test_bot"
        samsa = products.findAllByIsActiveTrueOrderBySortOrder().first()
        for (id in listOf(10L, 11L, 12L)) {
            users.touch(id, Source.QR)
            subscriptions.toggle(id, samsa.id!!)
        }
        val now = Instant.parse("2026-10-01T09:00:00Z")
        batch = batches.save(
            Batch(productId = samsa.id!!, status = BatchStatus.ANNOUNCED, announcedAt = now, expectedReadyAt = now + Duration.ofMinutes(15), createdBy = baker)
        )

        var nextId = 100L
        `when`(api.sendMessage(anySend())).thenAnswer { invocation ->
            val request = invocation.getArgument<SendMessageRequest>(0)
            if (request.chatId == 12L) throw TelegramApiException("sendMessage", 403, "Forbidden: bot was blocked by the user")
            val chatId = request.chatId as? Long ?: -1L
            Message(messageId = ++nextId, chat = Chat(id = chatId, type = if (chatId > 0) "private" else "channel"))
        }
        `when`(api.editMessageText(anyEdit())).thenAnswer { invocation ->
            val request = invocation.getArgument<EditMessageTextRequest>(0)
            Message(messageId = request.messageId, chat = Chat(id = request.chatId as? Long ?: -1L, type = "private"))
        }
    }

    @Test
    fun `announcement posts to the channel, sends the baker a card and each subscriber a message`() {
        val refs = notifier.announced(batch, samsa)

        assertEquals(101L, refs.channelMessageId, "the channel post goes first")
        assertEquals(baker, refs.controlChatId)
        assertEquals(102L, refs.controlMessageId)
        assertEquals(listOf(10L, 11L), messages.of(batch.id!!).map { it.chatId }, "the blocked subscriber has no message")
        assertFalse(users.find(12)!!.isActive, "403 marks the user as blocked")

        val captor = ArgumentCaptor.forClass(SendMessageRequest::class.java)
        verify(api, times(5)).sendMessage(captor.captureSend())
        val channel = captor.allValues.first { it.chatId == "@test_channel" }
        assertEquals("🔥 Готовим · Самса из печи · к 12:15", channel.text)
        val buttons = (channel.replyMarkup as InlineKeyboardMarkup).inlineKeyboard.single()
        assertEquals(listOf("Иду!", "Уведомлять лично"), buttons.map { it.text })
        assertEquals("https://t.me/test_bot?start=channel", buttons[1].url)
        val personal = captor.allValues.first { it.chatId == 10L }
        assertEquals("going:${batch.id}:dm", (personal.replyMarkup as InlineKeyboardMarkup).inlineKeyboard.single().single().callbackData)
    }

    @Test
    fun `a status change edits the channel post, the card and every personal message`() {
        val refs = notifier.announced(batch, samsa)
        clearInvocations(api)
        val ready = batch.copy(
            status = BatchStatus.READY, readyAt = batch.expectedReadyAt,
            channelMessageId = refs.channelMessageId, controlChatId = refs.controlChatId, controlMessageId = refs.controlMessageId,
        )

        notifier.changed(ready, samsa)

        val captor = ArgumentCaptor.forClass(EditMessageTextRequest::class.java)
        verify(api, times(4)).editMessageText(captor.captureEdit())
        assertEquals(listOf<Any>("@test_channel", baker, 10L, 11L), captor.allValues.map { it.chatId })
        assertTrue(captor.allValues.filter { it.chatId != baker }.all { it.text == "✅ Готово · Самса из печи · в 12:15" })
    }

    @Test
    fun `a going tap redraws only the baker card`() {
        val refs = notifier.announced(batch, samsa)
        clearInvocations(api)

        notifier.goingChanged(batch.copy(controlChatId = refs.controlChatId, controlMessageId = refs.controlMessageId), samsa)

        verify(api, times(1)).editMessageText(anyEdit())
        verify(api, never()).sendMessage(anySend())
    }
}

/*
 * Mockito matchers return null, and Kotlin null-checks non-null parameters at the call site, so
 * each helper registers the matcher and hands back a harmless instance (the mockito-kotlin trick).
 */
private val DUMMY_SEND = SendMessageRequest(chatId = 0, text = "")
private val DUMMY_EDIT = EditMessageTextRequest(chatId = 0, messageId = 0, text = "")

private fun anySend(): SendMessageRequest = ArgumentMatchers.any(SendMessageRequest::class.java) ?: DUMMY_SEND
private fun anyEdit(): EditMessageTextRequest = ArgumentMatchers.any(EditMessageTextRequest::class.java) ?: DUMMY_EDIT
private fun ArgumentCaptor<SendMessageRequest>.captureSend(): SendMessageRequest = capture() ?: DUMMY_SEND
private fun ArgumentCaptor<EditMessageTextRequest>.captureEdit(): EditMessageTextRequest = capture() ?: DUMMY_EDIT
