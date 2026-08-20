package ru.taskflow.user.application;

/**
 * Результат запроса входящего подтверждения у провайдера: номер, на который
 * должен позвонить человек, и идентификатор обращения — нужен, чтобы найти
 * концы при разборе жалоб.
 */
public record PhoneConfirmationRequest(String confirmationNumber, String ucallerId) {
}
