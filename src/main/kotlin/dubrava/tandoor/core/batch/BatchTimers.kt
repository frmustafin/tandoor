package dubrava.tandoor.core.batch

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/** Drives the automatic status transitions; a 20 s tick is plenty for minute-grained timers. */
@Component
@ConditionalOnProperty("bakery.timers-enabled", havingValue = "true", matchIfMissing = true)
class BatchTimers(private val batches: BatchService) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT20S", initialDelayString = "PT20S")
    fun tick() {
        try {
            val moved = batches.advanceTimers()
            if (moved > 0) log.info("timers moved {} batch(es)", moved)
        } catch (e: Exception) {
            log.error("timer tick failed", e)
        }
    }
}
