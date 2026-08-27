import { describe, expect, it } from 'vitest';
import { buildReminderPresets, isPastReminderTime } from './reminderPresets';

describe('buildReminderPresets', () => {
  it('"через час" всегда на час позже переданного момента', () => {
    const now = new Date('2026-08-27T10:00:00Z');
    const presets = buildReminderPresets(now, 'Europe/Moscow');
    const hour = presets.find((p) => p.key === 'hour')!;
    expect(hour.fireAt.getTime()).toBe(now.getTime() + 60 * 60 * 1000);
  });

  it('не предлагает «сегодня вечером», если вечерний час уже прошёл', () => {
    const now = new Date('2026-08-27T19:00:00Z'); // 22:00 по Москве
    const presets = buildReminderPresets(now, 'Europe/Moscow');
    expect(presets.find((p) => p.key === 'evening')).toBeUndefined();
  });

  it('предлагает «сегодня вечером», если вечерний час ещё впереди', () => {
    const now = new Date('2026-08-27T06:00:00Z'); // 09:00 по Москве
    const presets = buildReminderPresets(now, 'Europe/Moscow');
    expect(presets.find((p) => p.key === 'evening')).toBeDefined();
  });

  it('«завтра утром» присутствует всегда и позже текущего момента', () => {
    const now = new Date('2026-08-27T23:50:00Z');
    const presets = buildReminderPresets(now, 'Europe/Moscow');
    const morning = presets.find((p) => p.key === 'morning');
    expect(morning).toBeDefined();
    expect(morning!.fireAt.getTime()).toBeGreaterThan(now.getTime());
  });
});

describe('isPastReminderTime', () => {
  it('отклоняет прошедшее время', () => {
    const now = new Date('2026-08-27T10:00:00Z');
    expect(isPastReminderTime(new Date('2026-08-27T09:00:00Z'), now)).toBe(true);
  });

  it('принимает будущее время', () => {
    const now = new Date('2026-08-27T10:00:00Z');
    expect(isPastReminderTime(new Date('2026-08-27T11:00:00Z'), now)).toBe(false);
  });
});
