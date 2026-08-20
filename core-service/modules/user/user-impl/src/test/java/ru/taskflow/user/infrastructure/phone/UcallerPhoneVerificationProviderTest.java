package ru.taskflow.user.infrastructure.phone;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import ru.taskflow.user.application.PhoneConfirmationRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestToUriTemplate;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class UcallerPhoneVerificationProviderTest {

    private static final String PHONE = "+79991234567";
    private static final String CODE = "1234";
    private static final String BASE_URL = "https://taskflow.example.com";
    private static final String CALLBACK_SECRET = "cb-secret";

    private UcallerPhoneVerificationProvider providerWithKeys(
            String serviceId, String secretKey, String publicBaseUrl, String callbackSecret, MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.ucaller.ru/v1.0");
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        return new UcallerPhoneVerificationProvider(serviceId, secretKey, publicBaseUrl, callbackSecret, restClient);
    }

    private UcallerPhoneVerificationProvider fullyConfigured(MockRestServiceServer[] serverOut) {
        return providerWithKeys("service-1", "secret-1", BASE_URL, CALLBACK_SECRET, serverOut);
    }

    @Test
    void isAvailable_allFourPresent_true() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = fullyConfigured(serverOut);

        assertThat(provider.isAvailable()).isTrue();
    }

    @Test
    void isAvailable_missingServiceId_false() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("", "secret-1", BASE_URL, CALLBACK_SECRET, serverOut);

        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_missingSecretKey_false() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("service-1", "", BASE_URL, CALLBACK_SECRET, serverOut);

        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_missingPublicBaseUrl_false() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("service-1", "secret-1", "", CALLBACK_SECRET, serverOut);

        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_missingCallbackSecret_false() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("service-1", "secret-1", BASE_URL, "", serverOut);

        assertThat(provider.isAvailable()).isFalse();
    }

    @Test
    void health_unavailable_isDown() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("", "", "", "", serverOut);

        assertThat(provider.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void health_available_isUp() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = fullyConfigured(serverOut);

        assertThat(provider.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void requestConfirmation_notAvailable_throwsWithoutCallingNetwork() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("", "", "", "", serverOut);

        assertThatThrownBy(() -> provider.requestConfirmation(PHONE))
                .isInstanceOf(IllegalStateException.class);
        serverOut[0].verify();
    }

    @Test
    void requestConfirmation_success_returnsConfirmationNumberAndUcallerId() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = fullyConfigured(serverOut);
        String expectedCallbackUrl = BASE_URL + "/api/v1/phone/inbound-webhook/" + CALLBACK_SECRET;

        serverOut[0].expect(request -> {
                    String decodedUri = URLDecoder.decode(request.getURI().toString(), StandardCharsets.UTF_8);
                    assertThat(decodedUri).startsWith("https://api.ucaller.ru/v1.0/inboundCallWaiting?");
                    assertThat(decodedUri).contains("service_id=service-1");
                    assertThat(decodedUri).contains("key=secret-1");
                    assertThat(decodedUri).contains("phone=79991234567");
                    assertThat(decodedUri).contains("callback_url=" + expectedCallbackUrl);
                })
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"status\":true,\"ucaller_id\":103000,\"phone\":\"7999***4567\",\"confirmation_number\":\"79001000011\"}",
                        MediaType.APPLICATION_JSON));

        PhoneConfirmationRequest result = provider.requestConfirmation(PHONE);

        assertThat(result.confirmationNumber()).isEqualTo("79001000011");
        assertThat(result.ucallerId()).isEqualTo("103000");
        serverOut[0].verify();
    }

    @Test
    void requestConfirmation_providerRejects_throwsIllegalState() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = fullyConfigured(serverOut);

        serverOut[0].expect(method(HttpMethod.GET))
                .andRespond(withSuccess(
                        "{\"status\":false,\"error\":\"insufficient balance\"}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.requestConfirmation(PHONE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("insufficient balance");
    }

    @Test
    void sendCode_notAvailable_throwsWithoutCallingNetwork() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = providerWithKeys("", "", "", "", serverOut);

        assertThatThrownBy(() -> provider.sendCode(PHONE, CODE))
                .isInstanceOf(IllegalStateException.class);
        serverOut[0].verify();
    }

    @Test
    void sendCode_responseCodeMatchesOurs_returnsThatCode() {
        MockRestServiceServer[] serverOut = new MockRestServiceServer[1];
        var provider = fullyConfigured(serverOut);

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
        var provider = fullyConfigured(serverOut);

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
        var provider = fullyConfigured(serverOut);

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
        var provider = fullyConfigured(serverOut);

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
