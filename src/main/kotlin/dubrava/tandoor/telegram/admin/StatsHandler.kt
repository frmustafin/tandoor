package dubrava.tandoor.telegram.admin

import dubrava.tandoor.core.staff.Role
import dubrava.tandoor.core.stats.StatsService
import dubrava.tandoor.core.stats.StatsTexts
import dubrava.tandoor.telegram.CommandHandler
import dubrava.tandoor.telegram.api.Message
import dubrava.tandoor.telegram.api.SendMessageRequest
import dubrava.tandoor.telegram.api.TelegramApi
import org.springframework.stereotype.Component

/** `/stats [days]`: the plan's section 6 in one message; 14 days unless asked otherwise. Admins only, by the router. */
@Component
class StatsHandler(private val api: TelegramApi, private val stats: StatsService) : CommandHandler {

    override val command = "stats"

    override val requiredRole = Role.ADMIN

    override fun handle(message: Message, args: String) {
        val days = args.toIntOrNull()?.coerceIn(1, MAX_DAYS) ?: DEFAULT_DAYS
        val report = stats.report(days)
        api.sendMessage(SendMessageRequest(chatId = message.chat.id, text = StatsTexts.render(report), parseMode = "HTML"))
    }

    companion object {
        const val DEFAULT_DAYS = 14
        const val MAX_DAYS = 60
    }
}
