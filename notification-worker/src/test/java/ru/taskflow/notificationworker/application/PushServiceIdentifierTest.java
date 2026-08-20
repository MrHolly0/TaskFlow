package ru.taskflow.notificationworker.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PushServiceIdentifierTest {

    @Test
    void nameFor_googleEndpoint_returnsGoogle() {
        assertThat(PushServiceIdentifier.nameFor("https://fcm.googleapis.com/fcm/send/abc123"))
                .isEqualTo("Google");
    }

    @Test
    void nameFor_mozillaEndpoint_returnsMozilla() {
        assertThat(PushServiceIdentifier.nameFor("https://updates.push.services.mozilla.com/wpush/v2/abc123"))
                .isEqualTo("Mozilla");
    }

    @Test
    void nameFor_appleEndpoint_returnsApple() {
        assertThat(PushServiceIdentifier.nameFor("https://web.push.apple.com/abc123"))
                .isEqualTo("Apple");
    }

    @Test
    void nameFor_unknownEndpoint_namesTheHostInsteadOfGuessing() {
        assertThat(PushServiceIdentifier.nameFor("https://wns2-abc.notify.windows.com/abc123"))
                .isEqualTo("неизвестная служба (wns2-abc.notify.windows.com)");
    }

    @Test
    void nameFor_unparsableEndpoint_returnsUnknownWithoutThrowing() {
        assertThat(PushServiceIdentifier.nameFor("not a url"))
                .isEqualTo("неизвестная служба");
    }
}
