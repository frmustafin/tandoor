package dubrava.tandoor.telegram

import dubrava.tandoor.config.TelegramProperties
import dubrava.tandoor.telegram.api.TelegramApi
import dubrava.tandoor.telegram.api.TelegramApiException
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Pulls updates from Telegram on a single dedicated thread. Long polling needs no public
 * address, which is what lets the project skip a domain and TLS entirely.
 *
 * The thread is non-daemon on purpose: with no web server in the application, it is what
 * keeps the JVM alive after the context has started.
 */
@Component
class LongPollingWorker(
    private val api: TelegramApi,
    private val router: UpdateRouter,
    private val props: TelegramProperties,
    private val identity: BotIdentity,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var offset: Long? = null

    override fun start() {
        if (!props.pollingEnabled) {
            log.info("Telegram polling is disabled by configuration")
            return
        }
        if (props.token.isBlank()) {
            log.warn("BOT_TOKEN is empty: Telegram polling will not start")
            return
        }
        running = true
        thread = Thread(::run, "telegram-polling").also { it.isDaemon = false; it.start() }
    }

    override fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(Duration.ofSeconds(5).toMillis())
    }

    override fun isRunning(): Boolean = running

    private fun run() {
        try {
            prepare()
            poll()
        } catch (_: InterruptedException) {
            // normal shutdown
        }
    }

    /** Startup calls live on this thread so a Telegram outage delays polling instead of boot. */
    private fun prepare() {
        var delay = INITIAL_BACKOFF
        while (running) {
            try {
                api.deleteWebhook()
                val me = api.getMe()
                identity.username = me.username
                api.setMyCommands(BotCommands.CUSTOMER)
                log.info("Telegram polling started as @{}", me.username)
                return
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                log.warn("Telegram is not reachable yet ({}); retrying in {}s", e.message, delay.seconds)
                sleep(delay)
                delay = minOf(delay.multipliedBy(2), MAX_BACKOFF)
            }
        }
    }

    private fun poll() {
        var delay = INITIAL_BACKOFF
        while (running) {
            try {
                val updates = api.getUpdates(offset, props.pollTimeout, ALLOWED_UPDATES)
                for (update in updates) {
                    // Advance before handling: a handler failure must not replay the update forever.
                    offset = update.updateId + 1
                    try {
                        router.route(update)
                    } catch (e: Exception) {
                        log.error("update {} failed", update.updateId, e)
                    }
                }
                delay = INITIAL_BACKOFF
            } catch (e: TelegramApiException) {
                when {
                    e.isConflict -> {
                        log.error("Another instance is polling with this token (409); retrying in {}s", CONFLICT_BACKOFF.seconds)
                        sleep(CONFLICT_BACKOFF)
                    }
                    e.isRateLimited -> sleep(e.retryAfter ?: INITIAL_BACKOFF)
                    else -> {
                        log.warn("getUpdates failed: {}", e.message)
                        sleep(delay)
                        delay = minOf(delay.multipliedBy(2), MAX_BACKOFF)
                    }
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                if (!running) return
                log.warn("polling error: {}", e.message)
                sleep(delay)
                delay = minOf(delay.multipliedBy(2), MAX_BACKOFF)
            }
        }
    }

    private fun sleep(duration: Duration) = Thread.sleep(duration.toMillis())

    companion object {
        /** Channel posts by the bakery are not ours to read; only chats and button taps. */
        val ALLOWED_UPDATES = listOf("message", "callback_query")
        private val INITIAL_BACKOFF: Duration = Duration.ofSeconds(1)
        private val MAX_BACKOFF: Duration = Duration.ofSeconds(30)
        private val CONFLICT_BACKOFF: Duration = Duration.ofSeconds(10)
    }
}
