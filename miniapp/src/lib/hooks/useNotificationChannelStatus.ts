import { useSettings } from '@/lib/hooks/useSettings';
import { useIdentities } from '@/lib/hooks/useIdentities';
import { usePushSubscription } from '@/lib/hooks/usePushSubscription';

// «Работающий» канал — включённый переключателем И пригодный: push с выданным
// разрешением и живой подпиской, Telegram/почта — с привязанной идентичностью.
// Включённый переключатель без идентичности рабочим каналом не считается.
export function useNotificationChannelStatus() {
  const { data: settings } = useSettings();
  const { data: identities } = useIdentities();
  const { subscribed: pushSubscribed, permission: pushPermission, checking: pushChecking } = usePushSubscription();

  const hasTelegram = identities?.some((i) => i.provider === 'TELEGRAM') ?? false;
  const hasEmail = identities?.some((i) => i.provider === 'EMAIL') ?? false;

  const workingTelegram = !!settings?.notifyTelegram && hasTelegram;
  const workingEmail = !!settings?.notifyEmail && hasEmail;
  const workingPush = !!settings?.notifyPush && pushPermission === 'granted' && pushSubscribed;

  const ready = !!settings && !!identities && !pushChecking;
  const hasAnyWorkingChannel = workingTelegram || workingEmail || workingPush;

  return { ready, workingTelegram, workingEmail, workingPush, hasAnyWorkingChannel };
}
