package ru.taskflow.telegram.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.taskflow.assistant.api.AssistantChannel;
import ru.taskflow.assistant.api.AssistantService;
import ru.taskflow.assistant.api.FastPathResolver;
import ru.taskflow.assistant.api.dto.ApplyResult;
import ru.taskflow.assistant.api.dto.Proposal;
import ru.taskflow.telegram.infrastructure.client.TelegramMessageSender;
import ru.taskflow.telegram.infrastructure.client.dto.TelegramMessage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Обработчик текстовых сообщений в Telegram боте.
 *
 * Худеет до пересылки ассистенту: разбор и создание задач теперь целиком
 * на стороне AssistantService, здесь — только рендер предложения и быстрый
 * путь подтверждения без обращения к модели.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TextMessageHandler {

    private final AssistantService assistantService;
    private final FastPathResolver fastPathResolver;
    private final ProposalMessageRenderer renderer;
    private final TelegramMessageSender sender;

    public void handle(TelegramMessage message, UUID userId) {
        Optional<Proposal> pending = assistantService.findLatestPending(userId);
        Optional<Boolean> fastPath = fastPathResolver.resolve(message.text(), pending.isPresent());

        if (fastPath.isPresent()) {
            resolveFastPath(userId, pending.orElseThrow(), fastPath.get(), message.chat().id());
            return;
        }

        Proposal proposal = assistantService.handleText(userId, message.text(), AssistantChannel.TELEGRAM);
        send(message.chat().id(), proposal);
    }

    private void resolveFastPath(UUID userId, Proposal proposal, boolean accepted, long chatId) {
        if (accepted) {
            ApplyResult result = assistantService.apply(userId, proposal.id());
            sender.sendMessage(chatId, renderer.renderApplyResult(result));
        } else {
            assistantService.reject(userId, proposal.id());
            sender.sendMessage(chatId, "Предложение отклонено.");
        }
    }

    private void send(long chatId, Proposal proposal) {
        String text = renderer.render(proposal);
        List<List<TelegramMessageSender.InlineButton>> keyboard = renderer.keyboard(proposal);
        if (keyboard.isEmpty()) {
            sender.sendMessage(chatId, text);
        } else {
            sender.sendMessage(chatId, text, keyboard);
        }
    }
}
