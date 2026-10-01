package dubrava.tandoor.telegram.admin

import dubrava.tandoor.core.staff.InviteService
import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.core.staff.StaffService
import dubrava.tandoor.core.staff.StaffService.RevokeResult
import dubrava.tandoor.telegram.BotIdentity
import dubrava.tandoor.telegram.CallbackHandler
import dubrava.tandoor.telegram.CommandHandler
import dubrava.tandoor.telegram.StaffOnboarding
import dubrava.tandoor.telegram.api.AnswerCallbackQueryRequest
import dubrava.tandoor.telegram.api.CallbackQuery
import dubrava.tandoor.telegram.api.EditMessageTextRequest
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** `/staff`: who has which role, invitations by link, revocation with a confirmation. Admins only, by the router. */
@Component
class StaffHandler(
    private val api: TelegramApi,
    private val staff: StaffService,
    private val invites: InviteService,
    private val messages: StaffMessages,
    private val onboarding: StaffOnboarding,
    private val identity: BotIdentity,
) : CommandHandler, CallbackHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override val command = "staff"

    override val prefix = StaffMessages.PREFIX

    override val requiredRole = Role.ADMIN

    override fun handle(message: Message, args: String) {
        messages.sendList(message.chat.id, message.from?.id ?: message.chat.id)
    }

    override fun handle(query: CallbackQuery, payload: String) {
        val adminId = query.from.id
        val chatId = query.message?.chat?.id ?: adminId
        val action = payload.substringBefore(':')
        val arg = payload.substringAfter(':', "")

        val reply: String? = when (action) {
            "list" -> { messages.sendList(chatId, adminId); null }
            "invite" -> invite(chatId, adminId, Role.entries.firstOrNull { it.name == arg })
            "open" -> member(arg)?.let { messages.sendCard(chatId, it); null } ?: "Уже не сотрудник"
            "revoke" -> member(arg)?.let { messages.sendRevokeConfirmation(chatId, it); null } ?: "Уже не сотрудник"
            "revoke-no" -> { replaceQuestion(query, "Оставляю."); null }
            "revoke-yes" -> revoke(query, chatId, adminId, arg.toLongOrNull())
            else -> { log.warn("unknown staff action {}", action); null }
        }
        runCatching { api.answerCallbackQuery(AnswerCallbackQueryRequest(callbackQueryId = query.id, text = reply)) }
            .onFailure { log.warn("answerCallbackQuery failed: {}", it.message) }
    }

    private fun member(arg: String) = arg.toLongOrNull()?.let(staff::find)

    private fun invite(chatId: Long, adminId: Long, role: Role?): String? {
        if (role == null) return "Неизвестная роль"
        val link = identity.deepLink(InviteService.START_PREFIX + invites.create(role, adminId).token)
            ?: return "Бот ещё не узнал своё имя, попробуйте через минуту"
        messages.sendInvite(chatId, role, link, InviteService.TTL.toHours())
        return null
    }

    private fun revoke(query: CallbackQuery, chatId: Long, adminId: Long, memberId: Long?): String? {
        memberId ?: return null
        val member = staff.find(memberId) ?: return "Уже не сотрудник"
        return when (staff.revoke(memberId)) {
            RevokeResult.REMOVED -> {
                replaceQuestion(query, "Роль убрана: «${member.displayName}».")
                if (memberId != adminId) onboarding.farewell(memberId) else api.sendMessage(SendMessageRequest(chatId = chatId, text = StaffOnboarding.REVOKED))
                messages.sendList(chatId, adminId)
                null
            }
            RevokeResult.LAST_ADMIN -> "Нельзя убрать последнего админа"
            RevokeResult.NOT_STAFF -> "Уже не сотрудник"
        }
    }

    private fun replaceQuestion(query: CallbackQuery, text: String) {
        val message = query.message ?: return
        runCatching { api.editMessageText(EditMessageTextRequest(chatId = message.chat.id, messageId = message.messageId, text = text)) }
            .onFailure { log.warn("replacing the confirmation failed: {}", it.message) }
    }
}
