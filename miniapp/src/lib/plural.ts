// Русское склонение числительных: 11-14 всегда родительный множественного,
// дальше выбор идёт по последней цифре.
export function plural(n: number, forms: [one: string, few: string, many: string]): string {
  const mod10 = Math.abs(n) % 10;
  const mod100 = Math.abs(n) % 100;
  if (mod100 >= 11 && mod100 <= 14) return forms[2];
  if (mod10 === 1) return forms[0];
  if (mod10 >= 2 && mod10 <= 4) return forms[1];
  return forms[2];
}
