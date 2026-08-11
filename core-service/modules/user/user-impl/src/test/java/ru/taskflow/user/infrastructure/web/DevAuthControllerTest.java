package ru.taskflow.user.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

class DevAuthControllerTest {

    @Test
    void controller_isRestrictedToDevProfile() {
        Profile profile = DevAuthController.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("dev");
    }

    @Test
    void authController_noLongerExposesDevToken() {
        boolean hasDevToken = java.util.Arrays.stream(AuthController.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("devToken"));

        assertThat(hasDevToken).isFalse();
    }
}
