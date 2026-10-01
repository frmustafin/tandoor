package dubrava.tandoor.telegram

import dubrava.tandoor.core.product.ProductRepository
import dubrava.tandoor.telegram.api.KeyboardButton
import dubrava.tandoor.telegram.api.ReplyKeyboardMarkup
import org.springframework.stereotype.Component

/** The persistent keyboard with one button per active product, two per row for phone screens. */
@Component
class BakerKeyboard(private val products: ProductRepository) {

    /** Null when there are no active products: Telegram rejects an empty keyboard. */
    fun build(): ReplyKeyboardMarkup? {
        val buttons = products.findAllByIsActiveTrueOrderBySortOrder().map { KeyboardButton(it.name) }
        if (buttons.isEmpty()) return null
        return ReplyKeyboardMarkup(
            keyboard = buttons.chunked(2),
            inputFieldPlaceholder = "Нажмите позицию, когда партия в печи",
        )
    }
}
