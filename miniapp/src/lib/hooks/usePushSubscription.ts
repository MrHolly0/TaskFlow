import { useCallback, useEffect, useState } from 'react';
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

function currentPermission(): BrowserPermission {
  if (!('Notification' in window) || !('serviceWorker' in navigator) || !('PushManager' in window)) {
    return 'unsupported';
  }
  return Notification.permission as BrowserPermission;
}

export function usePushSubscription() {
  const [permission, setPermission] = useState<BrowserPermission>(currentPermission());
  const [subscribed, setSubscribed] = useState(false);
  const [checking, setChecking] = useState(permission !== 'unsupported');

  useEffect(() => {
    if (permission === 'unsupported') {
      setChecking(false);
      return;
    }
    let cancelled = false;
    // serviceWorker.ready может не разрешиться никогда — регистрация иногда
    // не завершается (нет активного воркера, сетевая заминка). Без тайм-аута
    // checking завис бы навсегда, и с ним вместе — весь выбор канала в
    // NotificationChannelPrompt, который ждёт готовности push перед показом.
    const timeout = new Promise<null>((resolve) => setTimeout(() => resolve(null), 4000));
    Promise.race([navigator.serviceWorker.ready, timeout])
      .then((registration) => (registration ? registration.pushManager.getSubscription() : null))
      .then((existing) => {
        if (!cancelled) {
          setSubscribed(!!existing);
          setChecking(false);
        }
      })
      .catch(() => {
        if (!cancelled) setChecking(false);
      });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Разрешение спрашиваем только отсюда — по явному нажатию переключателя,
  // никогда сами при загрузке. Отказ необратим для сайта: вернуть человека,
  // нажавшего «Заблокировать» на автомате, мы не сможем никакой кнопкой.
  const subscribe = useCallback(async () => {
    if (permission === 'unsupported' || !VAPID_PUBLIC_KEY) {
      return false;
    }
    const result = await Notification.requestPermission();
    setPermission(result as BrowserPermission);
    if (result !== 'granted') {
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
    setSubscribed(true);
    return true;
  }, [permission]);

  const unsubscribe = useCallback(async () => {
    if (permission === 'unsupported') return;
    const registration = await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.getSubscription();
    if (subscription) {
      const endpoint = subscription.endpoint;
      await subscription.unsubscribe();
      await getClient().delete('/push/subscriptions', { params: { endpoint } });
    }
    setSubscribed(false);
  }, [permission]);

  return { permission, subscribed, checking, subscribe, unsubscribe };
}
