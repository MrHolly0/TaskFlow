import { addDays } from 'date-fns';
import { fromZonedTime, toZonedTime } from 'date-fns-tz';

// Часы вечера и утра для быстрых вариантов напоминания (Б2) — в одном
// месте, а не разбросаны по компонентам.
const EVENING_HOUR = 19;
const MORNING_HOUR = 9;

export interface ReminderPreset {
  key: 'hour' | 'evening' | 'morning';
  label: string;
  fireAt: Date;
}

/**
 * «Через час», «сегодня вечером», «завтра утром» — время считается в поясе
 * пользователя. Вариант, чьё время уже прошло (вечер после EVENING_HOUR),
 * не предлагается вовсе (Б2: прошедшее время не предлагать).
 */
export function buildReminderPresets(now: Date, timezone: string): ReminderPreset[] {
  const zonedNow = toZonedTime(now, timezone);
  const presets: ReminderPreset[] = [
    { key: 'hour', label: 'Через час', fireAt: new Date(now.getTime() + 60 * 60 * 1000) },
  ];

  const eveningWallClock = new Date(
    zonedNow.getFullYear(), zonedNow.getMonth(), zonedNow.getDate(), EVENING_HOUR, 0, 0
  );
  const evening = fromZonedTime(eveningWallClock, timezone);
  if (evening.getTime() > now.getTime()) {
    presets.push({ key: 'evening', label: 'Сегодня вечером', fireAt: evening });
  }

  const tomorrowZoned = addDays(zonedNow, 1);
  const morningWallClock = new Date(
    tomorrowZoned.getFullYear(), tomorrowZoned.getMonth(), tomorrowZoned.getDate(), MORNING_HOUR, 0, 0
  );
  presets.push({ key: 'morning', label: 'Завтра утром', fireAt: fromZonedTime(morningWallClock, timezone) });

  return presets;
}

/** Прошедшее время не принимаем и в ручном выборе (Б2). */
export function isPastReminderTime(fireAt: Date, now: Date = new Date()): boolean {
  return fireAt.getTime() <= now.getTime();
}

// d.getFullYear()/getHours() и т.п. здесь — не браузерный локальный час:
// toZonedTime подменяет представление даты так, что стандартные геттеры
// отдают компоненты времени в переданном поясе, а не в поясе устройства.
export function isoToZonedDate(iso: string | undefined, timezone: string): string {
  if (!iso) return '';
  const d = toZonedTime(iso, timezone);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

export function isoToZonedTime(iso: string | undefined, timezone: string): string {
  if (!iso) return '';
  const d = toZonedTime(iso, timezone);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

// Обратное преобразование: дата/время, введённые пользователем как есть
// (без указания пояса), интерпретируются как момент в его домашнем поясе.
export function zonedInputToIso(date: string, time: string, timezone: string): string {
  const [year, month, day] = date.split('-').map(Number);
  const [hour, minute] = time.split(':').map(Number);
  const wallClock = new Date(year, month - 1, day, hour, minute);
  return fromZonedTime(wallClock, timezone).toISOString();
}
