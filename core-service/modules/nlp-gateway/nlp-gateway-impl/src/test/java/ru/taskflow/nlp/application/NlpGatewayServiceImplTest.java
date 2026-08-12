package ru.taskflow.nlp.application;

import org.junit.jupiter.api.Test;
import ru.taskflow.nlp.api.LlmMessage;
import ru.taskflow.nlp.api.LlmToolRequest;
import ru.taskflow.nlp.infrastructure.NlpGatewayConfig;
import ru.taskflow.nlp.infrastructure.NlpWorkerClient;
import ru.taskflow.nlp.infrastructure.RestClientConfig;

import java.net.ServerSocket;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NlpGatewayServiceImplTest {

    /**
     * Реальный сокет, который принимает соединение и никогда не отвечает —
     * читающий таймаут клиента обязан сработать раньше, чем сервер что-то
     * пришлёт. Таймаут в тесте короче продакшн-значения (20с) только ради
     * скорости прогона — сама проверка честная: настоящий сетевой таймаут,
     * а не имитация.
     */
    @Test
    void callWithTools_returnsUnavailable_whenToolCallTimesOut() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            Thread unresponsiveServer = new Thread(() -> {
                try (var socket = serverSocket.accept()) {
                    Thread.sleep(5000);
                } catch (Exception ignored) {
                    // сокет закроется вместе с клиентским таймаутом — ожидаемо
                }
            });
            unresponsiveServer.setDaemon(true);
            unresponsiveServer.start();

            var config = new NlpGatewayConfig();
            config.setWorkerUrl("http://localhost:" + serverSocket.getLocalPort());
            config.setToolCallReadTimeoutSeconds(1);

            var restClientConfig = new RestClientConfig(config);
            var client = new NlpWorkerClient(config, restClientConfig.restClient(), restClientConfig.toolCallRestClient());
            var gateway = new NlpGatewayServiceImpl(client);

            var response = gateway.callWithTools(new LlmToolRequest(List.of(LlmMessage.user("привет")), List.of()));

            assertThat(response.failed()).isTrue();
            assertThat(response.toolCalls()).isEmpty();
        }
    }
}
