/**
 * Список часовых поясов берётся у браузера, а не хранится в проекте:
 * состав зон и их смещения меняются решениями государств, и захардкоженный
 * список устаревает молча.
 */

// Запасной набор на случай, если Intl.supportedValuesOf недоступен
// (Safari до 15.4). Лучше шесть зон, чем пустой список.
const FALLBACK_ZONES = [
  'Europe/Kaliningrad',
  'Europe/Moscow',
  'Europe/Samara',
  'Asia/Yekaterinburg',
  'Asia/Novosibirsk',
  'Asia/Vladivostok',
  'UTC',
];

export function listTimezones(): string[] {
  const supported = (Intl as { supportedValuesOf?: (key: string) => string[] })
    .supportedValuesOf;
  if (typeof supported !== 'function') {
    return FALLBACK_ZONES;
  }
  try {
    return supported('timeZone');
  } catch {
    return FALLBACK_ZONES;
  }
}

/** Пояс устройства — предлагается, когда пользователь ещё ничего не выбирал. */
export function detectTimezone(): string | null {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || null;
  } catch {
    return null;
  }
}

/**
 * Смещение считается, а не берётся из таблицы: в странах с переходом на летнее
 * время оно меняется дважды в год, и записанное однажды значение будет врать
 * половину года.
 */
export function formatOffset(timezone: string, at: Date = new Date()): string {
  try {
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: timezone,
      timeZoneName: 'longOffset',
    }).formatToParts(at);
    const name = parts.find((p) => p.type === 'timeZoneName')?.value ?? '';
    // longOffset даёт "GMT+05:00", для UTC — просто "GMT"
    return name.replace('GMT', 'UTC') || 'UTC+00:00';
  } catch {
    return '';
  }
}

export function timezoneLabel(timezone: string): string {
  const offset = formatOffset(timezone);
  return offset ? `${timezone} (${offset})` : timezone;
}

/**
 * Название зоны по-русски: без него поиск идёт только по латинскому
 * идентификатору, и человек не найдёт свой пояс, набрав «Самара».
 *
 * Совпадение неполное и лучше не станет: имя зоне даёт один город на регион,
 * поэтому Новосибирск отдаётся как «Красноярск», а у Челябинска своей зоны
 * в IANA нет вовсе. Для таких случаев есть подсказка «пояс устройства».
 */
export function timezoneNameRu(timezone: string): string {
  try {
    const parts = new Intl.DateTimeFormat('ru', {
      timeZone: timezone,
      timeZoneName: 'longGeneric',
    }).formatToParts(new Date());
    const name = parts.find((p) => p.type === 'timeZoneName')?.value ?? '';
    // "Москва, стандартное время" -> "Москва": уточнение про стандартное
    // и летнее время только удлиняет строку и мешает искать.
    return name.replace(/,?\s*(стандартное|летнее)\s+время$/i, '').trim();
  } catch {
    return '';
  }
}

/** Строка, по которой идёт поиск: идентификатор, русское имя и смещение. */
export function timezoneSearchText(timezone: string): string {
  return [timezone, timezoneNameRu(timezone), formatOffset(timezone)]
    .filter(Boolean)
    .join(' ');
}
