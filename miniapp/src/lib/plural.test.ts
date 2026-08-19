import { describe, expect, it } from 'vitest';
import { plural } from './plural';

const forms: [string, string, string] = ['задача', 'задачи', 'задач'];

describe('plural', () => {
  it.each([
    [0, 'задач'],
    [1, 'задача'],
    [2, 'задачи'],
    [4, 'задачи'],
    [5, 'задач'],
    [11, 'задач'],
    [21, 'задача'],
    [111, 'задач'],
  ])('plural(%i) -> %s', (n, expected) => {
    expect(plural(n, forms)).toBe(expected);
  });
});
