package ru.taskflow.user.application;

/**
 * Доставка кода входа звонком на телефон. Отдельный интерфейс, а не прямая
 * зависимость от Ucaller — так уже было с LlmProvider для Groq, и в тот раз
 * смена модели поставщика без абстракции стоила времени; здесь та же
 * предосторожность на случай смены поставщика звонков.
 */
public interface PhoneVerificationProvider {

    boolean isAvailable();

    void sendCode(String phoneE164, String code);
}
