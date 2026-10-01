package dubrava.tandoor.telegram

import dubrava.tandoor.config.TelegramProperties
import dubrava.tandoor.core.stats.ChannelMembers
import dubrava.tandoor.telegram.api.GetChatMemberCountRequest
import dubrava.tandoor.telegram.api.TelegramApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** The channel's subscriber count for the daily snapshot; null rather than an exception when it cannot be had. */
@Component
class TelegramChannelMembers(private val api: TelegramApi, private val props: TelegramProperties) : ChannelMembers {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun count(): Int? {
        if (!props.hasChannel) return null
        return runCatching { api.getChatMemberCount(GetChatMemberCountRequest(chatId = props.channelId)) }
            .onFailure { log.warn("getChatMemberCount failed: {}", it.message) }
            .getOrNull()
    }
}
