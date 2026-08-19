package ru.taskflow.app.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiConfigTest {

    @Test
    void openApi_usesConfiguredBrandNameInTitle() {
        // springdoc.info.* в application.yml ни на что не влияет — единственный
        // рабочий способ задать заголовок Swagger-документации — бин OpenAPI,
        // это его и проверяем, а не сам факт наличия строки в yaml.
        var openApi = new OpenApiConfig().openApi("Кракен");

        assertThat(openApi.getInfo().getTitle()).isEqualTo("Кракен API");
    }
}
