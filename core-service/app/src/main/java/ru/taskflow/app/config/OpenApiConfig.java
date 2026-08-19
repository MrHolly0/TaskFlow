package ru.taskflow.app.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * springdoc-openapi не читает title/version/description из application.yml
 * (ключи springdoc.info.* ни на что не влияют, несмотря на то что выглядят
 * как настройка) — единственный рабочий способ задать их - бин OpenAPI.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openApi(@Value("${app.branding.name:Мунин}") String brandName) {
        return new OpenAPI().info(new Info()
                .title(brandName + " API")
                .version("1.0.0")
                .description("Digital task management assistant powered by AI"));
    }
}
