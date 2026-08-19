// navigator.platform устарел, но остаётся рабочим везде; userAgentData.platform
// новее и точнее там, где есть (Chromium), поэтому пробуем его первым.
export function isApplePlatform(): boolean {
  const uaDataPlatform = (navigator as Navigator & { userAgentData?: { platform?: string } })
    .userAgentData?.platform;
  const platform = uaDataPlatform ?? navigator.platform;
  return /mac|iphone|ipad|ipod/i.test(platform);
}

const APPLE_KEY_SYMBOLS: Record<string, string> = {
  Enter: '↵',
  Shift: '⇧',
  Alt: '⌥',
};

// На Apple — привычная запись символами (⌘↵). На остальных платформах символ
// клавиши без подписи читается как опечатка, поэтому там — слова (Ctrl+Enter).
export function shortcutLabel(key: string): string {
  if (isApplePlatform()) {
    return `⌘${APPLE_KEY_SYMBOLS[key] ?? key}`;
  }
  return `Ctrl+${key}`;
}
