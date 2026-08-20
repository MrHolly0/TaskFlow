import { useEffect, useRef, useState } from 'react';
import { IconArrowLeft } from '@tabler/icons-react';
import { Button } from '@/app/components/ui/button';

const TOTAL_WAIT_SECONDS = 5 * 60;
const POLL_INTERVAL_MS = 2000;

export interface PhoneConfirmationStatus {
  status: 'waiting' | 'confirmed' | 'expired' | 'conflict';
}

type Props<T extends PhoneConfirmationStatus> = {
  phone: string;
  confirmationNumber: string;
  onBack: () => void;
  onConfirmed: (result: T) => void;
  pollStatus: (phone: string) => Promise<T>;
  cancel: (phone: string) => Promise<void>;
};

// Основной способ подтверждения теперь — звонок человека нам, не нам ему:
// исходящий звонок на реальном номере не доходил, хотя Ucaller каждый раз
// отчитывался об успехе (call_status: 1). Входящий звонок эту проблему
// снимает устройством, а не починкой — заблокировать исходящий вызов
// человека некому.
export function PhoneWaitingStep<T extends PhoneConfirmationStatus>({
  phone,
  confirmationNumber,
  onBack,
  onConfirmed,
  pollStatus,
  cancel,
}: Props<T>) {
  const [secondsLeft, setSecondsLeft] = useState(TOTAL_WAIT_SECONDS);
  const [expired, setExpired] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const stoppedRef = useRef(false);

  useEffect(() => {
    stoppedRef.current = false;
    const pollTimer = setInterval(() => {
      if (stoppedRef.current) return;
      pollStatus(phone)
        .then((result) => {
          if (stoppedRef.current || result.status === 'waiting') return;
          stoppedRef.current = true;
          clearInterval(pollTimer);
          if (result.status === 'expired') {
            setExpired(true);
          } else {
            onConfirmed(result);
          }
        })
        .catch(() => {
          // Сеть моргнула — не глушим ожидание, попробуем на следующем тике.
        });
    }, POLL_INTERVAL_MS);

    return () => clearInterval(pollTimer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phone]);

  useEffect(() => {
    if (expired || stoppedRef.current) return;
    if (secondsLeft <= 0) {
      stoppedRef.current = true;
      setExpired(true);
      return;
    }
    const countdown = setInterval(() => setSecondsLeft((s) => s - 1), 1000);
    return () => clearInterval(countdown);
  }, [secondsLeft, expired]);

  const handleCancel = async () => {
    if (cancelling) return;
    setCancelling(true);
    stoppedRef.current = true;
    try {
      await cancel(phone);
    } catch {
      // Отмена — best effort: если запрос не дошёл, запись всё равно
      // протухнет сама по TTL через пять минут.
    } finally {
      onBack();
    }
  };

  if (expired) {
    return (
      <div className="space-y-6">
        <div className="space-y-1.5 text-center">
          <h2 className="text-lg font-semibold">Время вышло</h2>
          <p className="text-sm text-muted-foreground">
            Звонок не пришёл за пять минут. Попробуйте ещё раз.
          </p>
        </div>
        <Button onClick={onBack} className="w-full h-11">Начать заново</Button>
      </div>
    );
  }

  const minutes = Math.floor(secondsLeft / 60);
  const seconds = secondsLeft % 60;

  return (
    <div className="space-y-6">
      <button
        type="button"
        onClick={handleCancel}
        disabled={cancelling}
        className="flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
      >
        <IconArrowLeft className="w-4 h-4" />
        {cancelling ? 'Отменяем...' : 'Назад'}
      </button>

      <div className="space-y-1.5 text-center">
        <h2 className="text-lg font-semibold">Позвоните на этот номер</h2>
        <p className="text-sm text-muted-foreground">
          Отвечать не нужно — звонок можно сбросить сразу после соединения.
        </p>
      </div>

      <a
        href={`tel:${confirmationNumber}`}
        className="block text-center text-3xl font-mono font-semibold tracking-wide py-4 rounded-2xl bg-muted/50 hover:bg-muted transition-colors"
      >
        {confirmationNumber}
      </a>

      <p className="text-center text-xs text-muted-foreground">
        Ждём звонка — осталось {minutes}:{seconds.toString().padStart(2, '0')}
      </p>
    </div>
  );
}
