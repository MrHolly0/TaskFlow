package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.telegram.infrastructure.client.TelegramApiClient;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;

import java.util.List;
import java.util.UUID;

/**
 * Обработчик голосовых сообщений в Telegram боте.
 *
 * Расшифровка и разбор — целиком на стороне AssistantService.handleVoice,
 * здесь только скачивание файла и рендер предложения.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoiceMessageHandler {

    private final TelegramApiClient apiClient;
    private final TelegramMessageSender sender;
    private final AssistantService assistantService;
    private final ProposalMessageRenderer renderer;

    public void handle(TelegramMessage message, UUID userId) {
        String fileId = message.voice().fileId();
        byte[] data = apiClient.downloadVoice(fileId);

        Proposal proposal = assistantService.handleVoice(userId, data, AssistantChannel.TELEGRAM);

        String text = renderer.render(proposal);
        List<List<TelegramMessageSender.InlineButton>> keyboard = renderer.keyboard(proposal);
        if (keyboard.isEmpty()) {
            sender.sendMessage(message.chat().id(), text);
        } else {
            sender.sendMessage(message.chat().id(), text, keyboard);
        }
    }
}
