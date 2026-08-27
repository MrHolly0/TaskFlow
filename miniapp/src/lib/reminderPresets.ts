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
