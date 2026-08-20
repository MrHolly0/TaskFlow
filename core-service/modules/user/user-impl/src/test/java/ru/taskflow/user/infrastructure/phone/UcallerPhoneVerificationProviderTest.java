package ru.taskflow.user.infrastructure.phone;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UcallerPhoneVerificationProviderTest {

    private static final String PHONE = "+79991234567";
    private static final String CODE = "1234";

    private UcallerPhoneVerificationProvider providerWithKeys(String serviceId, String secretKey, MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.ucaller.ru/v1.0");
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        return new UcallerPhoneVerificationProvider(serviceId, secretKey, restClient);
    }

    @Test
    void isAvailable_bothKeysPresent_true() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("service-1", "secret-1", serverOut);

        assertThat(provider.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_missingServiceId_false() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("", "secret-1", serverOut);

        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_missingSecretKey_false() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("service-1", "", serverOut);

        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void health_unavailable_isDown() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("", "", serverOut);

        assertThat(provider.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void health_available_isUp() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("service-1", "secret-1", serverOut);

        assertThat(provider.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void sendCode_notAvailable_throwsWithoutCallingNetwork() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("", "", serverOut);

        assertThatThrownBy(() -> provider.sendCode(PHONE, CODE))
                .isInstanceOf(IllegalStateException.class);
        serverOut[0].verify();
    }

    @Test
    void sendCode_responseCodeMatchesOurs_returnsThatCode() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("service-1", "secret-1", serverOut);

        serverOut[0].expect(requestToUriTemplate(
                        "https://api.ucaller.ru/v1.0/initCall?service_id={sid}&key={key}&phone={phone}&code={code}",
                        "service-1", "secret-1", "79991234567", "1234"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"status\":true,\"ucaller_id\":103000,\"phone\":\"7999***4567\",\"code\":\"1234\"}",
                        MediaType.APPLICATION_JSON));

        String actualCode = provider.sendCode(PHONE, CODE);

        assertThat(actualCode).isEqualTo("1234");
        serverOut[0].verify();
    }

    // Код звонком — последние цифры номера, с которого звонят, а пул таких
    // номеров у Ucaller конечен: наш code — пожелание, не гарантия. Если
    // взяли не наш, это ещё не ошибка — доверяем ответу, а не входу.
    @Test
    void sendCode_responseCodeDiffersFromOurs_returnsProviderCodeNotOurs() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("service-1", "secret-1", serverOut);

        serverOut[0].expect(requestToUriTemplate(
                        "https://api.ucaller.ru/v1.0/initCall?service_id={sid}&key={key}&phone={phone}&code={code}",
                        "service-1", "secret-1", "79991234567", "1234"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"status\":true,\"ucaller_id\":103000,\"phone\":\"7999***4567\",\"code\":\"9081\"}",
                        MediaType.APPLICATION_JSON));

        String actualCode = provider.sendCode(PHONE, CODE);

        assertThat(actualCode).isEqualTo("9081");
    }

    // Отсутствие code в ответе — не повод падать: провайдер мог не вернуть
    // поле, а звонок при этом состоялся. Возвращаем свой код осознанно
    // (с предупреждением в лог), а не тихо теряем результат.
    @Test
    void sendCode_responseWithoutCode_fallsBackToOurCode() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("service-1", "secret-1", serverOut);

        serverOut[0].expect(requestToUriTemplate(
                        "https://api.ucaller.ru/v1.0/initCall?service_id={sid}&key={key}&phone={phone}&code={code}",
                        "service-1", "secret-1", "79991234567", "1234"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"status\":true,\"ucaller_id\":103000,\"phone\":\"7999***4567\"}",
                        MediaType.APPLICATION_JSON));

        String actualCode = provider.sendCode(PHONE, CODE);

        assertThat(actualCode).isEqualTo(CODE);
    }

    @Test
    void sendCode_providerRejectsCall_throwsIllegalState() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("service-1", "secret-1", serverOut);

        serverOut[0].expect(requestToUriTemplate(
                        "https://api.ucaller.ru/v1.0/initCall?service_id={sid}&key={key}&phone={phone}&code={code}",
                        "service-1", "secret-1", "79991234567", "1234"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"status\":false,\"error\":\"insufficient balance\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.sendCode(PHONE, CODE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insufficient balance");
    }
}
