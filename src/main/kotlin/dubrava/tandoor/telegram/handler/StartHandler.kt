package dubrava.tandoor.telegram.handler

import dubrava.tandoor.core.staff.InviteService
import dubrava.tandoor.core.staff.StaffService
import dubrava.tandoor.core.user.Source
import dubrava.tandoor.core.user.UserService
import dubrava.tandoor.telegram.CommandHandler
import dubrava.tandoor.telegram.CustomerMenu
import dubrava.tandoor.telegram.StaffOnboarding
import dubrava.tandoor.telegram.SubscriptionMessages
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import org.springframework.stereotype.Component

/**
 * `/start [payload]`. `invite_<token>` redeems an invitation and welcomes the new staff member;
 * existing staff get their keyboard back; a customer is registered with the source from the
 * deep link (`qr`, `channel`) and shown the product checkboxes. The role lookup here only
 * chooses the view; access is the router's job.
 */
@Component
class StartHandler(
    private val api: TelegramApi,
    private val staff: StaffService,
    private val invites: InviteService,
    private val onboarding: StaffOnboarding,
    private val users: UserService,
    private val subscriptions: SubscriptionMessages,
) : CommandHandler {

    override val command = "start"

    override fun handle(message: Message, args: String) {
        val userId = message.from?.id ?: message.chat.id
        val chatId = message.chat.id

        if (args.startsWith(InviteService.START_PREFIX)) {
            val granted = invites.redeem(args.removePrefix(InviteService.START_PREFIX), userId, StaffOnboarding.displayName(message.from))
            if (granted != null) {
                api.sendMessage(SendMessageRequest(chatId = chatId, text = "Вы добавлены в сотрудники пекарни: ${StaffOnboarding.roleName(granted)}."))
                onboarding.welcome(chatId, granted)
                return
            }
            api.sendMessage(SendMessageRequest(chatId = chatId, text = INVITE_INVALID))
        }

        val role = staff.roleOf(userId)
        if (role != null) {
            onboarding.welcome(chatId, role)
            return
        }
        users.touch(userId, Source.fromStartPayload(args))
        api.sendMessage(SendMessageRequest(chatId = chatId, text = GREETING, replyMarkup = CustomerMenu.keyboard()))
        subscriptions.send(chatId, userId)
    }

    companion object {
        // Placeholder wording; the final texts are agreed with the baker (plan, section 5).
        const val GREETING = "Привет! Это бот пекарни на Дубравной.\n" +
            "Сообщаем, когда выпечка только из печи, и о каких позициях — выбираете вы."

        const val INVITE_INVALID = "Ссылка-приглашение недействительна или устарела. Попросите админа прислать новую."
    }
}
