import { useEffect, useState } from 'react';
import { IconArrowLeft } from '@tabler/icons-react';
import { Button } from '@/app/components/ui/button';
import { Input } from '@/app/components/ui/input';
import { requestPhoneCode, verifyPhoneCode } from '@/lib/auth';

// Совпадает с LoginCodeService на бэкенде (MAX_ATTEMPTS_PHONE, COOLDOWN) —
// код короче, чем у почты (4 цифры против 6), поэтому и попыток меньше.
const MAX_ATTEMPTS = 3;
const RESEND_COOLDOWN_SECONDS = 60;

type Props = {
  phone: string;
  onBack: () => void;
  onVerified: () => void;
  // По умолчанию — вход (/auth/phone/*). Вкладка «Интеграции» передаёт сюда
  // привязку (/identities/phone/*) — тот же UI, код и попытки, другой смысл
  // конечного вызова на бэкенде.
  requestCode?: (phone: string) => Promise<void>;
  verifyCode?: (phone: string, code: string) => Promise<unknown>;
};

export function PhoneCodeStep({
  phone,
  onBack,
  onVerified,
  requestCode = requestPhoneCode,
  verifyCode = verifyPhoneCode,
}: Props) {
  const [code, setCode] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [resending, setResending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [attemptsLeft, setAttemptsLeft] = useState(MAX_ATTEMPTS);
  const [cooldown, setCooldown] = useState(RESEND_COOLDOWN_SECONDS);
  const [submitCount, setSubmitCount] = useState(0);

  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = setInterval(() => setCooldown((c) => Math.max(0, c - 1)), 1000);
    return () => clearInterval(timer);
  }, [cooldown]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (code.length !== 4 || submitting) return;
    setSubmitting(true);
    setError(null);
    try {
      await verifyCode(phone, code);
      onVerified();
    } catch {
      const left = Math.max(0, attemptsLeft - 1);
      setAttemptsLeft(left);
      setCode('');
      setSubmitCount((n) => n + 1);
      setError(
        left > 0
          ? `Неверный код. Осталось попыток: ${left}.`
          : 'Код исчерпал попытки. Запросите новый.',
      );
    } finally {
      setSubmitting(false);
    }
  };

  const handleResend = async () => {
    if (cooldown > 0 || resending) return;
    setResending(true);
    setError(null);
    try {
      await requestCode(phone);
      setAttemptsLeft(MAX_ATTEMPTS);
      setCooldown(RESEND_COOLDOWN_SECONDS);
    } catch {
      setError('Не получилось позвонить еще раз, попробуйте позже.');
    } finally {
      setResending(false);
    }
  };

  const codeExhausted = attemptsLeft <= 0;

  return (
    <div className="space-y-6">
      <button
        type="button"
        onClick={onBack}
        className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
      >
        <IconArrowLeft className="w-4 h-4" />
        Назад
      </button>

      <div className="space-y-1.5 text-center">
        <h2 className="text-lg font-semibold">Введите код</h2>
        <p className="text-sm text-muted-foreground">
          Мы позвонили на {phone} — код в последних цифрах номера звонившего.
        </p>
      </div>

      <form onSubmit={handleSubmit} className="space-y-4">
        <Input
          key={submitCount}
          autoFocus
          value={code}
          onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 4))}
          inputMode="numeric"
          autoComplete="one-time-code"
          placeholder="0000"
          disabled={submitting || codeExhausted}
          className="text-center text-2xl tracking-[0.5em] h-14 font-mono"
          maxLength={4}
        />

        {error && (
          <p className="text-center text-xs text-red-500 px-4">{error}</p>
        )}

        <Button
          type="submit"
          disabled={code.length !== 4 || submitting || codeExhausted}
          className="w-full h-11"
        >
          {submitting ? 'Проверяем...' : 'Войти'}
        </Button>

        <button
          type="button"
          onClick={handleResend}
          disabled={cooldown > 0 || resending}
          className="w-full text-center text-sm text-muted-foreground hover:text-foreground transition-colors cursor-pointer disabled:cursor-not-allowed disabled:hover:text-muted-foreground"
        >
          {cooldown > 0 ? `Позвонить еще раз через ${cooldown} с` : resending ? 'Звоним...' : 'Позвонить еще раз'}
        </button>
      </form>
    </div>
  );
}
