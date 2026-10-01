package dubrava.tandoor.telegram

import org.springframework.stereotype.Component

/** The bot's own username, learned from getMe at startup; needed to build `t.me/<bot>?start=…` links. */
@Component
class BotIdentity {

    @Volatile
    var username: String? = null

    /** Null until the username is known (before the first successful getMe, or in tests). */
    fun deepLink(payload: String): String? = username?.let { "https://t.me/$it?start=$payload" }
}
