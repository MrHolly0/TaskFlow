import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';
import { toZonedTime, fromZonedTime } from 'date-fns-tz';
import { isSameDay, addDays, format } from 'date-fns';
import { Priority } from './store';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/** Тот же календарный день в указанном поясе — не в поясе браузера. */
export function isSameZonedDay(a: Date | string, b: Date | string, timezone: string): boolean {
  return isSameDay(toZonedTime(a, timezone), toZonedTime(b, timezone));
}

/** Ключ календарного дня (yyyy-MM-dd) в указанном поясе — для группировки/подсчёта уникальных дней. */
export function zonedDayKey(date: Date | string, timezone: string): string {
  return format(toZonedTime(date, timezone), 'yyyy-MM-dd');
}

/**
 * Конец календарного дня (23:59:59), отстоящего на daysFromNow дней от `now`,
 * посчитанный в указанном поясе, возвращённый как настоящий момент времени (UTC-инстант).
 */
export function endOfZonedDay(daysFromNow: number, timezone: string, now: Date = new Date()): Date {
  const zonedNow = toZonedTime(now, timezone);
  const zonedTarget = addDays(zonedNow, daysFromNow);
  const wallClockEnd = new Date(
    zonedTarget.getFullYear(),
    zonedTarget.getMonth(),
    zonedTarget.getDate(),
    23, 59, 59
  );
  return fromZonedTime(wallClockEnd, timezone);
}

export function formatDeadline(deadline: string, timezone: string): string {
  const date = new Date(deadline);
  const now = new Date();
  const diff = date.getTime() - now.getTime();

  const hours = Math.floor(diff / (1000 * 60 * 60));
  const days = Math.floor(hours / 24);

  if (hours < 0) {
    const absHours = Math.abs(hours);
    const absDays = Math.floor(absHours / 24);
    if (absDays > 0) {
      return `Просрочено на ${absDays} ${getDaysWord(absDays)}`;
    }
    return absHours > 0
      ? `Просрочено на ${absHours} ${getHoursWord(absHours)}`
      : 'Просрочено';
  }

  if (hours < 1) {
    const minutes = Math.floor(diff / (1000 * 60));
    return `через ${minutes} мин`;
  }

  if (hours < 24) {
    return `через ${hours} ${getHoursWord(hours)}`;
  }

  if (days === 1) {
    return 'Завтра';
  }

  if (days < 7) {
    return `Через ${days} ${getDaysWord(days)}`;
  }

  return date.toLocaleDateString('ru-RU', {
    day: 'numeric',
    month: 'long',
    timeZone: timezone,
  });
}

function getHoursWord(hours: number): string {
  if (hours % 10 === 1 && hours % 100 !== 11) return 'час';
  if ([2, 3, 4].includes(hours % 10) && ![12, 13, 14].includes(hours % 100)) return 'часа';
  return 'часов';
}

function getDaysWord(days: number): string {
  if (days % 10 === 1 && days % 100 !== 11) return 'день';
  if ([2, 3, 4].includes(days % 10) && ![12, 13, 14].includes(days % 100)) return 'дня';
  return 'дней';
}

export function getPriorityColor(priority: Priority): string {
  const colors = {
    LOW: 'text-muted-foreground',
    MEDIUM: 'text-blue-500',
    HIGH: 'text-orange-500',
    URGENT: 'text-red-500',
  };
  return colors[priority];
}

export function getPriorityBgColor(priority: Priority): string {
  const colors = {
    LOW: 'border-l-4 border-l-gray-300 dark:border-l-gray-600',
    MEDIUM: 'border-l-4 border-l-blue-500',
    HIGH: 'border-l-4 border-l-orange-500',
    URGENT: 'border-l-4 border-l-red-500',
  };
  return colors[priority];
}

