package ru.taskflow.notify.infrastructure.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.taskflow.notify.infrastructure.persistence.PushSubscriptionJpaEntity;
import ru.taskflow.notify.infrastructure.persistence.PushSubscriptionRepository;
import ru.taskflow.notify.infrastructure.web.dto.PushSubscriptionRequest;
import ru.taskflow.shared.security.AuthenticatedUser;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/v1/push/subscriptions")
@RequiredArgsConstructor
@Tag(name = "Push", description = "Подписки на push-уведомления в браузере")
public class PushSubscriptionController {

    private final PushSubscriptionRepository repository;

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Зарегистрировать подписку",
            description = "Идемпотентно по endpoint — повторная регистрация обновляет ключи и владельца")
    public void subscribe(@Valid @RequestBody PushSubscriptionRequest request,
                           @AuthenticationPrincipal AuthenticatedUser user,
                           HttpServletRequest httpRequest) {
        var subscription = repository.findByEndpoint(request.endpoint())
                .orElseGet(PushSubscriptionJpaEntity::new);
        subscription.setUserId(user.userId());
        subscription.setEndpoint(request.endpoint());
        subscription.setP256dh(request.keys().p256dh());
        subscription.setAuth(request.keys().auth());
        subscription.setUserAgent(httpRequest.getHeader("User-Agent"));
        subscription.setLastSeenAt(OffsetDateTime.now());
        repository.save(subscription);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отменить подписку")
    public void unsubscribe(@RequestParam String endpoint) {
        repository.findByEndpoint(endpoint).ifPresent(repository::delete);
    }
}
