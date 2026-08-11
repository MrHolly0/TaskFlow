package ru.taskflow.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.taskflow.nlp.api.NlpGatewayService;
import ru.taskflow.nlp.api.NlpParseResult;
import ru.taskflow.task.api.TaskService;
import ru.taskflow.telegram.application.VoiceMessageHandler;
import ru.taskflow.telegram.infrastructure.client.TelegramApiClient;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramChat;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramUser;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramVoice;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoiceMessageHandlerTest {

    @Mock
    private TelegramApiClient apiClient;
    @Mock
    private TelegramMessageSender sender;
    @Mock
    private TaskService taskService;
    @Mock
    private NlpGatewayService nlpGatewayService;

    @Test
    void voiceMessage_downloadsFile_andReportsFailedRecognition() {
        var handler = new VoiceMessageHandler(apiClient, sender, taskService, nlpGatewayService);
        var userId = UUID.randomUUID();
        var message = new TelegramMessage(
                1L, new TelegramUser(1L, "u", "Name", null),
                new TelegramChat(100L), null, new TelegramVoice("file123", 5));
        when(apiClient.downloadVoice("file123")).thenReturn(new byte[]{1, 2, 3});
        when(taskService.findGroupNames(userId)).thenReturn(List.of());
        when(nlpGatewayService.parseVoice(any(), any(), any())).thenReturn(new NlpParseResult(List.of()));

        handler.handle(message, userId);

        verify(apiClient).downloadVoice(eq("file123"));
        verify(sender).sendMessage(eq(100L), contains("Распознаю"));
        verify(sender).sendMessage(eq(100L), contains("Не удалось распознать"));
    }
}
