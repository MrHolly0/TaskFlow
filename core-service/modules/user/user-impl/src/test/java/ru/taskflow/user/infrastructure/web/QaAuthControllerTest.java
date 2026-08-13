package ru.taskflow.user.infrastructure.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ru.taskflow.shared.security.JwtService;
import ru.taskflow.user.api.UserDto;
import ru.taskflow.user.api.UserService;
import ru.taskflow.user.application.RefreshTokenService;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class QaAuthControllerTest {

    private static final String SECRET = "correct-secret-value";

    @Mock
    private JwtService jwtService;
    @Mock
    private UserService userService;
    @Mock
    private RefreshTokenService refreshTokenService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var controller = new QaAuthController(jwtService, userService, refreshTokenService, SECRET);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void qaLogin_returnsNotFoundWithoutSecretHeader() throws Exception {
        mockMvc.perform(post("/api/v1/auth/qa-login"))
                .andExpect(status().isNotFound());

        verify(userService, never()).findOrCreateByTelegram(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void qaLogin_returnsNotFoundOnWrongSecret() throws Exception {
        mockMvc.perform(post("/api/v1/auth/qa-login").header("X-Qa-Secret", "wrong-secret"))
                .andExpect(status().isNotFound());

        verify(userService, never()).findOrCreateByTelegram(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void qaLogin_returnsNotFoundWhenSecretNotConfigured() throws Exception {
        var controllerWithoutSecret = new QaAuthController(jwtService, userService, refreshTokenService, "");
        var mvc = MockMvcBuilders.standaloneSetup(controllerWithoutSecret).build();

        mvc.perform(post("/api/v1/auth/qa-login").header("X-Qa-Secret", ""))
                .andExpect(status().isNotFound());

        verify(userService, never()).findOrCreateByTelegram(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void qaLogin_issuesTokenForSingleFixedUserOnCorrectSecret() throws Exception {
        UUID userId = UUID.randomUUID();
        var user = new UserDto(userId, -1L, "qa_demo", "QA", "Demo");
        when(userService.findOrCreateByTelegram(-1L, "qa_demo", "QA", "Demo")).thenReturn(user);
        when(jwtService.issueAccessToken(userId, "qa_demo")).thenReturn("access-token");
        when(refreshTokenService.issue(userId)).thenReturn("refresh-token");

        mockMvc.perform(post("/api/v1/auth/qa-login").header("X-Qa-Secret", SECRET))
                .andExpect(status().isOk());

        verify(userService).findOrCreateByTelegram(-1L, "qa_demo", "QA", "Demo");
    }
}
