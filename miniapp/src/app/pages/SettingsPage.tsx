import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTheme } from 'next-themes';
import { Card } from '@/app/components/ui/card';
import { Label } from '@/app/components/ui/label';
import { Input } from '@/app/components/ui/input';
import { Switch } from '@/app/components/ui/switch';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/app/components/ui/select';
import { Button, buttonVariants } from '@/app/components/ui/button';
import { Separator } from '@/app/components/ui/separator';
import { TimezonePicker } from '@/app/components/TimezonePicker';
import { cn } from '@/lib/utils';
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
import { useSettings, useUpdateSettings, useClearCompleted, useDisplayName, VoiceInputMode } from '@/lib/hooks/useSettings';
import { useIdentities } from '@/lib/hooks/useIdentities';
import { useInstallPrompt } from '@/lib/hooks/useInstallPrompt';
import { usePushSubscription } from '@/lib/hooks/usePushSubscription';
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
  const profileDisplayName = useDisplayName();

  const [reminderTime, setReminderTime] = useSetting('settings.reminderTime', '1h');
  const [urgentExtra, setUrgentExtra] = useBoolSetting('settings.urgentExtra', true);

  const { data: serverSettings } = useSettings();
  const { data: identities } = useIdentities();
  const { canInstall, promptInstall } = useInstallPrompt();
  const { permission: pushPermission, subscribe: subscribeToPush, unsubscribe: unsubscribeFromPush } = usePushSubscription();
  const updateSettings = useUpdateSettings();
  const clearCompleted = useClearCompleted();
  const [clearResult, setClearResult] = useState<number | null>(null);

  // Общий выключатель — настоящее серверное поле, не localStorage: от него
  // зависит, шлёт ли notification-worker хоть что-то (см. NotificationServiceImpl).
  const notificationsEnabled = serverSettings?.notificationsEnabled ?? true;
  const handleNotificationsToggle = (v: boolean) => updateSettings.mutate({ notificationsEnabled: v });

  const hasTelegram = identities?.some((i) => i.provider === 'TELEGRAM') ?? false;
  const hasEmail = identities?.some((i) => i.provider === 'EMAIL') ?? false;
  const notifyTelegram = serverSettings?.notifyTelegram ?? true;
  const notifyEmail = serverSettings?.notifyEmail ?? true;
  const notifyPush = serverSettings?.notifyPush ?? true;
  const handleNotifyTelegramToggle = (v: boolean) => updateSettings.mutate({ notifyTelegram: v });
  const handleNotifyEmailToggle = (v: boolean) => updateSettings.mutate({ notifyEmail: v });

  // «Запрещено в браузере» — состояние, которое мы не можем починить кнопкой:
  // человек сам отказал в разрешении, вернуть его может только он сам в
  // настройках браузера. Отдельно от «выключено у нас», иначе непонятно,
  // почему переключатель не поддаётся.
  const pushBlockedByBrowser = pushPermission === 'denied';
  const pushUnsupported = pushPermission === 'unsupported';
  const handleNotifyPushToggle = async (v: boolean) => {
    if (v) {
      // Разрешение браузера спрашиваем только по этому нажатию — не раньше.
      const granted = await subscribeToPush();
      if (!granted) return;
      updateSettings.mutate({ notifyPush: true });
    } else {
      await unsubscribeFromPush();
      updateSettings.mutate({ notifyPush: false });
    }
  };

  const currentAutoClean = serverSettings?.autoCleanCompletedDays
    ? String(serverSettings.autoCleanCompletedDays)
    : 'off';

  const handleAutoCleanChange = (value: string) => {
    const option = AUTO_CLEAN_OPTIONS.find((o) => o.value === value);
    if (!option) return;
    updateSettings.mutate({ autoCleanCompletedDays: option.days });
  };

  const currentTimezone = serverSettings?.timezone ?? 'Europe/Moscow';

  const handleTimezoneChange = (value: string) => {
    updateSettings.mutate({ timezone: value });
  };

  // Локальная копия синхронизируется с сервером при загрузке/перезагрузке
  // настроек, но дальше живёт своей жизнью — иначе набор текста дёргался бы
  // назад к сохранённому значению между запросами.
  const [displayName, setDisplayName] = useState('');
  const [displayNameError, setDisplayNameError] = useState<string | null>(null);
  useEffect(() => {
    if (serverSettings?.displayName != null) setDisplayName(serverSettings.displayName);
  }, [serverSettings?.displayName]);

  const savedDisplayName = serverSettings?.displayName ?? '';
  const trimmedDisplayName = displayName.trim();
  const displayNameDirty = trimmedDisplayName.length > 0 && trimmedDisplayName !== savedDisplayName;

  const handleSaveDisplayName = () => {
    if (!trimmedDisplayName) {
      setDisplayNameError('Имя не может быть пустым');
      return;
    }
    if (trimmedDisplayName.length > 64) {
      setDisplayNameError('Слишком длинное имя (максимум 64 символа)');
      return;
    }
    setDisplayNameError(null);
    // Zustand-синхронизацию сюда больше не добавляем: displayName теперь
    // читается с сервера (useDisplayName), а setQueryData внутри
    // useUpdateSettings уже обновил кэш настроек — этого достаточно.
    updateSettings.mutate({ displayName: trimmedDisplayName });
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
                {profileDisplayName.charAt(0).toUpperCase()}
              </div>
              <div className="flex flex-col gap-0.5">
                <p className="font-medium text-sm leading-tight">{profileDisplayName}</p>
                {user.username && user.username !== profileDisplayName && (
                  <p className="text-xs text-muted-foreground leading-tight">@{user.username} · Telegram</p>
                )}
              </div>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">Профиль не загружен</p>
          )}
          <div className="flex flex-col gap-2">
            <Label htmlFor="display-name">Отображаемое имя</Label>
            <div className="flex gap-2">
              <Input
                id="display-name"
                value={displayName}
                onChange={(e) => {
                  setDisplayName(e.target.value);
                  setDisplayNameError(null);
                }}
                disabled={updateSettings.isPending}
                maxLength={64}
                className="h-10"
              />
              <Button
                variant="outline"
                onClick={handleSaveDisplayName}
                disabled={updateSettings.isPending || !displayNameDirty}
                className="h-10 shrink-0"
              >
                Сохранить
              </Button>
            </div>
            {displayNameError && <p className="text-xs text-red-500">{displayNameError}</p>}
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="timezone">Часовой пояс</Label>
            <TimezonePicker
              id="timezone"
              value={currentTimezone}
              onChange={handleTimezoneChange}
              disabled={updateSettings.isPending}
            />
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
              checked={notificationsEnabled}
              onCheckedChange={handleNotificationsToggle}
              disabled={updateSettings.isPending}
            />
          </div>

          <div className="flex flex-col gap-3 pl-1 border-l-2 border-muted ml-1">
            <div className="flex items-center justify-between gap-4 pl-3">
              <div className="flex flex-col gap-1 min-w-0">
                <Label htmlFor="notify-telegram">Telegram</Label>
                {!hasTelegram && (
                  <p className="text-xs text-muted-foreground">
                    Telegram не подключён —{' '}
                    <Link to="/integrations" className="underline">
                      подключить в интеграциях
                    </Link>
                  </p>
                )}
              </div>
              <Switch
                id="notify-telegram"
                checked={hasTelegram && notifyTelegram}
                onCheckedChange={handleNotifyTelegramToggle}
                disabled={!hasTelegram || !notificationsEnabled || updateSettings.isPending}
              />
            </div>
            <div className="flex items-center justify-between gap-4 pl-3">
              <div className="flex flex-col gap-1 min-w-0">
                <Label htmlFor="notify-email">Почта</Label>
                {!hasEmail && (
                  <p className="text-xs text-muted-foreground">
                    Почта не подключена —{' '}
                    <Link to="/integrations" className="underline">
                      подключить в интеграциях
                    </Link>
                  </p>
                )}
              </div>
              <Switch
                id="notify-email"
                checked={hasEmail && notifyEmail}
                onCheckedChange={handleNotifyEmailToggle}
                disabled={!hasEmail || !notificationsEnabled || updateSettings.isPending}
              />
            </div>
            {!pushUnsupported && (
              <div className="flex items-center justify-between gap-4 pl-3">
                <div className="flex flex-col gap-1 min-w-0">
                  <Label htmlFor="notify-push">Уведомления в браузере</Label>
                  {pushBlockedByBrowser && (
                    <p className="text-xs text-muted-foreground">
                      Заблокированы в браузере — включить можно только в его настройках, у нас переключателя для этого нет.
                    </p>
                  )}
                </div>
                <Switch
                  id="notify-push"
                  checked={notifyPush && !pushBlockedByBrowser}
                  onCheckedChange={handleNotifyPushToggle}
                  disabled={pushBlockedByBrowser || !notificationsEnabled || updateSettings.isPending}
                />
              </div>
            )}
          </div>

          <div className="flex flex-col gap-2">
            <Label htmlFor="reminder-time">Время напоминания</Label>
            <Select value={reminderTime} onValueChange={setReminderTime} disabled={!notificationsEnabled}>
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
              disabled={!notificationsEnabled}
            />
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
                Темная
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

        {canInstall && (
          <>
            <Separator />

            <section className="flex flex-col gap-4">
              <h2 className="text-base font-semibold">Приложение</h2>
              <div className="flex flex-col gap-2">
                <p className="text-xs text-muted-foreground">
                  Установите Мунин как приложение — свой значок, отдельное окно, без адресной строки.
                </p>
                <Button variant="outline" onClick={promptInstall} className="h-10 self-start">
                  Установить приложение
                </Button>
              </div>
            </section>
          </>
        )}

        <Separator />

        <section className="flex flex-col gap-5">
          <h2 className="text-base font-semibold">Задачи</h2>

          <div className="flex flex-col gap-2">
            <Label htmlFor="auto-clean">Авто-удаление выполненных</Label>
            <p className="text-xs text-muted-foreground">
              Выполненные и отклоненные задачи скрываются через указанный срок. Системное удаление из БД — через 3 месяца.
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
              Скрывает все выполненные и отклоненные задачи немедленно.
            </p>
            {clearResult !== null && (
              <p className="text-xs text-green-600">
                Скрыто задач: {clearResult}
              </p>
            )}
            <AlertDialog>
              {/* Без asChild: Button — обычная функция без forwardRef, и Slot не может
                  привязать к ней ref. Диалог открывался бы всё равно (onClick подставляется
                  через проброс пропсов), но фокус не возвращался бы на кнопку при закрытии.
                  Тот же приём, что у AlertDialogAction/Cancel — стили buttonVariants
                  напрямую на триггере, без обёртки. */}
              <AlertDialogTrigger
                className={cn(buttonVariants({ variant: 'outline' }), 'h-10 self-start')}
                disabled={clearCompleted.isPending}
              >
                {clearCompleted.isPending ? 'Очищаем...' : 'Очистить выполненные'}
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
