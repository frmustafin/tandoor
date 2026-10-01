package dubrava.tandoor.core

import dubrava.tandoor.config.BakeryProperties
import dubrava.tandoor.core.batch.BatchTexts
import dubrava.tandoor.core.catalogue.CatalogueTexts
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

@Configuration
class CoreConfig {

    /** Injected everywhere time is read, so tests can move it. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun batchTexts(props: BakeryProperties): BatchTexts = BatchTexts(props.timezone)

    @Bean
    fun catalogueTexts(props: BakeryProperties): CatalogueTexts = CatalogueTexts(props.timezone)

    /** The bakery zone for anything that formats a date outside the text classes. */
    @Bean
    fun bakeryZone(props: BakeryProperties): ZoneId = props.timezone
}
