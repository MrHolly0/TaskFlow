package ru.taskflow.nlp.infrastructure.groq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModelAvailabilityCheckerTest {

    private static final String BASE_URL = "https://api.groq.com/openai/v1";
    private static final String MODELS_URL = BASE_URL + "/models";

    private MockRestServiceServer server;
    private ModelAvailabilityChecker checker;

    @BeforeEach
    void setUp() {
        GroqConfig config = new GroqConfig();
        config.setApiKey("test-key");
        config.setBaseUrl(BASE_URL);
        config.setLlmModel("openai/gpt-oss-120b");

        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        checker = new ModelAvailabilityChecker(config, new ObjectMapper(), restClient);
    }

    @Test
    void run_marksUpWhenConfiguredModelIsInTheList() {
        server.expect(requestTo(MODELS_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"data": [{"id": "openai/gpt-oss-120b"}, {"id": "whisper-large-v3"}]}
                        """, MediaType.APPLICATION_JSON));

        checker.run(null);

        assertThat(checker.health().getStatus()).isEqualTo(Status.UP);
        server.verify();
    }

    @Test
    void run_marksDownAndListsAvailableWhenConfiguredModelIsMissing() {
        server.expect(requestTo(MODELS_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"data": [{"id": "qwen/qwen3.6-27b"}, {"id": "groq/compound"}]}
                        """, MediaType.APPLICATION_JSON));

        checker.run(null);

        Health health = checker.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("available")).isEqualTo(java.util.List.of("qwen/qwen3.6-27b", "groq/compound"));
        server.verify();
    }

    @Test
    void run_marksUnknownAndDoesNotThrowWhenProviderIsUnreachable() {
        server.expect(requestTo(MODELS_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        checker.run(null);

        assertThat(checker.health().getStatus()).isEqualTo(Status.UNKNOWN);
        server.verify();
    }

    @Test
    void health_beforeAnyCheckReportsUnknown() {
        assertThat(checker.health().getStatus()).isEqualTo(Status.UNKNOWN);
    }
}
