import { useState } from 'react';
import { useTheme } from 'next-themes';
import { Card } from '@/app/components/ui/card';
import { Label } from '@/app/components/ui/label';
import { Switch } from '@/app/components/ui/switch';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/app/components/ui/select';
import { Button } from '@/app/components/ui/button';
import { Separator } from '@/app/components/ui/separator';
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from '@/app/components/ui/alert-dialog';
import { useStore } from '@/lib/store';
import { useSettings, useUpdateSettings, useClearCompleted, VoiceInputMode } from '@/lib/hooks/useSettings';
import { isTouchDevice } from '@/lib/device';

function useSetting(key: string, defaultValue: string): [string, (v: string) => void] {
  const stored = localStorage.getItem(key) ?? defaultValue;
  const set = (v: string) => localStorage.setItem(key, v);
  return [stored, set];
}

function useBoolSetting(key: string, defaultValue: boolean): [boolean, (v: boolean) => void] {
  const stored = localStorage.getItem(key);
  const value = stored === null ? defaultValue : stored === 'true';
  const set = (v: boolean) => localStorage.setItem(key, String(v));
  return [value, set];
}

const AUTO_CLEAN_OPTIONS = [
  { value: 'off', label: 'Не удалять автоматически', days: null },
  { value: '7', label: 'Через 7 дней', days: 7 },
  { value: '14', label: 'Через 2 недели', days: 14 },
  { value: '30', label: 'Через месяц', days: 30 },
  { value: '90', label: 'Через 3 месяца', days: 90 },
];

const VOICE_MODE_DESKTOP_OPTIONS: { value: VoiceInputMode; label: string }[] = [
  { value: 'SILENCE', label: 'Останавливать по тишине' },
  { value: 'TOGGLE', label: 'Нажать — начать, нажать — отправить' },
];

const VOICE_MODE_MOBILE_OPTIONS: { value: VoiceInputMode; label: string }[] = [
  { value: 'SILENCE', label: 'Останавливать по тишине' },
  { value: 'TOGGLE', label: 'Нажать — начать, нажать — отправить' },
  { value: 'HOLD', label: 'Удерживать, пока говоришь' },
];

export function SettingsPage() {
  const { theme, setTheme } = useTheme();
  const user = useStore((s) => s.user);
  const logout = useStore((s) => s.logout);

  const [timezone, setTimezone] = useSetting('settings.timezone', 'europe-moscow');
  const [notifications, setNotifications] = useBoolSetting('settings.notifications', true);
  const [reminderTime, setReminderTime] = useSetting('settings.reminderTime', '1h');
  const [urgentExtra, setUrgentExtra] = useBoolSetting('settings.urgentExtra', true);

  const { data: serverSettings } = useSettings();
  const updateSettings = useUpdateSettings();
  const clearCompleted = useClearCompleted();
  const [clearResult, setClearResult] = useState<number | null>(null);

  const currentAutoClean = serverSettings?.autoCleanCompletedDays
    ? String(serverSettings.autoCleanCompletedDays)
    : 'off';

  const handleAutoCleanChange = (value: string) => {
    const option = AUTO_CLEAN_OPTIONS.find((o) => o.value === value);
    if (!option) return;
    updateSettings.mutate({ autoCleanCompletedDays: option.days });
  };

  const touchDevice = isTouchDevice();
  const currentVoiceModeDesktop = serverSettings?.voiceInputModeDesktop ?? 'SILENCE';
  const currentVoiceModeMobile = serverSettings?.voiceInputModeMobile ?? 'SILENCE';

  const handleVoiceModeDesktopChange = (value: string) => {
    updateSettings.mutate({ voiceInputModeDesktop: value as VoiceInputMode });
  };

  const handleVoiceModeMobileChange = (value: string) => {
    updateSettings.mutate({ voiceInputModeMobile: value as VoiceInputMode });
  };

  const handleClearCompleted = () => {
    clearCompleted.mutate(undefined, {
      onSuccess: (data) => setClearResult(data.cleared),
    });
  };

  return (
    <div className="max-w-2xl mx-auto space-y-6">
      <h1 className="text-2xl font-semibold">Настройки</h1>

      <Card className="p-6 flex flex-col gap-8">
        <section className="flex flex-col gap-4">
          <h2 className="text-base font-semibold">Профиль</h2>
          {user ? (
            <div className="flex items-center gap-3 p-3 rounded-xl bg-muted/50">
              <div className="w-10 h-10 rounded-full bg-primary/20 flex items-center justify-center text-sm font-semibold text-primary flex-shrink-0">
                {(user.name || user.username || '?').charAt(0).toUpperCase()}
              </div>
              <div className="flex flex-col gap-0.5">
                <p className="font-medium text-sm leading-tight">{user.name || user.username}</p>
                {user.username && user.username !== user.name && (
                  <p className="text-xs text-muted-foreground leading-tight">@{user.username} · Telegram</p>
                )}
              </div>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">Профиль не загружен</p>
          )}
          <div className="flex flex-col gap-2">
            <Label htmlFor="timezone">Часовой пояс</Label>
            <Select value={timezone} onValueChange={setTimezone}>
              <SelectTrigger id="timezone" className="h-10">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="europe-moscow">Europe/Moscow (UTC+3)</SelectItem>
                <SelectItem value="europe-kaliningrad">Europe/Kaliningrad (UTC+2)</SelectItem>
                <SelectItem value="asia-yekaterinburg">Asia/Yekaterinburg (UTC+5)</SelectItem>
                <SelectItem value="asia-novosibirsk">Asia/Novosibirsk (UTC+7)</SelectItem>
                <SelectItem value="asia-vladivostok">Asia/Vladivostok (UTC+10)</SelectItem>
                <SelectItem value="utc">UTC</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </section>

        <Separator />

        <section className="flex flex-col gap-5">
          <h2 className="text-base font-semibold">Напоминания</h2>
          <div className="flex items-center justify-between gap-4">
            <div className="flex flex-col gap-1">
              <Label htmlFor="notifications">Включить напоминания</Label>
              <p className="text-xs text-muted-foreground">
                Уведомления о задачах с дедлайнами
              </p>
            </div>
            <Switch
              id="notifications"
              checked={notifications}
              onCheckedChange={setNotifications}
            />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="reminder-time">Время напоминания</Label>
            <Select value={reminderTime} onValueChange={setReminderTime} disabled={!notifications}>
              <SelectTrigger id="reminder-time" className="h-10">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="15m">За 15 минут</SelectItem>
                <SelectItem value="30m">За 30 минут</SelectItem>
                <SelectItem value="1h">За 1 час</SelectItem>
                <SelectItem value="2h">За 2 часа</SelectItem>
                <SelectItem value="1d">За день</SelectItem>
              </SelectContent>
            </Select>
          </div>
          <div className="flex items-center justify-between gap-4">
            <div className="flex flex-col gap-1">
              <Label htmlFor="urgent-reminder">Доп. за 15 мин для срочных</Label>
              <p className="text-xs text-muted-foreground">
                Дополнительное напоминание для срочных задач
              </p>
            </div>
            <Switch
              id="urgent-reminder"
              checked={urgentExtra}
              onCheckedChange={setUrgentExtra}
              disabled={!notifications}
            />
          </div>
        </section>

        <Separator />

        <section className="flex flex-col gap-4">
          <h2 className="text-base font-semibold">AI-разбор</h2>
          <div className="flex flex-col gap-2">
            <p className="text-sm text-muted-foreground">
              Используется <span className="font-medium text-foreground">Groq (llama3)</span> с автоматическим переключением на YandexGPT при недоступности. Настраивается в переменных окружения сервера.
            </p>
          </div>
        </section>

        <Separator />

        <section className="flex flex-col gap-4">
          <h2 className="text-base font-semibold">Голосовой ввод</h2>
          <div className="flex flex-col gap-2">
            <Label htmlFor="voice-mode-desktop">На компьютере</Label>
            <Select
              value={currentVoiceModeDesktop}
              onValueChange={handleVoiceModeDesktopChange}
              disabled={updateSettings.isPending}
            >
              <SelectTrigger id="voice-mode-desktop" className="h-10">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {VOICE_MODE_DESKTOP_OPTIONS.map((o) => (
                  <SelectItem key={o.value} value={o.value}>
                    {o.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="voice-mode-mobile">На телефоне{touchDevice ? ' (это устройство)' : ''}</Label>
            <Select
              value={currentVoiceModeMobile}
              onValueChange={handleVoiceModeMobileChange}
              disabled={updateSettings.isPending}
            >
              <SelectTrigger id="voice-mode-mobile" className="h-10">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {VOICE_MODE_MOBILE_OPTIONS.map((o) => (
                  <SelectItem key={o.value} value={o.value}>
                    {o.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </section>

        <Separator />

        <section className="flex flex-col gap-4">
          <h2 className="text-base font-semibold">Внешний вид</h2>
          <div className="flex flex-col gap-2">
            <Label>Тема</Label>
            <div className="flex gap-2">
              <Button
                variant={theme === 'light' ? 'default' : 'outline'}
                onClick={() => setTheme('light')}
                className="flex-1 h-10"
              >
                Светлая
              </Button>
              <Button
                variant={theme === 'dark' ? 'default' : 'outline'}
                onClick={() => setTheme('dark')}
                className="flex-1 h-10"
              >
                Тёмная
              </Button>
              <Button
                variant={theme === 'system' ? 'default' : 'outline'}
                onClick={() => setTheme('system')}
                className="flex-1 h-10"
              >
                Системная
              </Button>
            </div>
          </div>
        </section>

        <Separator />

        <section className="flex flex-col gap-5">
          <h2 className="text-base font-semibold">Задачи</h2>

          <div className="flex flex-col gap-2">
            <Label htmlFor="auto-clean">Авто-удаление выполненных</Label>
            <p className="text-xs text-muted-foreground">
              Выполненные и отклонённые задачи скрываются через указанный срок. Системное удаление из БД — через 3 месяца.
            </p>
            <Select
              value={currentAutoClean}
              onValueChange={handleAutoCleanChange}
              disabled={updateSettings.isPending}
            >
              <SelectTrigger id="auto-clean" className="h-10">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {AUTO_CLEAN_OPTIONS.map((o) => (
                  <SelectItem key={o.value} value={o.value}>
                    {o.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="flex flex-col gap-2">
            <Label>Очистить прямо сейчас</Label>
            <p className="text-xs text-muted-foreground">
              Скрывает все выполненные и отклонённые задачи немедленно.
            </p>
            {clearResult !== null && (
              <p className="text-xs text-green-600">
                Скрыто задач: {clearResult}
              </p>
            )}
            <AlertDialog>
              <AlertDialogTrigger asChild>
                <Button
                  variant="outline"
                  className="h-10 self-start"
                  disabled={clearCompleted.isPending}
                >
                  {clearCompleted.isPending ? 'Очищаем...' : 'Очистить выполненные'}
                </Button>
              </AlertDialogTrigger>
              <AlertDialogContent>
                <AlertDialogHeader>
                  <AlertDialogTitle>Очистить выполненные задачи?</AlertDialogTitle>
                  <AlertDialogDescription>
                    Все задачи со статусом «Выполнено» и «Отклонено» будут скрыты из списка.
                    Они окончательно удалятся через 3 месяца.
                  </AlertDialogDescription>
                </AlertDialogHeader>
                <AlertDialogFooter>
                  <AlertDialogCancel>Отмена</AlertDialogCancel>
                  <AlertDialogAction onClick={handleClearCompleted}>
                    Очистить
                  </AlertDialogAction>
                </AlertDialogFooter>
              </AlertDialogContent>
            </AlertDialog>
          </div>
        </section>

        <Separator />

        <section className="flex flex-col gap-4">
          <h2 className="text-base font-semibold">Аккаунт</h2>
          <Button variant="destructive" onClick={logout} className="h-10 self-start">
            Выйти из аккаунта
          </Button>
        </section>
      </Card>
    </div>
  );
}
