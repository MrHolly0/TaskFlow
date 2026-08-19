import { useEffect } from 'react';
import { TelegramLogo } from '@/app/components/TelegramLogo';

const BOT_ID = import.meta.env.VITE_TELEGRAM_BOT_ID || '8738130486';
const HASH_PREFIX = '#tgAuthResult=';

type Props = {
  onAuth: (fields: Record<string, string>) => void;
};

function readAuthResult(): Record<string, string> | null {
  if (!window.location.hash.startsWith(HASH_PREFIX)) return null;
  try {
    const raw = decodeURIComponent(window.location.hash.slice(HASH_PREFIX.length));
    const data = JSON.parse(atob(raw)) as Record<string, unknown>;
    const fields: Record<string, string> = {};
    for (const [key, value] of Object.entries(data)) fields[key] = String(value);
    return fields;
  } catch {
    return null;
  }
}

// Виджет telegram-widget.js рисует чужой iframe и требует внешний скрипт,
// который в России не грузится. oauth.telegram.org/auth — тот же путь входа
// без скрипта: обычная страница, куда переходим сами, а Telegram возвращает
// на return_to с результатом в хеше (#tgAuthResult=<base64>). Полный переход
// страницы, а не всплывающее окно, — если человек отменит вход у Telegram,
// он просто не окажется здесь с этим хешем, и страница не зависает в
// промежуточном состоянии, потому что мы его и не заводили.
export function TelegramLoginButton({ onAuth }: Props) {
  useEffect(() => {
    const fields = readAuthResult();
    if (!fields) return;
    history.replaceState(null, '', window.location.pathname + window.location.search);
    onAuth(fields);
  }, [onAuth]);

  const handleClick = () => {
    const params = new URLSearchParams({
      bot_id: BOT_ID,
      origin: window.location.origin,
      request_access: 'write',
      return_to: window.location.href,
    });
    window.location.href = `https://oauth.telegram.org/auth?${params.toString()}`;
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      className="flex items-center justify-center gap-3 py-3 px-6 rounded-2xl font-semibold text-white transition-all active:scale-95 cursor-pointer bg-[#2AABEE] hover:bg-[#229ED9]"
    >
      <TelegramLogo className="h-5 w-5" />
      Telegram
    </button>
  );
}
