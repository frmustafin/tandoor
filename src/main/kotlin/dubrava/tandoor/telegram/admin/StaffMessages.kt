package dubrava.tandoor.telegram.admin

import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.core.staff.Staff
import dubrava.tandoor.core.staff.StaffService
import dubrava.tandoor.telegram.StaffOnboarding
import dubrava.tandoor.telegram.api.InlineKeyboardButton
import dubrava.tandoor.telegram.api.InlineKeyboardMarkup
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The admin's view of staff: the list, a member card, and the revoke question. Callback data: `staff:<action>[:<arg>]`. */
@Component
class StaffMessages(private val api: TelegramApi, private val staff: StaffService, zone: ZoneId) {

    private val date = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(zone)

    fun sendList(chatId: Long, viewerId: Long): Message {
        val rows = staff.all().map { member ->
            val you = if (member.telegramId == viewerId) " (вы)" else ""
            listOf(InlineKeyboardButton("${icon(member.role)} ${member.displayName}$you", callbackData = "$OPEN:${member.telegramId}"))
        } + listOf(
            listOf(InlineKeyboardButton("➕ Пригласить пекаря", callbackData = "$INVITE:${Role.BAKER}")),
            listOf(InlineKeyboardButton("➕ Пригласить админа", callbackData = "$INVITE:${Role.ADMIN}")),
        )
        return api.sendMessage(
            SendMessageRequest(chatId = chatId, text = "Сотрудники. 👑 — админ, 👨‍🍳 — пекарь.", replyMarkup = InlineKeyboardMarkup(rows))
        )
    }

    fun sendCard(chatId: Long, member: Staff): Message {
        val text = "${member.displayName}\nРоль: ${StaffOnboarding.roleName(member.role)}\nДобавлен: ${date.format(member.addedAt)}"
        val keyboard = InlineKeyboardMarkup(
            listOf(
                listOf(InlineKeyboardButton("🚪 Убрать роль", callbackData = "$REVOKE:${member.telegramId}")),
                listOf(InlineKeyboardButton("← К списку", callbackData = LIST)),
            )
        )
        return api.sendMessage(SendMessageRequest(chatId = chatId, text = text, replyMarkup = keyboard))
    }

    fun sendRevokeConfirmation(chatId: Long, member: Staff): Message {
        val keyboard = InlineKeyboardMarkup(
            listOf(listOf(
                InlineKeyboardButton("Да, убрать", callbackData = "$REVOKE_YES:${member.telegramId}"),
                InlineKeyboardButton("Нет", callbackData = "$REVOKE_NO:${member.telegramId}"),
            ))
        )
        return api.sendMessage(
            SendMessageRequest(chatId = chatId, text = "Убрать роль у «${member.displayName}»? Человек останется покупателем.", replyMarkup = keyboard)
        )
    }

    fun sendInvite(chatId: Long, role: Role, link: String, hours: Long): Message =
        api.sendMessage(
            SendMessageRequest(
                chatId = chatId,
                text = "Ссылка-приглашение (${StaffOnboarding.roleName(role)}), одноразовая, действует $hours ч. " +
                    "Отправьте её человеку, он откроет её в Telegram и нажмёт «Start»:\n$link",
            )
        )

    private fun icon(role: Role) = if (role == Role.ADMIN) "👑" else "👨‍🍳"

    companion object {
        const val PREFIX = "staff"
        const val LIST = "$PREFIX:list"
        const val OPEN = "$PREFIX:open"
        const val INVITE = "$PREFIX:invite"
        const val REVOKE = "$PREFIX:revoke"
        const val REVOKE_YES = "$PREFIX:revoke-yes"
        const val REVOKE_NO = "$PREFIX:revoke-no"
    }
}
