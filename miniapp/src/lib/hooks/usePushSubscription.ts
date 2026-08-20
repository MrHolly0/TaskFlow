import { useCallback } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import axios from 'axios';

const API_BASE = import.meta.env.VITE_API_URL || '/api/v1';
const VAPID_PUBLIC_KEY = import.meta.env.VITE_VAPID_PUBLIC_KEY || '';

const getClient = () => {
  const token = localStorage.getItem('auth_token');
  return axios.create({
    baseURL: API_BASE,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
};

function urlBase64ToUint8Array(base64String: string): Uint8Array<ArrayBuffer> {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
  const rawData = atob(base64);
  const output = new Uint8Array(rawData.length);
  for (let i = 0; i < rawData.length; i++) {
    output[i] = rawData.charCodeAt(i);
  }
  return output;
}

export type BrowserPermission = 'default' | 'granted' | 'denied' | 'unsupported';

interface PushState {
  permission: BrowserPermission;
  subscribed: boolean;
}

function currentPermission(): BrowserPermission {
  if (!('Notification' in window) || !('serviceWorker' in navigator) || !('PushManager' in window)) {
    return 'unsupported';
  }
  return Notification.permission as BrowserPermission;
}

// serviceWorker.ready может не разрешиться никогда — регистрация иногда не
// завершается (нет активного воркера, сетевая заминка). Без тайм-аута
// проверка зависла бы навсегда, и с ней вместе — весь выбор канала в
// NotificationChannelPrompt, который ждёт готовности push перед показом.
async function checkPushState(): Promise<PushState> {
  const permission = currentPermission();
  if (permission === 'unsupported') {
    return { permission, subscribed: false };
  }
  const timeout = new Promise<null>((resolve) => setTimeout(() => resolve(null), 4000));
  try {
    const registration = await Promise.race([navigator.serviceWorker.ready, timeout]);
    if (!registration) {
      return { permission, subscribed: false };
    }
    const existing = await registration.pushManager.getSubscription();
    return { permission, subscribed: !!existing };
  } catch {
    return { permission, subscribed: false };
  }
}

const PUSH_QUERY_KEY = ['push-subscription'];

/**
 * Разрешение и живая подписка — общий кэш через React Query, не собственный
 * useState на каждый вызов хука. Раньше SettingsPage и
 * useNotificationChannelStatus (через NotificationChannelPrompt) держали
 * каждый свою копию: подписался в одном месте — другое узнавало об этом
 * только при полном перемонтировании (обычно выглядело как «помогает
 * только перезагрузка страницы»). Мутации subscribe/unsubscribe пишут прямо
 * в кэш через setQueryData, тем же приёмом, что useUpdateSettings.
 */
export function usePushSubscription() {
  const queryClient = useQueryClient();
  const { data, isLoading } = useQuery({
    queryKey: PUSH_QUERY_KEY,
    queryFn: checkPushState,
  });

  const permission = data?.permission ?? currentPermission();
  const subscribed = data?.subscribed ?? false;
  const checking = permission !== 'unsupported' && isLoading;

  // Разрешение спрашиваем только отсюда — по явному нажатию переключателя,
  // никогда сами при загрузке. Отказ необратим для сайта: вернуть человека,
  // нажавшего «Заблокировать» на автомате, мы не сможем никакой кнопкой.
  const subscribe = useCallback(async () => {
    if (permission === 'unsupported' || !VAPID_PUBLIC_KEY) {
      return false;
    }
    const result = await Notification.requestPermission();
    const newPermission = result as BrowserPermission;
    if (result !== 'granted') {
      queryClient.setQueryData<PushState>(PUSH_QUERY_KEY, { permission: newPermission, subscribed: false });
      return false;
    }

    const registration = await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: urlBase64ToUint8Array(VAPID_PUBLIC_KEY),
    });
    const json = subscription.toJSON();
    await getClient().post('/push/subscriptions', {
      endpoint: json.endpoint,
      keys: { p256dh: json.keys?.p256dh, auth: json.keys?.auth },
    });
    queryClient.setQueryData<PushState>(PUSH_QUERY_KEY, { permission: newPermission, subscribed: true });
    return true;
  }, [permission, queryClient]);

  const unsubscribe = useCallback(async () => {
    if (permission === 'unsupported') return;
    const registration = await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.getSubscription();
    if (subscription) {
      const endpoint = subscription.endpoint;
      await subscription.unsubscribe();
      await getClient().delete('/push/subscriptions', { params: { endpoint } });
    }
    queryClient.setQueryData<PushState>(PUSH_QUERY_KEY, { permission, subscribed: false });
  }, [permission, queryClient]);

  return { permission, subscribed, checking, subscribe, unsubscribe };
}
