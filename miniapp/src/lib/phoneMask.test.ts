import { describe, expect, it } from 'vitest';
import { formatPhoneInput } from './phoneMask';

describe('formatPhoneInput', () => {
  it.each([
    ['89205059543', '+7 (920) 505-95-43'],
    ['+79205059543', '+7 (920) 505-95-43'],
    ['79205059543', '+7 (920) 505-95-43'],
    ['9205059543', '+7 (920) 505-95-43'],
    ['+7 (920) 505-95-43', '+7 (920) 505-95-43'],
  ])('formatPhoneInput(%s) -> %s', (raw, expected) => {
    expect(formatPhoneInput(raw)).toBe(expected);
  });

  it('formats progressively as digits accumulate', () => {
    expect(formatPhoneInput('8')).toBe('+7');
    expect(formatPhoneInput('892')).toBe('+7 (92');
    expect(formatPhoneInput('8920')).toBe('+7 (920) ');
    expect(formatPhoneInput('892050')).toBe('+7 (920) 50');
  });

  it('returns empty string for empty input', () => {
    expect(formatPhoneInput('')).toBe('');
  });
});
