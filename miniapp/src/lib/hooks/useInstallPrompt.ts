import { useEffect, useState } from 'react';

interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

let deferredPrompt: BeforeInstallPromptEvent | null = null;
const listeners = new Set<() => void>();

function notify() {
  listeners.forEach((listener) => listener());
}

// Событие приходит один раз и рано — ловим его на уровне модуля, а не внутри
// компонента, иначе есть риск пропустить его до монтирования страницы настроек.
window.addEventListener('beforeinstallprompt', (event) => {
  event.preventDefault();
  deferredPrompt = event as BeforeInstallPromptEvent;
  notify();
});

window.addEventListener('appinstalled', () => {
  deferredPrompt = null;
  notify();
});

export function useInstallPrompt() {
  const [canInstall, setCanInstall] = useState(deferredPrompt !== null);

  useEffect(() => {
    const listener = () => setCanInstall(deferredPrompt !== null);
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }, []);

  const promptInstall = async () => {
    if (!deferredPrompt) return;
    await deferredPrompt.prompt();
    await deferredPrompt.userChoice;
    deferredPrompt = null;
    notify();
  };

  return { canInstall, promptInstall };
}
