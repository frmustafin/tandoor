package dubrava.tandoor.telegram

import dubrava.tandoor.core.batch.Batch
import dubrava.tandoor.core.product.Product
import dubrava.tandoor.telegram.api.InlineKeyboardButton
import dubrava.tandoor.telegram.api.InlineKeyboardMarkup
import dubrava.tandoor.telegram.api.KeyboardButton
import dubrava.tandoor.telegram.api.ReplyKeyboardMarkup

/** The persistent menu under a customer input field (plan 4.3). */
object CustomerMenu {
    const val CATALOGUE = "Продукция"
    const val MY_SUBSCRIPTIONS = "Мои подписки"
    const val UNSUBSCRIBE_ALL = "Отписаться от всего"

    fun keyboard() = ReplyKeyboardMarkup(
        keyboard = listOf(
            listOf(KeyboardButton(CATALOGUE), KeyboardButton(MY_SUBSCRIPTIONS)),
            listOf(KeyboardButton(UNSUBSCRIBE_ALL)),
        ),
    )
}

/** Product checkboxes; a tap flips one line in place. Callback data: `sub:toggle:<productId>` or `sub:all`. */
object SubscriptionKeyboard {
    const val PREFIX = "sub"
    const val TOGGLE = "$PREFIX:toggle"
    const val ALL = "$PREFIX:all"

    fun build(products: List<Product>, subscribed: Set<Long>): InlineKeyboardMarkup {
        val rows = products.map { product ->
            val mark = if (product.id in subscribed) "✅" else "⬜"
            listOf(InlineKeyboardButton("$mark ${product.name}", callbackData = "$TOGGLE:${product.id}"))
        }
        val allChosen = products.isNotEmpty() && products.all { it.id in subscribed }
        return InlineKeyboardMarkup(
            if (allChosen) rows else rows + listOf(listOf(InlineKeyboardButton("Подписаться на всё", callbackData = ALL)))
        )
    }
}

/** "Иду!" under public messages. Callback data: `going:<batchId>:<dm|channel>`. */
object GoingKeyboard {
    const val PREFIX = "going"
    const val GOING = "Иду!"

    fun forPersonal(batch: Batch): InlineKeyboardMarkup? =
        if (!batch.status.isActive) null
        else InlineKeyboardMarkup(listOf(listOf(InlineKeyboardButton(GOING, callbackData = "$PREFIX:${batch.id}:dm"))))

    /** The channel post also invites readers into the bot; the link carries the `channel` source tag. */
    fun forChannel(batch: Batch, subscribeLink: String?): InlineKeyboardMarkup? {
        val row = mutableListOf<InlineKeyboardButton>()
        if (batch.status.isActive) row += InlineKeyboardButton(GOING, callbackData = "$PREFIX:${batch.id}:channel")
        if (subscribeLink != null) row += InlineKeyboardButton("Уведомлять лично", url = subscribeLink)
        return row.takeIf { it.isNotEmpty() }?.let { InlineKeyboardMarkup(listOf(it)) }
    }
}
