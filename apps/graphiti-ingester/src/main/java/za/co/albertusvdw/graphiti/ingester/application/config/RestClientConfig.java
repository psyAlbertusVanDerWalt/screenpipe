package za.co.albertusvdw.graphiti.ingester.application.config;

import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RestClientConfig {

    /**
     * Applies the configured read timeout to every {@code RestClient}.
     *
     * <p>The default request factory times out far too early for this endpoint: a single
     * episode's extraction has been measured at over 180 seconds, and one measured "failure
     * rate" against this stack turned out to be a test harness's own 180s timeout firing on a
     * call that would have succeeded at 176s. The timeout must be sized for the slow-but-fine
     * case, not the median.
     */
    @Bean
    public RestClientCustomizer graphitiRestClientCustomizer(GraphitiProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofSeconds(10))
                .withReadTimeout(properties.getRequestTimeout());

        return restClientBuilder ->
                restClientBuilder.requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings));
    }
}
