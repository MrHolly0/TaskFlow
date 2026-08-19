import { useEffect, useRef } from 'react';

const BOT_USERNAME = import.meta.env.VITE_TELEGRAM_BOT_USERNAME || 'MHTaskFlowAI_Bot';

type Props = {
  onAuth: (user: Record<string, string | number>) => void;
  onLoaded: (loaded: boolean) => void;
};

export function TelegramLoginWidget({ onAuth, onLoaded }: Props) {
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!containerRef.current) return;
    const container = containerRef.current;
    (window as any).onTelegramWidgetAuth = onAuth;
    const script = document.createElement('script');
    script.src = 'https://telegram.org/js/telegram-widget.js?22';
    script.setAttribute('data-telegram-login', BOT_USERNAME);
    script.setAttribute('data-size', 'large');
    script.setAttribute('data-onauth', 'onTelegramWidgetAuth(user)');
    script.setAttribute('data-request-access', 'write');
    script.async = true;

    // В России telegram.org недоступен, и скрипт не загружается вовсе.
    // Без этой проверки на его месте оставался заголовок «или войти через»
    // над пустотой. Ждём появления iframe: onload скрипта срабатывает и
    // тогда, когда виджет по какой-то причине себя не отрисовал.
    script.onerror = () => onLoaded(false);
    const timer = window.setTimeout(
      () => onLoaded(Boolean(container.querySelector('iframe'))),
      4000,
    );

    container.innerHTML = '';
    container.appendChild(script);
    return () => {
      window.clearTimeout(timer);
      delete (window as any).onTelegramWidgetAuth;
    };
  }, [onAuth, onLoaded]);

  // Виджет — чужой iframe, его внутреннее содержимое отсюда не стилизуется.
  //
  // Тёмные уголки вокруг кнопки проявляются только в Safari: он рисует холст
  // вложенного документа своей подложкой по цветовой схеме, тогда как Chrome
  // и встроенный браузер Telegram оставляют его прозрачным.
  //
  // Поэтому здесь три меры сразу, а не одна: прозрачный фон и явно светлая
  // схема (виджет нарисован под светлый фон и другого не знает) — плюс
  // обрезка по скруглению. Обрезающий контейнер обязан обжимать iframe
  // по размеру: раньше скругление стояло на блоке во всю ширину, до углов
  // iframe не доставало и потому не работало.
  return (
    <div className="flex justify-center">
      <div
        ref={containerRef}
        className="w-fit overflow-hidden rounded-[20px] [&_iframe]:!border-0 [&_iframe]:!bg-transparent [&_iframe]:[color-scheme:light]"
      />
    </div>
  );
}
