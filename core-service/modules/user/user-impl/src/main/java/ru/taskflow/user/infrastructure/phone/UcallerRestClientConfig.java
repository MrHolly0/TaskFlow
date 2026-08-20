package ru.taskflow.user.infrastructure.phone;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class UcallerRestClientConfig {

    @Bean
    public RestClient ucallerRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.ucaller.ru/v1.0")
                .build();
    }
}
