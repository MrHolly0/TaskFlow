package ru.taskflow.nlp.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@RequiredArgsConstructor
public class RestClientConfig {

    private final NlpGatewayConfig nlpGatewayConfig;

    @Bean
    public RestClient restClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(nlpGatewayConfig.getConnectTimeoutSeconds() * 1000);
        factory.setReadTimeout(nlpGatewayConfig.getReadTimeoutSeconds() * 1000);

        return RestClient.builder()
            .requestFactory(factory)
            .build();
    }

    /**
     * Отдельный клиент для вызова инструментов: путь интерактивный (проход
     * укладывается в 20с бюджета спеки), общий клиент с read-timeout 30с
     * и тремя попытками retry даёт втрое больше худшего случая на один проход.
     */
    @Bean
    public RestClient toolCallRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(nlpGatewayConfig.getConnectTimeoutSeconds() * 1000);
        factory.setReadTimeout(nlpGatewayConfig.getToolCallReadTimeoutSeconds() * 1000);

        return RestClient.builder()
            .requestFactory(factory)
            .build();
    }
}
