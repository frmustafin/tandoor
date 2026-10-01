package dubrava.tandoor.core.batch

import dubrava.tandoor.core.product.Product

/**
 * The port through which the core tells the outside world about batches. The Telegram adapter
 * implements it; a second messenger would be another implementation, with the core untouched.
 */
interface BatchNotifier {

    /** Where the announcement landed, so later status changes can edit the same messages. */
    data class MessageRefs(
        val channelMessageId: Long? = null,
        val controlChatId: Long? = null,
        val controlMessageId: Long? = null,
    )

    /** Called once, right after the batch row exists. Must not throw: delivery failures are its own concern. */
    fun announced(batch: Batch, product: Product): MessageRefs

    /** Called after every status or time change, whether a tap or a timer caused it. Must not throw. */
    fun changed(batch: Batch, product: Product)

    /** Somebody tapped "Иду!": only the baker's counter needs redrawing, not the public messages. */
    fun goingChanged(batch: Batch, product: Product)
}
