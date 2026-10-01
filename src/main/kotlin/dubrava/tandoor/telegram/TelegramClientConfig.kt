package dubrava.tandoor.telegram

import com.fasterxml.jackson.annotation.JsonInclude
import dubrava.tandoor.config.TelegramProperties
import dubrava.tandoor.telegram.api.TelegramApi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.client.RestClient
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.time.Duration

/**
 * A dedicated Jackson mapper and RestClient for the Bot API. Telegram speaks snake_case and
 * rejects explicit nulls in some requests, so this mapper is kept apart from the
 * application-wide one rather than bending the global configuration.
 */
object TelegramClientSupport {

    fun jsonMapper(): JsonMapper = jacksonMapperBuilder()
        .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        .changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    /**
     * The read timeout must outlive a long-poll request, otherwise every quiet
     * [TelegramProperties.pollTimeout] seconds end in a spurious timeout error.
     */
    fun restClientBuilder(props: TelegramProperties): RestClient.Builder {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(10))
            setReadTimeout(props.pollTimeout.plusSeconds(15))
        }
        return RestClient.builder()
            .baseUrl("${props.apiBaseUrl}/bot${props.token}")
            .requestFactory(requestFactory)
            .configureMessageConverters { it.withJsonConverter(JacksonJsonHttpMessageConverter(jsonMapper())) }
    }
}

@Configuration
class TelegramClientConfig {

    @Bean
    fun telegramRestClient(props: TelegramProperties): RestClient =
        TelegramClientSupport.restClientBuilder(props).build()
}

@Configuration
class TelegramApiConfig {

    @Bean
    fun telegramApi(telegramRestClient: RestClient): TelegramApi = TelegramApi(telegramRestClient)
}
